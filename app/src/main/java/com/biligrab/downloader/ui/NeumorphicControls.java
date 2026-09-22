package com.biligrab.downloader.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;
import android.widget.Switch;

import com.biligrab.downloader.R;

/**
 * 把系统控件"浮雕化"。
 *
 * <p>开关和滑块没有替换成自定义 View，而是保留原控件、只换 drawable。
 * 原因是它们的 Java 契约不只有外观：{@code SeekBar} 带
 * {@code OnSeekBarChangeListener}（拖拽中/开始/结束三个回调），
 * {@code CompoundButton} 带 {@code OnCheckedChangeListener} 与可访问性语义。
 * 重写一个 View 就要把这些全部重新实现一遍，收益却只有画面。</p>
 *
 * <p>换 drawable 能拿到完整的浮雕效果，因为这些 drawable 由控件在
 * <b>硬件画布</b>上绘制 —— {@link NeumorphicDrawable} 的模糊照常生效。</p>
 */
public final class NeumorphicControls {

    private NeumorphicControls() {}

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * 把 Switch 换成新拟态开关。
     *
     * <p>轨道是<b>凹槽</b>（开关底座被挖进去），滑块是<b>凸起</b>（可以推动的实体）。
     * 这个凹凸关系不能反 —— 反过来就成了"坑里嵌着一块凸起"，
     * 看起来像是坏了。</p>
     *
     * <p>参数类型是 {@code Switch} 而不是 {@code CompoundButton}：
     * 轨道/滑块的 setter 只在 Switch 上，不在父类。调用方本来拿到的也是 Switch。</p>
     */
    public static void dressSwitch(Switch sw) {
        Context ctx = sw.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        // 轨道：凹槽，高 28dp，圆角 14dp。
        //
        // 分选中/未选中两张。为什么必须用颜色而不是只靠浮雕：
        // 新拟态的默认约定是"所有面与背景同色"，如果轨道也照办，
        // 那么开关的两种状态在视觉上完全一样 —— 用户看不出它到底是开还是关。
        // 浮雕能表达"这是一个可以按的槽"，表达不了"槽现在是什么状态"。
        //
        // StateListDrawable 自己会跟着 Switch 的 drawable state 切换，
        // 不需要注册 OnCheckedChangeListener（那个回调是调用方的，不能抢）。
        int primary = HyperTheme.primary(ctx);

        NeumorphicDrawable trackOff = new NeumorphicDrawable(ctx)
                .style(NeumorphicDrawable.CONCAVE)
                .radius(14)
                .elevation(1.5f)
                .intrinsic(52, 28)
                .noInset();
        trackOff.colors(HyperTheme.divider(ctx), HyperTheme.neumLight(ctx),
                HyperTheme.neumDark(ctx));

        NeumorphicDrawable trackOn = new NeumorphicDrawable(ctx)
                .style(NeumorphicDrawable.CONCAVE)
                .radius(14)
                .elevation(1.5f)
                .intrinsic(52, 28)
                .noInset();
        trackOn.colors(primary, HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));

        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);

        // 滑块：凸起圆盘，直径 22dp。
        // 取 22 而不是 20：Switch 把滑块从轨道左缘推到右缘，
        // 直径越小越容易在两端"戳出"轨道 14dp 的圆头之外。
        NeumorphicDrawable thumb = new NeumorphicDrawable(ctx)
                .style(NeumorphicDrawable.CONVEX)
                .radius(999)
                .elevation(2.5f)
                .intrinsic(22, 22)
                .noInset();
        // 滑块用卡片色而不是页面色：轨道开着时是主色，滑块压在上面必须有区别，
        // 用背景色的话在白底卡片上会和轨道糊成一片。
        thumb.colors(HyperTheme.card(ctx), HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));

        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        // 注意：这里没有 setThumbOffset —— 那是 SeekBar 的 API，Switch 没有。
        // Switch 的滑块位置完全由轨道和滑块自身的尺寸推出来。
        sw.setSwitchPadding(Math.round(8 * density));

        sw.setShowText(false);
        sw.setButtonDrawable(null);
        // 去掉系统给 Switch 的默认按下态图片
        sw.setBackground(null);

        // 关掉系统的着色。注意必须是 null 而不是透明色 ——
        // 设成 Color.TRANSPARENT 会把滑块整个涂成透明，直接看不见。
        sw.setThumbTintList(null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            sw.setTrackTintList(null);
        }

        // 触觉反馈挂在 touch 上而不是 checkedChange 上：
        // checkedChange 是调用方要用的回调，不能在这里抢走。
        // 返回 false 让控件继续处理，切换行为不受影响。
        sw.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    HyperosClick.hapticToggle(v);
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------------
    // 滑块
    // ------------------------------------------------------------------

    /**
     * 把 SeekBar 换成新拟态滑块。
     *
     * <p>结构是规范里的三件套：凹陷的槽（14dp 高、圆角 7dp）、
     * 已播放段（主色半透明）、以及一个 32dp 的凸起圆盘滑块 ——
     * 圆盘中心还有一个 8dp 的主色圆点。</p>
     */
    public static void dressSeekBar(SeekBar sb) {
        Context ctx = sb.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int trackH = Math.round(14 * density);
        int sbH = Math.round(sb.getResources().getDimension(R.dimen.touch_min));

        // 槽：凹
        NeumorphicDrawable track = new NeumorphicDrawable(ctx)
                .style(NeumorphicDrawable.CONCAVE)
                .radius(7)
                .elevation(2)
                .noInset();
        track.colors(HyperTheme.neumBase(ctx), HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));

        // 已播放段：凸起的实心条，主色 80% —— 规范里的 activeColor.copy(alpha = .8f)
        int p = HyperTheme.primary(ctx);
        NeumorphicDrawable played = new NeumorphicDrawable(ctx)
                .style(NeumorphicDrawable.CONVEX)
                .radius(7)
                .elevation(2)
                .noInset();
        played.colors(Color.argb(204, Color.red(p), Color.green(p), Color.blue(p)),
                HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));

        ClipDrawable clip = new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL);

        LayerDrawable progress = new LayerDrawable(new Drawable[]{track, clip});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        // 把 14dp 的槽在 48dp 的控件高度里垂直居中
        int pad = Math.max(0, (sbH - trackH) / 2);
        progress.setLayerInset(0, 0, pad, 0, pad);
        progress.setLayerInset(1, 0, pad, 0, pad);

        sb.setProgressDrawable(progress);
        sb.setThumb(newThumb(ctx, density));
        sb.setThumbOffset(0);
        sb.setSplitTrack(false);
        // 注意：这里不动 OnSeekBarChangeListener ——
        // 那是 PreviewController 拖拽定位要用的回调，绝不能在这里清掉。
    }

    /**
     * 32dp 凸起圆盘，中心嵌一个 8dp 的主色圆点。
     *
     * <p>圆点不是装饰：纯新拟态的滑块在深色模式下几乎看不见边界，
     * 一个高对比的点能明确指示"当前值在这里"。</p>
     */
    private static Drawable newThumb(Context ctx, float density) {
        int size = Math.round(32 * density);
        int dot = Math.round(8 * density);

        NeumorphicDrawable disc = new NeumorphicDrawable(ctx)
                .style(NeumorphicDrawable.CONVEX)
                .radius(999)
                .elevation(2)
                .noInset();
        disc.colors(HyperTheme.neumBase(ctx), HyperTheme.neumLight(ctx), HyperTheme.neumDark(ctx));
        disc.setBounds(0, 0, size, size);

        GradientDrawable center = new GradientDrawable();
        center.setShape(GradientDrawable.OVAL);
        center.setColor(HyperTheme.primary(ctx));
        int off = (size - dot) / 2;

        LayerDrawable ld = new LayerDrawable(new Drawable[]{disc, center});
        ld.setLayerInset(1, off, off, off, off);
        ld.setBounds(0, 0, size, size);
        ld.setAlpha(255);
        return ld;
    }

    /*
     * 这里曾经有过 refresh(Switch) 与 refresh(SeekBar) 两个换肤方法
     * （重新 dress 一遍以重新取色）。已删除：全项目没有调用点。
     *
     * 开关与滑块的轨道/滑块 drawable 确实不在 NeumorphicSurface 的覆盖范围内
     * —— 它们不是 View 的背景，而是 CompoundButton/AbsSeekBar 自己的 drawable。
     * 但换肤走的是 sheet.dismiss() + Activity.recreate()，整棵树重建，
     * dressSwitch / dressSeekBar 会被重新执行一遍，本来就覆盖到了。
     *
     * 如果将来改成不重建界面来换肤，这两个方法要加回来，而且**顺序很关键**：
     * setTrackDrawable 会重置 Switch 的选中态渲染，必须先记住 isChecked()
     * 再 dress、最后恢复，否则开着的高级选项会在换肤后自己关掉。
     */
}
