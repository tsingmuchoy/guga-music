package com.guga.music.flac;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * FLAC-in-MP4 解复用器（Apollo 新增，非 vendored）：把 fMP4/MP4 封装的「FLAC」流
 * 剥壳成一条裸 FLAC 字节流，喂给 Nayuki 解码器——B 站 Hi-Res「FLAC」流并非裸 FLAC，
 * 而是 FLAC-in-MP4（stsd 'fLaC' 样本项 + dfLa 配置盒），系统解码器与裸流解码器
 * 都不认这种封装（真机实锤 Invalid magic string），必须先剥壳。
 *
 * 只实现本任务需要的子集：
 * - 顶层 box 遍历（ftyp/moov/moof/mdat/free/skip/wide/uuid 等按 size 跳过，支持 largesize）；
 * - moov→trak→mdia→minf→stbl→stsd 找 'fLaC' 项，取 dfLa 里的原生 FLAC 元数据块
 *   （跳过 dfLa 的 version/flags 4 字节，余下即原生流的元数据块序列，首块必为 STREAMINFO）；
 * - 采样枚举：分片走 moof→traf→(tfhd/trex 默认值)→trun，整文件走 stco/co64+stsc+stsz；
 * - 虚拟裸流 = "fLaC" + dfLa 元数据块 + 各采样字节原样拼接（FLAC-in-MP4 的映射
 *   规定采样即帧数据，拼接与原生流的帧区字节等价）。
 *
 * 数据经 TeeFlacInput.Source 随机读（本地文件/在线 tee 通用）；moov 在尾时
 * 在线源要等数据/Range 补拉到位，属预期行为（调用方记 Diag 耗时）。
 */
public final class Mp4FlacDemux {

    /** 解析结果 + 虚拟流的段映射（采样段按序指向源内偏移） */
    public final boolean fragmented;
    public final int sampleCount;
    private final byte[] header;        // "fLaC" + 元数据块（虚拟流的头段，常驻内存）
    private final long[] segSrc;        // 每采样在源中的起始偏移
    private final int[] segLen;         // 每采样字节数
    private final long[] segVirtEnd;    // 每采样在虚拟流中的结束偏移（累计，含头段）
    private final long virtualLength;
    private final TeeFlacInput.Source src;

    private Mp4FlacDemux(TeeFlacInput.Source src, byte[] header, boolean fragmented,
                         List<long[]> segs) {
        this.src = src;
        this.header = header;
        this.fragmented = fragmented;
        this.sampleCount = segs.size();
        segSrc = new long[sampleCount];
        segLen = new int[sampleCount];
        segVirtEnd = new long[sampleCount];
        long v = header.length;
        for (int i = 0; i < sampleCount; i++) {
            segSrc[i] = segs.get(i)[0];
            segLen[i] = (int) segs.get(i)[1];
            v += segLen[i];
            segVirtEnd[i] = v;
        }
        virtualLength = v;
    }

    /** 以本解复用结果打开一个可喂给 FlacDecoder 的低层输入 */
    public FlacLowLevelInput openInput() {
        return new DemuxInput(this);
    }

    // ---------------- 容器识别 ----------------

    /** 嗅探：偏移 4 处是已知顶层 box 类型则判为 MP4 系容器 */
    public static boolean looksLikeMp4(byte[] head) {
        if (head == null || head.length < 8) return false;
        String t = new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (t) {
            case "ftyp": case "styp": case "moov": case "moof": case "mdat":
            case "free": case "skip": case "wide": case "pidx": case "sidx":
                return true;
            default:
                return false;
        }
    }

    // ---------------- 解析 ----------------

    private static final class Cursor {
        final TeeFlacInput.Source src;
        long len;   // 源总长，未知为 -1
        Cursor(TeeFlacInput.Source src) { this.src = src; this.len = src.length(); }

        int readAt(long pos, byte[] buf, int off, int n) throws IOException {
            int got = 0;
            while (got < n) {
                int r = src.readAt(pos + got, buf, off + got, n - got);
                if (r <= 0) break;
                got += r;
            }
            return got;
        }

