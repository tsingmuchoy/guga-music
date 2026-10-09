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

    /** 滚动棘轮触感：列表滚动时每划过一个条目轻震一下，像拨齿轮「划起来」的手感。
     *  只在滚动未静止时触发，45ms 节流防震成一片；走总开关（设置里关了就静默）。
     *  注意：目标列表若已有自己的 OnScrollListener 不要用这个（会被覆盖），在那边内联同样的逻辑 */
    /** 内容滚动棘轮（ScrollView 版）：手指滑动时每滚过约 48dp 轻震一下。
     *  只认「手指按下开始」的滚动会话（停滚 300ms 视为结束，惯性段包含在内），
     *  程序自动滚动不会触发；总开关关了自动静默 */
    public static void attachScrollRatchet(final android.widget.ScrollView sv) {
        if (sv == null) return;
        final float den = sv.getResources().getDisplayMetrics().density;
        final int step = (int) (48 * den);
        final boolean[] live = {false};
        final int[] lastY = {0};
        final int[] acc = {0};
        final Runnable[] stopper = new Runnable[1];
        stopper[0] = () -> live[0] = false;
        sv.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                live[0] = true;
                lastY[0] = sv.getScrollY();
                acc[0] = 0;
                sv.removeCallbacks(stopper[0]);
            }
            return false; // 只观察、不拦截，滚动照常
        });
        sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            if (!live[0]) return;
            acc[0] += Math.abs(sy - lastY[0]);
            lastY[0] = sy;
            sv.removeCallbacks(stopper[0]);
            sv.postDelayed(stopper[0], 300);
            if (acc[0] >= step) {
                acc[0] = 0;
                tick(sv.getContext());
            }
        });
    }

    /** 给整页接棘轮：找到页面里第一个 ScrollView 并挂上（设置类页面通用） */
    public static void ratchetPage(android.app.Activity a) {
        ratchetView(a.findViewById(android.R.id.content));
    }

    public static void ratchetView(android.view.View root) {
        android.widget.ScrollView sv = findScroll(root);
        if (sv != null) attachScrollRatchet(sv);
    }

    private static android.widget.ScrollView findScroll(android.view.View v) {
        if (v == null) return null;
        if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ScrollView r = findScroll(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    public static void attachRatchet(final android.widget.AbsListView lv) {
        final int[] lastIdx = {-1};
        final long[] lastAt = {0};
        final int[] state = {android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE};
        lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) { state[0] = s; }
            @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                if (state[0] != SCROLL_STATE_IDLE && lastIdx[0] >= 0 && first != lastIdx[0]) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastAt[0] >= 45) {
                        lastAt[0] = now;
                        tick(lv.getContext());
                    }
                }
                lastIdx[0] = first;
            }
        });
    }
}
