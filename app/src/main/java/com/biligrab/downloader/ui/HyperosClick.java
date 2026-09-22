package com.biligrab.downloader.ui;

import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * HyperOS 风格点击：无水波纹，靠缩放 + 浮雕形变给反馈。
 *
 * <p>规范里的关键约定（见 docs/design-refs/hyper-neumorphic.md）：</p>
 *
 * <ul>
 *   <li><b>没有涟漪。</b>新拟态的"表面"是一块有厚度的实体，涟漪是水面上的效果，
 *       两者在观感上是冲突的。所以整棵界面里不出现 RippleDrawable。</li>
 *   <li><b>按下变凹，松开回凸。</b>这是这套设计里最重要的一个动作 ——
 *       它把"点一下"表达成"按下去"，与浮雕的物理隐喻一致。</li>
 *   <li><b>scale 0.95。</b>幅度要小。压得更狠会让阴影穿帮，
 *       因为阴影是按原尺寸算的。</li>
 *   <li><b>150ms 按下 / 200ms 松开</b>，FastOutSlowInEasing。</li>
 *   <li><b>触觉反馈。</b>规范统一用 TextHandleMove，
 *       比 KEYBOARD_TAP 更轻，适合高频点击。</li>
 * </ul>
 */
public final class HyperosClick {

    /** FastOutSlowInEasing 在平台上的等价物：cubic-bezier(0.4, 0.0, 0.2, 1.0)。 */
    public static final Interpolator FAST_OUT_SLOW_IN =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private static final long DOWN_MS = 150;
    private static final long UP_MS = 200;
    private static final float PRESSED_SCALE = 0.95f;
    /** 按下时浮雕厚度降到 4/6 —— 对应规范里 6dp → 4dp 那一档。 */
    private static final float PRESSED_DEPTH = 4f / 6f;

    private HyperosClick() {}

    /**
     * 给 View 装上无涟漪点击。
     *
     * @param v      目标视图
     * @param action 点击后执行的动作；传 null 表示只做视觉反馈
     */
    public static void bind(final View v, final Runnable action) {
        v.setClickable(true);
        v.setFocusable(true);

        // 去掉系统默认的前景色涟漪。新拟态里不该出现涟漪。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            v.setForeground(null);
        }

        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        animatePress(view, true);
                        return true;
                    case MotionEvent.ACTION_UP:
                        animatePress(view, false);
                        view.performClick();
                        if (action != null) action.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        animatePress(view, false);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 绑定点击并附带一次触觉反馈。 */
    public static void bindWithHaptic(final View v, final Runnable action) {
        bind(v, new Runnable() {
            @Override
            public void run() {
                haptic(v);
                if (action != null) action.run();
            }
        });
    }

    /** 规范统一用 TextHandleMove：比 KEYBOARD_TAP 轻，适合高频点击。 */
    public static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    /** 开关的触觉用 LongPress，与规范一致：切换是一次"落位"，比点击更重。 */
    public static void hapticToggle(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    private static void animatePress(final View v, final boolean down) {
        float target = down ? PRESSED_SCALE : 1f;
        long dur = down ? DOWN_MS : UP_MS;

        v.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(dur)
                .setInterpolator(FAST_OUT_SLOW_IN)
                .start();

        // 浮雕同步做形变：凸 → 凹 太生硬，这里改成厚度收缩。
        // 用 ValueAnimator 直接驱动 drawable，不走属性系统 ——
        // NeumorphicDrawable 不是 View，没有现成的属性可绑。
        final Drawable bg = v.getBackground();
        if (!(bg instanceof NeumorphicDrawable)) return;
        final NeumorphicDrawable nd = (NeumorphicDrawable) bg;
        if (nd.isGlass()) return;   // 玻璃态不参与形变，只做缩放

        float from = nd.getPressFactor();
        ValueAnimator a = ValueAnimator.ofFloat(from, down ? PRESSED_DEPTH : 1f);
        a.setDuration(dur);
        a.setInterpolator(FAST_OUT_SLOW_IN);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                nd.setPressFactor((Float) anim.getAnimatedValue());
            }
        });
        a.start();
    }

    /**
     * 只做按下动画、不触发动作的绑定。
     * 用于那些点击后行为由别处决定的视图（例如整行是按钮、但要看内部状态分派）。
     */
    public static void bindVisualOnly(final View v) {
        bind(v, null);
    }
}
