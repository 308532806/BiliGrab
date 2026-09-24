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

    /**
     * 时间戳回退到这个幅度以内就当成抖动拉平，超过就丢弃。
     *
     * <p>50 毫秒：一帧 AAC 是 23 毫秒，视频一帧在 16-40 毫秒之间。
     * 这个量级之内的错位挪一下听不出来、看不出来；再大就是时间轴断了，
     * 硬压会变成爆音。</p>
     */
    private static final long MAX_JITTER_US = 50000L;

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
        // 合成分段计时。在 try 之外声明：结果要在 try/finally 之后才打日志
        // （那时才拿得到收尾耗时）。
        long t0 = 0L;
        long tVideo = 0L;
        long tAudio = 0L;
        long vWritten = 0L;
        long aWritten = 0L;
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

            // 分段计时。合成是整条链路里最慢的一段（实测 3.3-3.9 秒，
            // 占总时长的一半以上），但它内部又有四件事：建轨、抄视频样、
            // 抄音频样、收尾。不知道哪一件占大头就去优化，几乎一定改错地方。
            t0 = System.currentTimeMillis();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            if (vFmt != null) {
                // 视频不强制单调：H.264 带 B 帧时呈现时间本来就可能乱序，
                // MPEG4Writer 支持这一点（它报错的只有音频轨）。
                vWritten = copyTrack(vEx, vFmt, muxer, vOut, info, abort, false);
            }
            tVideo = System.currentTimeMillis();
            if (aFmt != null) {
                // 音频要丢掉「贴在前一帧身上」的重复帧，见 copyTrack 的注释。
                //
                // 只对 AAC 这么做。Opus / Vorbis（YouTube 的 WebM 音轨）
                // 帧长本来就是变化的，一帧 20 毫秒、下一帧 10 毫秒都正常，
                // 拿「典型间距的一半」去卡会把好帧当异常扔掉。它们的
                // 封装器也不像 OplusMPEG4Writer 那样一遇乱序就判死整条轨。
                boolean fixedLength = isAac(aFmt);
                aWritten = copyTrack(aEx, aFmt, muxer, aOut, info, abort, fixedLength);
            }
            tAudio = System.currentTimeMillis();

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
        //
        // 而且**每次都验**，不只验 stop() 报错的那次。真机上的坏产物是这样
        // 来的：音频轨中途被封装器判死，收尾时 moov 是在「这条轨已经死了」
        // 的状态下写的，于是它没有样本表。文件 29 MB、有 moov、有 soun 轨，
        // 但提取器读出来 0 条轨、播放器打不开 —— 而它被标成了「已完成」。
        // 只靠 stop() 抛不抛异常来触发验证，等于把「能不能发现」交给厂商实现
        // 决定：这台 OPPO 抛异常因此能被发现，别的 ROM 不抛就永远发现不了。
        // 读几个 box 头的代价相对一次几十 MB 的下载可以忽略。
        if (!verify(out, vFmt != null, aFmt != null, webm)) {
            if (stopFailure != null) {
                throw new IOException("封装收尾失败，产物不完整", stopFailure);
            }
            throw new IOException("封装出来的文件不完整（缺少样本表），已丢弃");
        }
        if (stopFailure != null) {
            Log.w(TAG, "muxer.stop() 报错，但产物结构完整、可正常播放，按成功处理："
                    + out.getName(), stopFailure);
        }

        // 内部耗时只在真正跑过合成时有意义（上面几处提前 return 的路径
        // 不会走到这里）
        if (tAudio > 0L) {
            long tStop = System.currentTimeMillis();
            Log.i(TAG, "[合成明细] 视频 " + (tVideo - t0) + "ms（" + vWritten
                    + " 样本） 音频 " + (tAudio - tVideo) + "ms（" + aWritten
                    + " 样本） 收尾 " + (tStop - tAudio) + "ms");
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
    private static boolean verify(File out, boolean needVideo, boolean needAudio, boolean webm) {
        if (!usable(out)) {
            return false;
        }

        BoxScan scan = scanBoxes(out);
        // 轨存在只是必要条件，**样本数才是**能播的充分证据。
        // 见 countStsz 的注释：真机上出现过 moov 在、soun 轨在、mdat 有 29 MB，
        // 但样本表是空的坏文件 —— 那种文件提取器读出来 0 条轨，播放器打不开。
        //
        // 判据写成「!= 0」而不是「> 0」是刻意的：-1 表示**没读到**
        // （box 结构超出我的解析能力），那种情况下无从判断，
        // 不能因此把一次正常的封装判成失败。只有确确实实读到 0 才算坏。
        boolean videoOk = !needVideo || (scan.hasVideoTrack && scan.videoSamples != 0);
        boolean audioOk = !needAudio || (scan.hasAudioTrack && scan.audioSamples != 0);
        boolean ok = webm || (scan.hasMoov && scan.mdatBytes > 0 && videoOk && audioOk);
        if (webm) {
            // WebM 是 EBML，不是 MP4 box 树，扫不了；只能靠提取器认。
            ok = playableByExtractor(out, needVideo, needAudio);
        }

        if (ok) {
            // 独立解析器也认得出，最好；认不出也不影响结论
            if (playableByExtractor(out, needVideo, needAudio)) {
                Log.i(TAG, "产物通过 box 结构与 MediaExtractor 双重校验：" + out.getName());
            } else {
                Log.i(TAG, "产物 box 结构完整（MediaExtractor 未识别，以结构为准）："
                        + out.getName() + " moov=" + scan.hasMoov
                        + " 视频轨=" + scan.hasVideoTrack + "(" + scan.videoSamples + " 样本)"
                        + " 音频轨=" + scan.hasAudioTrack + "(" + scan.audioSamples + " 样本)"
                        + " mdat=" + scan.mdatBytes);
            }
            return true;
        }

        Log.w(TAG, "产物结构不完整：" + out.getName()
                + " moov=" + scan.hasMoov
                + " 视频轨=" + scan.hasVideoTrack + "(" + scan.videoSamples + " 样本)"
                + " 音频轨=" + scan.hasAudioTrack + "(" + scan.audioSamples + " 样本)"
                + " mdat=" + scan.mdatBytes);
        return false;
    }

    /** 自己扫出来的结构事实。 */
    private static final class BoxScan {
        boolean hasMoov;
        boolean hasVideoTrack;
        boolean hasAudioTrack;
        long mdatBytes;
        /** 视频轨里声明的样本数，-1 表示没读到样本表。 */
        long videoSamples = -1;
        /** 音频轨里声明的样本数，-1 表示没读到样本表。 */
        long audioSamples = -1;
        /** moov 里声明的最长时长（微秒），-1 表示没读到。 */
        long durationUs = -1;
        /** moof 片段数。分片 MP4（fMP4）的样本在这里，不在 moov 里。 */
        long fragments;
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
                } else if ("moof".equals(type)) {
                    // 分片 MP4：样本分布在每个 moof 之后的 mdat 里。
                    // 「仅音频」路径要认这种情况，否则会把一份正确的
                    // 分片文件判成坏的（它的 moov 里没有样本表）。
                    s.fragments++;
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
                long qEnd = p + boxSize;
                boolean isVideo = false;
                boolean isAudio = false;
                long samples = -1;
                long dur = -1;
                while (q + 8 <= qEnd) {
                    long[] b2 = readBoxHeader(raf, q, qEnd);
                    if (b2 == null) {
                        break;
                    }
                    String t2 = boxType(raf, q);
                    if ("hdlr".equals(t2)) {
                        // hdlr 布局：size(4) type(4) version+flags(4) pre_defined(4)
                        //            handler_type(4) ...
                        // handler_type 是**裸的字段值**，不是 box 头。
                        // 用 readAscii 直接读那个偏移；用 boxType 会多跳 4 字节
                        // （它内部固定 seek 到 p+4 去取 box 类型），读出来是垃圾，
                        // 结果两条轨都判成不存在，好文件被当成坏的。
                        String h = readAscii(raf, q + b2[1] + 8);
                        if ("vide".equals(h)) {
                            isVideo = true;
                        } else if ("soun".equals(h)) {
                            isAudio = true;
                        }
                    } else if ("mdhd".equals(t2)) {
                        dur = readMdhdDuration(raf, q, b2[1]);
                    } else if ("minf".equals(t2)) {
                        samples = countStsz(raf, q + b2[1], q + b2[0]);
                    }
                    q += b2[0];
                }
                if (isVideo) {
                    s.hasVideoTrack = true;
                    if (samples >= 0) {
                        s.videoSamples = samples;
                    }
                } else if (isAudio) {
                    s.hasAudioTrack = true;
                    if (samples >= 0) {
                        s.audioSamples = samples;
                    }
                }
                if (dur > s.durationUs) {
                    s.durationUs = dur;
                }
                return;
            }
            p += boxSize;
        }
    }

    /**
     * 读 stsz 里声明的样本数。
     *
     * <h3>为什么非要读这个</h3>
     * <p>只看「有没有 moov、有没有 soun 轨」是不够的 —— 这次真机上就出现了
     * 一个**通过全部既有检查的坏文件**：mdat 有 29 MB、moov 在、soun 轨在，
     * 但 moov 里根本没有样本表（stsz/stco/stts 全缺），moov 只有 486 字节。
     * 封装的音频轨在中途被 {@code OplusMPEG4Writer} 判死（见 copyTrack 的注释），
     * 写进去 77513 帧就停了，89223 帧里少了 11710 帧，收尾时 moov 是
     * 在「这条轨已经死了」的状态下写的，于是它没有样本表。</p>
     *
     * <p>这样的文件 {@link MediaExtractor} 读出来是「0 条轨」，
     * 播放器直接打不开，而它却以「已完成」的姿态躺进了相册。
     * 样本数才是「这个文件能不能播」的硬指标，所以必须读。</p>
     *
     * @return 样本数；stbl 存在但没有 stsz 时返回 0（确定是坏的）；
     *         stbl 本身都没找到时返回 -1（结构超出解析能力，不据此否决）
     */
    private static long countStsz(java.io.RandomAccessFile raf, long start, long end) {
        long p = start;
        while (p + 8 <= end) {
            long[] b = readBoxHeader(raf, p, end);
            if (b == null) {
                return -1;
            }
            if ("stbl".equals(boxType(raf, p))) {
                long q = p + b[1];
                long qEnd = p + b[0];
                while (q + 8 <= qEnd) {
                    long[] b2 = readBoxHeader(raf, q, qEnd);
                    if (b2 == null) {
                        return 0;
                    }
                    if ("stsz".equals(boxType(raf, q))) {
                        // stsz: size(4) type(4) version+flags(4) sample_size(4) count(4)
                        // 计数在第 8 个字节处，与 version 无关
                        return readU32At(raf, q + b2[1] + 8);
                    }
                    q += b2[0];
                }
                // 走到这里说明 stbl 在、stsz 不在 —— 这正是真机上那个坏产物的
                // 样子：轨在、mdat 有 29 MB，但一条样本都没有，提取器读出来
                // 是「0 条轨」，播放器打不开。这种情况必须判 0 而不是「未知」，
                // 否则它又会以「已完成」的姿态躺进相册。
                return 0;
            }
            p += b[0];
        }
        return -1;
    }

    /** 读 mdhd 里的时长（微秒）。读不到返回 -1。 */
    private static long readMdhdDuration(java.io.RandomAccessFile raf, long p, long hdrLen) {
        try {
            raf.seek(p + hdrLen);
            byte[] b = new byte[32];
            int got = raf.read(b);
            if (got < 4) {
                return -1;
            }
            int version = b[0];
            long timescale;
            long dur;
            if (version == 0) {
                // version+flags(4) ctime(4) mtime(4) timescale(4) duration(4)
                if (got < 20) {
                    return -1;
                }
                timescale = readU32(b, 12);
                dur = readU32(b, 16);
            } else {
                // version+flags(4) ctime(8) mtime(8) timescale(4) duration(8)
                // duration 在偏移 24，占 8 字节 —— 缓冲区必须够 32
                if (got < 32) {
                    return -1;
                }
                timescale = readU32(b, 20);
                dur = readU64(b, 24);
            }
            if (timescale <= 0) {
                return -1;
            }
            return dur * 1000000L / timescale;
        } catch (Exception e) {
            return -1;
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

    /** 从文件任意偏移直接读一个 32 位大端整数（读不到返回 -1）。 */
    private static long readU32At(java.io.RandomAccessFile raf, long p) {
        try {
            raf.seek(p);
            byte[] b = new byte[4];
            if (raf.read(b) < 4) {
                return -1;
            }
            return readU32(b, 0);
        } catch (Exception e) {
            return -1;
        }
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

    /**
     * 这条轨是不是定长帧的 AAC。
     *
     * <p>只有 AAC 才适合用「相邻样本至少隔半个典型间距」去判异常帧：
     * 它的帧长是固定的（1024 个采样），所以间距本该稳定。
     * Opus / Vorbis 的帧长本来就变，不能这么判。</p>
     */
    private static boolean isAac(MediaFormat fmt) {
        try {
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            return mime != null && mime.startsWith("audio/mp4a");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 把一条轨的样本逐个搬进 muxer。
     *
     * <h3>为什么会丢掉极少数样本</h3>
     * <p>B 站与 YouTube 的音频是**分片 MP4**。实测一条 34 分钟的音频，
     * 源文件本身时间轴是干净的（412 个分片逐个核对 tfdt，零处回退），
     * 样本间距稳定在 23220 微秒，但其中有一处这样：</p>
     * <pre>
     * 样本 77512  pts=1783529274
     * 样本 77513  pts=1783529297   ← 只隔了 23 微秒，正常是 23220
     * 样本 77514  pts=1783552222   ← 又回到正常间距
     * </pre>
     * <p>也就是说 77513 是一个挤在前一帧几乎同一时刻的**多余帧**。
     * 封装器不接受这种间距，而且它的反应不是跳过这一帧，是直接判死整条轨：</p>
     * <pre>
     * E MPEG4Writer: do not support out of order frames
     *                (timestamp: 1783529297 &lt; last: 1783529319) for Audio track
     * D MPEG4Writer: Audio track stopped. Status:-1007
     * I MPEG4Writer: Received total/0-length (77514/0) buffers and encoded 77513 frames.
     * </pre>
     * <p>后果取决于容器里还有没有别的轨：纯音频就是整个文件废掉（89,223 帧
     * 只进去 77,513 帧，moov 里连样本表都没有），带视频的则是**视频正常、
     * 音频整条消失** —— 用户拿到一个能播但没声音的 MP4，比直接报错更糟，
     * 因为他要看过才发现。</p>
     *
     * <h3>为什么是「丢掉」而不是「拉平」</h3>
     * <p>拉平（把这一帧挪到上一帧之后一整帧）会让它盖住下一帧的位置，
     * 于是下一帧又要再挪，偏移一路传下去 —— 表面上看不出，
     * 实际是整条音轨持续后退。而丢掉这一帧的代价是 23 毫秒的静音，
     * 位置就在它本来该在的地方，后面的帧时间戳原封不动。</p>
     *
     * <p>判据用「不到正常间距的一半」而不是「小于等于零」：真正的回退
     * （负步长）和这种贴在一起的重复帧都是同一类毛病，而正常的抖动
     * 不会有一半这么大的幅度。正常间距从前几步学出来，不依赖音频参数
     * 一定能取到。</p>
     *
     * @param enforceMonotonic 音频轨传 true（WebM/Opus 帧长本来就不固定，
     *                         传 false，否则会把正常的短帧当异常丢掉）
     * @return 实际写进 muxer 的样本数
     */
    private static long copyTrack(MediaExtractor ex, MediaFormat fmt, MediaMuxer muxer,
                                  int outTrack, MediaCodec.BufferInfo info, Http.Abort abort,
                                  boolean enforceMonotonic)
            throws IOException {
        int maxInput = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : MIN_BUFFER;
        int cap = Math.max(MIN_BUFFER, Math.min(MAX_BUFFER, maxInput * 2));
        ByteBuffer buf = ByteBuffer.allocate(cap);

        long written = 0;
        long dropped = 0;
        long lastPts = Long.MIN_VALUE;
        long typicalStep = 0;

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

            if (enforceMonotonic && lastPts != Long.MIN_VALUE) {
                long step = pts - lastPts;
                if (typicalStep > 0 && step < typicalStep / 2) {
                    // 回退，或者挤在上一帧旁边的重复帧。丢掉它，
                    // lastPts 不动 —— 后面的样本时间戳自己就接得上。
                    dropped++;
                    ex.advance();
                    continue;
                }
                if (step > 0 && typicalStep == 0) {
                    // 从最初几步学出正常间距
                    typicalStep = step;
                }
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
            lastPts = pts;
            written++;
            ex.advance();
        }

        if (dropped > 0) {
            Log.w(TAG, "丢弃 " + dropped + " 个时间戳贴在前一帧上的异常样本（写入 "
                    + written + " 个，正常间距 " + typicalStep + "us）—— 不丢的话"
                    + "封装器会判死整条轨：纯音频整个坏掉，带视频的会变成无声");
        }
        return written;
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

    /**
     * 校验音频文件是完整可用的。
     *
     * <p>用在「仅音频」那条路径上 —— 那里只是把下载好的 m4a 复制一份，
     * 所以只需要确认这个文件本身没问题。</p>
     *
     * <p><b>不能只看 moov 里的样本数。</b>B 站与 YouTube 给的音频是
     * **分片 MP4**（fMP4）：moov 里的 stsz 是**空**的，样本分散在文件后半段的
     * 一个个 moof 里，靠 tfdt 串起来。拿「moov 里有多少样本」去判断，
     * 会把一份完全正确的分片文件判成坏的。所以这里分两种情况：
     * moov 里有样本就信它，moov 里没有就数 moof 片段。</p>
     */
    public static boolean hasPlayableAudio(File f) {
        if (!usable(f)) {
            return false;
        }
        BoxScan scan = scanBoxes(f);
        if (!scan.hasMoov || !scan.hasAudioTrack || scan.mdatBytes <= 0) {
            return false;
        }
        // 非分片：样本表在 moov 里
        if (scan.audioSamples > 0) {
            return true;
        }
        // 分片：样本在 moof 里
        return scan.fragments > 0;
    }
}
