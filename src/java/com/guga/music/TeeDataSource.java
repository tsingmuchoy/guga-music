package com.guga.music;

import android.media.MediaDataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** 边播边存的数据源：后台线程顺序把整流下到 .part 文件（支持断点续传），
 *  播放器优先从已下载部分读；读到未下载区先等一小会儿，等不到就用一次性
 *  Range 请求补这一口（不写缓存）。下载线程被 abort 后，未下完的 .part 保留待续。 */
public class TeeDataSource extends MediaDataSource {

    public interface Done {
        void onComplete(File part);
        void onFail();
    }

    private final String url;
    private final Map<String, String> headers;
    private final File part;
    private final Done done;
    private final Object lock = new Object();

    private volatile long total = -1;
    private volatile long downloaded = 0;
    private volatile boolean failed = false;
    private volatile boolean aborted = false;
    private volatile boolean completed = false;

    public TeeDataSource(String url, Map<String, String> headers, File part, Done done) {
        this.url = url;
        this.headers = headers;
        this.part = part;
        this.done = done;
        if (part.exists()) downloaded = part.length();
        Thread t = new Thread(this::downloadLoop, "tee-dl");
        t.setDaemon(true);
        t.start();
    }

    public void abort() {
        aborted = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    public boolean isCompleted() { return completed; }

    private HttpURLConnection open(long rangeFrom) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
        return c;
    }

    private void downloadLoop() {
        long from = part.exists() ? part.length() : 0;
        HttpURLConnection c = null;
        try {
            c = open(from);
            int code = c.getResponseCode();
            if (from > 0 && code == HttpURLConnection.HTTP_OK) {
                from = 0; // 服务器不支持续传，从头来
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("http " + code);
            }
            long len = c.getContentLengthLong();
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                String cr = c.getHeaderField("Content-Range");
                long tot = -1;
                if (cr != null && cr.contains("/")) {
                    try { tot = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) {}
                }
                total = tot > 0 ? tot : (len > 0 ? from + len : -1);
            } else {
                total = len;
            }
            downloaded = from;
            synchronized (lock) { lock.notifyAll(); }
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, from > 0)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!aborted && (n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    synchronized (lock) { lock.notifyAll(); }
                }
                out.flush();
            }
            if (aborted) return;
            if (total > 0 && downloaded < total) throw new Exception("short " + downloaded + "/" + total);
            completed = true;
            synchronized (lock) { lock.notifyAll(); }
            if (done != null) done.onComplete(part);
        } catch (Exception e) {
            failed = true;
            synchronized (lock) { lock.notifyAll(); }
            if (done != null) done.onFail();
        } finally {
            if (c != null) c.disconnect();
        }
    }

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

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) {
        if (size == 0) return 0;
        if (total >= 0 && position >= total) return -1;
        // 等顺序下载追上（最多约 6 秒）
        long deadline = System.currentTimeMillis() + 6000;
        synchronized (lock) {
            while (position >= downloaded && !completed && !failed && !aborted
                    && System.currentTimeMillis() < deadline) {
                try { lock.wait(150); } catch (InterruptedException e) { break; }
            }
        }
        if (position < downloaded) {
            try {
                return readFromPart(position, buffer, offset, size);
            } catch (Exception ignored) {}
        }
        if (aborted) return -1;
        // 远跳/等不到：一次性 Range 直读这一口（不写缓存）
        HttpURLConnection c = null;
        try {
            c = open(position);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return -1;
            try (InputStream in = c.getInputStream()) {
                int want = size;
                if (total >= 0) want = (int) Math.min(want, Math.max(0, total - position));
                if (want <= 0) return -1;
                int got = 0;
                while (got < want) {
                    int n = in.read(buffer, offset + got, want - got);
                    if (n == -1) break;
                    got += n;
                }
                return got > 0 ? got : -1;
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
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
