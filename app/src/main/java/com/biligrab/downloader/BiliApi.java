package com.biligrab.downloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站 Web 端公开接口封装。
 *
 * <p>只调用网页版自身使用的接口（{@code /x/web-interface/view}、
 * {@code /x/player/wbi/playurl}），携带用户本人登录态的 Cookie，
 * 行为与浏览器打开视频页一致。</p>
 */
public final class BiliApi {

    private static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl";

    /**
     * 旧版 playurl 端点。**不需要 WBI 签名**，也是未登录时唯一能拿到 1080P 的入口。
     *
     * <p>这是实测出来的，不是猜的。同一个稿件、同一组参数、空 Cookie，
     * 只改端点和 {@code try_look}：</p>
     *
     * <pre>
     *   /x/player/wbi/playurl  无 try_look  →  最高 480P
     *   /x/player/wbi/playurl  try_look=1   →  最高 480P
     *   /x/player/playurl      无 try_look  →  最高 480P
     *   /x/player/playurl      try_look=1   →  最高 1080P   ← 只有这个组合行
     * </pre>
     *
     * <p><b>两个条件是「与」的关系，缺一不可。</b>顺便排除了几个想当然的猜测：
     * 把 {@code qn} 从 127 改到 32 或干脆不传，结果一模一样；
     * 带上 {@code platform=pc}、{@code high_quality=1} 也没有影响。
     * 所以真正的开关只有「端点 + try_look」。</p>
     *
     * <p>{@code try_look}（试看）本来就是原项目 bilibilias 的意图 —— 它算好了
     * {@code try_look = if (未登录) "1" else null}，却忘了塞进请求参数，
     * 成了一段死代码。这里把它补上。</p>
     *
     * <p>风险：{@code try_look} 字面意思是「试看」，对大会员专享内容有返回
     * <b>截断片段</b>的可能。免费视频实测是完整长度。</p>
     */
    private static final String PLAYURL_URL_PLAIN = "https://api.bilibili.com/x/player/playurl";

    /** 从 Cookie 里取 SESSDATA。 */
    private static final Pattern P_SESSDATA = Pattern.compile("SESSDATA=([^;\\s]+)");

    /**
     * fnval 位掩码：
     * 16=DASH, 64=HDR, 128=4K, 256=杜比音频, 512=杜比视界, 1024=8K, 2048=AV1。
     */
    private static final int FNVAL = 16 | 64 | 128 | 256 | 512 | 1024 | 2048;

    /**
     * 请示的最高档位：8K。
     *
     * <p>这不是「我要 8K」，而是「按你能给的最高来」。服务端按账号权限降级，
     * 见 {@link #PLAYURL_URL_PLAIN} 里那张实测表 —— 决定能拿多高的是
     * 端点和 try_look，不是这里发的数字。发低了反而可能只回低码率。</p>
     */
    private static final int QN_MAX = 127;

    private static final Pattern P_BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern P_AV = Pattern.compile("(?i)av(\\d+)");

    /**
     * b23.tv / bili2233.cn 短链。
     *
     * <p>这两个域名只做 302 跳转，**地址里没有 BV 号** —— 光靠正则扫 BV
     * 是扫不出来的，必须真的发一次请求跟到最终地址。B 站 App 的「分享」
     * 和「复制口令」产出的几乎全是这种短链，这是过去只能用 BV 号解析的原因。</p>
     */
    private static final Pattern P_SHORT = Pattern.compile(
            "https?://(?:b23\\.tv|bili2233\\.cn)/[A-Za-z0-9]+");

    /**
     * 任意受支持站点的链接，用于从口令文本里挑出真正要用的一段。
     *
     * <p>末尾的字符类排除中文标点与空白：口令形如
     * {@code 【【官方MV】标题-UP主-哔哩哔哩】 https://b23.tv/AbCdEf}，
     * 直接按空白切会把后面的中文当成 URL 的一部分。</p>
     */
    private static final Pattern P_LINK = Pattern.compile(
            "https?://[A-Za-z0-9.\\-]*(?:bilibili\\.com|b23\\.tv|bili2233\\.cn"
                    + "|youtu\\.be|youtube\\.com)[^\\s\u4e00-\u9fff，。！？、；：（）【】]*");

    private BiliApi() {
    }

