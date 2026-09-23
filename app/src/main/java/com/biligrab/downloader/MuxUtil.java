package com.biligrab.downloader;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

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

    private static final String TAG = "BiliGrab/Mux";

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
        mux(video, audio, out, webm, null);
    }

    /**
     * @param abort 每个样本之间被问一次「还要继续吗」。返回 true 时立刻放弃并抛
     *              {@link Http.AbortedException}。
     *
     *              <p>合成一段 34 分钟的视频要几十秒，期间用户完全可能点「暂停」。
     *              不检查这个标志的话，界面会先弹一句「已暂停」，任务却继续跑完
     *              并变成「已完成」—— 同一个动作给出两个相反的答案，用户没法判断
     *              到底哪句是真的。</p>
     */
    public static void mux(File video, File audio, File out, boolean webm, Http.Abort abort)
            throws IOException {
        MediaExtractor vEx = null;
        MediaExtractor aEx = null;
        MediaMuxer muxer = null;
        // 这两个要在 try 之外声明：release() 之后还要用它们判断该验证哪几条轨
        MediaFormat vFmt = null;
        MediaFormat aFmt = null;
        RuntimeException stopFailure = null;
        try {
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
                copyTrack(vEx, vFmt, muxer, vOut, info, abort);
            }
            if (aFmt != null) {
                copyTrack(aEx, aFmt, muxer, aOut, info, abort);
            }

            // stop() 是这套 API 里最不可靠的一步，而且**它报错不代表失败**。
            //
            // OPPO / 一加 的定制 MPEG4Writer（OplusMPEG4Writer）会在文件其实
            // 已经写完整的情况下照样抛
            //     IllegalStateException: Error during stop(), muxer would have
            //     stopped already
            // 日志里跟着 stop() err: -1007（ERROR_MALFORMED）以及
            // 「Stop() called but track is not started or stopped」。
            // 实测此时产物是好的：moov 已落盘、轨数正确、样本数正确、
            // 各 box 长度之和与文件大小分毫不差，播放器可以正常播。
            //
            // 用户要的是「能播的文件」，不是「stop() 没报错的调用」。
            // 所以这里不把异常直接当失败，先记下来，等 release() 之后
            // 回头验证产物本身 —— 判断依据只有文件内容，与具体 ROM 无关。
            stopFailure = null;
            try {
                muxer.stop();
            } catch (RuntimeException e) {
                stopFailure = e;
            }
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

        // 验证必须放在 release() **之后**：moov 是在收尾阶段追加到文件尾的，
        // 有些实现在 release() 里才真正 flush 并关闭文件。在 stop() 一返回就
        // 去读，很可能读到的是「还没有 moov 的半成品」—— 那样正常完成的封装
        // 反而会被判成失败，比不验证更糟。
        if (stopFailure != null) {
            if (!verify(out, vFmt != null, aFmt != null)) {
                throw new IOException("封装收尾失败，产物不完整", stopFailure);
            }
            Log.w(TAG, "muxer.stop() 报错，但产物结构完整、可正常解析，按成功处理："
                    + out.getName(), stopFailure);
        }
    }

    private static boolean usable(File f) {
        return f != null && f.exists() && f.length() > 0;
    }

    /**
     * 只看产物本身，判断这次封装算不算成功。
     *
     * <p>正常收尾（{@code stop()} 返回）时 moov 必然已经写在文件里；抛异常时
     * 才是需要验证的情况。而恰恰是这种「后半段没跑完」的状态最危险：
     * mdat 可能有几 MB 数据，却是**没有 moov 的裸数据**，任何播放器都打不开。
     * 所以不能只看「文件非空」，必须真的解析一遍。</p>
     *
     * <p><b>为什么不只用 {@link MediaExtractor} 判断</b>：它是另一段系统代码，
     * 在出问题的这台 OPPO 上，同一份文件 {@code MediaMuxer} 写得出、
     * 系统播放器放得了、Python 的 box 解析器也读得全，唯独它返回「没有轨」。
     * 拿一个同样不可靠的组件去给另一个组件的产物背书，等于把失败从
     * 「写不出来」改成「读不出来」，用户还是拿不到文件。</p>
     *
     * <p>所以这里自己扫 box：MP4 的结构是公开固定的，
     * 「有没有 moov」「moov 里有没有 vide/soun 轨」都能直接读出来，
     * 不依赖任何厂商实现。MediaExtractor 只用作**加分项** —— 它认得出
     * 就一定没问题，它认不出也不作为否决依据。</p>
     */
    private static boolean verify(File out, boolean needVideo, boolean needAudio) {
        if (!usable(out)) {
            return false;
        }

        BoxScan scan = scanBoxes(out);
        boolean ok = scan.hasMoov && scan.mdatBytes > 0
                && (!needVideo || scan.hasVideoTrack)
                && (!needAudio || scan.hasAudioTrack);

        if (ok) {
            // 独立解析器也认得出，最好；认不出也不影响结论
            if (playableByExtractor(out, needVideo, needAudio)) {
                Log.i(TAG, "产物通过 box 结构与 MediaExtractor 双重校验：" + out.getName());
            } else {
                Log.i(TAG, "产物 box 结构完整（MediaExtractor 未识别，以结构为准）："
                        + out.getName() + " moov=" + scan.hasMoov
                        + " 视频轨=" + scan.hasVideoTrack + " 音频轨=" + scan.hasAudioTrack
                        + " mdat=" + scan.mdatBytes);
            }
            return true;
        }

        Log.w(TAG, "产物结构不完整：" + out.getName()
                + " moov=" + scan.hasMoov
                + " 视频轨=" + scan.hasVideoTrack
                + " 音频轨=" + scan.hasAudioTrack
                + " mdat=" + scan.mdatBytes);
        return false;
    }

    /** 自己扫出来的结构事实。 */
    private static final class BoxScan {
        boolean hasMoov;
        boolean hasVideoTrack;
        boolean hasAudioTrack;
        long mdatBytes;
    }

    /**
     * 遍历 MP4 的 box 树，记录结论。
     *
     * <p>只读 box 头，不做展开解析：{@code moov} 里的 {@code trak} →
     * {@code mdia} → {@code hdlr} 一共四层，全都按长度跳着走，
     * 不依赖任何字段偏移，因此对各家实现都成立。</p>
     *
     * <p>任何一步越界或长度不合法就立刻停下 —— 这本身就是「文件被截断」
     * 的证据，正是我们要识别的情况。</p>
     */
    private static BoxScan scanBoxes(File f) {
        BoxScan s = new BoxScan();
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long size = raf.length();
            long p = 0;
            while (p + 8 <= size) {
                raf.seek(p);
                byte[] head = new byte[16];
                if (raf.read(head) < 8) {
                    break;
                }
                long boxSize = readU32(head, 0);
                String type = new String(head, 4, 4, "US-ASCII");
                int hdrLen = 8;
                if (boxSize == 1) {
                    // 64 位长度（大文件才会用）
                    if (raf.length() < p + 16) {
                        break;
                    }
                    boxSize = readU64(head, 8);
                    hdrLen = 16;
                } else if (boxSize == 0) {
                    // 0 表示「一直到文件末尾」
                    boxSize = size - p;
                }
                if (boxSize < hdrLen || p + boxSize > size) {
                    break;
                }
                if ("moov".equals(type)) {
                    s.hasMoov = true;
                    scanMoov(raf, p + hdrLen, p + boxSize, s);
                } else if ("mdat".equals(type)) {
                    s.mdatBytes += boxSize - hdrLen;
                }
                p += boxSize;
            }
        } catch (Exception e) {
            Log.w(TAG, "box 扫描失败：" + f.getName(), e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                    // 关不掉就算了，只影响这一个文件的探测
                }
            }
        }
        return s;
    }

    /** 在 moov 里找 trak → mdia → hdlr，看它声明的是哪一类轨。 */
    private static void scanMoov(java.io.RandomAccessFile raf, long start, long end, BoxScan s) {
        long p = start;
        while (p + 8 <= end) {
            long[] box = readBoxHeader(raf, p, end);
            if (box == null) {
                return;
            }
            long boxSize = box[0];
            String type = boxType(raf, p);
            if ("trak".equals(type)) {
                scanTrak(raf, p + box[1], p + boxSize, s);
            }
            p += boxSize;
        }
    }

    /** 找到这条 trak 的 hdlr 类型，记下它是视频还是音频。 */
    private static void scanTrak(java.io.RandomAccessFile raf, long start, long end, BoxScan s) {
        long p = start;
        while (p + 8 <= end) {
            long[] box = readBoxHeader(raf, p, end);
            if (box == null) {
                return;
            }
            long boxSize = box[0];
            if ("mdia".equals(boxType(raf, p))) {
                long q = p + box[1];
                while (q + 8 <= p + boxSize) {
                    long[] b2 = readBoxHeader(raf, q, p + boxSize);
                    if (b2 == null) {
                        break;
                    }
                    if ("hdlr".equals(boxType(raf, q))) {
                        // hdlr 布局：size(4) type(4) version+flags(4) pre_defined(4)
                        //            handler_type(4) ...
                        // handler_type 是**裸的字段值**，不是 box 头。
                        // 用 readAscii 直接读那个偏移；用 boxType 会多跳 4 字节
                        // （它内部固定 seek 到 p+4 去取 box 类型），读出来是垃圾，
                        // 结果两条轨都判成不存在，好文件被当成坏的。
                        String h = readAscii(raf, q + b2[1] + 8);
                        if ("vide".equals(h)) {
                            s.hasVideoTrack = true;
                        } else if ("soun".equals(h)) {
                            s.hasAudioTrack = true;
                        }
                    }
                    q += b2[0];
                }
                return;
            }
            p += boxSize;
        }
    }

    /** @return {boxSize, headerLength}，不合法时返回 null */
    private static long[] readBoxHeader(java.io.RandomAccessFile raf, long p, long limit) {
        try {
            raf.seek(p);
            byte[] head = new byte[16];
            if (raf.read(head) < 8) {
                return null;
            }
            long boxSize = readU32(head, 0);
            int hdrLen = 8;
            if (boxSize == 1) {
                boxSize = readU64(head, 8);
                hdrLen = 16;
            } else if (boxSize == 0) {
                boxSize = limit - p;
            }
            if (boxSize < hdrLen || p + boxSize > limit) {
                return null;
            }
            return new long[]{boxSize, hdrLen};
        } catch (Exception e) {
            return null;
        }
    }

    /** 读偏移处的 4 字节 box 类型。 */
    private static String boxType(java.io.RandomAccessFile raf, long p) {
        return readAscii(raf, p + 4);
    }

    /** 读任意偏移的 4 字节 ASCII。用于读取不是 box 头的裸字段值。 */
    private static String readAscii(java.io.RandomAccessFile raf, long p) {
        try {
            raf.seek(p);
            byte[] t = new byte[4];
            if (raf.read(t) < 4) {
                return "";
            }
            return new String(t, "US-ASCII");
        } catch (Exception e) {
            return "";
        }
    }

    private static long readU32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static long readU64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    /** 独立解析器能否识别 —— 仅用于记录更乐观的证据，不作否决。 */
    private static boolean playableByExtractor(File out, boolean needVideo, boolean needAudio) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(out.getAbsolutePath());
            boolean v = firstTrack(ex, "video/") >= 0;
            boolean a = firstTrack(ex, "audio/") >= 0;
            if (needVideo && !v) {
                return false;
            }
            return !needAudio || a;
        } catch (Exception e) {
            return false;
        } finally {
            releaseQuietly(ex);
        }
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
                                  int outTrack, MediaCodec.BufferInfo info, Http.Abort abort)
            throws IOException {
        int maxInput = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : MIN_BUFFER;
        int cap = Math.max(MIN_BUFFER, Math.min(MAX_BUFFER, maxInput * 2));
        ByteBuffer buf = ByteBuffer.allocate(cap);

        while (true) {
            // 每个样本之间查一次。放在这里而不是循环外：合成大文件时
            // 一次 copyTrack 要跑几万轮，只在开头查等于没查。
            if (abort != null && abort.shouldStop()) {
                throw new Http.AbortedException(0);
            }
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
