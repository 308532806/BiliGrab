package com.biligrab.downloader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简 HTTP 客户端。
 *
 * <p>仅依赖 JDK/Android 自带的 {@link HttpURLConnection}，不引入 OkHttp 等第三方库，
 * 保证 APK 体积小、构建链路简单。</p>
 */
public final class Http {

    /** 必须使用桌面端 UA：B 站 CDN 对移动端 UA 有额外限制。 */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    /** 音视频 CDN 与接口均校验 Referer，缺失会返回 403。 */
    public static final String REFERER = "https://www.bilibili.com";

    private Http() {
    }

    public static HttpURLConnection open(String url, String cookie, boolean withReferer)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (withReferer) {
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("Origin", "https://www.bilibili.com");
        }
        if (cookie != null && !cookie.isEmpty()) {
            c.setRequestProperty("Cookie", cookie);
        }
        return c;
    }

    public static String get(String url, String cookie) throws IOException {
        return get(url, cookie, true);
    }

    public static String get(String url, String cookie, boolean withReferer) throws IOException {
        HttpURLConnection c = open(url, cookie, withReferer);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(readAll(in), "UTF-8");
            if (code >= 400) {
                throw new IOException("HTTP " + code + " " + snippet(body));
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            copy(in, bos, null, -1);
            return bos.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 流式拷贝，避免把整个视频读进内存。
     *
     * @param expected 期望总长度，&lt;=0 表示未知
     */
    public static long copy(InputStream in, OutputStream out, Progress progress, long expected)
            throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (progress != null) {
                progress.onBytes(total, expected);
            }
        }
        return total;
    }

    /** 静默关闭。用 {@link AutoCloseable} 而非 Closeable，以便同时接受流与 MediaExtractor。 */
    public static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** 下载进度回调。 */
    public interface Progress {
        void onBytes(long downloaded, long expected) throws IOException;
    }
}
