package com.guga.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerService extends Service {

    public interface Listener {
        void onTrackChanged(Track t);
        void onStateChanged(boolean playing);
        void onError(String msg);
    }

    public static final int MODE_SEQ = 0, MODE_LOOP = 1, MODE_SHUFFLE = 2;
    private static final String CH = "player";
    private static final int NID = 42;

    private static PlayerService inst;
    public static PlayerService get() { return inst; }

    private MediaPlayer mp;
    private BiliApi api;
    private HistoryDb history;
    private final List<Track> queue = new ArrayList<>();
    private int index = -1;
    private boolean playing = false;
    private boolean prepared = false;
    private boolean preparing = false;
    private String backupUrl = null;
    private boolean backupTried = false;
    private boolean refetchTried = false;
    private Track refetchTrack = null;
    private int activeTier = 2;              // 本首歌实际请求的音质档位（降档重试会改它）
    private String curStreamKind = "aac";    // 本次取到的实际流类型：aac / flac / dolby
    private int sessionTier = -1;            // 播放页临时请求的档位（只存内存，重启即失，不写设置）
    private int actualTier = -1;             // 本首歌实际在播的档位（-1=尚未取到流）
    private boolean[] availTiers = null;     // 本首歌可用的档位集合（null=未知）
    private boolean downgradeTried = false;  // 高音质流报错后降到 192K 的重试是否已用
    private boolean handlingError = false;   // 正在处理一条报错时，吞掉播放器连环补发的旧报错
    private boolean restartPaused = false;   // 音质原地重载后保持暂停（原本是暂停状态时）
    private final java.util.Set<String> flacBad = new java.util.HashSet<>(); // 本次运行内已知 FLAC 损坏的歌（bvid）
    private long prepareStartAt = 0;
    private int fetchSeq = 0;
    private int playToken = 0;
    private final android.os.Handler watchdog = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable watchdogTask = null;
    private int failStreak = 0;
    private final Random random = new Random();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private AudioManager audioMgr;
    private MediaSession session;
    private android.view.WindowManager islandWm;
    private android.view.View islandView;
    private android.widget.ImageView islandCover;
    private android.widget.ImageView islandToggle;
    private android.widget.ImageView islandNext;
    private android.widget.ImageView islandMini;
    private android.widget.TextView islandTitle;
    private boolean islandShown = false;
    private boolean islandExpanded = false;
    private boolean islandYield = false;
    private android.animation.ValueAnimator discAnim;
    private android.animation.ValueAnimator eqAnim;
    private android.view.View islandEq;
    private android.view.View[] islandBars;
    private android.graphics.Bitmap artForBars;
    private int lastCoverColor = 0xFFFF8A00;
    public int getLastCoverColor() { return lastCoverColor; }
    private android.view.WindowManager sbWm;
    private android.widget.TextView sbView;
    private boolean sbShown = false;
    private java.util.List<Lyrics.Line> sbLines;
    private String sbBvid;
    private String sbLoadingBvid;
    private int sbIdx = -1;
    private final Runnable sbTicker = new Runnable() {
        @Override public void run() {
            tickSbLyrics();
            if (sbShown) watchdog.postDelayed(this, 500);
        }
    };
    private android.animation.ValueAnimator islandAnim;
    private final Runnable islandCollapseTask = () -> setIslandExpanded(false, true);
    private int fgCount = 0;
    private final android.app.Application.ActivityLifecycleCallbacks lcCallbacks = new android.app.Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityStarted(android.app.Activity a) { fgCount++; refreshIsland(); }
        @Override public void onActivityStopped(android.app.Activity a) { if (fgCount > 0) fgCount--; refreshIsland(); refreshSbLyrics(); }
        @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
        @Override public void onActivityResumed(android.app.Activity a) {}
        @Override public void onActivityPaused(android.app.Activity a) {}
        @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
        @Override public void onActivityDestroyed(android.app.Activity a) {}
    };
    private android.graphics.Bitmap sessionArt;
    private String sessionArtBvid;
    private boolean sessionArtAlbum = false;
    private AudioFocusRequest focusReq;
    private boolean focusHeld = false;

    public static void ensureStarted(Context ctx) {
        Intent it = new Intent(ctx, PlayerService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it);
        else ctx.startService(it);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        Diag.log(this, "服务创建");
        api = new BiliApi(getApplicationContext());
        history = new HistoryDb(getApplicationContext());
        audioMgr = (AudioManager) getSystemService(AUDIO_SERVICE);
        mp = new MediaPlayer();
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setOnPreparedListener(m -> {
            prepared = true;
            handlingError = false;
            Diag.log(this, "✔ 已就绪开播");
            preparing = false;
            cancelWatchdog();
            // 若是被系统杀掉后恢复的同一首，回到上次进度
            try {
                android.content.SharedPreferences ps = getSharedPreferences("player_state", MODE_PRIVATE);
                Track cur = current();
                if (cur != null && cur.bvid.equals(ps.getString("pos_bvid", ""))) {
                    int pos = ps.getInt("pos", 0);
                    if (pos > 3000 && pos < m.getDuration()) m.seekTo(pos);
                }
                ps.edit().remove("pos").remove("pos_bvid").apply();
            } catch (Exception ignored) {}
            failStreak = 0;
            if (restartPaused) {
                restartPaused = false;
                setPlaying(false);
            } else {
                m.start();
                setPlaying(true);
            }
            updateNotification();
        });
        mp.setOnCompletionListener(m -> onComplete());
        mp.setOnErrorListener((m, what, extra) -> {
            // 播放器常在一次失败后连环补发好几条旧报错（典型 -38 连发），
            // 若不拦，后到的旧报错会把正在进行的恢复（降档/换址）误判成新失败甚至跳歌
            if (handlingError) {
                Diag.log(this, "（连环报错已忽略 what=" + what + " extra=" + extra + "）");
                return true;
            }
            handlingError = true;
            cancelWatchdog();
            Diag.log(this, "✖ 播放器报错 what=" + what + " extra=" + extra);
            // 高音质流（FLAC/杜比）报错：多半是设备解码不支持，先降到 192K AAC 重取一次，别直接跳歌
            if (("flac".equals(curStreamKind) || "dolby".equals(curStreamKind))
                    && !downgradeTried && refetchTrack != null) {
                if ("flac".equals(curStreamKind)) flacBad.add(refetchTrack.bvid);
                downgradeTried = true;
                activeTier = BiliApi.TIER_192K;
                Diag.log(this, "⚠ 高音质流（" + curStreamKind + "）播放失败，降到 192K 重试");
                fetchAndPlay(refetchTrack, playToken);
                return true;
            }
            // 主地址失败 -> 用备用 CDN 地址重试
            if (backupUrl != null && !backupTried) {
                backupTried = true;
                if (tryStream(backupUrl)) return true;
            }
            // 备用也不行 -> 重新取一次地址（换节点/刷新签名）再试
            if (!refetchTried && refetchTrack != null) {
                refetchTried = true;
                api.invalidateMixin();
                fetchAndPlay(refetchTrack, playToken);
                return true;
            }
            preparing = false;
            onPlayError("播放失败（错误码 " + what + "/" + extra + "），已自动跳下一首");
            return true;
        });
        initSession();
        try { getApplication().registerActivityLifecycleCallbacks(lcCallbacks); } catch (Exception ignored) {}
        createChannel();
        startForegroundCompat(buildNotification("咕嘎音乐", "点一首歌开始听吧"));
        restoreState();
        if (current() != null) updateNotification();
    }

    // ---------------- 对外控制 ----------------
    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public List<Track> getQueue() { return queue; }
    public int getIndex() { return index; }
    public Track current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    public boolean isPlaying() { return playing; }
    public boolean isPrepared() { return prepared; }
    public int getMode() { return getSharedPreferences("player", MODE_PRIVATE).getInt("mode", MODE_SEQ); }
    public void cycleMode() {
        int m = (getMode() + 1) % 3;
        getSharedPreferences("player", MODE_PRIVATE).edit().putInt("mode", m).apply();
    }
    public static final String[] QUALITY_NAMES = {
            "64K 省流", "132K 标准", "192K 高清", "Hi-Res 无损（需大会员）", "杜比全景声（需大会员）"};

    /** 音质档位 0-4，默认 192K(2)；旧版 lowq=true 的用户映射到 64K(0) */
    public int getQualityTier() {
        android.content.SharedPreferences pf = getSharedPreferences("player", MODE_PRIVATE);
        if (pf.contains("quality_tier")) return Math.max(0, Math.min(4, pf.getInt("quality_tier", 2)));
        return pf.getBoolean("lowq", false) ? 0 : 2;
    }

    public void setQualityTier(int tier) {
        getSharedPreferences("player", MODE_PRIVATE).edit()
                .putInt("quality_tier", Math.max(0, Math.min(4, tier))).apply();
        sessionTier = -1; // 改默认设置时，清掉播放页的临时档位
    }

    /** 本次生效的请求档位：播放页临时档位优先于设置里的默认档位 */
    public int getEffectiveTier() {
        return sessionTier >= 0 ? sessionTier : getQualityTier();
    }

    /** 播放页切档：只记在内存里的临时请求，不写设置；重启 App 后回到默认档位 */
    public void setSessionTier(int tier) {
        sessionTier = Math.max(0, Math.min(4, tier));
    }

    public boolean hasSessionTier() { return sessionTier >= 0; }

    /** 本首歌实际在播的档位（-1=还没取到流） */
    public int getActualTier() { return actualTier; }

    /** 本首歌可用的档位集合（null=未知） */
    public boolean[] getAvailTiers() { return availTiers; }

    /** 设置里切换音质后调用：当前歌曲原地以新档位重载，进度与播放/暂停状态都保持 */
    public void applyQualityChange() {
        Track t = current();
        if (t == null) return;
        if (!prepared && !playing && !preparing) return; // 还没播过：新档位下次播放自然生效
        int pos = 0;
        try { pos = prepared ? mp.getCurrentPosition() : 0; } catch (Exception ignored) {}
        if (pos > 3000) {
            getSharedPreferences("player_state", MODE_PRIVATE).edit()
                    .putInt("pos", pos).putString("pos_bvid", t.bvid).apply();
        }
        restartPaused = !playing;
        Diag.log(this, "🔀 音质切换为「" + QUALITY_NAMES[getEffectiveTier()] + "」，当前歌曲原地重载（进度 " + (pos / 1000) + "s）");
        startTrack();
    }

    public int getPosition() { try { return prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    public int getDuration() { try { return prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; } }
    public void seekTo(int ms) { if (prepared) { mp.seekTo(ms); publishState(); } }

    public void playQueue(List<Track> tracks, int start) {
        Track target = tracks != null && start >= 0 && start < tracks.size() ? tracks.get(start) : null;
        Track cur = current();
        if (target != null && cur != null && target.bvid != null && target.bvid.equals(cur.bvid)
                && preparing && sameQueue(tracks)) {
            Diag.log(this, "（重复点播已合并：这首正在加载中）");
            return;
        }
        queue.clear();
        queue.addAll(tracks);
        index = Math.max(0, Math.min(start, queue.size() - 1));
        startTrack();
    }

    private boolean sameQueue(List<Track> tracks) {
        if (tracks.size() != queue.size()) return false;
        for (int i = 0; i < tracks.size(); i++) {
            String a = tracks.get(i).bvid, b = queue.get(i).bvid;
            if (a == null ? b != null : !a.equals(b)) return false;
        }
        return true;
    }

    public void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        startTrack();
    }

    public void toggle() {
        if (current() == null) {
            // 服务可能被系统杀掉重建过：先尝试从存档恢复队列
            restoreState();
            if (current() == null) {
                fireError("播放队列已失效，重新点一首歌吧");
                return;
            }
        }
        if (preparing) {
            if (System.currentTimeMillis() - prepareStartAt > 12000) {
                Diag.log(this, "⚠️ 加载卡死自检触发，强制重建");
                playToken++;
                preparing = false;
                prepared = false;
                startTrack();
            } else {
                Diag.log(this, "（点播放被忽略：正在加载中）");
            }
            return;
        }
        if (!prepared) { startTrack(); return; }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                setPlaying(false);
                persistPos();
            } else {
                requestFocus();
                mp.start();
                setPlaying(true);
            }
        } catch (IllegalStateException e) {
            // 播放器状态异常（常见于系统冻结/恢复后）：整首重建
            prepared = false;
            startTrack();
            return;
        }
        updateNotification();
    }

    public void next(boolean manual) {
        if (queue.isEmpty()) return;
        if (getMode() == MODE_SHUFFLE && queue.size() > 1) {
            int n;
            do { n = random.nextInt(queue.size()); } while (n == index);
            index = n;
        } else {
            index++;
            if (index >= queue.size()) {
                if (manual) index = 0;
                else { index = queue.size() - 1; setPlaying(false); updateNotification(); return; }
            }
        }
        startTrack();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (getPosition() > 3000) { seekTo(0); return; }
        index = (index - 1 + queue.size()) % queue.size();
        startTrack();
    }

    // ---------------- 内部 ----------------
    private void startTrack() {
        final Track t = current();
        if (t == null) return;
        final int token = ++playToken;
        prepared = false;
        preparing = true;
        prepareStartAt = System.currentTimeMillis();
        Diag.log(this, "▶ 开始加载：" + t.title);
        backupUrl = null;
        backupTried = false;
        refetchTried = false;
        refetchTrack = t;
        activeTier = getEffectiveTier();
        curStreamKind = "aac";
        actualTier = -1;
        availTiers = null;
        downgradeTried = false;
        cancelWatchdog();
        try { mp.reset(); } catch (Exception ignored) {}
        setPlaying(false);
        fireTrack(t);
        updateNotification();
        requestFocus();
        persistState();
        armStall();
        resolveAndPlay(t, token, false);
    }

    /** 取 cid（缺时）后取播放地址；失败时刷新签名重试一次 */
    private void resolveAndPlay(final Track t, final int token, final boolean retried) {
        if (t.cid == 0) {
            api.view(t.bvid, new BiliApi.Cb<Track>() {
                @Override public void onOk(Track full) {
                    if (token != playToken) return;
                    t.cid = full.cid;
                    t.title = full.title;
                    t.cover = full.cover;
                    t.author = full.author;
                    t.durationSec = full.durationSec;
                    fireTrack(t);
                    history.add(t);
                    fetchAndPlay(t, token);
                }
                @Override public void onErr(String msg) {
                    if (token != playToken) return;
                    if (!retried) {
                        api.invalidateMixin();
                        resolveAndPlay(t, token, true);
                    } else {
                        preparing = false;
                        onPlayError(msg);
                    }
                }
            });
        } else {
            history.add(t);
            fetchAndPlay(t, token);
        }
    }

    private void fetchAndPlay(final Track t, final int token) {
        prepareStartAt = System.currentTimeMillis();
        final long fetchStart = System.currentTimeMillis();
        final int seq = ++fetchSeq;
        api.playUrl(t, activeTier, new BiliApi.Cb<String[]>() {
            @Override public void onOk(String[] urls) {
                if (token != playToken || seq != fetchSeq) return;
                Diag.log(PlayerService.this, "🔗 取址完成（" + ((System.currentTimeMillis() - fetchStart) + 500) / 1000 + " 秒）");
                backupUrl = urls.length > 1 ? urls[1] : null;
                curStreamKind = urls.length > 2 && urls[2] != null ? urls[2] : "aac";
                if (urls.length > 3 && urls[3] != null) {
                    try { actualTier = Integer.parseInt(urls[3]); } catch (Exception ignored) {}
                }
                if (urls.length > 4 && urls[4] != null && !urls[4].isEmpty()) {
                    boolean[] av2 = new boolean[5];
                    for (String part : urls[4].split(",")) {
                        try { int rr = Integer.parseInt(part.trim()); if (rr >= 0 && rr <= 4) av2[rr] = true; }
                        catch (Exception ignored) {}
                    }
                    availTiers = av2;
                }
                if ("flac".equals(curStreamKind) && flacBad.contains(t.bvid) && !downgradeTried) {
                    downgradeTried = true;
                    activeTier = BiliApi.TIER_192K;
                    Diag.log(PlayerService.this, "⏭ 这首歌的 FLAC 已知放不动，直接用 192K");
                    fetchAndPlay(t, token);
                    return;
                }
                if (!tryStream(urls[0])) {
                    preparing = false;
                    onPlayError("播放器异常");
                }
            }
            @Override public void onErr(String msg) {
                if (token != playToken || seq != fetchSeq) return;
                Diag.log(PlayerService.this, "✖ 取地址失败：" + msg);
                // 取址失败（常见于风控抖动）：刷新签名后重试一次
                if (!refetchTried) {
                    refetchTried = true;
                    api.invalidateMixin();
                    fetchAndPlay(t, token);
                    return;
                }
                preparing = false;
                onPlayError(msg);
            }
        });
    }

    private boolean tryStream(String url) {
        try {
            mp.reset();
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.bilibili.com");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            mp.setDataSource(getApplicationContext(), android.net.Uri.parse(url), headers);
            mp.prepareAsync();
            handlingError = false;
            prepareStartAt = System.currentTimeMillis();
            armStall();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 加载看门狗：从 startTrack 起全程巡检（取 cid -> 取址 -> prepare）。
     *  流阶段主节点 6 秒没就绪就提前切备用节点；12 秒无进展走完整回收链
     *  （备用地址 -> 重新解析取址 -> 报错跳歌）。 */
    private void armStall() {
        cancelWatchdog();
        final int token = playToken;
        watchdogTask = () -> {
            if (token != playToken || prepared || !preparing) return;
            long idle = System.currentTimeMillis() - prepareStartAt;
            if (backupUrl != null && !backupTried && idle >= 6000) {
                Diag.log(this, "⏩ 主节点 6 秒没动静，提前切备用节点");
                backupTried = true;
                prepareStartAt = System.currentTimeMillis();
                if (tryStream(backupUrl)) return;
            }
            if (idle < 12000) {
                watchdog.postDelayed(watchdogTask, 3000);
                return;
            }
            Diag.log(this, "⏱ 卡死回收触发（已 " + (idle / 1000) + " 秒无进展）");
            prepareStartAt = System.currentTimeMillis();
            if (backupUrl != null && !backupTried) {
                backupTried = true;
                if (tryStream(backupUrl)) return;
            }
            if (!refetchTried && refetchTrack != null) {
                refetchTried = true;
                api.invalidateMixin();
                resolveAndPlay(refetchTrack, token, true);
                return;
            }
            preparing = false;
            onPlayError("加载超时，已自动跳下一首");
        };
        watchdog.postDelayed(watchdogTask, 3000);
    }

    private void cancelWatchdog() {
        if (watchdogTask != null) {
            watchdog.removeCallbacks(watchdogTask);
            watchdogTask = null;
        }
    }

    public String debugState() {
        Track t = current();
        return "队列 " + queue.size() + " 首 · 第 " + (index + 1) + " 首\n"
                + "当前：" + (t == null ? "无" : t.title) + "\n"
                + "prepared=" + prepared + " preparing=" + preparing
                + " playing=" + playing + " 连败=" + failStreak
                + "\n音质档位=" + QUALITY_NAMES[getEffectiveTier()] + " 实际流=" + curStreamKind + (actualTier >= 0 ? "（实际" + QUALITY_NAMES[actualTier] + "）" : "");
    }

    // ---------------- 状态持久化（防系统杀服务后队列丢失） ----------------
    private void persistState() {
        try {
            JSONArray arr = new JSONArray();
            for (Track t : queue) {
                JSONObject o = new JSONObject();
                o.put("bvid", t.bvid);
                o.put("cid", t.cid);
                o.put("title", t.title);
                o.put("cover", t.cover);
                o.put("author", t.author);
                o.put("duration", t.durationSec);
                arr.put(o);
            }
            getSharedPreferences("player_state", MODE_PRIVATE).edit()
                    .putString("queue_json", arr.toString())
                    .putInt("index", index)
                    .apply();
        } catch (Exception ignored) {}
    }

    private void persistPos() {
        try {
            Track t = current();
            if (t == null) return;
            getSharedPreferences("player_state", MODE_PRIVATE).edit()
                    .putInt("pos", mp.getCurrentPosition())
                    .putString("pos_bvid", t.bvid)
                    .apply();
        } catch (Exception ignored) {}
    }

    private void restoreState() {
        if (!queue.isEmpty()) return;
        try {
            android.content.SharedPreferences ps = getSharedPreferences("player_state", MODE_PRIVATE);
            String json = ps.getString("queue_json", null);
            if (json == null) return;
            JSONArray arr = new JSONArray(json);
            List<Track> restored = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Track t = new Track();
                t.bvid = o.optString("bvid");
                t.cid = o.optLong("cid");
                t.title = o.optString("title");
                t.cover = o.optString("cover");
                t.author = o.optString("author");
                t.durationSec = o.optInt("duration");
                if (!t.bvid.isEmpty()) restored.add(t);
            }
            if (!restored.isEmpty()) {
                queue.addAll(restored);
                index = Math.max(0, Math.min(ps.getInt("index", 0), queue.size() - 1));
                prepared = false;
            }
        } catch (Exception ignored) {}
    }

    private void onComplete() {
        setPlaying(false);
        if (getMode() == MODE_LOOP) {
            startTrack();
        } else {
            next(false);
        }
    }

    private void onPlayError(String msg) {
        preparing = false;
        handlingError = false;
        fireError(msg);
        Diag.log(this, "✖ 播放失败：" + msg);
        failStreak++;
        if (failStreak < queue.size() && queue.size() > 1) {
            index = (index + 1) % queue.size();
            startTrack();
        } else {
            setPlaying(false);
            updateNotification();
        }
    }

    private void setPlaying(boolean p) {
        playing = p;
        for (Listener l : listeners) l.onStateChanged(p);
        publishState();
    }

    private void fireTrack(Track t) {
        for (Listener l : listeners) l.onTrackChanged(t);
        publishMetadata();
        refreshSessionArt(t);
    }

    private void fireError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }

    /** 焦点监听全局只用一个：失焦只暂停、绝不走 toggle（防状态翻转） */
    private final AudioManager.OnAudioFocusChangeListener focusListener = f -> {
        if (f == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = true;
            islandYield = false;
            refreshIsland();
        } else if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean wasHeld = focusHeld;
            focusHeld = false;
            islandYield = true;
            refreshIsland();
            if (playing && wasHeld) {
                Diag.log(this, "焦点被抢，自动暂停");
                try { mp.pause(); } catch (Exception ignored) {}
                setPlaying(false);
                persistPos();
                updateNotification();
            }
        }
    };

    private void ensureFocusRequest() {
        if (focusReq != null || Build.VERSION.SDK_INT < 26) return;
        focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build();
    }

    /** 只用同一个焦点请求对象、且持有焦点时绝不重复申请
     *  （旧版每次播放都新建请求，vivo 会把旧请求当成"别的App"通知失焦，
     *   导致程序自己暂停自己——日志里的连环"焦点被抢"就是这么来的） */
    private void requestFocus() {
        if (audioMgr == null || focusHeld) return;
        try {
            int r;
            if (Build.VERSION.SDK_INT >= 26) {
                ensureFocusRequest();
                r = audioMgr.requestAudioFocus(focusReq);
            } else {
                r = audioMgr.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusHeld = true;
        } catch (Exception ignored) {}
    }

    // ---------------- 媒体会话（系统媒体面：锁屏 / 蓝牙 / 原子随身听等） ----------------
    private void initSession() {
        try {
            session = new MediaSession(this, "GugaMusic");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            Intent open = new Intent(this, PlayerActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            session.setSessionActivity(PendingIntent.getActivity(this, 9, open, fl));
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { if (!playing) toggle(); }
                @Override public void onPause() { if (playing) toggle(); }
                @Override public void onSkipToNext() { next(true); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long pos) { seekTo((int) Math.max(0, pos)); }
                @Override public void onStop() { if (playing) toggle(); }
            });
            session.setActive(true);
            Diag.log(this, "🎛 媒体会话已建立（锁屏/蓝牙/系统媒体面）");
        } catch (Exception ignored) {}
    }

    private void publishMetadata() {
        if (session == null) return;
        Track t = current();
        if (t == null) return;
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.author)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, t.author)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, t.author)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "哔哩哔哩");
            if (t.durationSec > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationSec * 1000L);
            if (sessionArt != null && t.bvid != null && t.bvid.equals(sessionArtBvid)) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, sessionArt);
                mb.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, sessionArt);
            }
            session.setMetadata(mb.build());
        } catch (Exception ignored) {}
    }

    private void publishState() {
        if (session == null) return;
        try {
            int st = playing ? PlaybackState.STATE_PLAYING
                    : preparing ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED;
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(st, getPosition(), playing ? 1.0f : 0.0f)
                    .setActions(acts).build());
        } catch (Exception ignored) {}
    }

    /** 会话封面：先上视频封面，专辑原图匹配到后替换（与播放页同口径，同样受封面开关控制）。
     *  位图压到 480px 内：会话元数据走 Binder，大图有超限风险。 */
    private void refreshSessionArt(final Track t) {
        sessionArt = null;
        sessionArtBvid = null;
        sessionArtAlbum = false;
        if (t == null || t.bvid == null) return;
        final String bv = t.bvid;
        if (t.cover != null && !t.cover.isEmpty()) {
            ImgLoader.loadBitmap(t.cover, b -> {
                Track cur = current();
                if (b == null || cur == null || !bv.equals(cur.bvid) || sessionArtAlbum) return;
                sessionArt = scaleArt(b);
                sessionArtBvid = bv;
                publishMetadata();
                updateNotification();
            });
        }
        Lyrics.fetchCover(this, t, url -> {
            if (url == null) return;
            ImgLoader.loadBitmap(url, b -> {
                Track cur = current();
                if (b == null || cur == null || !bv.equals(cur.bvid)) return;
                sessionArt = scaleArt(b);
                sessionArtBvid = bv;
                sessionArtAlbum = true;
                publishMetadata();
                updateNotification();
            });
        });
    }

    private android.graphics.Bitmap scaleArt(android.graphics.Bitmap b) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int max = Math.max(w, h);
        if (max <= 480) return b;
        float s = 480f / max;
        return android.graphics.Bitmap.createScaledBitmap(b, Math.round(w * s), Math.round(h * s), true);
    }

    // ---------------- 悬浮岛（仿原子岛，实验功能） ----------------
    /** 开关在设置「🧪 实验功能」，另需悬浮窗权限；只在 App 退到后台且有当前歌曲时，
     *  固定显示在屏幕顶部中央（原子岛的位置）。平时是小胶囊（封面+状态+歌名），
     *  点一下展开成大胶囊（暂停/下一首按钮淡入），3.5 秒无操作自动收回；
     *  展开时点胶囊本体打开播放页。展开/收回用 ValueAnimator 驱动窗口宽度 + 按钮淡入淡出。
     *  跟系统原子岛无关，是自家悬浮窗仿制品。 */
    public void refreshIsland() {
        try {
            boolean want = getSharedPreferences("player", MODE_PRIVATE).getBoolean("float_island", false)
                    && android.provider.Settings.canDrawOverlays(this)
                    && current() != null && fgCount == 0 && !islandYield;
            if (!want) {
                if (islandShown && islandView != null && islandWm != null) {
                    try { islandWm.removeView(islandView); } catch (Exception ignored) {}
                }
                islandShown = false;
                islandExpanded = false;
                watchdog.removeCallbacks(islandCollapseTask);
                if (islandAnim != null) islandAnim.cancel();
                stopIslandAnims();
                return;
            }
            if (islandView == null) buildIslandView();
            Track t = current();
            islandTitle.setText(t.title);
            islandToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            islandMini.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            if (sessionArt != null && t.bvid != null && t.bvid.equals(sessionArtBvid)) {
                islandCover.setImageBitmap(sessionArt);
                islandCover.setVisibility(android.view.View.VISIBLE);
                if (islandBars != null && artForBars != sessionArt) {
                    artForBars = sessionArt;
                    int col = extractBarColor(sessionArt);
                    lastCoverColor = col;
                    for (android.view.View bar : islandBars) {
                        if (bar != null) bar.setBackgroundColor(col);
                    }
                }
            } else {
                islandCover.setVisibility(android.view.View.GONE);
                if (islandBars != null && artForBars != null) {
                    artForBars = null;
                    lastCoverColor = 0xFFFF8A00;
                    for (android.view.View bar : islandBars) {
                        if (bar != null) bar.setBackgroundColor(0xFFFF8A00);
                    }
                }
            }
            if (!islandShown) {
                islandExpanded = false;
                islandToggle.setVisibility(android.view.View.GONE);
                islandNext.setVisibility(android.view.View.GONE);
                islandWm.addView(islandView, islandLp());
                islandShown = true;
                Diag.log(this, "🫧 悬浮岛显示");
            }
            syncIslandAnim();
        } catch (Exception ignored) {}
    }

    private void armCollapse() {
        watchdog.removeCallbacks(islandCollapseTask);
        watchdog.postDelayed(islandCollapseTask, 3500);
    }

    private void setIslandExpanded(final boolean exp, boolean anim) {
        if (islandView == null || islandWm == null || !islandShown) return;
        islandExpanded = exp;
        watchdog.removeCallbacks(islandCollapseTask);
        if (exp) armCollapse();
        float d = getResources().getDisplayMetrics().density;
        android.view.WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) islandView.getLayoutParams();
        final int from = lp.width;
        final int to = (int) ((exp ? 260 : 134) * d);
        if (exp) {
            if (islandEq != null) islandEq.setVisibility(android.view.View.GONE);
            islandToggle.setVisibility(android.view.View.VISIBLE);
            islandNext.setVisibility(android.view.View.VISIBLE);
            islandToggle.setAlpha(anim ? 0f : 1f);
            islandNext.setAlpha(anim ? 0f : 1f);
        }
        if (islandAnim != null) islandAnim.cancel();
        if (!anim || from == to) {
            lp.width = to;
            try { islandWm.updateViewLayout(islandView, lp); } catch (Exception ignored) {}
            if (!exp) {
                islandToggle.setVisibility(android.view.View.GONE);
                islandNext.setVisibility(android.view.View.GONE);
            } else {
                islandToggle.setAlpha(1f);
                islandNext.setAlpha(1f);
            }
            syncIslandAnim();
            return;
        }
        islandAnim = android.animation.ValueAnimator.ofInt(from, to);
        islandAnim.setDuration(300);
        islandAnim.setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f));
        islandAnim.addUpdateListener(a -> {
            try {
                android.view.WindowManager.LayoutParams l2 =
                        (android.view.WindowManager.LayoutParams) islandView.getLayoutParams();
                l2.width = (int) a.getAnimatedValue();
                if (islandShown) islandWm.updateViewLayout(islandView, l2);
                float f = a.getAnimatedFraction();
                float al = exp ? f : 1f - f;
                islandToggle.setAlpha(al);
                islandNext.setAlpha(al);
            } catch (Exception ignored) {}
        });
        islandAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!islandExpanded) {
                    islandToggle.setVisibility(android.view.View.GONE);
                    islandNext.setVisibility(android.view.View.GONE);
                    syncIslandAnim();
                }
            }
        });
        islandAnim.start();
    }

    private void buildIslandView() {
        islandWm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        root.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.setPadding((int) (9 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF000000);
        bg.setCornerRadius(999 * d);
        root.setBackground(bg);
        islandCover = new android.widget.ImageView(this);
        int cs = (int) (24 * d);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(cs, cs);
        islandCover.setLayoutParams(clp);
        islandCover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        islandCover.setClipToOutline(true);
        islandCover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        root.addView(islandCover);
        islandMini = new android.widget.ImageView(this);
        int ms = (int) (26 * d), mp = (int) (6 * d);
        islandMini.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ms, ms));
        islandMini.setPadding(mp, mp, mp, mp);
        islandMini.setImageResource(R.drawable.ic_pause);
        islandMini.setColorFilter(0xFFFFFFFF);
        root.addView(islandMini);
        islandTitle = new android.widget.TextView(this);
        islandTitle.setTextColor(0xFFFFFFFF);
        islandTitle.setTextSize(12.5f);
        islandTitle.setSingleLine(true);
        islandTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        islandTitle.setMarqueeRepeatLimit(-1);
        islandTitle.setSelected(true);
        android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = (int) (1 * d);
        tlp.rightMargin = (int) (2 * d);
        islandTitle.setLayoutParams(tlp);
        root.addView(islandTitle);
        islandEq = buildEqBars();
        root.addView(islandEq);
        islandToggle = islandBtn(R.drawable.ic_pause);
        islandToggle.setVisibility(android.view.View.GONE);
        islandToggle.setOnClickListener(v -> { toggle(); armCollapse(); });
        root.addView(islandToggle);
        islandNext = islandBtn(R.drawable.ic_next);
        islandNext.setVisibility(android.view.View.GONE);
        islandNext.setOnClickListener(v -> { next(true); armCollapse(); });
        root.addView(islandNext);
        root.setOnClickListener(v -> {
            if (!islandExpanded) {
                setIslandExpanded(true, true);
            } else {
                Intent open = new Intent(this, PlayerActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try { startActivity(open); } catch (Exception ignored) {}
            }
        });
        islandView = root;
    }

    private android.widget.ImageView islandBtn(int res) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.ImageView iv = new android.widget.ImageView(this);
        int s = (int) (34 * d), p = (int) (7 * d);
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(s, s));
        iv.setPadding(p, p, p, p);
        iv.setImageResource(res);
        iv.setColorFilter(0xFFFFFFFF);
        return iv;
    }

    /** 律动条颜色跟着封面走：把封面缩到 12x12，按饱和度加权平均取主色，
     *  再把明度/饱和拉到黑底上够亮的档位；取不到鲜艳色就回退原子岛橙 */
    private int extractBarColor(android.graphics.Bitmap bmp) {
        try {
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, 12, 12, true);
            double rS = 0, gS = 0, bS = 0, wS = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    int c = small.getPixel(x, y);
                    android.graphics.Color.colorToHSV(c, hsv);
                    if (hsv[1] < 0.25f || hsv[2] < 0.15f || hsv[2] > 0.98f) continue;
                    double w = hsv[1] * (0.4 + hsv[2]);
                    rS += android.graphics.Color.red(c) * w;
                    gS += android.graphics.Color.green(c) * w;
                    bS += android.graphics.Color.blue(c) * w;
                    wS += w;
                }
            }
            if (small != bmp) small.recycle();
            if (wS <= 0) return 0xFFFF8A00;
            android.graphics.Color.RGBToHSV((int) (rS / wS), (int) (gS / wS), (int) (bS / wS), hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.78f);
            return android.graphics.Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFFFF8A00;
        }
    }

    /** 4 根小竖条按不同相位/速度正弦起伏，冒充节拍律动（样式动画，非真节拍检测） */
    private android.view.View buildEqBars() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.BOTTOM);
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (int) (14 * d));
        blp.leftMargin = (int) (2 * d);
        blp.rightMargin = (int) (5 * d);
        box.setLayoutParams(blp);
        islandBars = new android.view.View[4];
        int[] hs = {9, 13, 10, 14};
        for (int i = 0; i < 4; i++) {
            android.view.View bar = new android.view.View(this);
            android.widget.LinearLayout.LayoutParams bp = new android.widget.LinearLayout.LayoutParams(
                    (int) (3 * d), (int) (hs[i] * d));
            if (i > 0) bp.leftMargin = (int) (2 * d);
            bar.setLayoutParams(bp);
            bar.setBackgroundColor(0xFFFF8A00);
            box.addView(bar);
            islandBars[i] = bar;
        }
        box.setVisibility(android.view.View.GONE);
        return box;
    }

    /** 播放中：封面唱片匀速自转 + 律动条起伏；暂停：动画冻结、律动条让位给状态图标 */
    private void syncIslandAnim() {
        try {
            if (!islandShown || islandView == null) return;
            if (playing) {
                if (islandEq != null) islandEq.setVisibility(islandExpanded ? android.view.View.GONE : android.view.View.VISIBLE);
                if (islandMini != null) islandMini.setVisibility(android.view.View.GONE);
                if (discAnim == null) {
                    discAnim = android.animation.ValueAnimator.ofFloat(0f, 360f);
                    discAnim.setDuration(12000);
                    discAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    discAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                    discAnim.addUpdateListener(a -> { if (islandCover != null) islandCover.setRotation((Float) a.getAnimatedValue()); });
                    discAnim.start();
                } else if (discAnim.isPaused()) {
                    discAnim.resume();
                }
                if (eqAnim == null) {
                    eqAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    eqAnim.setDuration(880);
                    eqAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    eqAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                    final double[] ph = {0.0, 0.23, 0.47, 0.71};
                    final double[] sp = {1.0, 1.31, 0.83, 1.13};
                    eqAnim.addUpdateListener(a -> {
                        if (islandBars == null) return;
                        float fr = (Float) a.getAnimatedValue();
                        for (int i = 0; i < islandBars.length; i++) {
                            if (islandBars[i] == null) continue;
                            double v = Math.abs(Math.sin(2 * Math.PI * (fr * sp[i] + ph[i])));
                            islandBars[i].setScaleY((float) (0.25 + 0.75 * v));
                        }
                    });
                    eqAnim.start();
                } else if (eqAnim.isPaused()) {
                    eqAnim.resume();
                }
            } else {
                if (islandEq != null) islandEq.setVisibility(android.view.View.GONE);
                if (islandMini != null) islandMini.setVisibility(android.view.View.VISIBLE);
                if (discAnim != null && discAnim.isRunning()) discAnim.pause();
                if (eqAnim != null && eqAnim.isRunning()) eqAnim.pause();
            }
        } catch (Exception ignored) {}
    }

    private void stopIslandAnims() {
        try {
            if (discAnim != null && discAnim.isRunning()) discAnim.pause();
            if (eqAnim != null && eqAnim.isRunning()) eqAnim.pause();
        } catch (Exception ignored) {}
    }

    private android.view.WindowManager.LayoutParams islandLp() {
        float d = getResources().getDisplayMetrics().density;
        int type = Build.VERSION.SDK_INT >= 26
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_PHONE;
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                (int) (134 * d),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        // 胶囊顶到状态栏下沿：状态栏那条带是 SystemUI 的地盘，悬浮窗伸进去会被
        // 系统图标盖住且点不动（实测）；贴着下沿是「位置最高且可点」的极限
        int sb = 0;
        try {
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) sb = getResources().getDimensionPixelSize(resId);
        } catch (Exception ignored) {}
        if (sb <= 0) sb = (int) (28 * d);
        lp.y = sb + (int) (2 * d);
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return lp;
    }

    // ---------------- 状态栏歌词（实验功能） ----------------
    /** 开关在设置「🧪 实验功能」；切出 App 后在屏幕顶部（状态栏下沿）逐行显示当前歌词，
     *  绿色粗字带阴影。数据走歌词引擎 v4 按 bvid 取一次，500ms 对一次播放进度。
     *  悬浮岛在时自动下移一颗胶囊的高度错层；真岛忙（焦点被抢）时同样让行。 */
    public void refreshSbLyrics() {
        try {
            Track t = current();
            boolean want = getSharedPreferences("player", MODE_PRIVATE).getBoolean("status_lyrics", false)
                    && android.provider.Settings.canDrawOverlays(this)
                    && t != null && fgCount == 0 && !islandYield;
            if (!want) { hideSb(); return; }
            if (t.bvid != null && !t.bvid.equals(sbBvid) && !t.bvid.equals(sbLoadingBvid)) {
                hideSb();
                sbLoadingBvid = t.bvid;
                final String bv = t.bvid;
                Lyrics.fetchFor(this, t, api, r -> watchdog.post(() -> {
                    if (bv.equals(sbLoadingBvid)) sbLoadingBvid = null;
                    Track cur = current();
                    if (cur == null || !bv.equals(cur.bvid)) return;
                    sbBvid = bv;
                    if (r != null && r.has()) {
                        sbLines = r.lines;
                        sbIdx = -1;
                        Diag.log(this, "🎤 状态栏歌词：取到 " + r.lines.size() + " 行（" + r.source + "）");
                        showSb();
                        tickSbLyrics();
                    } else {
                        sbLines = null;
                        Diag.log(this, "🎤 状态栏歌词：本曲没匹配到歌词，不显示");
                        hideSb();
                    }
                }));
            }
            if (sbLines != null && t.bvid != null && t.bvid.equals(sbBvid)) showSb();
            if (sbShown) {
                applySbStyle();
                try { sbWm.updateViewLayout(sbView, sbLp()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private int sbBaseY() {
        int sb = 0;
        try {
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) sb = getResources().getDimensionPixelSize(resId);
        } catch (Exception ignored) {}
        if (sb <= 0) sb = (int) (28 * getResources().getDisplayMetrics().density);
        return sb + (int) (1 * getResources().getDisplayMetrics().density);
    }

    private void showSb() {
        try {
            if (sbView == null) buildSbView();
            if (!sbShown) {
                sbWm.addView(sbView, sbLp());
                sbShown = true;
                watchdog.removeCallbacks(sbTicker);
                watchdog.post(sbTicker);
            }
        } catch (Exception ignored) {}
    }

    private void hideSb() {
        try {
            if (sbShown && sbView != null && sbWm != null) sbWm.removeView(sbView);
        } catch (Exception ignored) {}
        sbShown = false;
        watchdog.removeCallbacks(sbTicker);
    }

    private void tickSbLyrics() {
        try {
            if (!sbShown || sbView == null || sbLines == null) return;
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) sbView.getLayoutParams();
            int wantY = sbWantY();
            if (lp.y != wantY) {
                lp.y = wantY;
                try { sbWm.updateViewLayout(sbView, lp); } catch (Exception ignored) {}
            }
            int idx = Lyrics.indexAt(sbLines, getPosition());
            if (idx != sbIdx) {
                sbIdx = idx;
                sbView.setText(idx >= 0 ? sbLines.get(idx).text : "");
            }
        } catch (Exception ignored) {}
    }

    /** 按二级设置刷新歌词条样式：字号、字体颜色（跟随封面或预设）、背景透明度 */
    private void applySbStyle() {
        if (sbView == null) return;
        android.content.SharedPreferences sp2 = getSharedPreferences("player", MODE_PRIVATE);
        sbView.setTextSize(sp2.getInt("sb_font_sp", 13));
        int col = sp2.getBoolean("sb_follow", true) ? lastCoverColor
                : sp2.getInt("sb_color", 0xFF00E676);
        sbView.setTextColor(col);
        int bgA = sp2.getInt("sb_bg_alpha", 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.argb(bgA * 255 / 100, 0, 0, 0));
        bg.setCornerRadius(999f);
        sbView.setBackground(bg);
    }

    private int sbWantY() {
        float d = getResources().getDisplayMetrics().density;
        int yOff = (int) (getSharedPreferences("player", MODE_PRIVATE).getInt("sb_y_off", 0) * d);
        return sbBaseY() + (islandShown ? (int) (40 * d) : 0) + yOff;
    }

    private void buildSbView() {
        sbWm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        sbView = new android.widget.TextView(this);
        sbView.setTextColor(0xFF00E676);
        sbView.setTextSize(13);
        sbView.setTypeface(sbView.getTypeface(), android.graphics.Typeface.BOLD);
        sbView.setGravity(android.view.Gravity.CENTER);
        sbView.setSingleLine(true);
        sbView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sbView.setShadowLayer(3f, 0f, 1f, 0xCC000000);
        sbView.setPadding((int) (16 * d), (int) (1 * d), (int) (16 * d), (int) (1 * d));
        applySbStyle();
    }

    private android.view.WindowManager.LayoutParams sbLp() {
        int type = Build.VERSION.SDK_INT >= 26
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_PHONE;
        android.content.SharedPreferences sp3 = getSharedPreferences("player", MODE_PRIVATE);
        int sbW = (int) (getResources().getDisplayMetrics().widthPixels
                * sp3.getInt("sb_width", 100) / 100.0);
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                sbW,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.y = sbWantY();
        lp.x = (int) (sp3.getInt("sb_x_off", 0) * getResources().getDisplayMetrics().density);
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return lp;
    }

    // ---------------- 通知 ----------------
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "播放控制", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent act(String action, int code) {
        Intent it = new Intent(this, PlayerService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, it, flags);
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(content)
                .setDeleteIntent(act("repost", 4))
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_previous, "上一首", act("prev", 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", act("toggle", 2))
                .addAction(android.R.drawable.ic_media_next, "下一首", act("next", 3));
        if (session != null) {
            try {
                b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
                b.setVisibility(Notification.VISIBILITY_PUBLIC);
                Track ct = current();
                if (sessionArt != null && ct != null && ct.bvid != null && ct.bvid.equals(sessionArtBvid)) {
                    b.setLargeIcon(sessionArt);
                }
            } catch (Exception ignored) {}
        }
        return b.build();
    }

    private void updateNotification() {
        Track t = current();
        Notification n = t == null ? buildNotification("咕嘎音乐", "点一首歌开始听吧")
                : buildNotification(t.title, t.author);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NID, n);
        refreshIsland();
        refreshSbLyrics();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "repost":
                    // 安卓 14+ 允许用户划掉前台服务通知；媒体面（通知中心/锁屏）靠这条通知挂载，
                    // 划掉后若不回贴，音乐还在放但控制面消失——只要队列还在就立刻重新贴出
                    if (current() != null) {
                        Diag.log(this, "（通知被划掉，已重新贴出）");
                        updateNotification();
                    }
                    break;
                case "toggle": toggle(); break;
                case "next": next(true); break;
                case "prev": prev(); break;
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Diag.log(this, "⚠️ 服务被销毁（多半是系统杀后台）");
        try { getApplication().unregisterActivityLifecycleCallbacks(lcCallbacks); } catch (Exception ignored) {}
        try { if (islandShown && islandView != null && islandWm != null) islandWm.removeView(islandView); } catch (Exception ignored) {}
        islandShown = false;
        hideSb();
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) {}
        try { mp.release(); } catch (Exception ignored) {}
        inst = null;
        super.onDestroy();
    }
}
