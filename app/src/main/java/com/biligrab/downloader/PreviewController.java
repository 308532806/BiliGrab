package com.biligrab.downloader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 预览播放器。
 *
 * <p>用平台的 {@link MediaPlayer} + {@link SurfaceView} 自己拼，而不是
 * {@code VideoView}。原因很具体：</p>
 *
 * <ul>
 *   <li>B 站 CDN <b>强制校验 Referer</b>，不带就是 403（实测）。所以必须能传
 *       请求头。{@code VideoView.setVideoURI(Uri, Map)} 在 API 34 已废弃，
 *       新系统上请求头可能被丢掉；{@code MediaPlayer.setDataSource(Context,
 *       Uri, Map)} 是稳定可用的那个。</li>
 *   <li>控制条要按项目自己的 Material 3 样式画，{@code MediaController}
 *       是不可定制的外观。</li>
 *   <li>进度条要能拖动 —— 预览的意义就是跳到任意位置看一眼。</li>
 * </ul>
 *
 * <p>播放地址由宿主懒加载：用户第一次点播放时才去请求，解析稿件时不请求，
 * 免得为了看一眼封面白跑一次接口。</p>
 */
public final class PreviewController {

    /** 进度条内部刻度。用 1000 而不是直接拿毫秒，避免超长视频溢出 SeekBar 的 int。 */
    private static final int SEEK_MAX = 1000;
    /** 进度刷新间隔。太小会一直重绘，太大进度条会一跳一跳。 */
    private static final long TICK_MS = 400L;

    /** 预览容器高度上限占屏幕的比例：竖屏视频不能把整屏吃光。 */
    private static final float MAX_HEIGHT_RATIO = 0.6f;

    public interface Listener {
        /**
         * 用户要求播放，但还没有播放地址。宿主负责去取，取到后调用
         * {@link #sourceReady}，失败调用 {@link #sourceFailed}。
         */
        void onNeedSource();

        /** 播放失败。宿主要不要提示由它决定 —— 预览失败不影响下载。 */
        void onError(String message);
    }

    private final Activity host;

    private final View box;
    private final ImageView cover;
    private final SurfaceView surface;
    private final ImageButton btnPlay;
    private final View loading;
    private final View bar;
    private final ImageButton btnPlayPause;
    private final SeekBar seek;
    private final TextView time;
    private final View errorBox;
    private final TextView errorText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            syncPosition();
            if (isPlaying()) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private Listener listener;

    private MediaPlayer player;
    private SurfaceHolder holder;
    private boolean surfaceReady;

    /** 已交给播放器的地址。同一个地址重复调用不会重播。 */
    private String loadedUrl;
    private long durationMs;

    /** 地址还没到就先记下来，Surface 一就绪立刻准备。 */
    private String pendingUrl;
    private long pendingDurationMs;
    /** 准备完成后要跳到的位置，-1 表示从头播。 */
    private int pendingSeekMs = -1;

    private boolean fetching;
    private boolean dragging;
    private boolean prepared;
    private boolean released;

    public PreviewController(Activity host) {
        this.host = host;

        this.box = host.findViewById(R.id.previewBox);
        this.cover = host.findViewById(R.id.ivCover);
        this.surface = host.findViewById(R.id.videoView);
        this.btnPlay = host.findViewById(R.id.btnPlay);
        this.loading = host.findViewById(R.id.previewLoading);
        this.bar = host.findViewById(R.id.previewBar);
        this.btnPlayPause = host.findViewById(R.id.btnPlayPause);
        this.seek = host.findViewById(R.id.seekPreview);
        this.time = host.findViewById(R.id.tvPreviewTime);
        this.errorBox = host.findViewById(R.id.previewError);
        this.errorText = host.findViewById(R.id.tvPreviewError);

        // SurfaceView 是独立合成层，不跟随父容器的 clipToOutline，
        // 所以圆角要显式装在容器上
        box.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        box.setClipToOutline(true);

        btnPlay.setOnClickListener(v -> requestPlay());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());

