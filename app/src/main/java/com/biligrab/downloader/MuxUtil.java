package com.biligrab.downloader;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 把 DASH 分离出的视频轨与音频轨合成单个 MP4。
 *
 * <p>使用 Android 系统自带的 {@link MediaMuxer}，因此 APK 不必打包 ffmpeg，
 * 体积可控制在 1 MB 以内。{@code video} 为 {@code null} 时退化为纯音频封装。</p>
 */
public final class MuxUtil {

    private static final int MIN_BUFFER = 1 << 20;
    private static final int MAX_BUFFER = 16 << 20;

    private MuxUtil() {
    }

    /**
     * @param video 含视频轨的源文件，可为 {@code null}
     * @param audio 含音频轨的源文件，可为 {@code null}
     * @param out   输出文件
     */
    public static void mux(File video, File audio, File out) throws IOException {
        mux(video, audio, out, false);
    }

    /**
     * @param webm 输出 WebM 而不是 MP4。
     *
     *             <p>这个开关不是风格偏好，而是硬性要求：VP9 / AV1 装不进
     *             MP4 —— 实测在 Android 12 上 {@code addTrack} 会直接抛
     *             {@code IllegalStateException: Failed to add the track to the
     *             muxer}。同理 AAC 也装不进 WebM。所以容器必须和编码配对：
     *             {@code H.264 + AAC → MP4}、{@code VP9/AV1 + Opus → WebM}。</p>
     *
     *             <p>YouTube 的 1440P 与 2160P 只有 VP9 / AV1，没有这条路径
     *             就等于那两档永远拿不到。</p>
     */
    public static void mux(File video, File audio, File out, boolean webm) throws IOException {
        MediaExtractor vEx = null;
        MediaExtractor aEx = null;
        MediaMuxer muxer = null;
        try {
            MediaFormat vFmt = null;
            MediaFormat aFmt = null;

            if (usable(video)) {
                vEx = new MediaExtractor();
                vEx.setDataSource(video.getAbsolutePath());
                int t = firstTrack(vEx, "video/");
                if (t >= 0) {
                    vFmt = vEx.getTrackFormat(t);
                    vEx.selectTrack(t);
                }
            }
            if (usable(audio)) {
                aEx = new MediaExtractor();
                aEx.setDataSource(audio.getAbsolutePath());
                int t = firstTrack(aEx, "audio/");
                if (t >= 0) {
                    aFmt = aEx.getTrackFormat(t);
                    aEx.selectTrack(t);
                }
            }

            if (vFmt == null && aFmt == null) {
                throw new IOException("源文件中没有找到可用的音视频轨");
            }

            muxer = new MediaMuxer(out.getAbsolutePath(),
                    webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                         : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // addTrack 在容器不收这个编码时抛的是 IllegalStateException
            // 而不是 IOException。在这里就地转成 IOException，
            // 上层才能用同一套错误处理，也能给用户一句能看懂的话。
            int vOut;
            int aOut;
            try {
                vOut = vFmt != null ? muxer.addTrack(vFmt) : -1;
                aOut = aFmt != null ? muxer.addTrack(aFmt) : -1;
            } catch (IllegalArgumentException | IllegalStateException e) {
                String which = webm ? "WebM" : "MP4";
                String vMime = vFmt == null ? "-" : String.valueOf(vFmt.getString(MediaFormat.KEY_MIME));
                String aMime = aFmt == null ? "-" : String.valueOf(aFmt.getString(MediaFormat.KEY_MIME));
                throw new IOException("系统封装器不接受这个编码组合（" + which
                        + "：视频 " + vMime + " / 音频 " + aMime + "）", e);
            }

            if (vFmt != null && vFmt.containsKey(MediaFormat.KEY_ROTATION)) {
                try {
                    muxer.setOrientationHint(vFmt.getInteger(MediaFormat.KEY_ROTATION));
                } catch (RuntimeException ignored) {
                    // 少数设备不支持设置旋转提示
                }
            }

            muxer.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            if (vFmt != null) {
                copyTrack(vEx, vFmt, muxer, vOut, info);
            }
            if (aFmt != null) {
                copyTrack(aEx, aFmt, muxer, aOut, info);
            }

            muxer.stop();
        } finally {
            releaseQuietly(vEx);
            releaseQuietly(aEx);
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                    // 未 start 或已 release
                }
            }
        }
    }

    private static boolean usable(File f) {
        return f != null && f.exists() && f.length() > 0;
    }

    /** MediaExtractor 只实现 AutoCloseable，这里统一兜底释放。 */
    private static void releaseQuietly(MediaExtractor ex) {
        if (ex == null) {
            return;
        }
        try {
            ex.release();
        } catch (RuntimeException ignored) {
            // 已释放
        }
    }

    private static void copyTrack(MediaExtractor ex, MediaFormat fmt, MediaMuxer muxer,
                                  int outTrack, MediaCodec.BufferInfo info) {
        int maxInput = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : MIN_BUFFER;
        int cap = Math.max(MIN_BUFFER, Math.min(MAX_BUFFER, maxInput * 2));
        ByteBuffer buf = ByteBuffer.allocate(cap);

        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) {
                break;
            }
            long pts = ex.getSampleTime();
            if (pts < 0) {
                break;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = pts;
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            try {
                muxer.writeSampleData(outTrack, buf, info);
            } catch (RuntimeException e) {
                // 个别损坏样本直接跳过，避免整段失败
                ex.advance();
                continue;
            }
            ex.advance();
        }
    }

    private static int firstTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** 校验产物确实含视频轨，用于下载后自检。 */
    public static boolean hasVideoTrack(File f) {
        if (!usable(f)) {
            return false;
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            return firstTrack(ex, "video/") >= 0;
        } catch (IOException e) {
            return false;
        } finally {
            releaseQuietly(ex);
        }
    }
}
