package com.biligrab.downloader;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一条下载记录。
 *
 * <h3>为什么要有这个类</h3>
 * <p>原来的下载是「一次性」的：{@code Model.Task} 从界面传进服务，跑完就没了；
 * 服务里所有任务共用一个 {@code filesDir/work} 目录，每次开跑先整个清空。
 * 那样的结构下「下载管理」无从谈起 —— 没有记录可列，没有独立的工作目录
 * 能留下半成品，所以暂停、继续、删除残留文件全都做不了。</p>
 *
 * <p>这里把一条下载变成**有身份、可持久化**的对象：有 id、有状态、有自己
 * 独占的工作目录，能落成 JSON 存到磁盘上，进程重启后还在。</p>
 *
 * <h3>进度为什么按流分开记</h3>
 * <p>一条 DASH 下载是「视频轨 + 音频轨」两段串行。只看总字节数会踩到一个坑：
 * 用户暂停在音频段，继续时视频轨的文件其实已经完整了，若只把已下载量当作
 * 一个标量，就会把那段完整的视频当成「还没下」而重下一遍 —— 或者更糟，
 * 把它算进总量的分母里，让进度条在继续的瞬间倒退。</p>
 *
 * <p>所以每条流各自记自己的已下与总量，{@link #doneBytes()} 与
 * {@link #totalBytes()} 由它们相加得出。分母只包含**已经确定总量**的流，
 * 后一条流还没开始时不会凭空把百分比压到一半。</p>
 *
 * <h3>为什么状态是可变字段而不是不可变对象</h3>
 * <p>下载过程中每秒要更新好几次字节数与速度。每更新一次就 new 一个对象、
 * 替换列表里的一项，会让界面持有旧引用而看不到变化 —— 那正是「进度条不动」
 * 这类 bug 的温床。所以在同步保护下直接改字段，界面读的永远是同一个对象。</p>
 */
public final class DownloadTask {

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 排队中，等待服务取走。 */
    public static final int STATUS_QUEUED = 0;
    /** 正在下载。 */
    public static final int STATUS_RUNNING = 1;
    /** 已暂停。半成品文件**保留**，可以继续。 */
    public static final int STATUS_PAUSED = 2;
    /** 已完成。 */
    public static final int STATUS_DONE = 3;
    /** 失败。半成品文件保留，可以继续重试。 */
    public static final int STATUS_FAILED = 4;
    /** 已取消。由用户主动取消，半成品文件已删除。 */
    public static final int STATUS_CANCELLED = 5;

    // ------------------------------------------------------------------
    // 身份与静态信息：创建后不再变，不需要同步保护
    // ------------------------------------------------------------------

    public String id = UUID.randomUUID().toString();
    public String title = "";
    /** 副标题：画质 / 编码这类一眼分辨来源的信息。 */
    public String subtitle = "";
    public boolean youtube;
    public long createdAt = System.currentTimeMillis();

    // ---- B 站 ----
    public String bvid = "";
    public long cid;
    public String partTitle = "";
    public int qn = Prefs.DEFAULT_QN;
    public boolean audioOnly;

    // ---- YouTube ----
    public String pageUrl = "";
    public String videoUrl = "";
    public long videoSize;
    public int videoWidth;
    public int videoHeight;
    public String audioUrl = "";
    public long audioSize;
    public boolean webm;
    public String proxy = "";
    public int youtubeClientProfile;
    public final Map<String, String> videoHeaders = new LinkedHashMap<>();
    public final Map<String, String> audioHeaders = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // 以下字段在同步块里读写
    // ------------------------------------------------------------------

    private final Object lock = new Object();

    private int status = STATUS_QUEUED;

    /** 视频轨已下载字节。**以磁盘上的文件长度为准**，不是内存计数。 */
    private long videoDone;
    /** 视频轨总字节。0 表示未知。 */
    private long videoTotal;
    private long audioDone;
    private long audioTotal;

    /** 当前瞬时速度，字节/秒。 */
    private long speedBps;
    /** 当前阶段的说明，如「下载视频流」，或暂停/取消这类终态说明。 */
    private String stage = "";
    /** 落盘后位置的人类可读描述。 */
    private String location = "";
    /**
     * 落盘后的引用 —— 用来删除这个文件。
     *
     * <p>可能是 {@code content://} 也可能是绝对路径：默认目录走 MediaStore，
     * 自定义目录走 SAF，两者能用的删除句柄不一样。存成字符串，
     * 由 {@link StorageDir#delete} 去分辨是哪一种。</p>
     */
    private String fileRef = "";
    /** 失败原因，成功时为空。 */
    private String error = "";

    /** 用户点了暂停。下载循环每一块数据都会看一眼。 */
    private volatile boolean pauseRequested;
    /** 用户点了取消。 */
    private volatile boolean cancelRequested;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public int status() {
        synchronized (lock) {
            return status;
        }
    }

    public void setStatus(int s) {
        synchronized (lock) {
            status = s;
        }
    }

    public boolean isActive() {
        int s = status();
        return s == STATUS_RUNNING || s == STATUS_QUEUED;
    }

    /** 这条记录还占着磁盘上的半成品吗（可以在界面上给一个「删除残留文件」）。 */
    public boolean hasPartial() {
        int s = status();
        return s == STATUS_PAUSED || s == STATUS_FAILED;
    }

    public long speedBps() {
        synchronized (lock) {
            return speedBps;
        }
    }

    public void setSpeed(long bps) {
        synchronized (lock) {
            speedBps = bps < 0 ? 0 : bps;
        }
    }

    public String stage() {
        synchronized (lock) {
            return stage;
        }
    }

    public void setStage(String s) {
        synchronized (lock) {
            stage = s == null ? "" : s;
        }
    }

    public String location() {
        synchronized (lock) {
            return location;
        }
    }

    public String fileRef() {
        synchronized (lock) {
            return fileRef;
        }
    }

    public void setSaved(String location, String fileRef) {
        synchronized (lock) {
            this.location = location == null ? "" : location;
            this.fileRef = fileRef == null ? "" : fileRef;
        }
    }

    public String error() {
        synchronized (lock) {
            return error;
        }
    }

    public void setError(String s) {
        synchronized (lock) {
            error = s == null ? "" : s;
        }
    }

    // ------------------------------------------------------------------
    // 每条流的字节数
    // ------------------------------------------------------------------

    public long videoDone() {
        synchronized (lock) {
            return videoDone;
        }
    }

    public long videoTotal() {
        synchronized (lock) {
            return videoTotal;
        }
    }

    public long audioDone() {
        synchronized (lock) {
            return audioDone;
        }
    }

    public long audioTotal() {
        synchronized (lock) {
            return audioTotal;
        }
    }

    public void setVideoDone(long v) {
        synchronized (lock) {
            videoDone = v < 0 ? 0 : v;
        }
    }

    public void setVideoTotal(long v) {
        synchronized (lock) {
            videoTotal = v < 0 ? 0 : v;
        }
    }

    public void setAudioDone(long v) {
        synchronized (lock) {
            audioDone = v < 0 ? 0 : v;
        }
    }

    public void setAudioTotal(long v) {
        synchronized (lock) {
            audioTotal = v < 0 ? 0 : v;
        }
    }

    /** 从头开始下载某条流时，把它的计数清零。 */
    public void resetVideo() {
        synchronized (lock) {
            videoDone = 0L;
            videoTotal = 0L;
        }
    }

    public void resetAudio() {
        synchronized (lock) {
            audioDone = 0L;
            audioTotal = 0L;
        }
    }

    public long doneBytes() {
        synchronized (lock) {
            return videoDone + audioDone;
        }
    }

    /**
     * 已确定总量的那些流加起来。
     *
     * <p>不用「两条流的总和」当分母：音频段还没开始时它的总量是 0，
     * 如果硬把它算进去，百分比会在视频段中间突然被砍一半。</p>
     */
    public long totalBytes() {
        synchronized (lock) {
            return videoTotal + audioTotal;
        }
    }

    /**
     * 百分比。
     *
     * @return 0..100；总量未知时返回 -1，让界面画不确定态的进度条，
     *         而不是假装 0%
     */
    public int percent() {
        synchronized (lock) {
            long total = videoTotal + audioTotal;
            if (total <= 0) {
                return -1;
            }
            long done = videoDone + audioDone;
            long p = done * 100L / total;
            if (p < 0) {
                return 0;
            }
            return p > 100 ? 100 : (int) p;
        }
    }

    // ------------------------------------------------------------------
    // 暂停 / 取消
    // ------------------------------------------------------------------

    public void requestPause() {
        pauseRequested = true;
    }

    /** 取消也隐含暂停 —— 两者都会让下载循环尽快退出。 */
    public void requestCancel() {
        cancelRequested = true;
    }

    public boolean pauseRequested() {
        return pauseRequested;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    /** 开始下载前清掉上一次的停止请求。 */
    public void resetStopFlags() {
        pauseRequested = false;
        cancelRequested = false;
    }

    /** 是否应当停止：暂停或取消任一成立。 */
    public boolean shouldStop() {
        return pauseRequested || cancelRequested;
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("subtitle", subtitle);
        o.put("youtube", youtube);
        o.put("createdAt", createdAt);
        o.put("bvid", bvid);
        o.put("cid", cid);
        o.put("partTitle", partTitle);
        o.put("qn", qn);
        o.put("audioOnly", audioOnly);
        o.put("pageUrl", pageUrl);
        o.put("videoUrl", videoUrl);
        o.put("videoSize", videoSize);
        o.put("videoWidth", videoWidth);
        o.put("videoHeight", videoHeight);
        o.put("audioUrl", audioUrl);
        o.put("audioSize", audioSize);
        o.put("webm", webm);
        o.put("proxy", proxy);
        o.put("youtubeClientProfile", youtubeClientProfile);
        o.put("videoHeaders", new JSONObject(videoHeaders));
        o.put("audioHeaders", new JSONObject(audioHeaders));

        synchronized (lock) {
            o.put("status", status);
            o.put("videoDone", videoDone);
            o.put("videoTotal", videoTotal);
            o.put("audioDone", audioDone);
            o.put("audioTotal", audioTotal);
            o.put("stage", stage);
            o.put("location", location);
            o.put("fileRef", fileRef);
            o.put("error", error);
        }
        return o;
    }

    public static DownloadTask fromJson(JSONObject o) {
        DownloadTask t = new DownloadTask();
        t.id = o.optString("id", t.id);
        t.title = o.optString("title", "");
        t.subtitle = o.optString("subtitle", "");
        t.youtube = o.optBoolean("youtube", false);
        t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        t.bvid = o.optString("bvid", "");
        t.cid = o.optLong("cid", 0L);
        t.partTitle = o.optString("partTitle", "");
        t.qn = o.optInt("qn", Prefs.DEFAULT_QN);
        t.audioOnly = o.optBoolean("audioOnly", false);
        t.pageUrl = o.optString("pageUrl", "");
        t.videoUrl = o.optString("videoUrl", "");
        t.videoSize = o.optLong("videoSize", 0L);
        t.videoWidth = o.optInt("videoWidth", 0);
        t.videoHeight = o.optInt("videoHeight", 0);
        t.audioUrl = o.optString("audioUrl", "");
        t.audioSize = o.optLong("audioSize", 0L);
        t.webm = o.optBoolean("webm", false);
        t.proxy = o.optString("proxy", "");
        t.youtubeClientProfile = o.optInt("youtubeClientProfile", 0);
        putAll(t.videoHeaders, o.optJSONObject("videoHeaders"));
        putAll(t.audioHeaders, o.optJSONObject("audioHeaders"));

        int st = o.optInt("status", STATUS_QUEUED);
        // 进程上次是被杀掉的 —— 那时有任务停在「下载中」或「排队中」，
        // 但现在没有任何线程在跑它们了。照搬状态会让界面永远显示一个
        // 不会动的进度条，看起来就是卡死。改成「已暂停」，用户可以自己点继续：
        // 半成品还在磁盘上，继续是有意义的。
        if (st == STATUS_RUNNING || st == STATUS_QUEUED) {
            st = STATUS_PAUSED;
        }
        t.setStatus(st);
        t.setVideoDone(o.optLong("videoDone", 0L));
        t.setVideoTotal(o.optLong("videoTotal", 0L));
        t.setAudioDone(o.optLong("audioDone", 0L));
        t.setAudioTotal(o.optLong("audioTotal", 0L));
        t.setStage(o.optString("stage", ""));
        t.setSaved(o.optString("location", ""), o.optString("fileRef", ""));
        t.setError(o.optString("error", ""));
        return t;
    }

    private static void putAll(Map<String, String> dst, JSONObject src) {
        if (src == null) {
            return;
        }
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            dst.put(k, src.optString(k, ""));
        }
    }

    /** 转成服务实际下载时用的老结构。 */
    public Model.Task toModelTask() {
        Model.Task m = new Model.Task();
        m.bvid = bvid;
        m.cid = cid;
        m.title = title;
        m.partTitle = partTitle;
        m.qn = qn;
        m.audioOnly = audioOnly;
        m.youtube = youtube;
        m.pageUrl = pageUrl;
        m.videoUrl = videoUrl;
        m.videoSize = videoSize;
        m.videoWidth = videoWidth;
        m.videoHeight = videoHeight;
        m.audioUrl = audioUrl;
        m.audioSize = audioSize;
        m.webm = webm;
        m.proxy = proxy;
        m.youtubeClientProfile = youtubeClientProfile;
        m.videoHeaders.putAll(videoHeaders);
        m.audioHeaders.putAll(audioHeaders);
        return m;
    }

    /**
     * 把服务里可能被改写的字段同步回来。
     *
     * <p>下载中会换档重试，客户端档位和直链都可能变。下一次继续下载要接着用
     * 最新的那套，否则会拿着上一档已经失败过的地址重试。</p>
     */
    public void syncFrom(Model.Task m) {
        videoUrl = m.videoUrl;
        if (m.videoSize > 0) {
            videoSize = m.videoSize;
        }
        audioUrl = m.audioUrl;
        if (m.audioSize > 0) {
            audioSize = m.audioSize;
        }
        youtubeClientProfile = m.youtubeClientProfile;
        videoHeaders.clear();
        videoHeaders.putAll(m.videoHeaders);
        audioHeaders.clear();
        audioHeaders.putAll(m.audioHeaders);
    }
}
