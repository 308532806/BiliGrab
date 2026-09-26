package com.biligrab.downloader;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地偏好：登录 Cookie、清晰度偏好、是否优先 H.264。 */
public final class Prefs {

    private static final String FILE = "biligrab";
    private static final String KEY_SESSDATA = "sessdata";
    /**
     * 登录后的用户名，只用来显示。
     *
     * <p>存它是为了让设置页能明确写出「已登录：某某」—— 只说「已登录」的话，
     * 用户没法判断登进去的是不是自己想用的那个号（多账号很常见）。</p>
     */
    private static final String KEY_LOGIN_UNAME = "login_uname";
    private static final String KEY_BUVID3 = "buvid3";
    private static final String KEY_QN = "prefer_qn";
    private static final String KEY_PREFER_AVC = "prefer_avc";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_PROXY = "youtube_proxy";
    private static final String KEY_SKIN = "ui_skin";
    private static final String KEY_PRIMARY = "ui_primary";

    /**
     * 用户自选的下载目录（SAF 的 tree URI）与它的显示名。
     *
     * <p>空串表示用默认目录。存两份不是冗余：URI 是给系统用的句柄，
     * 拿它拼不出「下载/BiliGrab」这种给人看的路径，而设置页需要显示后者。</p>
     */
    private static final String KEY_TREE_URI = "download_tree_uri";
    private static final String KEY_TREE_LABEL = "download_tree_label";

    /**
     * 并发连接数（1..{@link #MAX_CONNECTIONS}）。
     *
     * <p>存在的原因是 googlevideo 按**每条连接**限速：实测单连接 8.1 Mbps、
     * 4 连接 35.5 Mbps，换多少个 VPN 都没用，因为瓶颈不在出口带宽上。
     * 所以「快」和「稳」之间的取舍交给用户，默认取 4。</p>
     */
    private static final String KEY_CONNECTIONS = "download_connections";

    /** 并发上限。再多收益就没了（实测 8 连接反而比 4 连接慢）。 */
    public static final int MAX_CONNECTIONS = 8;
    public static final int DEFAULT_CONNECTIONS = 4;

    public static final int DEFAULT_QN = 80;

    /** 外观：跟随系统 / 强制浅色 / 强制深色。 */
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    /**
     * 视觉引擎。1.9.8 起恒玻璃态：常量与 setSkin 保留仅兼容旧偏好，
     * 不再有界面入口。
     */
    public static final int SKIN_NEUMORPHISM = 0;
    public static final int SKIN_GLASS = 1;

    /** 主题色索引，对应 res/values/arrays.xml 里 hyper_primary 的下标。 */
    public static final int PRIMARY_SAKURA = 0;
    public static final int PRIMARY_BLUE = 1;
    public static final int PRIMARY_DEEP_BLUE = 2;
    public static final int PRIMARY_MINT = 3;
    public static final int PRIMARY_LAVENDER = 4;
    public static final int PRIMARY_COUNT = 5;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String sessdata() {
        return sp.getString(KEY_SESSDATA, "");
    }

    public void setSessdata(String v) {
        sp.edit().putString(KEY_SESSDATA, v == null ? "" : v.trim()).apply();
    }

    public String loginUname() {
        return sp.getString(KEY_LOGIN_UNAME, "");
    }

    public void setLoginUname(String v) {
        sp.edit().putString(KEY_LOGIN_UNAME, v == null ? "" : v.trim()).apply();
    }

    /** 退出登录：SESSDATA 和用户名一起清，不能只清一个。 */
    public void clearLogin() {
        sp.edit().remove(KEY_SESSDATA).remove(KEY_LOGIN_UNAME).apply();
    }

    public String buvid3() {
        return sp.getString(KEY_BUVID3, "");
    }

    public void setBuvid3(String v) {
        sp.edit().putString(KEY_BUVID3, v == null ? "" : v.trim()).apply();
    }

    public boolean preferAvc() {
        return sp.getBoolean(KEY_PREFER_AVC, true);
    }

    public void setPreferAvc(boolean v) {
        sp.edit().putBoolean(KEY_PREFER_AVC, v).apply();
    }

    public int preferQn() {
        return sp.getInt(KEY_QN, DEFAULT_QN);
    }

