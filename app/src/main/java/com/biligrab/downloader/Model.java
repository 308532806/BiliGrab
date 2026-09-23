package com.biligrab.downloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 视频信息与数据模型。 */
public final class Model {

    private Model() {
    }

    /** 稿件信息。 */
    public static final class Video {
        public String bvid = "";
        public long aid;
        public String title = "";
        public String owner = "";
        public String cover = "";
        public String desc = "";
        public int duration;
        public final List<Part> pages = new ArrayList<>();

        public boolean isMultiPart() {
            return pages.size() > 1;
        }
    }

    /** 分 P 信息。 */
    public static final class Part {
        public int index = 1;
        public long cid;
        public String title = "";
        public int duration;

        @Override
        public String toString() {
            return title;
        }
    }

    /** 一条可下载的媒体流。 */
    public static final class Stream {
        public String url = "";
        public final List<String> backups = new ArrayList<>();
        public int quality;
        public int codecId;
        public long bandwidth;
        public int width;
        public int height;
        public String mimeType = "";
        /** 字节数。B 站接口会给，YouTube 走 yt-dlp 的 filesize(_approx)，都可能为 0。 */
        public long size;
        /**
         * 请求这条流时必须额外带的头。
         *
         * <p>B 站流是空的 —— 它的 CDN 只认 {@link Http#open} 里那套固定头。
         * YouTube 流会填上 yt-dlp 为这个 format 声明的 {@code http_headers}。</p>
         *
         * <p>为什么必须原样带上：googlevideo 的直链签名是和**解析时用的客户端**
         * 绑定的。yt-dlp 之所以在每个 format 里回传 {@code http_headers}，
         * 就是因为拿别的 User-Agent 去请求会被判定成另一个客户端而拒掉。
         * 之前这里直接丢了这些头、改用为 B 站准备的桌面 Chrome UA，
         * 结果就是下载恒 403。</p>
         */
        public final Map<String, String> headers = new LinkedHashMap<>();

        /** 带兜底地址的候选列表，主地址失败时按序重试。 */
        public List<String> candidates() {
            List<String> all = new ArrayList<>();
            if (!url.isEmpty()) {
                all.add(url);
            }
            all.addAll(backups);
            return all;
        }
    }

    /** 一次 playurl 解析的完整结果。 */
    public static final class PlayInfo {
        public final List<Stream> videos = new ArrayList<>();
        public final List<Stream> audios = new ArrayList<>();
        /** 展示用画质描述，如 “1080P 高清”。 */
        public String qualityDesc = "";
        public boolean audioOnlySupported;
        /** 画质档位 qn → 中文描述，来源为 support_formats。 */
        public final java.util.LinkedHashMap<Integer, String> qualities = new java.util.LinkedHashMap<>();

        /**
         * 稿件**宣称支持**、但当前账号**拿不到**的档位（qn → 描述）。
         *
         * <p>{@code support_formats} 列的是账号有权看到的档位，{@code dash} 列的才是
         * 真正给的。两者不一致时，这些档位会从 {@link #qualities} 里剔掉 ——
         * 留着它们等于让用户选一个下不到的档位。</p>
         *
         * <p>但剔掉之后必须**说清楚**。典型场景：稿件有 4K，未登录时服务端照样
         * 在 support_formats 里报 `120=4K 超高清`，dash 却只给到 1080P。
         * 界面上如果只是安静地少一行，用户会以为这个应用不支持 4K，
         * 而真实原因是 4K 需要大会员账号。这里存下来就是为了给出那句话。</p>
         */
        public final java.util.LinkedHashMap<Integer, String> lockedQualities = new java.util.LinkedHashMap<>();

        /**
         * 已经合体的音视频流。B 站走 {@code fnval=1} 的 durl 拿到，
         * YouTube 走 yt-dlp 的渐进式 MP4（itag 18）拿到。
         * 没有它就只能放弃预览 —— 分离轨给 MediaPlayer 播出来是没声音的。
         */
        public PreviewSource preview;

