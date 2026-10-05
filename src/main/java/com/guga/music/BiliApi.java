package com.guga.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BiliApi {

    public interface Cb<T> {
        void onOk(T v);
        void onErr(String msg);
    }

    public static class FavFolder {
        public long id;
        public String title;
        public int count;
    }

    public static class MyInfo {
        public boolean login;
        public String uname = "";
        public String face = "";
        public long mid;
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final SharedPreferences sp;

    public BiliApi(Context ctx) {
        sp = ctx.getSharedPreferences("bili", Context.MODE_PRIVATE);
    }

    // ---------------- 登录态 ----------------
    public boolean isLoggedIn() {
        return sp.getString("sessdata", null) != null;
    }

    public void saveLoginCookies(Map<String, String> cookies) {
        SharedPreferences.Editor e = sp.edit();
        if (cookies.containsKey("SESSDATA")) e.putString("sessdata", cookies.get("SESSDATA"));
        if (cookies.containsKey("DedeUserID")) e.putString("mid", cookies.get("DedeUserID"));
        if (cookies.containsKey("bili_jct")) e.putString("bili_jct", cookies.get("bili_jct"));
        e.apply();
    }

    public void logout() {
        sp.edit().remove("sessdata").remove("mid").remove("bili_jct").remove("uname").remove("face").apply();
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        appendCookie(sb, "buvid3", sp.getString("buvid3", null));
        appendCookie(sb, "buvid4", sp.getString("buvid4", null));
        appendCookie(sb, "SESSDATA", sp.getString("sessdata", null));
        appendCookie(sb, "DedeUserID", sp.getString("mid", null));
        appendCookie(sb, "bili_jct", sp.getString("bili_jct", null));
        return sb.toString();
    }

    private static void appendCookie(StringBuilder sb, String k, String v) {
        if (v == null || v.isEmpty()) return;
        if (sb.length() > 0) sb.append("; ");
        sb.append(k).append('=').append(v);
    }

    // ---------------- 基础请求 ----------------
    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.bilibili.com");
        String ck = cookieHeader();
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        int code = c.getResponseCode();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return new JSONObject(sb.toString());
    }

    private <T> void run(java.util.concurrent.Callable<T> job, Cb<T> cb) {
        POOL.execute(() -> {
            try {
                T v = job.call();
                MAIN.post(() -> cb.onOk(v));
            } catch (Exception e) {
                String m = e.getMessage() == null ? "网络异常" : e.getMessage();
                MAIN.post(() -> cb.onErr(m));
            }
        });
    }

    /** 确保 buvid 与当日 mixin key 就绪，并刷新登录信息 */
    private void prepare() throws Exception {
        if (sp.getString("buvid3", null) == null) {
            JSONObject spi = getJson("https://api.bilibili.com/x/frontend/finger/spi");
            JSONObject d = spi.getJSONObject("data");
            sp.edit().putString("buvid3", d.getString("b_3"))
                    .putString("buvid4", d.getString("b_4")).apply();
        }
        String today = String.valueOf(System.currentTimeMillis() / 86400000L);
        if (!today.equals(sp.getString("mixin_day", "")) || sp.getString("mixin", null) == null) {
            JSONObject nav = getJson("https://api.bilibili.com/x/web-interface/nav");
            JSONObject d = nav.getJSONObject("data");
            JSONObject wi = d.getJSONObject("wbi_img");
            String img = wi.getString("img_url");
            String sub = wi.getString("sub_url");
            img = img.substring(img.lastIndexOf('/') + 1).replace(".png", "");
            sub = sub.substring(sub.lastIndexOf('/') + 1).replace(".png", "");
            SharedPreferences.Editor e = sp.edit();
            e.putString("mixin", Wbi.mixinKey(img, sub));
            e.putString("mixin_day", today);
            if (d.optBoolean("isLogin")) {
                e.putString("uname", d.optString("uname"));
                e.putString("face", d.optString("face"));
                e.putString("mid", String.valueOf(d.optLong("mid")));
            }
            e.apply();
        }
    }

    private String mixin() {
        return sp.getString("mixin", "");
    }

    /** 让下次 prepare() 强制重新拉取签名密钥（签名疑似失效时用） */
    public void invalidateMixin() {
        sp.edit().remove("mixin_day").apply();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------- 业务接口 ----------------
    public void myInfo(Cb<MyInfo> cb) {
        run(() -> {
            prepare();
            MyInfo mi = new MyInfo();
            mi.login = isLoggedIn();
            mi.uname = sp.getString("uname", "");
            mi.face = sp.getString("face", "");
            try { mi.mid = Long.parseLong(sp.getString("mid", "0")); } catch (Exception ignored) {}
            // 若已登录但还没缓存用户信息，主动拉一次 nav
            if (mi.login && mi.uname.isEmpty()) {
                JSONObject nav = getJson("https://api.bilibili.com/x/web-interface/nav");
                JSONObject d = nav.getJSONObject("data");
                if (d.optBoolean("isLogin")) {
                    mi.uname = d.optString("uname");
                    mi.face = d.optString("face");
                    mi.mid = d.optLong("mid");
                    sp.edit().putString("uname", mi.uname).putString("face", mi.face)
                            .putString("mid", String.valueOf(mi.mid)).apply();
                } else {
                    mi.login = false;
                }
            }
            return mi;
        }, cb);
    }

    public void search(String keyword, int page, Cb<List<Track>> cb) {
        run(() -> {
            prepare();
            Map<String, String> p = new HashMap<>();
            p.put("search_type", "video");
            p.put("keyword", keyword);
            p.put("page", String.valueOf(page));
            String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" + Wbi.signQuery(p, mixin());
            JSONObject j = getJson(url);
            if (j.getInt("code") != 0) throw new Exception("搜索失败：" + j.optString("message"));
            List<Track> out = new ArrayList<>();
            JSONArray arr = j.getJSONObject("data").optJSONArray("result");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Track t = new Track();
                    t.bvid = o.optString("bvid");
                    t.title = stripHtml(o.optString("title"));
                    String pic = o.optString("pic");
                    t.cover = pic.startsWith("//") ? "https:" + pic : pic;
                    t.author = o.optString("author");
                    t.durationSec = parseDur(o.optString("duration"));
                    if (!t.bvid.isEmpty()) out.add(t);
                }
            }
            return out;
        }, cb);
    }

    /** 取视频字幕（歌词兜底用）：优先中文字幕轨 */
    public void subtitles(String bvid, long cid, Cb<List<Lyrics.Line>> cb) {
        run(() -> {
            JSONObject r = getJson("https://api.bilibili.com/x/player/v2?bvid=" + bvid + "&cid=" + cid);
            if (r.optInt("code") != 0) throw new Exception("字幕接口错误 " + r.optInt("code") + "：" + r.optString("message"));
            JSONObject data = r.getJSONObject("data");
            JSONObject sub = data.optJSONObject("subtitle");
            List<Lyrics.Line> empty = new ArrayList<>();
            if (sub == null) return empty;
            JSONArray arr = sub.optJSONArray("subtitles");
            if (arr == null || arr.length() == 0) return empty;
            JSONObject pick = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("lan").startsWith("zh")) { pick = o; break; }
                if (pick == null) pick = o;
            }
            if (pick == null) return empty;
            String url = pick.optString("subtitle_url");
            if (url.startsWith("//")) url = "https:" + url;
            if (url.isEmpty()) return empty;
            JSONObject body = getJson(url);
            JSONArray items = body.optJSONArray("body");
            List<Lyrics.Line> out = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    String text = o.optString("content").trim();
                    if (!text.isEmpty()) out.add(new Lyrics.Line((long) (o.optDouble("from") * 1000), text));
                }
            }
            return out;
        }, cb);
    }

    public void view(String bvid, Cb<Track> cb) {
        run(() -> {
            prepare();
            Map<String, String> p = new HashMap<>();
            p.put("bvid", bvid);
            String url = "https://api.bilibili.com/x/web-interface/wbi/view?" + Wbi.signQuery(p, mixin());
            JSONObject j = getJson(url);
            if (j.getInt("code") != 0) throw new Exception("获取视频信息失败：" + j.optString("message"));
            JSONObject d = j.getJSONObject("data");
            Track t = new Track();
            t.bvid = bvid;
            t.cid = d.optLong("cid");
            t.title = d.optString("title");
            t.cover = d.optString("pic");
            t.author = d.optJSONObject("owner") != null ? d.getJSONObject("owner").optString("name") : "";
            t.durationSec = d.optInt("duration");
            return t;
        }, cb);
    }

    /** 返回 {主地址, 备用地址(可空)}；优先 AAC 流（MediaPlayer 兼容最好），FLAC/杜比仅兜底 */
    public void playUrl(Track t, boolean lowQuality, Cb<String[]> cb) {
        run(() -> {
            prepare();
            Map<String, String> p = new HashMap<>();
            p.put("bvid", t.bvid);
            p.put("cid", String.valueOf(t.cid));
            p.put("fnval", "16");
            p.put("fnver", "0");
            p.put("fourk", "1");
            String url = "https://api.bilibili.com/x/player/wbi/playurl?" + Wbi.signQuery(p, mixin());
            JSONObject j = getJson(url);
            if (j.getInt("code") != 0) throw new Exception("获取播放地址失败（" + j.getInt("code") + "）：" + j.optString("message"));
            JSONObject d = j.getJSONObject("data");
            JSONObject dash = d.optJSONObject("dash");
            if (dash != null) {
                JSONArray auds = dash.getJSONArray("audio");
                JSONObject bestAac = null, bestAny = null;
                for (int i = 0; i < auds.length(); i++) {
                    JSONObject a = auds.getJSONObject(i);
                    int bw = a.optInt("bandwidth");
                    String codecs = a.optString("codecs");
                    if (bestAny == null || (lowQuality ? bw < bestAny.optInt("bandwidth") : bw > bestAny.optInt("bandwidth"))) bestAny = a;
                    if (codecs.startsWith("mp4a")) {
                        if (bestAac == null || (lowQuality ? bw < bestAac.optInt("bandwidth") : bw > bestAac.optInt("bandwidth"))) bestAac = a;
                    }
                }
                JSONObject pick = bestAac != null ? bestAac : bestAny;
                String backup = null;
                JSONArray bk = pick.optJSONArray("backupUrl");
                if (bk != null && bk.length() > 0) backup = bk.getString(0);
                return new String[]{pick.getString("baseUrl"), backup};
            }
            JSONArray durl = d.optJSONArray("durl");
            if (durl != null && durl.length() > 0) return new String[]{durl.getJSONObject(0).getString("url"), null};
            throw new Exception("该视频没有可播放的音频流");
        }, cb);
    }

    public void favFolders(Cb<List<FavFolder>> cb) {
        run(() -> {
            prepare();
            long mid = 0;
            try { mid = Long.parseLong(sp.getString("mid", "0")); } catch (Exception ignored) {}
            if (mid == 0) throw new Exception("还没登录");
            JSONObject j = getJson("https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=" + mid);
            if (j.getInt("code") != 0) throw new Exception("收藏夹读取失败：" + j.optString("message"));
            List<FavFolder> out = new ArrayList<>();
            JSONObject d = j.optJSONObject("data");
            JSONArray arr = d == null ? null : d.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    FavFolder f = new FavFolder();
                    f.id = o.optLong("id");
                    f.title = o.optString("title");
                    f.count = o.optInt("media_count");
                    out.add(f);
                }
            }
            return out;
        }, cb);
    }

    public void favTracks(long mediaId, Cb<List<Track>> cb) {
        run(() -> {
            prepare();
            List<Track> out = new ArrayList<>();
            int pn = 1;
            while (pn <= 40) { // ps 上限 20，最多 40 页 = 800 条
                JSONObject j = getJson("https://api.bilibili.com/x/v3/fav/resource/list?media_id=" + mediaId + "&pn=" + pn + "&ps=20&platform=web");
                if (j.getInt("code") != 0) throw new Exception("收藏内容读取失败（" + j.getInt("code") + "）：" + j.optString("message"));
                JSONObject d = j.getJSONObject("data");
                JSONArray arr = d.optJSONArray("medias");
                if (arr == null || arr.length() == 0) break;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String bvid = o.optString("bvid");
                    String title = o.optString("title");
                    if (bvid.isEmpty() || "已失效视频".equals(title)) continue;
                    Track t = new Track();
                    t.bvid = bvid;
                    t.title = title;
                    t.cover = o.optString("cover");
                    JSONObject up = o.optJSONObject("upper");
                    t.author = up != null ? up.optString("name") : "";
                    t.durationSec = o.optInt("duration");
                    out.add(t);
                }
                if (!d.optBoolean("has_more")) break;
                pn++;
            }
            return out;
        }, cb);
    }

    // ---------------- 工具 ----------------
    public static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    public static int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            String[] parts = s.split(":");
            int v = 0;
            for (String part : parts) v = v * 60 + Integer.parseInt(part.trim());
            return v;
        } catch (Exception e) {
            return 0;
        }
    }
}
