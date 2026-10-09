package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 全 App 统一弹窗：自绘深色圆角卡（bg_dialog），不许再用系统灰底弹窗（用户明令：不要大灰块） */
public class UiDialog {

    public interface Pick { void onPick(int idx); }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static int accent(Activity a) {
        TypedValue tv = new TypedValue();
        if (a.getTheme().resolveAttribute(R.attr.gAccent, tv, true)) return tv.data;
        return 0xFFFB7299;
    }

    private static LinearLayout card(Activity a) {
        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundResource(R.drawable.bg_dialog);
        ll.setPadding(dp(a, 22), dp(a, 20), dp(a, 22), dp(a, 16));
        return ll;
    }

    private static TextView titleView(Activity a, String t) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private static AlertDialog show(Activity a, View root) {
        AlertDialog d = new AlertDialog.Builder(a).setView(root).create();
        d.show();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    /** 列表菜单：标题 + 选项行，点了回调序号 */
    public static void menu(final Activity a, String title, String[] items, final Pick cb) {
        LinearLayout ll = card(a);
        if (title != null && !title.isEmpty()) {
            TextView t = titleView(a, title);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(a, 8);
            ll.addView(t, lp);
        }
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView row = new TextView(a);
            row.setText(items[i]);
            row.setTextColor(0xFFF2F2F7);
            row.setTextSize(15.5f);
            row.setPadding(dp(a, 2), dp(a, 13), dp(a, 2), dp(a, 13));
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                cb.onPick(idx);
            });
            ll.addView(row);
        }
        holder[0] = show(a, ll);
    }

    /** 确认框：标题 + 说明（可空） + 取消/确认两键 */
    public static void confirm(final Activity a, String title, String msg, String okText, final Runnable ok) {
        LinearLayout ll = card(a);
        ll.addView(titleView(a, title));
        if (msg != null && !msg.isEmpty()) {
            TextView m = new TextView(a);
            m.setText(msg);
            m.setTextColor(0xFFC9C9D4);
            m.setTextSize(14f);
            m.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            ll.addView(m, lp);
        }
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(a, 18);
        ll.addView(row, rlp);
        final AlertDialog[] holder = new AlertDialog[1];
        TextView cancel = btn(a, "取消", 0xFFC9C9D4);
        cancel.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); });
        row.addView(cancel);
        TextView okb = btn(a, okText, accent(a));
        okb.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.leftMargin = dp(a, 26);
        okb.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            ok.run();
        });
        row.addView(okb, olp);
        holder[0] = show(a, ll);
    }

    private static TextView btn(Activity a, String t, int color) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextColor(color);
        tv.setTextSize(15f);
        tv.setPadding(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6));
        return tv;
    }
}