        /** 这个结果是不是来自 YouTube。界面据此切换画质档位的含义。 */
        public boolean fromYouTube;

        /**
         * YouTube 专用：是否拿到了能装进 WebM 的音轨（Opus）。
         *
         * <p>WebM 不收 AAC，MP4 不收 Opus。没有 WebM 音轨时，
         * 1440P / 2160P 这两档就没有音轨可配，界面要提前说清楚，
         * 而不是等用户下完 342 MB 才在合成阶段失败。</p>
         */
        public boolean webmAudioAvailable;

        /** 按目标容器挑音轨。找不到对应容器的音轨时返回 {@code null}。 */
        public Stream audioFor(boolean webm) {
            for (Stream s : audios) {
                if (isWebmAudio(s) == webm) {
                    return s;
                }
            }
            return null;
        }

        public Stream videoByQuality(int qn, boolean preferAvc) {
            Stream best = null;
            for (Stream s : videos) {
                if (s.quality != qn) {
                    continue;
                }
                if (best == null) {
                    best = s;
                }
                // codecid: 7=AVC(H.264) 12=HEVC 13=AV1。AVC 兼容性最好，优先。
                if (preferAvc && s.codecId == 7) {
                    return s;
                }
            }
            if (best != null) {
                return best;
            }
            // 目标画质不存在时退化为码率最高的可用流
            for (Stream s : videos) {
                if (best == null || s.bandwidth > best.bandwidth) {
                    best = s;
                }
            }
            return best;
        }

        public Stream bestAudio() {
            Stream best = null;
            for (Stream s : audios) {
                if (best == null || s.bandwidth > best.bandwidth) {
                    best = s;
                }
            }
            return best;
        }
    }

    /**
     * 预览播放源。
     *
     * <p>预览不能用 DASH：那是**分离**的视频轨和音频轨，系统 {@code MediaPlayer}
     * 播出来没声音，而且不能自由拖动。所以预览走 {@code fnval=1} 的 durl 模式，
     * 拿到的是一整个「音视频已合体」的渐进式 MP4，可以边下边播、随便拖。</p>
     *
     * <p>URL 必须带 {@code Referer} 才能取到数据（不带会返回 403），
     * 所以播放时不能用裸的 {@code setVideoURI(Uri)}。</p>
     */
    public static final class PreviewSource {
        public String url = "";
        public final List<String> backups = new ArrayList<>();
        /** 字节数，用于显示「正在缓冲」时的进度。 */
        public long size;
        /** 毫秒。来自接口，比 MediaPlayer 准备完再问要早。 */
        public long durationMs;
        public int quality;
        public String format = "";

        public List<String> candidates() {
            List<String> all = new ArrayList<>();
            if (!url.isEmpty()) {
                all.add(url);
            }
            all.addAll(backups);
            return all;
        }
    }

    /** 一个下载任务。 */
    public static final class Task {
        public String bvid = "";
        public long cid;
        public String title = "";
        public String partTitle = "";
        public int qn = 80;
        public boolean audioOnly;

        // ------------------------------------------------------------------
        // YouTube 专用字段
        //
        // 为什么不只带一个页面地址让服务端重新解析：YouTube 的解析要跑一整个
        // Python 解释器，实测量级是 10-20 秒。用户在界面上刚看到画质列表就点下载，
        // 再让他等一次解析没有道理。所以界面解析出来的直链直接带过来。
        //
        // 直链本身是有时效的（googlevideo 的 expire 参数），但这个窗口以小时计，
        // 而任务在点击后立刻开始，不存在过期风险。
        // ------------------------------------------------------------------

