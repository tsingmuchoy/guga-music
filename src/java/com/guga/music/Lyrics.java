package com.guga.music;

import android.content.Context;
import android.util.Base64;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 歌词引擎 v4：QQ音乐 / 网易云 / 酷狗 / AMLL TTML / LRCLIB 按用户设置顺序级联（默认 QQ->网易云->酷狗->AMLL->LRCLIB），B 站字幕兜底；本地缓存 lyrics_v4 目录 */
public class Lyrics {

public static class Line {
public final long timeMs;
public final String text;
public Line(long t, String s) { timeMs = t; text = s; }
}

public static class Result {
public final List<Line> lines;
public final String source;
public Result(List<Line> l, String s) { lines = l; source = s; }
public boolean has() { return lines != null && !lines.isEmpty(); }
}

public interface Cb { void onResult(Result r); }

private static final ExecutorService POOL = Executors.newCachedThreadPool();
private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]");

// ---------------- LRC 解析 ----------------
public static List<Line> parseLrc(String lrc) {
List<Line> out = new ArrayList<>();
if (lrc == null) return out;
for (String raw : lrc.split("\n")) {
Matcher m = STAMP.matcher(raw);
List<Long> times = new ArrayList<>();
int lastEnd = 0;
while (m.find()) {
long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
String frac = m.group(3);
if (frac != null) {
long f = Long.parseLong(frac);
ms += frac.length() == 1 ? f * 100 : frac.length() == 2 ? f * 10 : f;
}
times.add(ms);
lastEnd = m.end();
}
if (times.isEmpty()) continue;
String text = raw.substring(lastEnd).trim();
if (text.isEmpty()) continue;
for (long t : times) out.add(new Line(t, text));
}
Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
return out;
}

public static int indexAt(List<Line> lines, long pos) {
int lo = 0, hi = lines.size() - 1, ans = -1;
while (lo <= hi) {
int mid = (lo + hi) / 2;
if (lines.get(mid).timeMs <= pos) { ans = mid; lo = mid + 1; }
else hi = mid - 1;
}
return ans;
}

// ---------------- 标题清洗与线索提取 ----------------
static String cleanTitle(String raw) {
if (raw == null) return "";
String s = raw;
s = s.replaceAll("【[^】]*】", " ").replaceAll("\\[[^\\]]*\\]", " ")
.replaceAll("「[^」]*」", " ").replaceAll("\\([^)]*\\)", " ")
.replaceAll("（[^）]*）", " ");
s = s.replaceAll("(?i)官方|正式版|完整版|高清|超清|修复", " ");
s = s.replaceAll("\\s+", " ").trim();
return s;
}

private static class Hints {
String name = "";
String artist = "";
final List<String> nameCands = new ArrayList<>();
final List<String> queries = new ArrayList<>();
}

private static Hints hintsOf(Track t) {
Hints h = new Hints();
String raw = t.title == null ? "" : t.title;
Matcher bm = Pattern.compile("《([^》]+)》").matcher(raw);
if (bm.find()) {
h.name = bm.group(1).trim();
String before = cleanTitle(raw.substring(0, bm.start()));
String[] seg = before.split("[-—|/]");
h.artist = seg[seg.length - 1].trim();
}
String cleaned = cleanTitle(raw);
if (h.name.isEmpty() && !cleaned.isEmpty()) {
String[] parts = cleaned.split("\\s+-\\s+|\\s+—\\s+");
if (parts.length == 2) {
h.name = parts[1].trim();
h.artist = parts[0].trim();
h.nameCands.add(parts[0].trim());
h.nameCands.add(parts[1].trim());
} else {
h.name = cleaned;
}
}
if (!h.name.isEmpty() && !h.nameCands.contains(h.name)) h.nameCands.add(0, h.name);
if (!cleaned.isEmpty() && !h.nameCands.contains(cleaned)) h.nameCands.add(cleaned);
Set<String> qs = new LinkedHashSet<>();
if (!h.artist.isEmpty() && !h.name.isEmpty()) qs.add(h.artist + " " + h.name);
if (!h.artist.isEmpty() && !h.name.isEmpty()) qs.add(h.artist + " - " + h.name);
if (!h.name.isEmpty()) qs.add(h.name);
if (!cleaned.isEmpty()) qs.add(cleaned);
if (!raw.trim().isEmpty()) qs.add(raw.trim());
h.queries.addAll(qs);
return h;
}

