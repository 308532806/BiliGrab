import com.biligrab.downloader.BiliApi;
import com.biligrab.downloader.Http;
import com.biligrab.downloader.Model;
import com.biligrab.downloader.WbiSigner;

import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * 桌面端联调测试：把 App 里的纯 Java 逻辑（Http / WbiSigner / Json / BiliApi / Model）
 * 拿到 JDK 上直接跑，验证 WBI 签名算法与接口解析是否正确。
 *
 * <p>这几个类刻意不依赖任何 Android API，因此可以脱离设备验证。</p>
 *
 * <pre>
 * 编译：
 *   javac -encoding UTF-8 -cp json-20240303.jar -d out \
 *       Http.java WbiSigner.java Json.java BiliApi.java Model.java TestApi.java
 * 运行：
 *   java -cp "out;json-20240303.jar" TestApi
 *   java -cp "out;json-20240303.jar" TestApi BV1xxxxxxxxx
 * </pre>
 *
 * 需要登录态才能验证高清画质时，设置环境变量 BILI_COOKIE="SESSDATA=xxxx"。
 */
public final class TestApi {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("\n[FAIL] 未预期的异常: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
        System.out.println("\n================ 结果 ================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void run(String[] args) throws Exception {
        String cookie = System.getenv("BILI_COOKIE");
        if (cookie == null) {
            cookie = "";
        }
        System.out.println("Cookie: " + (cookie.isEmpty() ? "(空，最高 480P)" : "(已提供)"));
        System.out.println("Java  : " + System.getProperty("java.version"));

        // ---------- 1. WBI 密钥 ----------
        System.out.println("\n=== 1/5 获取 WBI 密钥 ===");
        String mixin = WbiSigner.get().mixinKey(cookie);
        System.out.println("mixin_key = " + mixin);
        check("mixin_key 长度为 32", mixin.length() == 32);
        check("mixin_key 只含十六进制字符", mixin.matches("[0-9a-f]{32}"));

        // 同样的密钥应命中缓存，不被重复拉取
        check("mixin_key 缓存命中", mixin.equals(WbiSigner.get().mixinKey(cookie)));

        // ---------- 2. 取一个真实存在的 BV ----------
        String bvid = args.length > 0 ? args[0] : null;
        if (bvid == null) {
            System.out.println("\n=== 2/5 从热门接口取测试用 BV ===");
            String pop = Http.get(
                    "https://api.bilibili.com/x/web-interface/popular?ps=1&pn=1", "");
            org.json.JSONObject root = new org.json.JSONObject(pop);
            bvid = root.getJSONObject("data").getJSONArray("list")
                    .getJSONObject(0).getString("bvid");
            System.out.println("取得 BV = " + bvid);
        } else {
            System.out.println("\n=== 2/5 使用命令行指定的 BV ===");
            System.out.println("BV = " + bvid);
        }

        // ---------- 3. view 接口 ----------
        System.out.println("\n=== 3/5 解析稿件信息 ===");
        Model.Video v = BiliApi.view(bvid, cookie);
        System.out.println("BV    : " + v.bvid);
        System.out.println("标题  : " + v.title);
        System.out.println("UP主  : " + v.owner);
        System.out.println("时长  : " + v.duration + " 秒");
        System.out.println("封面  : " + v.cover);
        System.out.println("分P   : " + v.pages.size());
        for (int i = 0; i < Math.min(3, v.pages.size()); i++) {
            Model.Part p = v.pages.get(i);
            System.out.println("        P" + p.index + "  cid=" + p.cid + "  " + p.title);
        }
        check("标题非空", !v.title.isEmpty());
        check("cid 有效", v.pages.get(0).cid > 0);

        // ---------- 4. playurl（WBI 签名） ----------
        System.out.println("\n=== 4/5 获取 DASH 播放地址（核心：WBI 签名）===");
        Model.PlayInfo info = BiliApi.playurl(v.bvid, v.pages.get(0).cid, 127, cookie);
        System.out.println("可选画质: " + info.qualities);
        System.out.println("\n视频流 " + info.videos.size() + " 条：");
        for (Model.Stream s : info.videos) {
            System.out.println(String.format("   qn=%-5d %4dx%-5d %-10s bw=%-9d %s",
                    s.quality, s.width, s.height, Model.codecName(s.codecId), s.bandwidth,
                    host(s.url)));
        }
        System.out.println("\n音频流 " + info.audios.size() + " 条：");
        for (Model.Stream s : info.audios) {
            System.out.println(String.format("   %-14s bw=%-9d %s",
                    Model.audioName(s.quality), s.bandwidth, host(s.url)));
        }
        check("拿到视频流", !info.videos.isEmpty());
        check("拿到音频流", !info.audios.isEmpty());
        check("解析出画质档位", !info.qualities.isEmpty());

        // ---------- 5. 真实拉取 CDN 数据 ----------
        System.out.println("\n=== 5/5 实拉 CDN 数据（验证 Referer 与直链可用）===");
        Model.Stream vs = info.videoByQuality(80, true);
        if (vs == null) {
            vs = info.videos.get(0);
        }
        int vBytes = probe("视频", vs, cookie);
        check("视频流可下载", vBytes > 0);

        Model.Stream as = info.bestAudio();
        int aBytes = probe("音频", as, cookie);
        check("音频流可下载", aBytes > 0);

        // ---------- 备用地址 ----------
        if (!vs.backups.isEmpty()) {
            System.out.println("\n备用地址 " + vs.backups.size() + " 条:");
            for (String b : vs.backups) {
                System.out.println("   " + host(b));
            }
            check("备用地址非空", true);
        }
    }

    /** 只拉前 64 KB，验证直链确实可用。 */
    private static int probe(String label, Model.Stream s, String cookie) {
        if (s == null) {
            System.out.println(label + "流不存在，跳过");
            return 0;
        }
        HttpURLConnection c = null;
        try {
            c = Http.open(s.candidates().get(0), cookie, true);
            c.setRequestProperty("Range", "bytes=0-65535");
            int code = c.getResponseCode();
            System.out.println(label + " CDN: HTTP " + code
                    + "  content-length=" + c.getContentLengthLong());
            if (code >= 400) {
                return 0;
            }
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while (total < 65536 && (n = in.read(buf)) > 0) {
                total += n;
            }
            in.close();
            System.out.println(label + " 实际读取 " + total + " 字节");
            return total;
        } catch (Exception e) {
            System.out.println(label + " 拉取失败: " + e);
            return 0;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String host(String url) {
        int a = url.indexOf("://");
        int b = url.indexOf('/', a + 3);
        return b > 0 ? url.substring(a + 3, b) : url;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
