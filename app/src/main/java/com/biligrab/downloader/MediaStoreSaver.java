package com.biligrab.downloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 把合成好的 MP4 落到系统媒体库，让相册 / 文件管理器能直接看到。 */
public final class MediaStoreSaver {

    public static final String REL_DIR = "BiliGrab";

    private MediaStoreSaver() {
    }

    /**
     * @return 人类可读的落盘位置描述
     */
    public static String save(Context ctx, File src, String displayName, boolean audioOnly)
            throws IOException {
        return save(ctx, src, displayName, audioOnly, audioOnly ? "m4a" : "mp4");
    }

    /**
     * @param ext 扩展名（不含点）。扩展名必须和 MIME 一致 —— 之前不管什么
     *            类型都拼 {@code ".mp4"}，而音频那条走的 MIME 是
     *            {@code audio/mp4}，MediaStore 发现对不上，自己把
     *            {@code ".m4a"} 补到了后面，用户拿到的文件名就成了
     *            「标题.mp4.m4a」。
     */
    public static String save(Context ctx, File src, String displayName, boolean audioOnly,
                              String ext) throws IOException {
        String safeName = displayName + "." + ext;
        String mime = mimeOf(ext, audioOnly);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(ctx, src, safeName, mime, audioOnly);
        }
        return saveViaPublicDir(ctx, src, safeName, audioOnly);
    }

    private static String mimeOf(String ext, boolean audioOnly) {
        if ("webm".equalsIgnoreCase(ext)) {
            // WebM 的音视频是同一个 MIME。audioOnly 时走 Audio 集合，
            // 但类型串仍然得是 audio/webm，否则 MediaStore 又会改扩展名。
            return audioOnly ? "audio/webm" : "video/webm";
        }
        return audioOnly ? "audio/mp4" : "video/mp4";
    }

    private static String saveViaMediaStore(Context ctx, File src, String name, String mime,
                                            boolean audioOnly) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = audioOnly
                ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String relPath = (audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES)
                + "/" + REL_DIR;

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = cr.insert(collection, cv);
        if (item == null) {
            throw new IOException("无法在媒体库中创建条目（存储空间不足或权限被拒绝）");
        }
        try {
            OutputStream os = cr.openOutputStream(item);
            if (os == null) {
                throw new IOException("无法打开媒体库输出流");
            }
            try {
                copy(src, os);
            } finally {
                Http.closeQuietly(os);
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, cv, null, null);
        } catch (IOException e) {
            try {
                cr.delete(item, null, null);
            } catch (RuntimeException ignored) {
                // 清理失败不影响主流程
            }
            throw e;
        }
        // 回读库里真正的 DISPLAY_NAME，而不是我们请求的那个名字。
        // MediaStore 落盘时可能改过它（补扩展名、重名时加序号），
        // 直接报请求名会让提示和用户实际看到的文件对不上。
        return relPath + "/" + actualName(cr, item, name);
    }

    /** 读媒体库里实际落盘的文件名；读不到就退回请求时的名字。 */
    private static String actualName(ContentResolver cr, Uri item, String fallback) {
        Cursor c = null;
        try {
            c = cr.query(item, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // 单纯为了显示得准一点，查不到不值得让整次下载失败
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return fallback;
    }

    @SuppressWarnings("deprecation")
    private static String saveViaPublicDir(Context ctx, File src, String name, boolean audioOnly)
            throws IOException {
        // 音频进 Music、视频进 Movies —— 和 MediaStore 分支保持一致
        String type = audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES;
        File dir = new File(Environment.getExternalStoragePublicDirectory(type), REL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir.getAbsolutePath());
        }
        File dst = new File(dir, name);
        try (OutputStream out = new FileOutputStream(dst)) {
            copy(src, out);
        }
        MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, null, null);
        return dst.getAbsolutePath();
    }

    private static void copy(File src, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }
}
