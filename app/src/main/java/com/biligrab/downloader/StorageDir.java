package com.biligrab.downloader;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 下载目录：默认目录与用户自选目录的统一入口。
 *
 * <h3>两条完全不同的写入路径</h3>
 * <p>API 29 起应用不能随便往公共目录写文件，只能通过 MediaStore 登记，
 * 系统按类型决定它落在 Movies 还是 Music 下。而用户如果自己挑了一个目录
 * （SAF 的 {@code ACTION_OPEN_DOCUMENT_TREE}），写入就变成往那个 tree
 * 里 createDocument。两条路的句柄、删除方式、甚至「文件名会不会被改」
 * 都不一样，所以外边只递进来一个「引用字符串」，由这里负责分辨：</p>
 *
 * <ul>
 *   <li>{@code content://} 开头 —— SAF 自选目录，用 DocumentsContract 操作；</li>
 *   <li>其他 —— 默认目录，用 MediaStore 登记，删除走 ContentResolver。</li>
 * </ul>
 *
 * <h3>为什么默认目录下音频进 Music、视频进 Movies</h3>
 * <p>这是 MediaStore 的硬性归类：往 Video 集合里插一个 audio/mp4 会被系统
 * 拒掉或改名。用户不受影响 —— 相册和音乐播放器都能扫到。
 * 自选目录没有这个约束，音频视频都放用户指定的那一个目录里。</p>
 */
public final class StorageDir {

    private static final String TAG = "BiliGrab/Storage";

    /** 自选目录的引用存在偏好里。 */
    public static final String KEY_TREE_URI = "download_tree_uri";
    /** 自选目录给人看的名字，形如「下载/BiliGrab」。 */
    public static final String KEY_TREE_LABEL = "download_tree_label";

    private static final String REL_DIR = "BiliGrab";

    private StorageDir() {
    }

    // ------------------------------------------------------------------
    // 当前目录
    // ------------------------------------------------------------------

    /** 用户是否指定了自定义目录。 */
    public static boolean hasCustom(Context ctx) {
        return !treeUri(ctx).isEmpty();
    }

    public static String treeUri(Context ctx) {
        return new Prefs(ctx).downloadTreeUri();
    }

    public static String customLabel(Context ctx) {
        return new Prefs(ctx).downloadTreeLabel();
    }

    /**
     * 当前下载目录的人类可读描述，直接显示在设置里。
     *
     * <p>默认目录必须写出「视频进 Movies、音频进 Music」这件事 —— 否则用户
     * 找不到下载的文件时，会以为应用没保存成功。</p>
     */
    public static String describe(Context ctx, boolean audioOnly) {
        String custom = customLabel(ctx);
        if (!custom.isEmpty()) {
            return custom;
        }
        return (audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES)
                + "/" + REL_DIR;
    }

    /** 记下用户选中的目录。 */
    public static void setCustom(Context ctx, String uri, String label) {
        new Prefs(ctx).setDownloadTree(uri, label);
    }

    /**
     * 忘掉自选目录，回到默认。
     *
     * <p>同时释放持久化权限 —— 不释放的话系统会对同一个 tree 一直记着
     * 我们有权访问，那是没必要的残留（而且系统对每个应用的持久化授权
     * 数量是有限额的）。</p>
     */
    public static void clearCustom(Context ctx) {
        String uri = treeUri(ctx);
        new Prefs(ctx).setDownloadTree("", "");
        if (uri.isEmpty()) {
            return;
        }
        try {
            ctx.getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException e) {
            // 没授权过、或系统已经忘了，都不影响「回到默认目录」这件事
            Log.w(TAG, "释放目录权限失败（可忽略）：" + e.getMessage());
        }
    }

    /**
     * 检查已保存的自选目录是否还能用。
     *
     * <p>用户可能在系统设置里撤销了授权，或者把那个目录删了。这时如果继续
     * 往里写，会在下载的最后一步失败 —— 用户等了半天才看到错误。
     * 所以下载开始前先探一下，发现失效就退回默认目录并清掉记录。</p>
     *
     * @return true 表示自选目录仍然可用；false 表示已失效并已被清除
     */
    public static boolean verifyCustom(Context ctx) {
        String uri = treeUri(ctx);
        if (uri.isEmpty()) {
            return false;
        }
        boolean granted = false;
        try {
            List<UriPermission> perms = ctx.getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : perms) {
                if (p.getUri().toString().equals(uri) && p.isWritePermission()) {
                    granted = true;
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "读取目录授权失败：" + e.getMessage());
        }
        if (!granted) {
            Log.w(TAG, "自选目录授权已失效，退回默认目录");
            clearCustom(ctx);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /**
     * 把合成好的文件放进下载目录。
     *
     * @return 一个「引用字符串」，可交给 {@link #delete} 删除；
     *         同时也是给用户看的位置描述，由 {@link #describe} 负责
     */
    public static String save(Context ctx, File src, String displayName,
                              boolean audioOnly, String ext) throws IOException {
        String name = displayName + "." + ext;
        String mime = mimeOf(ext, audioOnly);
        String custom = treeUri(ctx);

        if (!custom.isEmpty()) {
            return saveToTree(ctx, custom, src, name, mime);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(ctx, src, name, mime, audioOnly);
        }
        return saveViaPublicDir(ctx, src, name, audioOnly);
    }

    private static String mimeOf(String ext, boolean audioOnly) {
        if ("webm".equalsIgnoreCase(ext)) {
            // WebM 的音视频是同一个 MIME。audioOnly 时走 Audio 集合，
            // 但类型串仍然得是 audio/webm，否则 MediaStore 又会改扩展名。
            return audioOnly ? "audio/webm" : "video/webm";
        }
        return audioOnly ? "audio/mp4" : "video/mp4";
    }

    // ------------------------------------------------------------------
    // 自选目录（SAF）
    // ------------------------------------------------------------------

    /**
     * 往 SAF 目录里写一个文件。
     *
     * <p>用 {@code DocumentsContract.createDocument} 而不是自己拼路径：
     * SAF 目录的真实路径可能是 {@code /storage/XXXX-XXXX/...}，
     * 也可能是云盘或另一个应用的私有存储，只有 ContentResolver 知道怎么落进去。</p>
     *
     * <p>文件名重复时系统会自己改成「名称 (1).ext」。我们回读真实文档名，
     * 这样提示里写的位置和用户实际看到的文件是一致的。</p>
     */
    private static String saveToTree(Context ctx, String treeUriStr, File src,
                                     String name, String mime) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri tree = Uri.parse(treeUriStr);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));

        Uri doc;
        try {
            doc = DocumentsContract.createDocument(cr, parent, mime, name);
        } catch (Exception e) {
            // 目录被删掉、授权被撤销、或那个 provider 不支持写入
            throw new IOException(ctx.getString(R.string.err_custom_dir_failed,
                    String.valueOf(e.getMessage())), e);
        }
        if (doc == null) {
            throw new IOException(ctx.getString(R.string.err_custom_dir_failed,
                    ctx.getString(R.string.err_custom_dir_null)));
        }

        OutputStream os = null;
        try {
            os = cr.openOutputStream(doc, "w");
            if (os == null) {
                throw new IOException(ctx.getString(R.string.err_custom_dir_null));
            }
            copy(src, os);
        } catch (IOException e) {
            try {
                DocumentsContract.deleteDocument(cr, doc);
            } catch (Exception ignored) {
                // 清理失败不该盖过真正的错误
            }
            throw e;
        } finally {
            Http.closeQuietly(os);
        }
        return doc.toString();
    }

    /** 从 SAF 文档 URI 反查显示名，拿不到就返回 null。 */
    private static String treeDisplayName(ContentResolver cr, Uri doc) {
        Cursor c = null;
        try {
            c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // 只为显示得准一点
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 默认目录：MediaStore（API 29+）
    // ------------------------------------------------------------------

    private static String saveViaMediaStore(Context ctx, File src, String name, String mime,
                                            boolean audioOnly) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = audioOnly
                ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String relPath = (audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES)
                + "/" + REL_DIR;

        // 重名时依次加后缀重试。
        //
        // 为什么要重试而不是直接失败：MediaStore 在 `files._data` 上有唯一索引，
        // 而部分 ROM（实测 ColorOS/Android 12）在把 IS_PENDING 从 1 改回 0
        // 的那一刻才去解析最终路径，如果那个路径已经被占，就抛
        // `UNIQUE constraint failed: files._data`。
        // 这时候数据其实已经完整写进文件了 —— 因为一个重名就报「下载失败」，
        // 让用户白等一场（还有可能已经把几十 MB 下完），是说不过去的。
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = attempt == 0 ? name : withSuffix(name, attempt + 1);
            try {
                return insertAndWrite(cr, collection, relPath, candidate, mime, src, audioOnly);
            } catch (NameConflictException e) {
                Log.w(TAG, "媒体库里 “" + candidate + "” 这个位置已被占用，换个名字重试");
            }
        }
        // 连加 8 个序号都撞名，或者这个 ROM 干脆不接受写入。
        // 报一句用户能看懂、且信息量对的话：数据已经在文件里了，
        // 他没白等；要做的是改名或换个目录。
        throw new IOException(ctx.getString(R.string.err_mediastore_conflict));
    }

    /**
     * 一次完整的「建条目 → 写数据 → 定稿」。
     *
     * @throws NameConflictException 目标路径被占用，调用方应换个名字重试
     */
    private static String insertAndWrite(ContentResolver cr, Uri collection, String relPath,
                                         String name, String mime, File src, boolean audioOnly)
            throws IOException {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item;
        try {
            item = cr.insert(collection, cv);
        } catch (RuntimeException e) {
            // 插入阶段就撞名（有些 ROM 会在这里直接抛）
            throw new NameConflictException(String.valueOf(e.getMessage()), e);
        }
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

            // 定稿。这里必须把路径相关的字段**再给一遍**：
            // 只更新 IS_PENDING 时，个别 ROM 会丢掉 RELATIVE_PATH 而按默认目录
            // 去算最终路径，结果就是上面那个 UNIQUE 冲突。
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
            try {
                cr.update(item, cv, null, null);
            } catch (RuntimeException e) {
                // 定稿失败：把这条半成品记录删掉（顺带删掉刚写的文件），
                // 换个名字重来一遍，而不是把已经下完的内容丢掉
                deleteQuietly(cr, item);
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("UNIQUE") || msg.contains("constraint")) {
                    throw new NameConflictException(msg, e);
                }
                throw new IOException(msg, e);
            }
            return item.toString();
        } catch (IOException e) {
            deleteQuietly(cr, item);
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(cr, item);
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
    }

    private static void deleteQuietly(ContentResolver cr, Uri item) {
        try {
            cr.delete(item, null, null);
        } catch (RuntimeException ignored) {
            // 清理失败不影响主流程
        }
    }

    /**
     * 给文件名加序号，保留扩展名。
     *
     * <p>「视频.mp4」→「视频 (2).mp4」。序号放在扩展名之前，
     * 否则播放器会认不出格式。</p>
     */
    private static String withSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + " (" + n + ")";
        }
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    /** 目标位置已被占用（不是真的失败，换个名字就行）。 */
    private static final class NameConflictException extends IOException {
        NameConflictException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    // ------------------------------------------------------------------
    // 默认目录：公共目录（API 26-28）
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static String saveViaPublicDir(Context ctx, File src, String name, boolean audioOnly)
            throws IOException {
        String type = audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES;
        File dir = new File(Environment.getExternalStoragePublicDirectory(type), REL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException(ctx.getString(R.string.err_public_dir, dir.getAbsolutePath()));
        }
        File dst = new File(dir, name);
        try (OutputStream out = new FileOutputStream(dst)) {
            copy(src, out);
        }
        MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, null, null);
        return dst.getAbsolutePath();
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 删掉一个已经落盘的文件。
     *
     * <p>引用可能是 SAF 文档 URI、MediaStore 条目 URI，或一个绝对路径 ——
     * 三种来源都要能处理，因为记录是持久化的，可能是旧版本写下的。</p>
     *
     * @return true 表示确实删掉了；false 表示删不掉（文件已不在、
     *         或系统不允许）—— 调用方据此决定要不要清掉记录
     */
    public static boolean delete(Context ctx, String ref) {
        if (ref == null || ref.isEmpty()) {
            return true;
        }
        ContentResolver cr = ctx.getContentResolver();

        if (ref.startsWith("content://")) {
            Uri uri = Uri.parse(ref);
            // 先按 SAF 文档删。MediaStore 的条目也能被 DocumentsContract 处理，
            // 但不一定；两条路都试过才稳妥。
            try {
                if (DocumentsContract.deleteDocument(cr, uri)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 落到 ContentResolver.delete
            }
            try {
                return cr.delete(uri, null, null) > 0;
            } catch (RuntimeException e) {
                Log.w(TAG, "删除媒体库条目失败：" + e.getMessage());
                return false;
            }
        }

        File f = new File(ref);
        if (!f.exists()) {
            return true;
        }
        return f.delete();
    }

    /** 这个引用当前还在不在。 */
    public static boolean exists(Context ctx, String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        if (ref.startsWith("content://")) {
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(ref))) {
                return in != null;
            } catch (Exception e) {
                return false;
            }
        }
        return new File(ref).exists();
    }

    /** 取出落盘文件的显示名，用于「删除」时告诉用户删的是哪个文件。 */
    public static String nameOf(Context ctx, String ref) {
        if (ref == null || ref.isEmpty()) {
            return "";
        }
        if (ref.startsWith("content://")) {
            String n = treeDisplayName(ctx.getContentResolver(), Uri.parse(ref));
            return n == null ? "" : n;
        }
        return new File(ref).getName();
    }

    /**
     * 落盘位置的人类可读描述，直接显示给用户。
     *
     * <p>不能在保存前算好：SAF 目录下系统可能改文件名，所以要等拿到真实
     * 名字之后再拼。</p>
     */
    public static String locationOf(Context ctx, String ref, String name, boolean audioOnly) {
        if (ref == null || ref.isEmpty()) {
            return "";
        }
        if (ref.startsWith("content://")) {
            String tree = treeUri(ctx);
            String base = (!tree.isEmpty() && ref.startsWith(tree))
                    ? customLabel(ctx) : describe(ctx, audioOnly);
            if (base.isEmpty()) {
                base = describe(ctx, audioOnly);
            }
            return name.isEmpty() ? base : base + "/" + name;
        }
        // API 26-28 的公共目录：引用本身就是绝对路径，直接显示
        return ref;
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