private static String normName(String s) {
if (s == null) return "";
return s.toLowerCase().replaceAll("[\\s\\(\\)（）\\[\\]【】\\-·•、,，。!！?？:：;；'\"“”‘’]", "");
}

/** 100 同名 / 80 互含 / 50 高重叠 / 0 无关 */
private static int nameScore(String candName, List<String> hints) {
String c = normName(candName);
if (c.isEmpty()) return 0;
int best = 0;
for (String hint : hints) {
String n = normName(hint);
if (n.isEmpty()) continue;
if (c.equals(n)) best = Math.max(best, 100);
else if (c.contains(n) || n.contains(c)) best = Math.max(best, 80);
else {
Set<Character> set = new HashSet<>();
for (char ch : n.toCharArray()) set.add(ch);
int hit = 0;
for (char ch : c.toCharArray()) if (set.contains(ch)) hit++;
if (c.length() > 0 && (double) hit / c.length() >= 0.6) best = Math.max(best, 50);
}
}
return best;
}

// ---------------- 主流程 ----------------
public static void fetchFor(final Context ctx, final Track track, final BiliApi api, final Cb cb) {
POOL.execute(() -> {
Result r = fetchSync(ctx.getApplicationContext(), track, api);
new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(r));
});
}

/** 歌词不对时换下一候选版本：序号 +1、清缓存、重新匹配 */
public static void refetchNext(final Context ctx, final Track track, final BiliApi api, final Cb cb) {
POOL.execute(() -> {
Context app = ctx.getApplicationContext();
android.content.SharedPreferences sp = app.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE);
int next = sp.getInt("alt_" + track.bvid, 0) + 1;
sp.edit().putInt("alt_" + track.bvid, next).apply();
try {
File dir = new File(app.getFilesDir(), "lyrics_v4");
new File(dir, track.bvid + ".lrc").delete();
new File(dir, track.bvid + ".none").delete();
} catch (Exception ignored) {}
Result r = fetchSync(app, track, api);
new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(r));
});
}

private static Result fetchSync(Context ctx, Track track, BiliApi api) {
File dir = new File(ctx.getFilesDir(), "lyrics_v4");
if (!dir.exists()) dir.mkdirs();
File cache = new File(dir, track.bvid + ".lrc");
File none = new File(dir, track.bvid + ".none");
try {
if (cache.exists()) {
String content = readFile(cache);
String src = content.startsWith("#src:") ? content.substring(5, content.indexOf('\n')) : "缓存";
List<Line> lines = parseLrc(content);
if (!lines.isEmpty()) return new Result(lines, src);
}
if (none.exists() && System.currentTimeMillis() - none.lastModified() < 3L * 24 * 3600 * 1000) {
return new Result(new ArrayList<>(), "");
}
} catch (Exception ignored) {}

Hints hints = hintsOf(track);
int alt = ctx.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE).getInt("alt_" + track.bvid, 0);

// 按用户设置的顺序逐源尝试（设置页可调；B 站字幕始终最后兜底）
for (String key : sourceOrder(ctx)) {
try {
String lrc = null;
if (key.equals("qq")) lrc = qqLyrics(track, hints, alt);
else if (key.equals("netease")) lrc = neteaseLyrics(track, hints, alt);
else if (key.equals("kugou")) lrc = kugouLyrics(track, hints, alt);
else if (key.equals("amll")) lrc = amllLyrics(track, hints, alt);
else if (key.equals("lrclib")) lrc = lrclibLyrics(track, hints, alt);
Result r = acceptLrc(cache, lrc, srcLabel(key));
if (r != null) { Diag.log(ctx, "🎤 歌词命中：" + srcShort(key) + "《" + hints.name + "》"); return r; }
} catch (Exception ignored) {}
}

// B 站字幕兜底
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
synchronized (lock) { box[0] = new ArrayList<>(); lock.notifyAll(); }
}
});
} else {
fetchSubtitles(api, track, box, lock);
}
synchronized (lock) { lock.wait(20000); }
if (box[0] != null && box[0].size() >= 5) {
StringBuilder sb = new StringBuilder("#src:视频字幕\n");
for (Line l : box[0]) {
sb.append(String.format("[%02d:%02d.%03d]", l.timeMs / 60000, (l.timeMs % 60000) / 1000, l.timeMs % 1000))
.append(l.text).append("\n");
}
writeFile(cache, sb.toString());
Diag.log(ctx, "🎤 歌词命中：视频字幕");
return new Result(box[0], "视频字幕");
}
} catch (Exception ignored) {}