    /**
     * 从任意文本（分享链接、口令、纯 ID）中提取视频标识。
     *
     * <p>纯函数，不联网。短链在这里提取不出来 —— 它的地址里没有 BV 号。
     * 需要联网跟短链请用 {@link #resolveId}。</p>
     */
    public static String extractId(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        Matcher m = P_BV.matcher(s);
        if (m.find()) {
            return m.group();
        }
        m = P_AV.matcher(s);
        if (m.find()) {
            return "av" + m.group(1);
        }
        if (s.matches("\\d+")) {
            return s;
        }
        return s;
    }

    /** 提取到的东西是否已经是可以直接请求的标识。 */
    private static boolean isResolved(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return id.regionMatches(true, 0, "BV", 0, 2)
                || id.regionMatches(true, 0, "av", 0, 2)
                || id.matches("\\d+");
    }

    /**
     * 从口令文本里挑出链接；没有链接时返回空串。
     *
     * <p>给输入框的「粘贴」用：口令是一整段中文加一个链接，
     * 直接把整段填进输入框既难看也不好确认，只留链接更清楚。</p>
     */
    public static String extractLink(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = P_LINK.matcher(raw);
        return m.find() ? m.group() : "";
    }

    /**
     * 提取标识；遇到短链时跟随重定向后再提取一次。
     *
     * <p>与 {@link #extractId} 的分工：那个是纯函数，这个是会联网的那一步。
     * 只有确认提取不出标识、且文本里有短链时才发请求，正常输入不多走一次网络。</p>
     *
     * @throws ApiException {@link #CODE_BAD_INPUT} 文本里根本没有可用的视频地址
     */
    public static String resolveId(String raw, String cookie) throws IOException {
        String s = raw == null ? "" : raw.trim();
        String id = extractId(s);
        if (isResolved(id)) {
            return id;
        }

        // 短链：跟一次跳转，最终地址里才有 BV 号
        Matcher shortLink = P_SHORT.matcher(s);
        if (shortLink.find()) {
            String link = shortLink.group();
            String resolved = followShortLink(link, cookie);
            if (resolved.isEmpty()) {
                throw new ApiException(CODE_BAD_INPUT, "解析短链",
                        "短链 " + link + " 没能打开。检查网络后重试，"
                                + "或直接在 B 站里复制 BV 号");
            }
            return resolved;
        }

        // 普通 bilibili 链接（或 YouTube 链接误入）。抽出来再试一次，
        // 万一它其实是个短链或带跳转的地址，就跟一下
        Matcher anyLink = P_LINK.matcher(s);
        if (anyLink.find()) {
            String link = anyLink.group();
            String fromLink = extractId(link);
            if (isResolved(fromLink)) {
                return fromLink;
            }
            String resolved = followShortLink(link, cookie);
            if (!resolved.isEmpty()) {
                return resolved;
            }
        }

        throw new ApiException(CODE_BAD_INPUT, "解析链接",
                "没能从这段内容里找到视频地址。可以粘贴分享链接、"
                        + "b23.tv 短链、BV 号或 av 号");
    }

