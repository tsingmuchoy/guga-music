package com.guga.music;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 歌词引擎：网易云按歌名+时长匹配取 LRC -> B 站视频字幕兜底 -> 本地缓存 */
public class Lyrics {

public static class Line {
public final long timeMs;
public final String text;
public Line(long t, String s) { timeMs = t; text = s;}
}

public static class Result {
public final List<Line> lines;
public final String source; // 网易云歌词 / 视频字幕 / ""
public Result(List<Line> l, String s) { lines = l; source = s;}
public boolean has() { return lines!= null &&!lines.isEmpty();}
}

public interface Cb { void onResult(Result r);}

private static final ExecutorService POOL = Executors.newCachedThreadPool();
private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]");

// ---------------- LRC 解析 ----------------
public static List<Line> parseLrc(String lrc) {
List<Line> out = new ArrayList<>();
if (lrc == null) return out;
for (String raw: lrc.split("\n")) {
Matcher m = STAMP.matcher(raw);
List<Long> times = new ArrayList<>();
int lastEnd = 0;
while (m.find()) {
long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
String frac = m.group(3);
if (frac!= null) {
long f = Long.parseLong(frac);
ms += frac.length() == 1? f * 100: frac.length() == 2? f * 10: f;
}
times.add(ms);
lastEnd = m.end();
}
if (times.isEmpty()) continue;
String text = raw.substring(lastEnd).trim();
if (text.isEmpty()) continue;
for (long t: times) out.add(new Line(t, text));
}
Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
return out;
}

/** 二分查找当前行下标（最后一个 timeMs <= pos 的行） */
public static int indexAt(List<Line> lines, long pos) {
int lo = 0, hi = lines.size() - 1, ans = -1;
while (lo <= hi) {
int mid = (lo + hi) / 2;
if (lines.get(mid).timeMs <= pos) { ans = mid; lo = mid + 1;}
else hi = mid - 1;
}
return ans;
}

// ---------------- 标题清洗 ----------------
static String cleanTitle(String raw) {
if (raw == null) return "";
String s = raw;
s = s.replaceAll("]*】", " ").replaceAll("\\[[^\\]]*\\]", " ")
.replaceAll("「[^」]*」", " ").replaceAll("\\([^)]*\\)", " ")
.replaceAll("（[^）]*）", " ");
s = s.replaceAll("(?i)官方|正式版|完整版|高清|超清|修复|MV|PV|4K|1080P|720P", " ");
s = s.replaceAll("\\s+", " ").trim();
return s;
}

// ---------------- 主流程 ----------------
/** 歌词不对时换下一候选版本：序号 +1、清缓存、重新匹配 */
public static void refetchNext(final Context ctx, final Track track, final BiliApi api, final Cb cb) {
POOL.execute(() -> {
Context app = ctx.getApplicationContext();
android.content.SharedPreferences sp = app.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE);
int next = sp.getInt("alt_" + track.bvid, 0) + 1;
sp.edit().putInt("alt_" + track.bvid, next).apply();
try {
File dir = new File(app.getFilesDir(), "lyrics");
new File(dir, track.bvid + ".lrc").delete();
new File(dir, track.bvid + ".none").delete();
} catch (Exception ignored) {}
Result r = fetchSync(app, track, api);
new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(r));
});
}

public static void fetchFor(final Context ctx, final Track track, final BiliApi api, final Cb cb) {
POOL.execute(() -> {
Result r = fetchSync(ctx.getApplicationContext(), track, api);
new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(r));
});
}

private static Result fetchSync(Context ctx, Track track, BiliApi api) {
File dir = new File(ctx.getFilesDir(), "lyrics");
if (!dir.exists()) dir.mkdirs();
File cache = new File(dir, track.bvid + ".lrc");
File none = new File(dir, track.bvid + ".none");
try {
if (cache.exists()) {
String content = readFile(cache);
String src = content.startsWith("#src:")? content.substring(5, content.indexOf('\n')): "缓存";
List<Line> lines = parseLrc(content);
if (!lines.isEmpty()) return new Result(lines, src);
}
if (none.exists() && System.currentTimeMillis() - none.lastModified() < 3L * 24 * 3600 * 1000) {
return new Result(new ArrayList<>(), "");
}
} catch (Exception ignored) {}

// 1) 网易云匹配
try {
int alt = ctx.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE).getInt("alt_" + track.bvid, 0);
String lrc = neteaseLyrics(track, alt);
if (lrc!= null) {
List<Line> lines = parseLrc(lrc);
if (lines.size() >= 5) {
writeFile(cache, "#src:网易云歌词\n" + lrc);
return new Result(lines, "网易云歌词");
}
}
} catch (Exception ignored) {}

// 2) B 站字幕兜底（同步等结果，最多 ~15 秒）
try {
final Object lock = new Object();
final List<Line>[] box = new List[1];
if (track.cid == 0) {
api.view(track.bvid, new BiliApi.Cb<Track>() {
@Override public void onOk(Track full) {
track.cid = full.cid;
fetchSubtitles(api, track, box, lock);
}
@Override public void onErr(String msg) {
synchronized (lock) { box[0] = new ArrayList<>(); lock.notifyAll();}
}
});
} else {
fetchSubtitles(api, track, box, lock);
}
synchronized (lock) { lock.wait(20000);}
if (box[0]!= null && box[0].size() >= 5) {
StringBuilder sb = new StringBuilder("#src:视频字幕\n");
for (Line l: box[0]) {
sb.append(String.format("[%02d:%02d.%03d]", l.timeMs / 60000, (l.timeMs % 60000) / 1000, l.timeMs % 1000))
.append(l.text).append("\n");
}
writeFile(cache, sb.toString());
return new Result(box[0], "视频字幕");
}
} catch (Exception ignored) {}