try { writeFile(none, ""); } catch (Exception ignored) {}
Diag.log(ctx, "🎤 歌词未命中：《" + hints.name + "》");
return new Result(new ArrayList<>(), "");
}

private static Result acceptLrc(File cache, String lrc, String src) {
if (lrc == null) return null;
List<Line> lines = parseLrc(lrc);
if (lines.size() < 5) return null;
try { writeFile(cache, "#src:" + src + "\n" + lrc); } catch (Exception ignored) {}
return new Result(lines, src);
}

private static void fetchSubtitles(BiliApi api, Track track, final List<Line>[] box, final Object lock) {
api.subtitles(track.bvid, track.cid, new BiliApi.Cb<List<Line>>() {
@Override public void onOk(List<Line> lines) {
synchronized (lock) { box[0] = lines; lock.notifyAll(); }
}
@Override public void onErr(String msg) {
synchronized (lock) { box[0] = new ArrayList<>(); lock.notifyAll(); }
}
});
}

// ---------------- 网易云 ----------------
private static class Cand {
long id; int group; long diff; String ak;
Cand(long i, int g, long d) { id = i; group = g; diff = d; }
}

private static String neteaseLyrics(Track track, Hints hints, int alt) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
Map<Long, Cand> byId = new HashMap<>();
for (String q : hints.queries) {
String url = "https://music.163.com/api/cloudsearch/pc?type=1&limit=10&offset=0&s="
+ URLEncoder.encode(q, "UTF-8");
JSONObject r = new JSONObject(httpGet(url, "https://music.163.com"));
JSONArray songs = r.optJSONObject("result") == null ? null : r.getJSONObject("result").optJSONArray("songs");
if (songs == null) continue;
for (int i = 0; i < songs.length(); i++) {
JSONObject sj = songs.getJSONObject(i);
long id = sj.optLong("id");
if (id <= 0 || byId.containsKey(id)) continue;
int score = nameScore(sj.optString("name"), hints.nameCands);
long dt = sj.optLong("dt");
long diff = wantDur > 0 ? Math.abs(dt - wantDur) : Long.MAX_VALUE / 2;
boolean artistHit = false;
JSONArray ars = sj.optJSONArray("ar");
if (ars != null && track.title != null) {
for (int a = 0; a < ars.length(); a++) {
String an = ars.getJSONObject(a).optString("name");
if (an.length() >= 2 && track.title.contains(an)) { artistHit = true; break; }
}
}
int group;
if (score >= 80) group = 0;
else if (score == 50 && artistHit) group = 1;
else if (score == 0 && artistHit && diff <= 5000) group = 2;
else continue;
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

// ---------------- LRCLIB ----------------
private static String lrclibLyrics(Track track, Hints hints, int alt) throws Exception {
if (hints.name.isEmpty()) return null;
long want = track.durationSec;
// 精确 get：歌名+歌手+时长
if (!hints.artist.isEmpty() && want > 0) {
try {
String u = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(hints.name, "UTF-8")
+ "&artist_name=" + URLEncoder.encode(hints.artist, "UTF-8") + "&duration=" + want;
JSONObject r = new JSONObject(httpGet(u, null));
String syn = r.optString("syncedLyrics");
if (syn != null && syn.contains("[")) return syn;
} catch (Exception ignored) {}
}
// search 候选：优先带时间轴的
String u = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(hints.name, "UTF-8")
+ (hints.artist.isEmpty() ? "" : "&artist_name=" + URLEncoder.encode(hints.artist, "UTF-8"));
JSONArray arr = new JSONArray(httpGet(u, null));
List<JSONObject> synced = new ArrayList<>();
String plainBest = null;
long plainBestDiff = Long.MAX_VALUE;
for (int i = 0; i < arr.length(); i++) {
JSONObject r = arr.getJSONObject(i);
if (nameScore(r.optString("trackName"), hints.nameCands) < 80) continue;
long diff = want > 0 ? Math.abs((long) (r.optDouble("duration") * 1000) - want * 1000) : 0;
if (want > 0 && diff > 12000) continue;
String syn = r.optString("syncedLyrics");
if (syn != null && syn.contains("[")) { synced.add(r); continue; }
String plain = r.optString("plainLyrics");
if (plain != null && plain.contains("\n") && diff < plainBestDiff) {
plainBestDiff = diff;
plainBest = plain;
}
}
if (!synced.isEmpty()) {
Collections.sort(synced, (a, b) -> Long.compare(
want > 0 ? Math.abs((long) (a.optDouble("duration") * 1000) - want * 1000) : 0,
want > 0 ? Math.abs((long) (b.optDouble("duration") * 1000) - want * 1000) : 0));
return synced.get(Math.floorMod(alt, synced.size())).optString("syncedLyrics");
}
if (plainBest != null) return synthPlain(plainBest, want);
return null;
}

/** 无时间轴的纯文本歌词：按时长均分生成近似时间轴 */
private static String synthPlain(String plain, long durSec) {
List<String> lines = new ArrayList<>();
for (String s : plain.split("\n")) {
String t = s.trim();
if (!t.isEmpty()) lines.add(t);
}
if (lines.size() < 5) return null;
long total = durSec > 0 ? durSec * 1000L : lines.size() * 4000L;
StringBuilder sb = new StringBuilder();
for (int i = 0; i < lines.size(); i++) {
long ms = total * i / lines.size();
sb.append(String.format("[%02d:%02d.%03d]", ms / 60000, (ms % 60000) / 1000, ms % 1000))
.append(lines.get(i)).append("\n");
}
return sb.toString();
}

// ---------------- 酷狗 ----------------
private static String kugouLyrics(Track track, Hints hints, int alt) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
Map<Long, Cand> byId = new HashMap<>();
for (String q : hints.queries) {
if (byId.size() > 0 && q.equals(hints.queries.get(0)) == false) break; // 首个查询有结果就够了
String url = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword="
+ URLEncoder.encode(q, "UTF-8");
JSONObject r = new JSONObject(httpGet(url, null));
JSONArray cands = r.optJSONArray("candidates");
if (cands == null) continue;
for (int i = 0; i < cands.length(); i++) {
JSONObject cj = cands.getJSONObject(i);
long id = cj.optLong("id");
if (id <= 0 || byId.containsKey(id)) continue;
int score = nameScore(cj.optString("song"), hints.nameCands);
long diff = wantDur > 0 ? Math.abs(cj.optLong("duration") - wantDur) : 0;
if (wantDur > 0 && diff > 12000) continue;
String singer = cj.optString("singer");
boolean artistHit = singer.length() >= 2 && track.title != null
&& (track.title.contains(singer) || singer.equals(hints.artist));
int group;
if (score >= 80) group = 0;
else if (score == 50 && artistHit) group = 1;
else if (score == 0 && artistHit && diff <= 5000) group = 2;
else continue;
Cand c = new Cand(id, group, diff);
c.ak = cj.optString("accesskey");
byId.put(id, c);
}
}
List<Cand> pool = new ArrayList<>(byId.values());
Collections.sort(pool, (a, b) -> a.group != b.group ? Integer.compare(a.group, b.group)
: Long.compare(a.diff, b.diff));
if (pool.isEmpty()) return null;
for (int k = 0; k < pool.size(); k++) {
Cand c = pool.get(Math.floorMod(alt + k, pool.size()));
try {
JSONObject r = new JSONObject(httpGet(
"https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=" + c.id
+ "&accesskey=" + c.ak, null));
String content = r.optString("content");
if (content == null || content.isEmpty()) continue;
String text;
try {
text = new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8);
} catch (Exception e) {
text = content;
}
if (text.contains("[")) return text;
} catch (Exception ignored) {}
}
return null;
}