    /**
     * 跟随一次短链跳转，返回从最终地址里提取到的标识；失败返回空串。
     *
     * <p>依赖 {@link HttpURLConnection} 自身的重定向跟随（{@code Http.open}
     * 已经开了 {@code setInstanceFollowRedirects}），不手动读 {@code Location} ——
     * b23.tv 有时要跳两次，自己跟就得写循环，而系统已经会跟。</p>
     *
     * <p>注意必须先碰一次响应：重定向链是在读取响应时走完的，
     * 没读之前 {@code getURL()} 返回的还是原地址。</p>
     */
    private static String followShortLink(String url, String cookie) {
        HttpURLConnection c = null;
        try {
            // withReferer=true：b23.tv 在缺 Referer 时可能返回一个 HTML 中转页
            // 而不是 302，那样就跟不到真实地址了
            c = Http.open(url, cookie, true);
            c.getResponseCode();
            Http.closeQuietly(c.getInputStream());
            String real = c.getURL().toString();
            String id = extractId(real);
            return isResolved(id) ? id : "";
        } catch (IOException e) {
            return "";
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * 带错误码的接口异常。
     *
     * <p>这个类刻意不依赖任何 Android API —— 正因为如此 {@code tools/desktop-verify/TestApi}
     * 才能在桌面上直接跑。所以它不能调用 {@code getString()}。</p>
     *
     * <p>解法是把接口返回的 {@code code} 原样抛出来，由界面层映射成用户看得懂的问题与恢复方式。
     * 界面**不应该**靠匹配异常消息里的中文子串来决定显示哪条错误 —— 那样改一个字就会静默失效。</p>
     */
    public static class ApiException extends IOException {

        /** 接口返回的业务码；本地校验失败时用下面三个哨兵码。 */
        public final int code;

        /** 出错时正在做什么，例如「获取视频信息」。 */
        public final String what;

        public ApiException(int code, String what, String message) {
            super(message);
            this.code = code;
            this.what = what;
        }
    }

    /** 哨兵码：链接/BV 号无法识别。 */
    public static final int CODE_BAD_INPUT = 1;
    /** 哨兵码：稿件没有可用的 cid。 */
    public static final int CODE_NO_CID = 2;
    /** 哨兵码：没有可用的 DASH 流。 */
    public static final int CODE_NO_DASH = 3;

    /** 获取稿件基本信息与分 P 列表。 */
    public static Model.Video view(String rawId, String cookie) throws IOException {
        String id = resolveId(rawId, cookie);
        String query;
        if (id.regionMatches(true, 0, "BV", 0, 2)) {
            query = "bvid=" + WbiSigner.encode(id);
        } else if (id.regionMatches(true, 0, "av", 0, 2)) {
            query = "aid=" + id.substring(2);
        } else if (id.matches("\\d+")) {
            query = "aid=" + id;
        } else {
            throw new ApiException(CODE_BAD_INPUT, "解析链接",
                    "无法识别的视频标识：" + rawId);
        }

        JSONObject root = Json.parse(Http.get(VIEW_URL + "?" + query, cookie));
        checkCode(root, "获取视频信息");
        JSONObject d = Json.obj(root, "data");

        Model.Video v = new Model.Video();
        v.bvid = d.optString("bvid");
        v.aid = d.optLong("aid");
        v.title = d.optString("title");
        v.desc = d.optString("desc");
        v.cover = httpsify(d.optString("pic"));
        v.duration = d.optInt("duration");
        JSONObject owner = d.optJSONObject("owner");
        v.owner = owner == null ? "" : owner.optString("name");

        JSONArray pages = d.optJSONArray("pages");
        if (pages != null && pages.length() > 0) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject p = Json.obj(pages, i);
                Model.Part part = new Model.Part();
                part.index = p.optInt("page", i + 1);
                part.cid = p.optLong("cid");
                part.title = p.optString("part");
                part.duration = p.optInt("duration");
                if (part.title.isEmpty()) {
                    part.title = "P" + part.index;
                }
                v.pages.add(part);
            }
        } else {
            // 部分特殊稿件（如互动视频）没有 pages，退化为单 P
            Model.Part part = new Model.Part();
            part.index = 1;
            part.cid = d.optLong("cid");
            part.title = v.title;
            part.duration = v.duration;
            v.pages.add(part);
        }
        if (v.pages.isEmpty() || v.pages.get(0).cid == 0) {
            throw new ApiException(CODE_NO_CID, "获取视频信息",
                    "未取到 cid，该稿件可能受版权限制或为付费内容");
        }
        return v;
    }

    /** Cookie 里是否带着非空的 SESSDATA。 */
    public static boolean loggedIn(String cookie) {
        if (cookie == null) {
            return false;
        }
        Matcher m = P_SESSDATA.matcher(cookie);
        return m.find() && !m.group(1).isEmpty();
    }

    /**
     * 走旧端点取播放地址。参数形状与实测时完全一致 —— 不加
     * {@code platform} / {@code high_quality}，因为实测中没有它们也能出 1080P，
     * 而加了之后的效果没有验证过。
     */
    private static JSONObject requestPlain(Map<String, Object> params, String cookie)
            throws IOException {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("try_look", 1);
        return Json.parse(Http.get(PLAYURL_URL_PLAIN + "?" + encodeQuery(p), cookie));
    }

    /** 走 WBI 端点取播放地址，保留原有的参数与 -403 重试逻辑。 */
    private static JSONObject requestWbi(Map<String, Object> params, String cookie)
            throws IOException {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("platform", "pc");
        p.put("high_quality", 1);

        String query = WbiSigner.get().sign(p, cookie);
        JSONObject root = Json.parse(Http.get(PLAYURL_URL + "?" + query, cookie));
        if (root.optInt("code") == -403) {
            // 密钥过期会导致 -403，刷新后重试一次
            WbiSigner.get().invalidate();
            query = WbiSigner.get().sign(p, cookie);
            root = Json.parse(Http.get(PLAYURL_URL + "?" + query, cookie));
        }
        return root;
    }

