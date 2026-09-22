package com.biligrab.downloader.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.biligrab.downloader.R;

/**
 * 读取 {@code app:neu*} 属性并把新拟态应用到 View。
 *
 * <p>被 com.biligrab.downloader.ui 下的 Neu* 系列 View 在构造时调用。
 * 单独抽出来是为了让那批 View 类各自只剩几行 —— 它们的差异只在父类，
 * 新拟态的处理完全一致。</p>
 */
public final class NeumAttr {

    private NeumAttr() {}

    /**
     * 名义上叫 app:，但为了不引入 res-auto 的额外声明，用项目自己的命名空间。
     *
     * <p>这张表和 {@code values/attrs.xml} 的 declare-styleable 必须严格对应，
     * 下面的下标也是照它写死的。曾经这里还挂着 neuStroke 与 neuGlassFlat 两项，
     * 但读取逻辑从未实现 —— 属性声明了却不读，写的人会以为生效了。
     * 已经两边一起删掉。加属性时记得两处同时改。</p>
     */
    private static final int[] ATTRS = {
            R.attr.neuStyle,
            R.attr.neuRadius,
            R.attr.neuElevation,
            R.attr.neuFill,
            R.attr.neuPress,
    };

    private static final int I_STYLE = 0;
    private static final int I_RADIUS = 1;
    private static final int I_ELEV = 2;
    private static final int I_FILL = 3;
    private static final int I_PRESS = 4;

    /** 默认值：不指定 neuStyle 时不画浮雕，保持普通 View 行为。 */
    public static final int STYLE_NONE = 2;

    public static void apply(View v, Context ctx, AttributeSet attrs) {
        if (attrs == null) return;

        TypedArray a = null;
        try {
            a = ctx.obtainStyledAttributes(attrs, ATTRS);
        } catch (Exception e) {
            return;
        }
        if (a == null) return;

        try {
            int style = a.getInt(I_STYLE, STYLE_NONE);
            if (style == STYLE_NONE) return;

            float radiusDp = a.getDimension(I_RADIUS, dp(ctx, 24));
            float elevDp = a.getDimension(I_ELEV, dp(ctx, 4));
            // 属性里的 dimension 已经换算成 px 了，这里换回 dp 交给 drawable
            radiusDp = toDp(ctx, radiusDp);
            elevDp = toDp(ctx, elevDp);

            NeumorphicDrawable d = style == NeumorphicDrawable.CONCAVE
                    ? NeumorphicSurface.concave(v, radiusDp, elevDp)
                    : NeumorphicSurface.convex(v, radiusDp, elevDp);

            if (a.hasValue(I_FILL)) {
                int fill = a.getColor(I_FILL, 0);
                d.colors(fill, HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));
            }

            if (a.getBoolean(I_PRESS, false)) {
                // 只做视觉反馈：真正的点击行为由调用方另行绑定
                HyperosClick.bindVisualOnly(v);
            }
        } finally {
            a.recycle();
        }
    }

    private static float dp(Context ctx, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static float toDp(Context ctx, float px) {
        return px / ctx.getResources().getDisplayMetrics().density;
    }
}
