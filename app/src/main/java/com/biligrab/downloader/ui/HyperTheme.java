package com.biligrab.downloader.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;

import com.biligrab.downloader.Prefs;
import com.biligrab.downloader.R;

/**
 * Hyper-Neumorphic 主题解析。
 *
 * <p>整套设计系统里所有颜色与皮肤的判断都收在这里，其它地方不直接读 R.color，
 * 这样「换皮肤」「换主题色」只需要清一次缓存 + 重建界面，不必到处改。</p>
 *
 * <h3>为什么不做成静态常量</h3>
 *
 * <p>颜色是资源，会随深色模式变化；主题色是用户设置，会随时变。
 * 两者都不是编译期常量，所以每次访问都重新解析。
 * 这些调用都在 onDraw 之外（配置 View 时读一次），开销可以忽略。</p>
 */
public final class HyperTheme {

    private HyperTheme() {}

    /** 深色模式由系统配置决定，与「外观」设置经 applyOverrideConfiguration 后的结果一致。 */
    public static boolean isDark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 当前视觉引擎。读设置，默认新拟态。 */
    public static boolean isGlass(Context ctx) {
        return new Prefs(ctx).skin() == Prefs.SKIN_GLASS;
    }

    // ------------------------------------------------------------------
    // 语义色
    // ------------------------------------------------------------------

    public static int background(Context c)   { return col(c, R.color.hyper_background); }
    public static int card(Context c)         { return col(c, R.color.hyper_card); }
    public static int textPrimary(Context c)  { return col(c, R.color.hyper_text_primary); }
    public static int textSecondary(Context c){ return col(c, R.color.hyper_text_secondary); }
    public static int textTertiary(Context c) { return col(c, R.color.hyper_text_tertiary); }
    public static int success(Context c)      { return col(c, R.color.hyper_success); }
    public static int warning(Context c)      { return col(c, R.color.hyper_warning); }
    public static int error(Context c)        { return col(c, R.color.hyper_error); }
    public static int border(Context c)       { return col(c, R.color.hyper_border); }
    public static int divider(Context c)      { return col(c, R.color.hyper_divider); }
    public static int iconBg(Context c)       { return col(c, R.color.hyper_icon_bg); }
    public static int badgeBg(Context c)      { return col(c, R.color.hyper_badge_bg); }
    public static int badgeText(Context c)    { return primary(c); }
    public static int scrim(Context c)        { return col(c, R.color.hyper_scrim); }

    // ------------------------------------------------------------------
    // 浮雕
    // ------------------------------------------------------------------

    /** 左上高光色。深色模式下是比背景略亮的一档，不是白色。 */
    public static int neumLight(Context c) { return col(c, R.color.neum_light); }

    /** 右下投影色。深色模式下是比背景略暗的一档。 */
    public static int neumDark(Context c) { return col(c, R.color.neum_dark); }

    /**
     * 浮雕面的底色。
     *
     * <p>新拟态的核心约定是<b>卡片与页面同色</b>，层级完全靠阴影表达。
     * 所以这里返回的是页面背景色而不是卡片色 —— 如果给卡片一个不同的底色，
     * 凸起感会立刻消失，变成「一张有边框的纸」。</p>
     */
    public static int neumBase(Context c) {
        return isGlass(c) ? col(c, R.color.glass_mesh_base) : background(c);
    }

    public static int glassTintConvex(Context c) { return col(c, R.color.glass_tint_convex); }
    public static int glassTintConcave(Context c){ return col(c, R.color.glass_tint_concave); }
    public static int glassBorderHi(Context c)   { return col(c, R.color.glass_border_hi); }
    public static int glassBorderLo(Context c)   { return col(c, R.color.glass_border_lo); }

    // ------------------------------------------------------------------
    // 主题色
    // ------------------------------------------------------------------

    /** 当前主色。随用户设置变化，深浅模式各有一套值。 */
    public static int primary(Context c) {
        return schemeColor(c, R.array.hyper_primary, new Prefs(c).primary());
    }

    public static int primaryVariant(Context c) {
        return schemeColor(c, R.array.hyper_primary_variant, new Prefs(c).primary());
    }

    /** 带透明度的主色，用于「激活段」这类半透明填充。 */
    public static int primaryAlpha(Context c, float alpha) {
        int p = primary(c);
        return Color.argb(Math.round(255 * alpha), Color.red(p), Color.green(p), Color.blue(p));
    }

    private static int schemeColor(Context c, int arrayRes, int index) {
        TypedArray ta = null;
        try {
            ta = c.getResources().obtainTypedArray(arrayRes);
            if (index < 0 || index >= ta.length()) index = 0;
            return ta.getColor(index, Color.GRAY);
        } catch (Exception e) {
            return Color.GRAY;
        } finally {
            if (ta != null) ta.recycle();
        }
    }

    /** 主题色的可选数量，用于设置面板生成色块。 */
    public static int schemeCount(Context c) {
        TypedArray ta = null;
        try {
            ta = c.getResources().obtainTypedArray(R.array.hyper_primary);
            return ta.length();
        } catch (Exception e) {
            return Prefs.PRIMARY_COUNT;
        } finally {
            if (ta != null) ta.recycle();
        }
    }

    /** 按索引取主色，供设置面板预览用（不受当前选择影响）。 */
    public static int schemePrimary(Context c, int index) {
        return schemeColor(c, R.array.hyper_primary, index);
    }

    /**
     * 在一个背景色上取可读的前景色（近黑或白）。
     *
     * <p>做法是<b>把两个候选都算一遍，取对比度高的那个</b>，而不是拿感知亮度
     * 去过一个阈值。理由：候选色只有两个而且都已知，估算纯属自找麻烦，
     * 而估算和真实的 WCAG 对比度并不是一回事。</p>
     *
     * <p>这不是理论问题 —— 旧实现用感知亮度
     * {@code 0.299R + 0.587G + 0.114B > 160} 判方向，品牌色樱花粉
     * {@code #FB7299} 算出来是 159.4，差 0.6 就落到了白字一侧：
     * 白字只有 2.64:1（远低于正文 4.5:1），而同色黑字有 7.17:1，
     * 差了 2.7 倍。浅色的薄荷绿、薰衣草紫和深色的两个蓝是同一个模式，
     * 十套主题色里有五套配错了字色。</p>
     *
     * <p>WCAG 相对亮度必须做 sRGB 反伽马（分段函数），与上面那个感知亮度
     * 加权完全是两套公式，不要互相替代。</p>
     */
    public static int contrastOn(Context c, int bg) {
        final int INK = 0xFF111111;
        final int PAPER = 0xFFFFFFFF;
        return contrastRatio(bg, INK) >= contrastRatio(bg, PAPER) ? INK : PAPER;
    }

    /**
     * WCAG 2.x 对比度，范围 1..21。
     *
     * <p>注意这里的亮度是<b>相对亮度</b>：先对每个通道做 sRGB 反伽马
     * （≤0.03928 时线性化，否则 {@code ((v+0.055)/1.055)^2.4}），
     * 再按 0.2126/0.7152/0.0722 加权。</p>
     */
    public static double contrastRatio(int a, int b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relativeLuminance(int color) {
        return 0.2126 * channel(Color.red(color))
                + 0.7152 * channel(Color.green(color))
                + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    // ------------------------------------------------------------------

    private static int col(Context c, int res) {
        try {
            return c.getResources().getColor(res, c.getTheme());
        } catch (Resources.NotFoundException e) {
            return Color.GRAY;
        }
    }
}
