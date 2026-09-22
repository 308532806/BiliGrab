import com.biligrab.downloader.Http;
import com.biligrab.downloader.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube 取流可行性探针。
 *
 * <p>要回答的问题是：不引入任何第三方库（只有 org.json + HttpURLConnection），
 * 能不能拿到 YouTube 的高画质直链？</p>
 *
 * <p>测两条路径：</p>
 * <ol>
 *   <li>抓 watch 页面 HTML，从里面抠出 {@code ytInitialPlayerResponse}</li>
 *   <li>直接打 InnerTube API（{@code /youtubei/v1/player}），拿干净 JSON</li>
 * </ol>
 *
 * <p>对拿到的每个格式，重点看两件事：<b>有没有直接的 url 字段</b>
 * （还是只有需要跑 JS 才能解的 signatureCipher），以及<b>那个 url 到底能不能下到数据</b>。</p>
 */
public final class ProbeYouTube {

    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** InnerTube 的公开 web key，网页端自己在用，写死在页面里。 */
    private static final String WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

    public static void main(String[] args) throws Exception {
        String videoId = args.length > 0 ? args[0] : "dQw4w9WgXcQ";
        System.out.println("=== YouTube 取流探针  videoId=" + videoId + " ===\n");

        JSONObject fromHtml = tryHtmlScrape(videoId);
        JSONObject fromInnerTube = tryInnerTube(videoId);

        report("路径 A：HTML 抓取 ytInitialPlayerResponse", fromHtml);
        report("路径 B：InnerTube /youtubei/v1/player", fromInnerTube);

        JSONObject best = fromHtml != null ? fromHtml : fromInnerTube;
        if (best != null) {
            probeDownload(best, videoId);
        }
    }

    // ==================================================================
    // 路径 A：抓 HTML
    // ==================================================================

    private static JSONObject tryHtmlScrape(String videoId) {
        System.out.println("--- 路径 A：抓 watch 页面 ---");
        try {
            String url = "https://www.youtube.com/watch?v=" + videoId + "&hl=en&gl=US";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", CHROME_UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            String html = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
            System.out.println("  HTTP " + code + "  页面 " + html.length() + " 字符");

            String marker = "ytInitialPlayerResponse";
            int at = html.indexOf(marker);
            if (at < 0) {
                System.out.println("  页面里找不到 " + marker);
                return null;
            }
            // 从 marker 之后的第一个 '{' 开始做括号配对，配对时要跳过字符串里的花括号和转义
            int start = html.indexOf('{', at);
            if (start < 0) {
                System.out.println("  marker 之后没有 '{'");
                return null;
            }
            String jsonText = extractBalanced(html, start);
            System.out.println("  抠出 JSON 长度 " + (jsonText == null ? 0 : jsonText.length()));
            return jsonText == null ? null : Json.parse(jsonText);
        } catch (Exception e) {
            System.out.println("  失败: " + e);
            return null;
        }
    }

