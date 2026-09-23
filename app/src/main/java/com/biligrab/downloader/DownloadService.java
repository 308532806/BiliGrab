package com.biligrab.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台下载服务：解析 → 下载 DASH 音视频流 → MediaMuxer 合成 → 写入下载目录。
 *
 * <h3>任务是怎么组织的</h3>
 * <p>服务持有一个待执行队列，单线程顺序消费。每个任务有自己独占的工作目录
 * （{@link WorkDir}），半成品文件在暂停后原样保留，继续时接着写。任务本身
 * 是 {@link DownloadTask} 对象，落在 {@link TaskStore} 里，进程重启后还在。</p>
 *
 * <h3>进度为什么按字节算</h3>
 * <p>原来的进度是「阶段映射」：视频 5→50%、音频 50→85%、合成 88…… 那些百分比
 * 与真实数据量无关，只是给每一段预先切好一条区间。它在总量已知时毫无意义，
 * 在总量未知时更糟 —— 实测代理下的 googlevideo 会走 chunked 响应，不给
 * {@code Content-Length}，于是「下载视频流」整段时间里进度条一动不动，
 * 而用户从系统流量监控里明明能看到速度。</p>
 *
 * <p>现在进度按**真实字节数**算：总量取自响应头，响应头没给就退回解析阶段
 * 已经知道的体积（{@code task.videoSize} / {@code audioSize}）。两者都没有时
 * 才显示为「不确定」，而不是假装 0%。</p>
 */
public class DownloadService extends Service {

    private static final String TAG = "BiliGrab";

    public static final String ACTION_ENQUEUE = "com.biligrab.app.action.ENQUEUE";
    public static final String ACTION_PAUSE = "com.biligrab.app.action.PAUSE";
    public static final String ACTION_CANCEL = "com.biligrab.app.action.CANCEL";
    public static final String EXTRA_TASK_ID = "taskId";

    private static final String CHANNEL_ID = "biligrab_download";
    private static final int NOTIF_ID = 0x2101;

    /** 同一个进程里只有一个服务实例。静态持有是为了让「暂停」不必拉起服务。 */
    private static volatile DownloadService INSTANCE;

    private ExecutorService executor;
    private NotificationManager notifMgr;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean workerRunning;
    private volatile String currentId = "";

    // ------------------------------------------------------------------
    // 进度观察者（UI 与服务在同一进程，直接用静态列表回调即可）
    // ------------------------------------------------------------------

    /** 下载进度监听。**回调来自后台线程**，界面自己 post 回主线程。 */
    public interface Listener {
        /** 某条任务的进度或状态变了。 */
        void onTaskProgress(DownloadTask task);

        /** 某条任务结束了（完成 / 失败 / 暂停 / 取消）。 */
        void onTaskFinished(DownloadTask task, boolean ok, String message);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void emitProgress(DownloadTask t) {
        for (Listener l : LISTENERS) {
            try {
                l.onTaskProgress(t);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener error", e);
            }
        }
    }

