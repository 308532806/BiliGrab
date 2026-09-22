package com.biligrab.downloader;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 自动换行的流式布局，专门用于芯片组。
 *
 * <p>平台没有提供可换行的容器，而 Material 3 的 filter chip 组应当换行排布，
 * 不是横向滚动。这里手写一个最小的实现，避免为此引入 AndroidX。</p>
 *
 * <p>行距与列距统一取 {@link #gap}，对应 8dp 的最小触控间隔要求。</p>
 */
public class FlowLayout extends ViewGroup {

    /** 芯片之间的横向与纵向间距，8dp。 */
    private final int gap;

    public FlowLayout(Context context) {
        this(context, null);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 间距取 touch_gap，与「相邻可点目标至少间隔 8dp」是同一条规则，
        // 不再在代码里重复写一个 8
        gap = getResources().getDimensionPixelSize(R.dimen.touch_gap);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());

        // 约束子视图不得超过可用宽度，否则换行逻辑失去意义
        int childWidthSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);

        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child, childWidthSpec, heightMeasureSpec);

            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();

            if (lineWidth > 0 && lineWidth + gap + cw > available) {
                // 换行
                totalHeight += lineHeight + gap;
                lineWidth = cw;
                lineHeight = ch;
            } else {
                lineWidth = (lineWidth == 0) ? cw : lineWidth + gap + cw;
                lineHeight = Math.max(lineHeight, ch);
            }
        }
        totalHeight += lineHeight;

        int height = totalHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int startX = getPaddingLeft();
        int x = startX;
        int y = getPaddingTop();
        int lineHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();

            if (x > startX && x + cw > startX + available) {
                x = startX;
                y += lineHeight + gap;
                lineHeight = 0;
            }
            child.layout(x, y, x + cw, y + ch);

            x += cw + gap;
            lineHeight = Math.max(lineHeight, ch);
        }
    }
}
