package com.guga.music;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

public class ThemeUtil {

    public static class Def {
        public final String id, name;
        public final int styleRes, bg, surface, accent;
        public final int[] grad; // 主题渐变色带（强调色 UI 的实际渲染：填充与文字都走它，不再是纯色）
        Def(String id, String name, int styleRes, int bg, int surface, int accent, int[] grad) {
            this.id = id; this.name = name; this.styleRes = styleRes;
            this.bg = bg; this.surface = surface; this.accent = accent;
            this.grad = grad;
        }
    }

    public static final Def[] DEFS = {
            new Def("pink", "哔哩粉", R.style.ThemePink, 0xFF171218, 0xFF2A2029, 0xFFFB7299,
                    new int[]{0xFFFB7299, 0xFF56B6F7}),
            new Def("sky", "天空蓝", R.style.ThemeSky, 0xFF0E1520, 0xFF1B2839, 0xFF56B6F7,
                    new int[]{0xFF56B6F7, 0xFF3ECF8E}),
            new Def("mint", "薄荷绿", R.style.ThemeMint, 0xFF0D1712, 0xFF192920, 0xFF4CC38A,
                    new int[]{0xFF4CC38A, 0xFF4CC3FF}),
            new Def("grape", "紫电葡萄", R.style.ThemeGrape, 0xFF150A24, 0xFF2A1845, 0xFFB26BFF,
                    new int[]{0xFFB26BFF, 0xFFFF4FD8}),
            new Def("aurora", "极光穹顶", R.style.ThemeAurora, 0xFF060D1F, 0xFF111F3E, 0xFF3DFFA2,
                    new int[]{0xFF3DFFA2, 0xFF3FA9FF, 0xFF7A5CFF}),
            new Def("matcha", "抹茶玄米", R.style.ThemeMatcha, 0xFF10160B, 0xFF202D14, 0xFFB5E048,
                    new int[]{0xFFB5E048, 0xFF4CC38A}),
            new Def("rainbow", "彩虹桥", R.style.ThemeRainbow, 0xFF0D0D15, 0xFF1E1B2C, 0xFFFF6B6B,
                    new int[]{0xFFFF5E5E, 0xFFFFB03A, 0xFFF7E74B, 0xFF3DFFA2, 0xFF3FA9FF, 0xFFB26BFF}),
            new Def("material", "Material You", R.style.ThemeMaterial, 0xFF0D0D12, 0xFF1E1E26, 0xFFA8B6FF,
                    new int[]{0xFFA8B6FF, 0xFFD3BFFF}),
    };

    /** 已下线主题（琥珀夜/瓷白）的存量设置一律回退到默认哔哩粉 */
    private static boolean valid(String id) {
        for (Def d : DEFS) if (d.id.equals(id)) return true;
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    public static String currentId(Context c) {
        String id = prefs(c).getString("theme_id", "pink");
        return valid(id) ? id : "pink";
    }

    public static Def current(Context c) {
        String id = currentId(c);
        for (Def d : DEFS) if (d.id.equals(id)) return d;
        return DEFS[0];
    }

    /** 实际生效的主题 id = 所选主题（瓷白下线后不再有日间浅色主题，跟随系统一并收口） */
    public static String effectiveId(Context c) {
        return currentId(c);
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
        // 所选主题与已应用的不一致（如存量主题被下线回退）也要重建
        if (!d) {
            String applied = prefs(c).getString("applied_id", "");
            if (!applied.isEmpty() && !effectiveId(c).equals(applied)) d = true;
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

    // ---------------- 渐变渲染（v1.24.1：主题不再只是纯色强调） ----------------
    public static int[] gradColors(Context c) { return effective(c).grad; }

    /** 主题渐变填充：cornerDp>=999 为椭圆（播放键/进度圆钮），否则圆角矩形 */
    public static GradientDrawable accentGradient(Context c, float cornerDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, effective(c).grad);
        if (cornerDp >= 999) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(cornerDp * c.getResources().getDisplayMetrics().density);
        return g;
    }

    /** 文字渐变：给 TextView 的画笔挂主题渐变着色器；按 gravity 算文本起始 x，短标签也从色带起点着色 */
    public static void gradientText(TextView tv) {
        if (tv == null) return;
        CharSequence t = tv.getText();
        float w = tv.getPaint().measureText(t == null ? "" : t.toString());
        if (w <= 0) return;
        float start = tv.getPaddingLeft();
        int avail = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
        if (avail > w) {
            int g = tv.getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
            if (g == android.view.Gravity.CENTER_HORIZONTAL) start += (avail - w) / 2f;
            else if (g == android.view.Gravity.RIGHT || g == android.view.Gravity.END) start += avail - w;
        }
        LinearGradient lg = new LinearGradient(0, 0, w, 0,
                effective(tv.getContext()).grad, null, Shader.TileMode.CLAMP);
        if (start != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(start, 0);
            lg.setLocalMatrix(m);
        }
        tv.getPaint().setShader(lg);
        tv.invalidate();
    }

    /** 渐变图标：把矢量图标的形状填成主题渐变（播放/暂停键用，保持字形本身着色、无底圆） */
    public static android.graphics.drawable.Drawable gradientIcon(android.content.Context c, int resId, int sizeDp) {
        int px = (int) (sizeDp * c.getResources().getDisplayMetrics().density);
        android.graphics.drawable.Drawable icon = c.getDrawable(resId);
        android.graphics.Bitmap shape = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas sc = new android.graphics.Canvas(shape);
        icon.setTint(0xFFFFFFFF);
        icon.setBounds(0, 0, px, px);
        icon.draw(sc);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas oc = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, px, px, effective(c).grad, null, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, px, px, p);
        p.setShader(null);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        oc.drawBitmap(shape, 0, 0, p);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), out);
    }

    /** 还原纯色文字（列表复用时给非当前项用） */
    public static void plainText(TextView tv, int color) {
        if (tv == null) return;
        tv.getPaint().setShader(null);
        tv.setTextColor(color);
    }

    public static int color(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
