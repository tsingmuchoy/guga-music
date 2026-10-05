package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;

/** 「加入本地歌单」选择器 + 新建歌单对话框（多处复用） */
public class PlaylistPicker {

    public static void show(final Activity act, final Track track) {
        if (track == null) return;
        final LocalDb db = new LocalDb(act);
        final List<LocalDb.Playlist> pls = db.playlists();
        String[] names = new String[pls.size() + 1];
        for (int i = 0; i < pls.size(); i++) names[i] = "🎵 " + pls.get(i).name + "（" + pls.get(i).count + " 首）";
        names[pls.size()] = "＋ 新建歌单…";
        new AlertDialog.Builder(act)
                .setTitle("加入本地歌单")
                .setItems(names, (d, w) -> {
                    if (w == pls.size()) {
                        createDialog(act, name -> {
                            long id = db.createPlaylist(name);
                            addToast(act, db.addTrack(id, track), name);
                        });
                    } else {
                        addToast(act, db.addTrack(pls.get(w).id, track), pls.get(w).name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void addToast(Activity act, boolean added, String name) {
        Toast.makeText(act, added ? "已加入「" + name + "」🎵" : "这首歌已经在「" + name + "」里啦",
                Toast.LENGTH_SHORT).show();
    }

    public interface OnName { void onName(String name); }

    public static void createDialog(final Activity act, final OnName cb) {
        final EditText et = new EditText(act);
        et.setHint("给歌单起个名字");
        et.setSingleLine(true);
        new AlertDialog.Builder(act)
                .setTitle("新建歌单")
                .setView(et)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(act, "名字不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cb.onName(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
