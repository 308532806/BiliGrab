package com.biligrab.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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
        String safeName = displayName + ".mp4";
        String mime = audioOnly ? "audio/mp4" : "video/mp4";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(ctx, src, safeName, mime, audioOnly);
        }
        return saveViaPublicDir(ctx, src, safeName);
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
        return relPath + "/" + name;
    }

    @SuppressWarnings("deprecation")
    private static String saveViaPublicDir(Context ctx, File src, String name) throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                REL_DIR);
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
