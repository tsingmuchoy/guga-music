package com.guga.music;

import android.content.Context;
import android.media.MediaDataSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/** 边播边存的数据源（v1.42.2 重构）：
 *  - 主线程（lead）顺序下载整流到 .part，播放器优先读连续已下载前缀；
 *  - total 已知时启用分块并行：2 个预取线程用 Range 领 lead 前方 ≥2 块的块写入对应偏移，
 *    lead 遇到已完成块整块跳过；块位图+前缀长度存 .part.meta 供断点续传（meta 是唯一可信
 *    来源，无 meta 一律从 0 重下，绝不按文件长度猜前缀，避免把预取空洞当好数据）；
 *  - 起播预缓冲垫：开头 1MB 未攒够前 readAt 先等（总上限 5 秒，赶在备节点看门狗之前）；
 *  - readAt 等不到数据的一次性 Range 补拉会写回 .part 对应偏移；
 *  - 卡顿取证：预缓冲达标 / 前沿等待 / Range 补拉 / 下载速度采样写 Diag（带 🐌 前缀）。
 *  下载被 abort 后 .part 与 .meta 保留待续；Done 回调只由 lead 触发一次。 */
public class TeeDataSource extends MediaDataSource {

    public interface Done {
        void onComplete(File part);
        void onFail();
    }

    private static final long BLOCK = 512L * 1024;      // 分块大小
    private static final long CUSHION = 1024L * 1024;    // 预缓冲垫目标
    private static final int PREFETCHERS = 2;

    private final Context ctx;
    private final String url;
    private final Map<String, String> headers;
    private final File part;
    private final File metaFile;
    private final Done done;
    private final Object lock = new Object();
    private final Object fileLock = new Object();       // 所有 RandomAccessFile 写互斥

    private volatile long total = -1;
    private volatile long downloaded = 0;              // 连续已下载前缀（readAt 只读这一段）
    private volatile boolean failed = false;
    private volatile boolean aborted = false;
    private volatile boolean completed = false;
    private volatile long leadPos = 0;                   // lead 当前流位置（预取领块规则用）

    // 分块状态（total 已知且非顺序模式时启用，除注明外在 lock 内访问）
    private boolean[] blockDone;
    private boolean[] blockClaimed;
    private volatile boolean parallelOff = false;        // 服务器不认 Range 时关停预取
    private boolean prefetchStarted = false;
    private Meta pendingMeta;                            // 构造时读到的续传状态，待 lead 校验 total
    private boolean doneFired = false;

    // 预缓冲垫
    private boolean cushionDone = false;
    private long cushionStartAt = -1;

    // 取证
    private long lastFrontierLogAt = 0;
    private int rangeCount = 0;
    private volatile long bytesFetched = 0;              // 实际经网络写入文件的字节（测速用）
    private long lastSpeedBytes = 0;
    private long lastSpeedAt;

    private static class Meta {
        long total;
        long prefix;
        boolean[] done;
    }

    public TeeDataSource(Context ctx, String url, Map<String, String> headers, File part, Done done) {
        this.ctx = ctx.getApplicationContext();
        this.url = url;
        this.headers = headers;
        this.part = part;
        this.metaFile = new File(part.getAbsolutePath() + ".meta");
        this.done = done;
        Meta m = readMeta();
        if (m != null && part.exists() && part.length() >= m.prefix) {
            pendingMeta = m;
            downloaded = m.prefix;
            leadPos = m.prefix;
        } else {
            downloaded = 0;
            leadPos = 0;
        }
        lastSpeedAt = System.currentTimeMillis();
        Thread t = new Thread(this::downloadLoop, "tee-dl");
        t.setDaemon(true);
        t.start();
        Thread s = new Thread(this::speedLoop, "tee-speed");
        s.setDaemon(true);
        s.start();
    }

