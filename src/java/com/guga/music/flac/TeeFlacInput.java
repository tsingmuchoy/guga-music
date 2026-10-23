package com.guga.music.flac;

import java.io.IOException;

/**
 * 把 Nayuki 解码器接到任意「随机读」来源之上的低层输入（Apollo 新增，非 vendored）。
 *
 * 内部 2MB 块缓冲把来源的 readAt 调用摊薄：在线 tee 的 readAt 对未下载区域
 * 一次调用代价不小（要等前缀或走 Range 补拉），解码器若小步频读会慢到不可用；
 * 有了块缓冲，顺序解码基本只在跨 2MB 边界时才真正触达来源，随机 seek 亦然。
 * seekTo 只改本地指针、不惊动来源；块缓冲按绝对偏移索引，seek 后仍可命中。
 */
public final class TeeFlacInput extends AbstractFlacLowLevelInput {

    /** 随机读来源：在线路径由 TeeDataSource 适配，离线自检可用普通文件适配 */
    public interface Source {
        /** 总字节数；未知返回 -1 */
        long length();
        /** 从 pos 起最多读 len 字节进 buf[off..]，返回实读数；EOF 返回 -1 */
        int readAt(long pos, byte[] buf, int off, int len) throws IOException;
        /** 释放来源（在线路径为空操作：tee 生命周期归播放服务管） */
        void close();
    }

    private static final int BLOCK = 2 * 1024 * 1024;

    private final Source src;
    private final byte[] block = new byte[BLOCK];
    private long blockStart = -1;   // block[0] 对应的绝对偏移；-1 = 缓冲无效
    private int blockLen = 0;
    private long pos = 0;           // 下一个要吐出的字节的绝对偏移
    private boolean closed = false;

    public TeeFlacInput(Source src) {
        super();
        this.src = src;
    }

    @Override
    public long getLength() {
        return src.length();
    }

    @Override
    public void seekTo(long p) {
        pos = p;
        positionChanged(p);
    }

    @Override
    protected int readUnderlying(byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("closed");
        if (len <= 0) return 0;
        if (blockStart >= 0 && pos >= blockStart && pos < blockStart + blockLen) {
            int n = (int) Math.min(len, blockStart + blockLen - pos);
            System.arraycopy(block, (int) (pos - blockStart), buf, off, n);
            pos += n;
            return n;
        }
        int got = 0;
        while (got < block.length) {
            int n = src.readAt(pos + got, block, got, block.length - got);
            if (n <= 0) break;
            got += n;
        }
        if (got == 0) return -1;
        blockStart = pos;
        blockLen = got;
        int n = Math.min(len, got);
        System.arraycopy(block, 0, buf, off, n);
        pos += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try { src.close(); } catch (Exception ignored) {}
            super.close();
        }
    }
}
