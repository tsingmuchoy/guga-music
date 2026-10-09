package com.guga.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 跨平台歌单导入引擎：识别分享链接 → 解析曲目 → 逐首匹配 B 站。
 *  平台：netease 网易云 / qq QQ音乐 / kugou 酷狗 / kuwo 酷我 / qishui 汽水音乐。
 *  匹配口径：宁可不配、不可错配（歌名对版是硬门槛，歌手/时长加权，过线才收）。 */
public class PlaylistImport {

public static class Src {
public String name, artist;
public int durSec;
public Src(String n, String a, int d) { name = n; artist = a; durSec = d; }
}

public static class Parsed {
public String platform, title;
public List<Src> tracks = new ArrayList<>();
public int skipped;
}

public static class Detected {
public String platform, id, url, extra;
}

public static final String[][] PLATFORMS = {
{"netease", "网易云音乐"}, {"qq", "QQ音乐"}, {"kugou", "酷狗音乐"}, {"kuwo", "酷我音乐"}, {"bodian", "波点音乐"}, {"qishui", "汽水音乐"}};

public static String platformName(String p) {
for (String[] x : PLATFORMS) if (x[0].equals(p)) return x[1];
return p;
}

// ---------------- 链接识别 ----------------

private static String find(String text, String regex) {
Matcher m = Pattern.compile(regex).matcher(text);
return m.find() ? m.group(1) : null;
}

public static Detected detect(String raw) {
if (raw == null) return null;
String text = raw.trim();
String url = find(text, "(https?://[^\\s，。；、）】]+)");
String probe = url != null ? url : text;
Detected d = new Detected();
d.url = url;
String id;
if (probe.contains("bodian")) {
id = find(probe, "[?&]playlistId=(\\d+)");
if (id != null) {
d.platform = "bodian"; d.id = id;
String src = find(probe, "[?&]source=(\\d+)");
d.extra = src == null ? "5" : src;
return d;
}
}
if (probe.contains("163.com")) {
id = find(probe, "[?#&]id=(\\d+)");
if (id != null) { d.platform = "netease"; d.id = id; return d; }
}
if (probe.contains("qq.com")) {
id = find(probe, "/playlist/(\\d+)");
if (id == null) id = find(probe, "taoge\\.html[^\\s]*?[?&]id=(\\d+)");
if (id == null) id = find(probe, "/playsquare/(\\d+)");
if (id == null) id = find(probe, "[?&]id=(\\d{6,})");
if (id != null) { d.platform = "qq"; d.id = id; return d; }
}
if (probe.contains("kugou.com")) {
id = find(probe, "special/single/(\\d+)");
if (id == null) id = find(probe, "songlist/(\\d+)");
if (id == null) id = find(probe, "[?&]listid=(\\d+)");
if (id != null) { d.platform = "kugou"; d.id = id; return d; }
if (find(probe, "global_collection_id=([A-Za-z0-9_\\-]+)") != null) {
d.platform = "kugou"; d.id = null; return d; // 靠短链跳转后再认
}
}
if (probe.contains("kuwo.cn")) {
id = find(probe, "playlist_detail/(\\d+)");
if (id == null) id = find(probe, "[?&]pid=(\\d+)");
if (id != null) { d.platform = "kuwo"; d.id = id; return d; }
}
if (probe.contains("qishui.douyin.com") || probe.contains("ssmusic.com")) {
d.platform = "qishui";
d.id = find(probe, "/s/([A-Za-z0-9]+)");
if (d.id == null) d.id = find(probe, "playlist/([A-Za-z0-9]+)");
if (d.url == null) d.url = probe;
return d;
}
if (url == null && text.matches("\\d{5,}")) { d.platform = null; d.id = text; return d; }
return null;
}

/** 跟随重定向拿最终 URL（短链解析用） */
public static String resolveFinalUrl(String url) {
HttpURLConnection c = null;
try {
c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000); c.setReadTimeout(8000);
c.setInstanceFollowRedirects(true);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
c.getResponseCode();
URL u = c.getURL();
return u == null ? url : u.toString();
} catch (Exception e) { return url; }
finally { if (c != null) c.disconnect(); }
}

