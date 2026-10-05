package com.guga.music;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.TypedValue;

public class ThemeUtil {

    public static class Def {
        public final String id, name;
        public final int styleRes, bg, surface, accent;
        Def(String id, String name, int styleRes, int bg, int surface, int accent) {
            this.id = id; this.name = name; this.styleRes = styleRes;
            this.bg = bg; this.surface = surface; this.accent = accent;
        }
    }

    public static final Def[] DEFS = {
            new Def("amber", "琥珀夜", R.style.ThemeAmber, 0xFF121417, 0xFF202329, 0xFFF5A623),
            new Def("pink", "哔哩粉", R.style.ThemePink, 0xFF171218, 0xFF2A2029, 0xFFFB7299),
            new Def("sky", "天空蓝", R.style.ThemeSky, 0xFF0E1520, 0xFF1B2839, 0xFF56B6F7),
            new Def("mint", "薄荷绿", R.style.ThemeMint, 0xFF0D1712, 0xFF192920, 0xFF4CC38A),
            new Def("porcelain", "瓷白玻璃", R.style.ThemePorcelain, 0xFFEDF0F4, 0xFFFFFFFF, 0xFFFF8A3D),
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    public static String currentId(Context c) {
        return prefs(c).getString("theme_id", "amber");
    }

    public static Def current(Context c) {
        String id = currentId(c);
        for (Def d : DEFS) if (d.id.equals(id)) return d;
        return DEFS[0];
    }

    // ---------------- 跟随系统（自动深色） ----------------
    public static boolean isFollowSystem(Context c) {
        return prefs(c).getBoolean("follow_system", false);
    }

    public static void setFollowSystem(Context c, boolean on) {
        prefs(c).edit().putBoolean("follow_system", on).putBoolean("theme_dirty", true).apply();
    }

    public static boolean isNight(Context c) {
        int mask = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 实际生效的主题 id：
     * 跟随系统开时——夜间用所选深色主题（选的是瓷白则回退琥珀夜），日间固定瓷白玻璃；
     * 关闭时就是所选主题本身。
     */
    public static String effectiveId(Context c) {
        String sel = currentId(c);
        if (!isFollowSystem(c)) return sel;
        if (isNight(c)) return "porcelain".equals(sel) ? "amber" : sel;
        return "porcelain";
    }

    public static Def effective(Context c) {
        String id = effectiveId(c);
        for (Def d : DEFS) if (d.id.equals(id)) return d;
        return DEFS[0];
    }

    public static void setTheme(Context c, String id) {
        prefs(c).edit().putString("theme_id", id).putBoolean("theme_dirty", true).apply();
    }

    public static boolean consumeDirty(Context c) {
        boolean d = prefs(c).getBoolean("theme_dirty", false);
        // 跟随系统时，系统日夜切换也会让已应用主题过期 -> 视为 dirty 触发重建
        if (!d && isFollowSystem(c)) {
            String applied = prefs(c).getString("applied_id", "");
            if (!effectiveId(c).equals(applied)) d = true;
        }
        if (d) prefs(c).edit().putBoolean("theme_dirty", false).apply();
        return d;
    }

    /** 在 setContentView 之前调用 */
    public static void apply(Activity a) {
        Def def = effective(a);
        prefs(a).edit().putString("applied_id", def.id).apply();
        a.setTheme(def.styleRes);
    }

    public static int color(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
