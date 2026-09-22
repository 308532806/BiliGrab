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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

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

    // ---- 结果 ----
    private ImageView ivCover;
    private TextView tvTitle;
    private TextView tvMeta;
    private View qualityHintBox;
    private TextView tvQualityHint;
    private Button btnQualityHintAction;
    private TextView tvPartsLabel;
    private ListView listParts;
    private TextView tvQualityLabel;
    private FlowLayout chipQuality;
    private Switch swAudioOnly;

    // ---- 进度 ----
    private View progressBox;
    private TextView tvStage;
    private TextView tvPercent;
    private TextView tvSavedTo;
    private ProgressBar progress;

    // ---- FAB ----
    private View fab;
    private ImageView ivFabIcon;
    private TextView tvFabLabel;

    // ---- 状态 ----
    private Model.Video current;
    private final List<Integer> qnList = new ArrayList<>();
    private int selectedQn = Prefs.DEFAULT_QN;
    private int selectedPart;
    private boolean downloading;
    private PartAdapter adapter;

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
        applyWindowInsets();
        wireActions();
        setupPartsList();
        setupQualityChips();

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
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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

        ivCover = findViewById(R.id.ivCover);
        tvTitle = findViewById(R.id.tvTitle);
        tvMeta = findViewById(R.id.tvMeta);
        qualityHintBox = findViewById(R.id.qualityHintBox);
        tvQualityHint = findViewById(R.id.tvQualityHint);
        btnQualityHintAction = findViewById(R.id.btnQualityHintAction);
        tvPartsLabel = findViewById(R.id.tvPartsLabel);
        listParts = findViewById(R.id.listParts);
        tvQualityLabel = findViewById(R.id.tvQualityLabel);
        chipQuality = findViewById(R.id.chipQuality);
        swAudioOnly = findViewById(R.id.swAudioOnly);

        progressBox = findViewById(R.id.progressBox);
        tvStage = findViewById(R.id.tvStage);
        tvPercent = findViewById(R.id.tvPercent);
        tvSavedTo = findViewById(R.id.tvSavedTo);
        progress = findViewById(R.id.progress);

        fab = findViewById(R.id.fab);
        ivFabIcon = findViewById(R.id.ivFabIcon);
        tvFabLabel = findViewById(R.id.tvFabLabel);
    }

    /**
     * Edge-to-edge：系统栏是透明的，内容必须自己避开。
     *
     * <p>做法是抬高顶栏而不是给它加内边距 —— 否则标题会压到状态栏上。</p>
     */
    private void applyWindowInsets() {
        final int baseBarHeight =
                getResources().getDimensionPixelSize(R.dimen.top_app_bar_height);
        final int baseMargin =
                getResources().getDimensionPixelSize(R.dimen.screen_margin);

        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();

            ViewGroup.LayoutParams barLp = topBar.getLayoutParams();
            barLp.height = baseBarHeight + top;
            topBar.setLayoutParams(barLp);

            ViewGroup.MarginLayoutParams fabLp =
                    (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
            fabLp.bottomMargin = baseMargin + bottom;
            fab.setLayoutParams(fabLp);

            // 横屏刘海/手势区：只改左右内边距，保留已有的底部留白
            scroll.setPadding(left, scroll.getPaddingTop(), right, scroll.getPaddingBottom());
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

        fab.setOnClickListener(v -> doDownload());

        swAudioOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateFabLabel();
            // 仅音频时画质选项没有意义，收起而不是留一个禁用控件；
            // 本来就没有可用画质时，也不能因为取消勾选就把它显示出来
            int vis = (isChecked || qnList.isEmpty()) ? View.GONE : View.VISIBLE;
            tvQualityLabel.setVisibility(vis);
            chipQuality.setVisibility(vis);
        });
    }

    private void setupPartsList() {
        adapter = new PartAdapter();
        listParts.setAdapter(adapter);
        listParts.setOnItemClickListener((parent, view, position, id) -> {
            selectedPart = position;
            adapter.notifyDataSetChanged();
        });
    }

    private void setupQualityChips() {
        // 无内容时彻底不占位
        chipQuality.setVisibility(View.GONE);
        tvQualityLabel.setVisibility(View.GONE);
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
        fab.setVisibility(View.GONE);
    }

    private void renderLoading() {
        hideAllStates();
        loadingBox.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
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
        fab.setVisibility(View.GONE);

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
        fab.setVisibility(downloading ? View.GONE : View.VISIBLE);
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
        String raw = inputUrl.getText().toString().trim();
        if (raw.isEmpty()) {
            renderError(getString(R.string.err_empty_input),
                    getString(R.string.err_empty_input_fix), null);
            return;
        }

        renderLoading();

        bg.execute(() -> {
            try {
                String cookie = prefs.cookie();
                Model.Video v = BiliApi.view(raw, cookie);
                // 用 127 探测一次，拿到该稿件全部可用画质
                Model.PlayInfo probe = BiliApi.playurl(
                        v.bvid, v.pages.get(0).cid, 127, cookie);
                ui.post(() -> onParsed(v, probe));
            } catch (Exception e) {
                ui.post(() -> onParseFailed(e));
            }
        });
    }

    private void onParsed(Model.Video v, Model.PlayInfo probe) {
        current = v;
        selectedPart = 0;
        downloading = false;

        renderResult();

        tvTitle.setText(v.title);
        tvMeta.setText(getString(R.string.meta_format,
                v.owner, v.pages.size(), fmtDuration(v.duration)));

        loadCover(v.cover);
        adapter.notifyDataSetChanged();

        // 先复位开关，再据此决定画质区是否显示，避免两处逻辑互相覆盖
        swAudioOnly.setChecked(false);
        buildQualityChips(probe);

        progressBox.setVisibility(View.GONE);
        updateFabLabel();
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

    private void buildQualityChips(Model.PlayInfo probe) {
        chipQuality.removeAllViews();
        qnList.clear();

        if (probe != null && probe.videos != null) {
            // 去重并按画质从高到低排序
            LinkedHashSet<Integer> seen = new LinkedHashSet<>();
            for (Model.Stream s : probe.videos) {
                seen.add(s.quality);
            }
            qnList.addAll(seen);
            Collections.sort(qnList, Collections.reverseOrder());
        }

        if (qnList.isEmpty()) {
            tvQualityLabel.setVisibility(View.GONE);
            chipQuality.setVisibility(View.GONE);
            Snackbar.show(findViewById(R.id.root),
                    getString(R.string.quality_not_available), null, null, R.drawable.ic_error);
            return;
        }

        int best = qnList.get(0);
        int target = prefs.preferQn();
        if (!qnList.contains(target)) {
            target = best;
        }
        selectedQn = target;

        for (Integer qn : qnList) {
            final int quality = qn;
            TextView chip = makeChip(chipQuality, qnLabel(probe, qn));
            chip.setSelected(qn == selectedQn);
            chip.setOnClickListener(v -> selectQualityChip((TextView) v, quality));
            chipQuality.addView(chip);
        }

        tvQualityLabel.setVisibility(
                swAudioOnly.isChecked() ? View.GONE : View.VISIBLE);
        chipQuality.setVisibility(
                swAudioOnly.isChecked() ? View.GONE : View.VISIBLE);

        // 只有「受登录限制」时才提示，避免无端打扰
        if (!prefs.hasLogin() && best < QN_1080P && best > 0) {
            tvQualityHint.setText(getString(R.string.hint_login_for_quality, qnLabel(probe, best)));
            qualityHintBox.setVisibility(View.VISIBLE);
        } else {
            qualityHintBox.setVisibility(View.GONE);
        }
    }

    private void selectQualityChip(TextView chip, int qn) {
        selectedQn = qn;
        for (int i = 0; i < chipQuality.getChildCount(); i++) {
            chipQuality.getChildAt(i).setSelected(false);
        }
        chip.setSelected(true);
    }

    /**
     * 造一个 M3 filter chip。
     *
     * <p>外观全部来自 {@code @style/Widget.Chip} 与 {@code view_chip.xml}，
     * 这里只负责绑定文案和行为 —— 之前画质芯片和主题芯片各自堆了一遍属性。</p>
     */
    private TextView makeChip(FlowLayout parent, String label) {
        TextView chip = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.view_chip, parent, false);
        chip.setText(label);
        return chip;
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

    private void updateFabLabel() {
        boolean audio = swAudioOnly.isChecked();
        tvFabLabel.setText(audio ? R.string.action_download_audio : R.string.action_download);
        fab.setContentDescription(getString(
                audio ? R.string.action_download_audio : R.string.action_download));
        ivFabIcon.setImageResource(audio ? R.drawable.ic_music : R.drawable.ic_download);
        fab.setEnabled(current != null && !downloading);
    }

    // ==================================================================
    // 下载
    // ==================================================================

    private void doDownload() {
        if (current == null || current.pages.isEmpty()) {
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_need_parse));
            return;
        }
        if (qnList.isEmpty() && !swAudioOnly.isChecked()) {
            Snackbar.show(findViewById(R.id.root),
                    getString(R.string.snack_need_parse), null, null, R.drawable.ic_error);
            return;
        }

        if (selectedPart < 0 || selectedPart >= current.pages.size()) {
            selectedPart = 0;
        }
        Model.Part part = current.pages.get(selectedPart);

        Model.Task task = new Model.Task();
        task.bvid = current.bvid;
        task.cid = part.cid;
        task.title = current.title;
        task.partTitle = part.title;
        task.qn = selectedQn;
        task.audioOnly = swAudioOnly.isChecked();

        prefs.setPreferQn(selectedQn);

        downloading = true;
        fab.setVisibility(View.GONE);
        progressBox.setVisibility(View.VISIBLE);
        tvSavedTo.setVisibility(View.GONE);
        tvStage.setText(R.string.stage_preparing);
        tvPercent.setText(getString(R.string.progress_percent, 0));
        progress.setProgress(0);

        DownloadService.enqueue(this, task);
    }

    // ---- DownloadService.Listener：回调来自子线程 ----

    @Override
    public void onProgress(String stage, int percent) {
        ui.post(() -> {
            downloading = true;
            fab.setVisibility(View.GONE);
            progressBox.setVisibility(View.VISIBLE);
            tvStage.setText(stage);
            tvPercent.setText(getString(R.string.progress_percent, percent));
            progress.setProgress(Math.max(0, Math.min(100, percent)));
        });
    }

    @Override
    public void onFinished(boolean ok, String message, String location) {
        ui.post(() -> {
            downloading = false;
            progress.setProgress(ok ? 100 : progress.getProgress());
            tvStage.setText(ok ? R.string.stage_done : R.string.stage_failed);
            tvPercent.setText(ok ? getString(R.string.progress_percent, 100) : "");

            if (ok && location != null && !location.isEmpty()) {
                tvSavedTo.setText(getString(R.string.saved_to, location));
                tvSavedTo.setVisibility(View.VISIBLE);
            }

            updateFabLabel();
            fab.setVisibility(View.VISIBLE);

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

        // ---- 外观 ----
        final FlowLayout chipTheme = content.findViewById(R.id.chipTheme);
        final int[] modeValues = {Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK};
        final int[] modeLabels = {
                R.string.settings_theme_system,
                R.string.settings_theme_light,
                R.string.settings_theme_dark};
        final int currentMode = prefs.themeMode();
        for (int i = 0; i < modeValues.length; i++) {
            final int mode = modeValues[i];
            final int index = i;
            TextView c = makeChip(chipTheme, getString(modeLabels[i]));
            c.setSelected(mode == currentMode);
            c.setOnClickListener(v -> {
                for (int k = 0; k < chipTheme.getChildCount(); k++) {
                    chipTheme.getChildAt(k).setSelected(k == index);
                }
                if (mode != prefs.themeMode()) {
                    prefs.setThemeMode(mode);
                    // 外观变化必须重建才能整体换色
                    sheet.dismiss();
                    recreate();
                }
            });
            chipTheme.addView(c);
        }

        // ---- 关于 ----
        TextView tvVersion = content.findViewById(R.id.tvVersion);
        tvVersion.setText(getString(R.string.settings_version, versionName()));

        // ---- 关闭 ----
        content.findViewById(R.id.btnSheetClose).setOnClickListener(v -> {
            commitSettings(et, swAvc);
            sheet.dismiss();
        });

        sheet.setOnDismissListener(d -> commitSettings(et, swAvc));
        sheet.show();
    }

    private void commitSettings(EditText et, Switch swAvc) {
        String sess = et.getText().toString().trim();
        boolean changed = !sess.equals(prefs.sessdata())
                || swAvc.isChecked() != prefs.preferAvc();
        prefs.setSessdata(sess);
        prefs.setPreferAvc(swAvc.isChecked());
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
        ivCover.setImageDrawable(null);
        if (url == null || url.isEmpty()) {
            return;
        }
        final String fixed = url.startsWith("//") ? "https:" + url : url;
        bg.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = Http.open(fixed, "", true);
                InputStream in = c.getInputStream();
                try {
                    bmp = BitmapFactory.decodeStream(in);
                } finally {
                    Http.closeQuietly(in);
                }
            } catch (Exception ignored) {
                // 封面加载失败不影响主流程
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            final Bitmap result = bmp;
            if (result != null) {
                ui.post(() -> ivCover.setImageBitmap(result));
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
            ((TextView) v.findViewById(R.id.tvIndex)).setText(String.valueOf(p.index));
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