// ---------------- HTTP ----------------

static String httpGet(String url, String referer) throws Exception {
HttpURLConnection c = null;
try {
c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000); c.setReadTimeout(12000);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
if (referer != null) c.setRequestProperty("Referer", referer);
if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
InputStream in = c.getInputStream();
ByteArrayOutputStream bos = new ByteArrayOutputStream();
byte[] buf = new byte[8192]; int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
in.close();
return new String(bos.toByteArray(), "UTF-8");
} finally { if (c != null) c.disconnect(); }
}

static String httpPostJson(String url, String json, String referer) throws Exception {
HttpURLConnection c = null;
try {
c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000); c.setReadTimeout(12000);
c.setRequestMethod("POST"); c.setDoOutput(true);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
if (referer != null) c.setRequestProperty("Referer", referer);
c.setRequestProperty("Content-Type", "application/json");
byte[] payload = json.getBytes("UTF-8");
c.setRequestProperty("Content-Length", String.valueOf(payload.length));
OutputStream os = c.getOutputStream(); os.write(payload); os.close();
if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
InputStream in = c.getInputStream();
ByteArrayOutputStream bos = new ByteArrayOutputStream();
byte[] buf = new byte[8192]; int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
in.close();
return new String(bos.toByteArray(), "UTF-8");
} finally { if (c != null) c.disconnect(); }
}

static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

private static String joinArtists(JSONArray arr, String key) {
if (arr == null) return "";
StringBuilder sb = new StringBuilder();
for (int i = 0; i < arr.length(); i++) {
JSONObject a = arr.optJSONObject(i);
if (a == null) continue;
String n = key == null ? a.optString("name") : a.optString(key);
if (n != null && !n.isEmpty()) { if (sb.length() > 0) sb.append("/"); sb.append(n); }
}
return sb.toString();
}

// ---------------- 各平台解析 ----------------

public static Parsed fetch(Detected d) throws Exception {
if ("netease".equals(d.platform)) return fetchNetease(d.id);
if ("qq".equals(d.platform)) return fetchQq(d.id);
if ("kugou".equals(d.platform)) return fetchKugou(d.id);
if ("kuwo".equals(d.platform)) return fetchKuwo(d.id);
if ("bodian".equals(d.platform)) return fetchBodian(d.id, d.extra);
if ("qishui".equals(d.platform)) return fetchQishui(d.url);
throw new Exception("暂不支持这个平台");
}

private static Parsed fetchNetease(String id) throws Exception {
JSONObject root = new JSONObject(httpGet(
"https://music.163.com/api/v3/playlist/detail?id=" + id, "https://music.163.com/"));
JSONObject pl = root.optJSONObject("playlist");
if (pl == null) throw new Exception("歌单不存在或不可见");
Parsed out = new Parsed();
out.platform = "netease";
out.title = pl.optString("name", "网易云歌单");
JSONArray tids = pl.optJSONArray("trackIds");
if (tids == null || tids.length() == 0) throw new Exception("歌单是空的");
List<Long> ids = new ArrayList<>();
for (int i = 0; i < tids.length() && ids.size() < 500; i++) {
JSONObject t = tids.optJSONObject(i);
if (t != null && t.optLong("id", 0) > 0) ids.add(t.optLong("id"));
}
Map<Long, Src> byId = new HashMap<>();
for (int base = 0; base < ids.size(); base += 100) {
int end = Math.min(base + 100, ids.size());
StringBuilder c = new StringBuilder("[");
for (int i = base; i < end; i++) { if (i > base) c.append(","); c.append("{\"id\":").append(ids.get(i)).append("}"); }
c.append("]");
JSONObject det = new JSONObject(httpGet(
"https://music.163.com/api/v3/song/detail?c=" + enc(c.toString()), "https://music.163.com/"));
JSONArray songs = det.optJSONArray("songs");
if (songs == null) continue;
for (int i = 0; i < songs.length(); i++) {
JSONObject s = songs.optJSONObject(i);
if (s == null) continue;
String name = s.optString("name", "");
if (name == null || name.isEmpty()) continue;
byId.put(s.optLong("id"), new Src(name, joinArtists(s.optJSONArray("ar"), null),
(int) (s.optLong("dt", 0) / 1000)));
}
}
for (Long songId : ids) {
Src s = byId.get(songId);
if (s == null) out.skipped++; else out.tracks.add(s);
}
if (out.tracks.isEmpty()) throw new Exception("没能解析出歌曲（歌单可能已私有）");
return out;
}

