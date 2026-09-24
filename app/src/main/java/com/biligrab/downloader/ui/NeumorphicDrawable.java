package com.biligrab.downloader.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;

/**
 * 新拟态浮雕引擎 —— 整套设计系统里唯一负责「厚度」的地方。
 *
 * <p>来源：docs/design-refs/hyper-neumorphic.md 的 Step 5（NeumorphicModifiers）。规范是
 * Compose 写的，但底下用的是 {@code android.graphics.BlurMaskFilter} +
 * {@code Canvas.drawRoundRect}，也就是原生图形 API，所以这里可以在纯 Java 里 1:1 复刻，
 * 不需要引入 Compose 或任何第三方库。</p>
 *
 * <h3>凸起与凹陷是两套完全不同的画法</h3>
 *
 * <p><b>凸起（{@link #CONVEX}）</b>：在形状轮廓上画两个偏移的实心圆角矩形，
 * 各自模糊。右下偏移 +e/2 画暗色（投影），左上偏移 -e/2 画亮色（高光）。
 * 最后把底色填在最上面，只让边缘那一圈阴影露出来。光源在左上，全程固定。</p>
 *
 * <p><b>凹陷（{@link #CONCAVE}）</b>：不是把凸起反过来。凹陷用的是 <b>STROKE + 模糊</b>，
 * 画在**内容之上**，形成内阴影。暗色描边往左上偏（模拟被挖掉的边缘挡光），
 * 亮色描边往右下偏（模拟坑底受光）。这个方向与凸起相反，是凹陷感成立的关键 ——
 * 只是把凸起的颜色对调的话，看起来会像一个贴上去的亮斑。</p>
 *
 * <h3>为什么用位图缓存</h3>
 *
 * <p>模糊是整条链路上最贵的操作。如果每次 {@code onDraw} 都重画，
 * 一个列表滚起来会掉帧。</p>
 *
 * <p>本来也可以走 {@code View.setLayerType(LAYER_TYPE_SOFTWARE)} 让系统保证支持
 * BlurMaskFilter —— 那是最省事的做法，但会让整棵子树退回软件渲染，滚动直接遭殃。
 * 这里选择把阴影**预渲染进一张位图**：尺寸或状态没变就直接 blit，
 * 变了才重画。代价是每个实例一张位图，换来的是硬件渲染路径不受影响。</p>
 *
 * <h3>外扩（bleed）</h3>
 *
 * <p>阴影必然画到形状边界之外，而 View 的 background 会被裁剪到 View 边界上。
 * 所以形状要在四个方向内缩 {@code bleed}，把空间让给阴影；
 * 调用方（{@link NeumorphicSurface}）负责把这段内缩补进 padding，
 * 免得内容贴着阴影。</p>
 *
 * @see NeumorphicSurface 推荐用它的静态方法应用到 View，而不是手工 new
 */
public class NeumorphicDrawable extends Drawable {

    /** 凸起：光源在左上，右下投影。默认态。 */
    public static final int CONVEX = 0;
    /** 凹陷：内阴影。用于按下态、开关轨道、输入框、滑块槽。 */
    public static final int CONCAVE = 1;

    /** 模糊半径相对 elevation 的倍数。规范里 BlurMaskFilter 的半径就是 elevation。 */
    private static final float BLUR_FACTOR = 1.0f;

    /**
     * 形状相对于 Drawable 边界的内缩倍数。
     * 偏移是 e/2，模糊半径是 e，模糊会向两侧各扩散约 e，
     * 所以 1.5e 是能容下「偏移 + 模糊」的最小安全值。
     */
    private static final float BLEED_FACTOR = 1.5f;

    private final float density;

    private int style = CONVEX;
    private float cornerRadiusPx;
    private float elevationPx;

    private int baseColor = Color.WHITE;
    private int lightColor = 0xFFFFFFFF;
    private int darkColor = 0xFFD1D9E6;

    /** 按下时的收缩比例，1 表示不收缩。用于光影联动。 */
    private float pressFactor = 1f;

    /**
     * 形状是否内缩以给阴影让位。默认 true。
     *
     * <p>用在 {@code Switch} 的轨道、{@code SeekBar} 的滑块这类场景时要关掉：
     * 那些控件本身就把 drawable 放在一个已经留好空间的位置上，
     * 再内缩一次会让形状比预期小一圈。</p>
     */
    private boolean insetEnabled = true;

    /** 内在尺寸（像素）。给 Switch/SeekBar 这类需要量的控件用。 */
    private int intrinsicW = -1;
    private int intrinsicH = -1;

