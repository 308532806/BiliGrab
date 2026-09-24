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
                // 用 accent() 而不是 colors()：玻璃态下 colors() 设的底色
                // 不参与绘制（玻璃面画的是半透明白雾），xml 里写了 neuFill
                // 的控件会静默变成没上色。accent() 会按引擎分派。
                d.accent(fill, HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));
            }

            if (a.getBoolean(I_PRESS, false)) {
                // 只做视觉反馈：真正的点击行为由调用方另行绑定
                HyperosClick.bindVisualOnly(v);
            }
        } finally {
            a.recycle();
        }
    }

    /**
     * 布局里标 {@code android:tag="neu:accent"} 表示"这个控件吃主题色"。
     *
     * <p>为什么用 tag 而不是自定义属性：{@code ProgressBar} 这类系统控件
     * 由框架自己构造，走不到 {@link #apply} 里，读不到 app: 命名空间的属性。
     * tag 是系统属性，任何 View 都能带，因此一套机制能覆盖自定义控件和系统控件。</p>
     */
    public static final String TAG_ACCENT = "neu:accent";

    /**
     * 遍历视图树，把主题色刷到所有标了 {@link #TAG_ACCENT} 的控件上。
     *
     * <h3>为什么必须遍历整棵树</h3>
     *
     * <p>主色是运行期设置，而 XML 只能写编译期的静态值 —— 想在布局里用主色，
     * 只能写 {@code @color/scheme_0_primary}，那是<b>樱花粉，第一套主题色的
     * 编译期快照</b>。所以每个用主色的控件都得在 Java 里补一行覆盖。</p>
     *
     * <p>"XML 兜底 + Java 逐个覆盖"这种做法必然漏，而且漏了不会报错 ——
     * 只是那个控件安静地停在樱花粉上，只有换到别的主题色才看得出来。
     * 实测漏了六个：{@code previewLoading} 转圈、{@code tvPreviewError}、
     * {@code btnPlayPause}，以及搜索/信息/下载三个空状态图标。</p>
     *
     * <p>改成"标记写在布局里 + 这里一次遍历刷掉"之后，判断依据和控件本身
     * 放在一起，不会再因为忘记加一行 Java 而漏。新加控件只要在布局里
     * 标一下 tag 就自动跟着主题走。</p>
     *
     * <p>必须在 {@code setContentView} 之后调用，此时整棵树才建好。</p>
     */
    public static void applyAccentTags(View root) {
        if (root == null) return;
        if (TAG_ACCENT.equals(root.getTag())) {
            applyAccent(root, root.getContext());
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyAccentTags(g.getChildAt(i));
            }
        }
    }

    /**
     * 把主题色刷到一个控件上。
     *
     * <p>文字走 {@link HyperTheme#primaryText}（推进到正文 4.5:1），
     * 图标走 {@link HyperTheme#primaryIcon}（推进到 3:1）——
     * 图标是图形，不适用正文的阈值。五套主题色里只有深海蓝天生够深，
     * 其余四套直接当文字用都不合格（樱花粉在浅色页面上只有 2.39:1）。</p>
     */
    public static void applyAccent(View v, Context ctx) {
        if (v instanceof android.widget.TextView) {
            ((android.widget.TextView) v).setTextColor(HyperTheme.primaryText(ctx));
        }
        if (v instanceof android.widget.ImageView) {
            ((android.widget.ImageView) v).setImageTintList(
                    android.content.res.ColorStateList.valueOf(HyperTheme.primaryIcon(ctx)));
        }
        // 进度圈/进度条用 indeterminateTint，对应的运行时 API 是
        // setIndeterminateTintList —— 只在 ProgressBar 上存在，
        // 所以单独判一次而不是塞进上面的分支。
        if (v instanceof android.widget.ProgressBar) {
            ((android.widget.ProgressBar) v).setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(HyperTheme.primaryIcon(ctx)));
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