        byte[] readExact(long pos, int n, String what) throws IOException {
            byte[] b = new byte[n];
            int got = readAt(pos, b, 0, n);
            if (got < n) throw new EOFException("读 " + what + " 时源提前结束（@" + pos + " 需 " + n + " 得 " + got + "）");
            return b;
        }

        /** 读一个 box 头：返回 {内容起始偏移, box 总大小, 类型}；文件结束返回 null */
        BoxHead boxAt(long pos) throws IOException {
            byte[] h = new byte[8];
            if (readAt(pos, h, 0, 8) < 8) return null;
            long size = u32(h, 0);
            String type = latin(h, 4, 4);
            int hdr = 8;
            if (size == 1) {
                byte[] e = new byte[8];
                if (readAt(pos + 8, e, 0, 8) < 8) return null;
                size = u64(e, 0);
                hdr = 16;
            } else if (size == 0) {
                size = (len > pos) ? (len - pos) : -1;   // 到文件尾（总长未知时为 -1：调用方按内容自限）
            }
            return new BoxHead(pos, size, hdr, type);
        }
    }

    private static final class BoxHead {
        final long start, size, content, end;
        final String type;
        BoxHead(long start, long size, int hdr, String type) {
            this.start = start;
            this.size = size;
            this.content = start + hdr;
            this.end = size >= 0 ? start + size : -1;
            this.type = type;
        }
    }

