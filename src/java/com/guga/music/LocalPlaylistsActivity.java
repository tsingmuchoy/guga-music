package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 本地歌单列表：新建 / 进入 / 长按重命名或删除 */
public class LocalPlaylistsActivity extends Activity {

    private LocalDb db;
    private List<LocalDb.Playlist> pls = new ArrayList<>();
    private ListView lv;
    private TextView tvEmpty;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return pls.size(); }
        @Override public Object getItem(int p) { return pls.get(p); }
        @Override public long getItemId(int p) { return pls.get(p).id; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(LocalPlaylistsActivity.this).inflate(R.layout.item_folder, parent, false);
            LocalDb.Playlist pl = pls.get(p);
            ((TextView) cv.findViewById(R.id.tvFolderTitle)).setText("🎵 " + pl.name);
            ((TextView) cv.findViewById(R.id.tvFolderCount)).setText(pl.count + " 首");
            return cv;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_local_playlists);
        db = new LocalDb(this);
        lv = findViewById(R.id.lvPlaylists);
        tvEmpty = findViewById(R.id.tvEmptyPlaylists);
        lv.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnNew).setOnClickListener(v ->
                PlaylistPicker.createDialog(this, name -> {
                    db.createPlaylist(name);
                    reload();
                }));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            LocalDb.Playlist pl = pls.get(pos);
            Intent it = new Intent(this, LocalPlaylistActivity.class);
            it.putExtra("pid", pl.id);
            it.putExtra("name", pl.name);
            startActivity(it);
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            final LocalDb.Playlist pl = pls.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(pl.name)
                    .setItems(new String[]{"✏️ 重命名", "🗑️ 删除歌单"}, (d, w) -> {
                        if (w == 0) {
                            PlaylistPicker.createDialog(this, name -> {
                                db.renamePlaylist(pl.id, name);
                                reload();
                            });
                        } else {
                            new AlertDialog.Builder(this)
                                    .setMessage("删除歌单「" + pl.name + "」？里面的歌曲记录会一起删掉（不影响 B 站收藏夹）")
                                    .setPositiveButton("删除", (d2, w2) -> {
                                        db.deletePlaylist(pl.id);
                                        reload();
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    })
                    .show();
            return true;
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (ThemeUtil.consumeDirty(this)) { recreate(); return; }
        reload();
    }

    private void reload() {
        pls = db.playlists();
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(pls.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
