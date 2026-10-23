package com.guga.music;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.guga.music.flac.DataFormatException;
import com.guga.music.flac.FlacDecoder;
import com.guga.music.flac.FlacLowLevelInput;
import com.guga.music.flac.Mp4FlacDemux;
import com.guga.music.flac.SeekableFileFlacInput;
import com.guga.music.flac.StreamInfo;
import com.guga.music.flac.TeeFlacInput;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** FLAC 软件解码引擎（v1.43.0 新增）：纯 Java 解码（vendored Nayuki FLAC library，
 *  LGPL，见 flac/ 目录）→ PCM 直喂 AudioTrack，绕开系统 MediaPlayer/厂商解码器——
 *  部分 B 站 Hi-Res FLAC 的怪封装（高采样率/特殊容器）被系统解码器拒播（what=1/-38），
 *  软解不看厂商解码器菜单，能解就能播。解不了/解坏仍抛回 PlayerService 走既有降档链。
 *
 *  数据来源与既有体系统一：在线歌读 TeeDataSource（边下边播、缓存照写、再播 0 流量），
 *  已缓存歌直接读缓存文件；本类不碰网络与缓存策略。
 *
 *  播放语义对齐 MediaPlayer 路径：
 *  - 位置以 AudioTrack 播放头为准（纪元基线 + 32 位回绕展开），暂停时播放头停走、位置冻结；
 *  - seek：本地文件走解码器原生定位（seek 表/帧同步搜索）；在线流重开解码器顺序
 *    解码跳过（FLAC 解码远快于实时，等待主要是等数据），落地帧按采样精确对齐；
 *  - 本地文件单帧损坏（CRC/格式错误）时用帧同步找回跳过坏帧续播（每首至多 3 次），
 *    在线流不做重同步（随机读代价不可控），直接失败走降档，避免长时间无声。
 *
 *  输出容错（v1.43.2）：真机实锤 24bit 歌建轨成功、软解开播后第一批 write 即被
 *  HAL 回 -3（ERROR_INVALID_OPERATION）。writePcm 因此带容错阶梯：负值先记全
 *  现场（返回值/播放状态/世代/已写帧/通道），再同参重建 AudioTrack 一次；float
 *  通道仍败则降 16bit PCM 重建续播；垂死线程（已 stop/世代已换）的写入失败只
 *  静默退出，绝不上报引擎失败误触发降档。 */
public class FlacEngine {

    public interface Callback {
        /** 元数据与输出通道就绪（回调在解码线程，服务侧需自行切主线程） */
        void onReady(int durationMs, int sampleRate, int channels, int depth);
        /** 全部采样已解码且已播完（自然结束） */
        void onEnded();
        /** 软解失败：why 为简短原因，服务侧据此走降档/跳歌链 */
        void onError(String why);
    }

    private final Context ctx;
    private final Callback cb;
    private final Object gate = new Object();       // 暂停等待 / seek 唤醒
    private final Object headLock = new Object();   // 播放头回绕展开的账本锁

    private volatile boolean stopped = false;
    private volatile boolean playing = false;       // 期望播放态（服务驱动）
    private volatile int runGen = 0;                // 启停世代：start/stop 各递增，垂死线程据此静默（v1.43.2）
    private volatile long seekTargetSamples = -1;   // 待处理 seek（绝对采样序号），-1 无
    private volatile int pendingSeekMs = -1;        // 就绪前收到的 seek（毫秒）
    private volatile boolean decodeEof = false;

    private Thread thread;
    private TeeDataSource savedTee;
    private File savedFile;
    private boolean teeBacked;

    private FlacDecoder dec;
    private volatile StreamInfo info;
    private AudioTrack track;
    private int outChannels = 2;
    private int depth = 16;
    private boolean floatPath = false;
    private boolean force16 = false;                // float 通道被真机拒绝后置真，本次运行余下重建一律 16bit（v1.43.2）
    private byte[] pcmBuf;
    private boolean firstWriteOk = false;           // 首写成功 Diag 只记一次
    private int ladderStep = 0;                     // 连续写失败的阶梯级（整块写成即复位）
    private int totalRebuilds = 0;                  // 本次运行累计重建次数（封顶防病态循环）

