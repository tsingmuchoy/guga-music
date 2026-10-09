package com.guga.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 二级设置：播放（音质五档 / 专辑封面 / 状态栏歌词入口 / 悬浮岛，从主设置页拆出） */
public class PlaybackSettingsActivity extends Activity {

    private TextView btnAlbumCover;
    private TextView btnLyricTrans;
    private TextView btnLyricRoma;
    private TextView btnLyricWords;
    private TextView btnFloatIsland;
    private TextView btnSbLyricsEntry;
    private TextView btnMetered;
    private TextView btnCacheCap;
    private TextView btnCacheInfo;
    private TextView btnTraffic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback_settings);
        Haptics.ratchetPage(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        btnAlbumCover = findViewById(R.id.btnAlbumCover);
        btnLyricTrans = findViewById(R.id.btnLyricTrans);
        btnLyricRoma = findViewById(R.id.btnLyricRoma);
        btnLyricWords = findViewById(R.id.btnLyricWords);
        refreshAlbumCover();
        refreshLyricTrans();
        refreshLyricRoma();
        refreshLyricWords();
        findViewById(R.id.rowAlbumCover).setOnClickListener(v -> { Haptics.press(this); Lyrics.setCoverArt(this, !Lyrics.isCoverArt(this)); refreshAlbumCover(); });
        findViewById(R.id.rowLyricTrans).setOnClickListener(v -> { Haptics.press(this); Lyrics.setShowTrans(this, !Lyrics.isShowTrans(this)); refreshLyricTrans(); });
        findViewById(R.id.rowLyricRoma).setOnClickListener(v -> { Haptics.press(this); Lyrics.setShowRoma(this, !Lyrics.isShowRoma(this)); refreshLyricRoma(); });
        findViewById(R.id.rowLyricWords).setOnClickListener(v -> { Haptics.press(this); Lyrics.setShowWords(this, !Lyrics.isShowWords(this)); refreshLyricWords(); });
        btnFloatIsland = findViewById(R.id.btnFloatIsland);
        refreshFloatIsland();
        findViewById(R.id.rowFloatIsland).setOnClickListener(v -> { Haptics.tick(this); onFloatIslandClicked(); });
        btnSbLyricsEntry = findViewById(R.id.btnSbLyricsEntry);
        refreshSbEntry();
        findViewById(R.id.rowSbLyricsEntry).setOnClickListener(v ->
                startActivity(new Intent(this, SbLyricsSettingsActivity.class)));

        buildQualityRows();

        btnMetered = findViewById(R.id.btnMetered);
        btnCacheCap = findViewById(R.id.btnCacheCap);
        btnCacheInfo = findViewById(R.id.btnCacheInfo);
        btnTraffic = findViewById(R.id.btnTraffic);
        refreshDataRows();
        findViewById(R.id.rowMetered).setOnClickListener(v -> {
            Haptics.press(this);
            android.content.SharedPreferences pf = getSharedPreferences("player", MODE_PRIVATE);
            boolean on = pf.getBoolean("metered_cap_on", true);
            int cap = pf.getInt("metered_cap_tier", 1);
            // 循环：关 -> 开·64K -> 开·132K -> 开·192K -> 关
            if (!on) {
                pf.edit().putBoolean("metered_cap_on", true).putInt("metered_cap_tier", 0).apply();
            } else if (cap < 2) {
                pf.edit().putInt("metered_cap_tier", cap + 1).apply();
            } else {
                pf.edit().putBoolean("metered_cap_on", false).apply();
            }
            refreshDataRows();
        });
        findViewById(R.id.rowCacheCap).setOnClickListener(v -> {
            Haptics.tick(this);
            android.content.SharedPreferences pf = getSharedPreferences("player", MODE_PRIVATE);
            int cur = pf.getInt("cache_cap_mb", 48);
            int nxt = cur == 16 ? 32 : cur == 32 ? 48 : cur == 48 ? 96 : 16;
            pf.edit().putInt("cache_cap_mb", nxt).apply();
            StreamCache.trim(this);
            refreshDataRows();
        });
        findViewById(R.id.rowCacheInfo).setOnClickListener(v -> {
            Haptics.tick(this);
            StreamCache.clearAll(this);
            Toast.makeText(this, "音频缓存已清空", Toast.LENGTH_SHORT).show();
            refreshDataRows();
        });
        findViewById(R.id.rowTraffic).setOnClickListener(v -> {
            Haptics.tick(this);
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.resetTrafficMonth();
            Toast.makeText(this, "流量统计已清零", Toast.LENGTH_SHORT).show();
            refreshDataRows();
        });
    }

    private String fmtBytes(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(java.util.Locale.CHINA, "%.1f MB", b / 1048576.0);
    }

    private void refreshDataRows() {
        android.content.SharedPreferences pf = getSharedPreferences("player", MODE_PRIVATE);
        boolean on = pf.getBoolean("metered_cap_on", true);
        int cap = pf.getInt("metered_cap_tier", 1);
        if (btnMetered != null) {
            btnMetered.setText(on
                    ? "流量下自动降档：开（上限 " + PlayerService.QUALITY_NAMES[cap] + "）"
                    : "流量下自动降档：关");
            IconUtil.leading(btnMetered, R.drawable.ic_signal, R.attr.gAccent);
        }
        if (btnCacheCap != null) {
            btnCacheCap.setText("音频缓存上限：" + pf.getInt("cache_cap_mb", 48) + " MB（可在 16/32/48/96 间切换）");
            IconUtil.leading(btnCacheCap, R.drawable.ic_sliders, R.attr.gAccent);
        }
        if (btnCacheInfo != null) {
            btnCacheInfo.setText("音频缓存：已用 " + fmtBytes(StreamCache.usedBytes(this)));
            IconUtil.leading(btnCacheInfo, R.drawable.ic_disk, R.attr.gAccent);
        }
        if (btnTraffic != null) {
            PlayerService svc = PlayerService.get();
            btnTraffic.setText(svc == null
                    ? "流量估算：播放服务未运行"
                    : "流量估算：本次约 " + fmtBytes(svc.trafficSessionBytes())
                            + " · 本月约 " + fmtBytes(svc.trafficMonthBytes()));
            IconUtil.leading(btnTraffic, R.drawable.ic_chart, R.attr.gAccent);
        }
    }

    private boolean islandPref() {
        return getSharedPreferences("player", MODE_PRIVATE).getBoolean("float_island", false);
    }

    private void refreshFloatIsland() {
        if (btnFloatIsland == null) return;
        boolean on = islandPref();
        boolean perm = android.provider.Settings.canDrawOverlays(this);
        btnFloatIsland.setText(!on ? "悬浮岛（仿原子岛）：关"
                : perm ? "悬浮岛（仿原子岛）：开（在别的 App 上方显示播控胶囊）"
                : "悬浮岛（仿原子岛）：已开启，但缺悬浮窗权限（去授权）");
        IconUtil.leading(btnFloatIsland, R.drawable.ic_island, R.attr.gAccent);
    }

    private void onFloatIslandClicked() {
        if (islandPref() && android.provider.Settings.canDrawOverlays(this)) {
            getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("float_island", false).apply();
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.refreshIsland();
            refreshFloatIsland();
            return;
        }
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("float_island", true).apply();
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮岛需要「显示在其他应用上层」权限，带你去开", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "打不开权限页，请在系统设置里手动给咕嘎音乐开悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        } else {
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.refreshIsland();
            Toast.makeText(this, "悬浮岛已开启，放首歌切到别的 App 看看", Toast.LENGTH_SHORT).show();
        }
        refreshFloatIsland();
    }

    private boolean sbPref() {
        return getSharedPreferences("player", MODE_PRIVATE).getBoolean("status_lyrics", false);
    }

    private void refreshSbEntry() {
        if (btnSbLyricsEntry == null) return;
        btnSbLyricsEntry.setText("状态栏歌词：" + (sbPref() ? "开" : "关") + "（位置/大小/颜色）");
        IconUtil.leading(btnSbLyricsEntry, R.drawable.ic_mic, R.attr.gAccent);
    }

    private void refreshAlbumCover() {
        btnAlbumCover.setText("专辑封面：" + (Lyrics.isCoverArt(this) ? "开（歌曲自动换专辑原图）" : "关（用视频封面）"));
        IconUtil.leading(btnAlbumCover, R.drawable.ic_image, R.attr.gAccent);
    }

    private void refreshLyricTrans() {
        btnLyricTrans.setText("歌词中文翻译：" + (Lyrics.isShowTrans(this) ? "开（日/韩歌曲有译文时显示）" : "关"));
        IconUtil.leading(btnLyricTrans, R.drawable.ic_globe, R.attr.gAccent);
    }

    private void refreshLyricRoma() {
        btnLyricRoma.setText("歌词罗马音：" + (Lyrics.isShowRoma(this) ? "开（日/韩歌曲有罗马音时显示）" : "关"));
        IconUtil.leading(btnLyricRoma, R.drawable.ic_mic, R.attr.gAccent);
    }

    private void refreshLyricWords() {
        btnLyricWords.setText("逐字歌词：" + (Lyrics.isShowWords(this) ? "开（全屏歌词页逐字扫光，有逐字数据的歌曲）" : "关"));
        IconUtil.leading(btnLyricWords, R.drawable.ic_mic, R.attr.gAccent);
    }

    /** 音质五档选择行，档位存 player 偏好 quality_tier */
    private void buildQualityRows() {
        LinearLayout box = findViewById(R.id.llQuality);
        box.removeAllViews();
        PlayerService svc = PlayerService.get();
        int cur = svc != null ? svc.getQualityTier()
                : getSharedPreferences("player", MODE_PRIVATE).getBoolean("lowq", false) ? 0 : 2;
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        for (int tier = 0; tier < PlayerService.QUALITY_NAMES.length; tier++) {
            final int t = tier;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
            row.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(PlayerService.QUALITY_NAMES[t]);
            name.setTextSize(15);
            name.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(name);

            if (t == cur) {
                TextView check = new TextView(this);
                check.setText("✓ 使用中");
                check.setTextSize(13);
                check.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                PlayerService s2 = PlayerService.get();
                boolean hasTrack = s2 != null && s2.current() != null;
                if (s2 != null) {
                    s2.setQualityTier(t);
                    s2.applyQualityChange();
                } else {
                    getSharedPreferences("player", MODE_PRIVATE).edit().putInt("quality_tier", t).apply();
                }
                Toast.makeText(this, "音质已切到「" + PlayerService.QUALITY_NAMES[t] + "」"
                                + (hasTrack ? "，当前歌曲已原地切换 🎵" : ""),
                        Toast.LENGTH_SHORT).show();
                buildQualityRows();
            });
            box.addView(row);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFloatIsland();
        refreshSbEntry();
        refreshDataRows();
        PlayerService svc = PlayerService.get();
        if (svc != null) { svc.refreshIsland(); svc.refreshSbLyrics(); }
    }
}
