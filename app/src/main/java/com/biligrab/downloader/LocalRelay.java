package com.biligrab.downloader;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一条远端直链经代理转发到 {@code 127.0.0.1}，供系统播放器使用。
 *
 * <h3>为什么需要它</h3>
 * <p>{@link android.media.MediaPlayer} 由 native 的 NuPlayer 驱动，<b>不认</b>
 * 应用层配置的代理 —— {@code setDataSource} 只能吃一个 URL，没有任何入口能
 * 把 {@link java.net.Proxy} 传进去。真机上点预览的表现是
 * {@code setDataSource} → {@code prepareAsync} 之后就再无下文，一直等到超时。</p>
 *
 * <p>而 YouTube 在中国大陆又必须走代理。于是这里在本地起一个极小的 HTTP
 * 转发：MediaPlayer 连 {@code http://127.0.0.1:PORT/}，我们按上游代理的
 * 规矩把请求发出去，再把响应原样搬回来。对 MediaPlayer 来说这就是一个
 * 普通的本地 HTTP 地址，行为完全正常。</p>
 *
 * <h3>为什么请求是重新构造的</h3>
 * <p>不转发客户端原始请求行。MediaPlayer 发来的是
 * {@code GET / HTTP/1.1} 加 {@code Host: 127.0.0.1:port}，而经代理访问
 * 需要的是 {@code GET https://…googlevideo.com/… HTTP/1.1} 加目标主机的
 * Host。与其改写再解析，不如直接按目标地址造一个干净的请求，
 * 只把影响播放语义的 {@code Range} 带过去 —— 拖动进度条依赖它。</p>
 *
 * <h3>生命周期</h3>
 * <p>一个实例只服务一条直链。换视频要 {@link #stop()} 掉旧的再起新的，
 * 因为目标地址在这个实例里是常量。用完必须停，否则会一直占着一个端口。</p>
 */
public final class LocalRelay {

    private static final String TAG = "BiliGrab/Relay";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final ServerSocket server;
    private final Thread acceptor;
    private final String targetUrl;
    private final String proxyHost;
    private final int proxyPort;
    private final String targetHost;
    private volatile boolean running = true;

    private LocalRelay(ServerSocket server, String targetUrl,
                       String proxyHost, int proxyPort, String targetHost) {
        this.server = server;
        this.targetUrl = targetUrl;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.targetHost = targetHost;

        this.acceptor = new Thread(this::acceptLoop, "preview-relay");
        this.acceptor.setDaemon(true);
    }

    /**
     * 起一个转发。
     *
     * @param proxySpec {@code host:port}。为空时返回 {@code null} —— 直连场景
     *                  下 MediaPlayer 自己就能访问，不需要这一层，
     *                  多绕一跳反而增加失败面。
     * @return 可用的转发实例；直连时返回 {@code null}
     */
    public static LocalRelay start(String targetUrl, String proxySpec) {
        if (proxySpec == null || proxySpec.trim().isEmpty()) {
            return null;
        }
        String host = Http.proxyHost(proxySpec);
        int port = Http.proxyPort(proxySpec);
        if (host.isEmpty() || port <= 0) {
            Log.w(TAG, "代理 " + proxySpec + " 解析失败，预览将直连");
            return null;
        }
        String targetHost = Http.hostOf(targetUrl);
        if (targetHost.isEmpty()) {
            Log.w(TAG, "无法从直链解析出主机名，预览将直连");
            return null;
        }
        try {
            // 只绑回环。这是本机内部用的转发，没有任何理由让局域网能连上来。
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
            LocalRelay r = new LocalRelay(ss, targetUrl, host, port, targetHost);
            r.acceptor.start();
            Log.i(TAG, "预览转发已启动 127.0.0.1:" + ss.getLocalPort() + " → " + targetHost
                    + " 经 " + host + ":" + port);
            return r;
        } catch (IOException e) {
            Log.w(TAG, "预览转发启动失败，将直连", e);
            return null;
        }
    }

    /** 交给 MediaPlayer 的本地地址。 */
    public String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/";
    }

    /** 停掉转发并释放端口。重复调用安全。 */
    public void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关不掉也无所谓，进程退出时会释放
        }
        acceptor.interrupt();
    }

    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                client.setSoTimeout(READ_TIMEOUT_MS);
                serve(client);
            } catch (IOException e) {
                // server.close() 会让 accept 抛 SocketException，这是正常退出路径
                if (running) {
                    Log.w(TAG, "转发连接异常：" + e);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "转发连接异常：" + e);
            } finally {
                closeQuietly(client);
            }
        }
    }

    private void serve(Socket client) {
        Socket upstream = null;
        try {
            List<String> headers = readRequestHeaders(client.getInputStream());
            String range = headerValue(headers, "Range");
            String userAgent = headerValue(headers, "User-Agent");

            upstream = new Socket();
            upstream.connect(new InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(READ_TIMEOUT_MS);

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(targetUrl).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(targetHost).append("\r\n");
            req.append("Accept: */*\r\n");
            req.append("Accept-Encoding: identity\r\n");
            if (userAgent != null) {
                req.append("User-Agent: ").append(userAgent).append("\r\n");
            }
            if (range != null) {
                req.append("Range: ").append(range).append("\r\n");
            }
            // 用 close 界定响应结束，这样不必实现 chunked 解析
            req.append("Connection: close\r\n\r\n");

            OutputStream uOut = upstream.getOutputStream();
            uOut.write(req.toString().getBytes("ISO-8859-1"));
            uOut.flush();

            // 状态行、响应头、正文一起搬。MediaPlayer 自己会解析它们，
            // 我们没有任何理由去理解内容 —— 当字节流转发最不容易出错。
            InputStream uIn = upstream.getInputStream();
            OutputStream cOut = client.getOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while (running && (n = uIn.read(buf)) > 0) {
                cOut.write(buf, 0, n);
                cOut.flush();
            }
        } catch (SocketException e) {
            // 播放器拖动进度时会主动断开旧连接，这是完全正常的
            Log.i(TAG, "客户端断开连接（通常是拖动进度）");
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "转发失败：" + e);
            }
        } finally {
            closeQuietly(upstream);
        }
    }

    /** 一直读到空行，只看头部。请求没有正文，所以不必管 Content-Length。 */
    private static List<String> readRequestHeaders(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                String line = cur.toString();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.isEmpty()) {
                    break;
                }
                lines.add(line);
                cur.setLength(0);
            } else {
                cur.append((char) b);
            }
            if (cur.length() > 8192) {
                // 单个头部行不该有这么大。与其无限读下去，不如断开。
                throw new IOException("请求头过长");
            }
        }
        return lines;
    }

    /** 大小写不敏感地取一个头的值。 */
    private static String headerValue(List<String> headers, String name) {
        for (String h : headers) {
            int c = h.indexOf(':');
            if (c <= 0) {
                continue;
            }
            if (h.substring(0, c).trim().equalsIgnoreCase(name)) {
                return h.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // 已经关了
        }
    }
}
