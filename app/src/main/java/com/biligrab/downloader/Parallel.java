package com.biligrab.downloader;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多连接分段下载。
 *
 * <h3>为什么需要它</h3>
 * <p>googlevideo 对**每一条连接**单独限速，出口带宽再宽也没用。
 * 实测同一个直链（71 MB、4K 档）：</p>
 *
 * <pre>
 *   1 连接    8.14 Mbps
 *   4 连接   35.50 Mbps   （4.36 倍）
 *   8 连接   27.39 Mbps   （反而退步）
 * </pre>
 *
 * <p>这正是「换了很多 VPN 都一样慢」的原因 —— 瓶颈不在机场带宽，
 * 而在单条 TCP 连接被服务端限了速。把一条连接拆成几条并行取，
 * 每一条都拿到自己那份限速额度，合起来才是线路真实的速度。</p>
 *
 * <h3>为什么分块大小是 4 MB 而不是「平均切成 N 份」</h3>
 * <p>切成 N 份固定区间看着更简单，但几条连接的速度天生不齐：
 * 最快的那条早早干完闲着，最慢的那条还在拖，总耗时由最慢的那条决定。
 * 改成「一个共享的任务队列 + 每块 4 MB」，谁先空下来谁就接着领下一块，
 * 快的连接自然多干，慢的连接少干，收尾时差距不会拉大。</p>
 *
 * <h3>断点台账为什么非有不可</h3>
 * <p>多连接没法像单连接那样用「文件有多长 = 下到哪了」来判断进度：
 * 文件是先按总长预分配的（否则几条连接各自 seek 写会互相打洞），
 * 所以 {@code length()} 一开始就等于总量。
 * 真正的进度记在一份 {@code .journal} 台账里 —— 每下完一块追加一行，
 * 下次接着下时只补台账里没有的那几块。</p>
 *
 * <p>台账和单连接续传**不能混用**：单连接把 {@code length()} 当成已下进度，
 * 而多连接下 {@code length()} 是预分配出来的、里面还有洞。
 * 所以调用方必须先问 {@link #hasJournal}，有台账就让多连接接管，
 * 不然宁可整份重下（见 {@link #discard}），绝不能拿一个带洞的文件去合流。</p>
 */
final class Parallel {

    private static final String TAG = "BiliGrab/Parallel";

    /** 分块大小。取小一点让负载更均衡，取大一点少几次请求；4 MB 是个平衡点。 */
    private static final long CHUNK = 4L * 1024 * 1024L;

    /** 台账文件后缀。 */
    private static final String JOURNAL_SUFFIX = ".journal";

    /** 台账魔数与版本，用来识别「这是不是我们写的、还能不能读懂」。 */
    private static final String MAGIC = "BiliGrabParts1";

    /** 单块重试次数。CDN 偶尔会在某一条连接上抽风，换个连接再要一次通常就好。 */
    private static final int CHUNK_TRY = 2;

    /** 读缓冲。和单连接那条路保持一致。 */
    private static final int BUF = 1 << 16;

    /** 每写这么多字节报一次进度，避免几条连接把回调打得过密。 */
    private static final int REPORT_EVERY = 1 << 18;

    private Parallel() {
    }

    // ------------------------------------------------------------------
    // 台账
    // ------------------------------------------------------------------

    private static File journalOf(File dst) {
        return new File(dst.getParentFile(), dst.getName() + JOURNAL_SUFFIX);
    }

    /** 这份目标文件上是否有一次没做完的多连接下载。 */
    static boolean hasJournal(File dst) {
        return journalOf(dst).exists();
    }

    /**
     * 丢掉多连接留下的全部痕迹（预分配的目标文件 + 台账）。
     *
     * <p>用在「没法接着下、只能重来」的场合。宁可白下一次，也不能让一个
     * 带洞的文件流到合流那一步 —— 那种文件的长度是对的、内容中间缺一块，
     * 合出来的结果就是画面在某一秒冻住，用户要播到一半才发现。</p>
     */
    static void discard(File dst) {
        File jf = journalOf(dst);
        if (jf.exists() && !jf.delete()) {
            Log.w(TAG, "删不掉台账 " + jf.getName());
        }
        if (dst.exists() && !dst.delete()) {
            Log.w(TAG, "删不掉半成品 " + dst.getName());
        }
    }

    /** 一次多连接下载的台账内容。 */
    private static final class Journal {
        long total;
        long chunk;
        final Set<Integer> done = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    }

    /**
     * 读台账。读不懂、或者和当前这次的总量对不上，就返回 {@code null}
     * —— 调用方据此决定是重下还是接着下。
     */
    private static Journal load(File jf, long totalHint) {
        if (!jf.exists()) {
            return null;
        }
        List<String> lines;
        try {
            lines = readLines(jf);
        } catch (IOException e) {
            Log.w(TAG, "台账读不出来，作废：" + e.getMessage());
            return null;
        }
        if (lines.isEmpty()) {
            return null;
        }
        String[] head = lines.get(0).trim().split("\\s+");
        if (head.length != 3 || !MAGIC.equals(head[0])) {
            return null;
        }
        Journal j = new Journal();
        try {
            j.total = Long.parseLong(head[1]);
            j.chunk = Long.parseLong(head[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        // 总量或块大小变了，之前那些块的边界就对不上了，没法再用
        if (j.total <= 0 || j.chunk <= 0 || j.chunk != CHUNK) {
            return null;
        }
        if (totalHint > 0 && totalHint != j.total) {
            return null;
        }
        int chunks = chunkCount(j.total);
        for (int i = 1; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (s.isEmpty() || s.charAt(0) != '+') {
                continue;
            }
            try {
                int idx = Integer.parseInt(s.substring(1));
                if (idx >= 0 && idx < chunks) {
                    j.done.add(idx);
                }
            } catch (NumberFormatException ignored) {
                // 半行（写到一半断电）：忽略就好，那一块会重新下
            }
        }
        return j;
    }

    private static List<String> readLines(File f) throws IOException {
        byte[] raw = new byte[(int) Math.min(f.length(), 1 << 20)];
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.readFully(raw);
        } finally {
            raf.close();
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static void writeHeader(File jf, long total) throws IOException {
        String s = MAGIC + " " + total + " " + CHUNK + "\n";
        FileOutputStream out = new FileOutputStream(jf, false);
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            Http.closeQuietly(out);
        }
    }

    /** 追加「第 idx 块下完了」。追加而不是重写，是为了断电时最多丢一条记录。 */
    private static synchronized void markDone(File jf, int idx) {
        try {
            FileOutputStream out = new FileOutputStream(jf, true);
            try {
                out.write(("+" + idx + "\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                Http.closeQuietly(out);
            }
        } catch (IOException e) {
            // 台账写不进去不是致命的：那块内容已经在文件里了，
            // 只影响「下次能不能少下几块」。不该因此让下载失败。
            Log.w(TAG, "台账追加失败（不影响本次下载）：" + e.getMessage());
        }
    }

    private static int chunkCount(long total) {
        long n = (total + CHUNK - 1) / CHUNK;
        return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }

    private static long chunkStart(int idx) {
        return (long) idx * CHUNK;
    }

    private static long chunkEnd(int idx, long total) {
        long e = chunkStart(idx) + CHUNK - 1L;
        return Math.min(e, total - 1L);
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    /** 服务端到底支不支持按区间取，以及文件总长是多少。 */
    static final class Probe {
        boolean ok;
        long total;

        static Probe no() {
            return new Probe();
        }
    }

    /**
     * 用 {@code Range: bytes=0-0} 探一次。
     *
     * <p>为什么不用 HEAD：CDN 对 HEAD 常常不给 {@code Content-Length}，
     * 而按区间取一个字节必然会回一段 {@code Content-Range}，里面带着总量
     * —— 这是唯一能确定「总量 + 支持区间」两件事的方法。</p>
     *
     * <p>本该是 206 却回了 200，说明服务端把 Range 忽略了。那种情况下
     * 每条连接都会从头把整份发过来，多连接只会变成多倍流量，必须放弃。</p>
     */
    static Probe probe(String url, String cookie, boolean withReferer, Proxy proxy,
                       Map<String, String> headers) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = Http.open(url, cookie, withReferer, proxy, headers);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                Probe p = new Probe();
                p.total = totalFromContentRange(c.getHeaderField("Content-Range"));
                p.ok = p.total > 0;
                Log.i(TAG, "多连接探测：支持区间，总量 " + p.total + " 字节，用时 "
                        + (System.currentTimeMillis() - t0) + "ms");
                return p;
            }
            Log.i(TAG, "多连接探测：服务端回 HTTP " + code + "（不是 206），改用单连接");
            return Probe.no();
        } catch (IOException e) {
            // 探测失败不代表下载一定失败（可能只是这一条连接运气不好），
            // 所以这里只记一笔、返回不支持，让单连接那条老路去报真正的错
            Log.w(TAG, "多连接探测失败，改用单连接：" + e.getMessage());
            return Probe.no();
        } finally {
            Http.closeQuietly(in);
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 从 {@code bytes 0-0/34610750} 里取出总量；拿不到返回 0。 */
    private static long totalFromContentRange(String range) {
        if (range == null) {
            return 0L;
        }
        int slash = range.lastIndexOf('/');
        if (slash <= 0) {
            return 0L;
        }
        String tail = range.substring(slash + 1).trim();
        if (tail.isEmpty() || "*".equals(tail)) {
            return 0L;
        }
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /** 服务端不肯按区间给数据。调用方收到它就退回单连接。 */
    static final class Unsupported extends IOException {
        Unsupported(String msg) {
            super(msg);
        }
    }

    /**
     * 把整份内容用 {@code connections} 条连接取到 {@code dst}。
     *
     * <p>有台账就接着下（只补缺的块），没台账就新建一个。</p>
     *
     * @param totalHint 解析阶段已知的总长，&lt;=0 表示未知（此时必须有台账）
     * @return 文件里已经确定下好的字节数（完整时等于总量）
     */
    static long fetch(String url, String cookie, boolean withReferer, Proxy proxy,
                      Map<String, String> headers, File dst, long totalHint, int connections,
                      Http.Progress progress, Http.Abort abort) throws IOException {
        File jf = journalOf(dst);
        Journal j = load(jf, totalHint);

        if (j == null) {
            // 没有可用的台账：这一份从头开始
            long total = totalHint;
            if (total <= 0) {
                throw new Unsupported("没有可用的总量信息，无法分段");
            }
            discard(dst);
            writeHeader(jf, total);
            j = load(jf, total);
            if (j == null) {
                throw new IOException("台账建立失败");
            }
        }

        final long total = j.total;
        final int chunks = chunkCount(total);
        final File journal = jf;
        final Set<Integer> done = j.done;

        long already = 0L;
        for (Integer idx : done) {
            already += chunkEnd(idx, total) - chunkStart(idx) + 1L;
        }

        Log.i(TAG, "多连接下载：总量 " + total + " 字节，分 " + chunks + " 块（每块 "
                + (CHUNK / 1024 / 1024) + " MB），并发 " + connections
                + "，已完成 " + done.size() + " 块 / " + Fmt.bytes(already));

        // 预分配：几条连接各自 seek 到自己的区间去写，文件必须先有总长，
        // 否则某条连接写到靠后的位置时会把文件撑大、中间的洞变成 0 字节，
        // 而那个长度又会被单连接的续传判断误读成「已经下到这么多了」。
        RandomAccessFile raf = new RandomAccessFile(dst, "rw");
        try {
            if (raf.length() != total) {
                raf.setLength(total);
            }
        } finally {
            Http.closeQuietly(raf);
        }

        final AtomicInteger next = new AtomicInteger(0);
        final AtomicLong doneBytes = new AtomicLong(already);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<IOException> fatal = new AtomicReference<>();
        final AtomicReference<Http.AbortedException> aborted = new AtomicReference<>();

        final CountDownLatch latch = new CountDownLatch(connections);
        ExecutorService pool = Executors.newFixedThreadPool(connections);

        for (int i = 0; i < connections; i++) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        worker(url, cookie, withReferer, proxy, headers, dst, total, chunks,
                                journal, done, next, doneBytes, stop, fatal, aborted,
                                progress, abort);
                    } catch (Throwable t) {
                        // 任何没预料到的异常都要记下来，否则这一条线程悄悄死掉、
                        // latch 还会照数减到零，主线程以为下完了
                        if (t instanceof Http.AbortedException) {
                            aborted.compareAndSet(null, (Http.AbortedException) t);
                        } else {
                            Log.w(TAG, "下载线程异常退出", t);
                            fatal.compareAndSet(null,
                                    t instanceof IOException ? (IOException) t
                                            : new IOException("下载线程异常：" + t));
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop.set(true);
            pool.shutdownNow();
            throw new IOException("下载被中断");
        } finally {
            pool.shutdown();
        }

        if (aborted.get() != null) {
            throw aborted.get();
        }
        IOException e = fatal.get();
        if (e != null) {
            throw e;
        }

        // 全部下完才删台账 —— 只要还剩一块，就不能让「长度是满的」
        // 这个假象离开这里
        if (!jf.delete()) {
            Log.w(TAG, "台账删除失败，下次会重下一遍：" + jf.getName());
        }
        Log.i(TAG, "多连接下载完成：" + Fmt.bytes(doneBytes.get()) + " / " + Fmt.bytes(total));
        return doneBytes.get();
    }

    private static void worker(String url, String cookie, boolean withReferer, Proxy proxy,
                               Map<String, String> headers, File dst, long total, int chunks,
                               File journal, Set<Integer> done, AtomicInteger next,
                               AtomicLong doneBytes, AtomicBoolean stop,
                               AtomicReference<IOException> fatal,
                               AtomicReference<Http.AbortedException> aborted,
                               Http.Progress progress, Http.Abort abort) throws IOException {
        RandomAccessFile out = new RandomAccessFile(dst, "rw");
        try {
            while (true) {
                if (stop.get() || aborted.get() != null || fatal.get() != null) {
                    return;
                }
                int idx = -1;
                // 领一块还没人领的
                while (true) {
                    int c = next.getAndIncrement();
                    if (c >= chunks) {
                        return;   // 没活了
                    }
                    if (!done.contains(c)) {
                        idx = c;
                        break;
                    }
                }

                IOException last = null;
                boolean ok = false;
                for (int attempt = 0; attempt < CHUNK_TRY && !ok; attempt++) {
                    if (stop.get() || aborted.get() != null) {
                        return;
                    }
                    try {
                        fetchChunk(url, cookie, withReferer, proxy, headers, out,
                                idx, total, doneBytes, stop, progress, abort);
                        ok = true;
                    } catch (Http.AbortedException ae) {
                        aborted.compareAndSet(null, ae);
                        return;
                    } catch (Unsupported u) {
                        // 服务端不肯按区间给：这不是「重试能好」的错，
                        // 交给上层退回单连接
                        fatal.compareAndSet(null, u);
                        stop.set(true);
                        return;
                    } catch (IOException e) {
                        last = e;
                        Log.w(TAG, "第 " + idx + " 块第 " + (attempt + 1) + " 次失败："
                                + e.getMessage());
                    }
                }
                if (!ok) {
                    fatal.compareAndSet(null, last != null ? last
                            : new IOException("第 " + idx + " 块下载失败"));
                    stop.set(true);
                    return;
                }

                done.add(idx);
                markDone(journal, idx);
            }
        } finally {
            Http.closeQuietly(out);
        }
    }

    /** 下第 {@code idx} 块，直接写进文件的对应偏移。 */
    private static void fetchChunk(String url, String cookie, boolean withReferer, Proxy proxy,
                                   Map<String, String> headers, RandomAccessFile out,
                                   int idx, long total, AtomicLong doneBytes, AtomicBoolean stop,
                                   Http.Progress progress, Http.Abort abort) throws IOException {
        long start = chunkStart(idx);
        long end = chunkEnd(idx, total);
        long want = end - start + 1L;

        HttpURLConnection c = Http.open(url, cookie, withReferer, proxy, headers);
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        InputStream in = null;
        // 这一块**暂时**记进进度的字节数。中途失败要原数退回去：
        // 失败的那一块不会被记进台账，下次会整块重下，现在把它算成已下
        // 就是骗用户 —— 进度条会跑到 100% 然后卡住等重下。
        long tentative = 0L;
        try {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                // 我们要的是一段，服务端却准备给整份 —— 它忽略了 Range。
                // 再下下去就是「每条连接都下一整份」，必须立刻停手。
                throw new Unsupported("服务端忽略了 Range（HTTP 200）");
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }

            in = c.getInputStream();
            out.seek(start);
            byte[] buf = new byte[BUF];
            long got = 0L;
            long sinceReport = 0L;
            int n;
            while (got < want && (n = in.read(buf, 0, (int) Math.min(buf.length, want - got))) > 0) {
                out.write(buf, 0, n);
                got += n;
                sinceReport += n;
                tentative += n;
                if (sinceReport >= REPORT_EVERY) {
                    sinceReport = 0L;
                    if (progress != null) {
                        progress.onBytes(doneBytes.get() + tentative, total);
                    }
                }
                if (abort != null && abort.shouldStop()) {
                    // 用户叫停。这一块没下完、也不会记进台账，所以**不计入**
                    // 进度：字节数始终只算「确定下好了的整块」。
                    // 写进文件的那些字节留着 —— 下次领到这一块会从块首
                    // 重新覆盖，不会和已有的数据冲突。
                    throw new Http.AbortedException(doneBytes.get());
                }
            }
            if (got != want) {
                throw new IOException("只拿到 " + got + " / " + want + " 字节");
            }
            // 块下完了，把这一块的字节数**真正记上**。
            // 前面一路都只是「doneBytes + tentative」地预览，因为那时这一块
            // 还有可能失败、记上去就得再退回来。走到这里它才算数。
            long now = doneBytes.addAndGet(tentative);
            if (progress != null) {
                progress.onBytes(now, total);
            }
            // 已经记进 doneBytes，不能再让 catch 退一遍
            tentative = 0L;
        } catch (IOException e) {
            // 没成功：把这一块暂时记上的字节数退回去，保证进度反映的
            // 始终是「确定下好了的」那些块
            if (tentative > 0) {
                doneBytes.addAndGet(-tentative);
            }
            throw e;
        } finally {
            Http.closeQuietly(in);
            c.disconnect();
        }
    }
}
