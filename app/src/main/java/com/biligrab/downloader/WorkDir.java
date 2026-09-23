package com.biligrab.downloader;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * 每个下载任务独占的工作目录。
 *
 * <h3>为什么要按任务分开</h3>
 * <p>原来所有任务共用 {@code filesDir/work}，开跑前先整个清空。那样有两个后果：
 * 一是永远只可能有一个任务存在，二是半成品留不下来，因此暂停与续传从根上
 * 做不到 —— 暂停意味着「保留已经下到的字节」，而共用目录的清空逻辑
 * 会把它们一起抹掉。</p>
 *
 * <p>现在每个任务用自己的目录，暂停时文件原样留着，继续时接着往里写。</p>
 */
public final class WorkDir {

    private static final String TAG = "BiliGrab/Work";

    private static final String ROOT = "work";

    private WorkDir() {
    }

    /** 任务的工作目录。不保证已存在。 */
    public static File of(Context ctx, String taskId) {
        return new File(new File(ctx.getFilesDir(), ROOT), taskId);
    }

    /** 取得工作目录并确保它存在。 */
    public static File prepare(Context ctx, String taskId) throws IOException {
        File dir = of(ctx, taskId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException(ctx.getString(R.string.err_tmp_dir));
        }
        return dir;
    }

    /** 视频轨的半成品文件。 */
    public static File videoFile(Context ctx, String taskId) {
        return new File(of(ctx, taskId), "video.part");
    }

    /** 音频轨的半成品文件。 */
    public static File audioFile(Context ctx, String taskId) {
        return new File(of(ctx, taskId), "audio.part");
    }

    private static File sigFile(Context ctx, String taskId) {
        return new File(of(ctx, taskId), "signature");
    }

    /**
     * 记下这批半成品是「哪个流」的碎片。
     *
     * <p>续传只有在目标还是同一个流时才有意义。用户可能下到一半去把画质从
     * 1080P 改成 720P 再继续 —— 那时旧文件的字节和新地址的字节对不上，
     * 硬接起来会得到一个能播、但某些位置花屏或跳帧的文件。这比重新下载更糟，
     * 因为用户看不出来。</p>
     *
     * <p>所以给每个任务的工作目录写一个签名，下载前比对；不一致就当全新的
     * 下载处理，把旧碎片丢掉。</p>
     */
    public static void writeSignature(Context ctx, String taskId, String sig) {
        File f = sigFile(ctx, taskId);
        try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
            out.write(sig.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } catch (IOException e) {
            // 签名写不进去不影响下载本身，只是续传时会保守地重下
            Log.w(TAG, "写入签名失败：" + e.getMessage());
        }
    }

    /**
     * 磁盘上的半成品是否属于这个签名。
     *
     * <p>没有签名文件、或签名对不上，一律返回 false —— 宁可重下载一遍，
     * 也不要把两段不同的数据拼在一起。</p>
     */
    public static boolean signatureMatches(Context ctx, String taskId, String sig) {
        File f = sigFile(ctx, taskId);
        if (!f.exists()) {
            return false;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            String onDisk = new String(buf, 0, off, java.nio.charset.Charset.forName("UTF-8"));
            return onDisk.equals(sig);
        } catch (IOException e) {
            return false;
        }
    }

    /** 这个任务已经占用了多少字节（两条半成品合计）。 */
    public static long usedBytes(Context ctx, String taskId) {
        long sum = 0L;
        for (File f : new File[]{videoFile(ctx, taskId), audioFile(ctx, taskId)}) {
            if (f.exists()) {
                sum += f.length();
            }
        }
        // 合流产物也算：它在下盘前的最后一刻才存在，那时不看会让进度看起来倒退
        File dir = of(ctx, taskId);
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().startsWith("output.")) {
                    sum += f.length();
                }
            }
        }
        return sum;
    }

    /** 某个半成品文件当前的字节数；不存在则 0。 */
    public static long lengthOf(File f) {
        return f != null && f.exists() ? f.length() : 0L;
    }

    /** 删掉这个任务的全部半成品。 */
    public static void delete(Context ctx, String taskId) {
        clear(of(ctx, taskId));
        File dir = of(ctx, taskId);
        if (dir.exists() && !dir.delete()) {
            // 删不掉就留着，不值得为它报错
        }
    }

    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                clear(f);
            }
            if (!f.delete()) {
                // 删不掉就留着
            }
        }
    }
}
