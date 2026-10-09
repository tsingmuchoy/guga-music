package com.guga.music;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 播放统计：按「歌曲 × 天」聚合（播放次数 / 累计秒数），只留最近 400 天，体量封顶 */
public class StatsDb extends SQLiteOpenHelper {

    public StatsDb(Context ctx) {
        super(ctx, "stats.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE play_day (bvid TEXT, day TEXT, plays INTEGER, seconds INTEGER,"
                + " title TEXT, author TEXT, cover TEXT, PRIMARY KEY(bvid, day))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    public static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date(ms));
    }

    public static String todayKey() {
        return dayKey(System.currentTimeMillis());
    }

    /** 含今天在内最近 days 天的起始日 */
    public static String daysAgoKey(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        return dayKey(c.getTimeInMillis());
    }

    public synchronized void recordPlay(Track t) {
        if (t == null || t.bvid == null || t.bvid.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        String today = todayKey();
        int plays = 0;
        boolean exists = false;
        Cursor c = db.query("play_day", new String[]{"plays"}, "bvid=? AND day=?",
                new String[]{t.bvid, today}, null, null, null);
        try {
            if (c.moveToFirst()) { exists = true; plays = c.getInt(0); }
        } finally { c.close(); }
        ContentValues v = new ContentValues();
        v.put("bvid", t.bvid);
        v.put("day", today);
        v.put("plays", plays + 1);
        v.put("title", t.title);
        v.put("author", t.author);
        v.put("cover", t.cover);
        if (exists) {
            db.update("play_day", v, "bvid=? AND day=?", new String[]{t.bvid, today});
        } else {
            v.put("seconds", 0);
            db.insert("play_day", null, v);
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -400);
        db.delete("play_day", "day<?", new String[]{dayKey(cal.getTimeInMillis())});
    }

    public synchronized void addSeconds(Track t, int sec) {
        if (t == null || t.bvid == null || t.bvid.isEmpty() || sec <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        String today = todayKey();
        int old = 0;
        boolean exists = false;
        Cursor c = db.query("play_day", new String[]{"seconds"}, "bvid=? AND day=?",
                new String[]{t.bvid, today}, null, null, null);
        try {
            if (c.moveToFirst()) { exists = true; old = c.getInt(0); }
        } finally { c.close(); }
        ContentValues v = new ContentValues();
        v.put("seconds", old + sec);
        if (exists) {
            db.update("play_day", v, "bvid=? AND day=?", new String[]{t.bvid, today});
        } else {
            v.put("bvid", t.bvid);
            v.put("day", today);
            v.put("plays", 0);
            v.put("title", t.title);
            v.put("author", t.author);
            v.put("cover", t.cover);
            db.insert("play_day", null, v);
        }
    }

    public static class Sum {
        public long plays, seconds, tracks;
    }

    /** 清空全部播放统计 */
    public synchronized void clearAll() {
        getWritableDatabase().delete("play_day", null, null);
    }

    /** 导出全部记录（备份用）：每行 [bvid, day, plays, seconds, title, author, cover] */
    public synchronized List<String[]> exportRows() {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT bvid, day, plays, seconds, title, author, cover FROM play_day ORDER BY day, bvid", null);
        try {
            while (c.moveToNext()) {
                out.add(new String[]{
                        c.getString(0), c.getString(1),
                        String.valueOf(c.getLong(2)), String.valueOf(c.getLong(3)),
                        c.getString(4) == null ? "" : c.getString(4),
                        c.getString(5) == null ? "" : c.getString(5),
                        c.getString(6) == null ? "" : c.getString(6)});
            }
        } finally { c.close(); }
        return out;
    }

    /** 导入备份：整表替换（先清后灌、一个事务） */
    public synchronized void importRows(List<String[]> rows) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("play_day", null, null);
            for (String[] r : rows) {
                if (r == null || r.length < 4 || r[0] == null || r[0].isEmpty() || r[1] == null) continue;
                ContentValues v = new ContentValues();
                v.put("bvid", r[0]);
                v.put("day", r[1]);
                v.put("plays", parseLongSafe(r[2]));
                v.put("seconds", parseLongSafe(r[3]));
                v.put("title", r.length > 4 ? r[4] : "");
                v.put("author", r.length > 5 ? r[5] : "");
                v.put("cover", r.length > 6 ? r[6] : "");
                db.insertWithOnConflict("play_day", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    public synchronized Sum summary(String fromDay, String toDay) {
        Sum s = new Sum();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(plays),0), COALESCE(SUM(seconds),0), COUNT(DISTINCT bvid)"
                        + " FROM play_day WHERE day>=? AND day<=?",
                new String[]{fromDay, toDay});
        try {
            if (c.moveToFirst()) {
                s.plays = c.getLong(0);
                s.seconds = c.getLong(1);
                s.tracks = c.getLong(2);
            }
        } finally { c.close(); }
        return s;
    }

    public static class Row {
        public String bvid, title, author, cover;
        public long plays, seconds;
    }

    // ---------------- 同歌归一 ----------------
    // 同一首歌常有多个视频（原唱/现场/翻唱/不同 UP 主上传），按 BV 分开算会把一首歌拆成好几条。
    // 归一键 = 清洗后的歌名 + 能提炼出的歌手；榜单与曲目数都按归一后的口径算。

    /** 从标题提取歌名：优先《》/「」中的内容；其次「歌手 - 歌名」的右半；否则原标题。
     *  结尾年份是版本注记不是歌名（「初恋 1990」→「初恋」），与封面引擎同口径 */
    static String extractSongName(String title) {
        if (title == null) return "";
        String name;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[《「]([^》」]{1,80})[》」]").matcher(title);
        if (m.find()) {
            name = m.group(1).trim();
        } else {
            String[] parts = title.split("\\s+[-—–~]\\s+", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) name = parts[1].trim();
            else name = title.trim();
        }
        java.util.regex.Matcher ym = java.util.regex.Pattern.compile(
                "^(.+?)\\s*[（(]?\\s*(19|20)\\d{2}\\s*[)）]?$").matcher(name);
        if (ym.find() && !cleanKey(ym.group(1)).isEmpty()) name = ym.group(1).trim();
        return name;
    }

    /** 从标题提取歌手：《》型取书名号前紧挨着的最后一个词段（前面常有「在…大声听」之类前缀垃圾），
     *  提不到再看书名号后、再挖标题括号段；「歌手 - 歌名」型取左半。提不出返回空 */
    static String extractSinger(String title) {
        if (title == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)[《「]").matcher(title);
        if (m.find()) {
            String pre = cleanLoose(m.group(1));
            // 前缀里若含「大声听/聆听」这类词，歌手在它们之后（哪怕和歌手连写没空格）
            String[] verbs = {"大声听", "一起听", "聆听", "欣赏"};
            for (String v : verbs) {
                int vi = pre.lastIndexOf(v);
                if (vi >= 0) { pre = pre.substring(vi + v.length()); break; }
            }
            String seg = trimSinger(lastSegment(pre));
            if (seg.length() >= 2 && seg.length() <= 12 && !junkPhrase(seg)) return seg;
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("[》」](.*?)$").matcher(title);
            if (m2.find()) {
                String seg2 = trimSinger(firstSegment(cleanLoose(m2.group(1))));
                if (seg2.length() >= 2 && seg2.length() <= 12 && !junkPhrase(seg2)) return seg2;
            }
            return bracketSinger(title);
        }
        String[] parts = title.split("\\s+[-—–~]\\s+", 2);
        if (parts.length == 2) {
            String left = trimSinger(cleanLoose(parts[0]));
            if (left.length() >= 2 && left.length() <= 12 && !junkPhrase(left)) return left;
        }
        return bracketSinger(title);
    }

    /** 像「百万级录音棚听」这类是场景短语、不是人名，直接否决 */
    private static boolean junkPhrase(String s) {
        return s.contains("录音棚") || s.contains("豪装") || s.contains("百万")
                || s.contains("音响") || s.contains("试听") || s.contains("录音室");
    }

    /** 歌手藏在标题括号段里时挖出来（如【One Last Kiss | 宇多田光】、[歌名 - 歌手]） */
    private static String bracketSinger(String title) {
        String songNameKey = cleanKey(extractSongName(title));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "[【\\[]([^】\\]]{2,40})[】\\]]").matcher(title);
        while (m.find()) {
            for (String part : m.group(1).split("[|｜/·、]")) {
                String cand = trimSinger(cleanLoose(part));
                if (cand.length() < 2 || cand.length() > 12 || junkPhrase(cand)) continue;
                String ck = cleanKey(cand);
                if (!ck.isEmpty() && !ck.equals(songNameKey)) return cand;
            }
        }
        return "";
    }

    private static String lastSegment(String s) {
        String[] parts = s.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String firstSegment(String s) {
        String[] parts = s.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    /** 歌手名修剪：剥掉「大声听/聆听」等前缀垃圾与「乐队」后缀 */
    private static String trimSinger(String s) {
        String x = s == null ? "" : s.trim();
        String[] junk = {"大声听", "一起听", "聆听", "欣赏", "播放", "来听", "听"};
        for (String j : junk) {
            if (x.startsWith(j) && x.length() > j.length() + 1) {
                x = x.substring(j.length());
                break;
            }
        }
        if (x.endsWith("乐队") && x.length() > 3) x = x.substring(0, x.length() - 2);
        return x.trim();
    }

    /** 显示用清洗：去掉括号段与常见噪声词，保留原文大小写 */
    static String cleanLoose(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[【\\[（(][^】\\]）)]*[】\\]）)]", " ");
        x = x.replaceAll("(?i)(官方|完整版|高清|修复版?|无损|纯享|现场|演唱会|mv|4k|8k|hi-?res|flac|cover|翻唱|原唱|伴奏)", " ");
        return x.replaceAll("\\s+", " ").trim();
    }

    /** 归一键清洗：只留字母/数字/汉字，统一小写 */
    static String cleanKey(String s) {
        String x = cleanLoose(s).toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.length(); i++) {
            char ch = x.charAt(i);
            if (Character.isLetterOrDigit(ch)) b.append(ch);
        }
        return b.toString();
    }

    static String songKey(String title, String author) {
        String name = cleanKey(extractSongName(title));
        if (name.isEmpty()) return "";
        return name + "|" + cleanKey(extractSinger(title));
    }

    private static class Grp {
        Row row;
        String nameKey = "", singerKey = "", dispName = "", dispSinger = "";
        long repPlays;
    }

    /** 歌手键兼容：相同，或互为包含的别名（短键 ≥3 字、长度差 ≤4，防「周杰/周杰伦」这类误合） */
    private static boolean singerCompatible(String a, String b) {
        if (a.equals(b)) return true;
        if (a.isEmpty() || b.isEmpty()) return false;
        String sh = a.length() <= b.length() ? a : b;
        String lo = a.length() <= b.length() ? b : a;
        return sh.length() >= 3 && lo.length() - sh.length() <= 4 && lo.contains(sh);
    }

    public synchronized List<Row> top(String fromDay, String toDay, int limit) {
        List<Row> perVideo = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT bvid, title, author, cover, SUM(plays) p, SUM(seconds) s FROM play_day"
                        + " WHERE day>=? AND day<=? GROUP BY bvid ORDER BY p DESC, s DESC",
                new String[]{fromDay, toDay});
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.bvid = c.getString(0);
                r.title = c.getString(1);
                r.author = c.getString(2);
                r.cover = c.getString(3);
                r.plays = c.getLong(4);
                r.seconds = c.getLong(5);
                perVideo.add(r);
            }
        } finally { c.close(); }

        // 归一分组：歌名键相同、且歌手键相同或互为别名（BEYOND 与 黄家驹Beyond）才合并
        List<Grp> grps = new ArrayList<>();
        for (Row v : perVideo) {
            String dispName = cleanLoose(extractSongName(v.title));
            String dispSinger = extractSinger(v.title);
            String nameKey = cleanKey(extractSongName(v.title));
            String singerKey = cleanKey(dispSinger);
            Grp g = null;
            if (!nameKey.isEmpty()) {
                for (Grp cand : grps) {
                    if (cand.nameKey.equals(nameKey) && singerCompatible(cand.singerKey, singerKey)) {
                        g = cand;
                        break;
                    }
                }
            }
            if (g == null) {
                g = new Grp();
                g.row = new Row();
                g.row.bvid = v.bvid;
                g.row.title = v.title;
                g.row.author = v.author;
                g.row.cover = v.cover;
                g.nameKey = nameKey;
                g.singerKey = singerKey;
                g.dispName = dispName;
                g.dispSinger = dispSinger;
                g.repPlays = -1;
                grps.add(g);
            }
            g.row.plays += v.plays;
            g.row.seconds += v.seconds;
            if (v.plays > g.repPlays) {
                // 代表条目 = 组内播得最多的那个视频（回播就播它）
                g.repPlays = v.plays;
                g.row.bvid = v.bvid;
                g.row.title = v.title;
                g.row.author = v.author;
                g.row.cover = v.cover;
                if (!dispName.isEmpty()) g.dispName = dispName;
            }
            // 歌手展示取组内更短的变体（BEYOND 优于 黄家驹Beyond）
            if (!dispSinger.isEmpty() && (g.dispSinger.isEmpty()
                    || singerKey.length() < cleanKey(g.dispSinger).length())) {
                g.dispSinger = dispSinger;
                g.singerKey = singerKey;
            }
        }
        List<Row> out = new ArrayList<>();
        for (Grp g : grps) {
            // 展示：干净歌名 + 歌手（提炼不出歌手时保留代表视频的 UP 主名）
            if (!g.dispName.isEmpty()) g.row.title = g.dispName;
            if (!g.dispSinger.isEmpty()) g.row.author = g.dispSinger;
            out.add(g.row);
        }
        out.sort((a, b) -> a.plays != b.plays
                ? Long.compare(b.plays, a.plays) : Long.compare(b.seconds, a.seconds));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 归一后的不同歌曲数（与最常播放榜同一聚类口径，别名歌手也算一首） */
    public synchronized int distinctSongs(String fromDay, String toDay) {
        List<String[]> clusters = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT bvid, title, author FROM play_day WHERE day>=? AND day<=? GROUP BY bvid",
                new String[]{fromDay, toDay});
        try {
            while (c.moveToNext()) {
                String title = c.getString(1);
                String nameKey = cleanKey(extractSongName(title));
                if (nameKey.isEmpty()) {
                    clusters.add(new String[]{"bv|" + c.getString(0), ""});
                    continue;
                }
                String singerKey = cleanKey(extractSinger(title));
                boolean merged = false;
                for (String[] cl : clusters) {
                    if (cl[0].equals(nameKey) && singerCompatible(cl[1], singerKey)) {
                        if (!singerKey.isEmpty() && singerKey.length() < cl[1].length()) cl[1] = singerKey;
                        merged = true;
                        break;
                    }
                }
                if (!merged) clusters.add(new String[]{nameKey, singerKey});
            }
        } finally { c.close(); }
        return clusters.size();
    }
}
