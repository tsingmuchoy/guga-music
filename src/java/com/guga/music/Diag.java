package com.guga.music;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 轻量诊断日志：把播放关键事件存本地，方便在设置页查看/截图定位问题 */
public class Diag {
    private static final int MAX_LINES = 150;

    public static synchronized void log(Context c, String msg) {
        try {
            SharedPreferences sp = c.getSharedPreferences("diag", Context.MODE_PRIVATE);
            String old = sp.getString("log", "");
            String line = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()) + "  " + msg;
            String[] parts = (old + "\n" + line).split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, parts.length - MAX_LINES);
            for (int i = from; i < parts.length; i++) {
                if (!parts[i].isEmpty()) sb.append(parts[i]).append("\n");
            }
            sp.edit().putString("log", sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String read(Context c) {
        try {
            String s = c.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("log", "");
            return s == null || s.isEmpty() ? "（暂无日志）" : s;
        } catch (Exception e) {
            return "（暂无日志）";
        }
    }

    /** 展示用：相邻重复行折叠（保留最新时间、带 ×N 计数），整表倒序（最新在上） */
    public static String readDisplay(Context c) {
        String raw = read(c);
        if (raw.startsWith("（")) return raw;
        java.util.List<String> folded = new java.util.ArrayList<>();
        String lastMsg = null, lastTime = null;
        int cnt = 0;
        for (String ln : raw.split("\n")) {
            if (ln.isEmpty()) continue;
            String time = "", msg = ln;
            int i = ln.indexOf("  ");
            if (i > 0) { time = ln.substring(0, i); msg = ln.substring(i + 2); }
            if (msg.equals(lastMsg)) {
                cnt++;
                lastTime = time;
            } else {
                if (lastMsg != null) folded.add(fmtLine(lastTime, lastMsg, cnt));
                lastMsg = msg; lastTime = time; cnt = 1;
            }
        }
        if (lastMsg != null) folded.add(fmtLine(lastTime, lastMsg, cnt));
        StringBuilder sb = new StringBuilder();
        for (int i = folded.size() - 1; i >= 0; i--) sb.append(folded.get(i)).append("\n");
        return sb.toString().trim();
    }

    private static String fmtLine(String time, String msg, int cnt) {
        return time + "  " + msg + (cnt > 1 ? " ×" + cnt : "");
    }

    public static void clear(Context c) {
        try {
            c.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().remove("log").apply();
        } catch (Exception ignored) {}
    }
}
