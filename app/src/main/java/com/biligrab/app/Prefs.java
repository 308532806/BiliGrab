package com.biligrab.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地偏好：登录 Cookie、清晰度偏好、是否优先 H.264。 */
public final class Prefs {

    private static final String FILE = "biligrab";
    private static final String KEY_SESSDATA = "sessdata";
    private static final String KEY_BUVID3 = "buvid3";
    private static final String KEY_QN = "prefer_qn";
    private static final String KEY_PREFER_AVC = "prefer_avc";

    public static final int DEFAULT_QN = 80;

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