try { writeFile(none, "");} catch (Exception ignored) {}
return new Result(new ArrayList<>(), "");
}

private static void fetchSubtitles(BiliApi api, Track track, final List<Line>[] box, final Object lock) {
api.subtitles(track.bvid, track.cid, new BiliApi.Cb<List<Line>>() {
@Override public void onOk(List<Line> lines) {
synchronized (lock) { box[0] = lines; lock.notifyAll();}
}
@Override public void onErr(String msg) {
synchronized (lock) { box[0] = new ArrayList<>(); lock.notifyAll();}
}
});
}

// ---------------- 网易云 ----------------
private static class Cand {
long id; int group; long diff;
Cand(long i, int g, long d) { id = i; group = g; diff = d; }
}

/** 名字归一化：去空格标点与版本括号，小写 */
private static String normName(String s) {
if (s == null) return "";
return s.toLowerCase()
.replaceAll("[\\s\\(\\)\\（\\）\\[\\]\\-·•、,，。!！?？:：;；'\"“”‘’]", "");
}

/** 候选歌名与目标的匹配分：100 同名 / 80 互含 / 50 字面高度重叠 / 0 无关 */
private static int nameScore(String candName, List<String> hints) {
String c = normName(candName);
if (c.isEmpty()) return 0;
int best = 0;
for (String h : hints) {
String n = normName(h);
if (n.isEmpty()) continue;
if (c.equals(n)) best = Math.max(best, 100);
else if (c.contains(n) || n.contains(c)) best = Math.max(best, 80);
else {
Set<Character> set = new HashSet<>();
for (char ch : n.toCharArray()) set.add(ch);
int hit = 0;
for (char ch : c.toCharArray()) if (set.contains(ch)) hit++;
double ratio = c.length() == 0 ? 0 : (double) hit / c.length();
if (ratio >= 0.6) best = Math.max(best, 50);
}
}
return best;
}

private static String neteaseLyrics(Track track, int alt) throws Exception {
List<String> hints = new ArrayList<>();
java.util.regex.Matcher bm = Pattern.compile("《([^》]+)》").matcher(track.title == null ? "" : track.title);
if (bm.find()) hints.add(bm.group(1));
String cleaned = cleanTitle(track.title);
if (!cleaned.isEmpty()) hints.add(cleaned);
Set<String> queries = new java.util.LinkedHashSet<>();
if (!cleaned.isEmpty()) queries.add(cleaned);
if (track.title != null && !track.title.trim().isEmpty()) queries.add(track.title.trim());
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
java.util.Map<Long, Cand> byId = new java.util.HashMap<>();
for (String q : queries) {
String url = "https://music.163.com/api/cloudsearch/pc?type=1&limit=10&offset=0&s="
+ URLEncoder.encode(q, "UTF-8");
JSONObject r = new JSONObject(httpGet(url, "https://music.163.com"));
JSONArray songs = r.optJSONObject("result") == null ? null : r.getJSONObject("result").optJSONArray("songs");
if (songs == null) continue;
for (int i = 0; i < songs.length(); i++) {
JSONObject sj = songs.getJSONObject(i);
long id = sj.optLong("id");
if (id <= 0 || byId.containsKey(id)) continue;
String name = sj.optString("name");
long dt = sj.optLong("dt");
long diff = wantDur > 0 ? Math.abs(dt - wantDur) : Long.MAX_VALUE / 2;
int score = nameScore(name, hints);
boolean artistHit = false;
JSONArray ars = sj.optJSONArray("ar");
if (ars != null && track.title != null) {
for (int a = 0; a < ars.length(); a++) {
String an = ars.getJSONObject(a).optString("name");
if (an.length() >= 2 && track.title.contains(an)) { artistHit = true; break; }
}
}
int group;
if (score >= 80) group = 0;                       // 歌名对得上：主池
else if (score == 50 && artistHit) group = 1;     // 歌名像 + 歌手对得上
else if (score == 0 && artistHit && diff <= 5000) group = 2; // 同歌手 + 时长几乎一致
else continue; // 仅时长相近的名字无关候选一律不要——宁缺毋错
byId.put(id, new Cand(id, group, diff));
}
}
List<Cand> pool = new ArrayList<>(byId.values());
Collections.sort(pool, (a, b) -> a.group != b.group ? Integer.compare(a.group, b.group)
: Long.compare(a.diff, b.diff));
if (pool.isEmpty()) return null;
for (int k = 0; k < pool.size(); k++) {
Cand c = pool.get(Math.floorMod(alt + k, pool.size()));
JSONObject lr = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=" + c.id,
"https://music.163.com"));
JSONObject lrc = lr.optJSONObject("lrc");
if (lrc != null) {
String text = lrc.optString("lyric");
if (text != null && text.contains("[")) return text;
}
}
return null;
}

static String httpGet(String url, String referer) throws Exception {
HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000);
c.setReadTimeout(10000);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
if (referer!= null) c.setRequestProperty("Referer", referer);
BufferedReader r = new BufferedReader(new InputStreamReader(
c.getResponseCode() >= 400? c.getErrorStream(): c.getInputStream(), StandardCharsets.UTF_8));
StringBuilder sb = new StringBuilder();
String line;
while ((line = r.readLine())!= null) sb.append(line);
r.close();
return sb.toString();
}

private static String readFile(File f) throws Exception {
BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
StringBuilder sb = new StringBuilder();
String line;
while ((line = r.readLine())!= null) sb.append(line).append("\n");
r.close();
return sb.toString();
}

private static void writeFile(File f, String content) throws Exception {
FileOutputStream out = new FileOutputStream(f);
out.write(content.getBytes(StandardCharsets.UTF_8));
out.close();
}
}
