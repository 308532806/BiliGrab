import com.biligrab.downloader.BiliApi;
import com.biligrab.downloader.Http;
import com.biligrab.downloader.Json;
import com.biligrab.downloader.Model;
import com.biligrab.downloader.WbiSigner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 探针：预览播放器到底能不能用 fnval=1 拿到「音视频已合体」的渐进式 MP4。
 *
 * <p>这是整个预览方案的前提。DASH（fnval=4048）给的是分离的视频轨和音频轨，
 * 系统 MediaPlayer 播出来没有声音，而且不能自由拖动。老的 durl 模式
 * （fnval=1）给的是完整 MP4，正好符合需求。</p>
 *
 * <p>顺带验证两件事：URL 是 http 还是 https（决定会不会被
 * {@code usesCleartextTraffic=false} 拦掉），以及不带 Referer 能不能取到数据。</p>
 */
public final class ProbePreview {

    private static final String PLAYURL = "https://api.bilibili.com/x/player/wbi/playurl";

    public static void main(String[] args) throws Exception {
        String bvid = args.length > 0 ? args[0] : null;
        if (bvid == null) {
            JSONObject hot = Json.parse(Http.get(
                    "https://api.bilibili.com/x/web-interface/popular?ps=1&pn=1", "", false));
            bvid = hot.getJSONObject("data").getJSONArray("list").getJSONObject(0)
                    .optString("bvid");
        }
        System.out.println("测试稿件: " + bvid);

        Model.Video v = BiliApi.view(bvid, "");
        long cid = v.pages.get(0).cid;
        System.out.println("标题: " + v.title);
        System.out.println("cid : " + cid);
        System.out.println("pic : " + v.cover);
        System.out.println("pic 协议: " + (v.cover.startsWith("http://")
                ? "明文 http  ← 会被 usesCleartextTraffic=false 拦截" : "https"));
        System.out.println();

        for (int fnval : new int[] {1, 0}) {
            System.out.println("========== fnval=" + fnval + " ==========");
            try {
                probe(bvid, cid, fnval);
            } catch (Exception e) {
                System.out.println("  失败: " + e);
            }
            System.out.println();
        }
    }

    private static void probe(String bvid, long cid, int fnval) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", 16);          // 360P：预览要快，取最低档
        params.put("fnver", 0);
        params.put("fnval", fnval);
        params.put("fourk", 1);
        params.put("platform", "pc");
        params.put("high_quality", 1);

        String query = WbiSigner.get().sign(params, "");
        JSONObject root = Json.parse(Http.get(PLAYURL + "?" + query, ""));

        System.out.println("  code = " + root.optInt("code") + "  " + root.optString("message"));
        if (root.optInt("code") != 0) {
            return;
        }
        JSONObject d = root.optJSONObject("data");

        System.out.println("  返回的顶层字段: " + d.keySet());
        System.out.println("  quality = " + d.optInt("quality")
                + "  format = " + d.optString("format")
                + "  timelength = " + d.optLong("timelength") + "ms");

        JSONObject dash = d.optJSONObject("dash");
        JSONArray durl = d.optJSONArray("durl");

        System.out.println("  含 dash = " + (dash != null) + "    含 durl = " + (durl != null));

        if (durl == null || durl.length() == 0) {
            System.out.println("  → 没有 durl，这个档位拿不到渐进式文件");
            return;
        }

        JSONObject seg = durl.getJSONObject(0);
        String url = seg.optString("url");
        int segCount = seg.optInt("order", 1);
        long size = seg.optLong("size");
        int len = seg.optInt("length");
        System.out.println("  分片数 = " + durl.length() + "  首片 size = " + size
                + "  length = " + len + "ms");
        System.out.println("  url = " + url);

        boolean https = url.startsWith("https://");
        System.out.println("  协议: " + (https ? "https ✓" : "明文 http ← 会被系统拦截"));

        for (String mode : new String[] {"带 Referer", "不带 Referer"}) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", Http.UA);
                c.setRequestProperty("Range", "bytes=0-262143");   // 只取前 256KB
                if ("带 Referer".equals(mode)) {
                    c.setRequestProperty("Referer", Http.REFERER);
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] head = new byte[16];
                int n = 0;
                if (in != null) {
                    n = in.read(head);
                }
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.max(n, 0); i++) {
                    hex.append(String.format("%02X ", head[i]));
                }
                String tag = (n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y'
                        && head[7] == 'p') ? "✓ 是 MP4（ftyp box）" : "首字节不是 ftyp";
                System.out.println("  [" + mode + "] HTTP " + code + "  " + tag + "  " + hex);
            } catch (Exception e) {
                System.out.println("  [" + mode + "] 失败: " + e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
    }
}
