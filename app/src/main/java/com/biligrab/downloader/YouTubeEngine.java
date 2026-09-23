package com.biligrab.downloader;

import android.content.Context;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * YouTube 解析引擎。
 *
 * <p><b>为什么这里要挂一个 Python 解释器。</b>
 * B 站是纯 HTTP + 几个哈希签名就能解决的（见 {@link WbiSigner}），
 * 但 YouTube 不是：它的每个媒体地址都包在 {@code signatureCipher} 里，
 * 要跑一段每次发布都重新混淆过的 JavaScript 才能还原出真实 URL。
 * 实测 {{@code https://www.youtube.com/watch?v=...}} 的
 * {@code ytInitialPlayerResponse}，27 个格式**全部**是 signatureCipher，
 * 没有一个带明文 {@code url} 字段；InnerTube 的 player 接口也被挡
 * （WEB 返回 UNPLAYABLE，ANDROID 返回 400，TVHTML5 提示客户端不再受支持）。</p>
 *
 * <p>所以这里的做法是：不自己重写解密，而是把 yt-dlp 当作**解析器**用，
 * 让它把已经解好签的地址吐出来，下载和合流仍然走本应用自己的
 * {@link Http} + {@link MuxUtil} 管线。这样做的两个好处：
 * YouTube 改混淆时不需要跟着改代码，而且不需要打包 ffmpeg
 * （那正是同类应用动辄 60 MB 的原因）。</p>
 *
 * <p><b>刻意不使用 {@code YoutubeDL.getInfo()}。</b>
 * 那个方法走 Jackson 反序列化，而 Jackson 并不在 AAR 里（是 Maven 的传递
 * 依赖）。用它就得再塞进来约 2.5 MB 的 jackson-databind/core/annotations。
 * 这里改用 {@code execute()} 取原始 JSON，交给系统自带的 {@code org.json}
 * 解析 —— dex 里仍然引用着 ObjectMapper，但没有任何代码路径会去碰它。</p>
 */
public final class YouTubeEngine {

    private static final String TAG = "BiliGrab/YouTube";

    /**
     * yt-dlp 的 vcodec 字段形如 {@code avc1.640028} / {@code vp09.00.51.08} /
     * {@code av01.0.08M.08}，取点号前的部分判断编码族。
     */
    private static final String CODEC_AVC = "avc1";
    private static final String CODEC_VP9 = "vp9";
    private static final String CODEC_AV1 = "av01";

    private YouTubeEngine() {
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private static volatile boolean sInited;
    private static volatile String sInitError = "";
    /** 已解压出来的解释器版本，如 {@code 2025.11.12}。空串表示还没问过。 */
    private static volatile String sVersion = "";

    /**
     * 确保 Python 运行时与 yt-dlp 已就绪。
     *
     * <p>第一次调用会把 {@code res/raw/ytdlp}（约 3 MB）解压到应用私有目录，
     * 在千元机上是秒级但绝不适合主线程，务必放到后台线程调用。
     * 之后每次调用都只是读一个 volatile 标记。</p>
     *
     * @return 初始化失败时返回原因，成功返回空串
     */
    public static String ensureReady(Context ctx) {
        if (sInited) {
            return "";
        }
        synchronized (YouTubeEngine.class) {
            if (sInited) {
                return "";
            }
            if (!sInitError.isEmpty()) {
                return sInitError;
            }
            try {
                long t0 = System.currentTimeMillis();
                YoutubeDL.getInstance().init(ctx.getApplicationContext());
                long ms = System.currentTimeMillis() - t0;
                sInited = true;
                Log.i(TAG, "Python 运行时初始化完成，耗时 " + ms + "ms");
                return "";
            } catch (Throwable t) {
                // 这里必须吞 Throwable 而不是 Exception：
                // 原生库缺失会抛 UnsatisfiedLinkError（Error 而非 Exception），
                // 如果让它逃出去会直接崩掉整个应用。
                sInitError = describe(t);
                Log.e(TAG, "初始化失败: " + sInitError, t);
                return sInitError;
            }
        }
    }

    /** 引擎是否可用（会触发一次初始化）。 */
    public static boolean isAvailable(Context ctx) {
        return ensureReady(ctx).isEmpty();
    }

    /**
     * 已解压的 yt-dlp 版本，形如 {@code 2025.11.12}。
     *
     * <p>直接问解释器要，而不是查库的 SharedPreferences：库只在自己执行过更新
     * 之后才把版本号写进 prefs，全新安装时那个键是空的，界面会显示成空白。
     * 跑一次 {@code --version} 无论哪种情况都拿得到真值。</p>
     */
    public static String version(Context ctx) {
        if (!isAvailable(ctx)) {
            return "";
        }
        if (!sVersion.isEmpty()) {
            return sVersion;
        }
        try {
            YoutubeDLRequest req = new YoutubeDLRequest("--version");
            // --version 不是 URL，用这个重载可以避免把 "--version" 当地址解析
            YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req);
            String out = resp == null ? null : resp.getOut();
            if (out != null) {
                for (String line : out.split("[\\r\\n]+")) {
                    String s = line.trim();
                    // yt-dlp 的版本号形如 2025.11.12 / 2025.11.12.232819(nightly)
                    if (s.matches("\\d{4}\\.\\d{2}\\.\\d{2}.*")) {
                        sVersion = s;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 yt-dlp 版本失败: " + t);
        }
        return sVersion;
    }

    /**
     * 从 GitHub 拉取最新稳定版 yt-dlp 并替换本地本体。
     *
     * <p><b>为什么不直接用库的 {@code updateYoutubeDL}。</b>
     * 那个实现走 Jackson 的 {@code readTree(url)} 和
     * {@code FileUtils.copyURLToFile}，两者都用 JDK 默认网络栈，
     * <b>完全不认我们传进来的代理</b>。而需要更新的场景恰恰就是被墙的场景，
     * 所以它在这台机器上必然失败。这里改用本应用自己的 {@link Http} 下载，
     * 代理自然跟着走。</p>
     *
     * <p><b>为什么不查 GitHub 的 releases API。</b>
     * 那个接口未认证时每小时只有 60 次，按出口 IP 计算，实测直接返回 403。
     * 这里改成请求 {@code /releases/latest} 并**关闭自动重定向**，
     * 从 302 的 {@code Location} 头里解析出版本号 —— 同样是官方途径，
     * 但走的是网页而非 API，没有那个限流。</p>
     *
     * <p>替换的是 {@code <noBackupFilesDir>/youtubedl-android/yt-dlp/yt-dlp}
     * 这一个文件 —— 与库 {@code init_ytdlp} 使用的是同一个路径。</p>
     *
     * @return 更新后的版本号
     */
    public static String updateYtDlp(Context ctx, String proxySpec) throws IOException {
        String err = ensureReady(ctx);
        if (!err.isEmpty()) {
            throw new IOException(err);
        }
        java.net.Proxy proxy = Http.parseProxy(proxySpec);
        String current = version(ctx);

        // 1) 请求 /releases/latest，只看它把我们指到哪里
        String latestPage = "https://github.com/yt-dlp/yt-dlp/releases/latest";
        String tag;
        HttpURLConnection c = Http.open(latestPage, null, false, proxy);
        // Http.open 默认开着重定向，这里必须关掉，否则拿不到 Location
        c.setInstanceFollowRedirects(false);
        try {
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (loc == null || loc.isEmpty()) {
                throw new IOException("GitHub 没有返回跳转地址（HTTP " + code + "）。"
                        + hintFor(code, proxySpec));
            }
            tag = loc.replaceAll("/+$", "");
            int slash = tag.lastIndexOf('/');
            if (slash >= 0) {
                tag = tag.substring(slash + 1);
            }
            if (!tag.matches("[0-9][0-9.]*.*")) {
                throw new IOException("无法从 " + loc + " 解析出版本号");
            }
        } finally {
            c.disconnect();
        }

        // 已经是最新就不用白下 3 MB
        if (tag.equals(current)) {
            Log.i(TAG, "yt-dlp 已是最新（" + tag + "），无需更新");
            return current;
        }

        // 2) 下到缓存目录，成功后再替换，避免下到一半把能用的版本毁掉
        String downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/"
                + tag + "/yt-dlp";
        File cache = new File(ctx.getCacheDir(), "yt-dlp.new");
        HttpURLConnection d = Http.open(downloadUrl, null, false, proxy);
        try {
            int code = d.getResponseCode();
            if (code >= 400) {
                throw new IOException("下载 yt-dlp 失败：HTTP " + code + "。" + hintFor(code, proxySpec));
            }
            long expected = d.getContentLength();
            try (InputStream in = d.getInputStream();
                 OutputStream out = new FileOutputStream(cache)) {
                long n = Http.copy(in, out, null, expected);
                if (expected > 0 && n < expected) {
                    throw new IOException("下载不完整：期望 " + expected + " 字节，实际 " + n);
                }
                if (n < 100_000L) {
                    // 正常情况下是 3 MB 左右的 zipapp。太小说明下到的是
                    // 一个错误页面，装上去会让引擎彻底不能用。
                    throw new IOException("下载到的文件只有 " + n + " 字节，不是有效的 yt-dlp");
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
            throw e;
        } finally {
            d.disconnect();
        }

        // 3) 替换。路径与库的 init() 完全一致，它下次启动就会用新文件。
        File ytdlpDir = new File(new File(ctx.getNoBackupFilesDir(), YoutubeDL.baseName),
                YoutubeDL.ytdlpDirName);
        if (!ytdlpDir.exists() && !ytdlpDir.mkdirs()) {
            throw new IOException("无法创建目录 " + ytdlpDir);
        }
        File binary = new File(ytdlpDir, YoutubeDL.ytdlpBin);
        File old = new File(ytdlpDir, YoutubeDL.ytdlpBin + ".old");
        //noinspection ResultOfMethodCallIgnored
        old.delete();
        if (binary.exists() && !binary.renameTo(old)) {
            throw new IOException("无法备份现有 yt-dlp");
        }
        if (!copyFile(cache, binary)) {
            // 替换失败就把旧的换回来，不要留下一个半残的状态
            //noinspection ResultOfMethodCallIgnored
            binary.delete();
            //noinspection ResultOfMethodCallIgnored
            old.renameTo(binary);
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
            throw new IOException("写入 yt-dlp 失败，已回滚到原版本");
        }
        //noinspection ResultOfMethodCallIgnored
        old.delete();
        //noinspection ResultOfMethodCallIgnored
        cache.delete();

        sVersion = "";
        String v = version(ctx);
        Log.i(TAG, "yt-dlp 已更新到 " + tag + "（解释器报告 " + v + "）");
        return v.isEmpty() ? tag : v;
    }

    /** 把 HTTP 状态码翻译成「怎么办」，而不是只丢一个数字给用户。 */
    private static String hintFor(int code, String proxySpec) {
        boolean noProxy = proxySpec == null || proxySpec.trim().isEmpty();
        if (code == 403 || code == 429) {
            return "GitHub 暂时拒绝了这个出口 IP，过几分钟再试。";
        }
        if (code == 404) {
            return "发布页面不存在，可能上游改了发布方式。";
        }
        if (noProxy) {
            return "GitHub 在部分网络下无法直连，请在设置里填代理后重试。";
        }
        return "请确认代理 " + proxySpec + " 可用。";
    }

    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            Http.copy(in, out, null, -1L);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "复制 yt-dlp 失败: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析结果。
     *
     * <p>YouTube 的元数据和流是同一趟取回来的，分两个对象返回只是为了让
     * 上层能直接复用 B 站那套「{@code Model.Video} + {@code PlayInfo}」渲染路径。</p>
     */
    public static final class Result {
        public final Model.Video video = new Model.Video();
        public final Model.PlayInfo play = new Model.PlayInfo();
    }

    /**
     * 解析一个 YouTube 链接，返回可下载的流。
     *
     * <p>返回的 {@link Model.PlayInfo} 复用 B 站那套结构，但 {@code quality}
     * 字段的语义不同：B 站是 qn 枚举（16/32/64/80/112…），YouTube 这边直接
     * 用**画面高度**（144/240/360/480/720/1080/1440/2160）。两者不会混淆，
     * 因为 B 站 qn 最大到 127，而 YouTube 的最低档是 144。</p>
     *
     * <p>阻塞调用，必须放在后台线程。</p>
     *
     * @param proxySpec {@code host:port} 形式的代理，空串表示直连。
     *                  YouTube 在中国大陆无法直连，不填会以
     *                  {@code [Errno 110] Connection timed out} 失败。
     */
    public static Result resolve(Context ctx, String url, String proxySpec) throws IOException {
        String err = ensureReady(ctx);
        if (!err.isEmpty()) {
            throw new IOException(err);
        }

        YoutubeDLRequest req = new YoutubeDLRequest(url);
        // --dump-single-json：把解析结果整个吐成一行 JSON，不下载任何字节。
        // 这是把 yt-dlp 当「纯解析器」用的关键。
        req.addOption("--dump-single-json");
        req.addOption("--skip-download");
        // 一个播放列表链接不该变成几十个解析任务
        req.addOption("--no-playlist");
        req.addOption("--no-warnings");
        // 不要加 --no-call-home：那是 youtube-dl 时代的遗留选项，
        // 现行 yt-dlp 已标记为 deprecated，传了只会在 stderr 多一行警告。
        req.addOption("--socket-timeout", "20");
        req.addOption("--retries", "3");

        // 代理必须通过 --proxy 传给 Python。Android 的「全局 HTTP 代理」
        // 只对 Java 层的网络栈生效，Python 的 urllib 完全不看那个设置，
        // 所以光在系统设置里配代理是没用的。
        if (proxySpec != null && !proxySpec.trim().isEmpty()) {
            String p = proxySpec.trim();
            req.addOption("--proxy", p.startsWith("http") ? p : "http://" + p);
        }

        YoutubeDLResponse resp;
        try {
            // 这个重载不带进度回调，因此不会碰到 kotlin.jvm.functions.Function3。
            // 下载进度由本应用自己的管线负责，本来也不需要它。
            resp = YoutubeDL.getInstance().execute(req);
        } catch (Throwable t) {
            throw new IOException("调用 yt-dlp 失败：" + describe(t), t);
        }

        if (resp == null) {
            throw new IOException("yt-dlp 没有返回结果");
        }
        String out = resp.getOut();
        if (resp.getExitCode() != 0 || out == null || out.trim().isEmpty()) {
            String e = resp.getErr();
            throw new IOException("yt-dlp 解析失败：" + shorten(e == null ? "" : e, 300));
        }

        JSONObject root;
        try {
            // --dump-single-json 正常输出一行，但个别版本会带额外的日志前缀，
            // 所以从第一个 '{' 开始截。
            int brace = out.indexOf('{');
            if (brace < 0) {
                throw new IOException("yt-dlp 输出里没有 JSON");
            }
            root = new JSONObject(out.substring(brace));
        } catch (Exception e) {
            throw new IOException("yt-dlp 输出的 JSON 无法解析：" + shorten(out, 200), e);
        }

        return build(root, url);
    }

    // ------------------------------------------------------------------
    // JSON → Model
    // ------------------------------------------------------------------

    private static Result build(JSONObject root, String pageUrl) throws IOException {
        Result result = new Result();
        Model.PlayInfo info = result.play;
        info.fromYouTube = true;

        info.videos.clear();
        info.audios.clear();
        info.qualities.clear();

        String title = root.optString("title", "");
        if (title.isEmpty()) {
            title = root.optString("fulltitle", "未命名视频");
        }
        long duration = root.optLong("duration", 0L);

        // 填视频元数据。字段名沿用 B 站的叫法，好让界面上的渲染路径完全复用：
        // owner 对应频道名，cover 对应封面图，pages 塞一个占位分 P。
        result.video.title = title;
        result.video.owner = root.optString("uploader", root.optString("channel", ""));
        result.video.cover = root.optString("thumbnail", "");
        result.video.desc = root.optString("description", "");
        result.video.duration = (int) duration;
        String vid = root.optString("id", "");
        result.video.bvid = vid;
        Model.Part part = new Model.Part();
        part.index = 1;
        part.title = title;
        part.duration = (int) duration;
        result.video.pages.add(part);

        JSONArray formats = root.optJSONArray("formats");
        if (formats == null || formats.length() == 0) {
            throw new IOException("yt-dlp 没有返回任何可用格式，视频可能被限制或需要登录");
        }

        // 同一个高度会有多种编码，按「MediaMuxer 能不能封装」排序取优。
        // H.264 在 MP4 里是铁定能封的；VP9 从 API 24 起可以；AV1 要 API 29。
        // 所以同一分辨率优先选 avc1 —— 画质档位再多，合不出文件也没意义。
        LinkedHashMap<Integer, Model.Stream> pickVideo = new LinkedHashMap<>();
        LinkedHashMap<Integer, Integer> pickRank = new LinkedHashMap<>();
        // 两种容器各留一条最佳音轨。MP4 收 AAC 不收 Opus，WebM 反过来，
        // 所以必须分开挑 —— 只留一条会让高画质那一档没有可用的音轨。
        Model.Stream bestAudioMp4 = null;
        Model.Stream bestAudioWebm = null;
        int bestMp4Rank = -1;
        int bestWebmRank = -1;
        // 已经合体的渐进式 MP4（itag 18 之类），预览要用它
        Model.Stream progressive = null;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) {
                continue;
            }
            // m3u8/直播清单不是直链，本应用的下载管线处理不了，跳过
            String proto = f.optString("protocol", "");
            if (proto.contains("m3u8") || proto.startsWith("rtmp")) {
                continue;
            }
            String fileUrl = f.optString("url", "");
            if (fileUrl.isEmpty()) {
                // 走到这里说明签名没解出来。不静默跳过：这通常意味着
                // 内置的 yt-dlp 版本已经过期，用户需要知道并去更新。
                Log.w(TAG, "格式 " + f.optString("format_id", "?") + " 没有解出直链，已跳过");
                continue;
            }

            String vcodec = f.optString("vcodec", "none");
            String acodec = f.optString("acodec", "none");
            boolean hasVideo = !"none".equals(vcodec) && !vcodec.isEmpty();
            boolean hasAudio = !"none".equals(acodec) && !acodec.isEmpty();

            Model.Stream s = new Model.Stream();
            s.url = fileUrl;
            readHeaders(f, s);
            s.mimeType = f.optString("ext", "");
            s.height = f.optInt("height", 0);
            s.width = f.optInt("width", 0);
            // quality 用画面高度填充。PlayInfo.videoByQuality() 是按 quality
            // 匹配的，不设就会永远匹配不到（真机上表现为每条流都显示 0P）。
            s.quality = s.height;
            s.bandwidth = f.optLong("tbr", 0L);
            if (s.bandwidth <= 0) {
                s.bandwidth = f.optLong("abr", 0L);
            }
            s.codecId = codecIdOf(vcodec);
            // filesize 是精确值，个别格式只给 filesize_approx。两者都没有就留 0，
            // 界面会退化成不显示体积而不是显示 0 B。
            s.size = f.optLong("filesize", 0L);
            if (s.size <= 0) {
                s.size = f.optLong("filesize_approx", 0L);
            }

            if (hasVideo && !hasAudio) {
                if (s.height <= 0) {
                    continue;
                }
                int rank = videoRank(vcodec);
                Integer prev = pickRank.get(s.height);
                if (prev == null || rank < prev) {
                    pickRank.put(s.height, rank);
                    pickVideo.put(s.height, s);
                }
            } else if (hasAudio && !hasVideo) {
                long abr = f.optLong("abr", 0L);
                s.quality = (int) abr;
                boolean webm = "webm".equalsIgnoreCase(f.optString("ext", ""));
                if (webm) {
                    int rank = audioRank(acodec, "webm", abr);
                    if (rank > bestWebmRank) {
                        bestWebmRank = rank;
                        bestAudioWebm = s;
                    }
                } else {
                    int rank = audioRank(acodec, f.optString("ext", ""), abr);
                    if (rank > bestMp4Rank) {
                        bestMp4Rank = rank;
                        bestAudioMp4 = s;
                    }
                }
            } else if (hasVideo && hasAudio) {
                // 音视频已经合体，可以直接给 MediaPlayer 播
                if (progressive == null || s.bandwidth > progressive.bandwidth) {
                    progressive = s;
                }
            }
        }

        if (pickVideo.isEmpty()) {
            throw new IOException("没有拿到任何视频直链。多半是内置的 yt-dlp 版本已过期，"
                    + "请在设置里更新后重试。");
        }

        info.videos.addAll(pickVideo.values());
        // MP4 音轨排前面：界面上的「仅音频」行和 1080P 及以下的下载都用它
        if (bestAudioMp4 != null) {
            info.audios.add(bestAudioMp4);
        }
        if (bestAudioWebm != null) {
            info.audios.add(bestAudioWebm);
        }
        // 只有 MP4 音轨时，WebM 那条路没有音轨可配。记下来，
        // 好让界面在用户点 1440P/2160P 时给出准确的解释。
        info.webmAudioAvailable = bestAudioWebm != null;

        // 档位表按画质从高到低，界面直接照着渲染
        List<Integer> heights = new ArrayList<>(pickVideo.keySet());
        heights.sort((a, b) -> b - a);
        for (Integer h : heights) {
            info.qualities.put(h, describeHeight(h));
        }

        info.qualityDesc = heights.isEmpty() ? "" : describeHeight(heights.get(0));
        info.audioOnlySupported = bestAudioMp4 != null || bestAudioWebm != null;

        if (progressive != null) {
            info.preview = new Model.PreviewSource();
            info.preview.url = progressive.url;
            info.preview.quality = progressive.height;
            info.preview.format = progressive.mimeType;
            info.preview.durationMs = duration > 0 ? duration * 1000L : 0L;
            info.preview.size = progressive.size;
        }

        result.video.desc = result.video.desc == null ? "" : result.video.desc;
        Log.i(TAG, "解析完成：" + title + " (" + pageUrl + ")"
                + "  时长 " + duration + "s  视频档 " + heights.size()
                + " 个  最高 " + info.qualityDesc
                + "  音轨 AAC " + (bestAudioMp4 != null ? "有" : "无")
                + " / Opus " + (bestAudioWebm != null ? "有" : "无")
                + "  渐进式预览 " + (progressive != null ? "有" : "无"));
        return result;
    }

    /**
     * 把 yt-dlp 为这个 format 声明的 {@code http_headers} 抄进流对象。
     *
     * <p>这些头不是可有可无的装饰。googlevideo 的直链签名和解析时的客户端绑定，
     * yt-dlp 回传 {@code http_headers} 正是为了让下载端原样复现那个客户端。
     * 换成别的 User-Agent 会被 CDN 当成另一个客户端，直接 403 ——
     * 这正是「YouTube 能解析但一下载就 403」的成因。</p>
     *
     * <p>只收文本头。{@code Cookie} 之类带状态的、以及 {@code Range}
     * （由下载管线自己控制）都跳过，避免把解析阶段的一次性凭据带到下载流量里。</p>
     */
    private static void readHeaders(JSONObject format, Model.Stream s) {
        JSONObject h = format.optJSONObject("http_headers");
        if (h == null) {
            return;
        }
        java.util.Iterator<String> keys = h.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k == null || k.isEmpty()) {
                continue;
            }
            String lower = k.toLowerCase(java.util.Locale.US);
            if ("cookie".equals(lower) || "range".equals(lower)
                    || "content-length".equals(lower) || "host".equals(lower)) {
                continue;
            }
            Object v = h.opt(k);
            if (v == null) {
                continue;
            }
            String sv = String.valueOf(v);
            if (!sv.isEmpty()) {
                s.headers.put(k, sv);
            }
        }
    }

    /** 编码族 → 本应用内部使用的 codecid。 */
    private static int codecIdOf(String vcodec) {
        if (vcodec.startsWith(CODEC_AVC)) {
            return 7;   // AVC/H.264 → 能进 MP4
        }
        if (vcodec.startsWith(CODEC_VP9)) {
            return Model.CODEC_VP9;  // → 只能进 WebM
        }
        if (vcodec.startsWith(CODEC_AV1)) {
            return 13;  // AV1
        }
        return 0;
    }

    /**
     * 同一分辨率下选哪个编码：数字越小越优先。
     * 排序依据是 MediaMuxer 的兼容性，不是压缩率。
     */
    private static int videoRank(String vcodec) {
        if (vcodec.startsWith(CODEC_AVC)) {
            return 0;
        }
        if (vcodec.startsWith(CODEC_VP9)) {
            return 1;
        }
        if (vcodec.startsWith(CODEC_AV1)) {
            return 2;
        }
        return 9;
    }

    /**
     * 音轨选哪个：数字越大越优先。
     *
     * <p>同一容器内的编码分开排：MP4 这边 m4a(AAC) 最前，WebM 那边是
     * opus/vorbis。两条线不互相比较 —— 它们压根不会出现在同一次封装里。</p>
     *
     * <p>编码分占主导，码率只作为同编码内的次级排序，这样不会出现
     * 「选了个码率略高但容器装不进去的流」。</p>
     */
    private static int audioRank(String acodec, String ext, long abr) {
        int r;
        if ("webm".equals(ext)) {
            r = (acodec.startsWith("opus") || acodec.startsWith("vorbis")) ? 4 : 0;
        } else {
            r = 0;
            if (acodec.startsWith("mp4a")) {
                r += 4;
            }
            if ("m4a".equals(ext)) {
                r += 2;
            }
        }
        // 编码分放大到远超码率量级，码率只在同编码内起作用
        return r * 100000 + (int) Math.min(abr, 99999L);
    }

    /** 高度 → 展示名。 */
    public static String describeHeight(int h) {
        switch (h) {
            case 2160:
                return "2160P 4K";
            case 1440:
                return "1440P 2K";
            case 1080:
                return "1080P 高清";
            case 720:
                return "720P 高清";
            case 480:
                return "480P 标清";
            case 360:
                return "360P 流畅";
            case 240:
                return "240P 极速";
            case 144:
                return "144P 最低";
            default:
                return h > 0 ? h + "P" : "未知画质";
        }
    }

    // ------------------------------------------------------------------
    // 匹配
    // ------------------------------------------------------------------

    /** 这个链接看起来是不是 YouTube。 */
    public static boolean isYouTubeUrl(String raw) {
        if (raw == null) {
            return false;
        }
        String u = raw.trim().toLowerCase();
        if (u.isEmpty()) {
            return false;
        }
        return u.contains("youtube.com/watch")
                || u.contains("youtube.com/shorts/")
                || u.contains("youtube.com/live/")
                || u.contains("youtube.com/embed/")
                || u.contains("m.youtube.com/")
                || u.startsWith("youtu.be/")
                || u.contains("//youtu.be/");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String describe(Throwable t) {
        String m = t.getMessage();
        String name = t.getClass().getSimpleName();
        if (m == null || m.isEmpty()) {
            return name;
        }
        return name + ": " + shorten(m, 240);
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
