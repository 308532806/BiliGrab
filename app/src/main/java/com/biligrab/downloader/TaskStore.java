package com.biligrab.downloader;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 下载记录的仓库（单例）。
 *
 * <p>记录落在 {@code filesDir/downloads.json}。为什么不用数据库：记录条数是
 * 个位数到几十条，一次全量读写的成本可以忽略，而 SQLite 会带来建表、迁移、
 * 游标这一整套负担。这里唯一真正的需求是「重启后还在」，一个 JSON 文件足够。</p>
 *
 * <p>写盘刻意做成**同步但节流**：进度每秒变好几次，每次都写盘既费电又磨损闪存。
 * 所以进度只更新内存，只有在状态变化（暂停/完成/失败）时才落盘。</p>
 */
public final class TaskStore {

    private static final String TAG = "BiliGrab/Store";
    private static final String FILE = "downloads.json";

    /** 记录变化时的回调。**来自任意线程**，界面自己 post 到主线程。 */
    public interface Listener {
        void onTasksChanged();
    }

    private static final TaskStore INSTANCE = new TaskStore();

    public static TaskStore get() {
        return INSTANCE;
    }

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private File file;
    private boolean loaded;

    private TaskStore() {
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) {
            try {
                l.onTasksChanged();
            } catch (RuntimeException e) {
                Log.w(TAG, "listener error", e);
            }
        }
    }

    /** 进度更新：只动内存，不写盘。 */
    public void notifyProgress() {
        notifyChanged();
    }

    // ------------------------------------------------------------------

    /** 首次访问时把磁盘上的记录读进来。重复调用安全。 */
    public synchronized void ensureLoaded(Context ctx) {
        if (loaded) {
            return;
        }
        loaded = true;
        file = new File(ctx.getFilesDir(), FILE);
        if (!file.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            JSONArray arr = new JSONArray(new String(buf, 0, off, Charset.forName("UTF-8")));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    tasks.add(DownloadTask.fromJson(o));
                }
            }
            Log.i(TAG, "读取下载记录 " + tasks.size() + " 条");
        } catch (Exception e) {
            // 读不出来就从空开始，绝不让一条坏记录挡住整个应用
            Log.w(TAG, "下载记录读取失败，从空开始", e);
            tasks.clear();
        }
    }

    /** 把当前记录写盘。状态变化时调用。 */
    public synchronized void save(Context ctx) {
        if (file == null) {
            file = new File(ctx.getFilesDir(), FILE);
        }
        try {
            JSONArray arr = new JSONArray();
            for (DownloadTask t : tasks) {
                arr.put(t.toJson());
            }
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(arr.toString().getBytes(Charset.forName("UTF-8")));
            }
        } catch (Exception e) {
            Log.w(TAG, "下载记录写入失败", e);
        }
    }

    // ------------------------------------------------------------------

    /** 全部记录，最近的在前。返回的是快照，调用方可以安全遍历。 */
    public synchronized List<DownloadTask> all() {
        List<DownloadTask> copy = new ArrayList<>(tasks);
        copy.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return copy;
    }

    public synchronized DownloadTask byId(String id) {
        for (DownloadTask t : tasks) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** 当前正在下载或排队的那一条；没有则返回 null。 */
    public synchronized DownloadTask active() {
        for (DownloadTask t : tasks) {
            int s = t.status();
            if (s == DownloadTask.STATUS_RUNNING || s == DownloadTask.STATUS_QUEUED) {
                return t;
            }
        }
        return null;
    }

    public synchronized void add(Context ctx, DownloadTask t) {
        tasks.add(t);
        save(ctx);
        notifyChanged();
    }

    /**
     * 移掉一条记录。
     *
     * @param deleteFile 是否顺手把它落盘的文件也删掉
     */
    public void remove(Context ctx, DownloadTask t, boolean deleteFile) {
        if (deleteFile && !t.fileRef().isEmpty()) {
            StorageDir.delete(ctx, t.fileRef());
        }
        WorkDir.delete(ctx, t.id);
        synchronized (this) {
            tasks.remove(t);
        }
        save(ctx);
        notifyChanged();
    }

    public synchronized void markDirty(Context ctx) {
        save(ctx);
    }
}
