package com.biligrab.downloader;

import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B 站 WBI 风控签名实现。
 *
 * <p>自 2023 年起，B 站的 {@code /x/player/wbi/playurl} 等接口要求携带
 * {@code wts} + {@code w_rid} 两个参数，否则返回 {@code -403 访问权限不足}。
 * 签名流程：</p>
 *
 * <ol>
 *   <li>从 {@code /x/web-interface/nav} 取得 {@code wbi_img.img_url} 与
 *       {@code wbi_img.sub_url}；</li>
 *   <li>取两个 URL 的文件名（去掉扩展名）拼接得到 {@code imgKey + subKey}；</li>
 *   <li>按 {@link #MIXIN_KEY_ENC_TAB} 重排后取前 32 位，得到 {@code mixin_key}；</li>
 *   <li>业务参数按 key 升序排列、过滤 {@code !'()*} 字符、URL 编码，
 *       末尾拼上 {@code mixin_key} 后取 MD5，即为 {@code w_rid}。</li>
 * </ol>
 */
public final class WbiSigner {

    /** 官方前端内置的重排表，共 64 项。 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
    };

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final long TTL_MS = 30L * 60L * 1000L;

    private static final WbiSigner INSTANCE = new WbiSigner();

    private String mixinKey = "";
    private long expireAt = 0L;

    private WbiSigner() {
    }

    public static WbiSigner get() {
        return INSTANCE;
    }

    /** 强制失效缓存，下次签名时重新拉取 nav。 */
    public synchronized void invalidate() {
        mixinKey = "";
        expireAt = 0L;
    }

    /** 获取（并缓存）mixin_key。 */
    public synchronized String mixinKey(String cookie) throws IOException {
        long now = System.currentTimeMillis();
        if (mixinKey.length() == 32 && now < expireAt) {
            return mixinKey;
        }
        String body = Http.get(NAV_URL, cookie, true);
        JSONObject root = Json.parse(body);
        JSONObject data = root.optJSONObject("data");
        JSONObject wbi = data == null ? null : data.optJSONObject("wbi_img");
        if (wbi == null) {
            throw new IOException("无法获取 WBI 密钥（nav 返回 code="
                    + root.optInt("code") + " " + root.optString("message") + "）");
        }
        String imgKey = fileStem(wbi.optString("img_url"));
        String subKey = fileStem(wbi.optString("sub_url"));
        if (imgKey.length() == 0 || subKey.length() == 0) {
            throw new IOException("WBI 密钥字段为空，接口结构可能已变更");
        }
        this.mixinKey = deriveMixinKey(imgKey + subKey);
        this.expireAt = now + TTL_MS;
        return this.mixinKey;
    }

    /**
     * 对参数做 WBI 签名。
     *
     * @return 已排序并编码好的完整 query string，形如 {@code a=1&b=2&wts=...&w_rid=...}
     */
    public String sign(Map<String, ?> params, String cookie) throws IOException {
        String mk = mixinKey(cookie);

        Map<String, String> p = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : params.entrySet()) {
            Object v = e.getValue();
            if (v != null) {
                p.put(e.getKey(), String.valueOf(v));
            }
        }
        p.put("wts", String.valueOf(System.currentTimeMillis() / 1000L));

        List<String> keys = new ArrayList<>(p.keySet());
        Collections.sort(keys);

        StringBuilder q = new StringBuilder(192);
        for (String k : keys) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(encode(k)).append('=').append(encode(sanitize(p.get(k))));
        }
        return q + "&w_rid=" + md5(q.toString() + mk);
    }

    /** 去掉 {@code !'()*} —— 这些字符不参与签名。 */
    private static String sanitize(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '!' || ch == '\'' || ch == '(' || ch == ')' || ch == '*') {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String fileStem(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String deriveMixinKey(String raw) {
        StringBuilder sb = new StringBuilder(64);
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (idx < raw.length()) {
                sb.append(raw.charAt(idx));
            }
        }
        return sb.length() >= 32 ? sb.substring(0, 32) : sb.toString();
    }

    public static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