    /** 响应里是否有可用的 DASH 视频轨。用于决定要不要回落到 WBI 端点。 */
    private static boolean hasStreams(JSONObject root) {
        if (root == null || root.optInt("code") != 0) {
            return false;
        }
        JSONObject d = root.optJSONObject("data");
        if (d == null) {
            return false;
        }
        JSONObject dash = d.optJSONObject("dash");
        if (dash == null) {
            return false;
        }
        JSONArray videos = dash.optJSONArray("video");
        return videos != null && videos.length() > 0;
    }

    /** 拼查询串。当前用到的值都是纯数字或 ID，编码只是为了不留坑。 */
    private static String encodeQuery(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(urlEncode(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;   // UTF-8 一定存在，走不到这里
        }
    }

    /**
     * 获取 DASH 播放地址。
     *
     * <p>{@code qn} 这里传的是**用户选中的那一档**，但它只影响
     * 非 DASH 的老接口行为。开了 {@code fnval} 的 DASH 请求，
     * 服务端返回的是账号能拿到的**全部**档位（{@code dash.video} 是一个数组），
     * 具体用哪一档由调用方在返回结果里挑。</p>
     */
    public static Model.PlayInfo playurl(String bvid, long cid, int qn, String cookie)
            throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        // 一律按最高档请示。原项目（bilibilias）也是固定发 127。
        // 用户真正选的那一档在拿到 dash.video 之后再挑。
        params.put("qn", QN_MAX);
        params.put("fnver", 0);
        params.put("fnval", FNVAL);
        params.put("fourk", 1);

        JSONObject root;
        if (loggedIn(cookie)) {
            // 已登录：保持原样走 WBI 端点。这条路径没法在桌面复现（需要真 Cookies），
            // 所以一行都不改，免得出新问题。
            root = requestWbi(params, cookie);
        } else {
            // 未登录：旧端点 + try_look=1，这是唯一能拿到 1080P 的组合。
            root = requestPlain(params, cookie);
            if (!hasStreams(root)) {
                // 旧端点被下线或临时抽风时回落到 WBI。宁可退回 480P，
                // 也不能让「画质优化」反而变成新的打不开。
                root = requestWbi(params, cookie);
            }
        }
        checkCode(root, "获取播放地址");

        JSONObject d = Json.obj(root, "data");
        Model.PlayInfo info = new Model.PlayInfo();

        parseQualities(d, info);

        JSONObject dash = d.optJSONObject("dash");
        if (dash == null) {
            throw new ApiException(CODE_NO_DASH, "获取播放地址",
                    "接口未返回 DASH 流。该视频可能是付费/大会员专享内容，"
                            + "或需要填写有效的 SESSDATA。");
        }

        JSONArray videos = dash.optJSONArray("video");
        if (videos == null || videos.length() == 0) {
            throw new ApiException(CODE_NO_DASH, "获取播放地址", "DASH 流中没有可用的视频轨");
        }
        for (int i = 0; i < videos.length(); i++) {
            info.videos.add(readStream(Json.obj(videos, i), true));
        }
        info.videos.sort(Comparator
                .comparingInt((Model.Stream s) -> s.quality).reversed()
                .thenComparing(Comparator.comparingLong((Model.Stream s) -> s.bandwidth).reversed()));

        // 把「报了但没有流」的档位从列表里剔掉。
        //
        // support_formats 列的是账号有权「看到」的档位，dash 列的才是真正「给的」。
        // 两者不一致是常态 —— 未登录时 support_formats 照样报 112(1080P 高码率)
        // 和 120(4K 超高清)，但 dash 里最高只有 80。留着它，用户会选到一个下不到的
        // 档位，再被 Model.videoByQuality 的兜底逻辑静默换成 1080P，等于界面在骗人。
        //
        // 但剔掉不等于当没发生过：被剔掉的档位记进 lockedQualities，
        // 界面据此说明「还有 4K，但要大会员」。否则用户只会看到列表里少了几档，
        // 以为这个应用不支持高画质。
        java.util.Set<Integer> available = new java.util.HashSet<>();
        for (Model.Stream s : info.videos) {
            available.add(s.quality);
        }
        for (java.util.Iterator<java.util.Map.Entry<Integer, String>> it =
                info.qualities.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<Integer, String> e = it.next();
            if (!available.contains(e.getKey())) {
                info.lockedQualities.put(e.getKey(), e.getValue());
                it.remove();
            }
        }

        JSONArray audios = dash.optJSONArray("audio");
        if (audios != null) {
            for (int i = 0; i < audios.length(); i++) {
                info.audios.add(readStream(Json.obj(audios, i), false));
            }
        }
        // 部分稿件把音频放在 dolby / flac 节点
        appendAudio(dash.optJSONObject("dolby"), info);
        appendAudio(dash.optJSONObject("flac"), info);

        info.audioOnlySupported = !info.audios.isEmpty();
        return info;
    }