        holder = surface.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                if (pendingUrl != null) {
                    String url = pendingUrl;
                    long ms = pendingDurationMs;
                    pendingUrl = null;
                    open(url, ms);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
                if (player != null) {
                    player.setDisplay(h);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    // 拖动过程中只更新时间文字，不真的跳 —— 否则会疯狂 seek
                    time.setText(timeLabel(progressToMs(progress)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (player != null && prepared) {
                    player.seekTo(progressToMs(sb.getProgress()));
                    handler.removeCallbacks(ticker);
                    handler.post(ticker);
                }
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ==================================================================
    //  封面
    // ==================================================================

    public void setCover(Bitmap bitmap) {
        if (bitmap == null) {
            cover.setImageDrawable(null);
            return;
        }
        cover.setImageBitmap(bitmap);
    }

    // ==================================================================
    //  播放控制
    // ==================================================================

    public boolean isPrepared() {
        return prepared;
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 用户点了播放键。没有地址就先向宿主要素，有地址就直接播。 */
    public void requestPlay() {
        if (released) {
            return;
        }
        errorBox.setVisibility(View.GONE);

        if (prepared) {
            play();
            return;
        }
        if (loadedUrl != null) {
            // 地址已经有了，只是还没准备好（多半是 Surface 重建过），重新走一遍
            startLoading(host.getString(R.string.preview_fetching));
            open(loadedUrl, durationMs);
            return;
        }
        if (fetching) {
            return;
        }
        fetching = true;
        startLoading(host.getString(R.string.preview_fetching));
        if (listener != null) {
            listener.onNeedSource();
        }
    }

    /** 宿主取到地址后回调。 */
    public void sourceReady(String url, long durationMs) {
        if (released) {
            return;
        }
        fetching = false;
        this.durationMs = durationMs;
        if (!surfaceReady) {
            // 等 surfaceCreated
            pendingUrl = url;
            pendingDurationMs = durationMs;
            return;
        }
        open(url, durationMs);
    }

    /** 宿主取地址失败。 */
    public void sourceFailed() {
        fetching = false;
        fail(null);
    }

    public void togglePlayPause() {
        if (isPlaying()) {
            pause();
        } else if (prepared) {
            play();
        } else {
            requestPlay();
        }
    }

    public void play() {
        if (player == null || !prepared) {
            requestPlay();
            return;
        }
        try {
            player.start();
            btnPlayPause.setImageResource(R.drawable.ic_pause);
            btnPlayPause.setContentDescription(host.getString(R.string.cd_pause));
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } catch (IllegalStateException e) {
            fail(e.getMessage());
        }
    }

    /** 暂停。进入后台时也会调，避免在后台继续出声。 */
    public void pause() {
        handler.removeCallbacks(ticker);
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (IllegalStateException ignored) {
                // 播放器状态已经不对了，下面照常更新图标即可
            }
        }
        btnPlayPause.setImageResource(R.drawable.ic_play);
        btnPlayPause.setContentDescription(host.getString(R.string.cd_play));
    }

    /** 解析出了新稿件：停掉当前播放，退回封面态。 */
    public void reset() {
        handler.removeCallbacks(ticker);
        releasePlayer();
        loadedUrl = null;
        pendingUrl = null;
        pendingSeekMs = -1;
        durationMs = 0;
        fetching = false;
        dragging = false;
        prepared = false;

        cover.setVisibility(View.VISIBLE);
        bar.setVisibility(View.GONE);
        errorBox.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);
        btnPlay.setVisibility(View.VISIBLE);
        btnPlay.setImageResource(R.drawable.ic_play);
        btnPlay.setContentDescription(host.getString(R.string.cd_play));
        seek.setProgress(0);
        time.setText("");
    }

    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    // ==================================================================
    //  内部
    // ==================================================================

    private void open(String url, long declaredDurationMs) {
        releasePlayer();
        errorBox.setVisibility(View.GONE);
        loadedUrl = url;

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());

            // 必须带 Referer 和桌面 UA：CDN 不带 Referer 直接返回 403（实测）
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", Http.REFERER);
            headers.put("User-Agent", Http.UA);
            headers.put("Accept", "*/*");
            mp.setDataSource(host, Uri.parse(url), headers);

            mp.setDisplay(holder);
            mp.setScreenOnWhilePlaying(true);

            mp.setOnPreparedListener(p -> {
                if (p != player) {
                    return;
                }
                prepared = true;
                if (declaredDurationMs > 0) {
                    durationMs = declaredDurationMs;
                } else {
                    durationMs = p.getDuration();
                }
                applyAspect(p.getVideoWidth(), p.getVideoHeight());

                // 封面盖在 Surface 上表示"还没在播"，准备好就把它撤掉
                cover.setVisibility(View.GONE);
                loading.setVisibility(View.GONE);
                btnPlay.setVisibility(View.GONE);
                bar.setVisibility(View.VISIBLE);
                seek.setProgress(0);
                time.setText(timeLabel(0));

                if (pendingSeekMs >= 0) {
                    p.seekTo(pendingSeekMs);
                    pendingSeekMs = -1;
                }
                p.start();
                btnPlayPause.setImageResource(R.drawable.ic_pause);
                btnPlayPause.setContentDescription(host.getString(R.string.cd_pause));
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            });

            mp.setOnCompletionListener(p -> {
                handler.removeCallbacks(ticker);
                seek.setProgress(SEEK_MAX);
                time.setText(timeLabel(durationMs));
                btnPlayPause.setImageResource(R.drawable.ic_play);
                btnPlayPause.setContentDescription(host.getString(R.string.cd_play));
            });

            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player) {
                    fail(null);
                }
                return true;
            });

