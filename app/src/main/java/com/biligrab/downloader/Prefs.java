package com.biligrab.downloader;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地偏好：登录 Cookie、清晰度偏好、是否优先 H.264。 */
public final class Prefs {

    private static final String FILE = "biligrab";
    private static final String KEY_SESSDATA = "sessdata";
    private static final String KEY_BUVID3 = "buvid3";
    private static final String KEY_QN = "prefer_qn";
    private static final String KEY_PREFER_AVC = "prefer_avc";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_PROXY = "youtube_proxy";

    public static final int DEFAULT_QN = 80;

    /** 外观：跟随系统 / 强制浅色 / 强制深色。 */
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

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
     * 供 {@code onCreate} 在 {@code super.onCreate} 之前读取。
     *
     * <p>那时实例字段还没初始化，所以走静态读取；外观覆盖必须早于任何资源访问，
     * 否则 {@code applyOverrideConfiguration} 会抛异常。</p>
     */
    public static int themeModeStatic(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, THEME_SYSTEM);
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