// ---------------- 歌词源顺序配置 ----------------
public static final String[] SRC_KEYS = {"qq", "netease", "kugou", "amll", "lrclib"};
public static final String[] SRC_NAMES = {"QQ音乐", "网易云音乐", "酷狗音乐", "AMLL TTML", "LRCLIB"};
private static final String DEFAULT_ORDER = "qq,netease,kugou,amll,lrclib";

public static List<String> sourceOrder(Context ctx) {
String saved = ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getString("source_order", "");
List<String> out = new ArrayList<>();
if (saved != null && !saved.isEmpty()) {
for (String k : saved.split(",")) {
k = k.trim();
if (isSrcKey(k) && !out.contains(k)) out.add(k);
}
}
for (String k : DEFAULT_ORDER.split(",")) if (!out.contains(k)) out.add(k);
return out;
}

public static void setSourceOrder(Context ctx, List<String> order) {
StringBuilder sb = new StringBuilder();
for (String k : order) { if (sb.length() > 0) sb.append(","); sb.append(k); }
ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).edit()
.putString("source_order", sb.toString()).apply();
}

private static boolean isSrcKey(String k) {
for (String s : SRC_KEYS) if (s.equals(k)) return true;
return false;
}

public static String srcName(String key) {
for (int i = 0; i < SRC_KEYS.length; i++) if (SRC_KEYS[i].equals(key)) return SRC_NAMES[i];
return key;
}

