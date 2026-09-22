package com.biligrab.downloader.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

/**
 * 把新拟态浮雕应用到任意 View 的工具。
 *
 * <p>直接 new {@link NeumorphicDrawable} 也能用，但少了这里做的两件事，
 * 十有八九会踩坑：</p>
 *
 * <ol>
 *   <li><b>补 padding。</b>阴影画在形状之外，而 View 的 background 会被裁剪到
 *       View 边界。所以形状必须在四个方向内缩（bleed），而调用方要让出这段空间 ——
 *       否则内容会紧贴阴影，看起来像"字压在了立面上"。</li>
 *   <li><b>同步主题色。</b>浮雕的高光/投影色随深浅模式变化，
 *       深色模式下沿用白色高光会让浮雕整个消失。</li>
 * </ol>
 *
 * <p>重复调用是安全的：会先扣掉上一次补的 padding 再补新的，
 * 不会随调用次数累积。</p>
 */
public final class NeumorphicSurface {

    private NeumorphicSurface() {}

    /** 凸起：默认态。按钮、卡片、开关滑块。 */
    public static NeumorphicDrawable convex(View v, float radiusDp, float elevationDp) {
        return apply(v, NeumorphicDrawable.CONVEX, radiusDp, elevationDp);
    }

    /** 凹陷：输入框、开关轨道、滑块槽、按钮按下态。 */
    public static NeumorphicDrawable concave(View v, float radiusDp, float elevationDp) {
        return apply(v, NeumorphicDrawable.CONCAVE, radiusDp, elevationDp);
    }

    /**
     * 应用浮雕。返回 drawable 以便交互层驱动按下动画，
     * 或调用 {@link #refreshTheme} 在换肤后重新取色。
     */
    public static NeumorphicDrawable apply(View v, int style, float radiusDp, float elevationDp) {
        Context ctx = v.getContext();

        // 先把上一次补的 padding 扣回来，避免重复调用越补越大
        int oldBleed = 0;
        if (v.getBackground() instanceof NeumorphicDrawable) {
            oldBleed = Math.round(((NeumorphicDrawable) v.getBackground()).bleedPx());
        }

        NeumorphicDrawable d = new NeumorphicDrawable(ctx);
        d.style(style).radius(radiusDp).elevation(elevationDp).glass(HyperTheme.isGlass(ctx));
        d.colors(HyperTheme.neumBase(ctx), HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));
        // 凸面是白雾（玻璃托起来），凹面是压暗（玻璃陷下去）。
        // 两者用同一个叠加色的话，凹陷在玻璃态下会完全看不出来。
        d.glassColors(style == NeumorphicDrawable.CONCAVE
                        ? HyperTheme.glassTintConcave(ctx)
                        : HyperTheme.glassTintConvex(ctx),
                HyperTheme.glassBorderHi(ctx), HyperTheme.glassBorderLo(ctx));
        v.setBackground(d);

        int bleed = Math.round(d.bleedPx());
        v.setPadding(
                v.getPaddingLeft() - oldBleed + bleed,
                v.getPaddingTop() - oldBleed + bleed,
                v.getPaddingRight() - oldBleed + bleed,
                v.getPaddingBottom() - oldBleed + bleed);

        return d;
    }

    /**
     * 换肤/换主题色之后重新取色。界面不再重建时用这个，
     * 比重建整棵视图树便宜得多。
     */
    public static void refreshTheme(View v) {
        if (!(v.getBackground() instanceof NeumorphicDrawable)) return;
        NeumorphicDrawable d = (NeumorphicDrawable) v.getBackground();
        Context ctx = v.getContext();
        d.glass(HyperTheme.isGlass(ctx));
        d.colors(HyperTheme.neumBase(ctx), HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));
        d.glassColors(HyperTheme.glassTintConvex(ctx), HyperTheme.glassBorderHi(ctx),
                HyperTheme.glassBorderLo(ctx));
        d.invalidateSelf();
    }

    /**
     * 在父容器里遍历并刷新所有浮雕面。
     * 用于换肤后一次性更新，不需要重建 Activity。
     */
    public static void refreshTree(View root) {
        refreshTheme(root);
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            refreshTree(g.getChildAt(i));
        }
    }

    /** 主色描边，用于输入框聚焦态。 */
    public static android.graphics.drawable.GradientDrawable focusRing(Context ctx, float radiusDp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(radiusDp * density);
        g.setStroke(Math.max(1, Math.round(density)), HyperTheme.primary(ctx));
        g.setColor(Color.TRANSPARENT);
        return g;
    }
}
