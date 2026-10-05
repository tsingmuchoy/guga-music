package com.guga.music;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 全屏歌词页：自动滚动跟随播放，点任意一行跳到那句 */
public class LyricsActivity extends Activity {

    private TextView tvSong, tvSource, tvEmpty, btnFix;
    private ListView lv;
    private List<Lyrics.Line> lines = new ArrayList<>();
    private int curIdx = -2;
    private long lastUserScrollAt = 0;
    private final Handler handler = new Handler();

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            PlayerService s = PlayerService.get();
            if (s != null && !lines.isEmpty()) {
                int idx = Lyrics.indexAt(lines, s.getPosition());
                if (idx != curIdx) {
                    curIdx = idx;
                    adapter.notifyDataSetChanged();
                    if (System.currentTimeMillis() - lastUserScrollAt > 2500 && idx >= 0) {
                        lv.setSelectionFromTop(idx, Math.max(0, lv.getHeight() / 3));
                    }
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_lyrics);
        tvSong = findViewById(R.id.tvSongTitle);
        tvSource = findViewById(R.id.tvSource);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnFix = findViewById(R.id.btnFixLyrics);
        lv = findViewById(R.id.lvLyrics);
        lv.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        lv.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                if (state != SCROLL_STATE_IDLE) lastUserScrollAt = System.currentTimeMillis();
            }
            @Override public void onScroll(AbsListView v, int f, int c, int t) {}
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            PlayerService s = PlayerService.get();
            if (s != null && pos < lines.size()) {
                s.seekTo((int) lines.get(pos).timeMs);
                lastUserScrollAt = 0;
            }
        });

        PlayerService s = PlayerService.get();
        if (s == null || s.current() == null) {
            tvEmpty.setText("现在没有在播的歌");
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        final Track t = s.current();
        tvSong.setText(t.title);
        tvSource.setText("歌词加载中…");
        loadLyrics(t, false);
        btnFix.setOnClickListener(v -> {
            btnFix.setVisibility(View.GONE);
            tvSource.setText("正在换一版歌词…");
            loadLyrics(t, true);
        });
    }

    private void loadLyrics(final Track t, boolean next) {
        BiliApi api = new BiliApi(getApplicationContext());
        Lyrics.Cb cb = r -> {
            if (r.has()) {
                lines = r.lines;
                tvEmpty.setVisibility(View.GONE);
                tvSource.setText("来源：" + r.source + "（点任意一行可跳转）");
                btnFix.setVisibility(r.source.contains("网易云") ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
                curIdx = -2;
                if (next) android.widget.Toast.makeText(this, "已换一版歌词 🎵", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                tvSource.setText("");
                btnFix.setVisibility(View.GONE);
                tvEmpty.setText(next ? "没有其他候选版本了 😢" : "这首歌暂时没找到歌词 😢");
                tvEmpty.setVisibility(View.VISIBLE);
            }
        };
        if (next) Lyrics.refetchNext(this, t, api, cb);
        else Lyrics.fetchFor(this, t, api, cb);
    }

    @Override protected void onResume() {
        super.onResume();
        if (ThemeUtil.consumeDirty(this)) { recreate(); return; }
        handler.post(ticker);
    }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return lines.size(); }
        @Override public Object getItem(int p) { return lines.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(LyricsActivity.this).inflate(R.layout.row_lyric, parent, false);
            TextView tv = (TextView) cv;
            tv.setText(lines.get(p).text);
            boolean cur = p == curIdx;
            tv.setTextColor(ThemeUtil.color(LyricsActivity.this, cur ? R.attr.gAccent : R.attr.gTextSec));
            tv.setTextSize(cur ? 17 : 15);
            tv.setTypeface(null, cur ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            return cv;
        }
    };
}