private static Parsed fetchQq(String id) throws Exception {
Parsed out = new Parsed();
out.platform = "qq";
try {
long dissid = Long.parseLong(id);
int begin = 0;
while (out.tracks.size() < 500) {
JSONObject param = new JSONObject();
param.put("disstid", dissid); param.put("onlysonglist", 0);
param.put("song_begin", begin); param.put("song_num", 500);
param.put("userinfo", 1); param.put("pic_dpi", 800);
JSONObject reqO = new JSONObject();
reqO.put("module", "music.srfDissInfo.DissInfo"); reqO.put("method", "CgiGetDiss"); reqO.put("param", param);
JSONObject comm = new JSONObject();
comm.put("g_tk", 5381); comm.put("uin", 0); comm.put("format", "json"); comm.put("ct", 20); comm.put("cv", 0);
JSONObject root = new JSONObject(); root.put("comm", comm); root.put("req", reqO);
JSONObject resp = new JSONObject(httpPostJson("https://u.y.qq.com/cgi-bin/musicu.fcg", root.toString(), "https://y.qq.com/"));
JSONObject data = resp.optJSONObject("req");
data = data == null ? null : data.optJSONObject("data");
if (data == null) break;
if (out.title == null) {
JSONObject di = data.optJSONObject("dirinfo");
out.title = di == null ? "QQ歌单" : di.optString("title", "QQ歌单");
}
JSONArray sl = data.optJSONArray("songlist");
if (sl == null || sl.length() == 0) break;
for (int i = 0; i < sl.length(); i++) {
JSONObject s = sl.optJSONObject(i);
if (s == null) continue;
String name = s.optString("name", "");
if (name.isEmpty()) { out.skipped++; continue; }
out.tracks.add(new Src(name, joinArtists(s.optJSONArray("singer"), null), s.optInt("interval", 0)));
}
begin += sl.length();
if (sl.length() < 500) break;
}
} catch (NumberFormatException ignored) {}
if (out.tracks.isEmpty()) {
// 兜底：旧歌单接口
JSONObject resp = new JSONObject(httpGet(
"https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&disstid=" + id + "&format=json&g_tk=5381",
"https://y.qq.com/"));
JSONArray cd = resp.optJSONArray("cdlist");
if (cd == null || cd.length() == 0) throw new Exception("歌单不存在或不可见");
JSONObject first = cd.optJSONObject(0);
out.title = first.optString("dissname", "QQ歌单");
JSONArray sl = first.optJSONArray("songlist");
if (sl != null) for (int i = 0; i < sl.length() && out.tracks.size() < 500; i++) {
JSONObject s = sl.optJSONObject(i);
if (s == null) continue;
String name = s.optString("songname", "");
if (name.isEmpty()) { out.skipped++; continue; }
out.tracks.add(new Src(name, joinArtists(s.optJSONArray("singer"), null), s.optInt("interval", 0)));
}
}
if (out.tracks.isEmpty()) throw new Exception("没能解析出歌曲（歌单可能已私有）");
if (out.title == null) out.title = "QQ歌单";
return out;
}

