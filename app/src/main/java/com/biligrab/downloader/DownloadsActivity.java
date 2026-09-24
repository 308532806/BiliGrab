package com.biligrab.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.biligrab.downloader.ui.GlassMeshDrawable;
import com.biligrab.downloader.ui.HyperTheme;
import com.biligrab.downloader.ui.NeumAttr;
import com.biligrab.downloader.ui.NeuButton;
import com.biligrab.downloader.ui.NeuText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 下载管理页。
 *
 * <h3>这一页要回答的三个问题</h3>
 * <ol>
 *   <li>现在在下什么、到哪了 —— 实时进度条 + 真实速度 + 剩余时间；</li>
 *   <li>不想下了怎么办 —— 暂停 / 继续 / 取消；</li>
 *   <li>不想要了怎么办 —— 删除记录、删除文件、删除残留。</li>
 * </ol>
 *
 * <h3>刷新是怎么驱动的</h3>
 * <p>任务在后台线程更新自己（{@link DownloadService} 与 {@link TaskStore}），
 * 界面只是读者。所以这里不做任何轮询，而是让数据源在变化时喊一声：</p>
 * <ul>
 *   <li>{@link TaskStore.Listener#onTasksChanged()} —— 记录增删或进度变动；</li>
 *   <li>{@link DownloadService.Listener} —— 服务主动推送的进度与终态。</li>
 * </ul>
 * <p>两者都可能来自任意线程，一律 post 回主线程再动视图。</p>
 *
 * <h3>为什么不整屏重建</h3>
 * <p>进度每秒推好几次，每次重建整列视图会让按钮在手指下消失、把滚动位置
 * 弹回顶部。这里按任务 id 复用已有的卡片视图，只改里面的文字和进度值。</p>
 */
public class DownloadsActivity extends Activity
        implements TaskStore.Listener, DownloadService.Listener {

    private static final String TAG = "BiliGrab";

    /** 暂停/恢复的确认结果。 */
    private static final int REQ_PICK_DIR = 0x7101;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View topBar;
    private TextView tvSummary;
    private ViewGroup listBox;
    private View emptyBox;
    private View btnClearDone;

    /**
     * 「停止后再删除」的目标任务 id。
     *
     * <p>删除一条正在下载的任务必须分两步：先让 service 停下来，等它回报
     * 终态，再清文件。不能一步做完 —— 见 {@link #doDelete}。</p>
     */
    private String pendingDelete;
    private boolean pendingDeleteWithFile;

    /**
     * 这次等待是为了「取消」而不是「删除」。
     *
     * <p>两者都要先让下载线程停稳，但收尾完全不同：取消要**留下记录**
     * （状态变成已取消，之后还能重试），删除要抹掉记录。所以停止之后
     * 得知道该走哪一条路。</p>
     */
    private boolean pendingCancelOnly;

    /** id → 已建好的卡片。用来复用，避免重建导致按钮跳动。 */
    private final List<Row> rows = new ArrayList<>();

    /** 原始主色。用于填充（进度条、药丸底），不是文字色。 */
    private int primaryColor;

    /** 主色当文字用时的颜色（已推进到正文 4.5:1）。 */
    private int accentTextColor;

    /** 主色当图标用时的颜色（已推进到 3:1）。 */
    private int accentIconColor;

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyThemeOverride();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        TaskStore.get().ensureLoaded(this);

        bindViews();
        applyNeumorphicTheme();
        applyWindowInsets();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnClearDone.setOnClickListener(v -> confirmClearDone());

        // 首次进入先画一遍：onResume 里注册监听只能收到**之后**的变化，
        // 已存在的记录必须自己读一次
        rebuild();
    }

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
    protected void onResume() {
        super.onResume();
        TaskStore.get().addListener(this);
        DownloadService.addListener(this);
        // 回到这一页时可能已经有变化了（比如用户在别处删了文件）
        ui.post(this::refreshAll);
    }

    @Override
    protected void onPause() {
        TaskStore.get().removeListener(this);
        DownloadService.removeListener(this);
        super.onPause();
    }

    private void bindViews() {
        topBar = findViewById(R.id.topBar);
        tvSummary = findViewById(R.id.tvSummary);
        listBox = findViewById(R.id.listBox);
        emptyBox = findViewById(R.id.emptyBox);
        btnClearDone = findViewById(R.id.btnClearDone);
    }

    private void applyNeumorphicTheme() {
        View root = findViewById(R.id.root);
        if (root != null) {
            if (HyperTheme.isGlass(this)) {
                root.setBackground(new GlassMeshDrawable(HyperTheme.isDark(this)));
            } else {
                root.setBackgroundColor(HyperTheme.background(this));
            }
        }
        primaryColor = HyperTheme.primary(this);
        accentTextColor = HyperTheme.primaryText(this);
        accentIconColor = HyperTheme.primaryIcon(this);
        if (btnClearDone instanceof TextView) {
            // 「清除已完成」是一段落在页面底色上的文字，不是图标，
            // 所以用 primaryText（4.5:1）而不是 primaryIcon（3:1）。
            ((TextView) btnClearDone).setTextColor(accentTextColor);
        }
        // 布局里标了 neu:accent 的控件要在这里统一刷一遍主色。
        //
        // 这一步漏过一次：activity_downloads.xml 的空状态图标标了 tag，
        // 但这个 Activity 没有调 applyAccentTags，tag 就成了死标记 ——
        // 编译通过、运行不报错，图标安静地停在 @color/scheme_0_primary
        // （第一套主题色的编译期快照），只有换到深海蓝这类非默认色才看得
        // 出来。实机上确实是樱花粉色的下载图标配着一套蓝界面。
        NeumAttr.applyAccentTags(findViewById(R.id.root));
    }

    /** 与首页同一套 edge-to-edge 处理：顶栏抬高，标题压在状态栏下方那条带子里。 */
    private void applyWindowInsets() {
        final int baseBarHeight =
                getResources().getDimensionPixelSize(R.dimen.top_app_bar_height);
        final int barPadLeft = topBar.getPaddingLeft();
        final int barPadRight = topBar.getPaddingRight();
        final int barPadBottom = topBar.getPaddingBottom();

        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();

            ViewGroup.LayoutParams lp = topBar.getLayoutParams();
            lp.height = baseBarHeight + top;
            topBar.setLayoutParams(lp);
            topBar.setPadding(barPadLeft, top, barPadRight, barPadBottom);

            // 底部导航栏：内容垫高，否则最后一张卡的按钮会被挡住
            View scroll = findViewById(R.id.scroll);
            int padBottom = getResources().getDimensionPixelSize(R.dimen.space_3xl);
            scroll.setPadding(left, 0, right, padBottom + bottom);
            return insets;
        });
    }

    // ==================================================================
    // 数据源回调
    // ==================================================================

    @Override
    public void onTasksChanged() {
        // 暂停中的任务被取消时，service 走的是「直接改状态」那条路：
        // 它只发 onTasksChanged，**不会**发 onTaskFinished（因为没有任何线程
        // 需要等）。所以这条路径也要收尾，否则等在那里的 pendingDelete
        // 永远不会被清掉，界面就此停住不再刷新。
        //
        // 但「不活跃」不等于「被取消了」——**下载成功也是不活跃**。
        // service 完成任务时先 notifyProgress() 再 emitFinished()，
        // 于是这里会先被叫到：如果只看「不活跃」就把记录按取消处理，
        // 一个刚刚成功落盘的任务会被写成「已取消、已下 0 字节」。
        // 实测踩到过：文件完完整整躺在 MyGrab 里（74,151,424 字节），
        // 管理页却显示「已取消，残留已清理 · 共占用 0 B」，用户
        // 以为没下成，磁盘上却躺着一个他找不到来源的文件。
        if (pendingDelete != null) {
            DownloadTask t = TaskStore.get().byId(pendingDelete);
            if (t == null) {
                String id = pendingDelete;
                pendingDelete = null;
                ui.post(() -> finishPendingDelete(id));
                return;
            }
            if (!t.isActive()) {
                // 已完成：像 onTaskFinished 那样按「你点晚了」处理，
                // 不要动记录 —— 成功就是成功。
                if (pendingCancelOnly && t.status() == DownloadTask.STATUS_DONE) {
                    pendingCancelOnly = false;
                    pendingDelete = null;
                    ui.post(() -> {
                        Snackbar.show(findViewById(R.id.root),
                                getString(R.string.snack_cancel_too_late, t.title));
                        refreshAll();
                    });
                    return;
                }
                String id = pendingDelete;
                pendingDelete = null;
                ui.post(() -> finishPendingDelete(id));
                return;
            }
        }
        ui.post(this::refreshAll);
    }

    @Override
    public void onTaskProgress(DownloadTask task) {
        ui.post(() -> updateRow(task));
    }

    @Override
    public void onTaskFinished(DownloadTask task, boolean ok, String message) {
        // 有一条「停止后再删 / 再取消」在等它落地，先把它做完再刷界面
        if (pendingDelete != null && pendingDelete.equals(task.id)) {
            boolean cancelOnly = pendingCancelOnly;
            // 用户点「取消」的那一刻，任务可能刚好合成完、文件已经进相册了。
            // 这时还按「取消」处理就错了 —— 已经落盘的文件不会被清掉，
            // 记录却停留在「已取消」，用户会以为没下成，而磁盘上躺着一个
            // 他找不到来源的文件。成功就是成功，此时按成功展示。
            if (cancelOnly && ok) {
                pendingCancelOnly = false;
                pendingDelete = null;
                ui.post(() -> {
                    Snackbar.show(findViewById(R.id.root),
                            getString(R.string.snack_cancel_too_late, task.title));
                    refreshAll();
                });
                return;
            }
            String id = pendingDelete;
            pendingDelete = null;
            ui.post(() -> finishPendingDelete(id));
            return;
        }
        // 终态要整列刷一次：这张卡的操作按钮要从「暂停」变成「继续」/「删除」，
        // 汇总里的占用大小也变了
        ui.post(this::refreshAll);
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    /** 整列重建。只在增删或终态时调用，不用于进度刷新。 */
    private void rebuild() {
        listBox.removeAllViews();
        rows.clear();

        List<DownloadTask> all = TaskStore.get().all();
        LayoutInflater inf = LayoutInflater.from(this);
        for (DownloadTask t : all) {
            View v = inf.inflate(R.layout.item_task, listBox, false);
            Row row = new Row(v, t);
            rows.add(row);
            listBox.addView(v);
        }
        refreshAll();
    }

    /** 只刷新已有视图的内容。 */
    private void refreshAll() {
        List<DownloadTask> all = TaskStore.get().all();

        // 记录数变了（有新增或被删），重建整列
        if (all.size() != rows.size()) {
            rebuild();
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            // all() 按时间倒序，listBox 里也是倒序加的，所以按下标一一对应
            if (!rows.get(i).task.id.equals(all.get(i).id)) {
                rebuild();
                return;
            }
        }

        for (Row r : rows) {
            updateRow(r.task);
        }
        updateSummary(all);
    }

    private void updateSummary(List<DownloadTask> all) {
        if (all.isEmpty()) {
            tvSummary.setVisibility(View.GONE);
            emptyBox.setVisibility(View.VISIBLE);
            btnClearDone.setVisibility(View.GONE);
            return;
        }
        tvSummary.setVisibility(View.VISIBLE);
        emptyBox.setVisibility(View.GONE);

        long used = 0L;
        int doneCount = 0;
        for (DownloadTask t : all) {
            if (t.status() == DownloadTask.STATUS_DONE) {
                doneCount++;
                // 完成的按落盘体积算
                used += outputBytes(t);
            } else if (t.hasPartial()) {
                used += WorkDir.usedBytes(this, t.id);
            } else if (t.isActive()) {
                used += WorkDir.usedBytes(this, t.id);
            }
        }
        tvSummary.setText(getString(R.string.downloads_summary, all.size(), Fmt.bytes(used)));
        btnClearDone.setVisibility(doneCount > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * 已完成任务的落盘体积。
     *
     * <p>半成品文件在完成时已经被清掉了，所以不能像未完成的那些一样去量工作
     * 目录 —— 那样会一律得到 0，汇总显示成「共占用 0 B」，用户会以为文件没保存。</p>
     */
    private long outputBytes(DownloadTask t) {
        String ref = t.fileRef();
        if (ref == null || ref.isEmpty()) {
            return 0L;
        }
        if (ref.startsWith("content://")) {
            // MediaStore / SAF 的条目：查 SIZE 列
            try (android.database.Cursor c = getContentResolver().query(
                    Uri.parse(ref), new String[]{
                            android.provider.OpenableColumns.SIZE
                    }, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    return c.getLong(0);
                }
            } catch (RuntimeException ignored) {
                // 查不到就不计入，不值得为此报错
            }
            return 0L;
        }
        java.io.File f = new java.io.File(ref);
        return f.exists() ? f.length() : 0L;
    }

    /** 更新一张卡上的全部内容。 */
    private void updateRow(DownloadTask t) {
        for (Row r : rows) {
            if (r.task.id.equals(t.id)) {
                r.bind(t);
                return;
            }
        }
    }

    // ==================================================================
    // 一条任务的卡片
    // ==================================================================

    private final class Row {
        final DownloadTask task;
        final TextView tvTitle;
        final TextView tvState;
        final TextView tvMeta;
        final ProgressBar pb;
        final TextView tvBytes;
        final TextView tvSpeed;
        final TextView tvEta;
        final NeuButton btnCancel;
        final NeuButton btnPrimary;
        final NeuButton btnDelete;

        Row(View v, DownloadTask task) {
            this.task = task;
            tvTitle = v.findViewById(R.id.tvTitle);
            tvState = v.findViewById(R.id.tvState);
            tvMeta = v.findViewById(R.id.tvMeta);
            pb = v.findViewById(R.id.pbProgress);
            tvBytes = v.findViewById(R.id.tvBytes);
            tvSpeed = v.findViewById(R.id.tvSpeed);
            tvEta = v.findViewById(R.id.tvEta);
            btnCancel = v.findViewById(R.id.btnCancel);
            btnPrimary = v.findViewById(R.id.btnPrimary);
            btnDelete = v.findViewById(R.id.btnDelete);

            // 实时速度是页面上最显眼的一行动态文字（项目当初就是为它才加的
            // 下载管理页），XML 里的 @color/scheme_0_primary 只是编译期兜底，
            // 不在这里跟着主题走的话会永远停在樱花粉上。
            tvSpeed.setTextColor(accentTextColor);

            btnPrimary.setOnClickListener(x -> onPrimary(task));
            btnCancel.setOnClickListener(x -> onCancel(task));
            btnDelete.setOnClickListener(x -> confirmDelete(task));
        }

        void bind(DownloadTask t) {
            tvTitle.setText(t.title);

            int st = t.status();
            tvState.setText(stateLabel(st));
            tvState.setTextColor(stateColor(st));

            // 副标题：来源 + 画质 + （失败时的原因）
            String meta = buildMeta(t);
            tvMeta.setText(meta);
            tvMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

            int pct = t.percent();
            boolean showBar = st == DownloadTask.STATUS_RUNNING
                    || st == DownloadTask.STATUS_PAUSED
                    || st == DownloadTask.STATUS_QUEUED
                    || (st == DownloadTask.STATUS_FAILED && t.doneBytes() > 0);

            if (showBar) {
                pb.setVisibility(View.VISIBLE);
                // 进度条的已完成段要跟着主色走：progress_download 里写死的是
                // scheme_0 兜底色，用户换成别的主题色后必须在这里覆盖。
                tintProgress(pb);
                if (pct >= 0) {
                    // 至少给 1%：下到 0.3% 时进度条完全不动，
                    // 看起来和「卡住了」没区别
                    pb.setProgress(Math.max(1, pct));
                } else {
                    pb.setProgress(0);
                }
            } else {
                pb.setVisibility(View.GONE);
            }

            // 数值行：已下 / 总量
            long done = t.doneBytes();
            long total = t.totalBytes();
            if (done > 0 || total > 0) {
                tvBytes.setVisibility(View.VISIBLE);
                if (total > 0) {
                    tvBytes.setText(Fmt.bytes(done) + " / " + Fmt.bytes(total));
                } else {
                    tvBytes.setText(Fmt.bytes(done));
                }
            } else {
                tvBytes.setVisibility(View.GONE);
            }

            // 速度：只在真的在跑、且速度已知时显示。
            // 暂停后残留一个「2.9 MB/s」会让用户以为还在下。
            long bps = t.speedBps();
            if (st == DownloadTask.STATUS_RUNNING && bps > 0) {
                tvSpeed.setVisibility(View.VISIBLE);
                tvSpeed.setText(Fmt.speed(bps));
            } else {
                tvSpeed.setVisibility(View.GONE);
            }

            // 剩余时间
            String eta = (st == DownloadTask.STATUS_RUNNING) ? Fmt.eta(done, total, bps) : "";
            if (!eta.isEmpty()) {
                tvEta.setVisibility(View.VISIBLE);
                tvEta.setText(getString(R.string.eta_prefix, eta));
            } else {
                tvEta.setVisibility(View.GONE);
            }

            bindCancelButton(btnCancel, t);
            bindPrimaryButton(btnPrimary, t);
            bindDeleteButton(btnDelete, t);
        }

        /**
         * 取消按钮：只在「这个任务确实还能被打断」时出现。
         *
         * <p>已完成 / 已取消 / 已失败的任务没有可取消的东西，按钮藏掉 ——
         * 一个点了没反应的按钮比没有按钮更让人困惑。</p>
         *
         * <p>排队中的任务主按钮本来就是「取消」，这里就不再重复一个，
         * 否则同一个动作在一张卡上出现两次，用户会以为是两件不同的事。</p>
         */
        private void bindCancelButton(NeuButton b, DownloadTask t) {
            int st = t.status();
            boolean show = st == DownloadTask.STATUS_RUNNING
                    || st == DownloadTask.STATUS_PAUSED;
            b.setVisibility(show ? View.VISIBLE : View.GONE);
            b.setEnabled(show);
        }

        /**
         * 主按钮的文字与可用性随状态变。
         *
         * <p>正在下 → 「暂停」；暂停/失败 → 「继续」；排队中 → 「取消」；
         * 已完成 → 不给主操作（隐藏），因为没有什么可做的；已取消 → 「重试」。</p>
         */
        private void bindPrimaryButton(NeuButton b, DownloadTask t) {
            switch (t.status()) {
                case DownloadTask.STATUS_RUNNING:
                    b.setVisibility(View.VISIBLE);
                    b.setEnabled(true);
                    b.setText(R.string.action_pause);
                    break;
                case DownloadTask.STATUS_QUEUED:
                    b.setVisibility(View.VISIBLE);
                    b.setEnabled(true);
                    b.setText(R.string.action_cancel);
                    break;
                case DownloadTask.STATUS_PAUSED:
                case DownloadTask.STATUS_FAILED:
                    b.setVisibility(View.VISIBLE);
                    b.setEnabled(true);
                    b.setText(R.string.action_resume);
                    break;
                case DownloadTask.STATUS_CANCELLED:
                    b.setVisibility(View.VISIBLE);
                    b.setEnabled(true);
                    b.setText(R.string.action_retry);
                    break;
                default:
                    // 已完成：没有可做的主操作
                    b.setVisibility(View.GONE);
                    break;
            }
        }

        private void bindDeleteButton(NeuButton b, DownloadTask t) {
            b.setVisibility(View.VISIBLE);
            b.setEnabled(true);
            b.setText(R.string.action_delete);
        }

        private String buildMeta(DownloadTask t) {
            StringBuilder sb = new StringBuilder();
            sb.append(t.youtube ? "YouTube" : "哔哩哔哩");
            if (!t.subtitle.isEmpty()) {
                sb.append(" · ").append(t.subtitle);
            }
            if (t.status() == DownloadTask.STATUS_DONE) {
                String loc = t.location();
                if (!loc.isEmpty()) {
                    sb.append("\n").append(getString(R.string.saved_to, loc));
                }
            } else if (!t.stage().isEmpty()) {
                sb.append(" · ").append(t.stage());
            }
            String err = t.error();
            if (!err.isEmpty() && t.status() == DownloadTask.STATUS_FAILED) {
                sb.append("\n").append(err);
            }
            return sb.toString();
        }
    }

    private String stateLabel(int st) {
        switch (st) {
            case DownloadTask.STATUS_QUEUED:    return getString(R.string.state_queued);
            case DownloadTask.STATUS_RUNNING:   return getString(R.string.state_running);
            case DownloadTask.STATUS_PAUSED:    return getString(R.string.state_paused);
            case DownloadTask.STATUS_DONE:      return getString(R.string.state_done);
            case DownloadTask.STATUS_FAILED:    return getString(R.string.state_failed);
            case DownloadTask.STATUS_CANCELLED: return getString(R.string.state_cancelled);
            default:                            return "";
        }
    }

    private int stateColor(int st) {
        switch (st) {
            case DownloadTask.STATUS_DONE:      return HyperTheme.success(this);
            case DownloadTask.STATUS_FAILED:    return HyperTheme.error(this);
            case DownloadTask.STATUS_RUNNING:   return accentTextColor;
            case DownloadTask.STATUS_CANCELLED: return HyperTheme.textTertiary(this);
            default:                            return HyperTheme.textSecondary(this);
        }
    }

    /**
     * 给进度条的两段着色。
     *
     * <p>{@code progress_download} 是一个 layer-list，两段的 drawable 实例是
     * 分开的 —— 直接给整个 ProgressBar 设 tint 会连轨道一起染上主色，
     * 那看起来就像「已经下完了」。所以取到 layer 再逐段设置。</p>
     */
    private void tintProgress(ProgressBar pb) {
        if (pb == null) {
            return;
        }
        android.graphics.drawable.Drawable d = pb.getProgressDrawable();
        if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld =
                    (android.graphics.drawable.LayerDrawable) d;
            android.graphics.drawable.Drawable bg =
                    ld.findDrawableByLayerId(android.R.id.background);
            if (bg != null) {
                bg.setTint(HyperTheme.divider(this));
            }
            android.graphics.drawable.Drawable fg =
                    ld.findDrawableByLayerId(android.R.id.progress);
            if (fg != null) {
                fg.setTint(primaryColor);
            }
        } else if (d != null) {
            // 不是 layer-list（极少数 ROM 会替换掉）就整体染主色，
            // 至少保证完成段是对的
            d.setTint(primaryColor);
        }
    }

    // ==================================================================
    // 操作
    // ==================================================================

    /**
     * 取消一条正在下或已暂停的下载。
     *
     * <p>取消和删除的区别是**记录留不留**：取消只停下并清掉半成品，
     * 记录变成「已取消」，之后还能点「重试」；删除是把这条记录整个抹掉。
     * 所以这里确认一次，把差别说清楚 —— 用户以为自己在「删」而实际
     * 只是「停」，回头看到列表里还留着一条会很困惑。</p>
     */
    private void onCancel(DownloadTask t) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.confirm_cancel_title);
        b.setMessage(getString(R.string.confirm_cancel_message, t.title,
                Fmt.bytes(WorkDir.usedBytes(this, t.id))));
        b.setPositiveButton(R.string.confirm_cancel_ok, (d, w) -> {
            // 同样要等 service 停稳再清残留，理由见 doDelete 的注释
            pendingDelete = t.id;
            pendingDeleteWithFile = false;
            pendingCancelOnly = true;
            DownloadService.cancel(this, t);
            Snackbar.show(findViewById(R.id.root),
                    getString(R.string.snack_task_cancelled, t.title));
        });
        b.setNegativeButton(android.R.string.cancel, null);
        b.show();
    }

    private void onPrimary(DownloadTask t) {
        switch (t.status()) {
            case DownloadTask.STATUS_RUNNING:
                DownloadService.pause(this, t);
                Snackbar.show(findViewById(R.id.root), getString(R.string.snack_task_paused, t.title));
                break;

            case DownloadTask.STATUS_QUEUED:
                DownloadService.cancel(this, t);
                Snackbar.show(findViewById(R.id.root), getString(R.string.snack_task_cancelled, t.title));
                break;

            case DownloadTask.STATUS_PAUSED:
            case DownloadTask.STATUS_FAILED:
            case DownloadTask.STATUS_CANCELLED:
                startResume(t);
                break;

            default:
                break;
        }
        refreshAll();
    }

    /**
     * 继续一条下载。
     *
     * <p>同时只允许一条在跑：这是服务本身的结构决定的（单线程顺序消费，
     * 共用一个前台通知）。让用户点下去之后什么都不发生是最糟的反馈，
     * 所以这里直接说清楚是谁在占着。</p>
     */
    private void startResume(DownloadTask t) {
        DownloadTask active = TaskStore.get().active();
        if (active != null && !active.id.equals(t.id)) {
            Snackbar.show(findViewById(R.id.root),
                    getString(R.string.snack_only_one_active, active.title));
            return;
        }
        // 开始前核对一下自选目录还在不在。等到下载跑完才发现目录失效，
        // 用户白白等一场。
        StorageDir.verifyCustom(this);
        DownloadService.resume(this, t);
        Snackbar.show(findViewById(R.id.root), getString(R.string.snack_task_resumed, t.title));
    }

    /**
     * 删除。问清楚「要不要连文件一起删」。
     *
     * <p>不默认删除文件是有意的：一条已完成的下载，用户想清掉的多半是列表里
     * 那一行，而不是硬盘上那个视频。真删错了没有回收站可以捞回来。</p>
     */
    private void confirmDelete(DownloadTask t) {
        int st = t.status();
        boolean finished = st == DownloadTask.STATUS_DONE;
        boolean partial = t.hasPartial() && WorkDir.usedBytes(this, t.id) > 0;

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.confirm_delete_title);

        if (finished) {
            String name = StorageDir.nameOf(this, t.fileRef());
            b.setMessage(getString(R.string.confirm_delete_message,
                    name.isEmpty() ? t.title : name));
            b.setPositiveButton(R.string.confirm_delete_file, (d, w) -> doDelete(t, true));
            b.setNegativeButton(R.string.confirm_delete_record_only, (d, w) -> doDelete(t, false));
        } else if (partial) {
            // 没下完的：说清楚残留占了多少 —— 用户才有依据决定要不要一起删
            b.setMessage(getString(R.string.confirm_delete_partial_message, t.title,
                    Fmt.bytes(WorkDir.usedBytes(this, t.id))));
            b.setPositiveButton(R.string.action_delete_partial, (d, w) -> doDelete(t, true));
            b.setNegativeButton(R.string.confirm_delete_record_only, (d, w) -> doDelete(t, false));
        } else if (t.isActive()) {
            // 正在下载：**不能**说「已经下载完成」（卡片上写着「下载中」），
            // 也不能给「只删记录」—— 半成品还在写，删了记录那些字节
            // 就永远没人认领了。这里只有「停止并删除」一个正经出口。
            b.setMessage(getString(R.string.confirm_delete_active_message, t.title));
            b.setPositiveButton(R.string.confirm_delete_active_ok, (d, w) -> doDelete(t, true));
            b.setNegativeButton(android.R.string.cancel, null);
        } else {
            // 队列里排队中、或者目录已经空了：没有任何文件需要处理
            b.setMessage(getString(R.string.confirm_delete_queued_message, t.title));
            b.setPositiveButton(R.string.action_delete, (d, w) -> doDelete(t, true));
            b.setNegativeButton(android.R.string.cancel, null);
        }
        b.show();
    }

    private void doDelete(DownloadTask t, boolean withFile) {
        // 正在跑的先把下载停掉，否则删完记录它还会继续往工作目录写。
        //
        // 这里是**异步**的：cancel() 只是发个意图，下载线程要过一会儿才看到
        // 停止标志。如果紧接着就删工作目录，那条线程极可能又建出新的
        // video.part —— 用户看到「已删除」，磁盘上却留着几十 MB 没人认领的
        // 碎片，而且再也没有记录能指向它。所以记下 intent，等 service 回报
        // 这条任务真的停了（onTaskFinished）再动手。
        if (t.isActive()) {
            pendingDelete = t.id;
            pendingDeleteWithFile = withFile;
            DownloadService.cancel(this, t);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_cancelling));
            return;
        }

        if (!withFile) {
            // 只删记录，但要清掉半成品 —— 记录没了，那些碎片就永远没人认领了
            TaskStore.get().remove(this, t, false);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_record_deleted));
            rebuild();
            return;
        }

        String ref = t.fileRef();
        boolean fileOk = true;
        if (!ref.isEmpty()) {
            fileOk = StorageDir.delete(this, ref);
        }

        if (!fileOk) {
            // 删不掉就**留着记录**：记录是用户唯一的线索，
            // 告诉他还剩一个文件没清掉，否则他会以为已经干净了
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_delete_failed),
                    null, null, R.drawable.ic_error);
            WorkDir.delete(this, t.id);
            TaskStore.get().markDirty(this);
            rebuild();
            return;
        }

        // WorkDir.delete 顺带清了残留，和「删记录」一起完成
        TaskStore.get().remove(this, t, false);
        Snackbar.show(findViewById(R.id.root),
                getString(ref.isEmpty() ? R.string.snack_partial_deleted
                        : R.string.snack_record_and_file_deleted));
        rebuild();
    }

    /**
     * 下载线程已经停稳，现在删才安全。
     *
     * <p>分两段而不是一次做完的理由见 {@link #doDelete} 的注释：只有
     * service 确认这条任务结束了，工作目录才不会被重新写出来。</p>
     */
    private void finishPendingDelete(String id) {
        DownloadTask t = TaskStore.get().byId(id);
        pendingDelete = null;
        boolean cancelOnly = pendingCancelOnly;
        pendingCancelOnly = false;
        boolean withFile = pendingDeleteWithFile;
        pendingDeleteWithFile = false;

        if (t == null) {
            rebuild();
            return;
        }
        // 先清目录：此时已经没有线程会再往里写
        WorkDir.delete(this, id);

        if (cancelOnly) {
            // 取消：**留下记录**。状态由 service 置成「已取消」，
            // 卡上会出现「重试」，用户改主意时不用重新解析一遍链接。
            //
            // 但残留文件刚刚被清掉了，字节数必须跟着归零。否则卡片会显示
            // 「已取消 · 已暂停 · 71.2 MB / 71.2 MB」，而汇总那里写着
            // 「共占用 0 B」—— 同一个屏幕上两个数字互相矛盾，用户没法判断
            // 那些字节到底还在不在。
            //
            // 已完成的任务绝不能走到这里：那样会把一个存好的文件写成
            //「已取消、已下 0 字节」。正常路径下 service 会拦住这种取消，
            // 这里是第二道闸 —— 记录已经是 DONE 就按成功展示。
            if (t.status() == DownloadTask.STATUS_DONE) {
                ui.post(() -> {
                    Snackbar.show(findViewById(R.id.root),
                            getString(R.string.snack_cancel_too_late, t.title));
                    rebuild();
                });
                return;
            }
            t.resetVideo();
            t.resetAudio();
            t.setStage(getString(R.string.stage_cancelled_clean));
            TaskStore.get().markDirty(this);
            rebuild();
            return;
        }

        if (!withFile) {
            TaskStore.get().remove(this, t, false);
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_record_deleted));
            rebuild();
            return;
        }

        String ref = t.fileRef();
        boolean fileOk = ref.isEmpty() || StorageDir.delete(this, ref);
        if (!fileOk) {
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_delete_failed),
                    null, null, R.drawable.ic_error);
            TaskStore.get().markDirty(this);
            rebuild();
            return;
        }
        TaskStore.get().remove(this, t, false);
        Snackbar.show(findViewById(R.id.root),
                getString(ref.isEmpty() ? R.string.snack_partial_deleted
                        : R.string.snack_record_and_file_deleted));
        rebuild();
    }

    private void confirmClearDone() {
        final List<DownloadTask> done = new ArrayList<>();
        for (DownloadTask t : TaskStore.get().all()) {
            if (t.status() == DownloadTask.STATUS_DONE) {
                done.add(t);
            }
        }
        if (done.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_clear_done)
                .setMessage(getString(R.string.confirm_clear_done_message, done.size()))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    for (DownloadTask t : done) {
                        // 只清记录：用户点的是「清除已完成」，不是「删除文件」。
                        // 真把几十个视频一起删掉是不可接受的。
                        TaskStore.get().remove(this, t, false);
                    }
                    Snackbar.show(findViewById(R.id.root),
                            getString(R.string.snack_cleared_done, done.size()));
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ==================================================================
    // 目录选择
    // ==================================================================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_DIR) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Snackbar.show(findViewById(R.id.root), getString(R.string.snack_dir_cancelled));
            return;
        }
        Uri tree = data.getData();
        try {
            // 必须带 READ|WRITE 两个 flag 才拿得到持久授权；只给一个的话
            // 系统只授权那一次，下次启动就失效了。
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // 有些 provider 不支持持久授权。仍然可以记下来，
            // 只是重启后可能失效 —— verifyCustom 会在开始时发现并退回默认。
            Log.w(TAG, "无法取得持久目录授权：" + e.getMessage());
        }
        StorageDir.setCustom(this, tree.toString(), labelOf(tree));
        Snackbar.show(findViewById(R.id.root),
                getString(R.string.snack_dir_set, StorageDir.customLabel(this)));
    }

    /** 把 tree URI 变成一个给人看的路径。 */
    private String labelOf(Uri tree) {
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
}
