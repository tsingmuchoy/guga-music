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

/** 歌词引擎 v4：QQ音乐 / 网易云 / 酷狗 / AMLL TTML / LRCLIB 按用户设置顺序级联（默认 QQ->网易云->酷狗->AMLL->LRCLIB），B 站字幕兜底；本地缓存 lyrics_v5 目录 */
public class Lyrics {

public static class Line {
public final long timeMs;
public final String text;
public String trans; // 中文翻译（有数据时按时间戳对齐进来）
public String roma;  // 罗马音（同上）
public List<Word> words; // 逐字时间轴（网易云 YRC / AMLL TTML，有数据时才有）
public Line(long t, String s) { timeMs = t; text = s; }
}

/** 逐字时间轴的一个字/词 */
public static class Word {
public long startMs; public long durMs; public String text;
public Word(long s, long d, String t) { startMs = s; durMs = d; text = t; }
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

// ---------------- 专辑封面（轻量化设计·三源） ----------------
// 网易云 + QQ + 酷我三源汇池统一排序：歌手命中组绝对优先（防同名翻唱抢位，v1.17.0 教训），
// 组内按歌名分、时长差排；无歌手命中时仅接受「歌名完全一致且时长差≤3秒」。
// 结果只存内存（进程级 Map），图片走 ImgLoader 原有内存缓存；不写任何磁盘文件。
public interface CoverCb { void onCover(String url); }
private static final java.util.Map<String, String> COVER_MEM = new java.util.HashMap<>();

public static boolean isCoverArt(Context ctx) {
return ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getBoolean("cover_art", true);
}
public static void setCoverArt(Context ctx, boolean on) {
ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).edit().putBoolean("cover_art", on).apply();
}

// 日/韩歌曲的中文翻译与罗马音开关（用户自选，存 lyrics_cfg，默认开）
public static boolean isShowTrans(Context ctx) {
return ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getBoolean("show_trans", true);
}
public static void setShowTrans(Context ctx, boolean on) {
ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).edit().putBoolean("show_trans", on).apply();
}
public static boolean isShowRoma(Context ctx) {
return ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getBoolean("show_roma", true);
}
public static void setShowRoma(Context ctx, boolean on) {
ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).edit().putBoolean("show_roma", on).apply();
}
public static boolean isShowWords(Context ctx) {
return ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getBoolean("show_words", true);
}
public static void setShowWords(Context ctx, boolean on) {
ctx.getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).edit().putBoolean("show_words", on).apply();
}

public static void fetchCover(final Context ctx, final Track track, final CoverCb cb) {
if (track == null || track.bvid == null || !isCoverArt(ctx)) { cb.onCover(null); return; }
// 用户手动匹配的封面（手动匹配歌曲时锁定）优先于一切自动匹配
LyricsDb.Bind mbd = LyricsDb.get(ctx, track.bvid);
if (mbd != null && mbd.cover != null && !mbd.cover.isEmpty()) { cb.onCover(mbd.cover); return; }
String memo = COVER_MEM.get(track.bvid);
if (memo != null) { cb.onCover(memo.isEmpty() ? null : memo); return; }
POOL.execute(() -> {
CoverCand w = null;
try { w = pickCoverArt(track, hintsOf(track)); } catch (Exception ignored) {}
COVER_MEM.put(track.bvid, w == null ? "" : w.url);
if (w != null) Diag.log(ctx, "\uD83D\uDDBC 专辑封面已替换（" + w.src + "专辑图）");
final String f = w == null ? null : w.url;
new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onCover(f));
});
}

private static class CoverCand {
String url; String src; int score; boolean artistHit; long diff;
CoverCand(String u, String s, int sc, boolean ah, long d) { url = u; src = s; score = sc; artistHit = ah; diff = d; }
}

/** 同一张封面图被多个候选共用时只保留记录最好的一条（防差候选覆盖好记录） */
private static void putCover(java.util.Map<String, CoverCand> byUrl, CoverCand c) {
CoverCand o = byUrl.get(c.url);
if (o == null || c.score > o.score || (c.score == o.score && c.artistHit && !o.artistHit)
|| (c.score == o.score && c.artistHit == o.artistHit && c.diff < o.diff)) byUrl.put(c.url, c);
}

/** 封面专用歌名候选：在歌词候选基础上再去掉结尾年份（如「初恋 1990」→「初恋」），
 *  并剔除纯歌手名（否则「feat. 某歌手」的歌会因标题含歌手名被误判歌名对版） */
private static java.util.List<String> coverCandsOf(Hints hints) {
java.util.List<String> out = new java.util.ArrayList<>();
for (String c : hints.nameCands) {
if (!hints.artist.isEmpty() && normName(c).equals(normName(hints.artist))) continue;
out.add(c);
}
for (String c : hints.nameCands) {
String v = c.replaceAll("\\s*(19|20)\\d{2}\\s*$", "").trim();
if (v.isEmpty() || out.contains(v)) continue;
if (!hints.artist.isEmpty() && normName(v).equals(normName(hints.artist))) continue;
out.add(v);
}
return out;
}

private static CoverCand pickCoverArt(Track track, Hints hints) {
java.util.List<String> cands = coverCandsOf(hints);
java.util.Map<String, CoverCand> byUrl = new java.util.LinkedHashMap<>();
try { collectNetEaseCovers(track, hints, cands, byUrl); } catch (Exception ignored) {}
try { collectQqCovers(track, hints, cands, byUrl); } catch (Exception ignored) {}
try { collectKuwoCovers(track, hints, cands, byUrl); } catch (Exception ignored) {}
CoverCand bestHit = null, bestLoose = null;
for (CoverCand c : byUrl.values()) {
if (c.artistHit && (c.score >= 100 || (c.score >= 80 && c.diff <= 3000))) {
if (bestHit == null || c.score > bestHit.score || (c.score == bestHit.score && c.diff < bestHit.diff)) bestHit = c;
} else if (c.score == 100 && c.diff <= 3000) {
if (bestLoose == null || c.diff < bestLoose.diff) bestLoose = c;
}
}
return bestHit != null ? bestHit : bestLoose;
}

private static void collectNetEaseCovers(Track track, Hints hints, java.util.List<String> cands,
java.util.Map<String, CoverCand> byUrl) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
int used = 0;
for (String q : hints.queries) {
if (used++ >= 3) break;
JSONObject r = new JSONObject(httpGet("https://music.163.com/api/cloudsearch/pc?type=1&limit=10&offset=0&s="
+ URLEncoder.encode(q, "UTF-8"), "https://music.163.com"));
JSONArray songs = r.optJSONObject("result") == null ? null : r.getJSONObject("result").optJSONArray("songs");
if (songs == null) continue;
for (int i = 0; i < songs.length(); i++) {
JSONObject sj = songs.getJSONObject(i);
int score = nameScore(sj.optString("name"), cands);
if (score < 80) continue;
JSONObject al = sj.optJSONObject("al");
String pic = al == null ? "" : al.optString("picUrl");
if (pic.isEmpty()) continue;
long dt = sj.optLong("dt");
long diff = wantDur > 0 ? Math.abs(dt - wantDur) : Long.MAX_VALUE / 2;
boolean artistHit = false;
JSONArray ars = sj.optJSONArray("ar");
if (ars != null) {
for (int a = 0; a < ars.length(); a++) {
String an = ars.getJSONObject(a).optString("name");
if (!an.isEmpty() && ((track.title != null && track.title.contains(an))
|| (!hints.artist.isEmpty() && (hints.artist.contains(an) || an.contains(hints.artist))))) { artistHit = true; break; }
}
}
String url = pic.contains("?") ? pic : pic + "?param=400y400";
putCover(byUrl, new CoverCand(url, "网易云", score, artistHit, diff));
}
}
}

private static void collectQqCovers(Track track, Hints hints, java.util.List<String> cands,
java.util.Map<String, CoverCand> byUrl) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
int used = 0;
for (String q : hints.queries) {
if (used++ >= 3) break;
JSONObject r = new JSONObject(httpGet("https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?format=json&p=1&n=10&w="
+ URLEncoder.encode(q, "UTF-8"), "https://y.qq.com/"));
JSONObject data = r.optJSONObject("data");
JSONObject song = data == null ? null : data.optJSONObject("song");
JSONArray list = song == null ? null : song.optJSONArray("list");
if (list == null) continue;
for (int i = 0; i < list.length(); i++) {
JSONObject sj = list.getJSONObject(i);
int score = nameScore(sj.optString("songname"), cands);
if (score < 80) continue;
String amid = sj.optString("albummid");
if (amid.isEmpty()) continue;
long diff = wantDur > 0 ? Math.abs(sj.optLong("interval") * 1000L - wantDur) : Long.MAX_VALUE / 2;
boolean artistHit = false;
JSONArray sgs = sj.optJSONArray("singer");
if (sgs != null) {
for (int a = 0; a < sgs.length(); a++) {
String an = sgs.getJSONObject(a).optString("name");
if (an.length() >= 2 && ((track.title != null && track.title.contains(an))
|| (!hints.artist.isEmpty() && (hints.artist.contains(an) || an.equals(hints.artist))))) { artistHit = true; break; }
}
}
String url = "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + amid + ".jpg";
putCover(byUrl, new CoverCand(url, "QQ音乐", score, artistHit, diff));
}
}
}

