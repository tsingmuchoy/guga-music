package com.guga.music;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 本地歌单存储（与 B 站收藏夹并行，互不影响）：歌单 + 歌曲元数据存本地 SQLite */
public class LocalDb extends SQLiteOpenHelper {

    public static class Playlist {
        public long id;
        public String name;
        public int count;
    }

    public LocalDb(Context ctx) {
        super(ctx.getApplicationContext(), "local.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, created INTEGER)");
        db.execSQL("CREATE TABLE ptracks(id INTEGER PRIMARY KEY AUTOINCREMENT, pid INTEGER, bvid TEXT, cid INTEGER, title TEXT, author TEXT, cover TEXT, dur INTEGER, added INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT p.id, p.name, (SELECT COUNT(*) FROM ptracks t WHERE t.pid = p.id) FROM playlists p ORDER BY p.created ASC", null);
        while (c.moveToNext()) {
            Playlist p = new Playlist();
            p.id = c.getLong(0);
            p.name = c.getString(1);
            p.count = c.getInt(2);
            out.add(p);
        }
        c.close();
        return out;
    }

    public long createPlaylist(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("playlists", null, v);
    }

    public void renamePlaylist(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("playlists", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deletePlaylist(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("ptracks", "pid=?", new String[]{String.valueOf(id)});
        db.delete("playlists", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Track> tracks(long pid) {
        List<Track> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("ptracks",
                new String[]{"bvid", "cid", "title", "author", "cover", "dur"},
                "pid=?", new String[]{String.valueOf(pid)}, null, null, "added ASC, id ASC");
        while (c.moveToNext()) {
            Track t = new Track();
            t.bvid = c.getString(0);
            t.cid = c.getLong(1);
            t.title = c.getString(2);
            t.author = c.getString(3);
            t.cover = c.getString(4);
            t.durationSec = c.getInt(5);
            out.add(t);
        }
        c.close();
        return out;
    }

    /** @return true 新加入；false 已经在歌单里 */
    public boolean addTrack(long pid, Track t) {
        Cursor c = getReadableDatabase().query("ptracks", new String[]{"id"},
                "pid=? AND bvid=?", new String[]{String.valueOf(pid), t.bvid}, null, null, null);
        boolean exists = c.moveToFirst();
        c.close();
        if (exists) return false;
        ContentValues v = new ContentValues();
        v.put("pid", pid);
        v.put("bvid", t.bvid);
        v.put("cid", t.cid);
        v.put("title", t.title);
        v.put("author", t.author);
        v.put("cover", t.cover);
        v.put("dur", t.durationSec);
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insert("ptracks", null, v);
        return true;
    }

    public void removeTrack(long pid, String bvid) {
        getWritableDatabase().delete("ptracks", "pid=? AND bvid=?",
                new String[]{String.valueOf(pid), bvid});
    }
}
