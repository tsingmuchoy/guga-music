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

    /** 从标题提取歌名：优先《》/「」中的内容；其次「歌手 - 歌名」的右半；否则原标题 */
    static String extractSongName(String title) {
        if (title == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[《「]([^》」]{1,80})[》」]").matcher(title);
        if (m.find()) return m.group(1).trim();
        String[] parts = title.split("\\s+[-—–~]\\s+", 2);
        if (parts.length == 2 && !parts[1].trim().isEmpty()) return parts[1].trim();
        return title.trim();
    }

    /** 从标题提取歌手：《》前的前缀（短而干净时）或「歌手 - 歌名」的左半；提不出返回空 */
    static String extractSinger(String title) {
        if (title == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)[《「]").matcher(title);
        if (m.find()) {
            String pre = cleanLoose(m.group(1));
            return pre.length() >= 2 && pre.length() <= 10 ? pre : "";
        }
        String[] parts = title.split("\\s+[-—–~]\\s+", 2);
        if (parts.length == 2) {
            String left = cleanLoose(parts[0]);
            if (left.length() >= 2 && left.length() <= 12) return left;
        }
        return "";
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

        java.util.Map<String, Row> groups = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> repPlays = new java.util.HashMap<>();
        for (Row v : perVideo) {
            String key = songKey(v.title, v.author);
            if (key.isEmpty()) key = "bv|" + v.bvid;
            Row g = groups.get(key);
            if (g == null) {
                g = new Row();
                g.bvid = v.bvid;
                g.title = v.title;
                g.author = v.author;
                g.cover = v.cover;
                groups.put(key, g);
                repPlays.put(key, -1L);
            }
            g.plays += v.plays;
            g.seconds += v.seconds;
            if (v.plays > repPlays.get(key)) {
                // 代表条目 = 组内播得最多的那个视频（回播就播它，封面先用它的）
                repPlays.put(key, v.plays);
                g.bvid = v.bvid;
                g.title = v.title;
                g.author = v.author;
                g.cover = v.cover;
            }
        }
        List<Row> out = new ArrayList<>(groups.values());
        for (Row g : out) {
            // 展示：干净歌名 + 歌手（提炼不出歌手时保留代表视频的 UP 主名）
            String singer = extractSinger(g.title);
            String name = cleanLoose(extractSongName(g.title));
            if (!name.isEmpty()) g.title = name;
            if (!singer.isEmpty()) g.author = singer;
        }
        out.sort((a, b) -> a.plays != b.plays
                ? Long.compare(b.plays, a.plays) : Long.compare(b.seconds, a.seconds));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 归一后的不同歌曲数（与最常播放榜同一口径） */
    public synchronized int distinctSongs(String fromDay, String toDay) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT bvid, title, author FROM play_day WHERE day>=? AND day<=? GROUP BY bvid",
                new String[]{fromDay, toDay});
        try {
            while (c.moveToNext()) {
                String k = songKey(c.getString(1), c.getString(2));
                keys.add(k.isEmpty() ? "bv|" + c.getString(0) : k);
            }
        } finally { c.close(); }
        return keys.size();
    }
}
