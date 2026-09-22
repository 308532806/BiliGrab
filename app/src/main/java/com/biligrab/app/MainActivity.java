package com.biligrab.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：粘贴链接 → 解析 → 选分 P 与画质 → 下载。
 */
public class MainActivity extends Activity implements DownloadService.Listener {

    private static final int REQ_PERMS = 1001;

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private EditText inputUrl;
    private Button btnParse;
    private Button btnDownload;
    private TextView btnSettings;
    private TextView tvLogin;
    private TextView tvTitle;
    private TextView tvMeta;
    private TextView tvPartsLabel;
    private TextView tvPlaceholder;
    private TextView tvStage;
    private TextView tvPercent;
    private ImageView ivCover;
    private View infoBox;
    private View controlBox;
    private View progressBox;
    private ListView listParts;
    private Spinner spQuality;
    private CheckBox cbAudioOnly;
    private ProgressBar progress;

    private Model.Video current;
    private final List<Integer> qnList = new ArrayList<>();
    private final List<String> qnLabels = new ArrayList<>();
    private int selectedPart = 0;
    private PartAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        bindViews();
        setupPartsList();
        setupQualitySpinner();

        btnParse.setOnClickListener(v -> doParse());
        btnSettings.setOnClickListener(v -> showSettings());
        btnDownload.setOnClickListener(v -> doDownload());
        cbAudioOnly.setOnCheckedChangeListener((b, checked) ->
                btnDownload.setText(checked ? R.string.download_audio : R.string.download));

        updateLoginState();
        requestPermissionsIfNeeded();
        ensureBuvid();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** 处理从 B 站 App「分享」过来的链接，省去手动粘贴。 */
    private void handleShareIntent(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        String text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        inputUrl.setText(text.trim());
        inputUrl.setSelection(inputUrl.getText().length());
        btnParse.post(this::doParse);
    }