/** 歌词不对时换下一候选版本：序号 +1、清缓存、重新匹配 */
public static void refetchNext(final Context ctx, final Track track, final BiliApi api, final Cb cb) {
POOL.execute(() -> {
Context app = ctx.getApplicationContext();
android.content.SharedPreferences sp = app.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE);
int next = sp.getInt("alt_" + track.bvid, 0) + 1;
sp.edit().putInt("alt_" + track.bvid, next).apply();
LyricsDb.unbind(app, track.bvid); // 用户主动换版本：解除手动锁定，回到自动匹配
try {
File dir = new File(app.getFilesDir(), "lyrics_v5");
new File(dir, track.bvid + ".lrc").delete();
new File(dir, track.bvid + ".none").delete();
} catch (Exception ignored) {}
Result r = fetchSync(app, track, api);
new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(r));
});
}

private static Result fetchSync(Context ctx, Track track, BiliApi api) {
File dir = new File(ctx.getFilesDir(), "lyrics_v5");
if (!dir.exists()) dir.mkdirs();
File cache = new File(dir, track.bvid + ".lrc");
File none = new File(dir, track.bvid + ".none");
// 手动锁定的歌词版本（歌词页手动搜索确认后按 BV 存进 lyrics.db）：优先于自动匹配
LyricsDb.Bind bind = LyricsDb.get(ctx, track.bvid);
try {
if (cache.exists()) {
Result cr = parseCache(readFile(cache));
// 有锁定时只有「正是锁定版」的缓存才直接用；没锁定走原逻辑
if (cr != null && (bind == null || cr.source.equals(bind.label))) return maybeWords(ctx, track, maybeEnrich(ctx, track, cr, cache, dir), cache, dir);
}
if (bind != null) {
try {
MCand mc = new MCand();
mc.src = bind.src; mc.ref = bind.ref; mc.name = bind.name; mc.artist = bind.artist; mc.durMs = bind.durMs;
Result br = acceptPack(cache, fetchBoundPack(mc), bind.label);
if (br != null) { Diag.log(ctx, "🎤 歌词命中：手动锁定（" + srcShort(bind.src) + "）"); return maybeWords(ctx, track, maybeEnrich(ctx, track, br, cache, dir), cache, dir); }
} catch (Exception ignored) {}
// 锁定源这次没取到：落到下面自动流程兜底，绑定保留、下次播放再试
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
LrcPack pack = null;
if (key.equals("qq")) pack = qqLyrics(track, hints, alt);
else if (key.equals("netease")) pack = neteaseLyrics(track, hints, alt);
else if (key.equals("kugou")) { String s1 = kugouLyrics(track, hints, alt); if (s1 != null) pack = new LrcPack(s1); }
else if (key.equals("kuwo")) { pack = kuwoLyrics(track, hints, alt); }
else if (key.equals("amll")) { pack = amllLyrics(track, hints, alt); }
else if (key.equals("lrclib")) { String s1 = lrclibLyrics(track, hints, alt); if (s1 != null) pack = new LrcPack(s1); }
Result r = acceptPack(cache, pack, srcLabel(key));
if (r != null) { Diag.log(ctx, "🎤 歌词命中：" + srcShort(key) + "《" + hints.name + "》"); return maybeWords(ctx, track, maybeEnrich(ctx, track, r, cache, dir), cache, dir); }
} catch (Exception ignored) {}
}

// B 站字幕兜底
Result sr = trySubtitles(ctx, track, api, cache);
if (sr != null) return maybeWords(ctx, track, maybeEnrich(ctx, track, sr, cache, dir), cache, dir);

try { writeFile(none, ""); trimLyricsDir(none); } catch (Exception ignored) {}
Diag.log(ctx, "🎤 歌词未命中：《" + hints.name + "》");
return new Result(new ArrayList<>(), "");
}

// ---------------- 手动搜索歌词（用户亲选 + 按 BV 锁定） ----------------
public static class MCand {
public String src = "";   // 源 key（qq/netease/kugou/kuwo/lrclib；amll 无独立搜索不参与）
public String ref = "";   // 源内定位：网易云/酷我=数字ID、QQ=songmid、酷狗=id:accesskey、LRCLIB=记录ID
public String name = "";
public String artist = "";
public long durMs;
public String cover = "";  // 专辑封面 URL（网易云/QQ/酷我搜索自带；酷狗/LRCLIB 无）
public String lrcText;    // 仅 LRCLIB：搜索结果自带歌词正文
}

/** 手动搜索：按用户设置的源顺序给原始候选（不做自动打分过滤，用户自己挑）。须在后台线程调用 */
public static List<MCand> manualSearch(Context ctx, String name, String artist) {
List<MCand> out = new ArrayList<>();
String q = artist == null || artist.trim().isEmpty() ? name : name + " " + artist.trim();
for (String key : sourceOrder(ctx)) {
try {
if (key.equals("qq")) manualQq(q, out);
else if (key.equals("netease")) manualNetease(q, out);
else if (key.equals("kugou")) manualKugou(q, out);
else if (key.equals("kuwo")) manualKuwo(q, out);
else if (key.equals("lrclib")) manualLrclib(name, artist, out);
} catch (Exception ignored) {}
}
return out;
}

private static void manualQq(String q, List<MCand> out) throws Exception {
JSONObject r = new JSONObject(httpGet("https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?format=json&p=1&n=15&w="
+ URLEncoder.encode(q, "UTF-8"), "https://y.qq.com/"));
JSONObject data = r.optJSONObject("data");
JSONObject song = data == null ? null : data.optJSONObject("song");
JSONArray list = song == null ? null : song.optJSONArray("list");
if (list == null) return;
for (int i = 0; i < list.length(); i++) {
JSONObject sj = list.getJSONObject(i);
String mid = sj.optString("songmid");
if (mid.isEmpty()) continue;
MCand c = new MCand();
c.src = "qq"; c.ref = mid;
c.name = sj.optString("songname");
String albummid = sj.optString("albummid");
if (!albummid.isEmpty()) c.cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albummid + ".jpg";
StringBuilder ab = new StringBuilder();
JSONArray sgs = sj.optJSONArray("singer");
if (sgs != null) for (int a = 0; a < sgs.length(); a++) {
if (ab.length() > 0) ab.append("、");
ab.append(sgs.getJSONObject(a).optString("name"));
}
c.artist = ab.toString();
c.durMs = sj.optLong("interval") * 1000L;
out.add(c);
}
}

private static void manualNetease(String q, List<MCand> out) throws Exception {
JSONObject r = new JSONObject(httpGet("https://music.163.com/api/cloudsearch/pc?type=1&limit=15&offset=0&s="
+ URLEncoder.encode(q, "UTF-8"), "https://music.163.com"));
JSONArray songs = r.optJSONObject("result") == null ? null : r.getJSONObject("result").optJSONArray("songs");
if (songs == null) return;
for (int i = 0; i < songs.length(); i++) {
JSONObject sj = songs.getJSONObject(i);
long id = sj.optLong("id");
if (id <= 0) continue;
MCand c = new MCand();
c.src = "netease"; c.ref = String.valueOf(id);
c.name = sj.optString("name");
StringBuilder ab = new StringBuilder();
JSONArray ars = sj.optJSONArray("ar");
if (ars != null) for (int a = 0; a < ars.length(); a++) {
if (ab.length() > 0) ab.append("、");
ab.append(ars.getJSONObject(a).optString("name"));
}
c.artist = ab.toString();
c.durMs = sj.optLong("dt");
JSONObject al = sj.optJSONObject("al");
if (al != null) {
String pu = al.optString("picUrl");
if (!pu.isEmpty()) c.cover = pu + "?param=500y500";
}
out.add(c);
}
}

