package com.guga.music;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** 手动锁定的歌词版本：按 BV 号绑定「歌词源 + 源内定位」，存 lyrics.db（一行几十字节）。
 *  用户在歌词页手动搜到并确认的版本记在这里，不受歌词文件缓存的轮转影响 */
public class LyricsDb extends SQLiteOpenHelper {

    public static class Bind {
        public String src, ref, name, artist, label, cover;
        public long durMs;
    }

    private static LyricsDb inst;

    private static synchronized LyricsDb of(Context ctx) {
        if (inst == null) inst = new LyricsDb(ctx.getApplicationContext());
        return inst;
    }

    private LyricsDb(Context ctx) { super(ctx, "lyrics.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE lyric_bind (bvid TEXT PRIMARY KEY, src TEXT, ref TEXT,"
                + " name TEXT, artist TEXT, dur INTEGER, label TEXT, ts INTEGER, cover TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) db.execSQL("ALTER TABLE lyric_bind ADD COLUMN cover TEXT");
    }

    public static Bind get(Context ctx, String bvid) {
        if (bvid == null || bvid.isEmpty()) return null;
        Cursor c = of(ctx).getReadableDatabase().query("lyric_bind",
                new String[]{"src", "ref", "name", "artist", "dur", "label", "cover"},
                "bvid=?", new String[]{bvid}, null, null, null);
        try {
            if (!c.moveToFirst()) return null;
            Bind b = new Bind();
            b.src = c.getString(0);
            b.ref = c.getString(1);
            b.name = c.getString(2);
            b.artist = c.getString(3);
            b.durMs = c.getLong(4);
            b.label = c.getString(5);
            b.cover = c.getString(6);
            return b;
        } finally { c.close(); }
    }

    public static void bind(Context ctx, String bvid, Bind b) {
        ContentValues v = new ContentValues();
        v.put("bvid", bvid);
        v.put("src", b.src);
        v.put("ref", b.ref);
        v.put("name", b.name);
        v.put("artist", b.artist);
        v.put("dur", b.durMs);
        v.put("label", b.label);
        v.put("cover", b.cover);
        v.put("ts", System.currentTimeMillis());
        of(ctx).getWritableDatabase().insertWithOnConflict("lyric_bind", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static void unbind(Context ctx, String bvid) {
        if (bvid == null) return;
        of(ctx).getWritableDatabase().delete("lyric_bind", "bvid=?", new String[]{bvid});
    }
}
