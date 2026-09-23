package com.biligrab.downloader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Map;

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

    /**
     * 把 {@code host:port} 解析成代理对象，无法解析或为空时返回 {@code null}（表示直连）。
     *
     * <p>允许用户写 {@code http://192.168.8.2:7890} 或 {@code 192.168.8.2:7890}，
     * 两种写法都很常见，多剥一层协议前缀成本极低。</p>
     */
    public static Proxy parseProxy(String spec) {
        if (spec == null) {
            return null;
        }
        String s = spec.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("http://")) {
            s = s.substring(7);
        } else if (s.startsWith("https://")) {
            s = s.substring(8);
        }
        // 去掉可能存在的路径部分
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return null;
        }
        try {
            String host = s.substring(0, colon);
            int port = Integer.parseInt(s.substring(colon + 1).trim());
            if (port <= 0 || port > 65535) {
                return null;
            }
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static HttpURLConnection open(String url, String cookie, boolean withReferer)
            throws IOException {
        return open(url, cookie, withReferer, null);
    }

    /** 剥掉协议前缀与路径，只留 {@code host:port}。解析不出时返回空串。 */
    private static String hostPort(String spec) {
        if (spec == null) {
            return "";
        }
        String s = spec.trim();
        if (s.startsWith("http://")) {
            s = s.substring(7);
        } else if (s.startsWith("https://")) {
            s = s.substring(8);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    /** 代理主机名。{@link LocalRelay} 要拿它去建原始 socket。 */
    public static String proxyHost(String spec) {
        String s = hostPort(spec);
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon).trim() : "";
    }

    /** 代理端口。解析失败返回 -1。 */
    public static int proxyPort(String spec) {
        String s = hostPort(spec);
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return -1;
        }
        try {
            int p = Integer.parseInt(s.substring(colon + 1).trim());
            return (p > 0 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从一个完整 URL 里取出主机名，取不到返回空串。 */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * @param proxy 为 {@code null} 时直连。B 站一路永远传 null ——
     *              它走代理只会更慢，还可能触发风控。代理只服务于 YouTube。
     */
    public static HttpURLConnection open(String url, String cookie, boolean withReferer, Proxy proxy)
            throws IOException {
        return open(url, cookie, withReferer, proxy, null);
    }

    /**
     * 带额外请求头的版本。
     *
     * <p>{@code extra} 在默认头之后写入，因此可以覆盖它们 —— YouTube 那条路
     * 必须用 yt-dlp 声明的 User-Agent，而不是这里为 B 站准备的桌面 Chrome。
     * 用别的 UA 打 googlevideo 会被判定成另一个客户端并返回 403。</p>
     *
     * @param extra 为 {@code null} 或空时行为与四参版本完全一致
     */
    public static HttpURLConnection open(String url, String cookie, boolean withReferer,
                                         Proxy proxy, Map<String, String> extra)
            throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (proxy == null
                ? u.openConnection()
                : u.openConnection(proxy));
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
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
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

    /**
     * 读取失败响应的正文，截断成一行，用于日志。
     *
     * <p>CDN 的 4xx 正文里往往写着真正的原因（签名过期、IP 不符、客户端不匹配），
     * 这是区分它们的唯一证据。读不到就返回空串，绝不因此抛异常 ——
     * 它只会被用在「已经出错」的分支里。</p>
     */
    public static String errorSnippet(HttpURLConnection c) {
        if (c == null) {
            return "";
        }
        InputStream in = null;
        try {
            in = c.getErrorStream();
            if (in == null) {
                return "";
            }
            return snippet(new String(readAll(in), "UTF-8"));
        } catch (Exception e) {
            return "";
        } finally {
            closeQuietly(in);
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