private static void manualKugou(String q, List<MCand> out) throws Exception {
JSONObject r = new JSONObject(httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword="
+ URLEncoder.encode(q, "UTF-8"), null));
JSONArray cands = r.optJSONArray("candidates");
if (cands == null) return;
for (int i = 0; i < cands.length() && i < 15; i++) {
JSONObject cj = cands.getJSONObject(i);
long id = cj.optLong("id");
if (id <= 0) continue;
MCand c = new MCand();
c.src = "kugou"; c.ref = id + ":" + cj.optString("accesskey");
c.name = cj.optString("song");
c.artist = cj.optString("singer");
c.durMs = cj.optLong("duration");
out.add(c);
}
}

private static void manualKuwo(String q, List<MCand> out) throws Exception {
JSONArray list = kuwoSearch(q, 20);
for (int i = 0; i < list.length(); i++) {
JSONObject sj = list.getJSONObject(i);
long rid = kuwoRid(sj);
if (rid <= 0) continue;
MCand c = new MCand();
c.src = "kuwo"; c.ref = String.valueOf(rid);
c.name = kuwoName(sj);
c.artist = kuwoUnescape(sj.optString("ARTIST"));
c.durMs = kuwoDurMs(sj);
String shortPic = sj.optString("web_albumpic_short");
if (!shortPic.isEmpty() && shortPic.indexOf('/') >= 0)
c.cover = "https://img1.kuwo.cn/star/albumcover/500/" + shortPic.substring(shortPic.indexOf('/') + 1);
out.add(c);
}
}

private static void manualLrclib(String name, String artist, List<MCand> out) throws Exception {
String u = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(name, "UTF-8")
+ (artist == null || artist.trim().isEmpty() ? "" : "&artist_name=" + URLEncoder.encode(artist.trim(), "UTF-8"));
JSONArray arr = new JSONArray(httpGet(u, null));
for (int i = 0; i < arr.length() && i < 15; i++) {
JSONObject r = arr.getJSONObject(i);
String syn = r.optString("syncedLyrics");
if (syn == null || !syn.contains("[")) continue;
MCand c = new MCand();
c.src = "lrclib"; c.ref = String.valueOf(r.optLong("id"));
c.name = r.optString("trackName");
c.artist = r.optString("artistName");
c.durMs = (long) (r.optDouble("duration") * 1000);
c.lrcText = syn;
out.add(c);
}
}

/** 按手动候选的定位直接取歌词原文（后台线程调用） */
/** 按手动候选的定位直接取歌词（连带译文，罗马音由 maybeEnrich 统一补）。后台线程调用 */
public static LrcPack fetchBoundPack(MCand c) {
try {
if (c.src.equals("qq")) {
LrcPack mp = null;
try { mp = qqPackMusicu(c.ref); } catch (Throwable ignored) {}
if (mp != null) return mp;
JSONObject r = new JSONObject(httpGet(
"https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + c.ref
+ "&format=json&nobase64=1&g_tk=5381", "https://y.qq.com/portal/player.html"));
String text = r.optString("lyric");
if (text != null && !text.contains("[")) {
try {
String dec = new String(Base64.decode(text, Base64.DEFAULT), StandardCharsets.UTF_8);
if (dec.contains("[")) text = dec;
} catch (Exception ignored) {}
}
if (text != null && text.contains("[")) {
LrcPack p = new LrcPack(text);
String tr = r.optString("trans");
if (tr != null && !tr.contains("[")) {
try {
String dec = new String(Base64.decode(tr, Base64.DEFAULT), StandardCharsets.UTF_8);
if (dec.contains("[")) tr = dec;
} catch (Exception ignored) {}
}
if (tr != null && tr.contains("[")) p.trans = tr;
return p;
}
} else if (c.src.equals("netease")) {
// 先走 v1 接口：一次拿齐歌词+翻译+罗马音（旧 api/song/lyric 没有 romalrc，手动匹配就白选了）
try {
JSONObject d = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric/v1?id=" + c.ref + "&cp=false&lv=1&kv=1&tv=1&rv=1&yv=1",
"https://music.163.com"));
JSONObject lrc = d.optJSONObject("lrc");
String text = lrc == null ? null : lrc.optString("lyric");
if (text != null && text.contains("[") && parseLrc(text).size() >= 5) {
LrcPack p = new LrcPack(text);
JSONObject tly = d.optJSONObject("tlyric");
if (tly != null) {
String tt = tly.optString("lyric");
if (tt != null && tt.contains("[")) p.trans = tt;
}
JSONObject rly = d.optJSONObject("romalrc");
if (rly != null) {
String rr = rly.optString("lyric");
if (rr != null && rr.contains("[")) p.roma = rr;
}
try { attachNeteaseWords(p, Long.parseLong(c.ref)); } catch (Exception ignored) {}
return p;
}
} catch (Exception ignored) {}
JSONObject lr = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=" + c.ref, "https://music.163.com"));
JSONObject lrc = lr.optJSONObject("lrc");
if (lrc != null) {
String text = lrc.optString("lyric");
if (text != null && text.contains("[")) {
LrcPack p = new LrcPack(text);
JSONObject tly = lr.optJSONObject("tlyric");
if (tly != null) {
String tt = tly.optString("lyric");
if (tt != null && tt.contains("[")) p.trans = tt;
}
try { attachNeteaseWords(p, Long.parseLong(c.ref)); } catch (Exception ignored) {}
return p;
}
}
} else if (c.src.equals("kugou")) {
int p = c.ref.indexOf(':');
if (p > 0) {
JSONObject r = new JSONObject(httpGet(
"https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=" + c.ref.substring(0, p)
+ "&accesskey=" + c.ref.substring(p + 1), null));
String content = r.optString("content");
if (content != null && !content.isEmpty()) {
String text;
try { text = new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8); }
catch (Exception e) { text = content; }
if (text.contains("[")) return new LrcPack(text);
}
}
} else if (c.src.equals("kuwo")) {
LrcPack kp = kuwoFetchLrc(Long.parseLong(c.ref));
if (kp != null) return kp;
} else if (c.src.equals("lrclib")) {
if (c.lrcText != null && c.lrcText.contains("[")) return new LrcPack(c.lrcText);
String u = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(c.name, "UTF-8")
+ "&artist_name=" + URLEncoder.encode(c.artist == null ? "" : c.artist, "UTF-8")
+ (c.durMs > 0 ? "&duration=" + (c.durMs / 1000) : "");
JSONObject r = new JSONObject(httpGet(u, null));
String syn = r.optString("syncedLyrics");
if (syn != null && syn.contains("[")) return new LrcPack(syn);
}
} catch (Exception ignored) {}
return null;
}

/** 只取歌词原文（手动页预览用） */
public static String fetchBoundLrc(MCand c) {
LrcPack p = fetchBoundPack(c);
return p == null ? null : p.lrc;
}

/** 用户确认某首歌：整包写入缓存（歌词+翻译+罗马音+逐字都在包里一次钉死，不再靠事后自动补齐碰运气）
 *  并按 BV 锁定（存 lyrics.db，含封面与原歌名），以后这首歌都用它 */
public static boolean applyManual(Context ctx, String bvid, MCand c, LrcPack pack) {
if (pack == null || pack.lrc == null || parseLrc(pack.lrc).size() < 5) return false;
String label = srcLabel(c.src) + "（手动）";
try {
File dir = new File(ctx.getFilesDir(), "lyrics_v5");
if (!dir.exists()) dir.mkdirs();
File cache = new File(dir, bvid + ".lrc");
if (acceptPack(cache, pack, label) == null) return false;
new File(dir, bvid + ".none").delete();
} catch (Exception ignored) {}
LyricsDb.Bind b = new LyricsDb.Bind();
b.src = c.src; b.ref = c.ref; b.name = c.name; b.artist = c.artist; b.durMs = c.durMs; b.label = label;
b.cover = c.cover;
LyricsDb.bind(ctx, bvid, b);
COVER_MEM.remove(bvid);
ctx.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE).edit().remove("alt_" + bvid).apply();
return true;
}

/** 只锁封面+原歌名（选中歌曲的歌词取不到时的兜底）：歌词不写缓存、继续自动匹配，
 *  fetchSync 试过绑定源取不到也会自动落回自动流程，不会把歌词弄丢 */
public static void applyManualCoverOnly(Context ctx, String bvid, MCand c) {
String label = srcLabel(c.src) + "（手动）";
LyricsDb.Bind b = new LyricsDb.Bind();
b.src = c.src; b.ref = c.ref; b.name = c.name; b.artist = c.artist; b.durMs = c.durMs; b.label = label;
b.cover = c.cover;
LyricsDb.bind(ctx, bvid, b);
COVER_MEM.remove(bvid);
ctx.getSharedPreferences("lyrics_alt", Context.MODE_PRIVATE).edit().remove("alt_" + bvid).apply();
}

