package com.biligrab.downloader.ui;

import android.content.Context;
import android.util.AttributeSet;

/**
 * 输入框。新拟态的输入框是凹陷的，见 concave。
 *
 * <p>本身不实现任何逻辑，只是把 XML 里的 neu* 属性转交给 {@link NeumAttr}。
 * 之所以需要这么多同构的子类，是因为新拟态要能落在任意一种基础控件上，
 * 而 Java 没有"给已有类追加行为"的手段。</p>
 */
public class NeuEdit extends android.widget.EditText {

    public NeuEdit(Context context) {
        super(context);
    }

    public NeuEdit(Context context, AttributeSet attrs) {
        super(context, attrs);
        NeumAttr.apply(this, context, attrs);
    }

    public NeuEdit(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        NeumAttr.apply(this, context, attrs);
    }
}