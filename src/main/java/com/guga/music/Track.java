package com.guga.music;

public class Track {
    public String bvid;
    public long cid;
    public String title;
    public String cover;
    public String author;
    public int durationSec;

    public Track() {}

    public Track(String bvid, String title, String cover, String author, int durationSec) {
        this.bvid = bvid;
        this.title = title;
        this.cover = cover;
        this.author = author;
        this.durationSec = durationSec;
    }

    public static String fmtDur(int sec) {
        if (sec <= 0) return "--:--";
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }
}