            mp.setOnInfoListener((p, what, extra) -> {
                if (p != player) {
                    return false;
                }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    loading.setVisibility(View.VISIBLE);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    loading.setVisibility(View.GONE);
                }
                return true;
            });

            player = mp;
            loading.setVisibility(View.VISIBLE);
            btnPlay.setVisibility(View.GONE);
            mp.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            fail(e.getMessage());
        }
    }

    private void fail(String detail) {
        handler.removeCallbacks(ticker);
        releasePlayer();
        prepared = false;

        cover.setVisibility(View.VISIBLE);
        bar.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);
        btnPlay.setVisibility(View.VISIBLE);
        btnPlay.setImageResource(R.drawable.ic_play);

        // 预览失败不阻断下载，所以这里只说"预览不行、下载照旧"，
        // 不把底层异常抛给用户
        errorText.setText(R.string.preview_failed);
        errorBox.setVisibility(View.VISIBLE);
        if (listener != null) {
            listener.onError(detail);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.setDisplay(null);
            } catch (IllegalStateException ignored) {
                // 已经出错或已释放，忽略
            }
            try {
                player.reset();
                player.release();
            } catch (IllegalStateException ignored) {
                // 同上
            }
            player = null;
        }
    }

    private void startLoading(String label) {
        errorBox.setVisibility(View.GONE);
        // 取地址期间保留封面，比留一块黑底好看，也让用户知道还没开始
        cover.setVisibility(View.VISIBLE);
        btnPlay.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        time.setText(label);
    }

    private void syncPosition() {
        if (player == null || !prepared || dragging) {
            return;
        }
        try {
            int pos = player.getCurrentPosition();
            int dur = durationMs > 0 ? (int) Math.min(durationMs, Integer.MAX_VALUE)
                    : player.getDuration();
            if (dur > 0) {
                seek.setProgress((int) ((long) pos * SEEK_MAX / dur));
            }
            time.setText(timeLabel(pos));
        } catch (IllegalStateException ignored) {
            // 播放器正在切换状态，下一拍再刷
        }
    }

    private int progressToMs(int progress) {
        long dur = durationMs;
        if (dur <= 0 && player != null) {
            try {
                dur = player.getDuration();
            } catch (IllegalStateException ignored) {
                dur = 0;
            }
        }
        return (int) (dur * Math.max(0, Math.min(SEEK_MAX, progress)) / SEEK_MAX);
    }

    private String timeLabel(long positionMs) {
        return host.getString(R.string.preview_time, fmt(positionMs), fmt(durationMs));
    }

    private static String fmt(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /**
     * 按视频实际比例调整预览容器高度。
     *
     * <p>固定高度会让竖屏视频被裁掉大半，完全自适应又会让竖屏视频占满整屏、
     * 把下载区顶到看不见。所以按比例算，但设一个上限。</p>
     */
    private void applyAspect(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int boxWidth = box.getWidth();
        if (boxWidth <= 0) {
            box.post(() -> applyAspect(videoWidth, videoHeight));
            return;
        }
        float ratio = (float) videoHeight / (float) videoWidth;

        int maxHeight = (int) (host.getResources().getDisplayMetrics().heightPixels
                * MAX_HEIGHT_RATIO);
        int target = Math.min((int) (boxWidth * ratio), maxHeight);
        target = Math.max(target, host.getResources().getDimensionPixelSize(R.dimen.cover_height) / 2);

        if (target != box.getLayoutParams().height) {
            box.getLayoutParams().height = target;
            box.requestLayout();
        }
    }
}
