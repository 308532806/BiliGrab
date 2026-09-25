package com.biligrab.downloader.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 玻璃态的网格背景 —— 整套玻璃引擎的底子。
 *
 * <p>规范里最关键的一条约定：<b>只有这里画 mesh，其它玻璃面都不画。</b>
 * 玻璃卡片、玻璃按钮全部只做半透明叠加，底下的光斑自己透出来。
 * 如果每个玻璃面各画一份 mesh，光斑会在每个面上重复一遍，
 * 整屏看起来像贴满了彩色贴纸，而不是"透过玻璃看同一片背景"。</p>
 *
 * <p>所以这个 drawable 挂在<b>根容器</b>上，整屏只挂一个。</p>
 *
 * <p>深浅两套配色是分别调的，不是同一套换个底色：
 * 浅色用小米品牌色系的 40/80 档做柔和的彩色雾，
 * 深色大幅压低不透明度 —— 同样浓度的光斑放在 #0F1320 上会显得脏。</p>
 */
public class GlassMeshDrawable extends Drawable {

    /** 一个光斑：位置与半径都用相对比例表示，这样任意尺寸下构图一致。 */
    private static final class Spot {
        final float x, y;      // 0..1
        final int color;       // 已含 alpha
        final float radius;    // 相对短边的倍数

        Spot(float x, float y, int color, float radius) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.radius = radius;
        }
    }

    // 小米品牌色系。mesh 的彩色雾由这几支混出来。
    private static final int MI_BLUE_40 = 0xFF4C8EFF;
    private static final int MI_GREEN_40 = 0xFF6DD400;
    private static final int MI_BLUE_80 = 0xFFADC9FF;
    private static final int MI_GREEN_80 = 0xFFB5F37F;

    // 浅色：5 个光斑。底色是暖白 #F3EFEA。
    //
    // 浓度是照着实机截图调下来的：规范给的是"柔和的彩色雾"，
    // 而按品牌色原值铺上去会变成一片高饱和的渐变，玻璃卡片反而被背景压住 ——
    // 玻璃面只是一层 18% 的白，背景一浓它就没有存在感了。
    private static final int LIGHT_BASE = 0xFFF3EFEA;
    private static final Spot[] LIGHT_SPOTS = {
            new Spot(0.08f, 0.02f, withAlpha(MI_BLUE_40, 0.30f), 0.78f),
            new Spot(0.92f, 0.10f, withAlpha(MI_GREEN_40, 0.20f), 0.72f),
            new Spot(0.75f, 0.55f, withAlpha(MI_BLUE_80, 0.34f), 0.88f),
            new Spot(0.15f, 0.80f, withAlpha(MI_GREEN_80, 0.30f), 0.82f),
            new Spot(0.55f, 0.30f, 0x40FFFFFF, 0.92f),
    };

    // 深色：4 个光斑，浓度再压一档。底色 #0F1320
    private static final int DARK_BASE = 0xFF0F1320;
    private static final Spot[] DARK_SPOTS = {
            new Spot(0.10f, 0.05f, withAlpha(MI_BLUE_40, 0.22f), 0.80f),
            new Spot(0.90f, 0.15f, withAlpha(MI_GREEN_40, 0.14f), 0.75f),
            new Spot(0.70f, 0.70f, withAlpha(MI_BLUE_80, 0.16f), 0.85f),
            new Spot(0.20f, 0.90f, withAlpha(MI_GREEN_80, 0.13f), 0.80f),
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect rect = new Rect();
    private final Path clipPath = new Path();
    private final float[] cornerRadii;
    private final boolean roundedCorners;
    private boolean dark;

    /** 是否走深色网格。由调用方指定，避免 drawable 反查主题配置。 */
    public GlassMeshDrawable(boolean dark) {
        this(dark, 0f, 0f);
    }

    /**
     * 仅为顶部两个角提供圆角裁剪，适用于贴住屏幕底边的底部面板。
     * 半径单位为像素；底部两角保持直角。
     */
    public GlassMeshDrawable(boolean dark, float topLeftRadius, float topRightRadius) {
        this.dark = dark;
        float topLeft = Math.max(0f, topLeftRadius);
        float topRight = Math.max(0f, topRightRadius);
        cornerRadii = new float[] {topLeft, topLeft, topRight, topRight, 0f, 0f, 0f, 0f};
        roundedCorners = topLeft > 0f || topRight > 0f;
        paint.setDither(true);
    }

    public GlassMeshDrawable dark(boolean d) {
        if (this.dark == d) return this;
        this.dark = d;
        invalidateSelf();
        return this;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        int saveCount = canvas.save();
        if (roundedCorners) {
            clipPath.reset();
            clipPath.addRoundRect(b.left, b.top, b.right, b.bottom,
                    cornerRadii, Path.Direction.CW);
            canvas.clipPath(clipPath);
        }
        rect.set(b);

        Spot[] spots = dark ? DARK_SPOTS : LIGHT_SPOTS;
        canvas.drawColor(dark ? DARK_BASE : LIGHT_BASE);

        float shortSide = Math.min(b.width(), b.height());
        for (Spot s : spots) {
            float cx = b.left + s.x * b.width();
            float cy = b.top + s.y * b.height();
            float r = Math.max(1f, s.radius * shortSide);

            paint.setShader(new RadialGradient(cx, cy, r, s.color, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
        }
        paint.setShader(null);

        // 顶部一道极淡的竖向渐变，让整屏有一点方向感
        paint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                dark ? 0x14FFFFFF : 0x0DFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
        paint.setShader(null);
        canvas.restoreToCount(saveCount);
    }

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
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
        return android.graphics.PixelFormat.OPAQUE;
    }
}