private static String srcLabel(String key) {
if (key.equals("qq")) return "QQ音乐歌词";
if (key.equals("amll")) return "AMLL TTML 歌词";
if (key.equals("lrclib")) return "LRCLIB 歌词";
if (key.equals("kugou")) return "酷狗歌词";
return "网易云歌词";
}

private static String srcShort(String key) {
if (key.equals("qq")) return "QQ音乐";
if (key.equals("amll")) return "AMLL TTML";
if (key.equals("lrclib")) return "LRCLIB";
if (key.equals("kugou")) return "酷狗";
return "网易云";
}

// ---------------- QQ 音乐 ----------------
private static class QCand {
String mid; int group; long diff;
QCand(String m, int g, long d) { mid = m; group = g; diff = d; }
}

private static List<QCand> qqCandidates(Track track, Hints hints) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
Map<String, QCand> byMid = new HashMap<>();
for (String q : hints.queries) {
String url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?format=json&p=1&n=10&w="
+ URLEncoder.encode(q, "UTF-8");
JSONObject r = new JSONObject(httpGet(url, "https://y.qq.com/"));
JSONObject data = r.optJSONObject("data");
JSONObject song = data == null ? null : data.optJSONObject("song");
JSONArray list = song == null ? null : song.optJSONArray("list");
if (list == null) continue;
for (int i = 0; i < list.length(); i++) {
JSONObject sj = list.getJSONObject(i);
String mid = sj.optString("songmid");
if (mid.isEmpty() || byMid.containsKey(mid)) continue;
int score = nameScore(sj.optString("songname"), hints.nameCands);
long diff = wantDur > 0 ? Math.abs(sj.optLong("interval") * 1000L - wantDur) : Long.MAX_VALUE / 2;
boolean artistHit = false;
JSONArray sgs = sj.optJSONArray("singer");
if (sgs != null) {
for (int a = 0; a < sgs.length(); a++) {
String an = sgs.getJSONObject(a).optString("name");
if (an.length() >= 2 && ((track.title != null && track.title.contains(an)) || an.equals(hints.artist))) { artistHit = true; break; }
}
}
int group;
if (score >= 80) group = 0;
else if (score == 50 && artistHit) group = 1;
else if (score == 0 && artistHit && diff <= 5000) group = 2;
else continue;
byMid.put(mid, new QCand(mid, group, diff));
}
if (!byMid.isEmpty()) break;
}
List<QCand> pool = new ArrayList<>(byMid.values());
Collections.sort(pool, (a, b) -> a.group != b.group ? Integer.compare(a.group, b.group)
: Long.compare(a.diff, b.diff));
return pool;
}

private static String qqLyrics(Track track, Hints hints, int alt) throws Exception {
List<QCand> pool = qqCandidates(track, hints);
if (pool.isEmpty()) return null;
for (int k = 0; k < pool.size(); k++) {
QCand c = pool.get(Math.floorMod(alt + k, pool.size()));
try {
JSONObject r = new JSONObject(httpGet(
"https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + c.mid
+ "&format=json&nobase64=1&g_tk=5381", "https://y.qq.com/portal/player.html"));
String text = r.optString("lyric");
if (text != null && !text.contains("[")) {
try {
String dec = new String(Base64.decode(text, Base64.DEFAULT), StandardCharsets.UTF_8);
if (dec.contains("[")) text = dec;
} catch (Exception ignored) {}
}
if (text != null && text.contains("[")) return text;
} catch (Exception ignored) {}
}
return null;
}

// ---------------- AMLL TTML（按平台歌曲 ID 查 amll-ttml-db） ----------------
private static String amllLyrics(Track track, Hints hints, int alt) throws Exception {
try {
List<QCand> pool = qqCandidates(track, hints);
int n = Math.min(pool.size(), 3);
for (int k = 0; k < n; k++) {
QCand c = pool.get(Math.floorMod(alt + k, pool.size()));
String lrc = amllFetch("qq-lyrics/" + c.mid + ".ttml");
if (lrc != null) return lrc;
}
} catch (Exception ignored) {}
try {
long id = neteaseBestId(track, hints);
if (id > 0) {
String lrc = amllFetch("ncm-lyrics/" + id + ".ttml");
if (lrc != null) return lrc;
}
} catch (Exception ignored) {}
return null;
}