/** 解除手动锁定并清掉歌词缓存，回到自动匹配 */
public static void clearManual(Context ctx, String bvid) {
LyricsDb.unbind(ctx, bvid);
COVER_MEM.remove(bvid);
try {
File dir = new File(ctx.getFilesDir(), "lyrics_v5");
new File(dir, bvid + ".lrc").delete();
new File(dir, bvid + ".none").delete();
} catch (Exception ignored) {}
}

/** 这首歌的歌词是否被手动锁定 */
public static boolean isBound(Context ctx, String bvid) { return LyricsDb.get(ctx, bvid) != null; }

/** 播放/歌词页显示用的歌名：手动匹配过就用匹配到的原歌名，否则用视频标题 */
public static String displayName(Context ctx, Track t) {
if (t == null) return "";
LyricsDb.Bind bd = LyricsDb.get(ctx, t.bvid);
return bd != null && bd.name != null && !bd.name.isEmpty() ? bd.name : (t.title == null ? "" : t.title);
}

/** 显示用的歌手：手动匹配优先，否则视频 UP 主 */
public static String displayArtist(Context ctx, Track t) {
if (t == null) return "";
LyricsDb.Bind bd = LyricsDb.get(ctx, t.bvid);
return bd != null && bd.artist != null && !bd.artist.isEmpty() ? bd.artist : (t.author == null ? "" : t.author);
}

private static Result trySubtitles(Context ctx, Track track, BiliApi api, File cache) {
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
trimLyricsDir(cache);
Diag.log(ctx, "🎤 歌词命中：视频字幕");
return new Result(box[0], "视频字幕");
}
} catch (Exception ignored) {}
return null;
}

static class LrcPack {
String lrc; String trans; String roma; String words;
LrcPack(String l) { lrc = l; }
}

private static Result acceptPack(File cache, LrcPack pack, String src) {
if (pack == null || pack.lrc == null) return null;
List<Line> lines = parseLrc(pack.lrc);
if (lines.size() < 5) return null;
try {
StringBuilder sb = new StringBuilder("#src:" + src + "\n" + pack.lrc);
if (pack.trans != null && parseLrc(pack.trans).size() >= 3) sb.append("\n#trans:\n").append(pack.trans);
if (pack.roma != null && parseLrc(pack.roma).size() >= 3) sb.append("\n#roma:\n").append(pack.roma);
if (pack.words != null && !pack.words.isEmpty()) sb.append("\n#words:\n").append(pack.words);
writeFile(cache, sb.toString());
trimLyricsDir(cache);
} catch (Exception ignored) {}
return buildResult(lines, src, pack.trans, pack.roma, pack.words);
}

/** 把译文/罗马音 LRC 按时间戳（±800ms 最近行）对齐挂到主歌词行上 */
private static Result buildResult(List<Line> lines, String src, String transLrc, String romaLrc) {
return buildResult(lines, src, transLrc, romaLrc, null);
}

private static Result buildResult(List<Line> lines, String src, String transLrc, String romaLrc, String wordsData) {
if (transLrc != null) align(lines, transLrc, true);
if (romaLrc != null) align(lines, romaLrc, false);
if (wordsData != null) attachWords(lines, wordsData);
return new Result(lines, src);
}

private static void align(List<Line> main, String extraLrc, boolean isTrans) {
List<Line> sub = new ArrayList<>();
for (Line e : parseLrc(extraLrc)) {
if (e.text != null && !e.text.trim().isEmpty()) sub.add(e);
}
if (sub.isEmpty() || main.isEmpty()) return;
// 全局偏移估计：所有 ±3s 内配对的时间差取最密集的 200ms 桶中位数（两源时间轴整体漂移时先校正）
long off = 0;
List<Long> deltas = new ArrayList<>();
for (Line e : sub) for (Line m : main) {
long dd = e.timeMs - m.timeMs;
if (Math.abs(dd) <= 3000) deltas.add(dd);
}
if (deltas.size() >= 3) {
java.util.Map<Long, Integer> buckets = new java.util.HashMap<>();
for (long dd : deltas) {
long b = Math.round(dd / 200.0);
Integer c = buckets.get(b);
buckets.put(b, c == null ? 1 : c + 1);
}
long bestB = 0; int bestC = -1;
for (java.util.Map.Entry<Long, Integer> en : buckets.entrySet()) {
if (en.getValue() > bestC) { bestC = en.getValue(); bestB = en.getKey(); }
}
List<Long> sel = new ArrayList<>();
for (long dd : deltas) if (Math.round(dd / 200.0) == bestB) sel.add(dd);
java.util.Collections.sort(sel);
off = sel.get(sel.size() / 2);
}
// 第一遍：校正后 ±800ms 内按距离从小到大贪心分配（每行只用一次，防相邻行抢位丢行）
int[] m2s = new int[main.size()];
java.util.Arrays.fill(m2s, -1);
boolean[] usedS = new boolean[sub.size()];
List<long[]> pairs = new ArrayList<>();
for (int j = 0; j < sub.size(); j++) for (int i = 0; i < main.size(); i++) {
long dd = Math.abs(main.get(i).timeMs - (sub.get(j).timeMs - off));
if (dd <= 800) pairs.add(new long[]{dd, i, j});
}
java.util.Collections.sort(pairs, (a, b) -> Long.compare(a[0], b[0]));
int assigned = 0;
for (long[] p : pairs) {
int i = (int) p[1], j = (int) p[2];
if (m2s[i] >= 0 || usedS[j]) continue;
m2s[i] = j; usedS[j] = true; assigned++;
}
// 第二遍：顺序填充——未配上的行只许用「前后已配行之间」未被占用的副行，校正后 ±2000ms 内取最近（救单行漂移/时间戳重复造成的疏漏）
if (assigned >= 3) {
for (int i = 0; i < main.size(); i++) {
if (m2s[i] >= 0) continue;
int lo = -1, hi = Integer.MAX_VALUE;
for (int k = 0; k < i; k++) if (m2s[k] > lo) lo = m2s[k];
for (int k = i + 1; k < main.size(); k++) if (m2s[k] >= 0 && m2s[k] < hi) hi = m2s[k];
int best = -1; long bd = Long.MAX_VALUE;
for (int j = 0; j < sub.size(); j++) {
if (usedS[j] || j <= lo || j >= hi) continue;
long cd = Math.abs(main.get(i).timeMs - (sub.get(j).timeMs - off));
if (cd <= 2000 && cd < bd) { bd = cd; best = j; }
}
if (best >= 0) { m2s[i] = best; usedS[best] = true; }
}
}
for (int i = 0; i < main.size(); i++) {
if (m2s[i] < 0) continue;
Line m = main.get(i);
String txt = sub.get(m2s[i]).text;
if (isTrans) { if (m.trans == null) m.trans = txt; }
else if (m.roma == null) m.roma = txt;
}
}

/** 读缓存文件：拆出 #src 主歌词与 #trans/#roma 分段并对齐 */
private static Result parseCache(String content) {
if (content == null) return null;
String src = content.startsWith("#src:") ? content.substring(5, content.indexOf('\n')) : "缓存";
String main = content, trans = null, roma = null, words = null;
int wi = main.indexOf("\n#words:\n");
if (wi >= 0) { words = main.substring(wi + 8); main = main.substring(0, wi); }
int ri = main.indexOf("\n#roma:\n");
if (ri >= 0) { roma = main.substring(ri + 7); main = main.substring(0, ri); }
int ti = main.indexOf("\n#trans:\n");
if (ti >= 0) { trans = main.substring(ti + 8); main = main.substring(0, ti); }
List<Line> lines = parseLrc(main);
if (lines.isEmpty()) return null;
return buildResult(lines, src, trans, roma, words);
}

private static boolean isForeignText(String s) {
for (int i = 0; i < s.length(); i++) {
char c = s.charAt(i);
if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7A3)
|| (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F)) return true;
}
return false;
}

/** 译文/罗马音与主歌词的时间戳重合率（防补错歌的校验门槛） */
private static double correlate(List<Line> main, String extraLrc) {
List<Line> ex = parseLrc(extraLrc);
if (ex.isEmpty()) return 0;
int total = 0, hit = 0;
for (Line m : main) {
if (m.text == null || m.text.trim().isEmpty()) continue;
total++;
for (Line e : ex) if (Math.abs(e.timeMs - m.timeMs) <= 800) { hit++; break; }
}
return total == 0 ? 0 : (double) hit / total;
}

