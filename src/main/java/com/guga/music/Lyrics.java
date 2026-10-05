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
String lrc = neteaseLyrics(track);
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
private static String neteaseLyrics(Track track) throws Exception {
Set<String> queries = new java.util.LinkedHashSet<>();
String cleaned = cleanTitle(track.title);
if (!cleaned.isEmpty()) queries.add(cleaned);
if (track.title!= null &&!track.title.trim().isEmpty()) queries.add(track.title.trim());
long wantDur = track.durationSec > 0? track.durationSec * 1000L: -1;
for (String q: queries) {
String url = "https://music.163.com/api/cloudsearch/pc?type=1&limit=8&offset=0&s="
+ URLEncoder.encode(q, "UTF-8");
JSONObject r = new JSONObject(httpGet(url, "https://music.163.com"));
JSONArray songs = r.optJSONObject("result") == null? null: r.getJSONObject("result").optJSONArray("songs");
if (songs == null) continue;
long bestId = -1, bestDiff = Long.MAX_VALUE;
long nameId = -1, nameDiff = Long.MAX_VALUE;
for (int i = 0; i < songs.length(); i++) {
JSONObject s = songs.getJSONObject(i);
long id = s.optLong("id");
String name = s.optString("name");
long dt = s.optLong("dt");
long diff = wantDur > 0? Math.abs(dt - wantDur): 0;
boolean nameHit =!cleaned.isEmpty() && (name.contains(cleaned) || cleaned.contains(name));
if (wantDur > 0 && diff <= 12000 && diff < bestDiff) { bestDiff = diff; bestId = id;}
if (nameHit && diff < nameDiff) { nameDiff = diff; nameId = id;}
}
long pick = bestId > 0? bestId: nameId;
if (pick > 0) {
JSONObject lr = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=" + pick,
"https://music.163.com"));
JSONObject lrc = lr.optJSONObject("lrc");
if (lrc!= null) {
String text = lrc.optString("lyric");
if (text!= null && text.contains("[")) return text;
}
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