    private static void appendAudio(JSONObject node, Model.PlayInfo info) {
        if (node == null) {
            return;
        }
        JSONArray arr = node.optJSONArray("audio");
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                info.audios.add(readStream(o, false));
            }
        }
    }

    private static Model.Stream readStream(JSONObject o, boolean video) {
        Model.Stream s = new Model.Stream();
        // 统一升级成 https：明文地址会被 manifest 的 usesCleartextTraffic=false
        // 拦掉，而这类失败在代码里是静默的
        s.url = httpsify(firstNonEmpty(o.optString("baseUrl"), o.optString("base_url")));
        JSONArray bu = o.optJSONArray("backupUrl");
        if (bu == null) {
            bu = o.optJSONArray("backup_url");
        }
        if (bu != null) {
            for (int i = 0; i < bu.length(); i++) {
                String u = httpsify(bu.optString(i));
                if (!u.isEmpty()) {
                    s.backups.add(u);
                }
            }
        }
        s.quality = o.optInt("id");
        s.codecId = o.optInt("codecid");
        s.bandwidth = o.optLong("bandwidth");
        s.width = o.optInt("width");
        s.height = o.optInt("height");
        s.mimeType = firstNonEmpty(o.optString("mimeType"), o.optString("mime_type"));
        if (!video) {
            s.width = 0;
            s.height = 0;
        }
        return s;
    }

    /**
     * 把接口返回的地址升级为 https。
     *
     * <p>B 站好几个接口（封面 {@code pic}、部分 durl）至今返回的是明文 {@code http://}，
     * 而应用的 manifest 里 {@code usesCleartextTraffic="false"} 会直接拦掉这类请求 ——
     * 表现就是封面永远加载不出来，还不报错。同一个路径 https 是可用的，所以统一改写。</p>
     *
     * <p>顺带处理协议相对地址（{@code //host/path}）。</p>
     */
    static String httpsify(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    /**
     * 取预览播放源。
     *
     * <p>用 {@code fnval=1} 走老的 durl 模式，拿到的是音视频**已合体**的渐进式 MP4，
     * 系统 {@code MediaPlayer} 才能边下边播并自由拖动。DASH 的分离流做不到这点。</p>
     *
     * <p>画质取最低档（默认 360P）：预览要的是尽快出画面，不是画质。</p>
     */
    public static Model.PreviewSource previewSource(String bvid, long cid, int qn, String cookie)
            throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", qn);
        params.put("fnver", 0);
        params.put("fnval", 1);
        params.put("fourk", 1);
        params.put("platform", "pc");
        params.put("high_quality", 1);

        String query = WbiSigner.get().sign(params, cookie);
        JSONObject root = Json.parse(Http.get(PLAYURL_URL + "?" + query, cookie));

        if (root.optInt("code") == -403) {
            // 密钥过期会导致 -403，刷新后重试一次
            WbiSigner.get().invalidate();
            query = WbiSigner.get().sign(params, cookie);
            root = Json.parse(Http.get(PLAYURL_URL + "?" + query, cookie));
        }
        checkCode(root, "获取预览地址");

        JSONObject d = Json.obj(root, "data");
        JSONArray durl = d.optJSONArray("durl");
        if (durl == null || durl.length() == 0) {
            throw new ApiException(CODE_NO_DASH, "获取预览地址",
                    "接口没有返回可播放的完整文件。该稿件可能是付费或大会员专享内容");
        }

        Model.PreviewSource src = new Model.PreviewSource();
        src.quality = d.optInt("quality", qn);
        src.format = d.optString("format", "mp4");
        src.durationMs = d.optLong("timelength", 0L);

        // durl 可能被切成多段（老 FLV 常见）。系统播放器播不了多段，
        // 所以只取第一段，并在长度上做提示。
        JSONObject first = Json.obj(durl, 0);
        src.url = httpsify(first.optString("url"));
        src.size = first.optLong("size", 0L);
        for (int i = 1; i < durl.length() && i <= 4; i++) {
            String backup = httpsify(Json.obj(durl, i).optString("url"));
            if (!backup.isEmpty()) {
                src.backups.add(backup);
            }
        }
        if (src.url.isEmpty()) {
            throw new ApiException(CODE_NO_DASH, "获取预览地址", "预览地址为空");
        }
        return src;
    }

    private static void parseQualities(JSONObject d, Model.PlayInfo info) {
        JSONArray sf = d.optJSONArray("support_formats");
        if (sf != null) {
            for (int i = 0; i < sf.length(); i++) {
                JSONObject f = sf.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                int q = f.optInt("quality");
                String desc = firstNonEmpty(f.optString("new_description"),
                        firstNonEmpty(f.optString("display_desc"), f.optString("description")));
                if (q > 0) {
                    info.qualities.put(q, desc.isEmpty() ? qnName(q) : desc);
                }
            }
        }
        if (info.qualities.isEmpty()) {
            JSONArray aq = d.optJSONArray("accept_quality");
            JSONArray ad = d.optJSONArray("accept_description");
            if (aq != null) {
                for (int i = 0; i < aq.length(); i++) {
                    int q = aq.optInt(i);
                    String desc = ad != null && i < ad.length() ? ad.optString(i) : "";
                    info.qualities.put(q, desc.isEmpty() ? qnName(q) : desc);
                }
            }
        }
    }

    /**
     * 档位号 → 中文名。
     *
     * <p>只在服务端没给描述时兜底。服务端通常会给（`new_description` 形如
     * 「4K 超高清」「杜比视界」），但个别稿件只回 `accept_quality` 这个纯数字
     * 数组，那时贴着 `qn120` 这种内部编号给用户看是没有意义的。</p>
     *
     * <p>这些档位里的 120/125/126/127 都需要大会员，未登录时不会出现在
     * 可下载列表里，但它们会出现在 {@link Model.PlayInfo#lockedQualities}，
     * 也就是那句「需要大会员」的提示里 —— 所以名字得对。</p>
     */
    private static String qnName(int qn) {
        switch (qn) {
            case 127: return "8K 超高清";
            case 126: return "杜比视界";
            case 125: return "HDR 真彩";
            case 120: return "4K 超高清";
            case 116: return "1080P 60帧";
            case 112: return "1080P 高码率";
            case 80:  return "1080P 高清";
            case 74:  return "720P 60帧";
            case 64:  return "720P 准高清";
            case 32:  return "480P 标清";
            case 16:  return "360P 流畅";
            case 6:   return "240P 极速";
            default:  return "qn" + qn;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    /**
     * 获取设备指纹 buvid3。缺失时部分接口会返回 -352 风控错误。
     *
     * @return 失败时返回空串，不抛异常
     */
    public static String fetchBuvid3() {
        try {
            JSONObject root = new JSONObject(Http.get(
                    "https://api.bilibili.com/x/frontend/finger/spi", "", false));
            JSONObject d = root.optJSONObject("data");
            return d == null ? "" : d.optString("b_3", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void checkCode(JSONObject root, String what) throws IOException {
        int code = root.optInt("code", -1);
        if (code == 0) {
            return;
        }
        String msg = root.optString("message", "");
        String hint = "";
        switch (code) {
            case -101:
                hint = "（账号未登录，请在设置里填写 SESSDATA）";
                break;
            case -352:
                hint = "（风控校验失败，请更新 Cookie 或稍后再试）";
                break;
            case -403:
                hint = "（访问权限不足，可能需要大会员账号）";
                break;
            case -404:
                hint = "（稿件不存在或已被删除）";
                break;
            case -799:
                hint = "（请求过于频繁，请稍后重试）";
                break;
            case 62002:
                hint = "（稿件不可见）";
                break;
            case 62004:
                hint = "（稿件审核中）";
                break;
            default:
                break;
        }
        throw new ApiException(code, what,
                what + "失败：code=" + code + " " + msg + hint);
    }
}
