package com.biligrab.downloader;

import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 极简 Snackbar。
 *
 * <p>为什么不用 Toast：Material 3 的瞬时反馈组件是 Snackbar —— 它出现在底部、
 * 不遮挡焦点、可以带一个动作，而且能被下一个消息顶掉。
 * Toast 既不能带动作，也无法被程序主动收起，用它做反馈是 Android 上的常见退化。</p>
 *
 * <p>同一时间只保留一条：新消息会先移除旧的，避免堆叠。</p>
 */
public final class Snackbar {

    private static final Object TAG = new Object();

    /** 无动作时停留 3.2 秒；有动作时给用户更多时间决定，停留 5 秒。 */
    private static final long DURATION_PLAIN_MS = 3_200L;
    private static final long DURATION_ACTION_MS = 5_000L;

    private Snackbar() {
    }

    /** 动作回调。 */
    public interface Action {
        void onAction();
    }

    public static void show(View root, CharSequence message) {
        show(root, message, null, null, 0);
    }

    public static void show(View root, CharSequence message, String actionLabel, Action action) {
        show(root, message, actionLabel, action, 0);
    }

    /**
     * @param iconRes 0 表示使用默认的信息图标；可传 {@link R.drawable#ic_error} 等
     */
    public static void show(View root, CharSequence message,
                            String actionLabel, Action action, int iconRes) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) root;

        // 同一时间只显示一条
        View existing = parent.findViewWithTag(TAG);
        if (existing != null) {
            parent.removeView(existing);
        }

        final View bar = LayoutInflater.from(root.getContext())
                .inflate(R.layout.view_snackbar, parent, false);
        bar.setTag(TAG);

        ((TextView) bar.findViewById(R.id.tvSnackbarMessage)).setText(message);

        ImageView icon = bar.findViewById(R.id.ivSnackbarIcon);
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
        }

        final Button actionButton = bar.findViewById(R.id.btnSnackbarAction);
        final boolean hasAction = actionLabel != null && action != null;
        if (hasAction) {
            actionButton.setText(actionLabel);
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setOnClickListener(v -> {
                dismiss(bar);
                action.onAction();
            });
        }

        // 贴在底部，两侧留出屏幕边距；底部 inset 由调用方通过 root 的 padding 提供
        ViewGroup.LayoutParams raw = bar.getLayoutParams();
        ViewGroup.MarginLayoutParams lp;
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            lp = (ViewGroup.MarginLayoutParams) raw;
        } else {
            lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        int margin = bar.getResources().getDimensionPixelSize(R.dimen.screen_margin);
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        // 留在 FAB 上方，两者不重叠
        lp.bottomMargin = margin + bar.getResources().getDimensionPixelSize(R.dimen.touch_min);
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) lp).gravity =
                    android.view.Gravity.BOTTOM;
        }
        bar.setLayoutParams(lp);

        parent.addView(bar);

        animate(bar, R.anim.snackbar_enter);
        bar.postDelayed(() -> dismiss(bar),
                hasAction ? DURATION_ACTION_MS : DURATION_PLAIN_MS);
    }

    private static void dismiss(final View bar) {
        if (bar.getParent() == null) {
            return;
        }
        animate(bar, R.anim.snackbar_exit);
        long delay = animationsEnabled(bar) ? 160L : 0L;
        bar.postDelayed(() -> {
            ViewGroup p = (ViewGroup) bar.getParent();
            if (p != null) {
                p.removeView(bar);
            }
        }, delay);
    }

    private static void animate(View view, int animRes) {
        if (!animationsEnabled(view)) {
            return;
        }
        view.startAnimation(android.view.animation.AnimationUtils
                .loadAnimation(view.getContext(), animRes));
    }

    /** 尊重系统「移除动画」设置：关掉时不做任何位移与淡入。 */
    private static boolean animationsEnabled(View view) {
        try {
            return ValueAnimator.areAnimatorsEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