private static String amllFetch(String path) {
String[] bases = {
"https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/",
"https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/"
};
for (String b : bases) {
try {
String ttml = httpGet(b + path, null);
if (ttml != null && ttml.contains("<tt")) {
String lrc = ttmlToLrc(ttml);
if (lrc != null) return lrc;
}
} catch (Exception ignored) {}
}
return null;
}

private static long neteaseBestId(Track track, Hints hints) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
long bestId = -1; int bestGroup = 99; long bestDiff = Long.MAX_VALUE;
for (String q : hints.queries) {
String url = "https://music.163.com/api/cloudsearch/pc?type=1&limit=10&offset=0&s="
+ URLEncoder.encode(q, "UTF-8");
JSONObject r = new JSONObject(httpGet(url, "https://music.163.com"));
JSONObject res = r.optJSONObject("result");
JSONArray songs = res == null ? null : res.optJSONArray("songs");
if (songs == null) continue;
for (int i = 0; i < songs.length(); i++) {
JSONObject sj = songs.getJSONObject(i);
long id = sj.optLong("id");
if (id <= 0) continue;
int score = nameScore(sj.optString("name"), hints.nameCands);
long diff = wantDur > 0 ? Math.abs(sj.optLong("dt") - wantDur) : Long.MAX_VALUE / 2;
boolean artistHit = false;
JSONArray ars = sj.optJSONArray("ar");
if (ars != null && track.title != null) {
for (int a = 0; a < ars.length(); a++) {
String an = ars.getJSONObject(a).optString("name");
if (an.length() >= 2 && track.title.contains(an)) { artistHit = true; break; }
}
}
int group;
if (score >= 80) group = 0;
else if (score == 50 && artistHit) group = 1;
else if (score == 0 && artistHit && diff <= 5000) group = 2;
else continue;
if (group < bestGroup || (group == bestGroup && diff < bestDiff)) { bestGroup = group; bestDiff = diff; bestId = id; }
}
if (bestId > 0) break;
}
return bestId;
}

private static final Pattern TTML_P = Pattern.compile("<p\\b[^>]*?\\bbegin=\"([^\"]+)\"[^>]*>(.*?)</p>", Pattern.DOTALL);
private static final Pattern TTML_TRANS = Pattern.compile("<span\\b[^>]*(?:x-translation|x-roman)[^>]*>.*?</span>", Pattern.DOTALL);

/** TTML（逐字轴）转行级 LRC：取每行 begin 时间，剥掉翻译/罗马音 span 与全部标签 */
private static String ttmlToLrc(String ttml) {
Matcher m = TTML_P.matcher(ttml);
StringBuilder sb = new StringBuilder();
int count = 0;
while (m.find()) {
long ms = ttmlTime(m.group(1));
if (ms < 0) continue;
String inner = TTML_TRANS.matcher(m.group(2)).replaceAll("");
inner = inner.replaceAll("<[^>]+>", "");
inner = inner.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
.replace("&apos;", "'").replace("&amp;", "&");
inner = inner.replaceAll("\\s+", " ").trim();
if (inner.isEmpty()) continue;
sb.append(String.format("[%02d:%02d.%03d]", ms / 60000, (ms % 60000) / 1000, ms % 1000))
.append(inner).append("\n");
count++;
}
return count >= 5 ? sb.toString() : null;
}

private static long ttmlTime(String s) {
try {
s = s.trim();
if (s.endsWith("s")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
String[] parts = s.split(":");
double sec = Double.parseDouble(parts[parts.length - 1]);
long min = parts.length >= 2 ? Long.parseLong(parts[parts.length - 2]) : 0;
long hr = parts.length >= 3 ? Long.parseLong(parts[parts.length - 3]) : 0;
return (long) ((hr * 3600 + min * 60) * 1000 + sec * 1000);
} catch (Exception e) { return -1; }
}

static String httpGet(String url, String referer) throws Exception {
HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000);
c.setReadTimeout(10000);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
if (referer != null) c.setRequestProperty("Referer", referer);
java.io.InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
StringBuilder sb = new StringBuilder();
String line;
while ((line = r.readLine()) != null) sb.append(line);
r.close();
return sb.toString();
}

private static String readFile(File f) throws Exception {
BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
StringBuilder sb = new StringBuilder();
String line;
while ((line = r.readLine()) != null) sb.append(line).append("\n");
r.close();
return sb.toString();
}

private static void writeFile(File f, String content) throws Exception {
FileOutputStream out = new FileOutputStream(f);
out.write(content.getBytes(StandardCharsets.UTF_8));
out.close();
}
}
