package com.biligrab.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
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
     * fnval 位掩码：
     * 16=DASH, 64=HDR, 128=4K, 256=杜比音频, 512=杜比视界, 1024=8K, 2048=AV1。
     */
    private static final int FNVAL = 16 | 64 | 128 | 256 | 512 | 1024 | 2048;

    private static final Pattern P_BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern P_AV = Pattern.compile("(?i)av(\\d+)");

    private BiliApi() {
    }

    /** 从任意文本（分享链接、口令、纯 ID）中提取视频标识。 */
    public static String extractId(String raw) {        if (raw == null) {
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

    /** 获取稿件基本信息与分 P 列表。 */
    public static Model.Video view(String rawId, String cookie) throws IOException {
        String id = extractId(rawId);
        String query;
        if (id.regionMatches(true, 0, "BV", 0, 2)) {
            query = "bvid=" + WbiSigner.encode(id);
        } else if (id.regionMatches(true, 0, "av", 0, 2)) {
            query = "aid=" + id.substring(2);
        } else if (id.matches("\\d+")) {
            query = "aid=" + id;
        } else {
            throw new IOException("无法识别的视频标识：" + rawId);
        }

        JSONObject root = Json.parse(Http.get(VIEW_URL + "?" + query, cookie));
        checkCode(root, "获取视频信息");
        JSONObject d = Json.obj(root, "data");

        Model.Video v = new Model.Video();
        v.bvid = d.optString("bvid");
        v.aid = d.optLong("aid");
        v.title = d.optString("title");
        v.desc = d.optString("desc");
        v.cover = d.optString("pic");
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
            throw new IOException("未取到 cid，该稿件可能受版权限制或为付费内容");
        }
        return v;
    }

    /** 获取 DASH 播放地址。 */
    public static Model.PlayInfo playurl(String bvid, long cid, int qn, String cookie)
            throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", qn);
        params.put("fnver", 0);
        params.put("fnval", FNVAL);
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
        checkCode(root, "获取播放地址");

        JSONObject d = Json.obj(root, "data");
        Model.PlayInfo info = new Model.PlayInfo();

        parseQualities(d, info);

        JSONObject dash = d.optJSONObject("dash");
        if (dash == null) {
            throw new IOException("接口未返回 DASH 流。该视频可能是付费/大会员专享内容，"
                    + "或需要填写有效的 SESSDATA。");
        }

        JSONArray videos = dash.optJSONArray("video");
        if (videos == null || videos.length() == 0) {
            throw new IOException("DASH 流中没有可用的视频轨");
        }
        for (int i = 0; i < videos.length(); i++) {
            info.videos.add(readStream(Json.obj(videos, i), true));
        }
        info.videos.sort(Comparator
                .comparingInt((Model.Stream s) -> s.quality).reversed()
                .thenComparing(Comparator.comparingLong((Model.Stream s) -> s.bandwidth).reversed()));

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
        s.url = firstNonEmpty(o.optString("baseUrl"), o.optString("base_url"));
        JSONArray bu = o.optJSONArray("backupUrl");
        if (bu == null) {
            bu = o.optJSONArray("backup_url");
        }
        if (bu != null) {
            for (int i = 0; i < bu.length(); i++) {
                String u = bu.optString(i);
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
                    info.qualities.put(q, desc.isEmpty() ? ("qn" + q) : desc);
                }
            }
        }
        if (info.qualities.isEmpty()) {
            JSONArray aq = d.optJSONArray("accept_quality");
            JSONArray ad = d.optJSONArray("accept_description");
            if (aq != null) {
                for (int i = 0; i < aq.length(); i++) {
                    int q = aq.optInt(i);
                    String desc = ad != null && i < ad.length() ? ad.optString(i) : ("qn" + q);
                    info.qualities.put(q, desc);
                }
            }
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
        throw new IOException(what + "失败：code=" + code + " " + msg + hint);
    }
}
