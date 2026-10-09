package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 手动搜索歌词：自己填歌名/歌手，跨各歌词源搜候选，点中预览后确认锁定（按 BV 存 lyrics.db） */
public class LyricsSearchActivity extends Activity {

    private String bvid, videoTitle;
    private int durSec;
    private EditText etName, etArtist;
    private TextView tvStatus, btnAuto;
    private ListView lv;
    private final List<Lyrics.MCand> results = new ArrayList<>();
    private boolean searching;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int p) { return results.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(LyricsSearchActivity.this).inflate(R.layout.item_lyric_cand, parent, false);
            Lyrics.MCand c = results.get(p);
            ((TextView) cv.findViewById(R.id.tvCandName)).setText(c.name);
            String dur = c.durMs > 0 ? " · " + (c.durMs / 60000) + ":" + String.format(java.util.Locale.CHINA, "%02d", (c.durMs % 60000) / 1000) : "";
            ((TextView) cv.findViewById(R.id.tvCandSub)).setText(
                    (c.artist == null || c.artist.isEmpty() ? "未知歌手" : c.artist) + dur + " · " + Lyrics.srcName(c.src));
            return cv;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_lyrics_search);
        Intent it = getIntent();
        bvid = it.getStringExtra("bvid");
        videoTitle = it.getStringExtra("title");
        durSec = it.getIntExtra("dur", 0);
        if (bvid == null) { finish(); return; }

        findViewById(R.id.btnBackSearch).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tvLyrCur)).setText("当前视频：" + (videoTitle == null ? bvid : videoTitle));
        etName = findViewById(R.id.etSongName);
        etArtist = findViewById(R.id.etArtist);
        tvStatus = findViewById(R.id.tvLyrStatus);
        btnAuto = findViewById(R.id.btnAutoRestore);
        lv = findViewById(R.id.lvCand);
        lv.setAdapter(adapter);
        Haptics.attachRatchet(lv);

        String guessName = StatsDb.extractSongName(videoTitle == null ? "" : videoTitle);
        etName.setText(guessName.isEmpty() ? (videoTitle == null ? "" : videoTitle) : guessName);
        etArtist.setText(StatsDb.extractSinger(videoTitle == null ? "" : videoTitle));

        if (Lyrics.isBound(this, bvid)) {
            btnAuto.setVisibility(View.VISIBLE);
            tvStatus.setText("这首歌的歌词已手动锁定 🔒 重新选一版可覆盖，或恢复自动匹配");
        }
        btnAuto.setOnClickListener(v -> {
            Haptics.tick(this);
            Lyrics.clearManual(this, bvid);
            Toast.makeText(this, "已恢复自动匹配", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
        findViewById(R.id.btnDoSearch).setOnClickListener(v -> { Haptics.tick(this); doSearch(); });
        lv.setOnItemClickListener((p, v, pos, id) -> preview(results.get(pos)));
        doSearch(); // 进页面先按预填词自动搜一次
    }

    private void doSearch() {
        if (searching) return;
        final String name = etName.getText().toString().trim();
        final String artist = etArtist.getText().toString().trim();
        if (name.isEmpty()) { Toast.makeText(this, "先填歌名", Toast.LENGTH_SHORT).show(); return; }
        searching = true;
        tvStatus.setText("正在跨源搜索…");
        new Thread(() -> {
            final List<Lyrics.MCand> found = Lyrics.manualSearch(getApplicationContext(), name, artist);
            runOnUiThread(() -> {
                searching = false;
                results.clear();
                results.addAll(found);
                adapter.notifyDataSetChanged();
                tvStatus.setText(found.isEmpty()
                        ? "没搜到候选，改改歌名/歌手再试试（比如去掉括号里的内容）"
                        : "共 " + found.size() + " 个候选，点一个预览歌词");
            });
        }).start();
    }

    private void preview(final Lyrics.MCand c) {
        Haptics.tick(this);
        tvStatus.setText("正在取这版歌词…");
        new Thread(() -> {
            final String lrc = Lyrics.fetchBoundLrc(c);
            final List<Lyrics.Line> lines = lrc == null ? new ArrayList<>() : Lyrics.parseLrc(lrc);
            runOnUiThread(() -> {
                if (lines.size() < 5) {
                    tvStatus.setText("共 " + results.size() + " 个候选，点一个预览歌词");
                    Toast.makeText(this, "这版歌词取不到或没有时间轴，换一版试试", Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(10, lines.size()); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(lines.get(i).text);
                }
                new AlertDialog.Builder(this)
                        .setTitle(c.name + " · " + Lyrics.srcName(c.src))
                        .setMessage(sb.toString())
                        .setNegativeButton("再看看", null)
                        .setPositiveButton("就用这版", (d, w) -> applyChoice(c, lrc))
                        .show();
            });
        }).start();
    }

    private void applyChoice(final Lyrics.MCand c, final String lrc) {
        new Thread(() -> {
            final boolean ok = Lyrics.applyManual(getApplicationContext(), bvid, c, lrc);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, "已锁定这版歌词 ✓ 以后这首歌都用它", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "锁定失败，这版歌词不完整", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
}