    public void abort() {
        aborted = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    public boolean isCompleted() { return completed; }

    // ---------------- 连接 ----------------

    private HttpURLConnection open(long rangeFrom) throws Exception {
        return openRange(rangeFrom, -1);
    }

    private HttpURLConnection openRange(long from, long to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (to >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        else if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        return c;
    }

    // ---------------- 续传状态（.meta） ----------------

    private Meta readMeta() {
        try {
            if (!metaFile.exists()) return null;
            String s;
            try (FileInputStream in = new FileInputStream(metaFile)) {
                byte[] b = new byte[(int) Math.min(metaFile.length(), 65536)];
                int n = in.read(b);
                if (n <= 0) return null;
                s = new String(b, 0, n, "UTF-8");
            }
            String[] ln = s.trim().split("\n");
            if (ln.length < 3) return null;
            Meta m = new Meta();
            m.total = Long.parseLong(ln[0].trim());
            m.prefix = Long.parseLong(ln[1].trim());
            if (m.total <= 0 || m.prefix < 0 || m.prefix > m.total) return null;
            int nb = (int) ((m.total + BLOCK - 1) / BLOCK);
            String bits = ln[2].trim();
            if (bits.length() != nb) return null;
            m.done = new boolean[nb];
            for (int i = 0; i < nb; i++) {
                if (bits.charAt(i) == '1') m.done[i] = true;
                else if (bits.charAt(i) != '0') return null;
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** 在 lock 内调用：把当前 total/前缀/位图落盘（文件很小，整写） */
    private void persistMetaLocked() {
        if (blockDone == null || total <= 0) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(total).append('\n').append(downloaded).append('\n');
            for (boolean d : blockDone) sb.append(d ? '1' : '0');
            sb.append('\n');
            try (FileOutputStream out = new FileOutputStream(metaFile)) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    private void deleteMeta() {
        try { metaFile.delete(); } catch (Exception ignored) {}
    }

    // ---------------- 前缀推进（lock 内调用） ----------------

    private long blockEnd(int i) {
        return Math.min(total, (i + 1) * BLOCK);
    }

    private boolean allDoneBeforeLocked(int bi) {
        for (int i = 0; i < bi && i < blockDone.length; i++) {
            if (!blockDone[i]) return false;
        }
        return true;
    }

    /** 前缀只能沿「整块完成」或「lead 正在写的当前块」向前走，绝不跨空洞 */
    private void advancePrefixLocked() {
        if (blockDone == null || total <= 0) return;
        while (downloaded < total) {
            int bi = (int) (downloaded / BLOCK);
            if (bi >= blockDone.length) { downloaded = total; break; }
            if (blockDone[bi]) { downloaded = blockEnd(bi); continue; }
            // lead 正停在本块内且本块之前全完成：前缀可跟到 lead 位置
            if (leadPos > downloaded && (int) (leadPos / BLOCK) == bi && allDoneBeforeLocked(bi)) {
                downloaded = Math.min(leadPos, blockEnd(bi));
                continue;
            }
            break;
        }
    }

    // ---------------- 主下载（lead） ----------------

    private void downloadLoop() {
        HttpURLConnection c = null;
        try {
            long from = downloaded; // meta 恢复的前缀（无 meta 时为 0）
            boolean restarted = false;
            long probedTotal;
            int code;
            // 探明 total（meta 对不上时断开重连一次，从 0 来）
            while (true) {
                c = open(from);
                code = c.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new Exception("http " + code);
                }
                long len = c.getContentLengthLong();
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    String cr = c.getHeaderField("Content-Range");
                    long tot = -1;
                    if (cr != null && cr.contains("/")) {
                        try { tot = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) {}
                    }
                    probedTotal = tot > 0 ? tot : (len > 0 ? from + len : -1);
                } else {
                    probedTotal = len;
                }
                if (pendingMeta != null && probedTotal > 0 && pendingMeta.total != probedTotal && !restarted) {
                    // 续传状态与真实文件对不上：全部作废，从 0 重来
                    restarted = true;
                    try { c.disconnect(); } catch (Exception ignored) {}
                    c = null;
                    synchronized (lock) {
                        pendingMeta = null;
                        downloaded = 0;
                        leadPos = 0;
                    }
                    from = 0;
                    continue;
                }
                break;
            }
            total = probedTotal;
            boolean metaAdopted = false;
            synchronized (lock) {
                if (total > 0) {
                    int nb = (int) ((total + BLOCK - 1) / BLOCK);
                    if (pendingMeta != null && pendingMeta.total == total) {
                        blockDone = pendingMeta.done;
                        metaAdopted = true;
                    } else {
                        blockDone = new boolean[nb];
                        downloaded = 0;
                        leadPos = 0;
                        from = 0;
                    }
                    blockClaimed = new boolean[nb];
                    pendingMeta = null;
                }
                lock.notifyAll();
            }
            long pos = code == HttpURLConnection.HTTP_OK && from > 0 ? 0 : from;
            // OK 且 from>0＝服务器没认 Range、从头发流：meta 采纳时位图仍有效（字节对得上），
            // 未采纳时上面已把 from 清零；两种情况都不用截断重写以外的数据
            if (total > 0 && !metaAdopted && pos == 0 && part.exists() && part.length() > 0) {
                synchronized (fileLock) {
                    try (RandomAccessFile z = new RandomAccessFile(part, "rw")) { z.setLength(0); }
                    catch (Exception ignored) {}
                }
            }
            leadPos = pos;
            if (blockDone != null && !prefetchStarted) {
                prefetchStarted = true;
                for (int i = 0; i < PREFETCHERS; i++) {
                    Thread p = new Thread(this::prefetchLoop, "tee-pre" + i);
                    p.setDaemon(true);
                    p.start();
                }
            }
            try (InputStream in = c.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                byte[] buf = new byte[64 * 1024];
                while (!aborted) {
                    if (total > 0 && pos >= total) break;
                    int bi = total > 0 ? (int) (pos / BLOCK) : -1;
                    if (bi >= 0 && bi < blockDone.length) {
                        boolean doneBlk;
                        synchronized (lock) { doneBlk = blockDone[bi]; }
                        if (doneBlk) {
                            // 已完成块：把流里对应字节丢弃，整块跳过
                            long skip = blockEnd(bi) - pos;
                            if (!skipFully(in, skip)) throw new Exception("eof during skip");
                            pos += skip;
                            leadPos = pos;
                            synchronized (lock) { advancePrefixLocked(); lock.notifyAll(); }
                            continue;
                        }
                    }
                    int want = buf.length;
                    if (bi >= 0) want = (int) Math.min(want, blockEnd(bi) - pos);
                    int n = in.read(buf, 0, want);
                    if (n == -1) break;
                    synchronized (fileLock) {
                        raf.seek(pos);
                        raf.write(buf, 0, n);
                    }
                    bytesFetched += n;
                    pos += n;
                    leadPos = pos;
                    synchronized (lock) {
                        if (blockDone == null) {
                            downloaded = pos; // total 未知：顺序模式，前缀就是流位置
                        } else {
                            if (bi >= 0 && pos == blockEnd(bi)) {
                                blockDone[bi] = true;
                                persistMetaLocked();
                            }
                            advancePrefixLocked();
                        }
                        lock.notifyAll();
                    }
                }
            }
            if (aborted) return;
            synchronized (lock) {
                if (total > 0 && downloaded < total) throw new Exception("short " + downloaded + "/" + total);
                if (total <= 0 && !aborted) {
                    // total 未知时以流 EOF 为完成（沿用旧语义）
                }
                completed = true;
                lock.notifyAll();
            }
            fireComplete();
        } catch (Exception e) {
            if (aborted) return;
            synchronized (lock) {
                failed = true;
                lock.notifyAll();
            }
            fireFail();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean skipFully(InputStream in, long skip) throws Exception {
        byte[] tmp = new byte[8192];
        long left = skip;
        while (left > 0) {
            int n = in.read(tmp, 0, (int) Math.min(tmp.length, left));
            if (n == -1) return false;
            left -= n;
        }
        return true;
    }

    private void fireComplete() {
        boolean fire;
        synchronized (lock) {
            fire = !doneFired;
            doneFired = true;
        }
        if (fire) {
            deleteMeta(); // 已转正为正式缓存，续传状态作废
            if (done != null) done.onComplete(part);
        }
    }

    private void fireFail() {
        boolean fire;
        synchronized (lock) {
            fire = !doneFired;
            doneFired = true;
        }
        if (fire && done != null) done.onFail();
    }

    // ---------------- 预取线程 ----------------

    /** 领一块：整块位于 lead 位置 + 至少 2 块之后、未完成未领取；无块可领返回 -1 */
    private int claimBlockLocked() {
        if (blockDone == null || parallelOff) return -1;
        long minStart = leadPos + 2 * BLOCK;
        for (int i = 0; i < blockDone.length; i++) {
            if (blockDone[i] || blockClaimed[i]) continue;
            if (i * BLOCK < minStart) continue;
            blockClaimed[i] = true;
            return i;
        }
        return -1;
    }

    private void prefetchLoop() {
        while (!aborted && !failed && !completed && !parallelOff) {
            int idx;
            synchronized (lock) {
                idx = claimBlockLocked();
                if (idx < 0) {
                    try { lock.wait(200); } catch (InterruptedException e) { return; }
                    continue;
                }
            }
            boolean ok = fetchBlock(idx);
            synchronized (lock) {
                if (idx < blockClaimed.length) blockClaimed[idx] = false;
                if (ok && !aborted) {
                    blockDone[idx] = true;
                    advancePrefixLocked();
                    persistMetaLocked();
                }
                lock.notifyAll();
            }
        }
    }

    private boolean fetchBlock(int idx) {
        long start = idx * BLOCK;
        long end = blockEnd(idx);
        long need = end - start;
        HttpURLConnection c = null;
        try {
            c = openRange(start, end - 1);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                parallelOff = true; // 服务器不认 Range：关停并行，老实顺序下
                return false;
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) return false;
            long got = 0;
            try (InputStream in = c.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                byte[] buf = new byte[64 * 1024];
                while (got < need && !aborted) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, need - got));
                    if (n == -1) break;
                    synchronized (fileLock) {
                        raf.seek(start + got);
                        raf.write(buf, 0, n);
                    }
                    got += n;
                    bytesFetched += n;
                }
            }
            return got == need;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------- 测速采样（取证） ----------------

    private void speedLoop() {
        while (!aborted && !failed && !completed) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            if (now - lastSpeedAt >= 10000 && !completed && !failed && !aborted) {
                long delta = bytesFetched - lastSpeedBytes;
                double secs = (now - lastSpeedAt) / 1000.0;
                long kbps = secs > 0 ? Math.round(delta / secs / 1024.0) : 0;
                if (total > 0) {
                    Diag.log(ctx, String.format(Locale.CHINA,
                            "🐌 下载速度 约 %d KB/s，已下 %.1f/总 %.1f MB", kbps,
                            downloaded / 1048576.0, total / 1048576.0));
                } else {
                    Diag.log(ctx, String.format(Locale.CHINA,
                            "🐌 下载速度 约 %d KB/s，已下 %.1f MB（总量未知）", kbps,
                            downloaded / 1048576.0));
                }
                lastSpeedBytes = bytesFetched;
                lastSpeedAt = now;
            }
        }
    }

    // ---------------- MediaDataSource ----------------

    @Override
    public long getSize() {
        long deadline = System.currentTimeMillis() + 3000;
        synchronized (lock) {
            while (total < 0 && !failed && !aborted && System.currentTimeMillis() < deadline) {
                try { lock.wait(200); } catch (InterruptedException e) { break; }
            }
        }
        return total;
    }

    /** 起播预缓冲垫：读开头区域时先等连续前缀攒到 cushion 目标（总等待上限 5 秒，只等一次） */
    private void awaitCushion(long position) {
        if (cushionDone) return;
        long target = total > 0 ? Math.min(CUSHION, total) : CUSHION;
        if (position >= target) return;
        synchronized (lock) {
            if (cushionDone) return;
            if (cushionStartAt < 0) cushionStartAt = System.currentTimeMillis();
            // 总上限 5 秒：要赶在 PlayerService 备节点看门狗（6 秒）动手之前放行
            long deadline = cushionStartAt + 5000;
            while (downloaded < target && !completed && !failed && !aborted
                    && System.currentTimeMillis() < deadline) {
                try { lock.wait(150); } catch (InterruptedException e) { break; }
            }
            cushionDone = true;
            if (downloaded >= target || completed) {
                long used = System.currentTimeMillis() - cushionStartAt;
                Diag.log(ctx, String.format(Locale.CHINA, "🐌 预缓冲达标 %.1fMB，用时 %.1fs",
                        target / 1048576.0, used / 1000.0));
            }
        }
    }

    /** 等前缀推过 position（锁内等待，下载线程 notify 唤醒），最多 maxMs */
    private void waitForData(long position, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        synchronized (lock) {
            while (position >= downloaded && !completed && !failed && !aborted
                    && System.currentTimeMillis() < deadline) {
                try { lock.wait(150); } catch (InterruptedException e) { break; }
            }
        }
    }

    /** 前沿等待取证：本次 readAt 已在前沿耗 ≥500ms 且仍未拿到数据时记一条（2 秒节流，沿用旧口径） */
    private void logFrontierIfNeeded(long position, long startAt) {
        long waited = System.currentTimeMillis() - startAt;
        if (waited < 500 || position < downloaded) return;
        long now = System.currentTimeMillis();
        boolean logIt;
        synchronized (lock) {
            logIt = now - lastFrontierLogAt >= 2000;
            if (logIt) lastFrontierLogAt = now;
        }
        if (logIt) {
            Diag.log(ctx, String.format(Locale.CHINA,
                    "🐌 前沿等待 %.1fs @ 位置 %.2f/已下 %.2f/总量 %.2f MB",
                    waited / 1000.0, position / 1048576.0, downloaded / 1048576.0,
                    total > 0 ? total / 1048576.0 : 0.0));
        }
    }

    /** readAt 被迫放弃（返回 -1）时留一条状态快照，方便复盘是网络、状态还是超时 */
    private void logReadGiveUp(long position, int rangeTries, String why) {
        Diag.log(ctx, String.format(Locale.CHINA,
                "🐌 readAt 放弃（%s）pos=%d 已下=%d 总量=%d failed=%s completed=%s，Range 重试 %d 次",
                why, position, downloaded, total, failed, completed, rangeTries));
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) {
        if (size == 0) return 0;
        if (total >= 0 && position >= total) return -1;
        awaitCushion(position);
        final long startAt = System.currentTimeMillis();
        // 第一阶段：等顺序/并行下载把前缀推过 position（最多约 6 秒，沿用旧语义）
        waitForData(position, 6000);
        logFrontierIfNeeded(position, startAt);
        // 第二阶段：补拉重试循环。下载还活着且没到文件尾时，一次补拉失败绝不直接 -1
        // ——那会被框架判成 MEDIA_ERROR_IO 误杀整首歌（v1.42.2 实测教训）。
        // 补拉带退避（0.4/0.9/1.6/2.5s…）重试并继续等下载推进；只有下载已失败/已结束、
        // 被 abort、或总等待超过 45 秒上限，才允许返回 -1，且放弃必留快照。
        int rangeTries = 0;
        while (true) {
            if (position < downloaded) {
                try {
                    int n = readFromPart(position, buffer, offset, size);
                    if (n > 0) return n;
                } catch (Exception ignored) {}
            }
            if (aborted) return -1; // 切歌/回退直连的主动拆除，不算放弃、不留痕
            if (failed || completed) {
                logReadGiveUp(position, rangeTries, failed ? "下载失败" : "下载已结束");
                return -1;
            }
            if (total >= 0 && position >= total) return -1;
            rangeTries++;
            int got = rangeFetch(position, buffer, offset, size);
            if (got > 0) return got;
            if (System.currentTimeMillis() - startAt > 45000) {
                logReadGiveUp(position, rangeTries, "等待超时");
                return -1;
            }
            long backoff = rangeTries == 1 ? 400 : rangeTries == 2 ? 900
                    : rangeTries == 3 ? 1600 : 2500;
            synchronized (lock) {
                long until = System.currentTimeMillis() + backoff;
                while (position >= downloaded && !completed && !failed && !aborted
                        && System.currentTimeMillis() < until) {
                    try { lock.wait(150); } catch (InterruptedException e) { return -1; }
                }
            }
            logFrontierIfNeeded(position, startAt);
        }
    }

    /** 一次性 Range 补拉：成功返回字节数（>0），失败返回 -1；读到的字节写回 .part 对应偏移 */
    private int rangeFetch(long position, byte[] buffer, int offset, int size) {
        long rs = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = open(position);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return -1;
            try (InputStream in = c.getInputStream()) {
                if (code == HttpURLConnection.HTTP_OK && position > 0) {
                    // 服务器没认 Range、从 0 回流：先丢弃 position 之前的字节，
                    // 否则拿流的头冒充 position 处的数据，字节错位（审计发现，原实现有此隐患）
                    if (!skipFully(in, position)) return -1;
                }
                int want = size;
                if (total >= 0) want = (int) Math.min(want, Math.max(0, total - position));
                if (want <= 0) return -1;
                int got = 0;
                while (got < want) {
                    int n = in.read(buffer, offset + got, want - got);
                    if (n == -1) break;
                    got += n;
                }
                if (got > 0) {
                    writeBack(position, buffer, offset, got);
                    int k;
                    synchronized (lock) { k = ++rangeCount; }
                    Diag.log(ctx, String.format(Locale.CHINA,
                            "🐌 Range 补拉 @%d 大小 %d B，耗时 %dms（累计第 %d 次）",
                            position, got, System.currentTimeMillis() - rs, k));
                }
                return got > 0 ? got : -1;
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Range 补拉写回：字节落到 .part 对应偏移；整块被覆盖的标记完成，衔接前缀时推进 downloaded */
    private void writeBack(long position, byte[] buffer, int offset, int got) {
        try {
            synchronized (fileLock) {
                try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                    raf.seek(position);
                    raf.write(buffer, offset, got);
                }
            }
            bytesFetched += got;
            synchronized (lock) {
                if (blockDone != null && total > 0) {
                    long end = position + got;
                    int first = (int) (position / BLOCK);
                    int last = (int) ((end - 1) / BLOCK);
                    for (int i = first; i <= last && i < blockDone.length; i++) {
                        if (i * BLOCK >= position && blockEnd(i) <= end) blockDone[i] = true;
                    }
                }
                if (position == downloaded) downloaded += got;
                advancePrefixLocked();
                persistMetaLocked();
                lock.notifyAll();
            }
        } catch (Exception ignored) {}
    }

    private int readFromPart(long position, byte[] buffer, int offset, int size) throws Exception {
        long avail = downloaded - position;
        int want = (int) Math.min(size, avail);
        if (total >= 0) want = (int) Math.min(want, Math.max(0, total - position));
        if (want <= 0) return -1;
        try (RandomAccessFile raf = new RandomAccessFile(part, "r")) {
            raf.seek(position);
            int got = 0;
            while (got < want) {
                int n = raf.read(buffer, offset + got, want - got);
                if (n == -1) break;
                got += n;
            }
            return got > 0 ? got : -1;
        }
    }

    @Override
    public void close() {
        // 不在此停下载：由服务在切歌/回退直连时显式 abort
    }
}
