package com.biligrab.downloader;

import android.app.Activity;
import android.app.Dialog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
public class MainActivity extends Activity implements DownloadService.Listener {

    private static final String TAG = "BiliGrab";

    private static final int REQ_PERMS = 1001;

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

    // ---- 四个互斥状态 ----
    private View emptyBox;
    private View loadingBox;
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
    }

    @Override
    protected void onPause() {
        DownloadService.removeListener(this);
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

        // 输入框的光标与选中高亮。不改的话换主题色后光标还是默认蓝，
        // 在粉色主题下非常跳。
        applyCaret(inputUrl, p);
    }

    /**
     * 把一块药丸刷成主色系：15% 透明度的主色底 + 主色文字。
     *
     * <p>底色必须留透明度：全不透明的主色底配主色文字会糊成一片，
     * 药丸是"标签"而不是"按钮"，不需要那么强的对比。</p>
     */
    private void applyPill(TextView tv, int primary) {
        if (tv == null) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(999);
        g.setColor((primary & 0x00FFFFFF) | 0x26000000);
        tv.setBackground(g);
        tv.setTextColor(primary);
    }

    private void applyAccent(View v, int color) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(color);
        }
    }

    /**
     * 输入框的选中高亮。
     *
     * <p>光标颜色本身改不了（平台只提供 {@code android:textCursorDrawable}，
     * 要在 XML 里指定一个 tint 好的 drawable，而主题色是运行时的），
     * 所以只处理选中高亮这一半 —— 它面积大，是换色后最明显的残留。</p>
     */
    private void applyCaret(EditText et, int color) {
        if (et == null) return;
        // 40% 透明度：高亮是背景，全不透明会盖住底下选中的字
        et.setHighlightColor((color & 0x00FFFFFF) | 0x66000000);
    }

    private void bindViews() {
        topBar = findViewById(R.id.topBar);
        scroll = findViewById(R.id.scroll);

        inputUrl = findViewById(R.id.inputUrl);
        btnParse = findViewById(R.id.btnParse);
        btnPaste = findViewById(R.id.btnPaste);
        btnSettings = findViewById(R.id.btnSettings);

        emptyBox = findViewById(R.id.emptyBox);
        loadingBox = findViewById(R.id.loadingBox);
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

        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();

            ViewGroup.LayoutParams barLp = topBar.getLayoutParams();
            barLp.height = baseBarHeight + top;
            topBar.setLayoutParams(barLp);

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

    private void renderLoading() {
        hideAllStates();
        // 开始解析就停掉上一个视频的预览：它马上就和新结果对不上了
        if (preview != null) {
            preview.reset();
        }
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
        inputUrl.setText(text.trim());
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
        inputUrl.setText(text.toString().trim());
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

        renderLoading();

        final boolean youtube = YouTubeEngine.isYouTubeUrl(raw);
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
                ui.post(() -> onParseFailed(e));
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

    private void onParseFailed(Exception e) {
        String raw = describeRaw(e);
        String title;
        String fix;

        if (e instanceof UnknownHostException) {
            title = getString(R.string.err_network);
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
            title = getString(R.string.err_network);
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
        if (!youtube && !prefs.hasLogin() && best < QN_1080P && best > 0) {
            tvQualityHint.setText(getString(R.string.hint_login_for_quality, qnLabel(probe, best)));
            qualityHintBox.setVisibility(View.VISIBLE);
        } else {
            qualityHintBox.setVisibility(View.GONE);
        }
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
                HyperTheme.divider(this)));

        tvLabel.setText(label);
        tvMeta.setText(meta);
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
     */
    private void applyChipStates(FlowLayout row, int selectedIndex) {
        boolean glass = HyperTheme.isGlass(this);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView chip = (TextView) v;

            boolean on = (i == selectedIndex);
            chip.setTextColor(on ? HyperTheme.primary(this) : HyperTheme.textSecondary(this));
            chip.setTypeface(null, on ? android.graphics.Typeface.BOLD
                                      : android.graphics.Typeface.NORMAL);

            if (glass) {
                NeumorphicSurface.convex(chip, 14, 3).glass(true);
            } else if (on) {
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
        ViewGroup parent = (ViewGroup) anchor.getParent();
        int at = parent.indexOfChild(anchor) + 1;

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(HyperTheme.textSecondary(this));
        tv.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(16);
        tp.bottomMargin = dp(4);
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
                onPick.onPick(index);
            });
            row.addView(c);
        }

        parent.addView(tv, at);
        parent.addView(row, at + 1);

        applyChipStates(row, selected);
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

            if (row.stream == null) {
                Snackbar.show(findViewById(R.id.root), getString(R.string.err_no_video_stream));
                return;
            }
            task.videoUrl = row.stream.url;
            task.videoSize = row.stream.size;

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

        DownloadService.enqueue(this, task);
    }

    // ---- DownloadService.Listener：回调来自子线程 ----

    @Override
    public void onProgress(String stage, int percent) {
        ui.post(() -> {
            downloading = true;
            if (activeRow == null) {
                return;
            }
            activeRow.meta.setText(stage);
            activeRow.percent.setText(getString(R.string.progress_percent, percent));
            activeRow.bar.setProgress(Math.max(0, Math.min(100, percent)));
        });
    }

    @Override
    public void onFinished(boolean ok, String message, String location) {
        ui.post(() -> {
            downloading = false;
            if (activeRow != null) {
                // setRunning(false) 会把副标题还原成画质信息
                activeRow.setRunning(false);
                activeRow = null;
            }
            setRowsEnabled(true);

            if (ok && location != null && !location.isEmpty()) {
                tvSavedTo.setText(getString(R.string.saved_to, location));
                tvSavedTo.setVisibility(View.VISIBLE);
            }

            Snackbar.show(findViewById(R.id.root), message,
                    null, null, ok ? R.drawable.ic_check : R.drawable.ic_error);
        });
    }

    // ==================================================================
    // 设置：底部表单
    // ==================================================================

    private void showSettings() {
        final Dialog sheet = new Dialog(this, R.style.Theme_BiliGrab_BottomSheet);
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_settings, null, false);
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
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
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
        final EditText et = content.findViewById(R.id.etSessdata);
        et.setText(prefs.sessdata());

        final TextView tvState = content.findViewById(R.id.tvLoginState);
        tvState.setText(prefs.hasLogin()
                ? R.string.settings_login_state_on : R.string.settings_login_state_off);
        // 药丸在 XML 里是编译期兜底色，换主题色后会留在蓝色上，这里跟着主色走
        applyPill(tvState, HyperTheme.primary(this));

        final Button btnClear = content.findViewById(R.id.btnClearCookie);
        btnClear.setOnClickListener(v -> {
            prefs.setSessdata("");
            et.setText("");
            tvState.setText(R.string.settings_login_state_off);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_cookie_cleared));
        });

        // ---- 下载偏好 ----
        final Switch swAvc = content.findViewById(R.id.swPreferAvc);
        swAvc.setChecked(prefs.preferAvc());
        // 开关的轨道与滑块由这里换成浮雕版本。没有替换成自定义 View，是因为
        // CompoundButton 带着 checkedChange 与可访问性语义，重写一遍不划算 ——
        // 只换 drawable 就能拿到完整的浮雕效果。
        NeumorphicControls.dressSwitch(swAvc);

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
                });

        // 主题色那一行做成色卡：直接把芯片底色刷成对应的颜色，
        // 比"写着颜色的名字但整行都是同一个色"直观得多。
        for (int i = 0; i < chipColor.getChildCount(); i++) {
            View v = chipColor.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            int c = HyperTheme.schemePrimary(this, i);
            ((TextView) v).setTextColor(HyperTheme.contrastOn(this, c));
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg instanceof NeumorphicDrawable) {
                NeumorphicDrawable nd = (NeumorphicDrawable) bg;
                nd.colors(c, HyperTheme.neumLight(this), HyperTheme.neumDark(this));
                nd.invalidateSelf();
            }
        }

        // ---- 关于 ----
        TextView tvVersion = content.findViewById(R.id.tvVersion);
        tvVersion.setText(getString(R.string.settings_version, versionName()));

        // ---- 关闭 ----
        content.findViewById(R.id.btnSheetClose).setOnClickListener(v -> {
            commitSettings(et, swAvc, etProxy);
            sheet.dismiss();
        });

        sheet.setOnDismissListener(d -> commitSettings(et, swAvc, etProxy));
        sheet.show();
    }

    private void commitSettings(EditText et, Switch swAvc) {
        commitSettings(et, swAvc, null);
    }

    private void commitSettings(EditText et, Switch swAvc, EditText etProxy) {
        String sess = et.getText().toString().trim();
        boolean changed = !sess.equals(prefs.sessdata())
                || swAvc.isChecked() != prefs.preferAvc();
        prefs.setSessdata(sess);
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
            v.findViewById(R.id.ivSelected)
                    .setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            v.setSelected(isSelected);
            return v;
        }
    }
}
