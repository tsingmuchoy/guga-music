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
    private final List<Lyrics.MCand> shown = new ArrayList<>();
    private android.widget.LinearLayout llSrcFilter;
    private View hsvSrcFilter;
    private String srcFilter = "";
    private boolean searching;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(LyricsSearchActivity.this).inflate(R.layout.item_lyric_cand, parent, false);
            Lyrics.MCand c = shown.get(p);
            android.widget.ImageView ivc = cv.findViewById(R.id.ivCandCover);
            if (c.cover != null && !c.cover.isEmpty()) {
                ivc.setVisibility(View.VISIBLE);
                ImgLoader.load(ivc, c.cover);
            } else ivc.setVisibility(View.GONE);
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
        llSrcFilter = findViewById(R.id.llSrcFilter);
        hsvSrcFilter = findViewById(R.id.hsvSrcFilter);
        btnAuto = findViewById(R.id.btnAutoRestore);
        lv = findViewById(R.id.lvCand);
        lv.setAdapter(adapter);
        Haptics.attachRatchet(lv);

        String guessName = StatsDb.extractSongName(videoTitle == null ? "" : videoTitle);
        etName.setText(guessName.isEmpty() ? (videoTitle == null ? "" : videoTitle) : guessName);
        etArtist.setText(StatsDb.extractSinger(videoTitle == null ? "" : videoTitle));

        if (Lyrics.isBound(this, bvid)) {
            btnAuto.setVisibility(View.VISIBLE);
            tvStatus.setText("这首歌已手动匹配 🔒 封面与歌词都用你选中的那首；重新选一首可覆盖，或恢复自动匹配");
        }
        btnAuto.setOnClickListener(v -> {
            Haptics.tick(this);
            Lyrics.clearManual(this, bvid);
            Toast.makeText(this, "已恢复自动匹配（封面+歌词）", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
        findViewById(R.id.btnDoSearch).setOnClickListener(v -> { Haptics.tick(this); doSearch(); });
        lv.setOnItemClickListener((p, v, pos, id) -> preview(shown.get(pos)));
        doSearch(); // 进页面先按预填词自动搜一次
    }

    /** 按源筛选（军师建议：候选太多时用户自己选源看）：全部 + 本次有结果的源 */
    private void buildSrcChips() {
        llSrcFilter.removeAllViews();
        hsvSrcFilter.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);
        addSrcChip("全部", "");
        for (String key : Lyrics.sourceOrder(getApplicationContext())) {
            boolean has = false;
            for (Lyrics.MCand c : results) if (c.src.equals(key)) { has = true; break; }
            if (has) addSrcChip(Lyrics.srcName(key), key);
        }
        applyFilter();
    }

    private void addSrcChip(String label, final String key) {
        TextView chip = new TextView(this);
        float den = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, (int) (30 * den));
        lp.setMargins(0, 0, (int) (8 * den), 0);
        chip.setLayoutParams(lp);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding((int) (13 * den), 0, (int) (13 * den), 0);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setSingleLine(true);
        chip.setTag(key);
        chip.setOnClickListener(v -> { Haptics.tick(this); srcFilter = key; styleSrcChips(); applyFilter(); });
        llSrcFilter.addView(chip);
        styleChip(chip, key.equals(srcFilter));
    }

    private void styleChip(TextView chip, boolean on) {
        if (on) {
            chip.setBackground(ThemeUtil.accentGradient(this, 15));
            chip.setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip_pill);
            chip.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            chip.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void styleSrcChips() {
        for (int i = 0; i < llSrcFilter.getChildCount(); i++) {
            TextView chip = (TextView) llSrcFilter.getChildAt(i);
            styleChip(chip, srcFilter.equals(chip.getTag()));
        }
    }

    private void applyFilter() {
        shown.clear();
        for (Lyrics.MCand c : results) if (srcFilter.isEmpty() || c.src.equals(srcFilter)) shown.add(c);
        adapter.notifyDataSetChanged();
        if (!results.isEmpty()) {
            tvStatus.setText(srcFilter.isEmpty()
                    ? "共 " + results.size() + " 个候选，点一个预览（应用后封面+歌词一起换成它）"
                    : Lyrics.srcName(srcFilter) + " 共 " + shown.size() + " 个候选（全部 " + results.size() + "）");
        }
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
                srcFilter = "";
                buildSrcChips();
                if (found.isEmpty()) tvStatus.setText("没搜到候选，改改歌名/歌手再试试（比如去掉括号里的内容）");
            });
        }).start();
    }

    private void preview(final Lyrics.MCand c) {
        Haptics.tick(this);
        tvStatus.setText("正在取这版歌词…");
        new Thread(() -> {
            final Lyrics.LrcPack pack = Lyrics.fetchBoundPack(c);
            final List<Lyrics.Line> lines = pack == null || pack.lrc == null
                    ? new ArrayList<>() : Lyrics.parseLrc(pack.lrc);
            runOnUiThread(() -> {
                if (lines.size() < 5) {
                    // 歌词取不到别一票否决：封面+原歌名还能用，歌词继续自动匹配
                    tvStatus.setText("共 " + shown.size() + " 个候选，点一个预览");
                    View root0 = getLayoutInflater().inflate(R.layout.dialog_lyric_preview, null);
                    ((TextView) root0.findViewById(R.id.tvPrevTitle)).setText(c.name + " · " + Lyrics.srcName(c.src));
                    android.widget.ImageView iv0 = root0.findViewById(R.id.ivPrevCover);
                    if (c.cover != null && !c.cover.isEmpty()) {
                        iv0.setVisibility(View.VISIBLE);
                        ImgLoader.load(iv0, c.cover);
                    } else iv0.setVisibility(View.GONE);
                    TextView note0 = root0.findViewById(R.id.tvPrevCoverNote);
                    note0.setVisibility(View.VISIBLE);
                    note0.setText("这首在" + Lyrics.srcName(c.src) + "的歌词暂时取不到（接口没返回），没法锁定歌词；"
                            + "可以只应用封面和原歌名，歌词继续自动匹配。");
                    root0.findViewById(R.id.svPrev).setVisibility(View.GONE);
                    TextView use0 = root0.findViewById(R.id.btnPrevUse);
                    use0.setText("只用封面+歌名");
                    final AlertDialog dlg0 = new AlertDialog.Builder(this).setView(root0).create();
                    root0.findViewById(R.id.btnPrevBack).setOnClickListener(v -> dlg0.dismiss());
                    use0.setOnClickListener(v -> { dlg0.dismiss(); applyCoverOnly(c); });
                    dlg0.show();
                    if (dlg0.getWindow() != null) dlg0.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    return;
                }
                // 预览：不用系统灰弹窗——自家深色圆角卡，完整歌词放可拖动的滚动区里看全
                View root = getLayoutInflater().inflate(R.layout.dialog_lyric_preview, null);
                ((TextView) root.findViewById(R.id.tvPrevTitle)).setText(c.name + " · " + Lyrics.srcName(c.src));
                android.widget.ImageView ivp = root.findViewById(R.id.ivPrevCover);
                TextView note = root.findViewById(R.id.tvPrevCoverNote);
                boolean hasCover = c.cover != null && !c.cover.isEmpty();
                if (hasCover) {
                    ivp.setVisibility(View.VISIBLE);
                    ImgLoader.load(ivp, c.cover);
                } else ivp.setVisibility(View.GONE);
                boolean hasRoma = pack.roma != null && Lyrics.parseLrc(pack.roma).size() >= 3;
                boolean hasTrans = pack.trans != null && Lyrics.parseLrc(pack.trans).size() >= 3;
                note.setVisibility(View.VISIBLE);
                note.setText("应用后：歌词用这版"
                        + (hasRoma ? "（带罗马音 ✓）" : "")
                        + (hasTrans ? "（带翻译）" : "")
                        + (hasCover ? " · 封面换成这张 · 播放页显示原歌名"
                                : " · 这条没有封面图，封面继续自动匹配"));
                StringBuilder sb = new StringBuilder();
                for (Lyrics.Line l : lines) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(l.text);
                }
                ((TextView) root.findViewById(R.id.tvPrevBody)).setText(sb.toString());
                android.widget.ScrollView sv = root.findViewById(R.id.svPrev);
                Haptics.attachScrollRatchet(sv);
                android.view.ViewGroup.LayoutParams slp = sv.getLayoutParams();
                slp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55);
                sv.setLayoutParams(slp);
                final AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();
                root.findViewById(R.id.btnPrevBack).setOnClickListener(v -> dlg.dismiss());
                root.findViewById(R.id.btnPrevUse).setOnClickListener(v -> { dlg.dismiss(); applyChoice(c, pack); });
                dlg.show();
                if (dlg.getWindow() != null) {
                    dlg.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }
            });
        }).start();
    }

    private void applyCoverOnly(final Lyrics.MCand c) {
        Lyrics.applyManualCoverOnly(getApplicationContext(), bvid, c);
        Toast.makeText(this, "已应用封面+原歌名 ✓ 歌词继续自动匹配", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void applyChoice(final Lyrics.MCand c, final Lyrics.LrcPack pack) {
        new Thread(() -> {
            final boolean ok = Lyrics.applyManual(getApplicationContext(), bvid, c, pack);
            runOnUiThread(() -> {
                if (ok) {
                    boolean hasCover = c.cover != null && !c.cover.isEmpty();
                    Toast.makeText(this, hasCover ? "已匹配 ✓ 封面和歌词都换成这首了"
                            : "已锁定歌词 ✓（这条没封面，封面继续自动匹配）", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "锁定失败，这版歌词不完整", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
}
