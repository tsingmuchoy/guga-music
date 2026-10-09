package com.guga.music;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** 3D 触感（触觉反馈）：轻点/确认/重压三档，走系统预置振动效果，设置里可关 */
public class Haptics {

    private static final String KEY = "haptics_on";

    public static boolean isOn(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setOn(Context c, boolean on) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    private static void fire(Context c, int predefined, long ms, int amp) {
        if (c == null || !isOn(c)) return;
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(predefined));
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp));
            }
        } catch (Exception ignored) {}
    }

    /** 轻触：列表点选、标签切换、普通按钮 */
    public static void tick(Context c) { fire(c, VibrationEffect.EFFECT_TICK, 12, 60); }

    /** 确认：播放/暂停、切主题、开关切换 */
    public static void press(Context c) { fire(c, VibrationEffect.EFFECT_CLICK, 20, 120); }

    /** 重压：长按类操作 */
    public static void heavy(Context c) { fire(c, VibrationEffect.EFFECT_HEAVY_CLICK, 30, 180); }
}
