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
import android.media.MediaPlayer;
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
    private long prepareStartAt = 0;
    private int fetchSeq = 0;
    private int playToken = 0;
    private final android.os.Handler watchdog = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable watchdogTask = null;
    private int failStreak = 0;
    private final Random random = new Random();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private AudioManager audioMgr;
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
            m.start();
            setPlaying(true);
            updateNotification();
        });
        mp.setOnCompletionListener(m -> onComplete());
        mp.setOnErrorListener((m, what, extra) -> {
            cancelWatchdog();
            Diag.log(this, "✖ 播放器报错 what=" + what + " extra=" + extra);
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
    public boolean isLowQuality() { return getSharedPreferences("player", MODE_PRIVATE).getBoolean("lowq", false); }
    public void setLowQuality(boolean v) { getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("lowq", v).apply(); }

    public int getPosition() { try { return prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    public int getDuration() { try { return prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; } }
    public void seekTo(int ms) { if (prepared) mp.seekTo(ms); }

    public void playQueue(List<Track> tracks, int start) {
        queue.clear();
        queue.addAll(tracks);
        index = Math.max(0, Math.min(start, queue.size() - 1));
        startTrack();
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
        cancelWatchdog();
        try { mp.reset(); } catch (Exception ignored) {}
        setPlaying(false);
        fireTrack(t);
        updateNotification();
        requestFocus();
        persistState();
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
        final int seq = ++fetchSeq;
        api.playUrl(t, isLowQuality(), new BiliApi.Cb<String[]>() {
            @Override public void onOk(String[] urls) {
                if (token != playToken || seq != fetchSeq) return;
                backupUrl = urls.length > 1 ? urls[1] : null;
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
            prepareStartAt = System.currentTimeMillis();
            armStall();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 卡死巡检：每 4 秒看一眼，只要还在加载且 12 秒无进展，就走回收链
     *  （备用地址 -> 重新取址 -> 报错跳歌）。所有 prepareAsync 都经 tryStream 挂上它。 */
    private void armStall() {
        cancelWatchdog();
        final int token = playToken;
        watchdogTask = () -> {
            if (token != playToken || prepared || !preparing) return;
            long idle = System.currentTimeMillis() - prepareStartAt;
            if (idle < 12000) {
                watchdog.postDelayed(watchdogTask, 4000);
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
                fetchAndPlay(refetchTrack, token);
                return;
            }
            preparing = false;
            onPlayError("加载超时，已自动跳下一首");
        };
        watchdog.postDelayed(watchdogTask, 4000);
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
                + " playing=" + playing + " 连败=" + failStreak;
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
    }

    private void fireTrack(Track t) {
        for (Listener l : listeners) l.onTrackChanged(t);
    }

    private void fireError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }

    /** 焦点监听全局只用一个：失焦只暂停、绝不走 toggle（防状态翻转） */
    private final AudioManager.OnAudioFocusChangeListener focusListener = f -> {
        if (f == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = true;
        } else if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean wasHeld = focusHeld;
            focusHeld = false;
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
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_previous, "上一首", act("prev", 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", act("toggle", 2))
                .addAction(android.R.drawable.ic_media_next, "下一首", act("next", 3));
        return b.build();
    }

    private void updateNotification() {
        Track t = current();
        Notification n = t == null ? buildNotification("咕嘎音乐", "点一首歌开始听吧")
                : buildNotification(t.title, t.author);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NID, n);
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
        try { mp.release(); } catch (Exception ignored) {}
        inst = null;
        super.onDestroy();
    }
}
