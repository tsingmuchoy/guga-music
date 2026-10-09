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

    public synchronized List<Row> top(String fromDay, String toDay, int limit) {
        List<Row> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT bvid, title, author, cover, SUM(plays) p, SUM(seconds) s FROM play_day"
                        + " WHERE day>=? AND day<=? GROUP BY bvid ORDER BY p DESC, s DESC LIMIT " + limit,
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
                out.add(r);
            }
        } finally { c.close(); }
        return out;
    }
}