private static Parsed fetchKugou(String id) throws Exception {
if (id == null) throw new Exception("酷狗概念歌单链接没能解析出歌单ID，请在酷狗里复制「歌单链接」再试");
Parsed out = new Parsed();
out.platform = "kugou";
JSONObject info = new JSONObject(httpGet(
"http://mobilecdn.kugou.com/api/v3/special/info?specialid=" + id, "http://www.kugou.com/"));
JSONObject idata = info.optJSONObject("data");
out.title = idata == null? "酷狗歌单": idata.optString("specialname", "酷狗歌单");
int total = idata == null? 0: idata.optInt("songcount", 0);
int page = 1;
while (out.tracks.size() < 500) {
JSONObject r = new JSONObject(httpGet(
"http://mobilecdn.kugou.com/api/v3/special/song?specialid=" + id + "&page=" + page + "&pagesize=100&version=9108",
"http://www.kugou.com/"));
JSONObject data = r.optJSONObject("data");
JSONArray arr = data == null? null: data.optJSONArray("info");
if (arr == null || arr.length() == 0) break;
for (int i = 0; i < arr.length(); i++) {
JSONObject s = arr.optJSONObject(i);
if (s == null) continue;
String fn = s.optString("filename", "");
String name = fn, artist = "";
int sep = fn.indexOf(" - ");
if (sep > 0) { artist = fn.substring(0, sep).trim(); name = fn.substring(sep + 3).trim();}
if (name.isEmpty()) { out.skipped++; continue;}
out.tracks.add(new Src(name, artist, s.optInt("duration", 0)));
}
if (total > 0 && out.tracks.size() >= total) break;
if (arr.length() < 100) break;
page++;
}
if (out.tracks.isEmpty()) throw new Exception("没能解析出歌曲（歌单可能已私有）");
return out;
}

private static Parsed fetchKuwo(String id) throws Exception {
Parsed out = new Parsed();
out.platform = "kuwo";
out.title = "酷我歌单";
int pn = 0;
while (out.tracks.size() < 500) {
JSONObject r = new JSONObject(httpGet(
"http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + id + "&pn=" + pn + "&rn=100&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=1&newver=1",
"http://www.kuwo.cn/"));
if (pn == 0) {
String t = r.optString("title", "");
if (!t.isEmpty()) out.title = t;
}
JSONArray ml = r.optJSONArray("musiclist");
if (ml == null || ml.length() == 0) break;
for (int i = 0; i < ml.length(); i++) {
JSONObject s = ml.optJSONObject(i);
if (s == null) continue;
String name = s.optString("name", "");
if (name.isEmpty()) { out.skipped++; continue;}
out.tracks.add(new Src(name, s.optString("artist", ""), s.optInt("duration", 0)));
}
int total = r.optInt("total", 0);
if (total > 0 && out.tracks.size() >= total) break;
if (ml.length() < 100) break;
pn++;
}
if (out.tracks.isEmpty()) throw new Exception("没能解析出歌曲（歌单可能已删除）");
return out;
}

/** 波点音乐接口专用 GET：bd-api 必须带 plat:h5 头，否则回 402 */
static String httpGetBd(String url) throws Exception {
HttpURLConnection c = null;
try {
c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000); c.setReadTimeout(12000);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
c.setRequestProperty("plat", "h5");
if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
InputStream in = c.getInputStream();
ByteArrayOutputStream bos = new ByteArrayOutputStream();
byte[] buf = new byte[8192]; int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
in.close();
return new String(bos.toByteArray(), "UTF-8");
} finally { if (c != null) c.disconnect(); }
}

/** 波点音乐：分享页同款接口（bd-api.kuwo.cn），info 拿歌单名、musicList 分页拿曲目；
 *  该接口分页有重复页怪癖，按歌曲 id 去重收集，集满 total 为止 */