    /** 玻璃态开关。开启后不画外投影，改为半透明叠加 + 高光描边。 */
    private boolean glass = false;
    private int glassTint = 0x2EFFFFFF;
    private int glassBorderHi = 0xCCFFFFFF;
    private int glassBorderLo = 0x1F000000;

    /**
     * 玻璃态下的主色填充。全透明表示"没设"。
     *
     * <p>这是修「玻璃态下主题色色卡全变白」那个 bug 时加的。原来的 {@link #drawGlass}
     * 只画 {@code glassTint} 这一层半透明白雾，<b>从头到尾没有用过 {@link #baseColor}</b>，
     * 于是 {@code colors()} 在玻璃态下是个静默空操作：设置面板把五张色卡分别刷成
     * 五套主题色，玻璃态下五张全是白的。</p>
     *
     * <p>为什么不直接让 {@code drawGlass} 用 {@code baseColor}：两者语义不同。
     * {@code baseColor} 在新拟态里是"与页面同色的实心面"，玻璃态的托底则是
     * 一层半透明的白雾 —— 直接把实心色填进玻璃面，凸起就没玻璃感了。
     * 所以主色走的是一条独立通路：<b>先铺主色，再把白雾盖上去</b>。
     * 顺序不能反 —— 反了主色会被压在底下，看起来像蒙了一层脏东西；
     * 盖上去才是"一块染了色的玻璃"，白雾仍然透得出光。</p>
     */
    private int glassAccent = 0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Bitmap cache;
    private int cacheW = -1;
    private int cacheH = -1;
    private float cacheScale = 1f;

