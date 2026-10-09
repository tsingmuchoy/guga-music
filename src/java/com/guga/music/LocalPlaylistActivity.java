package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 本地歌单详情：点歌播放（整单为队列），长按移除 */
public class LocalPlaylistActivity extends Activity {

    private LocalDb db;
    private long pid;
    private List<Track> tracks = new ArrayList<>();
    private ListView lv;
    private TextView tvEmpty;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return tracks.size(); }
        @Override public Object getItem(int p) { return tracks.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(LocalPlaylistActivity.this).inflate(R.layout.item_track, parent, false);
            Track t = tracks.get(p);
            ImgLoader.load(cv.findViewById(R.id.ivCover), t.cover);
            ((TextView) cv.findViewById(R.id.tvTitle)).setText(t.title);
            ((TextView) cv.findViewById(R.id.tvSub)).setText(
                    (t.author == null ? "" : t.author) + " · " + Track.fmtDur(t.durationSec));
            return cv;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_local_playlist);
        db = new LocalDb(this);
        pid = getIntent().getLongExtra("pid", -1);
        String name = getIntent().getStringExtra("name");
        TextView tvListName = (TextView) findViewById(R.id.tvListName);
        tvListName.setText(name == null ? "本地歌单" : name);
        IconUtil.leading(tvListName, R.drawable.ic_note, R.attr.gAccent);
        lv = findViewById(R.id.lvTracks);
        Haptics.attachRatchet(lv);
        tvEmpty = findViewById(R.id.tvEmptyTracks);
        lv.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayAll).setOnClickListener(v -> {
            if (!tracks.isEmpty()) playFrom(0);
            else Toast.makeText(this, "歌单还是空的", Toast.LENGTH_SHORT).show();
        });
        lv.setOnItemClickListener((p, v, pos, id) -> playFrom(pos));
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            final Track t = tracks.get(pos);
            UiDialog.menu(this, t.title, new String[]{"从歌单移除"}, idx -> {
                db.removeTrack(pid, t.bvid);
                reload();
            });
            return true;
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (ThemeUtil.consumeDirty(this)) { recreate(); return; }
        reload();
    }

    private void reload() {
        tracks = db.tracks(pid);
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void playFrom(final int pos) {
        PlayerService.ensureStarted(this);
        final List<Track> queue = new ArrayList<>(tracks);
        new Handler().postDelayed(() -> {
            PlayerService s = PlayerService.get();
            if (s == null) {
                Toast.makeText(this, "播放服务还没起来，再点一次试试", Toast.LENGTH_SHORT).show();
                return;
            }
            s.playQueue(queue, pos);
            startActivity(new Intent(this, PlayerActivity.class));
        }, 350);
    }
}