    private void bindViews() {
        inputUrl = findViewById(R.id.inputUrl);
        btnParse = findViewById(R.id.btnParse);
        btnDownload = findViewById(R.id.btnDownload);
        btnSettings = findViewById(R.id.btnSettings);
        tvLogin = findViewById(R.id.tvLogin);
        tvTitle = findViewById(R.id.tvTitle);
        tvMeta = findViewById(R.id.tvMeta);
        tvPartsLabel = findViewById(R.id.tvPartsLabel);
        tvPlaceholder = findViewById(R.id.tvPlaceholder);
        tvStage = findViewById(R.id.tvStage);
        tvPercent = findViewById(R.id.tvPercent);
        ivCover = findViewById(R.id.ivCover);
        infoBox = findViewById(R.id.infoBox);
        controlBox = findViewById(R.id.controlBox);
        progressBox = findViewById(R.id.progressBox);
        listParts = findViewById(R.id.listParts);
        spQuality = findViewById(R.id.spQuality);
        cbAudioOnly = findViewById(R.id.cbAudioOnly);
        progress = findViewById(R.id.progress);
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

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private void doParse() {
        String raw = inputUrl.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("请先粘贴链接或 BV 号");
            return;
        }
        btnParse.setEnabled(false);
        btnParse.setText(R.string.parsing);
        tvPlaceholder.setVisibility(View.VISIBLE);
        tvPlaceholder.setText(R.string.parsing);

        bg.execute(() -> {
            try {
                String cookie = prefs.cookie();
                Model.Video v = BiliApi.view(raw, cookie);
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

        btnParse.setEnabled(true);
        btnParse.setText(R.string.parse);
        tvPlaceholder.setVisibility(View.GONE);

        infoBox.setVisibility(View.VISIBLE);
        tvTitle.setText(v.title);
        StringBuilder meta = new StringBuilder();
        meta.append(v.owner).append("  ·  ").append(v.pages.size()).append(" P  ·  ")
                .append(fmtDuration(v.duration));
        tvMeta.setText(meta.toString());
        loadCover(v.cover);

        if (!v.pages.isEmpty()) {
            tvPartsLabel.setVisibility(View.VISIBLE);
            listParts.setVisibility(View.VISIBLE);
        }
        adapter.notifyDataSetChanged();

        fillQualities(probe);

        controlBox.setVisibility(View.VISIBLE);
        showProgress(false);
        tvStage.setText(R.string.ready);
        tvPercent.setText("");
    }

    private void onParseFailed(Exception e) {
        btnParse.setEnabled(true);
        btnParse.setText(R.string.parse);
        tvPlaceholder.setText(R.string.tip);
        toast(describe(e));
    }

    private void fillQualities(Model.PlayInfo probe) {
        qnList.clear();
        qnLabels.clear();

        if (!probe.qualities.isEmpty()) {
            List<Integer> qs = new ArrayList<>(probe.qualities.keySet());
            Collections.sort(qs, Collections.reverseOrder());
            for (Integer q : qs) {
                qnList.add(q);
                qnLabels.add(probe.qualities.get(q) + "  (" + q + ")");
            }
        } else {
            // 接口没给 support_formats 时，退化到实际返回的流
            List<Integer> seen = new ArrayList<>();
            for (Model.Stream s : probe.videos) {
                if (!seen.contains(s.quality)) {
                    seen.add(s.quality);
                }
            }
            Collections.sort(seen, Collections.reverseOrder());
            for (Integer q : seen) {
                qnList.add(q);
                qnLabels.add("qn" + q);
            }
        }

        if (qnList.isEmpty()) {
            controlBox.setVisibility(View.GONE);
            toast("该稿件没有可下载的画质");
            return;
        }

        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, qnLabels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spQuality.setAdapter(a);

        int want = prefs.preferQn();
        int idx = qnList.indexOf(want);
        if (idx < 0 && !qnList.isEmpty()) {
            idx = 0;
        }
        if (idx >= 0) {
            spQuality.setSelection(idx);
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    private void doDownload() {
        if (current == null || current.pages.isEmpty()) {
            toast(getString(R.string.need_parse_first));
            return;
        }
        if (qnList.isEmpty()) {
            toast("画质信息不可用，请重新解析");
            return;
        }
        int pos = spQuality.getSelectedItemPosition();
        if (pos < 0 || pos >= qnList.size()) {
            pos = 0;
        }
        int qn = qnList.get(pos);
        prefs.setPreferQn(qn);

        Model.Part part = current.pages.get(selectedPart);
        Model.Task task = new Model.Task();
        task.bvid = current.bvid;
        task.cid = part.cid;
        task.title = current.title;
        task.partTitle = part.title;
        task.qn = qn;
        task.audioOnly = cbAudioOnly.isChecked();

        showProgress(true);
        tvStage.setText(R.string.parsing);
        tvPercent.setText("0%");
        progress.setProgress(0);

        DownloadService.enqueue(this, task);
    }

    // ------------------------------------------------------------------
    // DownloadService.Listener —— 回调来自子线程
    // ------------------------------------------------------------------

    @Override
    public void onProgress(String stage, int percent) {
        ui.post(() -> {
            showProgress(true);
            tvStage.setText(stage);
            tvPercent.setText(percent + "%");
            progress.setProgress(percent);
        });
    }

    @Override
    public void onFinished(boolean ok, String message, String location) {
        ui.post(() -> {
            progress.setProgress(100);
            tvStage.setText(ok ? R.string.done : R.string.failed);
            tvPercent.setText(ok ? "100%" : "");
            if (ok) {
                tvStage.setText(getString(R.string.save_location) + "：" + location);
            } else {
                tvStage.setText(message);
            }
            toast(message);
        });
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    private void showSettings() {
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(4), pad, 0);

        TextView help = new TextView(this);
        help.setText(R.string.setting_help);
        help.setTextSize(12f);
        help.setTextColor(getColor(R.color.text_dim));
        help.setLineSpacing(dp(3), 1f);
        box.addView(help);

        final EditText et = new EditText(this);
        et.setHint(R.string.setting_hint_sessdata);
        et.setText(prefs.sessdata());
        et.setTextSize(13f);
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(et, lp);

        final CheckBox cb = new CheckBox(this);
        cb.setText(R.string.prefer_avc);
        cb.setTextSize(13f);
        cb.setChecked(prefs.preferAvc());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        box.addView(cb, lp2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.setting_title)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    prefs.setSessdata(et.getText().toString());
                    prefs.setPreferAvc(cb.isChecked());
                    updateLoginState();
                    toast("已保存");
                })
                .setNeutralButton("清除登录态", (d, w) -> {
                    prefs.setSessdata("");
                    updateLoginState();
                    toast("已清除 Cookie");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private void setupPartsList() {
        adapter = new PartAdapter();
        listParts.setAdapter(adapter);
        listParts.setOnItemClickListener((parent, view, position, id) -> {
            selectedPart = position;
            adapter.notifyDataSetChanged();
        });
    }

    private void setupQualitySpinner() {
        ArrayAdapter<String> empty = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<String>());
        empty.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spQuality.setAdapter(empty);
    }

    private void updateLoginState() {
        if (prefs.hasLogin()) {
            tvLogin.setText(R.string.logged_in);
            tvLogin.setTextColor(getColor(R.color.ok));
        } else {
            tvLogin.setText(R.string.not_logged_in);
            tvLogin.setTextColor(getColor(R.color.text_dim));
        }
    }

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

    private void showProgress(boolean show) {
        progressBox.setVisibility(show ? View.VISIBLE : View.GONE);
        controlBox.setVisibility(show ? View.GONE : View.VISIBLE);
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

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        if (e instanceof java.net.UnknownHostException) {
            return "无法联网，请检查网络连接";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "网络超时，请重试";
        }
        return m;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static String fmtDuration(int sec) {
        if (sec <= 0) {
            return "--:--";
        }
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        int s = sec % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    /** 分 P 列表适配器。 */
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
                    .setText("时长 " + fmtDuration(p.duration));
            v.findViewById(R.id.vSelected)
                    .setVisibility(position == selectedPart ? View.VISIBLE : View.INVISIBLE);
            return v;
        }
    }

    /** 供外部（通知点击）跳转使用。 */
    public static void start(Context ctx) {
        ctx.startActivity(new android.content.Intent(ctx, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
