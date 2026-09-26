package com.biligrab.downloader;

import android.app.Activity;
import android.app.Dialog;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.biligrab.downloader.ui.GlassMeshDrawable;
import com.biligrab.downloader.ui.HyperTheme;
import com.biligrab.downloader.ui.HyperosClick;
import com.biligrab.downloader.ui.NeumAttr;
import com.biligrab.downloader.ui.NeumorphicControls;
import com.biligrab.downloader.ui.NeumorphicDrawable;
import com.biligrab.downloader.ui.NeumorphicSurface;
import com.biligrab.downloader.ui.StaggerEnter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面。
 *
 * <p>Material 3 的实现要点：</p>
 * <ul>
 *   <li>一个界面只有一个主要动作，承载在扩展 FAB 上；</li>
 *   <li>瞬时反馈一律走 Snackbar，不用 Toast；</li>
 *   <li>设置是不需要打断的任务，用底部表单而不是模态对话框；</li>
 *   <li>画质选择用可换行的芯片组，而不是下拉选择器；</li>
 *   <li>解析结果、空状态、加载态、错误态是四个互斥的视图状态，
 *       任何时候都只显示其中一个 —— 不会出现空白屏。</li>
 * </ul>
 */
public class MainActivity extends Activity
        implements DownloadService.Listener, TaskStore.Listener {

    private static final String TAG = "BiliGrab";

    private static final int REQ_PERMS = 1001;
    /** 应用内登录。和权限请求分开编号，否则回调里分不清是谁返回的。 */
    private static final int REQ_LOGIN = 1002;
    /** 自选下载目录（SAF 的目录选择器）。 */
    private static final int REQ_PICK_DIR = 1003;

    private static final String STATE_URL = "state_url";
    private static final String STATE_HAD_RESULT = "state_had_result";

    /** 低于这个画质码就认为受到了未登录限制。80 = 1080P。 */
    private static final int QN_1080P = 80;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private Prefs prefs;

    // ---- 顶栏与输入 ----
    private View topBar;
    private ScrollView scroll;
    private EditText inputUrl;
    private Button btnParse;
    private ImageButton btnPaste;
    private ImageButton btnSettings;
    private ImageButton btnDownloads;

    // ---- 四个互斥状态 ----
    private View emptyBox;
    private View loadingBox;
    /** 加载态的副标题。文案按来源切换，所以要在代码里改。 */
    private TextView tvLoadingBody;
    private View errorBox;
    private View resultBox;

    // ---- 错误 ----
    private TextView errorTitle;
    private TextView errorFix;
    private TextView errorDetail;
    private Button btnRetry;

    /**
     * 当前主题色（已解析成 ARGB）。
     *
     * <p>缓存一份是因为列表行会反复绑定，而 {@code HyperTheme.primary()} 每次
     * 都要 obtainTypedArray —— 单次调用不算贵，放进 getView 里就贵了。</p>
     */
    private int primaryColor = android.graphics.Color.GRAY;

    // ---- 结果 ----
    private PreviewController preview;
    private TextView tvTitle;
    private TextView tvMeta;
    private View qualityHintBox;
    private TextView tvQualityHint;
    private Button btnQualityHintAction;
    private TextView tvPartsLabel;
    private ListView listParts;
    private LinearLayout listDownloads;
    private TextView tvSavedTo;

    // ---- 状态 ----
    private Model.Video current;
    private Model.PlayInfo currentProbe;

    /**
     * YouTube 预览用的本地转发。MediaPlayer 不认应用层代理，
     * 只能让它连 127.0.0.1，由我们经代理把字节搬回来。
     * 换视频或退出时必须停掉，否则端口一直占着。
     */
    private LocalRelay previewRelay;

    /** 停掉预览转发。可以安全地重复调用。 */
    private void stopPreviewRelay() {
        if (previewRelay != null) {
            previewRelay.stop();
            previewRelay = null;
        }
    }
    private int selectedQn = Prefs.DEFAULT_QN;
    private int selectedPart;
    private boolean downloading;
    private PartAdapter adapter;

    /** 每个画质一行（末尾还有一行「仅音频」）。 */
    private final List<DownloadRow> rows = new ArrayList<>();
    /** 正在下载的那一行，没有任务时为 null。 */
    private DownloadRow activeRow;
    /**
     * 首页刚点下、正在跑的那条记录。
     *
     * <p>只为了让进度回调能确认「说的就是我这一行」—— 回调带的是任务 id，
     * 而首页只认得自己启动的那一个。</p>
     */
    private DownloadTask activeTask;

    /**
     * 预览播放源请求的画质。
     *
     * <p>预览要的是尽快出画面，不是画质，所以固定取最低档。</p>
     */
    private static final int PREVIEW_QN = 16;

    /** 一行下载目标持有的视图。 */
    private static final class DownloadRow {
        final int qn;
        final boolean audioOnly;
        final View main;
        final TextView label;
        final TextView meta;
        final ImageView icon;
        final TextView percent;
        final ProgressBar bar;
        /** 空闲时该显示的副标题，下载结束后要还原回来。 */
        final String metaIdle;
        /**
         * 这一行对应的流。B 站这边是 null（服务会自己按 qn 重新解析），
         * YouTube 这边必须带上 —— 直链是界面解析阶段拿到的，
         * 服务端没有别的办法再拿到它。
         */
        final Model.Stream stream;

        /** 这一行当前代表哪条下载记录（空串表示还没开始下）。 */
        String boundId = "";

        DownloadRow(int qn, boolean audioOnly, Model.Stream stream, View main, TextView label,
                    TextView meta, ImageView icon, TextView percent, ProgressBar bar,
                    String metaIdle) {
            this.qn = qn;
            this.audioOnly = audioOnly;
            this.stream = stream;
            this.main = main;
            this.label = label;
            this.meta = meta;
            this.icon = icon;
            this.percent = percent;
            this.bar = bar;
            this.metaIdle = metaIdle;
        }

        void setRunning(boolean running) {
            icon.setVisibility(running ? View.GONE : View.VISIBLE);
            percent.setVisibility(running ? View.VISIBLE : View.GONE);
            bar.setVisibility(running ? View.VISIBLE : View.GONE);
            if (!running) {
                meta.setText(metaIdle);
                bar.setProgress(0);
            }
        }

        /**
         * 这条进度回调说的是不是我这一行。
         *
         * <p>服务是按任务 id 推送的，而首页只关心自己刚点的那一个。
         * 不比对的话，用户在下载管理页操控的**别的**任务会把首页这一行也刷掉
         * —— 看起来就像首页那个下载串了。</p>
         */
        boolean matches(DownloadTask t) {
            return t != null && boundId.equals(t.id);
        }

        /** 记下这一行现在代表谁。 */
        void bind(DownloadTask t) {
            boundId = t.id;
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 外观覆盖必须早于任何资源访问，否则 applyOverrideConfiguration 会抛异常
        applyThemeOverride();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        bindViews();
        applyNeumorphicTheme();
        applyWindowInsets();
        // PreviewController 自己 findViewById 绑定预览区，
        // 必须在 setContentView 之后构造
        preview = new PreviewController(this);
        wireActions();
        setupPartsList();

        // 旋转等重建后，自动重新解析一次，用户不必再点一下
        if (savedInstanceState != null) {
            String url = savedInstanceState.getString(STATE_URL, "");
            if (!url.isEmpty()) {
                inputUrl.setText(url);
                if (savedInstanceState.getBoolean(STATE_HAD_RESULT, false)) {
                    btnParse.post(this::doParse);
                } else {
                    renderEmpty();
                }
            } else {
                renderEmpty();
            }
        } else {
            renderEmpty();
            handleIntent(getIntent());
        }

        ensureBuvid();
        requestPermissionsIfNeeded();
        warmUpYouTubeEngine();
    }

    /**
     * 后台预热 YouTube 解析引擎。
     *
     * <p>首次使用要把 {@code libpython.zip.so} 解压到应用私有目录、把内置的
     * yt-dlp 落地，实测要十几秒。放到启动时做掉，用户第一次点「解析」就不用
     * 对着进度条发愣。之后每次启动只是几次文件存在性检查，开销可以忽略。</p>
     *
     * <p>失败不提示：没装引擎不该影响 B 站功能，用户真去解析 YouTube 链接时
     * 会在结果区看到具体原因。</p>
     */
    private void warmUpYouTubeEngine() {
        bg.execute(() -> {
            String err = YouTubeEngine.ensureReady(this);
            if (err.isEmpty()) {
                Log.i(TAG, "YouTube 引擎就绪，yt-dlp " + YouTubeEngine.version(this));
            } else {
                Log.w(TAG, "YouTube 引擎预热失败：" + err);
            }
        });
    }

    /**
     * 处理用户在系统里选择的外观。
     *
     * <p>走 {@code applyOverrideConfiguration} 而不是 {@code setTheme}：
     * 前者能同时改写 uiMode，从而让 values-night 资源生效。</p>
     */
    private void applyThemeOverride() {
        int mode = Prefs.themeModeStatic(this);
        if (mode == Prefs.THEME_SYSTEM) {
            return;
        }
        Configuration cfg = new Configuration(getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == Prefs.THEME_DARK
                        ? Configuration.UI_MODE_NIGHT_YES
                        : Configuration.UI_MODE_NIGHT_NO);
        try {
            applyOverrideConfiguration(cfg);
        } catch (Throwable ignored) {
            // 资源已被访问时无法覆盖，退回跟随系统，不影响功能
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.addListener(this);
        // 记录层也要听：在下载管理页里删掉一条还没下完的任务时，
        // service 只发 TaskStore 的回调，**不会**发 onTaskFinished
        //（没有任何线程需要等它结束）。首页原来只听 service，
        // 于是那一行永远停在被删之前的百分比上，图标也不还原 ——
        // 用户看到的是一个「卡住的下载」，而任务其实已经没了。
        TaskStore.get().addListener(this);
        // 从管理页返回时补刷一次：期间任务可能已经被删，
        // 光靠回调要在下次通知才有反应，这里直接对齐一次现状。
        syncRowsWithStore();
    }

    @Override
    protected void onPause() {
        DownloadService.removeListener(this);
        TaskStore.get().removeListener(this);
        // 退到后台就别继续出声了；再回来时用户自己点播放继续
        if (preview != null) {
            preview.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (preview != null) {
            preview.release();
        }
        stopPreviewRelay();
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_URL, inputUrl.getText().toString());
        outState.putBoolean(STATE_HAD_RESULT, current != null);
    }

    // ==================================================================
    // 视图绑定与初始化
    // ==================================================================

    /**
     * 应用 Hyper-Neumorphic 主题。
     *
     * <p>布局里的浮雕由 {@code Neu*} 系列 View 在构造时自动铺好，
     * 这里只处理两件它们在 XML 阶段做不了的事：</p>
     *
     * <ol>
     *   <li><b>玻璃引擎的整屏网格。</b>mesh 必须<b>只画一份</b>、
     *       挂在根容器上。如果每个玻璃面各画一份，光斑会在每个面上重复，
     *       整屏会像贴满了彩色贴纸，而不是"透过玻璃看同一片背景"。</li>
     *   <li><b>主色。</b>主色是运行时可切换的（5 套），而主题资源是编译期固定的，
     *       两者无法共存 —— 所以 XML 里只放默认值兜底，真正的取色在这里覆盖。</li>
     * </ol>
     */
    private void applyNeumorphicTheme() {
        View root = findViewById(R.id.root);
        if (root != null) {
            if (HyperTheme.isGlass(this)) {
                root.setBackground(new GlassMeshDrawable(HyperTheme.isDark(this)));
                applySkeletonGlass();
            } else {
                root.setBackgroundColor(HyperTheme.background(this));
            }
        }

        int p = HyperTheme.primary(this);
        primaryColor = p;

        // 主按钮的文字色。三颗按钮在 XML 里用的是 scheme_0_primary 兜底，
        // 用户换了主题色之后必须在这里跟着走。
        applyAccent(findViewById(R.id.btnParse), p);
        applyAccent(findViewById(R.id.btnRetry), p);
        applyAccent(findViewById(R.id.btnQualityHintAction), p);

        // 剩下所有吃主色的控件走声明式：布局里标 android:tag="neu:accent"，
        // 这里一次遍历刷掉。见 NeumAttr.applyAccentTags 关于"为什么逐个覆盖
        // 一定会漏"的说明 —— 实测漏了六个，其中包括预览区的转圈和暂停键。
        NeumAttr.applyAccentTags(findViewById(R.id.root));

        // 输入框的光标与选中高亮。不改的话换主题色后光标还是默认蓝，
        // 在粉色主题下非常跳。
        applyCaret(inputUrl, p);
    }

    /**
     * 玻璃态下的骨架屏占位块。
     *
     * <p>XML 里的 {@code bg_skeleton} 用的是 divider 实色，那是给新拟态的
     * 浅色页面配的冷灰；玻璃态的卡片面是暖白玻璃，冷灰叠上去会发脏。
     * 这里换成凹面玻璃色（浅色 4% 黑 / 深色 12% 白），与卡片里其它内容
     * 同一体系，同时保持骨架屏该有的低对比。</p>
     */
    private void applySkeletonGlass() {
        int fill = HyperTheme.glassTintConcave(this);
        int radius = (int) getResources().getDimension(R.dimen.radius_field);
        int[] ids = new int[] { R.id.skelCover, R.id.skelLine1, R.id.skelLine2 };
        for (int id : ids) {
            View v = findViewById(id);
            if (v == null) continue;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(radius);
            g.setColor(fill);
            v.setBackground(g);
        }
    }

    /**
     * 把一块药丸刷成主色系：15% 透明度的主色底 + 主色文字。
     *
     * <p>底色必须留透明度：全不透明的主色底配主色文字会糊成一片，
     * 药丸是"标签"而不是"按钮"，不需要那么强的对比。</p>
     *
     * <p>文字色不能直接用主色：底色是主色叠在页面上的混合结果，
     * 对它的对比度要按那个混合色算。这里用 {@link HyperTheme#readableOn}
     * 现算 —— 浅色页面上樱花粉底 + 樱花粉字原本只有 2.39:1。</p>
     */
    private void applyPill(TextView tv, int primary) {
        if (tv == null) return;
        // 15% 的主色盖在页面底色上，就是这个药丸实际呈现的底
        int face = blendOver(HyperTheme.pageFace(this), primary, 0.15f);
        if (HyperTheme.isGlass(this)) {
            // 玻璃态：走引擎的「主色面」通路（主色打底 + 玻璃描边），
            // 与设置面板的色卡同一个画法 —— 药丸要像"玻璃上的一块色片"，
            // 而不是叠在旧页面底色上的一层半透明色。
            tv.setBackground(NeumorphicSurface.create(
                    this, NeumorphicDrawable.CONVEX, 999f, 0f, 0, 0)
                    .accent(face, HyperTheme.neumLight(this), HyperTheme.neumDark(this)));
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(999);
            g.setColor((primary & 0x00FFFFFF) | 0x26000000);
            tv.setBackground(g);
        }
        tv.setTextColor(HyperTheme.readableOn(primary, face));
    }

    /** {@code over} 按 alpha 叠在 {@code base} 上得到的不透明结果色。 */
    private static int blendOver(int base, int over, float alpha) {
        return android.graphics.Color.rgb(
                Math.round(android.graphics.Color.red(over) * alpha
                        + android.graphics.Color.red(base) * (1 - alpha)),
                Math.round(android.graphics.Color.green(over) * alpha
                        + android.graphics.Color.green(base) * (1 - alpha)),
                Math.round(android.graphics.Color.blue(over) * alpha
                        + android.graphics.Color.blue(base) * (1 - alpha)));
    }

    private void applyAccent(View v, int color) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(color);
        }
    }

    /**
     * 输入框的光标与选中高亮。
     *
     * <p>两块都要按主题色走。它们是"没自己画、但换主题色后会露馅"的系统表面：
     * 光标默认吃 {@code colorAccent}，而那是编译期写死的樱花粉快照；
     * 选中高亮默认是一层更浅的系统蓝。换了主题色，这两处就是最明显的残留。</p>
     *
     * <p>光标以前改不了 —— 平台只提供 {@code android:textCursorDrawable}，
     * 要在 XML 里指定一个 tint 好的 drawable，而主题色是运行期的。
     * 但 {@code setTextCursorDrawable} 从 API 29 起可以直接给 Drawable，
     * 就不必再受这个限制了（minSdk 26，低版本跳过即可）。</p>
     */
    private void applyCaret(EditText et, int color) {
        if (et == null) return;
        // 40% 透明度：高亮是背景，全不透明会盖住底下选中的字
        et.setHighlightColor((color & 0x00FFFFFF) | 0x66000000);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 1.5dp 宽的光标条，跟正文里的一根竖笔差不多粗细
            float w = 1.5f * getResources().getDisplayMetrics().density;
            GradientDrawable caret = new GradientDrawable();
            caret.setShape(GradientDrawable.RECTANGLE);
            caret.setColor(color);
            caret.setSize(Math.max(1, Math.round(w)), Math.round(24 * getResources()
                    .getDisplayMetrics().density));
            et.setTextCursorDrawable(caret);
        }
    }

    private void bindViews() {
        topBar = findViewById(R.id.topBar);
        scroll = findViewById(R.id.scroll);

        inputUrl = findViewById(R.id.inputUrl);
        btnParse = findViewById(R.id.btnParse);
        btnPaste = findViewById(R.id.btnPaste);
        btnSettings = findViewById(R.id.btnSettings);
        btnDownloads = findViewById(R.id.btnDownloads);

        emptyBox = findViewById(R.id.emptyBox);
        loadingBox = findViewById(R.id.loadingBox);
        tvLoadingBody = findViewById(R.id.tvLoadingBody);
        errorBox = findViewById(R.id.errorBox);
        resultBox = findViewById(R.id.resultBox);

        errorTitle = findViewById(R.id.errorTitle);
        errorFix = findViewById(R.id.errorFix);
        errorDetail = findViewById(R.id.errorDetail);
        btnRetry = findViewById(R.id.btnRetry);

        tvTitle = findViewById(R.id.tvTitle);
        tvMeta = findViewById(R.id.tvMeta);
        qualityHintBox = findViewById(R.id.qualityHintBox);
        tvQualityHint = findViewById(R.id.tvQualityHint);
        btnQualityHintAction = findViewById(R.id.btnQualityHintAction);
        tvPartsLabel = findViewById(R.id.tvPartsLabel);
        listParts = findViewById(R.id.listParts);
        listDownloads = findViewById(R.id.listDownloads);
        tvSavedTo = findViewById(R.id.tvSavedTo);
    }

    /**
     * Edge-to-edge：系统栏是透明的，内容必须自己避开。
     *
     * <p>做法是抬高顶栏而不是给它加内边距 —— 否则标题会压到状态栏上。</p>
     */
    private void applyWindowInsets() {
        final int baseBarHeight =
                getResources().getDimensionPixelSize(R.dimen.top_app_bar_height);
        final int baseBottom =
                getResources().getDimensionPixelSize(R.dimen.content_gap);

        // 顶栏自己的横向与底部内边距是布局里定的，这里只接管顶部，
        // 所以先把原值记下来 —— 每次 inset 回调都基于它们重算，
        // 而不是在已经加过 padding 的值上再叠加（那样转屏或分屏会越加越多）。
        final int barPadLeft = topBar.getPaddingLeft();
        final int barPadRight = topBar.getPaddingRight();
        final int barPadBottom = topBar.getPaddingBottom();

        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();

            ViewGroup.LayoutParams barLp = topBar.getLayoutParams();
            barLp.height = baseBarHeight + top;
            topBar.setLayoutParams(barLp);

            // 关键：内容靠 paddingTop 让开状态栏，而不是随着被撑高的顶栏一起居中。
            //
            // 只抬高高度是不够的 —— 顶栏的 gravity 是 center_vertical，高度变成
            // 「64dp + 状态栏高度」之后，标题会在这整条高带的**中点**落位，
            // 也就是比正确位置高出半个状态栏，同时顶部留出一条空白死带。
            // 实机表现就是「标题贴着状态栏、上面还空一块」，看着像两截。
            //
            // 加 paddingTop 之后，标题与齿轮被压到状态栏下方那条**恰好 64dp**
            // 的带子里居中；顶栏背景仍然从屏幕最顶端开始画，所以状态栏区域
            // 是应用自己的底色 —— 沉浸式，且状态栏不额外占用布局空间。
            topBar.setPadding(barPadLeft, top, barPadRight, barPadBottom);

            // 没有悬浮按钮了，内容要自己避开手势区，否则最后一行贴在导航条上
            scroll.setPadding(left, scroll.getPaddingTop(), right, baseBottom + bottom);
            return insets;
        });
    }

    private void wireActions() {
        btnParse.setOnClickListener(v -> doParse());
        btnRetry.setOnClickListener(v -> doParse());
        btnSettings.setOnClickListener(v -> showSettings());
        btnQualityHintAction.setOnClickListener(v -> showSettings());
        btnDownloads.setOnClickListener(v ->
                startActivity(new Intent(this, DownloadsActivity.class)));

        // 尾部按钮一钮两用：空的时候是「粘贴」，有内容时是「清空」
        btnPaste.setOnClickListener(v -> {
            if (inputUrl.getText().toString().trim().isEmpty()) {
                pasteFromClipboard();
            } else {
                inputUrl.setText("");
                inputUrl.requestFocus();
            }
        });
        inputUrl.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                syncInputTrailingButton();
            }
        });

        // 输入框失去焦点时也要收键盘，否则点别处键盘还挂着
        inputUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard();
            }
        });

        // 键盘上的「搜索」键等同于点解析
        inputUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO) {
                doParse();
                return true;
            }
            return false;
        });

        preview.setListener(new PreviewController.Listener() {
            @Override
            public void onNeedSource() {
                fetchPreviewSource();
            }

            @Override
            public void onError(String message) {
                // 预览失败不影响下载，所以只记日志，不弹提示打扰用户
                Log.w(TAG, "预览播放失败: " + message);
            }
        });
    }

    /**
     * 懒加载预览地址。
     *
     * <p>解析稿件时不请求：用户可能只想下载，为了看一眼封面白跑一次接口不值得。
     * 等他真的按下播放键再取。</p>
     *
     * <p>预览走 {@code fnval=1} 的渐进式 MP4 —— 下载用的 DASH 是分离的音视频轨，
     * 系统播放器播出来没声音，也拖不动。</p>
     */
    private void fetchPreviewSource() {
        final Model.Video v = current;
        final Model.PlayInfo info = currentProbe;
        Log.i(TAG, "取预览地址：video=" + (v != null)
                + " pages=" + (v == null ? -1 : v.pages.size())
                + " youtube=" + (info != null && info.fromYouTube)
                + " preview=" + (info == null || info.preview == null
                        ? "无" : info.preview.url.length() + "字符"));

        if (v == null || v.pages.isEmpty()) {
            preview.sourceFailed();
            return;
        }

        // YouTube 的预览地址在解析阶段就一起拿到了（yt-dlp 顺带返回的
        // 渐进式 MP4，音视频已经合体，可以直接交给 MediaPlayer）。
        // 不必也不该再跑一次 Python。
        if (info != null && info.fromYouTube) {
            stopPreviewRelay();
            if (info.preview != null && !info.preview.url.isEmpty()) {
                // MediaPlayer 是 native 的 NuPlayer，不认应用层代理。
                // 有代理时必须在本地起一跳转发，否则它会直连 googlevideo
                // 然后一直卡在 prepareAsync（真机实测）。
                LocalRelay relay = LocalRelay.start(
                        info.preview.url, prefs.youtubeProxy());
                if (relay != null) {
                    previewRelay = relay;
                    preview.sourceReady(relay.url(), info.preview.durationMs);
                } else {
                    preview.sourceReady(info.preview.url, info.preview.durationMs);
                }
            } else {
                // 极少数视频没有渐进式格式。分离轨给 MediaPlayer 播出来是无声的，
                // 所以这里选择明确失败，而不是播一个没声音的画面。
                Log.w(TAG, "该 YouTube 视频没有渐进式格式，无法预览");
                preview.sourceFailed();
            }
            return;
        }
        stopPreviewRelay();

        final Model.Part part = v.pages.get(clampPartIndex());
        bg.execute(() -> {
            try {
                Model.PreviewSource src = BiliApi.previewSource(
                        v.bvid, part.cid, PREVIEW_QN, prefs.cookie());
                ui.post(() -> preview.sourceReady(src.url, src.durationMs));
            } catch (Exception e) {
                Log.w(TAG, "预览地址获取失败", e);
                ui.post(preview::sourceFailed);
            }
        });
    }

    private int clampPartIndex() {
        if (current == null || current.pages.isEmpty()) {
            return 0;
        }
        if (selectedPart < 0 || selectedPart >= current.pages.size()) {
            selectedPart = 0;
        }
        return selectedPart;
    }

    /** 收起软键盘。结果区在输入框下方，键盘留着会把结果顶出屏幕。 */
    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
        }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(inputUrl.getWindowToken(), 0);
        }
        // 有的 ROM 上 hideSoftInputFromWindow 会失效（聚焦视图刚变过），
        // 换一个 token 再试一次
        if (imm != null && focused != null && focused != inputUrl) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    private void setupPartsList() {
        adapter = new PartAdapter();
        listParts.setAdapter(adapter);
        listParts.setOnItemClickListener((parent, view, position, id) -> {
            int previous = selectedPart;
            selectedPart = position;
            adapter.notifyDataSetChanged();
            // 分 P 换了，之前那个分 P 的预览和它就不对应了
            if (previous != position) {
                preview.reset();
            }
        });
    }

    // ==================================================================
    // 四个互斥状态
    // ==================================================================

    private void hideAllStates() {
        emptyBox.setVisibility(View.GONE);
        loadingBox.setVisibility(View.GONE);
        errorBox.setVisibility(View.GONE);
        resultBox.setVisibility(View.GONE);
    }

    private void renderEmpty() {
        hideAllStates();
        emptyBox.setVisibility(View.VISIBLE);
    }

    private void renderLoading(boolean youtube) {
        hideAllStates();
        // 开始解析就停掉上一个视频的预览：它马上就和新结果对不上了
        if (preview != null) {
            preview.reset();
        }
        // 加载文案必须分来源。写死「正在向哔哩哔哩请求稿件信息与可用画质」，
        // 解析 YouTube 链接时就会在屏幕上明说自己在问 B 站 —— 这和下载报错
        // 串成 B 站话术是同一类错误，只是发生在加载阶段。
        tvLoadingBody.setText(youtube
                ? R.string.loading_body_youtube : R.string.loading_body);
        loadingBox.setVisibility(View.VISIBLE);
        btnParse.setEnabled(false);
        btnParse.setText(R.string.action_parsing);
    }

    /**
     * 错误态。文案分三部分：说清问题、给出恢复方式、必要时附上原始信息。
     *
     * <p>把原始错误也露出来，用户反馈问题时不必再去翻日志。</p>
     */
    private void renderError(String title, String fix, String detail) {
        hideAllStates();
        errorBox.setVisibility(View.VISIBLE);

        errorTitle.setText(title);
        errorFix.setText(fix);
        if (detail == null || detail.trim().isEmpty() || detail.equals(title)) {
            errorDetail.setVisibility(View.GONE);
        } else {
            errorDetail.setVisibility(View.VISIBLE);
            errorDetail.setText(detail);
        }
    }

    private void renderResult() {
        hideAllStates();
        resultBox.setVisibility(View.VISIBLE);
        btnParse.setEnabled(true);
        btnParse.setText(R.string.action_parse);

        // 结果区的错峰入场动画。
        //
        // 必须 post 到下一帧，不能直接调：resultBox 这一帧刚从 GONE 变成 VISIBLE，
        // 它的子树还没测量，此时读到的尺寸和位置都是旧的，动画会从错误的地方出发。
        //
        // 这里动画的是 resultBox 的直接子 View，而不是写死一组 id：
        // 结果区的每一块（预览、标题、元信息、分 P、下载档位）的可见性是随
        // 解析结果变的，用 id 列表就会给一个已经 GONE 的控件做动画 —— 白做，还占延迟。
        resultBox.post(() -> {
            if (!(resultBox instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) resultBox;
            java.util.ArrayList<View> shown = new java.util.ArrayList<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() == View.VISIBLE) {
                    shown.add(c);
                }
            }
            StaggerEnter.play(shown.toArray(new View[0]));
        });
    }

    // ==================================================================
    // 分享 / 剪贴板
    // ==================================================================

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        String text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            text = intent.getData().toString();
        }
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        // 从 B 站 App「分享」过来的同样是整段口令，只取链接
        String raw = text.trim();
        String link = BiliApi.extractLink(raw);
        inputUrl.setText(link.isEmpty() ? raw : link);
        inputUrl.setSelection(inputUrl.getText().length());
        btnParse.post(this::doParse);
    }

    /**
     * 同步输入框尾部按钮的图标与无障碍描述。
     *
     * <p>有内容时切成「清空」—— 换一个视频下载时最常见的动作就是清掉旧链接。
     * 长按输入框仍然能粘贴，所以并不是把粘贴入口拿走了。</p>
     */
    private void syncInputTrailingButton() {
        boolean empty = inputUrl.getText().toString().trim().isEmpty();
        btnPaste.setImageResource(empty ? R.drawable.ic_paste : R.drawable.ic_close);
        btnPaste.setContentDescription(getString(
                empty ? R.string.cd_paste : R.string.cd_clear));
    }

    private void pasteFromClipboard() {
        CharSequence text = null;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            }
        } catch (Throwable ignored) {
            // 剪贴板在某些 ROM 上会抛权限异常，按「读不到」处理即可
        }
        if (text == null || text.toString().trim().isEmpty()) {
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_clipboard_empty));
            return;
        }

        // 从 B 站 App 复制的「口令」是一整段中文加一个链接，形如：
        //   【【官方MV】Never Gonna Give You Up- Rick Astley-哔哩哔哩】 https://b23.tv/AbCdEf
        // 把整段填进输入框，用户看不出到底要解析什么，也没法确认粘贴对不对。
        // 只留链接。挑不出链接（用户复制的是纯 BV 号或别的什么）时原样填入。
        String raw = text.toString().trim();
        String link = BiliApi.extractLink(raw);
        inputUrl.setText(link.isEmpty() ? raw : link);
        inputUrl.setSelection(inputUrl.getText().length());
        Snackbar.show(findViewById(R.id.root), getString(R.string.snack_copied));
    }

    // ==================================================================
    // 解析
    // ==================================================================

    private void doParse() {
        // 先收键盘。结果区就在输入框正下方，键盘不收起来会把封面和下载行
        // 整个顶到屏幕外 —— 真机上看起来就像"点了解析没反应"。
        hideKeyboard();

        String raw = inputUrl.getText().toString().trim();
        if (raw.isEmpty()) {
            renderError(getString(R.string.err_empty_input),
                    getString(R.string.err_empty_input_fix), null);
            return;
        }

        // 来源要在 renderLoading 之前算出来：加载文案得按来源选
        final boolean youtube = YouTubeEngine.isYouTubeUrl(raw);
        renderLoading(youtube);

        bg.execute(() -> {
            try {
                if (youtube) {
                    YouTubeEngine.Result r = YouTubeEngine.resolve(this, raw, prefs.youtubeProxy());
                    ui.post(() -> onParsed(r.video, r.play));
                } else {
                    String cookie = prefs.cookie();
                    Model.Video v = BiliApi.view(raw, cookie);
                    // 用 127 探测一次，拿到该稿件全部可用画质
                    Model.PlayInfo probe = BiliApi.playurl(
                            v.bvid, v.pages.get(0).cid, 127, cookie);
                    ui.post(() -> onParsed(v, probe));
                }
            } catch (Exception e) {
                ui.post(() -> onParseFailed(e, youtube));
            }
        });
    }

    private void onParsed(Model.Video v, Model.PlayInfo probe) {
        current = v;
        currentProbe = probe;
        selectedPart = 0;
        downloading = false;
        activeRow = null;

        renderResult();

        tvTitle.setText(v.title);
        if (probe != null && probe.fromYouTube) {
            // YouTube 没有分 P 的概念，照搬 B 站的「N 个分P」会显示成一句废话
            tvMeta.setText(getString(R.string.meta_format_youtube,
                    v.owner.isEmpty() ? getString(R.string.youtube_unknown_channel) : v.owner,
                    fmtDuration(v.duration)));
        } else {
            tvMeta.setText(getString(R.string.meta_format,
                    v.owner, v.pages.size(), fmtDuration(v.duration)));
        }

        // 新稿件：预览退回封面态，否则会留着上一个视频的画面
        preview.reset();
        stopPreviewRelay();
        loadCover(v.cover);
        adapter.notifyDataSetChanged();

        buildDownloadRows(probe);
        tvSavedTo.setVisibility(View.GONE);

        // 回到顶部。否则上一次留下的滚动位置会让用户以为没解析出来
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    /**
     * 连不上时的标题。两条来源共用同一个异常分支（{@link UnknownHostException}
     * 和通用 {@code IOException}），但文案不能说错对象 —— 解析 YouTube 时
     * 弹出「无法连接到哔哩哔哩」就是第 4 条报告那类问题。
     */
    private static int networkTitle(boolean youtube) {
        return youtube ? R.string.err_network_youtube : R.string.err_network;
    }

    private void onParseFailed(Exception e, boolean youtube) {
        String raw = describeRaw(e);
        String title;
        String fix;

        if (e instanceof UnknownHostException) {
            title = getString(networkTitle(youtube));
            fix = getString(R.string.err_network_fix);
        } else if (e instanceof SocketTimeoutException) {
            title = getString(R.string.err_timeout);
            fix = getString(R.string.err_timeout_fix);
        } else if (e instanceof BiliApi.ApiException) {
            // 按错误码分派。这里刻意不匹配异常消息里的中文子串 ——
            // 那样只要改一个字的文案，错误映射就会静默失效
            int code = ((BiliApi.ApiException) e).code;
            switch (code) {
                case -101:
                    title = getString(R.string.err_need_login);
                    fix = getString(R.string.err_need_login_fix);
                    break;
                case -352:
                case -799:
                    title = getString(R.string.err_forbidden);
                    fix = getString(R.string.err_forbidden_fix);
                    break;
                case -403:
                    title = getString(R.string.err_need_vip);
                    fix = getString(R.string.err_need_vip_fix);
                    break;
                case -404:
                case 62002:
                    title = getString(R.string.err_not_found);
                    fix = getString(R.string.err_not_found_fix);
                    break;
                case 62004:
                    title = getString(R.string.err_under_review);
                    fix = getString(R.string.err_under_review_fix);
                    break;
                case BiliApi.CODE_BAD_INPUT:
                    title = getString(R.string.err_bad_input);
                    fix = getString(R.string.err_bad_input_fix);
                    break;
                case BiliApi.CODE_NO_CID:
                case BiliApi.CODE_NO_DASH:
                    title = getString(R.string.err_no_stream);
                    fix = getString(R.string.err_no_stream_fix);
                    break;
                default:
                    title = getString(R.string.err_generic);
                    fix = getString(R.string.err_generic_fix);
                    break;
            }
        } else if (e instanceof java.io.IOException) {
            title = getString(networkTitle(youtube));
            fix = getString(R.string.err_network_fix);
        } else {
            title = getString(R.string.err_generic);
            fix = getString(R.string.err_generic_fix);
        }

        renderError(title, fix, raw);
        btnParse.setEnabled(true);
        btnParse.setText(R.string.action_parse);
    }

    // ==================================================================
    // 画质芯片
    // ==================================================================

    /**
     * 按可用画质生成下载行，末尾固定补一行「仅音频」。
     *
     * <p>上一版这里是画质芯片 + 一个「仅音频」开关 + 一个 FAB 的组合：
     * 选画质、切开关、再按 FAB，三步才能开始下载。现在一步 ——
     * 想下哪个档就点哪一行。</p>
     */
    private void buildDownloadRows(Model.PlayInfo probe) {
        listDownloads.removeAllViews();
        rows.clear();
        selectedQn = Prefs.DEFAULT_QN;

        boolean youtube = probe != null && probe.fromYouTube;

        // 画质去重、从高到低
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        if (probe != null && probe.videos != null) {
            for (Model.Stream s : probe.videos) {
                seen.add(s.quality);
            }
        }
        List<Integer> qns = new ArrayList<>(seen);
        Collections.sort(qns, Collections.reverseOrder());

        if (qns.isEmpty()) {
            Snackbar.show(findViewById(R.id.root),
                    getString(R.string.quality_not_available), null, null, R.drawable.ic_error);
            qualityHintBox.setVisibility(View.GONE);
            return;
        }

        int best = qns.get(0);
        int target = prefs.preferQn();
        selectedQn = qns.contains(target) ? target : best;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Integer qn : qns) {
            // YouTube 的 qn 就是画面高度，videoByQuality 按 quality 匹配，能直接命中
            Model.Stream s = probe.videoByQuality(qn, prefs.preferAvc());
            String meta;
            if (s == null || s.width <= 0) {
                meta = "";
            } else if (youtube) {
                // YouTube 的档位体积差异极大（144P 是 2 MB，2160P 是 342 MB），
                // 把体积摆出来比只写分辨率有用得多
                meta = getString(R.string.row_meta_youtube,
                        s.width, s.height, fmtSize(s.size), codecName(s.codecId));
            } else {
                meta = getString(R.string.row_meta_video, s.width, s.height, codecName(s.codecId));
            }
            rows.add(addDownloadRow(inflater, qn, false, s, qnLabel(probe, qn), meta,
                    R.drawable.ic_download));
        }

        Model.Stream audio = probe.audios.isEmpty() ? null : probe.audios.get(0);
        String audioDesc = getString(R.string.row_audio_desc);
        if (youtube && audio != null && audio.size > 0) {
            audioDesc = getString(R.string.row_audio_desc_size, fmtSize(audio.size));
        }
        rows.add(addDownloadRow(inflater, selectedQn, true, audio,
                getString(R.string.row_audio), audioDesc, R.drawable.ic_music));

        setRowsEnabled(true);

        // 「登录解锁画质」只对 B 站成立。YouTube 的档位限制来自服务端，
        // 与登录状态无关，在那边显示这句会纯粹误导用户。
        String hint = null;
        if (!youtube) {
            // 优先说大会员档位：稿件宣称有 4K / 真彩，当前账号却拿不到。
            // 这比笼统的「登录能解锁」具体得多 —— 用户一眼就知道差在哪、
            // 以及要升到哪个等级的账号。缺了这句，4K 会被安静地藏起来，
            // 看的人只会以为这个应用不支持高画质。
            String locked = lockedQualityLabel(probe);
            if (!locked.isEmpty()) {
                hint = getString(R.string.hint_vip_for_quality, locked, qnLabel(probe, best));
            } else if (!prefs.hasLogin() && best < QN_1080P && best > 0) {
                hint = getString(R.string.hint_login_for_quality, qnLabel(probe, best));
            }
        }
        if (hint != null) {
            tvQualityHint.setText(hint);
            qualityHintBox.setVisibility(View.VISIBLE);
        } else {
            qualityHintBox.setVisibility(View.GONE);
        }
    }

    /**
     * 把「稿件支持但当前账号拿不到」的档位拼成一句枚举，如「4K 超高清、HDR 真彩」。
     *
     * <p>只列比实际拿到的最高档**更高**的。{@code lockedQualities} 里也会混进比它低的
     * （例如已经拿到 80，列表里还有个需要更高权限的 112 变体），把低的也念出来，
     * 用户会以为「连现在这一档我都没有」。</p>
     *
     * @return 没有任何更高档位时返回空串
     */
    private static String lockedQualityLabel(Model.PlayInfo probe) {
        if (probe == null || probe.lockedQualities.isEmpty()) {
            return "";
        }
        int best = 0;
        for (Model.Stream s : probe.videos) {
            if (s.quality > best) {
                best = s.quality;
            }
        }
        java.util.List<Integer> keys = new java.util.ArrayList<>(probe.lockedQualities.keySet());
        // 从高到低，读起来才是「4K 超高清、HDR 真彩」而不是乱序
        java.util.Collections.sort(keys, java.util.Collections.reverseOrder());
        StringBuilder sb = new StringBuilder();
        for (Integer q : keys) {
            if (q == null || q <= best) {
                continue;
            }
            String name = probe.lockedQualities.get(q);
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** 人类可读的体积。0 表示未知，此时返回空串而不是 "0 B"。 */
    static String fmtSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        double mb = bytes / 1024.0 / 1024.0;
        if (mb < 1024.0) {
            return String.format(java.util.Locale.US, "%.0f MB", mb);
        }
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    private DownloadRow addDownloadRow(LayoutInflater inflater, int qn, boolean audioOnly,
                                       Model.Stream stream, String label, String meta,
                                       int iconRes) {
        View root = inflater.inflate(R.layout.item_download, listDownloads, false);
        View main = root.findViewById(R.id.rowMain);
        TextView tvLabel = root.findViewById(R.id.tvRowLabel);
        TextView tvMeta = root.findViewById(R.id.tvRowMeta);
        ImageView icon = root.findViewById(R.id.ivRowIcon);
        TextView percent = root.findViewById(R.id.tvRowPercent);
        ProgressBar bar = root.findViewById(R.id.pbRow);

        // 进度条用带圆角的形状，再让 tint 决定颜色 ——
        // 布局里只设了 tint，平台默认的进度形状是直角的，
        // 4dp 高的直角条在这里会显得毛糙。
        bar.setProgressDrawable(getResources().getDrawable(
                R.drawable.progress_download, getTheme()));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                HyperTheme.primary(this)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                HyperTheme.progressTrack(this)));

        tvLabel.setText(label);
        tvMeta.setText(meta);
        // 百分比是落在页面底色上的文字，走 primaryText（4.5:1）。
        // 它在 XML 里写的是 @color/scheme_0_primary 兜底，
        // 而这里不刷的话会永远停在樱花粉上 —— 换成深海蓝之后
        // 这一行就是粉字，和旁边蓝色的进度条不是一套色。
        percent.setTextColor(HyperTheme.primaryText(this));
        // 音频行用音符图标，和视频行区分开 —— 不用读文字也能一眼分辨
        icon.setImageResource(iconRes);

        DownloadRow row = new DownloadRow(qn, audioOnly, stream, main, tvLabel, tvMeta,
                icon, percent, bar, meta);
        main.setContentDescription(getString(R.string.cd_download_quality, label));
        main.setOnClickListener(v -> startDownload(row));

        listDownloads.addView(root);
        return row;
    }

    private void setRowsEnabled(boolean enabled) {
        for (DownloadRow r : rows) {
            r.main.setEnabled(enabled);
        }
    }

    private String codecName(int codecId) {
        switch (codecId) {
            case 7:  return getString(R.string.codec_avc);
            case 12: return getString(R.string.codec_hevc);
            case 13: return getString(R.string.codec_av1);
            case Model.CODEC_VP9: return getString(R.string.codec_vp9);
            default: return getString(R.string.codec_unknown);
        }
    }

    /**
     * 造一个新拟态芯片（设置面板的选项）。
     *
     * <p>外观全部来自 {@code @style/Widget.Chip} 与 {@code view_chip.xml}。
     * 主界面的画质已经改成下载行，这里现在只服务设置面板的选项。</p>
     */
    private TextView makeChip(FlowLayout parent, String label) {
        TextView chip = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.view_chip, parent, false);
        chip.setText(label);
        // 新拟态既没有涟漪也没有状态列表，点击反馈改由 HyperosClick 提供
        // （缩放 + 浮雕形变 + 触觉）。它挂在 touch 上，
        // 所以外面照常 setOnClickListener 就能拿到点击行为。
        HyperosClick.bindVisualOnly(chip);
        return chip;
    }

    /**
     * 把一组芯片刷成"只有一个选中"的样子。
     *
     * <p>选中 = 凸起 3dp + 主色文字；未选中 = 凹入 1.5dp + 次要色文字。
     * 凹凸同时变化是有意的：只改颜色的话，在"卡片与页面同色"的平面里
     * 选中项几乎看不出来 —— 新拟态表达状态靠形变，不靠色差。</p>
     *
     * <p>两个引擎都走同一套凹凸：玻璃态的 {@code drawGlass} 同样画方向光
     * （凸面顶部亮边、凹面顶部暗影 + 底部亮边），所以形变在玻璃态下照样成立。
     * 这里曾经对玻璃态特判、把整行都刷成凸面 —— 那等于把唯一的状态通道关掉，
     * 玻璃态下五张主题色卡看起来一模一样，分不出选的是哪一张。</p>
     */
    private void applyChipStates(FlowLayout row, int selectedIndex) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView chip = (TextView) v;

            boolean on = (i == selectedIndex);
            // primaryText 而不是 primary：选中的芯片文字直接落在页面底色上，
            // 五套主题色里只有深海蓝天生够 4.5:1，其余四套都要推深才合格。
            chip.setTextColor(on ? HyperTheme.primaryText(this) : HyperTheme.textSecondary(this));
            chip.setTypeface(null, on ? android.graphics.Typeface.BOLD
                                      : android.graphics.Typeface.NORMAL);

            if (on) {
                NeumorphicSurface.convex(chip, 14, 3);
            } else {
                NeumorphicSurface.concave(chip, 14, 1.5f);
            }
        }
    }

    /** 芯片被选中时的回调。 */
    public interface ChipPick {
        void onPick(int index);
    }

    /**
     * 把主题色那一行刷成色卡：每张的底色就是它代表的那套主题色。
     *
     * <p>三个引擎相关的坑都在这里收口：</p>
     *
     * <ol>
     *   <li><b>底色必须走 {@code NeumorphicDrawable.accent()}</b>。玻璃态下
     *       {@code colors()} 设的底根本不参与绘制（玻璃面画的是半透明白雾），
     *       五张色卡会全变成白的 —— 这就是「玻璃态 + 深海蓝时按钮变白」那个 bug。</li>
     *   <li><b>字色要拿实际呈现的面去算</b>，不是原色。玻璃态下真正的面是
     *       "主色 + 36% 白雾"的混合（深海蓝 {@code #1652A8} → {@code #6A90C7}），
     *       拿原色算会得出"配白字"，落在混合色上只有 3.4:1。</li>
     *   <li><b>不碰凹凸</b>。颜色的形状由 {@link #applyChipStates} 决定
     *       （选中凸起、未选中凹入），这里只补色。要是这里也设一遍形状，
     *       色卡就会比同一行其它芯片多一种形态，看着像两个不同的控件。</li>
     * </ol>
     *
     * <p>之所以要能被重复调用：{@code applyChipStates} 每次点芯片都会重设背景，
     * 把主色一起冲掉，所以点击回调里还得再补一次。</p>
     */
    private void paintSwatches(FlowLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView chip = (TextView) v;
            int c = HyperTheme.schemePrimary(this, i);

            android.graphics.drawable.Drawable bg = chip.getBackground();
            if (!(bg instanceof NeumorphicDrawable)) continue;
            NeumorphicDrawable nd = (NeumorphicDrawable) bg;
            nd.accent(c, HyperTheme.neumLight(this), HyperTheme.neumDark(this));
            nd.invalidateSelf();

            chip.setTextColor(HyperTheme.contrastOn(this, nd.faceColor()));
        }
    }

    /**
     * 芯片行的后置修饰。在 {@link #applyChipStates} 之后跑，
     * 用来叠一层 applyChipStates 不认识的外观 —— 目前是主题色色卡的主色填充。
     *
     * <p>为什么需要它：applyChipStates 会重设整行芯片的背景（换凹凸），
     * 那会把色卡上的主色一起冲掉。所以每次重绘形态之后都得再铺一次主色，
     * 否则"点一下已经选中的色卡"就会把那张卡变回没有颜色的白芯片
     * —— 因为点已选中项不会触发 recreate()，界面不会重建。</p>
     */
    public interface RowDecorator {
        void decorate(FlowLayout row);
    }

    /**
     * 在 {@code anchor} 之后插入一行新的芯片选项（含小标题）。
     *
     * <p>程序化创建而不是写进 XML：外观选项的组数会随设计系统演进变化
     * （主题明暗 / 视觉引擎 / 主题色），每加一组都要同时改布局和 Java，
     * 很容易漏。收成一处之后，加一组只需要一次调用。</p>
     *
     * <p>连续调用时把上一次返回的行当作 anchor，就会自然按顺序堆叠。</p>
     *
     * @return 新建的芯片行
     */
    private FlowLayout addChipRow(FlowLayout anchor, String title, String[] labels,
                                  int selected, final ChipPick onPick) {
        return addChipRow(anchor, title, labels, selected, onPick, null);
    }

    /**
     * 同上，另外挂一个后置修饰器，在每次刷新芯片形态之后再叠一层外观。
     *
     * @param decorate 可为 null。见 {@link RowDecorator} 关于"为什么需要它"
     */
    private FlowLayout addChipRow(FlowLayout anchor, String title, String[] labels,
                                  int selected, final ChipPick onPick,
                                  final RowDecorator decorate) {
        ViewGroup parent = (ViewGroup) anchor.getParent();
        int at = parent.indexOfChild(anchor) + 1;

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.chip_group_title_text));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(HyperTheme.textSecondary(this));
        tv.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = getResources().getDimensionPixelSize(R.dimen.space_lg);
        tp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_xs);
        tv.setLayoutParams(tp);

        final FlowLayout row = new FlowLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView c = makeChip(row, labels[i]);
            c.setOnClickListener(v -> {
                HyperosClick.haptic(v);
                applyChipStates(row, index);
                if (decorate != null) decorate.decorate(row);
                onPick.onPick(index);
            });
            row.addView(c);
        }

        parent.addView(tv, at);
        parent.addView(row, at + 1);

        applyChipStates(row, selected);
        if (decorate != null) decorate.decorate(row);
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 画质码 → 用户能看懂的名字。
     *
     * <p>优先用接口返回的 {@code accept_description}：哔哩哔哩偶尔会调整档位的叫法，
     * 以接口为准就不用跟着改代码。接口没列到这一档时才退回本地兜底表。</p>
     */
    private String qnLabel(Model.PlayInfo info, int qn) {
        if (info != null && info.qualities != null) {
            String fromApi = info.qualities.get(qn);
            if (fromApi != null && !fromApi.trim().isEmpty()) {
                return fromApi.trim();
            }
        }
        switch (qn) {
            case 127: return getString(R.string.qn_8k);
            case 126: return getString(R.string.qn_dolby_vision);
            case 125: return getString(R.string.qn_hdr);
            case 120: return getString(R.string.qn_4k);
            case 116: return getString(R.string.qn_1080p60);
            case 112: return getString(R.string.qn_1080p_plus);
            case 80:  return getString(R.string.qn_1080p);
            case 74:  return getString(R.string.qn_720p60);
            case 64:  return getString(R.string.qn_720p);
            case 32:  return getString(R.string.qn_480p);
            case 16:  return getString(R.string.qn_360p);
            case 6:   return getString(R.string.qn_240p);
            default:  return getString(R.string.qn_unknown, qn);
        }
    }

    // ==================================================================
    // 下载
    // ==================================================================

    /**
     * 从某一行开始下载。
     *
     * <p>进度直接长在这一行上（行尾换百分比、行下方出细进度条）。
     * 上一版把进度放在页面底部的卡片里，真机实测那个位置在视口之外 ——
     * 用户点完「开始下载」画面毫无变化，以为没反应。</p>
     */
    private void startDownload(DownloadRow row) {
        if (downloading) {
            return;
        }
        if (current == null || current.pages.isEmpty()) {
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_need_parse));
            return;
        }

        boolean youtube = currentProbe != null && currentProbe.fromYouTube;

        Model.Task task = new Model.Task();
        task.title = current.title;
        task.qn = row.qn;
        task.audioOnly = row.audioOnly;
        task.youtube = youtube;

        if (youtube) {
            Model.Part p = current.pages.get(0);
            task.pageUrl = inputUrl.getText().toString().trim();
            task.partTitle = p.title;
            task.videoWidth = row.stream != null ? row.stream.width : 0;
            task.videoHeight = row.stream != null ? row.stream.height : row.qn;
            task.proxy = prefs.youtubeProxy();
            // 记下解析是在哪一档客户端上成功的。下载时若被 CDN 拒了，
            // 服务端就从下一档继续换 —— 没有这个数字就只能瞎试。
            // 注意是 currentProbe（PlayInfo）而不是 current（Video）：
            // 档位信息属于这次解析的产物，不属于视频元数据。
            task.youtubeClientProfile = currentProbe.youtubeClientProfile;

            if (row.stream == null) {
                Snackbar.show(findViewById(R.id.root), getString(R.string.err_no_video_stream));
                return;
            }
            task.videoUrl = row.stream.url;
            task.videoSize = row.stream.size;
            // yt-dlp 为这条流声明的头必须原样带下去。丢掉它们就等于换了个
            // 客户端去请求同一条签名直链，googlevideo 会回 403。
            task.videoHeaders.putAll(row.stream.headers);

            // 容器跟着视频编码走，这不是偏好问题：MediaMuxer 不接受
            // VP9 进 MP4（实测抛 IllegalStateException），而 AAC 进不了 WebM。
            // YouTube 的 1440P / 2160P 只有 VP9 与 AV1，所以这两档必须走 WebM。
            task.webm = Model.needsWebm(row.stream.codecId);

            Model.Stream a = currentProbe.audioFor(task.webm);
            if (a == null && !row.audioOnly) {
                // 下到一个没有声音的视频比直接说清楚更糟 —— 用户多半
                // 不会意识到问题出在音轨格式上。这条分支实际很难走到：
                // yt-dlp 对任何视频都会给 Opus 音轨。
                Snackbar.show(findViewById(R.id.root), getString(
                        task.webm ? R.string.err_no_webm_audio : R.string.err_no_audio_stream));
                return;
            }
            if (a != null) {
                task.audioUrl = a.url;
                task.audioSize = a.size;
                task.audioHeaders.putAll(a.headers);
            }
        } else {
            Model.Part part = current.pages.get(clampPartIndex());
            task.bvid = current.bvid;
            task.cid = part.cid;
            task.partTitle = part.title;

            // 记住视频档位的选择；「仅音频」不代表画质偏好
            if (!row.audioOnly) {
                selectedQn = row.qn;
                prefs.setPreferQn(row.qn);
            }
        }

        downloading = true;
        activeRow = row;
        row.meta.setText(R.string.stage_preparing);
        row.percent.setText(getString(R.string.progress_percent, 0));
        row.bar.setProgress(0);
        row.setRunning(true);

        // 一次只跑一个任务；其余行先禁掉，避免并发下载互相抢带宽
        setRowsEnabled(false);
        tvSavedTo.setVisibility(View.GONE);

        // Model.Task 是给下载引擎用的扁平结构；DownloadTask 是**记录**，
        // 有 id、状态与持久化。两者内容重合，但职责不同 —— 前者跑完就丢，
        // 后者要留在下载管理页里，并且重启后还在。
        DownloadTask dt = new DownloadTask();
        dt.title = task.title;
        dt.partTitle = task.partTitle;
        dt.youtube = task.youtube;
        dt.qn = task.qn;
        dt.audioOnly = task.audioOnly;
        dt.bvid = task.bvid;
        dt.cid = task.cid;
        dt.pageUrl = task.pageUrl;
        dt.videoUrl = task.videoUrl;
        dt.videoSize = task.videoSize;
        dt.videoWidth = task.videoWidth;
        dt.videoHeight = task.videoHeight;
        dt.audioUrl = task.audioUrl;
        dt.audioSize = task.audioSize;
        dt.webm = task.webm;
        dt.proxy = task.proxy;
        dt.youtubeClientProfile = task.youtubeClientProfile;
        dt.videoHeaders.putAll(task.videoHeaders);
        dt.audioHeaders.putAll(task.audioHeaders);
        dt.subtitle = describeTask(dt, row);

        activeTask = dt;
        row.bind(dt);
        // 开始前先确认自选目录还能写。等到下载跑完才发现授权失效，
        // 用户白等一场。
        if (StorageDir.hasCustom(this)) {
            StorageDir.verifyCustom(this);
        }

        DownloadService.start(this, dt);
    }

    /** 给下载记录写一句副标题：来源 + 画质/编码，用于在管理页一眼分辨。 */
    private String describeTask(DownloadTask t, DownloadRow row) {
        StringBuilder sb = new StringBuilder();
        if (t.youtube) {
            sb.append(YouTubeEngine.describeHeight(t.videoHeight));
            if (t.webm) {
                sb.append(" · WebM");
            }
        } else {
            // 直接复用那一行上已经算好的画质名（接口描述优先，退回本地表），
            // 免得两处各写一份、早晚对不上
            sb.append(row.label.getText());
            if (!t.audioOnly && prefs.preferAvc()) {
                // 只在用户开了「优先 AVC」时标出来：那时拿到的一定是 H.264，
                // 是用户主动选的结果，值得确认一下
                sb.append(" · ").append(getString(R.string.codec_avc));
            }
        }
        return sb.toString();
    }

    // ---- TaskStore.Listener：记录增删改 ----

    /**
     * 下载记录变了（新增 / 删除 / 状态改动）。
     *
     * <p>首页必须听这个，否则「在管理页把一条没下完的任务删掉」这件事
     * 首页完全不知道：那条任务已经从记录里消失，没有任何 service 回调
     * 会再提到它，而首页那一行还停在被删之前的百分比、图标也还是隐藏的。
     * 用户回到首页看到的是一个卡死的下载进度，点它却什么都不会发生。</p>
     */
    @Override
    public void onTasksChanged() {
        ui.post(this::syncRowsWithStore);
    }

    /**
     * 把首页的下载行对齐到记录的真实状态。
     *
     * <p>复位条件写成「这条记录没了，或者它已经不在跑」。
     * 不能只判「记录没了」—— 取消 / 暂停一条**排队中**的任务时，
     * service 走的是「直接改状态」那条路，只发记录回调、
     * **不发** onTaskFinished（没有线程需要等它结束）。
     * 只判记录是否存在的话，那种情况照样会卡住。</p>
     *
     * <p>反过来，还在跑的（运行中 / 排队中）一律不碰：那是首页自己要
     * 显示进度的那一条。</p>
     */
    private void syncRowsWithStore() {
        boolean reset = false;
        for (DownloadRow r : rows) {
            if (r.boundId.isEmpty()) {
                continue;
            }
            DownloadTask t = TaskStore.get().byId(r.boundId);
            if (t == null || !t.isActive()) {
                // 记录被删掉，或者任务已经不在跑了（取消 / 暂停 / 失败 / 完成）：
                // 复位成「还没开始下」的样子 —— 图标回来、百分比和进度条收起。
                r.boundId = "";
                r.setRunning(false);
                reset = true;
            }
        }
        if (reset) {
            // 不跑了就不该再拦住用户点第二次
            downloading = false;
            activeRow = null;
            setRowsEnabled(true);
        }
    }

    // ---- DownloadService.Listener：回调来自子线程 ----

    /**
     * 进度。真正细致的进度（速度、剩余时间、每条任务的状态）由
     * {@link DownloadsActivity} 呈现；首页这里只保留「这一行在下」的粗粒度反馈，
     * 因为首页看到的始终只是用户刚点的那一个。
     */
    @Override
    public void onTaskProgress(DownloadTask task) {
        ui.post(() -> {
            downloading = task.isActive();
            if (activeRow == null || !activeRow.matches(task)) {
                return;
            }
            String stage = task.stage();
            if (!stage.isEmpty()) {
                activeRow.meta.setText(stage);
            }
            int pct = task.percent();
            if (pct < 0) {
                // 总量未知：显示已下载的字节数而不是一个假的百分比
                activeRow.percent.setText(Fmt.bytes(task.doneBytes()));
                activeRow.bar.setProgress(0);
            } else {
                activeRow.percent.setText(getString(R.string.progress_percent, pct));
                activeRow.bar.setProgress(Math.max(0, Math.min(100, pct)));
            }

            // 速度也顺手显示在首页：用户盯着这一行等的时候，
            // 「2.9 MB/s」比一个百分比更能说明「它在动」
            long bps = task.speedBps();
            if (task.status() == DownloadTask.STATUS_RUNNING && bps > 0 && pct >= 0) {
                activeRow.meta.setText(task.stage() + " · " + Fmt.speed(bps));
            }
        });
    }

    @Override
    public void onTaskFinished(DownloadTask task, boolean ok, String message) {
        ui.post(() -> {
            downloading = false;
            if (activeRow != null && activeRow.matches(task)) {
                // setRunning(false) 会把副标题还原成画质信息
                activeRow.setRunning(false);
                activeRow = null;
            }
            setRowsEnabled(true);

            if (ok) {
                String loc = task.location();
                if (!loc.isEmpty()) {
                    tvSavedTo.setText(getString(R.string.saved_to, loc));
                    tvSavedTo.setVisibility(View.VISIBLE);
                }
            }

            Snackbar.show(findViewById(R.id.root), message,
                    null, null, ok ? R.drawable.ic_check : R.drawable.ic_error);
        });
    }

    // ==================================================================
    // 设置：底部表单
    // ==================================================================

    /**
     * 把登录态药丸刷成当前的值。
     *
     * <p>登录成功和退出登录都会调用它，所以抽出来 —— 两处各写一遍的话，
     * 早晚有一处忘了改，就会出现「已经退出了还显示已登录」。</p>
     */
    private void refreshLoginState(TextView tv, Prefs prefs) {
        if (!prefs.hasLogin()) {
            tv.setText(R.string.settings_login_state_off);
            return;
        }
        String uname = prefs.loginUname();
        // 用户名可能是空的 —— 旧版本只存了 SESSDATA，没有这一步。
        // 那种情况下退回「已登录」，不要去猜一个名字出来。
        tv.setText(uname.isEmpty()
                ? getString(R.string.settings_login_state_on)
                : getString(R.string.settings_login_state_on_as, uname));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_DIR) {
            onDirPicked(resultCode, data);
            return;
        }

        if (requestCode != REQ_LOGIN) {
            return;
        }
        Prefs prefs = new Prefs(this);
        if (resultCode != RESULT_OK || !prefs.hasLogin()) {
            // 用户自己关掉了登录页。这不是错误，不需要弹提示。
            return;
        }
        String uname = prefs.loginUname();
        if (data != null && data.hasExtra(LoginActivity.EXTRA_UNAME)) {
            uname = data.getStringExtra(LoginActivity.EXTRA_UNAME);
        }
        Snackbar.show(findViewById(R.id.root), getString(R.string.snack_logged_in, uname));

        // 登录态直接决定画质上限，刚才那份解析结果已经不作数了。
        // 输入框里还有内容就重解析一次，否则用户得手动再点一下。
        if (!inputUrl.getText().toString().trim().isEmpty()) {
            doParse();
        }
    }

    /**
     * 用户从系统的目录选择器回来了。
     *
     * <p>拿到 tree URI 之后必须立刻 {@code takePersistableUriPermission}：
     * 不申请持久化的话，这次授权只在本进程有效，用户下次启动应用就写不进去了
     * —— 而那会在下载的最后一步才失败。</p>
     */
    private void onDirPicked(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            // 用户按了返回。静默即可，他本来就可能是在看一眼。
            return;
        }
        android.net.Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // 个别 provider 不支持持久授权。仍然记下来，只是重启后可能失效；
            // StorageDir.verifyCustom 会在下载开始时发现并退回默认目录。
            Log.w(TAG, "无法取得持久目录授权：" + e.getMessage());
        }
        StorageDir.setCustom(this, tree.toString(), labelOfTree(tree));
        Snackbar.show(findViewById(R.id.root),
                getString(R.string.snack_dir_set, StorageDir.customLabel(this)));
    }

    /** 把 tree URI 变成给人看的路径。 */
    private static String labelOfTree(android.net.Uri tree) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            if (docId != null && !docId.isEmpty()) {
                int colon = docId.indexOf(':');
                String after = colon >= 0 ? docId.substring(colon + 1) : docId;
                return after.isEmpty() ? docId : after;
            }
        } catch (RuntimeException ignored) {
            // 取不到就退回整个 URI
        }
        return tree.toString();
    }

    /** 刷新设置面板里那一行「当前：…」。 */
    private void refreshDirLabel(TextView tv) {
        if (tv == null) {
            return;
        }
        boolean custom = StorageDir.hasCustom(this);
        String path = StorageDir.describe(this, false);
        tv.setText(custom
                ? getString(R.string.settings_dir_using_custom, path)
                : getString(R.string.settings_dir_using_default, path));
    }

    /**
     * 铺一行 1..{@link Prefs#MAX_CONNECTIONS} 的并发数按钮。
     *
     * <p>选中态跟首页的画质芯片用同一套语言：凸起 + 主色文字 = 选中，
     * 凹入 + 次要色 = 未选中。新拟态里状态靠形变表达，不靠色差，
     * 否则在「卡片与页面同色」的平面里根本看不出哪个被选中。</p>
     *
     * <p>点一下直接落盘 —— 这一项没有「保存」按钮。</p>
     */
    private void buildConnChips(ViewGroup row, final Prefs prefs) {
        if (row == null) {
            return;
        }
        row.removeAllViews();
        final int current = prefs.connections();
        final TextView[] chips = new TextView[Prefs.MAX_CONNECTIONS];

        for (int i = 1; i <= Prefs.MAX_CONNECTIONS; i++) {
            final int n = i;
            TextView b = new TextView(this);
            b.setText(getString(R.string.conn_value, n));
            b.setGravity(Gravity.CENTER);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.connection_chip_text));
            b.setContentDescription(getString(R.string.cd_conn_option, n));
            // 每个按钮等宽铺满整行；用 weight 而不是固定宽，
            // 免得在窄屏上挤出去
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, (int) getResources().getDimension(R.dimen.touch_min), 1f);
            if (i > 1) {
                lp.setMarginStart((int) getResources().getDimension(R.dimen.space_sm));
            }
            b.setLayoutParams(lp);
            paintConnChip(b, n == current);
            b.setOnClickListener(v -> {
                prefs.setConnections(n);
                for (int k = 0; k < chips.length; k++) {
                    if (chips[k] != null) {
                        paintConnChip(chips[k], k == n - 1);
                    }
                }
                Snackbar.show(findViewById(R.id.root),
                        getString(R.string.snack_conn_saved, n));
            });
            chips[i - 1] = b;
            row.addView(b);
        }
    }

    /** 给一个并发数按钮上色 + 上凸凹。 */
    private void paintConnChip(TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setTextColor(on ? HyperTheme.primaryText(this) : HyperTheme.textSecondary(this));
        chip.setTypeface(null, on ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        // 与 applyChipStates 同一套：凹凸都要跟着选中态走。
        // 这里曾经对玻璃态特判成全凸起，玻璃态下就分不出选了哪个连接数。
        if (on) {
            NeumorphicSurface.convex(chip, 14, 3);
        } else {
            NeumorphicSurface.concave(chip, 14, 1.5f);
        }
    }

    private void showSettings() {
        final Dialog sheet = new Dialog(this, R.style.Theme_BiliGrab_BottomSheet);
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_settings, null, false);
        boolean blurEnabled = false;
        if (HyperTheme.isGlass(this)
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            WindowManager blurManager = getSystemService(WindowManager.class);
            blurEnabled = blurManager != null && blurManager.isCrossWindowBlurEnabled();
        }
        if (HyperTheme.isGlass(this)) {
            float corner = getResources().getDimension(R.dimen.radius_dialog);
            View sheetRoot = content.findViewById(R.id.sheetRoot);
            GlassMeshDrawable glass = new GlassMeshDrawable(
                    HyperTheme.isDark(this), corner, corner);
            // 面板必须挡住后面的内容：窗口模糊可用时保留一点透感（78%），系统关闭模糊时全不透明（100%）。
            glass.backgroundAlpha(blurEnabled ? 0xC8 : 0xFF);
            sheetRoot.setBackground(glass);
        }
        sheet.setContentView(content);

        Window w = sheet.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            // gravity 必须写进 attributes，不能只靠 Window.setGravity：
            // 后者会被随后的 setAttributes 覆盖掉，面板就会浮在屏幕中间而不是贴底。
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = blurEnabled ? 0.28f : 0.58f;
            w.setAttributes(lp);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                int blurRadius = blurEnabled
                        ? Math.round(28f * getResources().getDisplayMetrics().density) : 0;
                w.setBackgroundBlurRadius(blurRadius);
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        // 内容过高时限制成可滚动，避免铺满整个屏幕
        final ScrollView sv = content.findViewById(R.id.sheetScroll);
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        sv.post(() -> {
            if (sv.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = sv.getLayoutParams();
                lp.height = maxH;
                sv.setLayoutParams(lp);
            }
        });

        // ---- 账号 ----
        // 登录改成在应用内完成。以前是让用户去浏览器 F12 里翻出 SESSDATA 粘进来，
        // 那个值取错了不会报错，只会表现为「明明登录了却还是 480P」，
        // 用户没有任何办法自查。现在点一下按钮，走 B 站官方登录页。
        final TextView tvState = content.findViewById(R.id.tvLoginState);
        refreshLoginState(tvState, prefs);
        // 药丸在 XML 里是编译期兜底色，换主题色后会留在蓝色上，这里跟着主色走
        applyPill(tvState, HyperTheme.primary(this));

        final Button btnLogin = content.findViewById(R.id.btnLogin);
        // 面板收起来，否则它会盖在整屏的登录页上
        btnLogin.setOnClickListener(v -> {
            sheet.dismiss();
            startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
        });

        final Button btnClear = content.findViewById(R.id.btnClearCookie);
        btnClear.setOnClickListener(v -> {
            prefs.clearLogin();
            refreshLoginState(tvState, prefs);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_cookie_cleared));
        });

        // ---- 下载偏好 ----
        final Switch swAvc = content.findViewById(R.id.swPreferAvc);
        swAvc.setChecked(prefs.preferAvc());
        // 开关的轨道与滑块由这里换成浮雕版本。没有替换成自定义 View，是因为
        // CompoundButton 带着 checkedChange 与可访问性语义，重写一遍不划算 ——
        // 只换 drawable 就能拿到完整的浮雕效果。
        NeumorphicControls.dressSwitch(swAvc);

        // ---- 并发连接数 ----
        // YouTube 按每条连接限速，这里是唯一能真正提速的开关。
        // 做成一行数字按钮，点一下即存，不用再点「保存」。
        buildConnChips(content.findViewById(R.id.rowConn), prefs);

        // ---- 下载位置 ----
        // 目录做成「选 / 恢复默认」两个按钮，而不是让用户手打路径。
        // 从 Android 10 起应用不能随便往任意路径写，必须拿到系统授权的
        // tree URI；让用户输入路径字符串，一半的情况下会得到一个写不进去的目录，
        // 而失败要等到下载的最后一步才暴露出来。
        final TextView tvDir = content.findViewById(R.id.tvDirValue);
        refreshDirLabel(tvDir);

        final Button btnPickDir = content.findViewById(R.id.btnPickDir);
        btnPickDir.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_DIR);
            } catch (ActivityNotFoundException e) {
                // 极少数精简 ROM 抽掉了文件选择器
                Log.w(TAG, "没有可用的目录选择器", e);
                Snackbar.show(findViewById(R.id.root), getString(R.string.snack_dir_invalid),
                        null, null, R.drawable.ic_error);
            }
        });

        final Button btnResetDir = content.findViewById(R.id.btnResetDir);
        btnResetDir.setOnClickListener(v -> {
            StorageDir.clearCustom(this);
            refreshDirLabel(tvDir);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_dir_reset));
        });

        // ---- YouTube ----
        final EditText etProxy = content.findViewById(R.id.etProxy);
        etProxy.setText(prefs.youtubeProxy());

        final TextView tvDlp = content.findViewById(R.id.tvYtDlpVersion);
        final Button btnUpdate = content.findViewById(R.id.btnUpdateYtDlp);
        tvDlp.setText(R.string.settings_engine_checking);

        // 读版本要起一次解释器（约 30 ms，但首次解压要几秒），
        // 放在后台线程，别卡住表单弹出的动画
        bg.execute(() -> {
            String v;
            if (!YouTubeEngine.isAvailable(this)) {
                v = "";
            } else {
                v = YouTubeEngine.version(this);
            }
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (v.isEmpty()) {
                    tvDlp.setText(R.string.settings_engine_missing);
                } else {
                    tvDlp.setText(getString(R.string.settings_engine_version, v));
                }
                // 引擎都没装起来就没得更新
                btnUpdate.setEnabled(!v.isEmpty());
            });
        });

        btnUpdate.setOnClickListener(v -> {
            btnUpdate.setEnabled(false);
            tvDlp.setText(R.string.snack_engine_updating);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_engine_updating));

            // 更新用的是输入框里的值而不是已保存的值：用户刚填完代理就点更新，
            // 是很自然的顺序，这时候拿旧值去连必然失败
            final String proxy = etProxy.getText().toString().trim();
            bg.execute(() -> {
                try {
                    String nv = YouTubeEngine.updateYtDlp(this, proxy);
                    ui.post(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        tvDlp.setText(getString(R.string.settings_engine_version, nv));
                        btnUpdate.setEnabled(true);
                        Snackbar.show(findViewById(R.id.root),
                                getString(R.string.snack_engine_updated, nv), null, null,
                                R.drawable.ic_check);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "更新 yt-dlp 失败", e);
                    ui.post(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        btnUpdate.setEnabled(true);
                        // 版本号可能已经被上一次成功的更新改掉了，重新读一次
                        String cur = YouTubeEngine.version(this);
                        tvDlp.setText(cur.isEmpty()
                                ? getString(R.string.settings_engine_missing)
                                : getString(R.string.settings_engine_version, cur));
                        Snackbar.show(findViewById(R.id.root), describeRaw(e), null, null,
                                R.drawable.ic_error);
                    });
                }
            });
        });

        // ---- 外观 ----
        final FlowLayout chipTheme = content.findViewById(R.id.chipTheme);
        final int[] modeValues = {Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK};
        final int[] modeLabels = {
                R.string.settings_theme_system,
                R.string.settings_theme_light,
                R.string.settings_theme_dark};
        final int currentMode = prefs.themeMode();
        int currentModeIndex = 0;
        for (int i = 0; i < modeValues.length; i++) {
            if (modeValues[i] == currentMode) currentModeIndex = i;
            final int mode = modeValues[i];
            final int index = i;
            TextView c = makeChip(chipTheme, getString(modeLabels[i]));
            c.setOnClickListener(v -> {
                HyperosClick.haptic(v);
                applyChipStates(chipTheme, index);
                if (mode != prefs.themeMode()) {
                    prefs.setThemeMode(mode);
                    // 外观变化必须重建才能整体换色
                    sheet.dismiss();
                    recreate();
                }
            });
            chipTheme.addView(c);
        }
        applyChipStates(chipTheme, currentModeIndex);

        // 视觉引擎。规范是双引擎的：两者共用同一套尺寸与交互参数，
        // 只有"表面怎么画"不同（浮雕 vs 玻璃）。
        final int[] skinValues = {Prefs.SKIN_NEUMORPHISM, Prefs.SKIN_GLASS};
        final String[] skinLabels = {
                getString(R.string.settings_skin_neumorphism),
                getString(R.string.settings_skin_glass)};
        final int skinIndex = prefs.skin() == Prefs.SKIN_GLASS ? 1 : 0;
        FlowLayout chipSkin = addChipRow(chipTheme, getString(R.string.settings_skin),
                skinLabels, skinIndex, i -> {
                    if (skinValues[i] != prefs.skin()) {
                        prefs.setSkin(skinValues[i]);
                        sheet.dismiss();
                        recreate();
                    }
                });

        // 主题色。规范正文只定义了默认蓝，其余按同一结构补全；
        // 默认给的是樱花粉，保留 BiliGrab 原本的品牌色。
        final int schemeCount = HyperTheme.schemeCount(this);
        final String[] colorLabels = new String[schemeCount];
        android.content.res.TypedArray names =
                getResources().obtainTypedArray(R.array.hyper_scheme_names);
        for (int i = 0; i < schemeCount; i++) {
            colorLabels[i] = names.getString(i);
        }
        names.recycle();

        FlowLayout chipColor = addChipRow(chipSkin, getString(R.string.settings_primary_color),
                colorLabels, prefs.primary(), i -> {
                    if (i != prefs.primary()) {
                        prefs.setPrimary(i);
                        sheet.dismiss();
                        recreate();
                    }
                }, row -> paintSwatches(row));

        // 主题色那一行做成色卡：直接把芯片底色刷成对应的颜色，
        // 比"写着颜色的名字但整行都是同一个色"直观得多。
        //
        // 注意这里只是首帧的补画 —— 点击时 addChipRow 内部会再调一次
        // RowDecorator，因为 applyChipStates 会把背景整个换掉。
        paintSwatches(chipColor);

        // ---- 关于 ----
        TextView tvVersion = content.findViewById(R.id.tvVersion);
        tvVersion.setText(getString(R.string.settings_version, versionName()));

        // ---- 关闭 ----
        content.findViewById(R.id.btnSheetClose).setOnClickListener(v -> {
            commitSettings(swAvc, etProxy);
            sheet.dismiss();
        });

        sheet.setOnDismissListener(d -> commitSettings(swAvc, etProxy));
        sheet.show();
    }

    /**
     * 把面板上的改动落盘。
     *
     * <p>这里**不再处理登录态**：它已经改成在 {@link LoginActivity} 里登录后立即保存，
     * 不需要也不应该由「面板关闭」这个动作来同步。以前它每次收起面板都会把输入框
     * 里的内容写回 SESSDATA，万一用户改了一半没提交，反而会把好的登录态覆盖掉。</p>
     */
    private void commitSettings(Switch swAvc, EditText etProxy) {
        boolean changed = swAvc.isChecked() != prefs.preferAvc();
        prefs.setPreferAvc(swAvc.isChecked());

        if (etProxy != null) {
            String proxy = etProxy.getText().toString().trim();
            if (!proxy.equals(prefs.youtubeProxy())) {
                prefs.setYoutubeProxy(proxy);
                changed = true;
            }
        }

        if (changed) {
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_settings_saved));
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private void ensureBuvid() {
        if (!prefs.buvid3().isEmpty()) {
            return;
        }
        bg.execute(() -> {
            String b = BiliApi.fetchBuvid3();
            if (!b.isEmpty()) {
                prefs.setBuvid3(b);
            }
        });
    }

    private void loadCover(String url) {
        preview.setCover(null);
        if (url == null || url.isEmpty()) {
            return;
        }
        // 接口返回的封面是明文 http，manifest 里 usesCleartextTraffic=false 会拦掉它。
        // BiliApi 已经统一升级成 https，这里再兜一次底。
        final String fixed = BiliApi.httpsify(url);
        // YouTube 的封面在 i.ytimg.com 上，和视频一样被墙，必须走代理。
        // 判据用最终 URL 而不是当前数据源：封面地址本身就是最好的指示。
        final boolean isYouTubeCover = Http.hostOf(fixed).endsWith("ytimg.com");
        final java.net.Proxy proxy = isYouTubeCover
                ? Http.parseProxy(prefs.youtubeProxy())
                : null;
        // B 站 CDN 校验 Referer，ytimg 不校验 —— 多送一个反而多余
        final boolean withReferer = !isYouTubeCover;
        bg.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = Http.open(fixed, "", withReferer, proxy);
                InputStream in = c.getInputStream();
                try {
                    bmp = BitmapFactory.decodeStream(in);
                } finally {
                    Http.closeQuietly(in);
                }
            } catch (Exception e) {
                // 封面失败不影响主流程，但必须留下痕迹。
                // 之前这里是 catch (Exception ignored)，于是封面被系统明文策略
                // 拦掉这件事完全不可见 —— 界面上只是恒久一个灰框。
                Log.w(TAG, "封面加载失败: " + fixed, e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            final Bitmap result = bmp;
            if (result != null) {
                ui.post(() -> preview.setCover(result));
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    /** 原始错误信息，用于展示给用户以及在错误分类时做匹配。 */
    private static String describeRaw(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        return m;
    }

    private static String fmtDuration(int sec) {
        if (sec <= 0) {
            return "--:--";
        }
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        int s = sec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** 供外部（通知点击）跳转使用。 */
    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ==================================================================
    // 分 P 列表
    // ==================================================================

    private class PartAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return current == null ? 0 : current.pages.size();
        }

        @Override
        public Object getItem(int position) {
            return current.pages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_part, parent, false);
            }
            Model.Part p = current.pages.get(position);
            TextView index = v.findViewById(R.id.tvIndex);
            index.setText(String.valueOf(p.index));
            // 序号徽标走主色。放在这里而不是 XML：主色是运行时可切换的，
            // 而列表行是复用的，不能只在 inflate 时算一次。
            applyPill(index, primaryColor);
            ((TextView) v.findViewById(R.id.tvPartTitle)).setText(p.title);
            ((TextView) v.findViewById(R.id.tvPartDuration))
                    .setText(MainActivity.this.getString(
                            R.string.part_duration, fmtDuration(p.duration)));

            boolean isSelected = position == selectedPart;
            // 选中状态用「对勾 + 底色 + 标题色」三重编码，不只靠颜色
            //
            // 对勾的 tint 也要在这里跟着主题走：XML 里的
            // @color/scheme_0_primary 只是编译期兜底，列表行又是复用的，
            // 不刷的话换主题色后对勾还是樱花粉。
            android.widget.ImageView check = v.findViewById(R.id.ivSelected);
            check.setImageTintList(android.content.res.ColorStateList.valueOf(
                    HyperTheme.primaryIcon(MainActivity.this)));
            check.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            v.setSelected(isSelected);
            return v;
        }
    }
}