    // 位置账本（单位：采样帧，每声道一帧）
    private long totalFrames = -1;                  // STREAMINFO 总采样，-1 未知
    private volatile long decNext = 0;              // 解码器下一帧的首样本序号（绝对）
    private volatile long epochBase = 0;            // 当前纪元首帧的绝对样本序号
    private long epochHead0 = 0;                    // 纪元开始时播放头累计帧（已展开）
    private int lastRawHead = 0;
    private long headWraps = 0;
    private long lastPlayedRaw = 0;
    private volatile long writtenInEpoch = 0;       // 本纪元已写入 AudioTrack 的帧数
    private int resyncs = 0;
    private boolean endedFired = false;

    /** 垂死信号：引擎已 stop 或世代已换，解码线程据此静默退出，绝不走 onError */
    private static final class StoppedSignal extends RuntimeException {}

    public FlacEngine(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    // ---------------- 对外控制（任意线程可调） ----------------

    public void start(TeeDataSource tee, File file) {
        runGen++;
        savedTee = tee;
        savedFile = file;
        teeBacked = file == null;
        thread = new Thread(() -> runLoop(tee, file), "flac-engine");
        thread.setDaemon(true);
        thread.start();
    }

    public void play() {
        // 先启动轨道并确认进入 PLAYING，再置期望态唤醒解码线程（v1.43.2 时序修正：
        // 旧实现先置 flag 后 play、且吞掉 play 的异常，解码线程 250ms 轮询醒来时
        // 可能抢在轨真正启动之前写入未启动的轨、被 HAL 回 -3）
        AudioTrack t = track;
        if (t != null) startTrackNow(t);
        playing = true;
        synchronized (gate) { gate.notifyAll(); }
    }

    /** 启动轨道并核对播放状态；已在播返回 true。失败只返回 false 不抛——
     *  写路径的容错阶梯会接管（重建轨道），不在这里判死 */
    private boolean startTrackNow(AudioTrack t) {
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) t.play();
            return t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        } catch (Exception e) {
            return false;
        }
    }

    public void pause() {
        playing = false;
        AudioTrack t = track;
        if (t != null) { try { t.pause(); } catch (Exception ignored) {} }
    }

    public boolean isPlaying() { return playing && !stopped; }

    public void seekTo(int ms) {
        if (ms < 0) ms = 0;
        StreamInfo si = info;
        if (si == null) { pendingSeekMs = ms; return; }
        seekTargetSamples = (long) ms * si.sampleRate / 1000L;
        synchronized (gate) { gate.notifyAll(); }
    }

    public int getPositionMs() {
        StreamInfo si = info;
        if (si == null || si.sampleRate <= 0) return 0;
        long pos = epochBase + playedSinceEpoch();
        if (totalFrames > 0 && pos > totalFrames) pos = totalFrames;
        if (pos < 0) pos = 0;
        return (int) (pos * 1000L / si.sampleRate);
    }

    /** 总时长（毫秒）；STREAMINFO 没给总采样时返回 -1，由服务回退到曲目元数据 */
    public int getDurationMs() {
        StreamInfo si = info;
        if (si == null || si.sampleRate <= 0 || totalFrames <= 0) return -1;
        return (int) (totalFrames * 1000L / si.sampleRate);
    }

    /** 立即停机：换世代+置停机位、同步释放 AudioTrack（能立刻打断阻塞中的 write），
     *  再尽力等解码线程收尾；垂死线程残余的写入失败凭世代号静默，绝不上报（v1.43.2） */
    public void stop() {
        runGen++;
        stopped = true;
        playing = false;
        synchronized (gate) { gate.notifyAll(); }
        AudioTrack t = track;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
        Thread th = thread;
        if (th != null) {
            th.interrupt();
            try { th.join(200); } catch (InterruptedException ignored) {}
        }
    }

    // ---------------- 播放头账本 ----------------

    /** AudioTrack 播放头累计帧（32 位无符号回绕展开）；纪元基线在每次 flush 后自校准，
     *  不依赖 flush 是否清零播放头的厂商实现差异 */
    private long playedFramesRaw() {
        synchronized (headLock) {
            AudioTrack t = track;
            if (t == null) return lastPlayedRaw;
            int raw;
            try { raw = t.getPlaybackHeadPosition(); }
            catch (Exception e) { return lastPlayedRaw; }
            if (raw < lastRawHead && (lastRawHead - raw) > (1 << 30)) headWraps++;
            lastRawHead = raw;
            lastPlayedRaw = headWraps * 4294967296L + (raw & 0xFFFFFFFFL);
            return lastPlayedRaw;
        }
    }

    private long playedSinceEpoch() {
        return Math.max(0, playedFramesRaw() - epochHead0);
    }

    /** 换轨时播放头账本清零：新轨的播放头从 0 起，原回绕展开状态不得带过来 */
    private void resetHeadLedger() {
        synchronized (headLock) {
            lastRawHead = 0;
            headWraps = 0;
            lastPlayedRaw = 0;
        }
    }

    // ---------------- 输入 ----------------

    private TeeFlacInput.Source teeSource(final TeeDataSource tee) {
        return new TeeFlacInput.Source() {
            @Override public long length() { return tee.getSize(); }
            @Override public int readAt(long pos, byte[] buf, int off, int len) {
                return tee.readAt(pos, buf, off, len);
            }
            @Override public void close() { /* tee 生命周期归 PlayerService 管 */ }
        };
    }

    private TeeFlacInput.Source fileSource(File file) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(file, "r");
        return new TeeFlacInput.Source() {
            @Override public long length() {
                try { return raf.length(); } catch (IOException e) { return -1; }
            }
            @Override public int readAt(long pos, byte[] buf, int off, int len) throws IOException {
                synchronized (raf) {
                    raf.seek(pos);
                    return raf.read(buf, off, len);
                }
            }
            @Override public void close() {
                try { raf.close(); } catch (IOException ignored) {}
            }
        };
    }

    /** 读流的前 16 字节做容器嗅探（本地文件直读、在线经 tee 的 readAt） */
    private byte[] sniffHead(TeeDataSource tee, File file) throws IOException {
        byte[] head = new byte[16];
        int n = 0;
        if (file != null) {
            FileInputStream in = new FileInputStream(file);
            try {
                while (n < head.length) {
                    int r = in.read(head, n, head.length - n);
                    if (r < 0) break;
                    n += r;
                }
            } finally { in.close(); }
        } else {
            while (n < head.length) {
                int r = tee.readAt(n, head, n, head.length - n);
                if (r <= 0) break;
                n += r;
            }
        }
        if (n < head.length) {
            byte[] t = new byte[n];
            System.arraycopy(head, 0, t, 0, n);
            return t;
        }
        return head;
    }

    /** 容器分流（v1.43.1）：裸 FLAC 走 v1.43.0 原路径零改动；FLAC-in-MP4 先经
     *  Mp4FlacDemux 剥壳成虚拟裸流再喂解码器；其余未知容器直接失败（带首 16 字节
     *  hex+ascii 进 Diag，下次一眼认型），由服务走降档兜底。 */
    private FlacLowLevelInput openInput(final TeeDataSource tee, File file) throws IOException {
        byte[] head = sniffHead(tee, file);
        if (head.length >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            Diag.log(ctx, "🎧 容器识别：裸 FLAC");
            if (file != null) return new SeekableFileFlacInput(file);
            return new TeeFlacInput(teeSource(tee));
        }
        if (Mp4FlacDemux.looksLikeMp4(head)) {
            TeeFlacInput.Source src = file != null ? fileSource(file) : teeSource(tee);
            long t0 = System.currentTimeMillis();
            Mp4FlacDemux demux = Mp4FlacDemux.parse(src);
            Diag.log(ctx, "🎧 容器剥壳：MP4 内 FLAC（fLaC 样本项，"
                    + (demux.fragmented ? "分片" : "整文件") + "，" + demux.sampleCount
                    + " 个采样，解析 " + (System.currentTimeMillis() - t0) + "ms）");
            return demux.openInput();
        }
        String desc = hexAscii(head);
        Diag.log(ctx, "🎧 未知容器（首 16 字节 " + desc + "）");
        throw new DataFormatException("未知容器（首16字节 " + desc + "）");
    }

    private static String hexAscii(byte[] b) {
        StringBuilder hex = new StringBuilder(), asc = new StringBuilder();
        for (byte x : b) {
            hex.append(String.format("%02x ", x & 0xFF));
            int v = x & 0xFF;
            asc.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
        }
        return hex.toString().trim() + " | " + asc;
    }

    private void reopenDecoder() throws IOException {
        try { if (dec != null) dec.close(); } catch (Exception ignored) {}
        dec = new FlacDecoder(openInput(savedTee, savedFile));
        while (dec.readAndHandleMetadataBlock() != null) { }
        decNext = 0;
    }

    // ---------------- 解码主循环（解码线程） ----------------

    private void runLoop(TeeDataSource tee, File file) {
        try {
            dec = new FlacDecoder(openInput(tee, file));
            while (dec.readAndHandleMetadataBlock() != null) { }
            info = dec.streamInfo;
            if (info == null) throw new DataFormatException("Missing stream info");
            if (info.sampleRate <= 0 || info.numChannels <= 0 || info.sampleDepth <= 0)
                throw new DataFormatException("Bad stream info");
            totalFrames = info.numSamples > 0 ? info.numSamples : -1;
            buildTrack();
            decNext = 0;
            epochBase = 0;
            epochHead0 = playedFramesRaw();
            writtenInEpoch = 0;
            int[][] samples = new int[info.numChannels][65536];
            if (pendingSeekMs > 0) {
                int psm = pendingSeekMs;
                pendingSeekMs = -1;
                doSeek((long) psm * info.sampleRate / 1000L, samples);
            }
            Diag.log(ctx, "🎧 FLAC 软解开播（" + info.sampleRate + "Hz/" + info.sampleDepth
                    + "bit/" + info.numChannels + "声道，总时长 "
                    + (totalFrames > 0 ? (totalFrames / info.sampleRate) + "s" : "未知") + "）");
            cb.onReady(getDurationMs(), info.sampleRate, outChannels, info.sampleDepth);

            while (!stopped) {
                long st = seekTargetSamples;
                if (st >= 0) {
                    seekTargetSamples = -1;
                    doSeek(st, samples);
                    continue;
                }
                if (decodeEof) {
                    if (playing && writtenInEpoch > 0
                            && playedSinceEpoch() >= writtenInEpoch - 1) {
                        fireEnded();
                        return;
                    }
                    sleepQuiet(60);
                    continue;
                }
                if (!playing) {
                    synchronized (gate) {
                        if (!playing && !stopped && seekTargetSamples < 0) {
                            try { gate.wait(250); } catch (InterruptedException e) { return; }
                        }
                    }
                    continue;
                }
                int n;
                try {
                    n = dec.readAudioBlock(samples, 0);
                } catch (DataFormatException dfe) {
                    if (!teeBacked && resyncs < 3) {
                        resyncs++;
                        // 目标点逐次前移：坏帧本身的帧头若完好，重同步可能恰落回坏帧，
                        // 前移约两个块长可确保下一次落到坏帧之后
                        long land = dec.resyncNear(decNext + 1 + (resyncs - 1) * 8192L);
                        Diag.log(ctx, "🎧 FLAC 坏帧跳过（帧同步找回，第 " + resyncs + " 次）@ "
                                + (decNext * 1000L / info.sampleRate / 1000) + "s");
                        epochBase += land - decNext;   // 位置账本跨过缺口，保持进度真实
                        decNext = land;
                        continue;
                    }
                    throw dfe;
                }
                if (n == 0) { decodeEof = true; continue; }
                decNext += n;
                writePcm(samples, 0, n);
            }
        } catch (StoppedSignal ss) {
            // 已停机/世代已换：垂死线程静默退出，绝不上报失败触发降档（v1.43.2）
        } catch (Exception e) {
            if (!stopped) {
                String why = shortWhy(e);
                Diag.log(ctx, "🎧 FLAC 软解异常：" + why);
                cb.onError(why);
            }
        } finally {
            try { if (dec != null) dec.close(); } catch (Exception ignored) {}
            dec = null;
        }
    }

    private void fireEnded() {
        if (endedFired) return;
        endedFired = true;
        playing = false;
        cb.onEnded();
    }

    // ---------------- seek 落地 ----------------

    /** 把解码器落到 target 样本处并写出落地帧的应播部分；落地后位置账本换纪元。
     *  本地文件：解码器原生定位（seek 表/帧同步搜索，落地帧从 target 精确开始）；
     *  在线流：顺序解码跳过（需要回头时重开解码器），落地帧按采样偏移裁头。 */
    private void doSeek(long target, int[][] samples) throws IOException {
        long t0 = System.currentTimeMillis();
        if (totalFrames > 0) target = Math.max(0, Math.min(target, totalFrames - 1));
        else target = Math.max(0, target);
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
        }
        decodeEof = false;
        long landed = target;
        int pendOff = -1, pendN = 0;
        if (!teeBacked) {
            int n = dec.seekAndReadAudioBlock(target, samples, 0);
            if (n == 0) {
                decodeEof = true;
                landed = totalFrames > 0 ? totalFrames : target;
                decNext = landed;
            } else {
                pendOff = 0;
                pendN = n;
                decNext = target + n;
            }
        } else {
            if (target < decNext) reopenDecoder();
            while (true) {
                int n = dec.readAudioBlock(samples, 0);
                if (n == 0) { decodeEof = true; landed = decNext; break; }
                if (decNext + n > target) {
                    pendOff = (int) (target - decNext);
                    pendN = n - pendOff;
                    decNext += n;
                    landed = target;
                    break;
                }
                decNext += n;
            }
        }
        epochBase = landed;
        epochHead0 = playedFramesRaw();
        writtenInEpoch = 0;
        if (pendN > 0) writePcm(samples, pendOff, pendN);
        if (playing && t != null) { try { t.play(); } catch (Exception ignored) {} }
        Diag.log(ctx, "🎧 FLAC 软解 seek 到 " + (landed * 1000L / info.sampleRate / 1000)
                + "s，耗时 " + (System.currentTimeMillis() - t0) + "ms");
    }

    // ---------------- 输出 ----------------

    private void buildTrack() throws IOException {
        int rate = info.sampleRate;
        outChannels = Math.min(2, info.numChannels);
        depth = info.sampleDepth;
        floatPath = depth > 16 && !force16;
        int chMask = outChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int enc = floatPath ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
        int bps = floatPath ? 4 : 2;
        int minBuf = AudioTrack.getMinBufferSize(rate, chMask, enc);
        // 缓冲至少装下 2 个最大帧：暂停/seek 落地时单帧写入不阻塞
        int want = 65536 * outChannels * bps * 2;
        int bufSize = Math.max(minBuf > 0 ? minBuf * 2 : 0, want);
        if (bufSize > 4 * 1024 * 1024) bufSize = 4 * 1024 * 1024;
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate).setChannelMask(chMask).setEncoding(enc).build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try { track.release(); } catch (Exception ignored) {}
            track = null;
            throw new IOException("AudioTrack 初始化失败（" + rate + "Hz）");
        }
        pcmBuf = new byte[65536 * outChannels * bps];
        resetHeadLedger();
        Diag.log(ctx, "🎧 AudioTrack 建轨（" + rate + "Hz/" + (floatPath ? "FLOAT" : "PCM16")
                + "/" + outChannels + "ch/缓冲 " + bufSize + "B/状态 " + track.getState() + "）");
    }

    private int frameBytes() { return outChannels * (floatPath ? 4 : 2); }

    private void releaseTrackQuiet() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private static String playStateName(AudioTrack t) {
        if (t == null) return "无轨";
        try {
            switch (t.getPlayState()) {
                case AudioTrack.PLAYSTATE_PLAYING: return "PLAYING";
                case AudioTrack.PLAYSTATE_PAUSED: return "PAUSED";
                case AudioTrack.PLAYSTATE_STOPPED: return "STOPPED";
                default: return "?" + t.getPlayState();
            }
        } catch (Exception e) { return "异常"; }
    }

    /** 容错阶梯（v1.43.2）：write 负值/轨道没启动时调用。返回重建后应从本块第几帧
     *  续写（= 旧轨已实际播出的本块帧数，未播出的部分会重写给新轨，账本不回退）；
     *  -1 = 阶梯走完，放弃。阶梯形态：① 同参重建一次；②（仅 float）降 16bit 重建，
     *  且该次运行后续重建一律 16bit；③ 仍败才判死。总重建数封顶 4 防病态循环。
     *  heardBase/epochWrittenBefore 由 writePcm 维护：同一块内多次重建时，
     *  已播帧按「此前纪元已确认播出 + 本纪元实际播出」累计，不重复不倒退。 */
    private int recoverTrack(int myGen, long chunkAbs, long epochWrittenBefore, int heardBase, int accepted) {
        if (stopped || myGen != runGen) return -1;
        if (totalRebuilds >= 4) return -1;
        // 先量旧轨把本块播出了多少（release 后就量不到了）
        long playedChunk = heardBase + (playedSinceEpoch() - epochWrittenBefore);
        if (playedChunk < heardBase) playedChunk = heardBase;
        if (playedChunk > accepted) playedChunk = accepted;
        ladderStep++;
        final boolean to16;
        if (ladderStep == 1) {
            to16 = false;
        } else if (ladderStep == 2 && floatPath) {
            to16 = true;
        } else {
            return -1;
        }
        totalRebuilds++;
        try {
            releaseTrackQuiet();
            if (to16) {
                force16 = true;
                Diag.log(ctx, "🎧 输出通道降级 float→16bit（AudioTrack 重建续播，第 "
                        + totalRebuilds + " 次重建）");
            } else {
                Diag.log(ctx, "🎧 AudioTrack 重建（同参，第 " + totalRebuilds + " 次）");
            }
            buildTrack();
            AudioTrack t = track;
            if (t == null) return -1;
            if (playing && !startTrackNow(t)) return -1;
            epochBase = chunkAbs + playedChunk;
            epochHead0 = playedFramesRaw();
            writtenInEpoch = 0;
            return (int) playedChunk;
        } catch (Exception e) {
            return -1;
        }
    }

    /** int 采样 → 当前输出编码的 PCM 字节（写入 pcmBuf，返回字节数）。
     *  float 通道：采样归一化为 -1..1 的 float 小端；16bit 通道：按位深移位收敛。 */
    private int convertPcm(int[][] samples, int off, int frames) {
        int ch = outChannels;
        int p = 0;
        if (floatPath) {
            float denom = (float) (1 << (depth - 1));
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < ch; c++) {
                    float f = samples[c][off + i] / denom;
                    if (f > 1f) f = 1f; else if (f < -1f) f = -1f;
                    int bits = Float.floatToRawIntBits(f);
                    pcmBuf[p++] = (byte) bits;
                    pcmBuf[p++] = (byte) (bits >> 8);
                    pcmBuf[p++] = (byte) (bits >> 16);
                    pcmBuf[p++] = (byte) (bits >> 24);
                }
            }
        } else {
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < ch; c++) {
                    int v = samples[c][off + i];
                    if (depth < 16) v <<= (16 - depth);
                    else if (depth > 16) v >>= (depth - 16);
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    pcmBuf[p++] = (byte) v;
                    pcmBuf[p++] = (byte) (v >> 8);
                }
            }
        }
        return p;
    }

    /** 整帧阻塞写入 AudioTrack；负值不一次判死，走 recoverTrack 阶梯续写。
     *  期望暂停时（seek 预卷落地帧）不强行启动轨道——预卷写入是标准姿势；
     *  期望播放时写入前必须确认轨道已 PLAYING（v1.43.2：绝不写未启动的轨）。 */
    private void writePcm(int[][] samples, int off, int frames) throws IOException {
        final int myGen = runGen;
        final long chunkAbs = decNext - frames;    // 本块首帧的绝对样本序号
        long epochWrittenBefore = writtenInEpoch;   // 当前纪元在本块之前已写帧数（重建后归 0）
        int heardBase = 0;                          // 此前纪元已确认播出的本块帧数
        int accepted = 0;                           // 本块已被历代轨接受的帧数
        while (accepted < frames) {
            if (stopped || myGen != runGen) throw new StoppedSignal();
            AudioTrack t = track;
            if (t == null || (playing && !startTrackNow(t))) {
                int na = recoverTrack(myGen, chunkAbs, epochWrittenBefore, heardBase, accepted);
                if (na < 0) throw new IOException("AudioTrack 无法启动/已失效");
                heardBase = na;
                accepted = na;
                epochWrittenBefore = 0;   // 新纪元从本块续写处起算
                continue;
            }
            int p = convertPcm(samples, off + accepted, frames - accepted);
            int w = t.write(pcmBuf, 0, p, AudioTrack.WRITE_BLOCKING);
            if (w < 0) {
                if (stopped || myGen != runGen) throw new StoppedSignal();
                Diag.log(ctx, "🎧 AudioTrack write 返回 " + w + "（状态 " + playStateName(t)
                        + "/世代 " + myGen + "/本纪元已写 " + writtenInEpoch + " 帧/通道 "
                        + (floatPath ? "FLOAT" : "PCM16") + "）");
                int na = recoverTrack(myGen, chunkAbs, epochWrittenBefore, heardBase, accepted);
                if (na < 0) throw new IOException("AudioTrack write " + w);
                heardBase = na;
                accepted = na;
                epochWrittenBefore = 0;
                continue;
            }
            int framesW = w / frameBytes();
            accepted += framesW;
            writtenInEpoch += framesW;
            if (!firstWriteOk && framesW > 0) {
                firstWriteOk = true;
                Diag.log(ctx, "🎧 AudioTrack 首写成功（" + w + "B）");
            }
            // WRITE_BLOCKING 理论上写满才返回；防御性地对部分写继续循环补齐
        }
        ladderStep = 0;   // 整块写成，阶梯复位（总重建数不清，防整首反复重建）
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String shortWhy(Exception e) {
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m != null && !m.isEmpty() ? "：" + m : "");
        return s.length() > 90 ? s.substring(0, 90) : s;
    }
}