    private static void emitFinished(DownloadTask t, boolean ok, String message) {
        for (Listener l : LISTENERS) {
            try {
                l.onTaskFinished(t, ok, message);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener error", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外的控制入口
    // ------------------------------------------------------------------

    /** 新建一条下载并立刻交给服务。 */
    public static void start(Context ctx, DownloadTask t) {
        TaskStore store = TaskStore.get();
        store.ensureLoaded(ctx);
        if (store.byId(t.id) == null) {
            store.add(ctx, t);
        }
        t.resetStopFlags();
        t.setStatus(DownloadTask.STATUS_QUEUED);
        store.markDirty(ctx);
        enqueue(ctx, t.id);
    }

    /** 继续一条暂停或失败的下载。 */
    public static void resume(Context ctx, DownloadTask t) {
        if (t == null || t.isActive()) {
            return;
        }
        t.resetStopFlags();
        t.setError("");
        t.setStatus(DownloadTask.STATUS_QUEUED);
        TaskStore.get().markDirty(ctx);
        enqueue(ctx, t.id);
    }

    private static void enqueue(Context ctx, String id) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_ENQUEUE);
        i.putExtra(EXTRA_TASK_ID, id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /**
     * 暂停一条下载。
     *
     * <p>服务没在跑时直接改状态就行 —— 那种情况下没有任何线程在工作，
     * 为了一次状态改动去启动一个前台服务是没必要的。</p>
     */
    public static void pause(Context ctx, DownloadTask t) {
        if (t == null) {
            return;
        }
        DownloadService s = INSTANCE;
        if (s != null && t.id.equals(s.currentId)) {
            // 正在下：让下载循环自己停下来，它才知道该收尾什么
            t.requestPause();
            return;
        }
        t.setStatus(DownloadTask.STATUS_PAUSED);
        TaskStore.get().markDirty(ctx);
        TaskStore.get().notifyProgress();
    }

    /** 取消一条下载：停掉并把半成品删掉。 */
    public static void cancel(Context ctx, DownloadTask t) {
        if (t == null) {
            return;
        }
        DownloadService s = INSTANCE;
        if (s != null && t.id.equals(s.currentId)) {
            t.requestCancel();
            return;
        }
        s = INSTANCE;
        if (s != null) {
            s.queue.remove(t.id);
        }
        t.setStatus(DownloadTask.STATUS_CANCELLED);
        WorkDir.delete(ctx, t.id);
        TaskStore.get().markDirty(ctx);
        TaskStore.get().notifyProgress();
    }

    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        executor = Executors.newSingleThreadExecutor();
        notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        TaskStore.get().ensureLoaded(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        String id = str(intent, EXTRA_TASK_ID);
        TaskStore store = TaskStore.get();
        store.ensureLoaded(this);

        if (ACTION_PAUSE.equals(action)) {
            DownloadTask t = store.byId(id);
            if (t != null && id.equals(currentId)) {
                t.requestPause();
            } else if (t != null) {
                t.setStatus(DownloadTask.STATUS_PAUSED);
                store.markDirty(this);
                store.notifyProgress();
            }
            if (currentId.isEmpty()) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            DownloadTask t = store.byId(id);
            if (t != null && id.equals(currentId)) {
                t.requestCancel();
            } else if (t != null) {
                queue.remove(id);
                t.setStatus(DownloadTask.STATUS_CANCELLED);
                WorkDir.delete(this, id);
                store.markDirty(this);
                store.notifyProgress();
            }
            if (currentId.isEmpty()) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        if (!ACTION_ENQUEUE.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        DownloadTask t = store.byId(id);
        if (t == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // 必须在 5 秒内调用 startForeground，所以放在入队之前
        startForegroundCompat(getString(R.string.stage_preparing), t.title, 0, t);

        if (!queue.contains(id) && !id.equals(currentId)) {
            t.setStatus(DownloadTask.STATUS_QUEUED);
            queue.add(id);
            emitProgress(t);
        }
        ensureWorker();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // 队列
    // ------------------------------------------------------------------

    private void ensureWorker() {
        if (workerRunning) {
            return;
        }
        workerRunning = true;
        executor.execute(this::drainQueue);
    }

    private void drainQueue() {
        try {
            while (true) {
                String id = queue.poll();
                if (id == null) {
                    break;
                }
                DownloadTask t = TaskStore.get().byId(id);
                if (t == null) {
                    continue;
                }
                // 排队期间被暂停或取消了，跳过
                if (t.status() != DownloadTask.STATUS_QUEUED) {
                    continue;
                }
                if (t.cancelRequested()) {
                    continue;
                }
                runOne(t);
            }
        } catch (Throwable e) {
            // 队列循环本身不该因为某一条任务的异常而终止
            Log.w(TAG, "下载队列异常退出", e);
        } finally {
            workerRunning = false;
            currentId = "";
            // 期间又有人入队的话，接着干
            if (!queue.isEmpty()) {
                ensureWorker();
                return;
            }
            stopForeground(true);
            stopSelf();
        }
    }

    /**
     * 跑一条任务，把结果写回它的状态。
     *
     * <h3>三种结束方式</h3>
     * <ul>
     *   <li>成功 → {@code DONE}，清掉工作目录；</li>
     *   <li>被中止且用户点的是取消 → {@code CANCELLED}，删掉半成品；</li>
     *   <li>被中止且用户点的是暂停 → {@code PAUSED}，**保留**半成品供继续。</li>
     * </ul>
     * <p>失败同样保留半成品：网络抖一下就要从零重下 300 MB 是说不过去的。</p>
     */
    private void runOne(DownloadTask dt) {
        TaskStore store = TaskStore.get();
        boolean wasPartial = dt.status() == DownloadTask.STATUS_PAUSED
                || dt.status() == DownloadTask.STATUS_FAILED;

        dt.resetStopFlags();
        dt.setError("");
        dt.setStage(getString(R.string.stage_preparing));
        dt.setStatus(DownloadTask.STATUS_RUNNING);
        currentId = dt.id;
        store.notifyProgress();

        Model.Task task = dt.toModelTask();
        // 只在「确实有过半成品，而且是对同一个流」时才续传。
        // 换了画质或换了链接还接着往旧文件里写，拼出来的东西是坏的。
        boolean resume = wasPartial && WorkDir.signatureMatches(this, dt.id, signatureOf(dt));

        String message;
        boolean ok = false;
        try {
            String ref = runTask(dt, task, resume);
            String name = StorageDir.nameOf(this, ref);
            String location = StorageDir.locationOf(this, ref, name, task.audioOnly);
            dt.setSaved(location, ref);
            dt.setStatus(DownloadTask.STATUS_DONE);
            dt.setStage(getString(R.string.stage_done));
            ok = true;
            message = getString(R.string.saved_to, location);
        } catch (Http.AbortedException e) {
            if (dt.cancelRequested()) {
                dt.setStatus(DownloadTask.STATUS_CANCELLED);
                dt.setStage(getString(R.string.stage_cancelled));
                WorkDir.delete(this, dt.id);
                message = getString(R.string.stage_cancelled);
            } else {
                dt.setStatus(DownloadTask.STATUS_PAUSED);
                dt.setStage(getString(R.string.stage_paused));
                message = getString(R.string.stage_paused);
            }
            Log.i(TAG, "任务被中止：" + dt.title + " → " + message);
        } catch (Exception e) {
            Log.w(TAG, "download failed", e);
            dt.setStatus(DownloadTask.STATUS_FAILED);
            dt.setError(describe(e));
            dt.setStage(getString(R.string.stage_failed));
            message = dt.error();
        } finally {
            dt.syncFrom(task);
            if (ok) {
                WorkDir.delete(this, dt.id);
            }
            // 停下来就不该再显示速度 —— 留着最后那个数值会让暂停后的界面
            // 看起来还在跑
            dt.setSpeed(0L);
            store.markDirty(this);
        }

        store.notifyProgress();
        emitFinished(dt, ok, message);
        notifyDone(ok, dt, message);
    }

    /** 这条任务的半成品在什么条件下才算「同一个流」。 */
    private static String signatureOf(DownloadTask t) {
        if (t.youtube) {
            return "yt|" + t.pageUrl + "|" + t.qn + "|" + t.webm + "|" + t.audioOnly;
        }
        return "bili|" + t.bvid + "|" + t.cid + "|" + t.qn + "|" + t.audioOnly;
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    /**
     * @param resume true 表示接着已有的半成品往下写
     * @return 落盘文件的引用（可直接交给 {@link StorageDir#delete}）
     */
    private String runTask(DownloadTask dt, Model.Task task, boolean resume) throws Exception {
        if (!resume) {
            // 全新开始：把上一次留下的碎片清掉，否则会拿旧数据拼出新文件
            WorkDir.delete(this, dt.id);
            // 磁盘清了，记录里的字节数也必须跟着归零 —— 否则界面会显示
            // 一个「已有 200 MB」但实际上从 0 开始的进度条，然后永远不动
            dt.resetVideo();
            dt.resetAudio();
        } else {
            // 续传：把已下载量对齐磁盘上的真实长度。
            // 进程被杀、或上次写入没来得及记盘时，记录里的数字会偏小，
            // 以文件长度为准才是可靠的。
            dt.setVideoDone(WorkDir.lengthOf(WorkDir.videoFile(this, dt.id)));
            dt.setAudioDone(WorkDir.lengthOf(WorkDir.audioFile(this, dt.id)));
        }
        File work = WorkDir.prepare(this, dt.id);
        WorkDir.writeSignature(this, dt.id, signatureOf(dt));

        return task.youtube ? runYouTubeTask(dt, task, work) : runBiliTask(dt, task, work);
    }

    // ------------------------------------------------------------------
    // B 站：解析 → 下 DASH 双轨 → 合流
    // ------------------------------------------------------------------

    private String runBiliTask(DownloadTask dt, Model.Task task, File work) throws Exception {
        Prefs prefs = new Prefs(this);
        String cookie = prefs.cookie();

        reportStage(dt, getString(R.string.stage_resolve));

        Model.PlayInfo info = BiliApi.playurl(task.bvid, task.cid, task.qn, cookie);

        File vFile = null;
        File aFile = null;

        if (!task.audioOnly) {
            Model.Stream v = info.videoByQuality(task.qn, prefs.preferAvc());
            if (v == null) {
                throw new IOException(getString(R.string.err_no_video_stream));
            }
            vFile = WorkDir.videoFile(this, dt.id);
            // 体积优先取接口给的：它是最准的，而且解析阶段就拿到了，
            // 不必等第一个响应包回来才知道该按多少算 100%。
            // 接口这次没给（0）就**保留**上次已知的值 —— 续传时把它清成 0，
            // 进度条会从「有百分比」退化成「不确定」，看起来像丢进度了。
            if (v.size > 0) {
                dt.setVideoTotal(v.size);
            }
            reportStage(dt, getString(R.string.stage_video,
                    v.width + "x" + v.height, Model.codecName(v.codecId)));
            downloadStream(dt, true, v.candidates(), vFile, cookie, true, null, false, null,
                    v.size);
        }

        Model.Stream a = info.bestAudio();
        if (a != null) {
            aFile = WorkDir.audioFile(this, dt.id);
            if (a.size > 0) {
                dt.setAudioTotal(a.size);
            }
            reportStage(dt, getString(R.string.stage_audio));
            downloadStream(dt, false, a.candidates(), aFile, cookie, true, null, false, null,
                    a.size);
        } else if (task.audioOnly) {
            throw new IOException(getString(R.string.err_no_audio_stream));
        }

        return muxAndSave(dt, task, work, vFile, aFile);
    }

    // ------------------------------------------------------------------
    // YouTube：直链已经由界面解析好了，这里只负责下载与合流
    // ------------------------------------------------------------------

    /**
     * <p>正常路径下不在服务里重新解析，是有意为之：YouTube 的解析要跑一整个
     * Python 解释器，实测量级 10-20 秒。用户刚在界面上看到画质列表就点了下载，
     * 再让他对着通知栏等一次解析没有道理。直链在点击后立即使用，
     * 远早于 googlevideo 的过期时间。</p>
     *
     * <p>唯一的例外是**下载被 CDN 拒了**（403 之类）：那时会换一档播放器
     * 客户端重新解析一次，见 {@link #downloadYouTubeStream}。</p>
     */
    private String runYouTubeTask(DownloadTask dt, Model.Task task, File work) throws Exception {
        java.net.Proxy proxy = Http.parseProxy(task.proxy);
        if (proxy == null && !task.proxy.trim().isEmpty()) {
            Log.w(TAG, "代理 " + task.proxy + " 无法解析，本次直连");
        }

        File vFile = null;
        File aFile = null;

        if (!task.audioOnly) {
            if (task.videoUrl.isEmpty()) {
                throw new IOException(getString(R.string.err_no_video_stream));
            }
            vFile = WorkDir.videoFile(this, dt.id);
            dt.setVideoTotal(task.videoSize);
            reportStage(dt, getString(R.string.stage_video,
                    task.videoWidth + "x" + task.videoHeight,
                    YouTubeEngine.describeHeight(task.videoHeight)));
            // 不传 Cookie、不带 B 站 Referer：googlevideo 的直链自带签名，
            // 多送一个 B 站 Referer 反而会被 CDN 当成异常请求。
            downloadYouTubeStream(dt, task, true, vFile, task.videoSize);
        }

        if (task.audioOnly || !task.audioUrl.isEmpty()) {
            if (task.audioUrl.isEmpty()) {
                throw new IOException(getString(R.string.err_no_audio_stream));
            }
            aFile = WorkDir.audioFile(this, dt.id);
            dt.setAudioTotal(task.audioSize);
            reportStage(dt, getString(R.string.stage_audio));
            downloadYouTubeStream(dt, task, false, aFile, task.audioSize);
        }

        return muxAndSave(dt, task, work, vFile, aFile);
    }

    /**
     * 下载 YouTube 的一条流；被 CDN 拒了就换一档播放器客户端重新拿地址再试。
     *
     * <p>为什么下载阶段还要再解析一次：yt-dlp 给出的直链是绑在某个播放器
     * 客户端上的，而客户端决定了这条地址在 CDN 那边要不要额外凭证。
     * 被风控的出口 IP 上，默认客户端发出的地址会被 googlevideo 判 403。</p>
     *
     * <p>换档时重新解析**整个视频**，把两条轨的地址一起刷新。</p>
     *
     * @param isVideo true 下载视频轨，false 下载音频轨
     */
    private void downloadYouTubeStream(DownloadTask dt, Model.Task task, boolean isVideo,
                                       File dst, long sizeHint) throws IOException {
        // 从解析时成功的那一档往后走，不回头 —— 前面几档已经证明不行了
        int profile = task.youtubeClientProfile;
        while (true) {
            String url = isVideo ? task.videoUrl : task.audioUrl;
            java.util.Map<String, String> headers =
                    isVideo ? task.videoHeaders : task.audioHeaders;
            try {
                downloadStream(dt, isVideo, java.util.Collections.singletonList(url), dst,
                        null, false, Http.parseProxy(task.proxy), true, headers, sizeHint);
                return;
            } catch (Http.AbortedException e) {
                // 用户按了暂停或取消。这时**不要**去换客户端重试 ——
                // 那不是失败，用户要的就是停下来。
                throw e;
            } catch (IOException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                profile++;
                if (!YouTubeEngine.isClientRelated(msg)
                        || profile >= YouTubeEngine.clientProfiles()) {
                    throw e;
                }
                Log.w(TAG, (isVideo ? "视频流" : "音频流") + "失败（" + msg + "），改用播放器客户端 "
                        + YouTubeEngine.clientProfileName(profile) + " 重新解析后重试");
                try {
                    refreshYouTubeUrls(task, dt, profile);
                } catch (IOException again) {
                    // 换客户端重解析也失败了。这时**要报原始的下载错误**，
                    // 而不是这个新的解析错误 —— 用户点的是下载，下载为什么失败
                    // 才是他要知道的事。重解析的失败原因记进日志就够了。
                    Log.w(TAG, "改用 " + YouTubeEngine.clientProfileName(profile)
                            + " 重新解析也失败：" + again.getMessage());
                    throw e;
                }
                // 换了地址就得从零开始：新地址的第一块和旧文件的第 N 块接不上
                if (isVideo) {
                    dt.resetVideo();
                } else {
                    dt.resetAudio();
                }
                if (dst.exists() && !dst.delete()) {
                    Log.w(TAG, "无法删除旧分片：" + dst.getName());
                }
            }
        }
    }

    /**
     * 换一档播放器客户端重新解析，把 task 里的直链与请求头换成新的。
     *
     * <p>只认**同一档画质**：用户选的是哪一档就下哪一档。</p>
     */
    private void refreshYouTubeUrls(Model.Task task, DownloadTask dt, int profile)
            throws IOException {
        YouTubeEngine.Result r = YouTubeEngine.resolveWithClient(
                this, task.pageUrl, task.proxy, profile);
        Model.PlayInfo info = r.play;

        boolean gotVideo = false;
        for (Model.Stream s : info.videos) {
            if (s.quality == task.qn) {
                task.videoUrl = s.url;
                if (s.size > 0) {
                    task.videoSize = s.size;
                    dt.setVideoTotal(s.size);
                }
                task.videoHeaders.clear();
                task.videoHeaders.putAll(s.headers);
                gotVideo = true;
                break;
            }
        }
        if (!gotVideo) {
            Log.w(TAG, "客户端 " + YouTubeEngine.clientProfileName(profile)
                    + " 没有 " + YouTubeEngine.describeHeight(task.qn) + " 这一档，保留原地址");
        }

        if (task.audioOnly || !task.audioUrl.isEmpty()) {
            Model.Stream a = info.audioFor(task.webm);
            if (a != null) {
                task.audioUrl = a.url;
                if (a.size > 0) {
                    task.audioSize = a.size;
                    dt.setAudioTotal(a.size);
                }
                task.audioHeaders.clear();
                task.audioHeaders.putAll(a.headers);
            }
        }
    }

    // ------------------------------------------------------------------
    // 合流与落盘（B 站与 YouTube 共用）
    // ------------------------------------------------------------------

    private String muxAndSave(DownloadTask dt, Model.Task task, File work,
                              File vFile, File aFile) throws Exception {
        // 扩展名必须和实际容器一致，而且要和 StorageDir 的 MIME 对得上。
        String ext = task.audioOnly ? "m4a" : (task.webm ? "webm" : "mp4");
        File outFile = new File(work, "output." + ext);

        if (task.audioOnly) {
            // **仅音频不重新封装，直接落盘。**
            //
            // B 站与 YouTube 给的音频流本身就是一条完整的、独立的 m4a：
            // 有 ftyp、有 moov、有样本表，播放器直接能播。把它拆成样本
            // 再用 MediaMuxer 写一遍，唯一的收益是「走同一条代码路径」，
            // 代价是凭空多一次可能失败的操作 —— 而这台 OPPO 上它真的会失败。
            //
            // 实测：34 分钟的音频，源文件完全正确（89,223 个样本、时长
            // 2055 秒，用 MediaExtractor 逐样本验过，零处时间戳回退），
            // 但 OplusMPEG4Writer 在写到第 77,513 帧时判了整条轨死刑：
            //     do not support out of order frames
            //     (timestamp: 1783529297 < last: 1783529319) for Audio track
            // 于是产物只有 77,513 帧、时长缩到 29:43，moov 里甚至连样本表
            // 都没有（提取器读出来 0 条轨），而它被标成了「已完成」。
            //
            // 复制一个正确的文件不可能出错，重写一个正确的文件却可以。
            // 所以这条路径上不做无谓的重写。
            copyFile(aFile, outFile, dt::shouldStop);
            if (!MuxUtil.hasPlayableAudio(outFile)) {
                throw new IOException(getString(R.string.err_audio_incomplete));
            }
            reportStage(dt, getString(R.string.stage_save_library));
            return StorageDir.save(this, outFile, task.displayName(), true, ext);
        }

        reportStage(dt, getString(R.string.stage_mux));
        try {
            // 把停止标志交给封装器：合成也要能被「暂停 / 取消」打断。
            // 本方法里对 AbortedException 特意不做包装 —— 它表达的是
            // 「用户要停」，不是「这档画质封不出来」，包装成后者会让
            // 一次正常的暂停显示成合成失败。
            MuxUtil.mux(vFile, aFile, outFile, task.webm, dt::shouldStop);
        } catch (Http.AbortedException e) {
            throw e;
        } catch (Exception e) {
            // 这里报「哪一档」必须用这条任务自己记下来的标签。
            // 以前调的是 YouTubeEngine.describeHeight(task.videoHeight)，
            // 对哔哩哔哩是错的：它的 videoHeight 可能为 0，于是 480P 的
            // 视频会显示成「（MP4 容器 / 未知画质 画质）」—— 用户拿着一句
            // 既认不出自己下的是哪档、又重复了「画质」二字的话，没法办。
            // dt.subtitle 是解析时就写好的「来源 · 画质 · 编码」，直接可用。
            String label = dt.subtitle;
            if (label == null || label.isEmpty()) {
                label = YouTubeEngine.describeHeight(task.videoHeight);
            }
            throw new IOException(getString(R.string.err_mux_failed,
                    ext.toUpperCase(java.util.Locale.US), label), e);
        }

        if (!MuxUtil.hasVideoTrack(outFile)) {
            throw new IOException(getString(R.string.err_no_video_track));
        }

        reportStage(dt, getString(R.string.stage_save_library));
        return StorageDir.save(this, outFile, task.displayName(), false, ext);
    }

    /**
     * 把下载好的音频文件复制成最终产物。
     *
     * <p>用流式复制而不是 {@code renameTo}：工作目录和目标可能不在同一个
     * 文件系统上（工作目录在应用私有区、目标是 SAF 那边或公共目录），
     * 跨设备的重命名在 Java 层会直接返回 false，而且它失败时不抛异常、
     * 只给一个布尔值，很容易被忽略成一件事都没发生。</p>
     *
     * @param abort 复制几十 MB 也是要时间的，期间要能被「暂停 / 取消」打断。
     *              不查的话，用户点了取消、界面也回到了待下载，文件却还在写，
     *              最后凭空冒出一个他没要的成品。
     */
    private static void copyFile(File src, File dst, Http.Abort abort) throws IOException {
        if (src == null || !src.exists() || src.length() <= 0) {
            throw new IOException("音频文件不存在或为空");
        }
        java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(src), 256 * 1024);
        java.io.OutputStream out = new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(dst), 256 * 1024);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (abort != null && abort.shouldStop()) {
                    throw new Http.AbortedException(0);
                }
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            Http.closeQuietly(in);
            Http.closeQuietly(out);
        }
        // 字节数对不上就一定不能交出去：半截的音频文件播到中途断掉，
        // 比直接报错更难查。
        if (dst.length() != src.length()) {
            throw new IOException("复制不完整：源 " + src.length() + " 字节，结果 "
                    + dst.length() + " 字节");
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /** 依次尝试候选地址下载。 */
    private void downloadStream(DownloadTask dt, boolean isVideo, List<String> urls, File dst,
                                String cookie, boolean withReferer,
                                java.net.Proxy proxy, boolean youtube,
                                java.util.Map<String, String> headers, long sizeHint)
            throws IOException {
        if (urls.isEmpty()) {
            throw new IOException(getString(R.string.err_no_url));
        }
        // 续传时可能遇到「这条流上次已经下完了」—— 用户暂停在音频段，
        // 视频轨的文件其实是完整的。这时再去请求一次没有意义，而且
        // Range 起点等于文件长度时服务端会回 416（请求区间不可满足），
        // 把它当成错误会让一次本该成功的继续直接失败。
        if (sizeHint > 0 && dst.exists() && dst.length() >= sizeHint) {
            Log.i(TAG, (isVideo ? "视频" : "音频") + "轨已完整（"
                    + dst.length() + "/" + sizeHint + "），跳过");
            if (isVideo) {
                dt.setVideoDone(dst.length());
                dt.setVideoTotal(sizeHint);
            } else {
                dt.setAudioDone(dst.length());
                dt.setAudioTotal(sizeHint);
            }
            reportProgress(dt, true);
            return;
        }
        IOException last = null;
        for (int i = 0; i < urls.size(); i++) {
            try {
                if (i > 0) {
                    Log.i(TAG, "fallback to backup url #" + i);
                }
                downloadUrl(dt, isVideo, urls.get(i), dst, cookie, withReferer, proxy, youtube,
                        headers, sizeHint);
                return;
            } catch (Http.AbortedException e) {
                // 用户主动停下。备用地址重试是给网络错误准备的，
                // 拿它去覆盖一个「暂停」的意图只会让暂停失灵。
                throw e;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "url failed: " + urls.get(i), e);
                // **不删半成品**：下一个备用地址会带上 Range 接着往下要，
                // 已经下到的部分不该白费。地址是区间签名（没法续传）时，
                // downloadUrl 自己会从零覆盖，不需要在这里删。
            }
        }
        throw last != null ? last : new IOException(getString(
                youtube ? R.string.err_all_urls_failed_youtube : R.string.err_all_urls_failed));
    }

    /**
     * 地址的查询串里是否自带 {@code range} 参数。
     *
     * <p>只看查询串，不看路径 —— 路径里出现 range 字样不代表签名覆盖了区间。</p>
     */
    private static boolean hasRangeParam(String url) {
        if (url == null) {
            return false;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return false;
        }
        String query = url.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if ("range".equalsIgnoreCase(k.trim())) {
                return true;
            }
        }
        return false;
    }

    private void downloadUrl(DownloadTask dt, boolean isVideo, String url, File dst,
                             String cookie, boolean withReferer,
                             java.net.Proxy proxy, boolean youtube,
                             java.util.Map<String, String> headers, long sizeHint)
            throws IOException {
        // 地址自带 range 时不能自己加 Range，那种签名只覆盖它写的那一段。
        // 也正因为如此，这类地址没法续传 —— 只能从头下。
        boolean rangeSigned = hasRangeParam(url);
        long startAt = 0L;
        if (!rangeSigned && dst.exists() && dst.length() > 0) {
            startAt = dst.length();
        }

        HttpURLConnection c = Http.open(url, cookie, withReferer, proxy, headers);
        if (!rangeSigned) {
            c.setRequestProperty("Range", "bytes=" + startAt + "-");
        }
        if (youtube) {
            Log.i(TAG, "YouTube 直链 host=" + Http.hostOf(url)
                    + "  头=" + (headers == null ? 0 : headers.size())
                    + "  自带range=" + rangeSigned
                    + "  续传起点=" + startAt
                    + "  UA=" + (headers == null ? "(未设置)"
                            : headers.getOrDefault("User-Agent", "(无)")));
        }

        boolean append = false;
        try {
            int code = c.getResponseCode();

            // 206 才是「接着给」。服务端回 200 说明它忽略了 Range，
            // 那我们拿到的是整段 —— 必须从零写，否则新旧数据会拼错位。
            if (startAt > 0) {
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    append = true;
                } else {
                    Log.i(TAG, "服务端未接受续传（HTTP " + code + "），从零开始");
                    startAt = 0L;
                }
            }

            if (code >= 400) {
                if (youtube) {
                    Log.w(TAG, "YouTube 直链返回 " + code + "，响应体："
                            + Http.errorSnippet(c));
                }
                throw new IOException(getString(
                        youtube ? R.string.err_cdn_http_youtube : R.string.err_cdn_http, code));
            }

            long expected = resolveTotal(c, code, startAt, sizeHint);

            // 匿名类要捕获的必须是 final/有效 final。startAt 上面被改过
            // （服务端不接受续传时会归零），所以这里拷一份出来。
            final long base = startAt;

            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dst, append);
            long got;
            try {
                reportStreamStart(dt, isVideo, base, expected);
                got = Http.copy(in, out, new Http.Progress() {
                    @Override
                    public void onBytes(long done, long exp) {
                        // done 是**本次**写入的字节数，要加上续传的起点
                        // 才是这条流真正的进度。
                        onStreamBytes(dt, isVideo, base + done);
                    }
                }, expected, () -> dt.shouldStop());
            } finally {
                Http.closeQuietly(in);
                Http.closeQuietly(out);
            }

            // 连接提前关掉是一个**静默**的失败：read() 返回 -1，拷贝正常返回，
            // 文件看着也写完了，只是比该有的短。它会一路走到合流那一步，
            // 合成出一个时长正确、但画面在某一秒之后冻住的文件 —— 用户
            // 要播到一半才发现。所以在写完之后立刻核对长度。
            long written = base + got;
            if (expected > 0 && written < expected) {
                throw new IOException(getString(R.string.err_truncated,
                        Fmt.bytes(written), Fmt.bytes(expected)));
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * 定出这条流的总字节数。
     *
     * <h3>这是「进度条不动」的根因</h3>
     * <p>原来只读 {@code getContentLengthLong()}：代理下的 googlevideo 走
     * chunked 响应，压根不给这个头，于是总量是 0、百分比恒等于阶段起点。
     * 数据在流、速度看得见、进度条一动不动 —— 正是用户报的那个现象。</p>
     *
     * <p>现在的顺序：{@code Content-Range} 里的总量（续传时最准）→
     * {@code Content-Length}（续传时是剩余量，要加上起点）→
     * 解析阶段已知的体积 {@code sizeHint}（B 站接口与 yt-dlp 都会给）。</p>
     */
    private static long resolveTotal(HttpURLConnection c, int code, long startAt, long sizeHint) {
        String range = c.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash > 0) {
                try {
                    long t = Long.parseLong(range.substring(slash + 1).trim());
                    if (t > 0) {
                        return t;
                    }
                } catch (NumberFormatException ignored) {
                    // 落到下面的分支
                }
            }
        }
        long len = c.getContentLengthLong();
        if (len > 0) {
            return code == HttpURLConnection.HTTP_PARTIAL ? startAt + len : len;
        }
        // 响应头什么都没给。退回解析阶段已知的体积 ——
        // 这条路正是原来缺的那一步。
        return sizeHint > 0 ? sizeHint : 0L;
    }

    // ------------------------------------------------------------------
    // 进度上报
    // ------------------------------------------------------------------

    /** 速度平滑窗口：太短会跳，太长会迟钝。3 秒是个手感合适的折中。 */
    private final Speed speed = new Speed(3000L);

    /** 距上次刷新界面的时间，用来节流。 */
    private long lastPushMs;

    private void reportStreamStart(DownloadTask dt, boolean isVideo, long startAt, long expected) {
        // 这条流的总量以响应头为准（它比解析阶段的估值更准），
        // 响应头没给就保留解析时已知的那个值。
        if (expected > 0) {
            if (isVideo) {
                dt.setVideoTotal(expected);
            } else {
                dt.setAudioTotal(expected);
            }
        }
        if (startAt > 0) {
            if (isVideo) {
                dt.setVideoDone(startAt);
            } else {
                dt.setAudioDone(startAt);
            }
        }
        // 新的一条流开始，把测速窗口清空 —— 上一段的尾巴会把这一段的
        // 初始速度拉低，看起来像「刚开始很慢」
        speed.reset();
        reportProgress(dt, true);
    }

    private void onStreamBytes(DownloadTask dt, boolean isVideo, long streamDone) {
        if (isVideo) {
            dt.setVideoDone(streamDone);
        } else {
            dt.setAudioDone(streamDone);
        }
        reportProgress(dt, false);
    }

    /**
     * 算总进度并（节流地）推给界面。
     *
     * @param force 状态变化时强制推送，不看节流
     */
    private void reportProgress(DownloadTask dt, boolean force) {
        long done = dt.doneBytes();
        long total = dt.totalBytes();
        long bps = speed.update(done);

        long now = System.currentTimeMillis();
        if (!force && now - lastPushMs < 350L) {
            return;
        }
        lastPushMs = now;

        dt.setSpeed(bps);
        TaskStore.get().notifyProgress();
        updateNotification(dt);
        emitProgress(dt);
    }

    private void reportStage(DownloadTask dt, String stage) {
        dt.setStage(stage);
        reportProgress(dt, true);
    }

    /**
     * 滑动窗口测速。
     *
     * <p>为什么不用「两次采样相减」：那个值在每次调用之间抖动极大，
     * 界面上会看到速度在 0 和峰值之间乱跳，反而看不出真实带宽。
     * 取最近几秒的窗口算平均，读起来才是「下载速度」。</p>
     */
    private static final class Speed {
        private final long windowMs;
        private final java.util.ArrayDeque<long[]> samples = new java.util.ArrayDeque<>();

        Speed(long windowMs) {
            this.windowMs = windowMs;
        }

        /** 喂进当前的累计字节数，返回平滑后的速度（字节/秒）。 */
        synchronized long update(long done) {
            long now = System.currentTimeMillis();
            samples.addLast(new long[]{now, done});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > windowMs) {
                samples.removeFirst();
            }
            if (samples.size() < 2) {
                return 0L;
            }
            long[] first = samples.peekFirst();
            long[] last = samples.peekLast();
            long dtMs = last[0] - first[0];
            if (dtMs <= 0) {
                return 0L;
            }
            long db = last[1] - first[1];
            if (db <= 0) {
                return 0L;
            }
            return db * 1000L / dtMs;
        }

        /** 暂停或换流时清空，避免把停顿算进平均。 */
        synchronized void reset() {
            samples.clear();
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notifMgr == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        ch.setShowBadge(false);
        notifMgr.createNotificationChannel(ch);
    }

    private void startForegroundCompat(String stage, String title, int percent, DownloadTask t) {
        Notification n = buildNotification(stage, title, percent, true, t);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String stage, String title, int percent,
                                           boolean ongoing, DownloadTask t) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        // 点通知进下载管理页，而不是主界面 —— 用户想管理下载时会点这里
        Intent open = new Intent(this, DownloadsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        b.setContentTitle(stage)
                .setContentText(title)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);

        if (percent > 0) {
            b.setProgress(100, percent, false);
        } else {
            b.setProgress(0, 0, true);
        }

        // 通知里也给一个暂停键：用户多半是在通知栏上才想起要停
        if (t != null && t.isActive()) {
            b.addAction(new Notification.Action.Builder(
                    null, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE, t.id))
                    .build());
        }
        return b.build();
    }

    private PendingIntent serviceIntent(String action, String taskId) {
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(action);
        i.putExtra(EXTRA_TASK_ID, taskId);
        // request code 用 id 的散列：不同任务的 PendingIntent 不能互相覆盖
        return PendingIntent.getService(this, taskId.hashCode(), i,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private void updateNotification(DownloadTask dt) {
        if (notifMgr == null) {
            return;
        }
        try {
            int pct = dt.percent();
            String stage = dt.stage();
            long bps = dt.speedBps();
            String text = dt.title;
            if (bps > 0 && pct >= 0) {
                text = dt.title + " · " + Fmt.bytes(dt.doneBytes()) + "/"
                        + Fmt.bytes(dt.totalBytes()) + " · " + Fmt.speed(bps);
            }
            notifMgr.notify(NOTIF_ID, buildNotification(stage, text,
                    pct < 0 ? 0 : Math.max(1, pct), true, dt));
        } catch (RuntimeException e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    private void notifyDone(boolean ok, DownloadTask dt, String message) {
        if (notifMgr == null) {
            return;
        }
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Intent open = new Intent(this, DownloadsActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0);

            boolean paused = dt.status() == DownloadTask.STATUS_PAUSED;
            int titleRes = ok ? R.string.notif_done_title
                    : (paused ? R.string.notif_paused_title : R.string.notif_failed_title);
            b.setContentTitle(getString(titleRes))
                    .setContentText(dt.title + " · " + message)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            notifMgr.notify(NOTIF_ID, b.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "notifyDone failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return getString(R.string.err_download_timeout);
        }
        if (e instanceof java.net.UnknownHostException) {
            return getString(R.string.err_download_dns);
        }
        return m;
    }

    private static String str(Intent i, String key) {
        String v = i.getStringExtra(key);
        return v == null ? "" : v;
    }
}