private static Parsed fetchBodian(String id, String source) throws Exception {
if (source == null || source.isEmpty()) source = "5";
Parsed out = new Parsed();
out.platform = "bodian";
out.title = "波点歌单";
String rid = Long.toHexString(System.nanoTime());
try {
JSONObject info = new JSONObject(httpGetBd(
"https://bd-api.kuwo.cn/api/service/playlist/info/" + id + "?source=" + source + "&reqId=" + rid));
if (info.optInt("code") == 200) {
JSONObject data = info.optJSONObject("data");
if (data != null) {
String t = data.optString("name", "");
if (!t.isEmpty()) out.title = t;
}
}
} catch (Exception ignored) {}
Set<Long> seen = new HashSet<>();
int total = -1;
for (int pn = 0; pn < 12 && out.tracks.size() < 500; pn++) {
JSONObject r = new JSONObject(httpGetBd(
"https://bd-api.kuwo.cn/api/service/playlist/" + id + "/musicList?source=" + source
+ "&pn=" + pn + "&rn=100&reqId=" + rid + pn));
if (r.optInt("code") != 200) {
if (pn == 0) throw new Exception("波点接口拒绝了请求（歌单可能已设为私密）");
break;
}
JSONObject data = r.optJSONObject("data");
if (data == null) break;
if (total < 0) total = data.optInt("total", 0);
JSONArray list = data.optJSONArray("list");
if (list == null || list.length() == 0) break;
int added = 0;
for (int i = 0; i < list.length(); i++) {
JSONObject s = list.optJSONObject(i);
if (s == null) continue;
long mid = s.optLong("id", 0);
if (mid > 0 && !seen.add(mid)) continue;
String name = s.optString("name", "");
if (name.isEmpty()) { out.skipped++; continue; }
String artist = s.optString("artist", "");
if (artist.isEmpty()) artist = joinArtists(s.optJSONArray("artists"), null);
out.tracks.add(new Src(name, artist, s.optInt("duration", 0)));
added++;
}
if (added == 0) break;
if (total > 0 && out.tracks.size() >= total) break;
}
if (out.tracks.isEmpty()) throw new Exception("没能解析出歌曲（歌单可能已设为私密）");
return out;
}

/** 汽水音乐：分享页 HTML 里的 _ROUTER_DATA JSON 递归找曲目（name + artists 数组的对象） */
private static Parsed fetchQishui(String url) throws Exception {
if (url == null || url.isEmpty()) throw new Exception("汽水分享链接无效");
HttpURLConnection c = null;
String html;
try {
c = (HttpURLConnection) new URL(url).openConnection();
c.setConnectTimeout(8000); c.setReadTimeout(12000);
c.setInstanceFollowRedirects(true);
c.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1");
InputStream in = c.getInputStream();
ByteArrayOutputStream bos = new ByteArrayOutputStream();
byte[] buf = new byte[16384]; int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
in.close();
html = new String(bos.toByteArray(), "UTF-8");
} finally { if (c!= null) c.disconnect();}
int marker = html.indexOf("_ROUTER_DATA");
if (marker < 0) throw new Exception("汽水页面结构已变化，暂时解析不了这个链接");
int brace = html.indexOf('{', marker);
if (brace < 0) throw new Exception("汽水页面数据不完整");
int depth = 0, end = -1;
for (int i = brace; i < html.length(); i++) {
char ch = html.charAt(i);
if (ch == '{') depth++;
else if (ch == '}') { depth--; if (depth == 0) { end = i; break;}}
}
if (end < 0) throw new Exception("汽水页面数据不完整");
JSONObject root = new JSONObject(html.substring(brace, end + 1));
Parsed out = new Parsed();
out.platform = "qishui";
out.title = "汽水音乐歌单";
Matcher tm = Pattern.compile("<title>([^<]+)</title>").matcher(html);
if (tm.find()) {
String t = tm.group(1).replaceAll(" - 汽水音乐.*$", "").replaceAll("｜汽水音乐.*$", "").trim();
if (!t.isEmpty() && t.length() <= 60) out.title = t;
}
Set<String> seen = new HashSet<>();
collectTracks(root, out, seen);
if (out.tracks.isEmpty()) throw new Exception("这个汽水链接里没找到歌单曲目（可能只是单曲分享）");
return out;
}