    public NeumorphicDrawable(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
        paint.setDither(true);
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    public NeumorphicDrawable style(int s) { this.style = s; invalidateCache(); return this; }

    /** 圆角，单位 dp。 */
    public NeumorphicDrawable radius(float dp) {
        this.cornerRadiusPx = dp * density;
        invalidateCache();
        return this;
    }

    /** 浮雕高度，单位 dp。同时决定模糊半径与阴影偏移（偏移 = 0.5 × 该值）。 */
    public NeumorphicDrawable elevation(float dp) {
        this.elevationPx = dp * density;
        invalidateCache();
        return this;
    }

    public NeumorphicDrawable colors(int base, int light, int dark) {
        this.baseColor = base;
        this.lightColor = light;
        this.darkColor = dark;
        invalidateCache();
        return this;
    }

    /**
     * 按下时的视觉收缩。0.8 表示浮雕降到八成厚度。
     * 由 {@link HyperosClick} 在按下/松开时驱动，不在本类里做动画 ——
     * 动画属于交互层，这里只负责画。
     */
    public void setPressFactor(float f) {
        if (Math.abs(f - pressFactor) < 0.01f) return;
        pressFactor = f;
        invalidateCache();
    }

    public float getPressFactor() { return pressFactor; }

    public NeumorphicDrawable glass(boolean on) {
        this.glass = on;
        invalidateCache();
        return this;
    }

    public NeumorphicDrawable glassColors(int tint, int borderHi, int borderLo) {
        this.glassTint = tint;
        this.glassBorderHi = borderHi;
        this.glassBorderLo = borderLo;
        invalidateCache();
        return this;
    }

    /**
     * 玻璃态下先铺一层实心主色，再盖白雾。传 0（全透明）表示恢复成纯白雾。
     *
     * <p>用在新拟态里"这个面本身就是主色"的场景 —— 目前只有设置面板的
     * 主题色色卡。玻璃态下必须走这条路，否则 {@link #colors} 的底色会被
     * {@link #drawGlass} 无视，色卡全变成白的。</p>
     */
    public NeumorphicDrawable glassAccent(int color) {
        this.glassAccent = color;
        invalidateCache();
        return this;
    }

    /**
     * 按当前引擎把"这个面是主色"设上去。两个引擎的写法不同，调用方不该知道区别。
     *
     * <p>{@link #glass} 为真走 {@link #glassAccent}（主色打底 + 白雾覆盖），
     * 否则走 {@link #colors}（实心填主色）。</p>
     *
     * <p>这个方法是给"背景已经由别处设好、只是要补一层主色"的场景用的 ——
     * 例如主题色色卡：{@code applyChipStates} 先把选中的刷成凸、其余刷成凹，
     * 这里再补主色。<b>它不重置凹凸</b>，所以不会把选中态的形状冲掉。
     * 要连形状一起设，用 {@link NeumorphicSurface#accent}。</p>
     */
    public NeumorphicDrawable accent(int color, int light, int dark) {
        if (glass) {
            return glassAccent(color);
        }
        return colors(color, light, dark);
    }

    /**
     * 这个面<b>实际呈现的颜色</b>。要在面上摆文字、需要算对比度时用。
     *
     * <p>不能直接拿设进去的颜色算 —— 两个引擎、两种凹凸，一共四条渲染路径，
     * 每条呈现出来的颜色都不一样：</p>
     *
     * <ul>
     *   <li><b>凸</b>：底色平铺，只有边缘一圈高光/亮边，中间就是本色。</li>
     *   <li><b>玻璃态 · 凹</b>：顶部一道 {@code 0x42000000} 的渐变一直铺到
     *       {@code 0.78h}，<b>整个面都被压暗</b>，中间也不例外。</li>
     *   <li><b>新拟态 · 凹</b>：内阴影是一圈<b>描边</b>（宽 {@code 2e}，
     *       再按 {@code e} 模糊），只压暗边缘十几像素，中间是本色。</li>
     *   <li><b>玻璃态 · 普通面</b>：再叠一层半透明白雾，比本色亮。</li>
     *   <li><b>玻璃态 · 主色面</b>：{@link #drawGlass} 对主色面跳过白雾
     *       （色卡必须准确显示颜色），所以呈现的就是主色再按凹凸明暗。</li>
     * </ul>
     *
     * <p>两个引擎的凹陷必须分开处理，混为一谈会配错字色。
     * 这是实测踩出来的：色卡在玻璃态下是"选中凸起、未选中凹入"，
     * 按本色给四张未选中的卡选字色时，默认蓝选到黑字，而它真实的凹面
     * {@code #0068D9} 上黑字只有 3.58:1、白字有 5.88:1；薰衣草紫同理
     * （黑 3.81 / 白 5.19）。实机上那两张卡的标签确实是灰的。</p>
     */
    public int faceColor() {
        if (glass) {
            int face = glassAccent != 0
                    ? glassAccent
                    : withAlphaOver(baseColor, glassTint);
            // 主色面不叠白雾，但一样吃那道铺满全高的方向光；
            // 普通玻璃面的白雾是均匀的，只需再叠上凹陷的压暗
            return style == CONCAVE ? scale(face, GLASS_CONCAVE_SHADE) : face;
        }
        // 新拟态的凹陷是描边式的，中间不受影响 —— 文字就坐在中间，
        // 所以这里不加压暗。系数见 NEUM_CONCAVE_EDGE_ONLY 的说明。
        return baseColor;
    }

    /**
     * 玻璃态凹陷面的压暗系数，{@code 0.84}。
     *
     * <p>从实机截图反推，不是估的。设备处于玻璃态、四张未选中的色卡
     * （凹陷）本色与渲染值：</p>
     *
     * <pre>
     *   樱花粉  #FB7299 → #D86284   0.861 / 0.860 / 0.863
     *   默认蓝  #007AFF → #0068D9   0.852 / 0.851
     *   薄荷绿  #32BB78 → #2A9C64   0.840 / 0.834 / 0.833
     *   薰衣草紫 #8E7CF0 → #7163BF   0.796 / 0.798 / 0.796
     * </pre>
     *
     * <p>逐色在 0.80~0.86 之间浮动（方向光是沿高度渐变的，不同高度压暗
     * 程度不同）。取 0.84 是让<b>字色的选择</b>在五套主题色上全部落在实测
     * 的那一侧：樱花粉/薄荷绿选黑，默认蓝/深海蓝/薰衣草紫选白 ——
     * 与截图完全一致，最差一档 4.54:1。</p>
     */
    private static final float GLASS_CONCAVE_SHADE = 0.84f;

    /** 按比例整体压暗，保持色相。 */
    private static int scale(int color, float f) {
        return Color.rgb(
                Math.min(255, Math.max(0, Math.round(Color.red(color) * f))),
                Math.min(255, Math.max(0, Math.round(Color.green(color) * f))),
                Math.min(255, Math.max(0, Math.round(Color.blue(color) * f))));
    }

    /** 把 {@code over} 按自身 alpha 叠到 {@code base} 上，得到不透明的结果色。 */
    private static int withAlphaOver(int base, int over) {
        float a = Color.alpha(over) / 255f;
        if (a >= 1f) return over;
        int r = Math.round(Color.red(over) * a + Color.red(base) * (1 - a));
        int g = Math.round(Color.green(over) * a + Color.green(base) * (1 - a));
        int b = Math.round(Color.blue(over) * a + Color.blue(base) * (1 - a));
        return Color.rgb(
                Math.min(255, Math.max(0, r)),
                Math.min(255, Math.max(0, g)),
                Math.min(255, Math.max(0, b)));
    }

    public boolean isGlass() { return glass; }

    /** 形状相对于边界的实际内缩量（像素）。调用方据此补 padding。 */
    public float bleedPx() {
        return (glass || !insetEnabled) ? 0f : elevationPx * BLEED_FACTOR;
    }

    /**
     * 关掉内缩，让形状铺满整个边界。
     * 用于 Switch 轨道、SeekBar 滑块这类由控件自己定位的场景。
     */
    public NeumorphicDrawable noInset() {
        this.insetEnabled = false;
        invalidateCache();
        return this;
    }

    /** 设定内在尺寸（dp）。控件据此决定给这个 drawable 多大空间。 */
    public NeumorphicDrawable intrinsic(float wDp, float hDp) {
        this.intrinsicW = Math.round(wDp * density);
        this.intrinsicH = Math.round(hDp * density);
        return this;
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicW > 0 ? intrinsicW : (int) (21 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicH > 0 ? intrinsicH : (int) (21 * density);
    }

    private void invalidateCache() {
        cacheW = -1;
        cacheH = -1;
        if (cache != null) {
            cache.recycle();
            cache = null;
        }
    }

    // ------------------------------------------------------------------
    // Drawable
    // ------------------------------------------------------------------

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        float scale = 1f;
        // 大尺寸时降采样渲染再放大：模糊本身就看不出细节，
        // 半分辨率能把位图内存和渲染时间都降到四分之一。
        if (b.width() > 720) scale = 0.5f;

        if (cache == null || cacheW != b.width() || cacheH != b.height() || cacheScale != scale) {
            renderCache(b.width(), b.height(), scale);
        }
        if (cache == null) return;

        Rect dst = new Rect(b);
        canvas.drawBitmap(cache, null, dst, null);
    }

    private void renderCache(int w, int h, float scale) {
        if (cache != null) {
            cache.recycle();
            cache = null;
        }
        int bw = Math.max(1, Math.round(w * scale));
        int bh = Math.max(1, Math.round(h * scale));

        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            // 宁可没有阴影，也不能因为阴影把应用拖垮
            return;
        }
        Canvas c = new Canvas(bmp);
        c.scale(scale, scale);
        drawInto(c, w, h);

        cache = bmp;
        cacheW = w;
        cacheH = h;
        cacheScale = scale;
    }

