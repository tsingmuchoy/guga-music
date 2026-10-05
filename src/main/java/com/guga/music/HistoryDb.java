package com.guga.music;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class HistoryDb extends SQLiteOpenHelper {
    public HistoryDb(Context ctx) {
        super(ctx, "history.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE plays (bvid TEXT PRIMARY KEY, title TEXT, cover TEXT, author TEXT, duration INTEGER, last_played INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    public void add(Track t) {
        ContentValues v = new ContentValues();
        v.put("bvid", t.bvid);
        v.put("title", t.title);
        v.put("cover", t.cover);
        v.put("author", t.author);
        v.put("duration", t.durationSec);
        v.put("last_played", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("plays", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Track> list() {
        List<Track> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("plays", null, null, null, null, null, "last_played DESC", "100");
        try {
            while (c.moveToNext()) {
                Track t = new Track();
                t.bvid = c.getString(0);
                t.title = c.getString(1);
                t.cover = c.getString(2);
                t.author = c.getString(3);
                t.durationSec = c.getInt(4);
                out.add(t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void clear() {
        getWritableDatabase().delete("plays", null, null);
    }
}
