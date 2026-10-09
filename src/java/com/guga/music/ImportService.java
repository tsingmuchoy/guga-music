package com.guga.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** 歌单导入后台服务：解析+匹配整单跑在这里（前台服务+进度通知），
 *  导入页只是个视图——离开页面/切去听歌都不影响，回来能续看进度与结果。
 *  任务状态放静态区（单任务制），页面通过 Listener 或直接读静态值同步。 */
public class ImportService extends Service {

public static final int ST_IDLE = 0, ST_RUNNING = 1, ST_DONE = 2, ST_FAILED = 3, ST_CANCELLED = 4;

public static volatile int state = ST_IDLE;
public static volatile PlaylistImport.Parsed parsed;
public static volatile List<Track> matchedTracks = new ArrayList<>();
public static volatile List<PlaylistImport.Src> matchedSrcs = new ArrayList<>();
public static volatile List<PlaylistImport.Src> unmatched = new ArrayList<>();
public static volatile List<PlaylistImport.Src> dupSrcs = new ArrayList<>();
public static volatile int progressDone, progressTotal;
public static volatile String stageText = "";
public static volatile String errorText = "";
public static volatile boolean blockedAbort;
private static volatile boolean cancelFlag;
private static PlaylistImport.Detected jobUse;
private static String jobBrute;

public interface Listener { void onProgress(); void onFinish(); }
public static volatile Listener listener;

private static final String CH = "guga_import";
private static final int NID = 4202;
private final Handler main = new Handler(Looper.getMainLooper());

public static void startJob(Context ctx, PlaylistImport.Detected use, String brute) {
jobUse = use;
jobBrute = brute;
cancelFlag = false;
Intent it = new Intent(ctx, ImportService.class);
if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it); else ctx.startService(it);
}

public static void cancel() { cancelFlag = true; }
public static boolean isRunning() { return state == ST_RUNNING; }

@Override public IBinder onBind(Intent i) { return null; }

@Override public void onCreate() {
super.onCreate();
if (Build.VERSION.SDK_INT >= 26) {
NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
if (nm != null) nm.createNotificationChannel(
new NotificationChannel(CH, "歌单导入", NotificationManager.IMPORTANCE_LOW));
}
}

@Override public int onStartCommand(Intent it, int flags, int startId) {
if (it != null && "cancel".equals(it.getAction())) { cancelFlag = true; return START_NOT_STICKY; }
startForegroundCompat(buildNotif("歌单导入", "正在准备…", 0, 0, true));
if (state == ST_RUNNING) return START_NOT_STICKY;
state = ST_RUNNING;
errorText = "";
blockedAbort = false;
parsed = null;
matchedTracks = new ArrayList<>();
matchedSrcs = new ArrayList<>();
unmatched = new ArrayList<>();
dupSrcs = new ArrayList<>();
progressDone = 0;
progressTotal = 0;
stageText = "正在解析歌单…";
new Thread(this::runJob).start();
return START_NOT_STICKY;
}

private void runJob() {
BiliApi api = new BiliApi(this);
try {
PlaylistImport.Parsed p = jobBrute != null
? PlaylistImport.fetchByIdBruteforce(jobBrute) : PlaylistImport.fetch(jobUse);
PlaylistImport.dedupeSource(p);
dupSrcs = new ArrayList<>(p.dups);
parsed = p;
if (cancelFlag) { finish(ST_CANCELLED, null); return; }
int total = p.tracks.size();
progressTotal = total;
stageText = "共 " + total + " 首，开始匹配 B 站…";
pushProgress(0, total);
int consecBlocked = 0;
java.util.Set<String> seenBv = new java.util.HashSet<>();
for (int i = 0; i < total; i++) {
if (cancelFlag) { finish(ST_CANCELLED, null); return; }
PlaylistImport.Src s = p.tracks.get(i);
Track t = null;
boolean blockedHere = false;
try {
t = PlaylistImport.matchOneE(api, s);
consecBlocked = 0;
} catch (PlaylistImport.BlockedException be) {
blockedHere = true;
long[] waits = {4000, 10000, 25000};
for (int a = 0; a < waits.length && t == null; a++) {
stageText = "B站搜索被临时风控，等 " + (waits[a] / 1000) + " 秒后第 " + (a + 1) + " 次重试…";
pushProgress(i, total);
sleepQuiet(waits[a]);
if (cancelFlag) { finish(ST_CANCELLED, null); return; }
try { t = PlaylistImport.matchOneE(api, s); blockedHere = false; consecBlocked = 0; }
catch (PlaylistImport.BlockedException be2) { blockedHere = true; }
}
}
if (blockedHere) {
consecBlocked++;
if (consecBlocked >= 3) {
blockedAbort = true;
unmatched.add(s);
for (int j = i + 1; j < total; j++) unmatched.add(p.tracks.get(j));
progressDone = total;
break;
}
}
if (t != null) {
// 两条源曲目匹配到同一个 B 站视频：只留第一条，后一条算重复自动剔除
if (seenBv.add(t.bvid)) { matchedTracks.add(t); matchedSrcs.add(s); } else dupSrcs.add(s);
} else unmatched.add(s);
progressDone = i + 1;
stageText = "匹配中 " + (i + 1) + "/" + total + " · 已匹配 " + matchedTracks.size() + " 首";
pushProgress(i + 1, total);
if (i < total - 1) sleepQuiet(700);
}
finish(ST_DONE, null);
} catch (Exception e) {
finish(ST_FAILED, e.getMessage() == null ? "网络或链接异常" : e.getMessage());
}
}

private void pushProgress(int done, int total) {
String title = parsed != null && parsed.title != null ? "导入歌单 · " + parsed.title : "歌单导入";
notifyNow(buildNotif(title, stageText, done, total, true));
Listener l = listener;
if (l != null) main.post(l::onProgress);
}

private void finish(int newState, String err) {
state = newState;
if (err != null) errorText = err;
if (newState == ST_DONE) {
String title = parsed != null && parsed.title != null ? parsed.title : "歌单";
notifyNow(buildNotif("导入完成 · " + title,
"匹配 " + matchedTracks.size() + " 首，点开查看", progressTotal, progressTotal, false));
} else if (newState == ST_FAILED) {
notifyNow(buildNotif("导入失败", errorText, 0, 0, false));
}
stopForeground(newState == ST_CANCELLED);
stopSelf();
Listener l = listener;
if (l != null) main.post(l::onFinish);
}

private void notifyNow(Notification n) {
NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
if (nm != null) nm.notify(NID, n);
}

private Notification buildNotif(String title, String text, int done, int total, boolean ongoing) {
Intent open = new Intent(this, PlaylistImportActivity.class);
open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
PendingIntent content = PendingIntent.getActivity(this, 77, open, flags);
Notification.Builder b = Build.VERSION.SDK_INT >= 26
? new Notification.Builder(this, CH) : new Notification.Builder(this);
b.setContentTitle(title)
.setContentText(text)
.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
.setContentIntent(content)
.setOngoing(ongoing)
.setOnlyAlertOnce(true);
if (total > 0) b.setProgress(total, done, false);
if (ongoing) {
Intent ci = new Intent(this, ImportService.class);
ci.setAction("cancel");
PendingIntent cp = PendingIntent.getService(this, 78, ci,
PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消导入", cp);
}
return b.build();
}

private void startForegroundCompat(Notification n) {
try {
if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
else startForeground(NID, n);
} catch (Exception e) {
try { startForeground(NID, n); } catch (Exception ignored) {}
}
}

private static void sleepQuiet(long ms) {
try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
}
}
