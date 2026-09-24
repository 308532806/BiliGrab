package com.biligrab.downloader.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import com.biligrab.downloader.R;

/**
 * 按钮，可带浮雕。
 *
 * <p>本身不实现任何业务逻辑，只做两件事：把 XML 里的 neu* 属性转交给
 * {@link NeumAttr}，以及按 {@code neuRole} 解析文字色。</p>
 *
 * <h3>为什么文字色必须在这里解析，不能写在 style 里</h3>
 *
 * <p>{@code Widget.Button.Filled} 原来在 XML 里写死了
 * {@code android:textColor="@color/scheme_0_primary"} —— 那是<b>樱花粉，
 * 第一套主题色的编译期快照</b>。用户在设置里换成深海蓝之后，所有 filled
 * 按钮的文字仍然是樱花粉，而按钮底色是跟着主题走的，于是出现粉字压在蓝底上。</p>
 *
 * <p>XML 里解决不了这件事：style 只能在编译期取一个静态值，
 * 而主题色是运行期设置。所以三个 style 的 textColor 全部留空，
 * 改由这里每次构造时按当前主题解析。</p>
 *
 * <h3>三个角色</h3>
 *
 * <ul>
 *   <li>{@code filled} —— 主操作，每屏一个。主色文字。</li>
 *   <li>{@code tonal} —— 次级主色操作。也是主色文字，靠更浅的浮雕降低权重。</li>
 *   <li>{@code outlined} —— 中性操作（取消、删除）。正文色，与主题色无关。</li>
 * </ul>
 *
 * <p>文字色一律过 {@link HyperTheme#primaryText}，不是直接拿主色 ——
 * 五套主题色里只有深海蓝天生够深，其余四套当文字都不达正文 4.5:1
 * （樱花粉在浅色页面上只有 2.39:1）。</p>
 */
public class NeuButton extends android.widget.Button {

    /** 见 attrs.xml 的 neuRole。默认 none：不碰文字色，交给 style。 */
    public static final int ROLE_NONE = 0;
    public static final int ROLE_FILLED = 1;
    public static final int ROLE_TONAL = 2;
    public static final int ROLE_OUTLINED = 3;

    private int role = ROLE_NONE;

    public NeuButton(Context context) {
        super(context);
    }

    public NeuButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        NeumAttr.apply(this, context, attrs);
        readRole(context, attrs);
        applyRoleColor();
    }

    public NeuButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        NeumAttr.apply(this, context, attrs);
        readRole(context, attrs);
        applyRoleColor();
    }

    private void readRole(Context ctx, AttributeSet attrs) {
        TypedArray a = null;
        try {
            a = ctx.obtainStyledAttributes(attrs, R.styleable.Neumorphic);
            role = a.getInt(R.styleable.Neumorphic_neuRole, ROLE_NONE);
        } catch (Exception ignored) {
            // 解析失败就退回 none，不要让按钮整个构造不出来
        } finally {
            if (a != null) a.recycle();
        }
    }

    /** 按角色上色。{@code filled} 与 {@code tonal} 都用主色，区别只在浮雕。 */
    private void applyRoleColor() {
        if (isInEditMode()) return;
        Context ctx = getContext();
        switch (role) {
            case ROLE_FILLED:
            case ROLE_TONAL:
                setTextColor(HyperTheme.primaryText(ctx));
                break;
            case ROLE_OUTLINED:
                setTextColor(HyperTheme.textPrimary(ctx));
                break;
            default:
                break;
        }
    }

    /**
     * 禁用态。
     *
     * <p>项目里没有 {@code res/color/} 目录，也就没有任何 ColorStateList，
     * 所以按钮禁用后文字色<b>和启用时完全一样</b> —— 用户看不出它不能按。
     * {@code elev_button_disabled} 这个 token 也从没被任何地方引用过。</p>
     *
     * <p>不引 ColorStateList：那需要为每个角色各写一个 xml 文件，
     * 而且每套主题色都得再写一份（ColorStateList 同样是编译期静态值）。
     * 直接在代码里按当前角色算，一处收口。</p>
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (isInEditMode()) return;
        if (enabled) {
            applyRoleColor();
        } else {
            setTextColor(HyperTheme.textDisabled(getContext()));
        }
    }
}
