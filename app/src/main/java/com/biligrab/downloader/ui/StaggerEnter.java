package com.biligrab.downloader.ui;

import android.animation.ValueAnimator;
import android.view.View;

/**
 * 分段入场动画。
 *
 * <p>规范给的参数：总时长 1000ms，透明度 0→1，纵向偏移 60→0，
 * 缩放 0.92→1，逐项延迟 0 / 150 / 250 / 350 / 450ms，
 * 缓动一律 FastOutSlowInEasing。</p>
 *
 * <p>为什么偏移是"从下往上"而不是"从左往右"：新拟态的卡片是平铺在
 * 一个平面上的，纵向位移读起来像是"从平面里升起来"，
 * 横向位移则会破坏这个平面的完整性。</p>
 *
 * <p>延迟刻意不按等间距排 —— 等间距读起来像机器在逐行打印，
 * 前疏后密的节奏更像东西自然落位。</p>
 */
public final class StaggerEnter {

    /** 逐项延迟（毫秒）。超出这个长度的项沿用最后一个值。 */
    private static final long[] DELAYS = {0, 150, 250, 350, 450};

    private static final long DURATION = 1000;
    private static final float OFFSET_Y_DP = 60f;
    private static final float FROM_SCALE = 0.92f;

    private StaggerEnter() {}

    /**
     * 让一组视图依次入场。
     *
     * @param views 按入场顺序排列的视图；null 和非 VISIBLE 元素会被跳过
     */
    public static void play(View... views) {
        if (views == null) return;
        int visibleIndex = 0;
        for (View v : views) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            playOne(v, delayFor(visibleIndex++));
        }
    }

    /** 对单个视图执行入场，延迟由调用方决定。 */
    public static void playOne(View v, long delayMs) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;

        // 不要让一次旧动画把刷新后的最终状态带走。这个方法只在首次入场
        // 使用，但 cancel() 也让调用方重复触发时保持确定性。
        v.animate().cancel();

        // Android 的「移除动画」开关是全局设置。关闭时不要先把内容设成透明，
        // 否则用户会看到一帧空白，甚至在 ROM 禁止动画时一直等不到回调。
        if (!ValueAnimator.areAnimatorsEnabled()) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }

        float density = v.getResources().getDisplayMetrics().density;
        float offsetPx = OFFSET_Y_DP * density;

        // 初始态：透明、下移、略小
        v.setAlpha(0f);
        v.setTranslationY(offsetPx);
        v.setScaleX(FROM_SCALE);
        v.setScaleY(FROM_SCALE);

        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(DURATION)
                .setInterpolator(HyperosClick.FAST_OUT_SLOW_IN)
                .start();
    }

    /*
     * 这里曾经有过一个 playResult(preview, title, meta, parts, downloads)，
     * 把结果区的五块按固定顺序编排动画。已删除。
     *
     * 原因：它要求调用方传五个具体控件，而结果区的每一块的可见性是随解析结果变的
     * （单 P 没有分 P 列表、纯音频没有画质提示、解析失败时连预览都没有）。
     * 传进来一个已经 GONE 的控件，就是给一个看不见的东西做动画 —— 白做，
     * 还白占一个延迟档位，让后面真正该出现的那块晚 100ms 才动。
     *
     * 现在的做法见 MainActivity.renderResult()：收集 resultBox 当前**可见**的
     * 直接子 View，交给上面的 play(View...) 按顺序编排。既不依赖 id 列表，
     * 也不会给隐藏的块空转。
     */

    private static long delayFor(int index) {
        if (index < 0) return 0;
        if (index >= DELAYS.length) return DELAYS[DELAYS.length - 1];
        return DELAYS[index];
    }
}