private static double asciiRatio(String lrc) {
List<Line> ex = parseLrc(lrc);
long tot = 0, ok = 0;
for (Line e : ex) {
if (e.text == null) continue;
for (int i = 0; i < e.text.length(); i++) {
char c = e.text.charAt(i);
if (c == ' ' || c == '\t') continue;
tot++;
if (c < 128) ok++;
}
}
return tot == 0 ? 0 : (double) ok / tot;
}

// ---------------- 逐字时间轴（YRC 解析 / 序列化 / 挂载） ----------------

private static class YLine { long startMs; List<Word> words = new ArrayList<>(); }

private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");

/** 解析网易云 YRC：唱词行是 [行起,行长](字起,字长,0)字(字起,字长,0)字…，开头是 JSON 制作信息行（自动跳过） */
private static List<YLine> parseYrc(String yrc) {
List<YLine> out = new ArrayList<>();
if (yrc == null) return out;
for (String raw : yrc.split("\n")) {
Matcher m = YRC_LINE.matcher(raw.trim());
if (!m.find()) continue;
YLine yl = new YLine();
try { yl.startMs = Long.parseLong(m.group(1)); } catch (Exception e) { continue; }
String rest = m.group(3);
int i = 0;
while (i < rest.length()) {
int lp = rest.indexOf('(', i);
if (lp < 0) break;
int rp = rest.indexOf(')', lp);
if (rp < 0) break;
int next = rest.indexOf('(', rp);
String text = next < 0 ? rest.substring(rp + 1) : rest.substring(rp + 1, next);
String[] parts = rest.substring(lp + 1, rp).split(",");
if (parts.length >= 2) {
try {
long ws = Long.parseLong(parts[0].trim());
long wd = Long.parseLong(parts[1].trim());
if (!text.isEmpty()) yl.words.add(new Word(ws, wd, text));
} catch (Exception ignored) {}
}
i = next < 0 ? rest.length() : next;
}
if (yl.words.size() >= 2) out.add(yl);
}
return out;
}

private static String escWord(String s) {
return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
}

private static String unescWord(String s) {
StringBuilder sb = new StringBuilder();
for (int i = 0; i < s.length(); i++) {
char c = s.charAt(i);
if (c == '\\' && i + 1 < s.length()) sb.append(s.charAt(++i));
else sb.append(c);
}
return sb.toString();
}

/** 逐字轴序列化（缓存 #words 段）：每行一条「行时间<TAB>相对起,时长,字;相对起,时长,字…」 */
private static String serializeWords(List<Line> lines) {
StringBuilder sb = new StringBuilder();
for (Line l : lines) {
if (l.words == null || l.words.isEmpty()) continue;
sb.append(l.timeMs).append('\t');
for (int i = 0; i < l.words.size(); i++) {
Word w = l.words.get(i);
if (i > 0) sb.append(';');
sb.append(w.startMs - l.timeMs).append(',').append(w.durMs).append(',').append(escWord(w.text));
}
sb.append('\n');
}
return sb.length() == 0 ? null : sb.toString();
}

private static void attachWords(List<Line> lines, String data) {
java.util.Map<Long, Line> byTime = new java.util.HashMap<>();
for (Line l : lines) if (!byTime.containsKey(l.timeMs)) byTime.put(l.timeMs, l);
for (String row : data.split("\n")) {
int tab = row.indexOf('\t');
if (tab <= 0) continue;
Line target;
try { target = byTime.get(Long.parseLong(row.substring(0, tab).trim())); }
catch (Exception e) { continue; }
if (target == null) continue;
List<Word> ws = new ArrayList<>();
String body = row.substring(tab + 1);
List<String> segs = new ArrayList<>();
StringBuilder cur = new StringBuilder();
for (int i = 0; i < body.length(); i++) {
char c = body.charAt(i);
if (c == '\\' && i + 1 < body.length()) { cur.append(c).append(body.charAt(++i)); }
else if (c == ';') { segs.add(cur.toString()); cur.setLength(0); }
else cur.append(c);
}
if (cur.length() > 0) segs.add(cur.toString());
for (String seg : segs) {
int c1 = seg.indexOf(','), c2 = c1 < 0 ? -1 : seg.indexOf(',', c1 + 1);
if (c1 < 0 || c2 < 0) continue;
try {
long rel = Long.parseLong(seg.substring(0, c1).trim());
long dur = Long.parseLong(seg.substring(c1 + 1, c2).trim());
String text = unescWord(seg.substring(c2 + 1));
if (!text.isEmpty()) ws.add(new Word(target.timeMs + rel, dur, text));
} catch (Exception ignored) {}
}
if (ws.size() >= 2) target.words = ws;
}
}

/** 给网易云歌词包补逐字轴：v1 接口 yv=1 取 YRC，按行起始时间（±150ms）贴到包内歌词行 */
private static void attachNeteaseWords(LrcPack p, long nid) {
try {
JSONObject d = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric/v1?id=" + nid + "&cp=false&lv=0&kv=0&tv=0&rv=0&yv=1",
"https://music.163.com"));
JSONObject yo = d.optJSONObject("yrc");
if (yo == null) return;
List<YLine> yl = parseYrc(yo.optString("lyric"));
if (yl.isEmpty()) return;
List<Line> lines = parseLrc(p.lrc);
for (Line m : lines) {
YLine best = null; long bd = Long.MAX_VALUE;
for (YLine y : yl) { long dd = Math.abs(y.startMs - m.timeMs); if (dd < bd) { bd = dd; best = y; } }
if (best != null && bd <= 150) m.words = best.words;
}
p.words = serializeWords(lines);
} catch (Exception ignored) {}
}

private static String stamp(long ms) {
return String.format("[%02d:%02d.%03d]", ms / 60000, (ms % 60000) / 1000, ms % 1000);
}

private static String lrcTextOf(List<Line> lines) {
StringBuilder sb = new StringBuilder();
for (Line l : lines) {
if (l.text == null) continue;
sb.append(stamp(l.timeMs)).append(l.text).append("\n");
}
return sb.toString();
}

private static String extraTextOf(List<Line> lines, boolean isTrans) {
StringBuilder sb = new StringBuilder();
int n = 0;
for (Line l : lines) {
String v = isTrans ? l.trans : l.roma;
if (v == null || v.isEmpty()) continue;
sb.append(stamp(l.timeMs)).append(v).append("\n");
n++;
}
return n >= 3 ? sb.toString() : null;
}

/** 逐字补齐：结果没有逐字轴时，先去网易云取同曲 YRC，取不到再试酷狗 KRC（原生逐字、轻量解码）；
 *  两源共用同一套闸门：与主歌词的时间重合率 ≥0.5、整体偏移校正、单调归属、覆盖 ≥1/2，否则整体放弃（宁缺毋滥）。
 *  试过的歌 7 天内不重复试（.w3 标记），防一次网络抖动永久堵死。 */
private static Result maybeWords(Context ctx, Track track, Result r, File cache, File dir) {
try {
if (r == null || r.lines == null || r.lines.size() < 5) return r;
for (Line l : r.lines) if (l.words != null && !l.words.isEmpty()) return r;
File mark = new File(dir, track.bvid + ".w3");
if (mark.exists() && System.currentTimeMillis() - mark.lastModified() < 7L * 24 * 3600 * 1000) return r;
try { new java.io.FileOutputStream(mark).close(); } catch (Exception ignored) {}
Hints hints = hintsOf(track);
boolean done = false;
String wsrc = "";
// 1) 网易云 YRC
try {
long nid = neteaseBestId(track, hints);
if (nid > 0) {
JSONObject d = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric/v1?id=" + nid + "&cp=false&lv=0&kv=0&tv=0&rv=0&yv=1",
"https://music.163.com"));
JSONObject yo = d.optJSONObject("yrc");
JSONObject lo = d.optJSONObject("lrc");
String yrc = yo == null ? null : yo.optString("lyric");
String lrc = lo == null ? null : lo.optString("lyric");
if (yrc != null && lrc != null) {
List<YLine> yl = parseYrc(yrc);
if (!yl.isEmpty() && attachYWords(r, yl, lrc)) { done = true; wsrc = "网易云YRC"; }
}
}
} catch (Exception ignored) {}
// 2) 酷狗 KRC（原生逐字格式、轻量混淆非加密，网易云没逐字数据的歌常能在这补上）
if (!done) {
try {
if (tryKugouKrc(track, hints, r)) { done = true; wsrc = "酷狗KRC"; }
} catch (Exception ignored) {}
}
if (!done) return r;
StringBuilder sb = new StringBuilder("#src:" + r.source + "\n");
sb.append(lrcTextOf(r.lines));
String ts = extraTextOf(r.lines, true);
if (ts != null) sb.append("\n#trans:\n").append(ts);
String rs = extraTextOf(r.lines, false);
if (rs != null) sb.append("\n#roma:\n").append(rs);
String wd = serializeWords(r.lines);
if (wd != null) sb.append("\n#words:\n").append(wd);
writeFile(cache, sb.toString());
Diag.log(ctx, "🎤 已补齐逐字歌词（" + wsrc + "）《" + track.title + "》");
} catch (Exception ignored) {}
return r;
}

