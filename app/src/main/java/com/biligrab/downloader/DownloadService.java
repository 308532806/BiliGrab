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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台下载服务：解析 → 下载 DASH 音视频流 → MediaMuxer 合成 → 写入媒体库。
 *
 * <p>任务串行执行，保证不会同时占用过多带宽与磁盘 IO。</p>
 */
public class DownloadService extends Service {

    private static final String TAG = "BiliGrab";

    public static final String ACTION_ENQUEUE = "com.biligrab.app.action.ENQUEUE";
    public static final String EXTRA_BVID = "bvid";
    public static final String EXTRA_CID = "cid";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PART = "part";
    public static final String EXTRA_QN = "qn";
    public static final String EXTRA_AUDIO_ONLY = "audioOnly";

    private static final String CHANNEL_ID = "biligrab_download";
    private static final int NOTIF_ID = 0x2101;

    private ExecutorService executor;
    private NotificationManager notifMgr;

    // ------------------------------------------------------------------
    // 进度观察者（UI 与服务在同一进程，直接用静态列表回调即可）
    // ------------------------------------------------------------------

    /** 下载进度监听。 */
    public interface Listener {
        void onProgress(String stage, int percent);

        void onFinished(boolean ok, String message, String location);
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

    private static void emitProgress(String stage, int percent) {
        for (Listener l : LISTENERS) {
            try {
                l.onProgress(stage, percent);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener error", e);
            }
        }
    }

    private static void emitFinished(boolean ok, String message, String location) {
        for (Listener l : LISTENERS) {
            try {
                l.onFinished(ok, message, location);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener error", e);
            }
        }
    }