    /** 在 [start,end) 内顺序找子 box，返回其 BoxHead；找不到返回 null */
    private static BoxHead findChild(Cursor c, long start, long end, String want) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHead b = c.boxAt(pos);
            if (b == null || b.size < 0) return null;
            if (b.type.equals(want)) return b;
            if (b.size <= 0) return null;
            pos += b.size;
        }
        return null;
    }

    /** 在字节数组内顺序找子 box，返回 {内容偏移, 总大小}（数组内相对偏移）；找不到返回 null */
    private static int[] findChildIn(byte[] buf, int start, int end, String want) {
        int pos = start;
        while (pos + 8 <= end) {
            int size = (int) u32(buf, pos);
            String type = latin(buf, pos + 4, 4);
            int hdr = 8;
            if (size == 1) { size = (int) u64(buf, pos + 8); hdr = 16; }
            else if (size == 0) size = end - pos;
            if (size < hdr) return null;
            if (type.equals(want)) return new int[]{pos + hdr, size};
            pos += size;
        }
        return null;
    }

    public static Mp4FlacDemux parse(TeeFlacInput.Source src) throws IOException {
        Cursor c = new Cursor(src);

        // 第一遍顶层遍历：记 moov、每个 moof 及其后随 mdat 的数据起始
        BoxHead moov = null;
        List<long[]> moofs = new ArrayList<>();   // {moofStart, moofEnd, mdatDataStart(-1 无)}
        long scan = 0;
        long guard = 0;
        while (true) {
            BoxHead b = c.boxAt(scan);
            if (b == null) break;
            if ("moov".equals(b.type)) moov = b;
            else if ("moof".equals(b.type)) {
                long mdatData = -1;
                if (b.end > 0) {
                    BoxHead after = c.boxAt(b.end);
                    if (after != null && "mdat".equals(after.type)) mdatData = after.content;
                }
                moofs.add(new long[]{b.start, b.end, mdatData});
            }
            if (b.size < 0) break;                 // size=0 的收尾 box
            scan += b.size;
            if (b.size == 0 || ++guard > 100000) break;
            if (c.len > 0 && scan >= c.len) break;
        }
        if (moov == null) throw new DataFormatException("MP4 内未找到 moov");
        if (moov.size <= 0 || moov.size > 64L * 1024 * 1024)
            throw new DataFormatException("moov 大小异常：" + moov.size);
        byte[] moovBuf = c.readExact(moov.content, (int) (moov.size - (moov.content - moov.start)), "moov");

        MoovInfo mi = parseMoov(moovBuf);
        if (mi.dfla == null) throw new DataFormatException("stsd 中未找到 fLaC/dfLa");

        byte[] header = new byte[4 + mi.dfla.length];
        header[0] = 'f'; header[1] = 'L'; header[2] = 'a'; header[3] = 'C';
        System.arraycopy(mi.dfla, 0, header, 4, mi.dfla.length);
        // dfLa 首块必须是 STREAMINFO（块头：type=0，长度 34）
        if (mi.dfla.length < 4 + 34 || (mi.dfla[0] & 0x7F) != 0 || u24(mi.dfla, 1) != 34)
            throw new DataFormatException("dfLa 首元数据块不是 STREAMINFO");

        List<long[]> segs = new ArrayList<>();
        boolean fragmented = !moofs.isEmpty();
        if (fragmented) {
            collectFragmented(c, moofs, mi, segs);
        } else {
            collectWhole(mi, segs);
        }
        if (segs.isEmpty()) throw new DataFormatException("MP4 内未枚举到任何 FLAC 采样");
        return new Mp4FlacDemux(src, header, fragmented, segs);
    }

    // ---------------- moov ----------------

    private static final class Trex {
        long dur, size, flags;
    }

    private static final class MoovInfo {
        byte[] dfla;               // dfLa 元数据块序列（已剥 version/flags）
        long trackId = -1;
        long[] chunkOffsets;       // stco/co64
        int[] stscFirst, stscPerChunk;
        int[] sampleSizes;         // stsz 展开（统一样本大小时按需生成）
        int sampleSizeUniform;
        int sampleCount;
        final java.util.Map<Long, Trex> trex = new java.util.HashMap<>();
    }

    private static MoovInfo parseMoov(byte[] moov) throws IOException {
        MoovInfo mi = new MoovInfo();
        // mvex→trex 默认值（分片时 tfhd/trun 缺项的兜底）
        int[] mvex = findChildIn(moov, 0, moov.length, "mvex");
        if (mvex != null) {
            int p = mvex[0], e = mvex[0] + mvex[1] - 8;
            while (p + 8 <= e) {
                int[] ch = findChildIn(moov, p, e, "trex");
                if (ch == null) break;
                byte[] tb = moov;
                int q = ch[0];
                // trex: ver/flags(4) trackID(4) descIdx(4) dur(4) size(4) flags(4)
                Trex t = new Trex();
                long tid = u32(tb, q + 4);
                t.dur = u32(tb, q + 12);
                t.size = u32(tb, q + 16);
                t.flags = u32(tb, q + 20);
                mi.trex.put(tid, t);
                p = ch[0] - 8 + ch[1];
            }
        }
        // 遍历 trak 找带 fLaC 的
        int pos = 0;
        while (pos + 8 <= moov.length) {
            int[] tr = findChildIn(moov, pos, moov.length, "trak");
            if (tr == null) break;
            int tStart = tr[0] - 8, tEnd = tr[0] - 8 + tr[1];
            pos = tEnd;
            int[] tkhd = findChildIn(moov, tr[0], tEnd, "tkhd");
            long tid = -1;
            if (tkhd != null) {
                int ver = moov[tkhd[0]] & 0xFF;
                tid = u32(moov, tkhd[0] + (ver == 1 ? 20 : 12));
            }
            int[] mdia = findChildIn(moov, tr[0], tEnd, "mdia");
            if (mdia == null) continue;
            int[] minf = findChildIn(moov, mdia[0], tEnd, "minf");
            if (minf == null) continue;
            int[] stbl = findChildIn(moov, minf[0], tEnd, "stbl");
            if (stbl == null) continue;
            byte[] dfla = extractDfla(moov, stbl[0], stbl[0] - 8 + stbl[1]);
            if (dfla == null) continue;   // 不是 FLAC 轨
            mi.dfla = dfla;
            mi.trackId = tid;
            parseStblTables(moov, stbl[0], stbl[0] - 8 + stbl[1], mi);
            return mi;
        }
        throw new DataFormatException("moov 内未找到 FLAC 音轨");
    }

    /** stbl→stsd→'fLaC' 条目→子 box 'dfLa'，返回剥掉 version/flags 的元数据块序列 */
    private static byte[] extractDfla(byte[] moov, int stblStart, int stblEnd) {
        int[] stsd = findChildIn(moov, stblStart, stblEnd, "stsd");
        if (stsd == null) return null;
        int p = stsd[0] + 4;                       // 跳 ver/flags
        int cnt = (int) u32(moov, p);
        p += 4;
        for (int i = 0; i < cnt; i++) {
            int eSize = (int) u32(moov, p);
            String eType = latin(moov, p + 4, 4);
            if ("fLaC".equals(eType)) {
                // 音频样本项固定布局 28 字节后是子 box 区
                int[] df = findChildIn(moov, p + 8 + 28, p + eSize, "dfLa");
                if (df == null) return null;
                int payloadLen = df[1] - 8;
                if (payloadLen <= 4) return null;
                byte[] out = new byte[payloadLen - 4];
                System.arraycopy(moov, df[0] + 4, out, 0, out.length);
                return out;
            }
            p += eSize;
            if (eSize <= 0) break;
        }
        return null;
    }

    private static void parseStblTables(byte[] moov, int s, int e, MoovInfo mi) {
        int[] stco = findChildIn(moov, s, e, "stco");
        int[] co64 = stco == null ? findChildIn(moov, s, e, "co64") : null;
        if (stco != null) {
            int n = (int) u32(moov, stco[0] + 4);
            mi.chunkOffsets = new long[n];
            for (int i = 0; i < n; i++) mi.chunkOffsets[i] = u32(moov, stco[0] + 8 + i * 4);
        } else if (co64 != null) {
            int n = (int) u32(moov, co64[0] + 4);
            mi.chunkOffsets = new long[n];
            for (int i = 0; i < n; i++) mi.chunkOffsets[i] = u64(moov, co64[0] + 8 + i * 8);
        }
        int[] stsc = findChildIn(moov, s, e, "stsc");
        if (stsc != null) {
            int n = (int) u32(moov, stsc[0] + 4);
            mi.stscFirst = new int[n];
            mi.stscPerChunk = new int[n];
            for (int i = 0; i < n; i++) {
                mi.stscFirst[i] = (int) u32(moov, stsc[0] + 8 + i * 12);
                mi.stscPerChunk[i] = (int) u32(moov, stsc[0] + 12 + i * 12);
            }
        }
        int[] stsz = findChildIn(moov, s, e, "stsz");
        if (stsz != null) {
            mi.sampleSizeUniform = (int) u32(moov, stsz[0] + 4);
            mi.sampleCount = (int) u32(moov, stsz[0] + 8);
            if (mi.sampleSizeUniform == 0 && mi.sampleCount > 0) {
                mi.sampleSizes = new int[mi.sampleCount];
                for (int i = 0; i < mi.sampleCount; i++)
                    mi.sampleSizes[i] = (int) u32(moov, stsz[0] + 12 + i * 4);
            }
        }
    }

    /** 整文件采样枚举：stco/co64 + stsc + stsz */
    private static void collectWhole(MoovInfo mi, List<long[]> segs) throws IOException {
        if (mi.chunkOffsets == null || mi.stscFirst == null || mi.sampleCount <= 0)
            throw new DataFormatException("整文件 MP4 缺采样表（stco/stsc/stsz）");
        int sampleIdx = 0;
        int scIdx = 0;
        for (int ch = 0; ch < mi.chunkOffsets.length; ch++) {
            while (scIdx + 1 < mi.stscFirst.length && mi.stscFirst[scIdx + 1] <= ch + 1) scIdx++;
            int per = mi.stscPerChunk[scIdx];
            long pos = mi.chunkOffsets[ch];
            for (int k = 0; k < per && sampleIdx < mi.sampleCount; k++) {
                int size = mi.sampleSizeUniform > 0 ? mi.sampleSizeUniform : mi.sampleSizes[sampleIdx];
                segs.add(new long[]{pos, size});
                pos += size;
                sampleIdx++;
            }
        }
        if (sampleIdx != mi.sampleCount)
            throw new DataFormatException("采样表展开数 " + sampleIdx + " 与 stsz 声明 " + mi.sampleCount + " 不符");
    }

    // ---------------- 分片采样枚举 ----------------

    private static void collectFragmented(Cursor c, List<long[]> moofs, MoovInfo mi,
                                            List<long[]> segs) throws IOException {
        long lastDataEnd = -1;   // 无显式 base 时的推省游标（跨 fragment 连续）
        for (long[] mf : moofs) {
            long moofStart = mf[0], moofEnd = mf[1], mdatData = mf[2];
            if (moofEnd <= moofStart || moofEnd - moofStart > 16L * 1024 * 1024) continue;
            byte[] buf = c.readExact(moofStart, (int) (moofEnd - moofStart), "moof");
            int moofContent = 8;   // buf 以 moof box 起点为 0 基准时其内容偏移（box 头 8 或 16，由下行修正）
            // boxAt 口径：buf[0..8) 是 moof 头；largesize 罕见，按头内 size 字段复核
            long declared = u32(buf, 0);
            moofContent = declared == 1 ? 16 : 8;
            int p = moofContent;
            int end = buf.length;
            while (p + 8 <= end) {
                int[] traf = findChildIn(buf, p, end, "traf");
                if (traf == null) break;
                parseTraf(buf, traf[0], traf[0] - 8 + traf[1], moofStart, mdatData, mi, segs,
                        lastDataEnd);
                if (!segs.isEmpty()) {
                    long[] last = segs.get(segs.size() - 1);
                    lastDataEnd = last[0] + last[1];
                }
                p = traf[0] - 8 + traf[1];
            }
        }
    }

    private static void parseTraf(byte[] buf, int s, int e, long moofStart, long mdatData,
                                   MoovInfo mi, List<long[]> segs, long lastDataEnd) throws IOException {
        int[] tfhd = findChildIn(buf, s, e, "tfhd");
        if (tfhd == null) return;
        int q = tfhd[0];
        int flags = (int) u24(buf, q + 1);
        long trackId = u32(buf, q + 4);
        q += 8;
        long baseOff = -1;
        long defDur = -1, defSize = -1;
        if ((flags & 0x000001) != 0) { baseOff = u64(buf, q); q += 8; }
        if ((flags & 0x000002) != 0) { q += 4; }
        if ((flags & 0x000008) != 0) { defDur = u32(buf, q); q += 4; }
        if ((flags & 0x000010) != 0) { defSize = u32(buf, q); q += 4; }
        if ((flags & 0x000020) != 0) { q += 4; }
        if (mi.trackId >= 0 && trackId != mi.trackId) return;   // 别轨的分片
        Trex trex = mi.trex.get(trackId);
        if (defSize < 0 && trex != null) defSize = trex.size;
        if (baseOff < 0) {
            if ((flags & 0x020000) != 0) baseOff = moofStart;          // default-base-is-moof
            else if (lastDataEnd >= 0) baseOff = lastDataEnd;
            else baseOff = moofStart;
        }
        long cursor = -1;
        int p = s;
        while (p + 8 <= e) {
            int[] trun = findChildIn(buf, p, e, "trun");
            if (trun == null) break;
            p = trun[0] - 8 + trun[1];
            int r = trun[0];
            int tflags = (int) u24(buf, r + 1);
            int count = (int) u32(buf, r + 4);
            r += 8;
            long dataOff = -1;
            if ((tflags & 0x000001) != 0) { dataOff = (int) u32(buf, r); r += 4; }
            if ((tflags & 0x000004) != 0) { r += 4; }
            boolean hasDur = (tflags & 0x000100) != 0;
            boolean hasSize = (tflags & 0x000200) != 0;
            boolean hasFlags = (tflags & 0x000400) != 0;
            boolean hasCto = (tflags & 0x000800) != 0;
            long runStart;
            if (dataOff >= 0) runStart = baseOff + dataOff;
            else if (cursor >= 0) runStart = cursor;
            else if (mdatData >= 0) runStart = mdatData;
            else throw new DataFormatException("trun 无 data-offset 且无从推断数据起点");
            for (int i = 0; i < count; i++) {
                long size;
                if (hasDur) r += 4;
                if (hasSize) { size = u32(buf, r); r += 4; }
                else if (defSize >= 0) size = defSize;
                else throw new DataFormatException("trun 与 tfhd/trex 均无采样大小");
                if (hasFlags) r += 4;
                if (hasCto) r += 4;
                segs.add(new long[]{runStart, size});
                runStart += size;
            }
            cursor = runStart;
        }
    }

    // ---------------- 基础读数 ----------------

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static long u24(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 16) | ((b[off + 1] & 0xFFL) << 8) | (b[off + 2] & 0xFFL);
    }

    private static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }

    private static String latin(byte[] b, int off, int n) {
        return new String(b, off, n, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // ---------------- 虚拟流输入 ----------------

    /**
     * 把段映射呈现为一条连续裸 FLAC 流的低层输入：头段走内存、采样段经源随机读
     * （带 2MB 块缓冲摊薄在线 readAt，与 TeeFlacInput 同策略）。支持随机 seekTo——
     * Nayuki 的 seek/重同步在虚拟流上无感工作，位置口径与原生流完全一致。
     */
    private static final class DemuxInput extends AbstractFlacLowLevelInput {
        private static final int BLOCK = 2 * 1024 * 1024;

        private final Mp4FlacDemux d;
        private final byte[] block = new byte[BLOCK];
        private long blockStart = -1;
        private int blockLen = 0;
        private long pos = 0;
        private boolean closed = false;

        DemuxInput(Mp4FlacDemux d) {
            super();
            this.d = d;
        }

        @Override public long getLength() {
            return d.virtualLength;
        }

        @Override public void seekTo(long p) {
            pos = p;
            positionChanged(p);
        }

        @Override protected int readUnderlying(byte[] buf, int off, int len) throws IOException {
            if (closed) throw new IOException("closed");
            if (len <= 0) return 0;
            if (pos >= d.virtualLength) return -1;
            // 头段（内存）
            if (pos < d.header.length) {
                int n = (int) Math.min(len, d.header.length - pos);
                System.arraycopy(d.header, (int) pos, buf, off, n);
                pos += n;
                return n;
            }
            // 采样段：二分定位
            long vOff = pos - d.header.length;
            int lo = 0, hi = d.segVirtEnd.length - 1, idx = hi;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long segStartVirt = (mid == 0 ? d.header.length : d.segVirtEnd[mid - 1]);
                if (pos < segStartVirt) hi = mid - 1;
                else if (pos >= d.segVirtEnd[mid]) lo = mid + 1;
                else { idx = mid; break; }
            }
            long segStartVirt = (idx == 0 ? d.header.length : d.segVirtEnd[idx - 1]);
            long srcPos = d.segSrc[idx] + (pos - segStartVirt);
            int segRemain = (int) (d.segVirtEnd[idx] - pos);
            int want = Math.min(len, segRemain);
            int n = readSrc(srcPos, buf, off, want);
            if (n <= 0) return -1;
            pos += n;
            return n;
        }

        private int readSrc(long srcPos, byte[] buf, int off, int len) throws IOException {
            if (blockStart >= 0 && srcPos >= blockStart && srcPos < blockStart + blockLen) {
                int n = (int) Math.min(len, blockStart + blockLen - srcPos);
                System.arraycopy(block, (int) (srcPos - blockStart), buf, off, n);
                return n;
            }
            int got = 0;
            while (got < block.length) {
                int r = d.src.readAt(srcPos + got, block, got, block.length - got);
                if (r <= 0) break;
                got += r;
            }
            if (got == 0) return -1;
            blockStart = srcPos;
            blockLen = got;
            int n = Math.min(len, got);
            System.arraycopy(block, 0, buf, off, n);
            return n;
        }

        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                try { d.src.close(); } catch (Exception ignored) {}
                super.close();
            }
        }
    }
}