/** 把一份逐字轴（YLine 列表）重组进主歌词行：先与参考 LRC 做时间重合率校验（≥0.5），
 *  再估整体偏移、单调归属、覆盖率 ≥1/2 才保留，否则回滚。网易云 YRC 与酷狗 KRC 共用。 */
private static boolean attachYWords(Result r, List<YLine> yl, String refLrc) {
if (correlate(r.lines, refLrc) < 0.5) return false;
// 先估两边时间轴的整体偏移（各源时间戳常差几百毫秒，不校正会吞首字/串行）
List<Line> neLines = parseLrc(refLrc);
List<Long> deltas = new ArrayList<>();
for (Line e : neLines) for (Line m : r.lines) {
long dd = e.timeMs - m.timeMs;
if (Math.abs(dd) <= 1500) deltas.add(dd);
}
long off = 0;
if (deltas.size() >= 3) {
java.util.Map<Long, Integer> buckets = new java.util.HashMap<>();
for (long dd : deltas) {
long b = Math.round(dd / 200.0);
Integer c = buckets.get(b);
buckets.put(b, c == null ? 1 : c + 1);
}
long bestB = 0; int bestC = -1;
for (java.util.Map.Entry<Long, Integer> en : buckets.entrySet()) {
if (en.getValue() > bestC) { bestC = en.getValue(); bestB = en.getKey(); }
}
List<Long> sel = new ArrayList<>();
for (long dd : deltas) if (Math.round(dd / 200.0) == bestB) sel.add(dd);
java.util.Collections.sort(sel);
off = sel.get(sel.size() / 2);
}
// 单调归属：每个字归「校正后行戳 ≤ 字起＋250ms」的最后一行（一个字只归一行，不串行）
List<List<Word>> perLine = new ArrayList<>();
for (int i = 0; i < r.lines.size(); i++) perLine.add(new ArrayList<>());
int ptr = -1;
for (YLine y : yl) {
for (Word w : y.words) {
while (ptr + 1 < r.lines.size() && r.lines.get(ptr + 1).timeMs + off <= w.startMs + 250) ptr++;
if (ptr >= 0) perLine.get(ptr).add(w);
}
}
int total = 0, covered = 0;
for (int i = 0; i < r.lines.size(); i++) {
Line m = r.lines.get(i);
if (m.text == null || m.text.trim().isEmpty()) continue;
total++;
if (perLine.get(i).size() >= 2) { m.words = perLine.get(i); covered++; }
}
if (total == 0 || covered * 2 < total) {
for (Line m : r.lines) m.words = null;
return false;
}
return true;
}

private static final byte[] KRC_KEY = {
0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
0x51, 0x36, 0x31, 0x2D, (byte) 0xCE, (byte) 0xD2, 0x6E, 0x69 };

/** 酷狗 KRC 解码：Base64 → 去 krc1 头 → 逐字节 XOR 固定密钥 → zlib 解压（轻量混淆、非加密） */
private static String decodeKrc(String b64) {
try {
byte[] raw = Base64.decode(b64, Base64.DEFAULT);
if (raw.length < 5 || raw[0] != 'k' || raw[1] != 'r' || raw[2] != 'c' || raw[3] != '1')
return new String(raw, StandardCharsets.UTF_8);
byte[] dec = new byte[raw.length - 4];
for (int i = 0; i < dec.length; i++) dec[i] = (byte) (raw[i + 4] ^ KRC_KEY[i % 16]);
java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
new java.io.ByteArrayInputStream(dec));
java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
byte[] buf = new byte[8192];
int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
in.close();
return bos.toString("UTF-8");
} catch (Exception e) { return null; }
}

private static final Pattern KRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
private static final Pattern KRC_WORD = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");

/** 解析 KRC：唱词行 [行起,行长]<字偏移,字长,0>字…（偏移相对行起） */
private static List<YLine> parseKrc(String text) {
List<YLine> out = new ArrayList<>();
for (String row : text.split("\n")) {
Matcher m = KRC_LINE.matcher(row.trim());
if (!m.find()) continue;
long start;
try { start = Long.parseLong(m.group(1)); } catch (Exception e) { continue; }
Matcher wm = KRC_WORD.matcher(m.group(3));
List<Word> ws = new ArrayList<>();
while (wm.find()) {
try {
long rel = Long.parseLong(wm.group(1));
long dur = Long.parseLong(wm.group(2));
String t = wm.group(3);
if (!t.isEmpty()) ws.add(new Word(start + rel, dur, t));
} catch (Exception ignored) {}
}
if (ws.size() >= 2) {
YLine y = new YLine();
y.startMs = start;
y.words = ws;
out.add(y);
}
}
return out;
}

/** 逐字第二来源：酷狗 KRC。选歌规则与酷狗歌词一致（歌名分+歌手+时长差），取 fmt=krc 解码后走同一套对齐闸门 */
private static boolean tryKugouKrc(Track track, Hints hints, Result r) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
Cand best = null;
for (String q : hints.queries) {
JSONObject sr = new JSONObject(httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword="
+ URLEncoder.encode(q, "UTF-8"), null));
JSONArray cands = sr.optJSONArray("candidates");
if (cands == null) continue;
for (int i = 0; i < cands.length(); i++) {
JSONObject cj = cands.getJSONObject(i);
long id = cj.optLong("id");
if (id <= 0) continue;
int score = nameScore(cj.optString("song"), hints.nameCands);
long diff = wantDur > 0 ? Math.abs(cj.optLong("duration") - wantDur) : Long.MAX_VALUE / 2;
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
if (best == null || c.group < best.group || (c.group == best.group && c.diff < best.diff)) best = c;
}
if (best != null) break;
}
if (best == null) return false;
JSONObject dl = new JSONObject(httpGet(
"https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8&id=" + best.id
+ "&accesskey=" + best.ak, null));
String content = dl.optString("content");
if (content == null || content.isEmpty()) return false;
String text = decodeKrc(content);
if (text == null) return false;
List<YLine> yl = parseKrc(text);
if (yl.isEmpty()) return false;
StringBuilder ref = new StringBuilder();
for (YLine y : yl) {
StringBuilder lt = new StringBuilder();
for (Word w : y.words) lt.append(w.text);
ref.append(stamp(y.startMs)).append(lt).append("\n");
}
return attachYWords(r, yl, ref.toString());
}

/** 日/韩歌曲补齐译文/罗马音：主歌词缺译/罗马音时，去网易云 v1 接口按歌名歌手找同一首歌，
 *  过时间戳重合率校验才敢贴（防错配），补到后并回缓存、打标记不再重复尝试 */
