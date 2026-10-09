package com.guga.music;

import android.app.Activity;
import android.content.Intent;
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

    private TextView tvSong, tvSource, tvEmpty, btnFix, btnManual, btnOffset;
    private Track curTrack;
    private int curOffsetMs = 0;
    private ListView lv;
    private List<Lyrics.Line> lines = new ArrayList<>();
    private int curIdx = -2;
    private long lastUserScrollAt = 0;
    private boolean lyrUserDriven;
    private int lyrIdx = -1;
    private long lyrAt;
    private final Handler handler = new Handler();

    // 逐字扫光专用快刷：40ms 只更新当前行 SyllableView 的进度（不重绑列表）
    private final Runnable wordTicker = new Runnable() {
        @Override public void run() {
            PlayerService s = PlayerService.get();
            if (s != null && lv != null) {
                long pos = s.getPosition() - curOffsetMs;
                for (int i = 0; i < lv.getChildCount(); i++) {
                    Object tag = lv.getChildAt(i).getTag();
                    if (tag instanceof SyllableView) {
                        SyllableView sv = (SyllableView) tag;
                        if (sv.getVisibility() == View.VISIBLE) sv.setPosition(pos);
                    }
                }
            }
            handler.postDelayed(this, 40);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            PlayerService s = PlayerService.get();
            if (s != null && !lines.isEmpty()) {
                int idx = Lyrics.indexAt(lines, s.getPosition() - curOffsetMs);
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
        btnManual = findViewById(R.id.btnManualLyrics);
        btnOffset = findViewById(R.id.btnOffsetLyrics);
        lv = findViewById(R.id.lvLyrics);
        lv.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        lv.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                if (state != SCROLL_STATE_IDLE) lastUserScrollAt = System.currentTimeMillis();
                // 棘轮触感只认手指驱动：触摸滚动置真、静止复位；自动跟随是 setSelectionFromTop 直跳、不经触摸态
                if (state == SCROLL_STATE_TOUCH_SCROLL) lyrUserDriven = true;
                else if (state == SCROLL_STATE_IDLE) lyrUserDriven = false;
            }
            @Override public void onScroll(AbsListView v, int f, int c, int t) {
                if (lyrUserDriven && lyrIdx >= 0 && f != lyrIdx) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lyrAt >= 45) { lyrAt = now; Haptics.tick(LyricsActivity.this); }
                }
                lyrIdx = f;
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            PlayerService s = PlayerService.get();
            if (s != null && pos < lines.size()) {
                s.seekTo((int) lines.get(pos).timeMs + curOffsetMs);
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
        curTrack = t;
        curOffsetMs = Lyrics.offsetOf(this, t.bvid);
        btnOffset.setVisibility(View.VISIBLE);
        refreshOffsetBtn();
        btnOffset.setOnClickListener(v -> showOffsetDialog());
        btnManual.setVisibility(View.VISIBLE);
        btnManual.setOnClickListener(v -> {
            Intent it = new Intent(this, LyricsSearchActivity.class);
            it.putExtra("bvid", t.bvid);
            it.putExtra("title", t.title);
            it.putExtra("dur", t.durationSec);
            startActivityForResult(it, 7201);
        });
        tvSong.setText(Lyrics.displayName(this, t));
        tvSource.setText("歌词加载中…");
        loadLyrics(t, false);
        btnFix.setOnClickListener(v -> {
            btnFix.setVisibility(View.GONE);
            tvSource.setText("正在换一版歌词…");
            loadLyrics(t, true);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // 手动搜索页里锁定/解锁了歌词：回来立刻按新结果重载
        if (req == 7201 && res == RESULT_OK && curTrack != null) loadLyrics(curTrack, false);
    }

    private void refreshOffsetBtn() {
        if (btnOffset == null) return;
        btnOffset.setText(curOffsetMs == 0 ? "歌词偏移 ⏱"
                : "歌词偏移 ⏱（" + (curOffsetMs > 0 ? "+" : "")
                + String.format(java.util.Locale.CHINA, "%.1f", curOffsetMs / 1000.0) + "s）");
    }

    private String fmtOff(int ms) {
        if (ms == 0) return "0 ms（无偏移）";
        return (ms > 0 ? "+" : "") + String.format(java.util.Locale.CHINA, "%.1f", ms / 1000.0) + " s";
    }

    private void applyOffset(int ms) {
        curOffsetMs = ms;
        if (curTrack != null) Lyrics.setOffset(this, curTrack.bvid, ms);
        refreshOffsetBtn();
        curIdx = -2; // 强制歌词 ticker 按新偏移重绑当前行
    }

    /** 歌词偏移调整：滑杆 ±10s（步进 0.1s）+ 微调键，改动即时生效并按视频绑定保存 */
    private void showOffsetDialog() {
        if (curTrack == null) return;
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        root.setPadding((int) (22 * dp), (int) (20 * dp), (int) (22 * dp), (int) (16 * dp));
        TextView title = new TextView(this);
        title.setText("歌词偏移（绑定这支视频）");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(16.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        final TextView val = new TextView(this);
        val.setText(fmtOff(curOffsetMs));
        val.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
        val.setTextSize(20);
        val.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams vlp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = (int) (14 * dp);
        vlp.bottomMargin = (int) (2 * dp);
        root.addView(val, vlp);
        TextView hint = new TextView(this);
        hint.setText("正数 = 歌词延后出现 · 负数 = 提前；拖动即时生效");
        hint.setTextColor(0xFFC9C9D4);
        hint.setTextSize(12);
        hint.setGravity(android.view.Gravity.CENTER);
        root.addView(hint);
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(200);
        sb.setProgress(curOffsetMs / 100 + 100);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = (int) (10 * dp);
        root.addView(sb, slp);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int prog, boolean fromUser) {
                if (!fromUser) return;
                applyOffset((prog - 100) * 100);
                val.setText(fmtOff(curOffsetMs));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        android.widget.LinearLayout qrow = new android.widget.LinearLayout(this);
        qrow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        qrow.setGravity(android.view.Gravity.CENTER);
        String[] qs = {"-0.5s", "-0.1s", "+0.1s", "+0.5s"};
        int[] qv = {-500, -100, 100, 500};
        for (int i = 0; i < qs.length; i++) {
            final int dv = qv[i];
            TextView b = new TextView(this);
            b.setText(qs[i]);
            b.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            b.setTextSize(14);
            b.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
            b.setOnClickListener(v -> {
                int ms = Math.max(-10000, Math.min(10000, curOffsetMs + dv));
                applyOffset(ms);
                sb.setProgress(ms / 100 + 100);
                val.setText(fmtOff(curOffsetMs));
            });
            qrow.addView(b);
        }
        root.addView(qrow);
        android.widget.LinearLayout brow = new android.widget.LinearLayout(this);
        brow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        brow.setGravity(android.view.Gravity.END);
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = (int) (12 * dp);
        root.addView(brow, blp);
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];
        TextView reset = new TextView(this);
        reset.setText("重置");
        reset.setTextColor(0xFFC9C9D4);
        reset.setTextSize(15);
        reset.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        reset.setOnClickListener(v -> {
            applyOffset(0);
            sb.setProgress(100);
            val.setText(fmtOff(0));
        });
        brow.addView(reset);
        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
        done.setTextSize(15);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = (int) (26 * dp);
        done.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); });
        brow.addView(done, dlp);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this).setView(root).create();
        holder[0] = dlg;
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
    }

    private void loadLyrics(final Track t, boolean next) {
        BiliApi api = new BiliApi(getApplicationContext());
        Lyrics.Cb cb = r -> {
            if (r.has()) {
                lines = r.lines;
                tvEmpty.setVisibility(View.GONE);
                tvSource.setText("来源：" + r.source + "（点任意一行可跳转）");
                btnFix.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
                curIdx = -2;
                if (next) android.widget.Toast.makeText(this, "已换一版歌词 🎵", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                tvSource.setText("");
                btnFix.setVisibility(View.GONE);
                tvEmpty.setText(next ? "没有其他候选版本了 😢" : "网易云 / LRCLIB / 酷狗 / 视频字幕都找过了，暂时没找到这首歌的歌词 😢");
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
        handler.post(wordTicker);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(wordTicker);
        super.onPause();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return lines.size(); }
        @Override public Object getItem(int p) { return lines.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(LyricsActivity.this).inflate(R.layout.row_lyric, parent, false);
            Lyrics.Line line = lines.get(p);
            TextView tv = cv.findViewById(R.id.tvLyricMain);
            boolean cur = p == curIdx;
            // 逐字模式：当前行且这行有逐字轴时，用 SyllableView 逐字扫光替代普通文本；
            // 没真逐字且用户开了「模拟扫字」时，按行时长现算一份模拟轴兜底（真数据永远优先）
            List<Lyrics.Word> wlist = line.words;
            if ((wlist == null || wlist.size() < 2) && Lyrics.isSimWords(LyricsActivity.this)) {
                long nextMs = p + 1 < lines.size() ? lines.get(p + 1).timeMs : line.timeMs + 6000;
                wlist = Lyrics.simWords(line, nextMs);
            }
            boolean useWords = cur && Lyrics.isShowWords(LyricsActivity.this)
                    && wlist != null && wlist.size() >= 2;
            Object tag = cv.getTag();
            SyllableView sv = tag instanceof SyllableView ? (SyllableView) tag : null;
            if (useWords) {
                if (sv == null) {
                    sv = new SyllableView(LyricsActivity.this);
                    sv.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    ((ViewGroup) cv).addView(sv, 0);
                    cv.setTag(sv);
                }
                sv.setVisibility(View.VISIBLE);
                sv.setWords(wlist, 17, ThemeUtil.color(LyricsActivity.this, R.attr.gTextSec),
                        ThemeUtil.gradColors(LyricsActivity.this));
                PlayerService ps = PlayerService.get();
                if (ps != null) sv.setPosition(ps.getPosition() - curOffsetMs);
                tv.setVisibility(View.GONE);
            } else {
                if (sv != null) sv.setVisibility(View.GONE);
                tv.setVisibility(View.VISIBLE);
                tv.setText(line.text);
                if (cur) ThemeUtil.gradientText(tv);
                else ThemeUtil.plainText(tv, ThemeUtil.color(LyricsActivity.this, R.attr.gTextSec));
                tv.setTextSize(cur ? 17 : 15);
                tv.setTypeface(null, cur ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            // 副行：罗马音（小、淡）与中文翻译（略大、次级色），开关关了或这行没数据就不占位
            TextView tvr = cv.findViewById(R.id.tvLyricRoma);
            boolean sr = Lyrics.isShowRoma(LyricsActivity.this) && line.roma != null && !line.roma.isEmpty();
            tvr.setVisibility(sr ? View.VISIBLE : View.GONE);
            if (sr) {
                tvr.setText(line.roma);
                // 当前行：原词/罗马音/译文一起亮（副行提亮+略放大），非当前行恢复原淡色
                tvr.setTextColor(ThemeUtil.color(LyricsActivity.this, cur ? R.attr.gTextSec : R.attr.gTextFaint));
                tvr.setTextSize(cur ? 12.5f : 11.5f);
            }
            TextView tvt = cv.findViewById(R.id.tvLyricTrans);
            boolean st = Lyrics.isShowTrans(LyricsActivity.this) && line.trans != null && !line.trans.isEmpty();
            tvt.setVisibility(st ? View.VISIBLE : View.GONE);
            if (st) {
                tvt.setText(line.trans);
                tvt.setTextColor(ThemeUtil.color(LyricsActivity.this, cur ? R.attr.gTextPri : R.attr.gTextSec));
                tvt.setTextSize(cur ? 13.5f : 12.5f);
            }
            return cv;
        }
    };
}
