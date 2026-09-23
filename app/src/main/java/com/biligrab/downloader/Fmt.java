package com.biligrab.downloader;

import java.util.Locale;

/**
 * 数字的显示格式。
 *
 * <p>下载管理页要同时显示「已下载多少 / 总共多少」和「每秒多少」，这两处都
 * 必须用同一种口径，否则 1.5 GiB 的文件配一个按 10^6 算的速度，
 * 用户心算出来的剩余时间会明显不对。</p>
 *
 * <p>这里统一用 **1024 进制**（KiB/MiB/GiB），这是文件大小的事实标准；
 * 但网络速度业界习惯用 1000 进制（Mbps）说，所以速度额外给出 Mb/s
 * 换算 —— 那是用户去对比宽带套餐时唯一有意义的数字。</p>
 */
public final class Fmt {

    private Fmt() {
    }

    /** 字节数 → “1.5 GB”。 */
    public static String bytes(long b) {
        if (b < 0) {
            b = 0;
        }
        if (b < 1024L) {
            return b + " B";
        }
        double kb = b / 1024.0;
        if (kb < 1024.0) {
            return trim(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return trim(mb) + " MB";
        }
        return trim(mb / 1024.0) + " GB";
    }

    /** 速度 → “3.2 MB/s”。 */
    public static String speed(long bps) {
        if (bps <= 0) {
            return "0 B/s";
        }
        return bytes(bps) + "/s";
    }

    /**
     * 速度的比特口径 → “26 Mbps”。
     *
     * <p>用户拿它和宽带套餐对得上；纯字节口径的 MB/s 对不上任何运营商页面。</p>
     */
    public static String bitrate(long bps) {
        if (bps <= 0) {
            return "0 Mbps";
        }
        double kbps = bps * 8.0 / 1000.0;
        if (kbps < 1000.0) {
            return trim(kbps) + " Kbps";
        }
        return trim(kbps / 1000.0) + " Mbps";
    }

    /**
     * 剩余时间 → “还剩 1 分 20 秒”。
     *
     * <p>总量或速度未知时返回空串 —— 界面应当直接不显示这一项，
     * 而不是显示「—」或「00:00」那种让人以为卡住的占位。</p>
     */
    public static String eta(long done, long total, long bps) {
        if (bps <= 0 || total <= 0 || done <= 0 || done >= total) {
            return "";
        }
        long sec = (total - done) / bps;
        if (sec < 0) {
            return "";
        }
        if (sec < 60) {
            return sec + " 秒";
        }
        long min = sec / 60;
        if (min < 60) {
            long s = sec % 60;
            return s == 0 ? min + " 分钟" : (min + " 分 " + s + " 秒");
        }
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? (h + " 小时") : (h + " 小时 " + m + " 分");
    }

    /** 时间戳 → “今天 14:05” / “3 天前” 这类相对描述。 */
    public static String when(long ts) {
        long now = System.currentTimeMillis();
        long diff = now - ts;
        if (diff < 0) {
            return "刚刚";
        }
        long min = diff / 60000L;
        if (min < 1) {
            return "刚刚";
        }
        if (min < 60) {
            return min + " 分钟前";
        }
        long h = min / 60;
        if (h < 24) {
            return h + " 小时前";
        }
        long d = h / 24;
        if (d < 30) {
            return d + " 天前";
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.US, "%d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** 一位小数；整数时不带小数点（3.0 → “3”，避免“3.0 MB”这种噪声）。 */
    private static String trim(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }
}