private static Result maybeEnrich(Context ctx, Track track, Result r, File cache, File dir) {
try {
if (r == null || r.lines == null || r.lines.size() < 5) return r;
if (new File(dir, track.bvid + ".x2").exists()) return r;
int total = 0, nt = 0, nr = 0;
boolean foreign = false;
for (Line l : r.lines) {
if (l.text == null || l.text.trim().isEmpty()) continue;
total++;
if (isForeignText(l.text)) foreign = true;
if (l.trans != null && !l.trans.isEmpty()) nt++;
if (l.roma != null && !l.roma.isEmpty()) nr++;
}
if (!foreign || total == 0) return r;
boolean wantT = isShowTrans(ctx) && nt * 3 < total;
boolean wantR = isShowRoma(ctx) && nr * 3 < total;
if (!wantT && !wantR) return r;
long nid = neteaseBestId(track, hintsOf(track));
File mark = new File(dir, track.bvid + ".x2");
if (nid <= 0) { mark.createNewFile(); return r; }
JSONObject d = new JSONObject(httpGet(
"https://music.163.com/api/song/lyric/v1?id=" + nid + "&cp=false&lv=0&kv=0&tv=1&rv=1",
"https://music.163.com"));
JSONObject tly = d.optJSONObject("tlyric");
JSONObject rly = d.optJSONObject("romalrc");
String t = tly == null ? null : tly.optString("lyric");
String ro = rly == null ? null : rly.optString("lyric");
boolean added = false;
if (wantT && t != null && t.contains("[") && correlate(r.lines, t) >= 0.35) { align(r.lines, t, true); added = true; }
if (wantR && ro != null && ro.contains("[") && asciiRatio(ro) >= 0.75 && correlate(r.lines, ro) >= 0.5) { align(r.lines, ro, false); added = true; }
mark.createNewFile();
if (added) {
StringBuilder sb = new StringBuilder("#src:" + r.source + "\n");
sb.append(lrcTextOf(r.lines));
String ts = extraTextOf(r.lines, true);
if (ts != null) sb.append("\n#trans:\n").append(ts);
String rs = extraTextOf(r.lines, false);
if (rs != null) sb.append("\n#roma:\n").append(rs);
writeFile(cache, sb.toString());
Diag.log(ctx, "🎤 已补齐译文/罗马音（网易云）《" + track.title + "》");
}
} catch (Exception ignored) {}
return r;
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

private static LrcPack neteaseLyrics(Track track, Hints hints, int alt) throws Exception {
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
if (text != null && text.contains("[")) {
LrcPack p = new LrcPack(text);
JSONObject tly = lr.optJSONObject("tlyric");
if (tly != null) {
String tt = tly.optString("lyric");
if (tt != null && tt.contains("[")) p.trans = tt;
}
attachNeteaseWords(p, c.id);
return p;
}
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


// ---------------- 酷我 ----------------
// 搜索走 search.kuwo.cn 经典 r.s 接口（免令牌）；歌词走 www.kuwo.cn openapi（lrclist 时间+文本）；
// 封面用搜索自带的 web_albumpic_short，把尺寸段 120 换成 500 取大图。老歌/冷门歌库存是它的强项。
private static JSONArray kuwoSearch(String q, int rn) throws Exception {
String url = "https://search.kuwo.cn/r.s?client=kt&all=" + URLEncoder.encode(q, "UTF-8")
+ "&pn=0&rn=" + rn + "&uid=221260053&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1"
+ "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1";
JSONObject r = new JSONObject(httpGet(url, "https://www.kuwo.cn/"));
JSONArray out = new JSONArray();
JSONArray abs = r.optJSONArray("abslist");
if (abs == null) return out;
for (int i = 0; i < abs.length(); i++) {
JSONObject sj = abs.getJSONObject(i);
out.put(sj);
JSONArray sub = sj.optJSONArray("SUBLIST");
if (sub != null) for (int j = 0; j < sub.length(); j++) out.put(sub.getJSONObject(j));
}
return out;
}

private static String kuwoUnescape(String s) {
if (s == null) return "";
return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
.replace("&lt;", "<").replace("&gt;", ">");
}

private static String kuwoName(JSONObject sj) {
String n = kuwoUnescape(sj.optString("SONGNAME"));
return n.isEmpty() ? kuwoUnescape(sj.optString("NAME")) : n;
}

private static long kuwoRid(JSONObject sj) {
String rid = sj.optString("MUSICRID").replace("MUSIC_", "").trim();
try { return Long.parseLong(rid); } catch (Exception e) { return sj.optLong("DC_TARGETID"); }
}

private static long kuwoDurMs(JSONObject sj) {
try { return Long.parseLong(sj.optString("DURATION").trim()) * 1000L; } catch (Exception e) { return 0; }
}

private static LrcPack kuwoLyrics(Track track, Hints hints, int alt) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
Map<Long, Cand> byId = new HashMap<>();
for (String q : hints.queries) {
if (!byId.isEmpty()) break; // 首个有候选的查询就够了（同酷狗策略，省请求）
JSONArray list = kuwoSearch(q, 20);
for (int i = 0; i < list.length(); i++) {
JSONObject sj = list.getJSONObject(i);
long rid = kuwoRid(sj);
if (rid <= 0 || byId.containsKey(rid)) continue;
int score = nameScore(kuwoName(sj), hints.nameCands);
long diff = wantDur > 0 ? Math.abs(kuwoDurMs(sj) - wantDur) : Long.MAX_VALUE / 2;
if (wantDur > 0 && diff > 12000) continue;
String singer = kuwoUnescape(sj.optString("ARTIST"));
boolean artistHit = singer.length() >= 2 && track.title != null
&& (track.title.contains(singer) || singer.equals(hints.artist));
int group;
if (score >= 80) group = 0;
else if (score == 50 && artistHit) group = 1;
else if (score == 0 && artistHit && diff <= 5000) group = 2;
else continue;
byId.put(rid, new Cand(rid, group, diff));
}
}
List<Cand> pool = new ArrayList<>(byId.values());
Collections.sort(pool, (a, b) -> a.group != b.group ? Integer.compare(a.group, b.group)
: Long.compare(a.diff, b.diff));
if (pool.isEmpty()) return null;
for (int k = 0; k < pool.size(); k++) {
Cand c = pool.get(Math.floorMod(alt + k, pool.size()));
try {
LrcPack p = kuwoFetchLrc(c.id);
if (p != null) return p;
} catch (Exception ignored) {}
}
return null;
}

private static boolean hasKana(String s) {
for (int i = 0; i < s.length(); i++) {
char c = s.charAt(i);
if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0xFF66 && c <= 0xFF9D)) return true;
}
return false;
}

private static LrcPack kuwoFetchLrc(long rid) throws Exception {
JSONObject r = new JSONObject(httpGet(
"https://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=" + rid,
"https://www.kuwo.cn/"));
JSONObject data = r.optJSONObject("data");
JSONArray list = data == null ? null : data.optJSONArray("lrclist");
if (list == null) return null;
List<Long> times = new ArrayList<>();
List<String> texts = new ArrayList<>();
for (int i = 0; i < list.length(); i++) {
JSONObject lj = list.getJSONObject(i);
String text = kuwoUnescape(lj.optString("lineLyric")).trim();
if (text.isEmpty()) continue;
double sec;
try { sec = Double.parseDouble(lj.optString("time")); } catch (Exception e) { continue; }
times.add(Math.round(sec * 1000));
texts.add(text);
}
if (texts.size() < 5) return null;
// 酷我日语歌把译文行挂在原词行结束处（与下一句原词同一个时间戳），原词/译文交错成双行，
// 播放页和歌词页都会挤成一团、罗马音还会整体错位一行。识别到这种结构就把译文折回
// 它前面最近的原词行当翻译，恢复一句一行。特征：译文行时间戳与某句原词行精确重合。
int kana = 0;
java.util.Set<Long> kanaTimes = new java.util.HashSet<>();
for (int i = 0; i < texts.size(); i++) if (hasKana(texts.get(i))) { kana++; kanaTimes.add(times.get(i)); }
if (kana >= 5) {
StringBuilder main = new StringBuilder(), trans = new StringBuilder();
long lastKanaMs = -1;
int folded = 0;
for (int i = 0; i < texts.size(); i++) {
String t = texts.get(i);
long ms = times.get(i);
if (hasKana(t)) {
main.append(stamp(ms)).append(t).append("\n");
lastKanaMs = ms;
} else if (lastKanaMs >= 0 && (ms == lastKanaMs || kanaTimes.contains(ms))) {
trans.append(stamp(lastKanaMs)).append(t).append("\n");
folded++;
} else {
main.append(stamp(ms)).append(t).append("\n");
}
}
if (folded >= 3) {
LrcPack p = new LrcPack(main.toString());
p.trans = trans.toString();
return p;
}
}
StringBuilder sb = new StringBuilder();
for (int i = 0; i < texts.size(); i++) sb.append(stamp(times.get(i))).append(texts.get(i)).append("\n");
return new LrcPack(sb.toString());
}

private static void collectKuwoCovers(Track track, Hints hints, java.util.List<String> cands,
java.util.Map<String, CoverCand> byUrl) throws Exception {
long wantDur = track.durationSec > 0 ? track.durationSec * 1000L : -1;
int used = 0;
for (String q : hints.queries) {
if (used++ >= 3) break;
JSONArray list = kuwoSearch(q, 10);
for (int i = 0; i < list.length(); i++) {
JSONObject sj = list.getJSONObject(i);
int score = nameScore(kuwoName(sj), cands);
if (score < 80) continue;
String shortPic = sj.optString("web_albumpic_short");
if (shortPic.isEmpty() || shortPic.indexOf('/') < 0) continue;
long diff = wantDur > 0 ? Math.abs(kuwoDurMs(sj) - wantDur) : Long.MAX_VALUE / 2;
String singer = kuwoUnescape(sj.optString("ARTIST"));
boolean artistHit = singer.length() >= 2 && ((track.title != null && track.title.contains(singer))
|| (!hints.artist.isEmpty() && (hints.artist.contains(singer) || singer.contains(hints.artist))));
String url = "https://img1.kuwo.cn/star/albumcover/500/"
+ shortPic.substring(shortPic.indexOf('/') + 1);
putCover(byUrl, new CoverCand(url, "酷我", score, artistHit, diff));
}
}
}