    public void setPreferQn(int qn) {
        sp.edit().putInt(KEY_QN, qn).apply();
    }

    /**
     * 并发连接数，钳在 1..{@link #MAX_CONNECTIONS}。
     *
     * <p>钳位放在这里而不是设置界面：旧版本存下来过什么值、或者
     * 手改过 prefs 文件，都不该让下载线程池按一个荒唐的数字去开连接。</p>
     */
    public int connections() {
        int n = sp.getInt(KEY_CONNECTIONS, DEFAULT_CONNECTIONS);
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_CONNECTIONS);
    }

    public void setConnections(int n) {
        sp.edit().putInt(KEY_CONNECTIONS, Math.max(1, Math.min(n, MAX_CONNECTIONS))).apply();
    }

    public boolean hasLogin() {
        return !sessdata().isEmpty();
    }

    public int themeMode() {
        return sp.getInt(KEY_THEME, THEME_SYSTEM);
    }

    public void setThemeMode(int mode) {
        sp.edit().putInt(KEY_THEME, mode).apply();
    }

    /**
     * YouTube 走的 HTTP 代理，形如 {@code 192.168.8.2:7890}。空串表示直连。
     *
     * <p>这不是可有可无的选项：YouTube 在中国大陆无法直连，没有代理时
     * yt-dlp 会以 {@code [Errno 110] Connection timed out} 结束。
     * 所以它和 SESSDATA 一样属于「不填就有一整块功能不能用」的配置。</p>
     *
     * <p>刻意只作用于 YouTube 一路：B 站走代理反而会变慢甚至被风控，
     * 两边的网络需求完全不同。</p>
     */
    public String youtubeProxy() {
        return sp.getString(KEY_PROXY, "").trim();
    }

    public void setYoutubeProxy(String v) {
        sp.edit().putString(KEY_PROXY, v == null ? "" : v.trim()).apply();
    }

    /**
     * 自选的下载目录。空串表示用默认目录（视频进 Movies/BiliGrab，
     * 音频进 Music/BiliGrab）。
     */
    public String downloadTreeUri() {
        return sp.getString(KEY_TREE_URI, "");
    }

    public String downloadTreeLabel() {
        return sp.getString(KEY_TREE_LABEL, "");
    }

    public void setDownloadTree(String uri, String label) {
        sp.edit()
                .putString(KEY_TREE_URI, uri == null ? "" : uri)
                .putString(KEY_TREE_LABEL, label == null ? "" : label)
                .apply();
    }

    /**
     * 供 {@code onCreate} 在 {@code super.onCreate} 之前读取。
     *
     * <p>那时实例字段还没初始化，所以走静态读取；外观覆盖必须早于任何资源访问，
     * 否则 {@code applyOverrideConfiguration} 会抛异常。</p>
     */
    public static int themeModeStatic(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, THEME_SYSTEM);
    }

    /** 视觉引擎：1.9.8 起恒玻璃态（常量与 setSkin 保留仅兼容旧偏好）。 */
    public int skin() {
        return SKIN_GLASS;
    }

    public void setSkin(int v) {
        sp.edit().putInt(KEY_SKIN, v).apply();
    }

    /** 主题色索引，0..PRIMARY_COUNT-1。默认樱花粉，保留原品牌色。 */
    public int primary() {
        int i = sp.getInt(KEY_PRIMARY, PRIMARY_SAKURA);
        return (i < 0 || i >= PRIMARY_COUNT) ? PRIMARY_SAKURA : i;
    }

    public void setPrimary(int v) {
        sp.edit().putInt(KEY_PRIMARY, v).apply();
    }

    /**
     * 组装请求头里的 Cookie。
     *
     * <p>SESSDATA 决定能拿到多高的清晰度（未登录最高 480P）。
     * buvid3 是设备指纹，缺失时部分接口会返回 -352 风控错误。</p>
     */
    public String cookie() {
        StringBuilder sb = new StringBuilder();
        String sess = sessdata();
        if (!sess.isEmpty()) {
            sb.append("SESSDATA=").append(sess);
        }
        String buvid = buvid3();
        if (!buvid.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("buvid3=").append(buvid);
        }
        return sb.toString();
    }
}
