package com.guga.music;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 轻量诊断日志：把播放关键事件存本地，方便在设置页查看/截图定位问题 */
public class Diag {
    private static final int MAX_LINES = 80;

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

    public static void clear(Context c) {
        try {
            c.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().remove("log").apply();
        } catch (Exception ignored) {}
    }
}