    /** 供 UI 调用：把任务交给服务。 */
    public static void enqueue(Context ctx, Model.Task task) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_ENQUEUE);
        i.putExtra(EXTRA_BVID, task.bvid);
        i.putExtra(EXTRA_CID, task.cid);
        i.putExtra(EXTRA_TITLE, task.title);
        i.putExtra(EXTRA_PART, task.partTitle);
        i.putExtra(EXTRA_QN, task.qn);
        i.putExtra(EXTRA_AUDIO_ONLY, task.audioOnly);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_ENQUEUE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final Model.Task task = new Model.Task();
        task.bvid = str(intent, EXTRA_BVID);
        task.cid = intent.getLongExtra(EXTRA_CID, 0L);
        task.title = str(intent, EXTRA_TITLE);
        task.partTitle = str(intent, EXTRA_PART);
        task.qn = intent.getIntExtra(EXTRA_QN, Prefs.DEFAULT_QN);
        task.audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false);

        // 必须在 5 秒内调用 startForeground
        Notification preparing = buildNotification(
                getString(R.string.stage_preparing),
                getString(R.string.stage_preparing_detail, task.title), 0, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, preparing,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, preparing);
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String message;
                String location = "";
                try {
                    location = runTask(task);
                    ok = true;
                    message = getString(R.string.saved_to, location);
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    message = describe(e);
                }
                emitProgress(ok ? getString(R.string.stage_done)
                        : getString(R.string.stage_failed), 100);
                emitFinished(ok, message, location);
                notifyDone(ok, task.title, message);
                stopForeground(true);
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
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
    // 主流程
    // ------------------------------------------------------------------

    private String runTask(Model.Task task) throws Exception {
        Prefs prefs = new Prefs(this);
        String cookie = prefs.cookie();

        File work = new File(getExternalFilesDir(null), "work");
        if (!work.exists() && !work.mkdirs()) {
            throw new IOException(getString(R.string.err_tmp_dir));
        }
        clearDir(work);

        String stageResolve = getString(R.string.stage_resolve);
        emitProgress(stageResolve, 2);
        updateNotification(stageResolve, task.title, 2);

        Model.PlayInfo info = BiliApi.playurl(task.bvid, task.cid, task.qn, cookie);

        File vFile = null;
        File aFile = null;

        if (!task.audioOnly) {
            Model.Stream v = info.videoByQuality(task.qn, prefs.preferAvc());
            if (v == null) {
                throw new IOException(getString(R.string.err_no_video_stream));
            }
            vFile = new File(work, "video.m4s");
            String label = getString(R.string.stage_video,
                    v.width + "x" + v.height, Model.codecName(v.codecId));
            emitProgress(label, 5);
            downloadStream(v, vFile, cookie, 5, 50, label, task.title);
        }

        Model.Stream a = info.bestAudio();
        if (a != null) {
            aFile = new File(work, "audio.m4s");
            int lo = task.audioOnly ? 5 : 50;
            String stageAudio = getString(R.string.stage_audio);
            emitProgress(stageAudio, lo);
            downloadStream(a, aFile, cookie, lo, 85, stageAudio, task.title);
        } else if (task.audioOnly) {
            throw new IOException(getString(R.string.err_no_audio_stream));
        }

        File outFile = new File(work, "output.mp4");
        String stageMux = getString(R.string.stage_mux);
        emitProgress(stageMux, 88);
        updateNotification(stageMux, task.title, 88);
        MuxUtil.mux(vFile, aFile, outFile);

        if (!task.audioOnly && !MuxUtil.hasVideoTrack(outFile)) {
            throw new IOException(getString(R.string.err_no_video_track));
        }

        String stageSave = getString(R.string.stage_save_library);
        emitProgress(stageSave, 95);
        updateNotification(stageSave, task.title, 95);
        String location = MediaStoreSaver.save(this, outFile, task.displayName(), task.audioOnly);

        clearDir(work);
        return location;
    }

    private void downloadStream(Model.Stream s, File dst, String cookie,
                                int lo, int hi, String stage, String title) throws IOException {
        List<String> urls = s.candidates();
        if (urls.isEmpty()) {
            throw new IOException(getString(R.string.err_no_url));
        }
        IOException last = null;
        for (int i = 0; i < urls.size(); i++) {
            try {
                if (i > 0) {
                    Log.i(TAG, "fallback to backup url #" + i);
                }
                downloadUrl(urls.get(i), dst, cookie, lo, hi, stage, title);
                return;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "url failed: " + urls.get(i), e);
                // 单个地址失败即换备用 CDN 重试
                if (dst.exists() && !dst.delete()) {
                    Log.w(TAG, "cannot delete partial file");
                }
            }
        }
        throw last != null ? last : new IOException(getString(R.string.err_all_urls_failed));
    }

    private void downloadUrl(String url, File dst, String cookie,
                             int lo, int hi, String stage, String title) throws IOException {
        HttpURLConnection c = Http.open(url, cookie, true);
        c.setRequestProperty("Range", "bytes=0-");
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new IOException(getString(R.string.err_cdn_http, code));
            }
            long total = c.getContentLengthLong();
            if (total <= 0) {
                String range = c.getHeaderField("Content-Range");
                if (range != null) {
                    int slash = range.lastIndexOf('/');
                    if (slash > 0) {
                        try {
                            total = Long.parseLong(range.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {
                            total = 0L;
                        }
                    }
                }
            }

            final long expected = total;
            final int range_ = Math.max(1, hi - lo);
            final int base = lo;

            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dst);
            try {
                Http.copy(in, out, new Http.Progress() {
                    private long lastPush = 0L;

                    @Override
                    public void onBytes(long done, long exp) {
                        long known = expected > 0 ? expected : exp;
                        int pct = known > 0
                                ? (int) (base + (range_ * done) / known)
                                : base;
                        if (pct > hi) {
                            pct = hi;
                        }
                        // 最多每 400ms 刷一次，避免高频刷新 UI 与通知
                        long now = System.currentTimeMillis();
                        if (now - lastPush > 400L || pct >= hi) {
                            lastPush = now;
                            emitProgress(stage, pct);
                            updateNotification(stage, title, pct);
                        }
                    }
                }, expected);
            } finally {
                Http.closeQuietly(in);
                Http.closeQuietly(out);
            }
        } finally {
            c.disconnect();
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

    private static void clearDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!f.delete()) {
                Log.w(TAG, "cannot delete " + f);
            }
        }
    }

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

    private Notification buildNotification(String stage, String title, int percent, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Intent open = new Intent(this, MainActivity.class);
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
        return b.build();
    }

    private void updateNotification(String stage, String title, int percent) {
        if (notifMgr == null) {
            return;
        }
        try {
            notifMgr.notify(NOTIF_ID, buildNotification(stage, title, percent, true));
        } catch (RuntimeException e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    private void notifyDone(boolean ok, String title, String message) {
        if (notifMgr == null) {
            return;
        }
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0);
            b.setContentTitle(getString(ok ? R.string.notif_done_title
                            : R.string.notif_failed_title))
                    .setContentText(title + " · " + message)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            notifMgr.notify(NOTIF_ID, b.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "notifyDone failed", e);
        }
    }
}