    /** 在指定尺寸的画布上真正作画。所有尺寸参数都是未缩放的逻辑像素。 */
    private void drawInto(Canvas c, int w, int h) {
        float e = elevationPx * (glass ? 1f : pressFactor) * BLUR_FACTOR;
        float r = Math.min(cornerRadiusPx, Math.min(w, h) / 2f);

        if (glass) {
            drawGlass(c, w, h, r);
            return;
        }

        float inset = elevationPx * BLEED_FACTOR;
        float off = e * 0.5f;

        if (style == CONVEX) {
            if (!insetEnabled) inset = 0f;
            // 形状本体占据的矩形（已经内缩掉阴影需要的空间）
            rect.set(inset, inset, w - inset, h - inset);

            paint.setStyle(Paint.Style.FILL);
            paint.setMaskFilter(new BlurMaskFilter(e, BlurMaskFilter.Blur.NORMAL));

            // 右下投影
            paint.setColor(darkColor);
            c.save();
            c.translate(off, off);
            c.drawRoundRect(rect, r, r, paint);
            c.restore();

            // 左上高光
            paint.setColor(lightColor);
            c.save();
            c.translate(-off, -off);
            c.drawRoundRect(rect, r, r, paint);
            c.restore();

            // 底色填在最上层，只留下边缘那一圈
            paint.setMaskFilter(null);
            paint.setColor(baseColor);
            c.drawRoundRect(rect, r, r, paint);

        } else {
            // 凹陷：底色先铺满，内阴影叠在上面
            // 描边宽 2e 且以路径为中心，所以至少要内缩 e，否则一半会被裁掉
            if (!insetEnabled || inset < e) inset = e;
            rect.set(inset, inset, w - inset, h - inset);

            paint.setMaskFilter(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(baseColor);
            c.drawRoundRect(rect, r, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(e * 2f);
            paint.setMaskFilter(new BlurMaskFilter(e, BlurMaskFilter.Blur.NORMAL));

            // 左上内暗影：被挖掉的边缘挡光
            paint.setColor(withAlpha(darkColor, 0.8f));
            c.save();
            c.translate(-off, -off);
            c.drawRoundRect(rect, r, r, paint);
            c.restore();

            // 右下内高光：坑底受光
            paint.setColor(lightColor);
            c.save();
            c.translate(off, off);
            c.drawRoundRect(rect, r, r, paint);
            c.restore();

            paint.setMaskFilter(null);
        }
    }

    /**
     * 玻璃态：不画外投影，改成半透明叠加 + 一道渐变描边 + 方向光。
     *
     * <p>这里刻意不画 mesh —— mesh 由根容器上的 {@link GlassMeshDrawable}
     * 全局画一次，玻璃面只做叠加，底下的光斑自然透出来。
     * 如果每个玻璃面各画一份 mesh，光斑会在每个面上重复，看着像贴纸。</p>
     *
     * <p>凹凸在玻璃态下靠<b>方向光</b>区分，不是靠明暗：凸面顶部一道亮边
     * （受光），凹面顶部一道暗影 + 底部一道亮边（被里面的边缘挡光）。
     * 只平铺一层暗色的话，凹陷会变成一块没有深度的灰色方块 ——
     * 这一点是实机验证过的，不是推测。</p>
     */
    private void drawGlass(Canvas c, int w, int h, float r) {
        rect.set(0, 0, w, h);

        // 0. 主色打底。只有"这个面本身就是主色"时才画（设置面板的色卡）。
        //
        //    这一笔就是「玻璃态下主题色色卡全变白」那个 bug 的修复点：
        //    原来的实现从这里直接跳到第 1 步的白雾，baseColor 完全没参与绘制，
        //    于是 colors() 在玻璃态下是空操作。现在主色先落地。
        paint.setMaskFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        final boolean accent = glassAccent != 0;
        if (accent) {
            paint.setColor(glassAccent);
            c.drawRoundRect(rect, r, r, paint);
        }

        // 1. 半透明白雾。
        //
        //    主色面（色卡）跳过这一步。白雾是玻璃引擎用来表达"半透的玻璃"
        //    的，但它会改变色相 —— 深海蓝 #1652A8 叠 36% 白雾变成 #6E93C9，
        //    已经不是深海蓝了。色卡是"色样"，它唯一的职责是准确显示这个颜色：
        //    用户照着色卡挑颜色，挑完按钮真用上的是 #1652A8，
        //    色卡却显示 #6E93C9，等于骗了用户。
        //
        //    而且跳过之后两张状态的色相才一致：未选中的色卡走凹面，
        //    凹面的叠加色是 4% 黑（几乎透明），本来就接近原色；
        //    选中的若不跳过，同一套主题色会因为选中与否显示成两种颜色。
        //
        //    深度感由第 2 步的方向光和第 3 步的描边提供，不受影响。
        if (!accent) {
            paint.setColor(glassTint);
            c.drawRoundRect(rect, r, r, paint);
        }

        // 2. 方向光。裁剪到圆角矩形内，否则渐变会溢出到圆角外
        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW);
        c.clipPath(clip);

        if (style == CONCAVE) {
            // 顶部内阴影：被"挖掉的那圈边缘"挡住的光
            paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.78f,
                    0x42000000, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(rect, paint);
            // 底部亮边：坑底受光。这一道是凹陷在浅色底上唯一的"存在感"，
            // 所以比顶部阴影更重要，不能省。
            paint.setShader(new android.graphics.LinearGradient(0, h, 0, h * 0.55f,
                    0x55FFFFFF, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(rect, paint);
        } else {
            // 顶部亮边：凸起面的受光面
            paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.45f,
                    0x38FFFFFF, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(rect, paint);
        }
        paint.setShader(null);
        c.restore();

        // 3. 描边：上亮下暗，模拟玻璃的边缘厚度
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setShader(new android.graphics.LinearGradient(
                0, 0, 0, h, glassBorderHi, glassBorderLo, android.graphics.Shader.TileMode.CLAMP));
        RectF edge = new RectF(rect);
        float hw = paint.getStrokeWidth() * 0.5f;
        edge.inset(hw, hw);
        c.drawRoundRect(edge, r - hw, r - hw, paint);
        paint.setShader(null);
    }

    private static int withAlpha(int color, float mul) {
        int a = Math.round(Color.alpha(color) * mul);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        return false;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static float dp(Context ctx, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** 让 BlurMaskFilter 在部分机型的软件路径上也稳定。API 28 起 blur 走的是原生实现。 */
    static boolean blurSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    }
}