// ---------------- 歌词源顺序配置 ----------------
public static final String[] SRC_KEYS = {"qq", "netease", "kugou", "kuwo", "amll", "lrclib"};
public static final String[] SRC_NAMES = {"QQ音乐", "网易云音乐", "酷狗音乐", "酷我音乐", "AMLL TTML", "LRCLIB"};
private static final String DEFAULT_ORDER = "qq,netease,kugou,kuwo,amll,lrclib";

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
if (key.equals("kuwo")) return "酷我歌词";
return "网易云歌词";
}

private static String srcShort(String key) {
if (key.equals("qq")) return "QQ音乐";
if (key.equals("amll")) return "AMLL TTML";
if (key.equals("lrclib")) return "LRCLIB";
if (key.equals("kugou")) return "酷狗";
if (key.equals("kuwo")) return "酷我";
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

private static String b64Text(String b64) {
if (b64 == null || b64.length() == 0) return null;
try {
String t = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
if (!t.contains("[")) return null;
int cnt = 0;
for (int i = 0; i + 1 < t.length(); i++) if (t.charAt(i) == '\n' && t.charAt(i + 1) == '[') cnt++;
if (cnt < 3 && !t.startsWith("[")) return null;
return t;
} catch (Exception e) { return null; }
}
private static String httpPostJson(String url, String json) {
java.net.HttpURLConnection c = null;
try {
c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
c.setConnectTimeout(6000); c.setReadTimeout(8000);
c.setRequestMethod("POST"); c.setDoOutput(true);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
c.setRequestProperty("Referer", "https://y.qq.com/");
c.setRequestProperty("Content-Type", "application/json");
byte[] payload = json.getBytes(StandardCharsets.UTF_8);
c.setRequestProperty("Content-Length", String.valueOf(payload.length));
java.io.OutputStream os = c.getOutputStream(); os.write(payload); os.close();
if (c.getResponseCode() != 200) return null;
java.io.InputStream in = c.getInputStream();
java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
byte[] buf = new byte[8192]; int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
in.close();
return new String(bos.toByteArray(), StandardCharsets.UTF_8);
} catch (Exception e) { return null; }
finally { if (c != null) c.disconnect(); }
}
private static LrcPack qqPackMusicu(String mid) {
try {
JSONObject param = new JSONObject();
param.put("songMID", mid); param.put("songID", 0); param.put("index", 0);
param.put("crypt", 0); param.put("lrc_t", 0); param.put("qrc", 0); param.put("qrc_t", 0);
param.put("roma", 1); param.put("roma_t", 0); param.put("trans", 1); param.put("trans_t", 0); param.put("type", 1);
JSONObject reqO = new JSONObject();
reqO.put("module", "music.musichallSong.PlayLyricInfo"); reqO.put("method", "GetPlayLyricInfo"); reqO.put("param", param);
JSONObject comm = new JSONObject();
comm.put("g_tk", 5381); comm.put("uin", 0); comm.put("format", "json"); comm.put("ct", 20); comm.put("cv", 0);
JSONObject root = new JSONObject(); root.put("comm", comm); root.put("req", reqO);
String body = httpPostJson("https://u.y.qq.com/cgi-bin/musicu.fcg", root.toString());
if (body == null) return null;
JSONObject data = new JSONObject(body).optJSONObject("req");
if (data == null) return null;
data = data.optJSONObject("data");
if (data == null) return null;
String lyric = b64Text(data.optString("lyric", ""));
if (lyric == null) return null;
LrcPack p = new LrcPack(lyric);
String trans = b64Text(data.optString("trans", ""));
if (trans != null) p.trans = trans;
return p;
} catch (Exception e) { return null; }
}
private static LrcPack qqLyrics(Track track, Hints hints, int alt) throws Exception {
List<QCand> pool = qqCandidates(track, hints);
if (pool.isEmpty()) return null;
for (int k = 0; k < pool.size(); k++) {
QCand c = pool.get(Math.floorMod(alt + k, pool.size()));
LrcPack mp = null;
try { mp = qqPackMusicu(c.mid); } catch (Exception ignored) {}
if (mp != null) return mp;
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
if (text != null && text.contains("[")) {
LrcPack p = new LrcPack(text);
String tr = r.optString("trans");
if (tr != null && !tr.contains("[")) {
try {
String dec = new String(Base64.decode(tr, Base64.DEFAULT), StandardCharsets.UTF_8);
if (dec.contains("[")) tr = dec;
} catch (Exception ignored) {}
}
if (tr != null && tr.contains("[")) p.trans = tr;
return p;
}
} catch (Exception ignored) {}
}
return null;
}

// ---------------- AMLL TTML（按平台歌曲 ID 查 amll-ttml-db） ----------------
private static LrcPack amllLyrics(Track track, Hints hints, int alt) throws Exception {
try {
List<QCand> pool = qqCandidates(track, hints);
int n = Math.min(pool.size(), 3);
for (int k = 0; k < n; k++) {
QCand c = pool.get(Math.floorMod(alt + k, pool.size()));
LrcPack p = amllFetch("qq-lyrics/" + c.mid + ".ttml");
if (p != null) return p;
}
} catch (Exception ignored) {}
try {
long id = neteaseBestId(track, hints);
if (id > 0) {
LrcPack p = amllFetch("ncm-lyrics/" + id + ".ttml");
if (p != null) return p;
}
} catch (Exception ignored) {}
return null;
}

private static LrcPack amllFetch(String path) {
String[] bases = {
"https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/",
"https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/"
};
for (String b : bases) {
try {
String ttml = httpGet(b + path, null);
if (ttml != null && ttml.contains("<tt")) {
String lrc = ttmlToLrc(ttml);
if (lrc != null) {
LrcPack p = new LrcPack(lrc);
p.words = ttmlWordsData(ttml, lrc);
return p;
}
}
} catch (Exception ignored) {}
}
return null;
}

private static final Pattern TTML_WORD = Pattern.compile("<span\\b([^>]*)>([^<]*)</span>");
private static final Pattern TTML_ATTR_T = Pattern.compile("(begin|end)=\"([^\"]+)\"");

/** 从 TTML 提取逐字跨度：每行 <p> 里带 begin/end 的普通 span（排除翻译/罗马音/背景声部），按行时间贴到 LRC 行后序列化 */
private static String ttmlWordsData(String ttml, String lrcText) {
try {
Matcher m = TTML_P.matcher(ttml);
java.util.Map<Long, List<Word>> byBegin = new java.util.HashMap<>();
while (m.find()) {
long pBegin = ttmlTime(m.group(1));
if (pBegin < 0) continue;
String inner = TTML_TRANS.matcher(m.group(2)).replaceAll("");
Matcher sm = TTML_WORD.matcher(inner);
List<Word> ws = new ArrayList<>();
while (sm.find()) {
String attrs = sm.group(1);
if (attrs.contains("x-bg")) continue;
long wb = -1, we = -1;
Matcher am = TTML_ATTR_T.matcher(attrs);
while (am.find()) {
if (am.group(1).equals("begin")) wb = ttmlTime(am.group(2));
else we = ttmlTime(am.group(2));
}
if (wb < 0 || we <= wb) continue;
String text = sm.group(2).replace("&lt;", "<").replace("&gt;", ">")
.replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
if (!text.isEmpty()) ws.add(new Word(wb, we - wb, text));
}
if (ws.size() >= 2) byBegin.put(pBegin, ws);
}
if (byBegin.isEmpty()) return null;
List<Line> lines = parseLrc(lrcText);
for (Line ln : lines) {
List<Word> ws = byBegin.get(ln.timeMs);
if (ws == null) {
long bd = Long.MAX_VALUE;
for (java.util.Map.Entry<Long, List<Word>> e : byBegin.entrySet()) {
long dd = Math.abs(e.getKey() - ln.timeMs);
if (dd < bd) { bd = dd; ws = e.getValue(); }
}
if (bd > 150) ws = null;
}
if (ws != null) ln.words = ws;
}
return serializeWords(lines);
} catch (Exception e) { return null; }
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

/** 歌词缓存上限：目录文件超 1000 个（约 500 首）时删最旧的，防越用越大 */
private static void trimLyricsDir(File anyInDir) {
try {
File dir = anyInDir == null ? null : anyInDir.getParentFile();
if (dir == null) return;
File[] fs = dir.listFiles();
if (fs == null || fs.length <= 1000) return;
java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
int excess = fs.length - 1000;
for (int i = 0; i < excess; i++) fs[i].delete();
} catch (Exception ignored) {}
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