private static void collectTracks(Object node, Parsed out, Set<String> seen) {
if (out.tracks.size() >= 500) return;
if (node instanceof JSONObject) {
JSONObject o = (JSONObject) node;
JSONArray artists = o.optJSONArray("artists");
String name = o.optString("name", "");
if (artists!= null && artists.length() > 0 &&!name.isEmpty()) {
String artist = joinArtists(artists, null);
String key = name + "|" + artist;
if (seen.add(key)) {
int dur = o.optInt("duration", 0);
if (dur > 20000) dur = dur / 1000;
out.tracks.add(new Src(name, artist, dur));
}
}
JSONArray keys = o.names();
if (keys!= null) for (int i = 0; i < keys.length(); i++) {
collectTracks(o.opt(keys.optString(i)), out, seen);
}
} else if (node instanceof JSONArray) {
JSONArray a = (JSONArray) node;
for (int i = 0; i < a.length(); i++) collectTracks(a.opt(i), out, seen);
}
}

// ---------------- B 站匹配 ----------------

static String norm(String s) {
if (s == null) return "";
String t = s.toLowerCase().replaceAll("\\s+", "");
t = t.replace('（', '(').replace('）', ')').replace('【', '[').replace('】', ']');
return t;
}

static String coreName(String s) {
String t = norm(s);
t = t.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\([^)]*\\)", "");
return t.trim();
}

private static final String[] PENALTY_WORDS = {"翻唱", "cover", "现场版", "live", "伴奏", "纯音乐", "钢琴版", "教学", "合唱版", "dj版", "加速版", "降调", "8d", "耳机版"};

/** 给一条 B 站候选打分；返回 -1 表示歌名没对上（硬门槛不过） */
public static int score(Track c, Src s) {
String titleN = norm(c.title);
String nameN = norm(s.name);
String core = coreName(s.name);
boolean nameHit = (!nameN.isEmpty() && titleN.contains(nameN))
|| (!core.isEmpty() && core.length() >= 2 && titleN.contains(core));
if (!nameHit) return -1;
int sc = 50;
if (titleN.equals(nameN) || coreName(c.title).equals(core)) sc += 10;
boolean artistHit = false;
if (s.artist!= null &&!s.artist.isEmpty()) {
for (String part: s.artist.split("[/、,&]")) {
String a = norm(part);
if (a.length() >= 2 && (titleN.contains(a) || norm(c.author).contains(a))) { artistHit = true; break;}
}
}
if (artistHit) sc += 30;
if (s.durSec > 0 && c.durationSec > 0) {
int diff = Math.abs(s.durSec - c.durationSec);
if (diff <= 10) sc += 20; else if (diff <= 20) sc += 12; else if (diff <= 35) sc += 5;
else if (diff > 90) sc -= 20;
}
String srcN = nameN + norm(s.artist);
for (String w: PENALTY_WORDS) {
if (titleN.contains(w) &&!srcN.contains(w)) { sc -= 25; break;}
}
return sc;
}

public static Track pickBest(List<Track> cands, Src s) {
Track best = null; int bs = -1;
for (Track c: cands) {
int sc = score(c, s);
if (sc > bs) { bs = sc; best = c;}
}
return bs >= 78? best: null;
}

/** 同步等待 BiliApi 异步搜索（在导入工作线程里调用） */
public static List<Track> searchSync(BiliApi api, String keyword) {
final List<Track>[] box = new List[1];
final Exception[] err = new Exception[1];
final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
api.search(keyword, 1, null, new BiliApi.Cb<List<Track>>() {
@Override public void onOk(List<Track> v) { box[0] = v; latch.countDown();}
@Override public void onErr(String msg) { err[0] = new Exception(msg); latch.countDown();}
});
try { latch.await(20, java.util.concurrent.TimeUnit.SECONDS);} catch (InterruptedException ignored) {}
if (box[0]!= null) return box[0];
return new ArrayList<>();
}

public static Track matchOne(BiliApi api, Src s) {
String firstArtist = s.artist == null? "": s.artist.split("[/、,&]")[0].trim();
String q = firstArtist.isEmpty()? s.name: s.name + " " + firstArtist;
List<Track> cands = searchSync(api, q);
Track best = pickBest(cands, s);
if (best == null &&!firstArtist.isEmpty()) {
// 换个问法再试一次：只用歌名搜，靠打分把关
best = pickBest(searchSync(api, s.name), s);
}
return best;
}
}