        /** true 表示这是 YouTube 任务，走另一条下载与合流路径。 */
        public boolean youtube;
        /** 页面地址，仅用于展示与日志。 */
        public String pageUrl = "";
        public String videoUrl = "";
        public long videoSize;
        public int videoWidth;
        public int videoHeight;
        public String audioUrl = "";
        public long audioSize;
        /**
         * 两条流各自的请求头，原样取自 {@link Stream#headers}。
         *
         * <p>分开存是因为视频和音频常常来自不同的 itag，签名绑定的客户端
         * 未必相同 —— 合成一份共用的头在个别格式上仍然会 403。</p>
         */
        public final Map<String, String> videoHeaders = new LinkedHashMap<>();
        public final Map<String, String> audioHeaders = new LinkedHashMap<>();
        /**
         * 输出 WebM 而不是 MP4。
         *
         * <p>由视频编码决定，不是用户选项：VP9 / AV1 装不进 MP4，
         * AAC 装不进 WebM，两者没有交集。</p>
         */
        public boolean webm;
        /** 下载 YouTube 直链所用的代理，空串表示直连。 */
        public String proxy = "";

        public String displayName() {
            String base = sanitize(title);
            if (partTitle != null && !partTitle.isEmpty() && !partTitle.equals(title)) {
                base = base + " - " + sanitize(partTitle);
            }
            return base;
        }

        private static String sanitize(String s) {
            if (s == null) {
                return "video";
            }
            StringBuilder sb = new StringBuilder(s.length());
            for (char c : s.toCharArray()) {
                if ("\\/:*?\"<>|\r\n\t".indexOf(c) >= 0) {
                    sb.append('_');
                } else {
                    sb.append(c);
                }
            }
            String out = sb.toString().trim();
            if (out.length() > 80) {
                out = out.substring(0, 80);
            }
            return out.isEmpty() ? "video" : out;
        }
    }

    /** codecid → 可读名称。 */
    public static String codecName(int codecId) {
        switch (codecId) {
            case 7:
                return "AVC/H.264";
            case 12:
                return "HEVC/H.265";
            case 13:
                return "AV1";
            case CODEC_VP9:
                // 单独给一个 id 而不是并到 12：VP9 不是 HEVC，界面上标成
                // 「H.265」会让用户以为下载的是另一种编码，而这两者的
                // 封装去向完全不同（VP9 只能进 WebM，HEVC 只能进 MP4）
                return "VP9";
            default:
                return "codec#" + codecId;
        }
    }

    // ------------------------------------------------------------------
    // 容器选择
    //
    // MediaMuxer 对「什么能进哪个容器」有硬性限制，实测 OPPO PDPM00
    // (Android 12) 上把 VP9 加进 MP4 会直接抛 IllegalStateException
    // （Failed to add the track to the muxer）。所以容器不能写死，
    // 必须跟着视频编码走：
    //
    //   H.264        → MP4（配 AAC）
    //   VP9 / AV1    → WebM（配 Opus）
    //
    // 这不是偏好问题。YouTube 的 1440P 和 2160P **只有** VP9 与 AV1，
    // 写死 MP4 就等于这两档永远下不了。
    // ------------------------------------------------------------------

    /** VP9。借用一个 B 站不会出现的值，避免和 DASH 的 codecid 撞车。 */
    public static final int CODEC_VP9 = 14;

    /** 这个视频编码是否必须装进 WebM。 */
    public static boolean needsWebm(int codecId) {
        return codecId == CODEC_VP9 || codecId == 13;
    }

    /**
     * 这个音轨能不能装进 WebM。
     *
     * <p>WebM 不收 AAC，MP4 不收 Opus，两边没有交集，所以选音轨必须
     * 和选容器同时决定。</p>
     */
    public static boolean isWebmAudio(Stream s) {
        return s != null && "webm".equalsIgnoreCase(s.mimeType);
    }

    /** DASH 音频档位 id → 可读名称。 */
    public static String audioName(int id) {
        switch (id) {
            case 30216:
                return "64K";
            case 30232:
                return "132K";
            case 30280:
                return "192K";
            case 30250:
                return "杜比全景声";
            case 30251:
                return "Hi-Res 无损";
            default:
                return "音频#" + id;
        }
    }
}
