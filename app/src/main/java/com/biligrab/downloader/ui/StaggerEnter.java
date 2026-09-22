package com.biligrab.downloader.ui;

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
     * @param views 按入场顺序排列的视图；null 元素会被跳过
     */
    public static void play(View... views) {
        if (views == null) return;
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            playOne(v, delayFor(i));
        }
    }

    /** 对单个视图执行入场，延迟由调用方决定。 */
    public static void playOne(View v, long delayMs) {
        if (v == null) return;

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

    /**
     * 结果区出现时的入场编排。
     *
     * <p>顺序是刻意的：先预览框（用户最关心"是不是我要的那个视频"），
     * 再标题，再分 P，最后下载行。信息按重要性落位，而不是按视图树顺序。</p>
     */
    public static void playResult(View preview, View title, View meta, View parts, View downloads) {
        playOne(preview, DELAYS[0]);
        playOne(title, DELAYS[1]);
        playOne(meta, DELAYS[1]);
        playOne(parts, DELAYS[2]);
        playOne(downloads, DELAYS[3]);
    }

    private static long delayFor(int index) {
        if (index < 0) return 0;
        if (index >= DELAYS.length) return DELAYS[DELAYS.length - 1];
        return DELAYS[index];
    }
}
