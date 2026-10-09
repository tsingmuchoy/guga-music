package com.guga.music;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 「加入本地歌单」底部玻璃弹窗 + 新建歌单输入卡（与 App 玻璃风统一，多处复用） */
public class PlaylistPicker {

    public static void show(final Activity act, final Track track) {
        if (track == null) return;
        final LocalDb db = new LocalDb(act);
        final List<LocalDb.Playlist> pls = db.playlists();

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(act).inflate(R.layout.dialog_playlist_picker, null);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        LinearLayout box = root.findViewById(R.id.llPickerItems);
        for (final LocalDb.Playlist pl : pls) {
            box.addView(row(act, pl.name, pl.count + " 首", R.drawable.ic_note, v -> {
                dlg.dismiss();
                addToast(act, db.addTrack(pl.id, track), pl.name);
            }));
        }
        box.addView(row(act, "＋ 新建歌单…", "", 0, v -> {
            dlg.dismiss();
            createDialog(act, name -> {
                long id = db.createPlaylist(name);
                addToast(act, db.addTrack(id, track), name);
            });
        }));
        root.findViewById(R.id.btnPickerCancel).setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    private static View row(Activity act, String main, String sub, int icon, View.OnClickListener cb) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        TextView tv = new TextView(act);
        tv.setLayoutParams(lp);
        if (icon != 0) IconUtil.leading(tv, icon, R.attr.gAccent);
        tv.setPadding(6, 30, 6, 30);
        tv.setText(sub.isEmpty() ? main : main + "   ·   " + sub);
        tv.setTextSize(15.5f);
        tv.setTextColor(ThemeUtil.color(act, R.attr.gTextPri));
        tv.setOnClickListener(cb);
        return tv;
    }

    private static void addToast(Activity act, boolean added, String name) {
        Toast.makeText(act, added ? "已加入「" + name + "」🎵" : "这首歌已经在「" + name + "」里啦",
                Toast.LENGTH_SHORT).show();
    }

    public interface OnName { void onName(String name); }

    public static void createDialog(final Activity act, final OnName cb) {
        createDialog(act, "新建歌单", "", "创建", cb);
    }

    /** 玻璃风输入卡（新建 / 重命名共用） */
    public static void createDialog(final Activity act, String title, String prefill,
                                    String okText, final OnName cb) {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(act).inflate(R.layout.dialog_input, null);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.86);
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) root.findViewById(R.id.tvInputTitle)).setText(title);
        final EditText et = root.findViewById(R.id.etInput);
        if (prefill != null && !prefill.isEmpty()) {
            et.setText(prefill);
            et.setSelection(prefill.length());
        }
        TextView ok = root.findViewById(R.id.btnInputOk);
        ok.setText(okText);
        root.findViewById(R.id.btnInputCancel).setOnClickListener(v -> dlg.dismiss());
        ok.setOnClickListener(v -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(act, "名字不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            cb.onName(name);
        });
        dlg.show();
    }
}
