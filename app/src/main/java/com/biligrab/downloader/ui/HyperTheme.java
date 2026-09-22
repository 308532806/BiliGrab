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
     * 在一个背景色上取可读的前景色。
     *
     * <p>用感知亮度而不是简单的 RGB 均值：人眼对绿最敏感、对蓝最不敏感，
     * 等权平均会把中蓝判成"亮色"从而配上黑字，实际几乎看不清。
     * 系数取自 sRGB 的相对亮度加权。</p>
     */
    public static int contrastOn(Context c, int bg) {
        double l = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg);
        // 阈值 160 而不是 128：这几套主题色的亮度分布在 150 附近聚得最密，
        // 把分界推高一点，浅色主题色才不会被判成深色。
        return l > 160 ? 0xFF111111 : 0xFFFFFFFF;
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