    /**
     * 从 {@code start} 处的 '{' 开始，返回配对完整的那个 JSON 对象子串。
     *
     * <p>不能简单地找下一个 {@code "};"} —— JSON 里的字符串值完全可能包含
     * 花括号和引号，必须真的按 JSON 的字符串/转义规则走一遍。</p>
     */
    private static String extractBalanced(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (ch == '\\') {
                if (inStr) {
                    esc = true;
                }
                continue;
            }
            if (ch == '"') {
                inStr = !inStr;
                continue;
            }
            if (inStr) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ==================================================================
    // 路径 B：InnerTube
    // ==================================================================

    private static JSONObject tryInnerTube(String videoId) {
        System.out.println("\n--- 路径 B：InnerTube API ---");
        JSONObject out = null;
        // 试几种客户端。不同客户端对 PoToken 的要求不一样，
        // 网页端现在越收越紧，安卓/TV 端反而常常更宽松。
        String[][] clients = {
                {"WEB", "2", "1.20240101.00.00"},
                {"ANDROID", "3", "19.09.37"},
                {"TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0"},
        };
        for (String[] cl : clients) {
            try {
                JSONObject ctx = new JSONObject();
                JSONObject client = new JSONObject();
                client.put("clientName", cl[0]);
                client.put("clientVersion", cl[2]);
                client.put("hl", "en");
                client.put("gl", "US");
                ctx.put("client", client);

                JSONObject body = new JSONObject();
                body.put("context", ctx);
                body.put("videoId", videoId);
                body.put("contentCheckOk", true);
                body.put("racyCheckOk", true);

                String url = "https://www.youtube.com/youtubei/v1/player?key=" + WEB_KEY
                        + "&prettyPrint=false";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", cl[0].startsWith("WEB") ? CHROME_UA
                        : "com.google.android.youtube/" + cl[2] + " (Linux; U; Android 12) gzip");
                c.setRequestProperty("X-YouTube-Client-Name", cl[1]);
                c.setRequestProperty("X-YouTube-Client-Version", cl[2]);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
                int code = c.getResponseCode();
                String text = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
                System.out.println("  client=" + cl[0] + "  HTTP " + code + "  " + text.length() + " 字符");

                JSONObject o = Json.parse(text);
                String status = o.optJSONObject("playabilityStatus") == null ? "?"
                        : o.optJSONObject("playabilityStatus").optString("status", "?");
                System.out.println("    playabilityStatus=" + status);
                if ("OK".equals(status) && o.optJSONObject("streamingData") != null) {
                    System.out.println("    ✓ 这个客户端能拿到 streamingData");
                    if (out == null) {
                        out = o;
                    }
                } else {
                    String reason = o.optJSONObject("playabilityStatus") == null ? ""
                            : o.optJSONObject("playabilityStatus").optString("reason", "");
                    System.out.println("    reason: " + reason);
                }
            } catch (Exception e) {
                System.out.println("  client=" + cl[0] + "  失败: " + e);
            }
        }
        return out;
    }

    // ==================================================================
    // 报告
    // ==================================================================

    private static void report(String label, JSONObject player) {
        System.out.println("\n=== " + label + " ===");
        if (player == null) {
            System.out.println("  没拿到 playerResponse");
            return;
        }
        JSONObject ps = player.optJSONObject("playabilityStatus");
        if (ps != null) {
            System.out.println("  status = " + ps.optString("status"));
            if (ps.has("reason")) {
                System.out.println("  reason = " + ps.optString("reason"));
            }
        }
        JSONObject vd = player.optJSONObject("videoDetails");
        if (vd != null) {
            System.out.println("  标题   = " + vd.optString("title"));
            System.out.println("  时长   = " + vd.optLong("lengthSeconds") + "s");
            System.out.println("  作者   = " + vd.optString("author"));
        }
        JSONObject sd = player.optJSONObject("streamingData");
        if (sd == null) {
            System.out.println("  ✗ 没有 streamingData");
            return;
        }
        System.out.println("  过期于 = " + sd.optLong("expiresInSeconds") + "s 后");

        summarizeList("muxed  formats", sd.optJSONArray("formats"));
        summarizeList("adaptiveFormats", sd.optJSONArray("adaptiveFormats"));
    }

    private static void summarizeList(String name, JSONArray arr) {
        if (arr == null) {
            System.out.println("  " + name + ": 无");
            return;
        }
        int plain = 0;
        int cipher = 0;
        int withN = 0;
        System.out.println("  " + name + ": " + arr.length() + " 个");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) {
                continue;
            }
            boolean hasUrl = f.has("url");
            if (hasUrl) {
                plain++;
                String u = f.optString("url");
                if (u.contains("&n=") || u.contains("?n=")) {
                    withN++;
                }
            } else if (f.has("signatureCipher") || f.has("cipher")) {
                cipher++;
            }
            String mime = f.optString("mimeType");
            String shortMime = mime.contains(";") ? mime.substring(0, mime.indexOf(';')) : mime;
            System.out.printf("    [%s] %-10s %-22s %6s kbps  %9s bytes  %s%s%n",
                    f.optString("itag"),
                    f.optString("qualityLabel", "-"),
                    shortMime,
                    f.optInt("bitrate") / 1000,
                    f.optString("contentLength", "-"),
                    hasUrl ? "url直链" : "signatureCipher",
                    hasUrl && (f.optString("url").contains("&n=")
                            || f.optString("url").contains("?n=")) ? " +含n参数" : "");
        }
        System.out.println("    → 直链 " + plain + " 个，需解密 " + cipher + " 个，其中带 n 参数 " + withN + " 个");
    }

    // ==================================================================
    // 实测下载
    // ==================================================================

    private static void probeDownload(JSONObject player, String videoId) {
        System.out.println("\n=== 实测：拿最好的视频直链下 512KB 看看 ===");
        JSONObject sd = player.optJSONObject("streamingData");
        if (sd == null) {
            System.out.println("  没有 streamingData");
            return;
        }
        JSONArray af = sd.optJSONArray("adaptiveFormats");
        if (af == null) {
            System.out.println("  没有 adaptiveFormats");
            return;
        }
        List<JSONObject> plain = new ArrayList<>();
        for (int i = 0; i < af.length(); i++) {
            JSONObject f = af.optJSONObject(i);
            if (f != null && f.has("url")) {
                plain.add(f);
            }
        }
        if (plain.isEmpty()) {
            System.out.println("  ✗ 没有一个 adaptiveFormat 带直链，全部需要跑 JS 解密");
            return;
        }
        // 挑分辨率最高的那个视频轨
        JSONObject best = null;
        int bestH = -1;
        for (JSONObject f : plain) {
            if (!f.optString("mimeType").startsWith("video/")) {
                continue;
            }
            int h = f.optInt("height", 0);
            if (h > bestH) {
                bestH = h;
                best = f;
            }
        }
        if (best == null) {
            System.out.println("  没有视频轨");
            return;
        }
        System.out.println("  选中: itag=" + best.optString("itag")
                + "  " + best.optString("qualityLabel")
                + "  " + best.optString("mimeType")
                + "  " + best.optLong("contentLength") + " bytes");
        String u = best.optString("url");
        System.out.println("  URL 片段: " + u.substring(0, Math.min(150, u.length())) + "...");
        System.out.println("  URL 里有 n 参数: " + (u.contains("&n=") || u.contains("?n=")));

        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("User-Agent", CHROME_UA);
            c.setRequestProperty("Range", "bytes=0-524287");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            long t0 = System.currentTimeMillis();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] head = in == null ? new byte[0] : readUpTo(in, 524288);
            long ms = Math.max(1, System.currentTimeMillis() - t0);
            System.out.println("  HTTP " + code
                    + "  Content-Range=" + c.getHeaderField("Content-Range")
                    + "  Content-Type=" + c.getHeaderField("Content-Type"));
            System.out.println("  实际读到 " + head.length + " 字节，耗时 " + ms + "ms  → "
                    + (head.length / 1024.0 / (ms / 1000.0)) + " KB/s");
            System.out.print("  头 16 字节: ");
            for (int i = 0; i < Math.min(16, head.length); i++) {
                System.out.printf("%02X ", head[i]);
            }
            System.out.println(head.length >= 8
                    ? "\n  （MP4 的话第 4-8 字节应为 'ftyp'）" : "");
            // MP4 box 结构
            if (head.length >= 12) {
                String box = new String(head, 4, 4, StandardCharsets.US_ASCII);
                System.out.println("  第 2 个 box: '" + box + "'");
            }
        } catch (Exception e) {
            System.out.println("  下载失败: " + e);
        }
    }

    // ==================================================================

    private static byte[] readUpTo(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while (bos.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - bos.size()))) > 0) {
            bos.write(buf, 0, n);
        }
        Http.closeQuietly(in);
        return bos.toByteArray();
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        Http.closeQuietly(in);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private ProbeYouTube() {
    }
}
