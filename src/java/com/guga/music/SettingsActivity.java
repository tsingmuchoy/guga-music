package com.guga.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/** 设置主页（v1.22.0 起）：只放入口行，各分组进二级页（主题/播放/歌词源/更新源/诊断） */
public class SettingsActivity extends Activity {

    private TextView eTheme, ePlayback, eLyrics, eUpdate, eDiag, eHaptics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Haptics.ratchetPage(this);

        eTheme = findViewById(R.id.btnEntryTheme);
        ePlayback = findViewById(R.id.btnEntryPlayback);
        eLyrics = findViewById(R.id.btnEntryLyrics);
        eUpdate = findViewById(R.id.btnEntryUpdate);
        eDiag = findViewById(R.id.btnEntryDiag);
        eTheme.setOnClickListener(v -> { Haptics.tick(this); startActivity(new Intent(this, ThemeSettingsActivity.class)); });
        ePlayback.setOnClickListener(v -> { Haptics.tick(this); startActivity(new Intent(this, PlaybackSettingsActivity.class)); });
        eLyrics.setOnClickListener(v -> { Haptics.tick(this); startActivity(new Intent(this, LyricsOrderActivity.class)); });
        eUpdate.setOnClickListener(v -> { Haptics.tick(this); startActivity(new Intent(this, UpdateSourceActivity.class)); });
        eDiag.setOnClickListener(v -> { Haptics.tick(this); startActivity(new Intent(this, DiagSettingsActivity.class)); });
        eHaptics = findViewById(R.id.btnHaptics);
        eHaptics.setOnClickListener(v -> {
            boolean on = !Haptics.isOn(this);
            Haptics.setOn(this, on);
            if (on) Haptics.press(this);
            refreshSummaries();
            Toast.makeText(this, on ? "触感反馈已开启 📳" : "触感反馈已关闭", Toast.LENGTH_SHORT).show();
        });
        refreshSummaries();

        findViewById(R.id.btnClearHistory).setOnClickListener(v -> {
            Haptics.tick(this);
            new HistoryDb(this).clear();
            Toast.makeText(this, "播放历史已清空", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnAbout).setOnClickListener(v -> {
            Haptics.tick(this);
            startActivity(new Intent(this, AboutActivity.class));
        });
    }

    private void refreshSummaries() {
        String themeName = ThemeUtil.currentId(this);
        for (ThemeUtil.Def d : ThemeUtil.DEFS) {
            if (d.id.equals(themeName)) { themeName = d.name; break; }
        }
        eTheme.setText("主题配色：" + themeName + " ›");
        IconUtil.leading(eTheme, R.drawable.ic_palette, R.attr.gAccent);
        PlayerService svc = PlayerService.get();
        int tier = svc != null ? svc.getQualityTier()
                : getSharedPreferences("player", MODE_PRIVATE).getBoolean("lowq", false) ? 0 : 2;
        ePlayback.setText("播放：" + PlayerService.QUALITY_NAMES[tier] + " ›");
        IconUtil.leading(ePlayback, R.drawable.ic_note, R.attr.gAccent);
        eLyrics.setText("歌词源顺序 ›");
        IconUtil.leading(eLyrics, R.drawable.ic_mic, R.attr.gAccent);
        eUpdate.setText("版本与更新：" + UpdateChecker.sourceName(UpdateChecker.sourcePref(this)) + " ›");
        IconUtil.leading(eUpdate, R.drawable.ic_refresh, R.attr.gAccent);
        eDiag.setText("播放诊断 ›");
        IconUtil.leading(eDiag, R.drawable.ic_pulse, R.attr.gAccent);
        eHaptics.setText("触感反馈：" + (Haptics.isOn(this) ? "开" : "关") + " ›");
        IconUtil.leading(eHaptics, R.drawable.ic_vibrate, R.attr.gAccent);
        IconUtil.leading((android.widget.TextView) findViewById(R.id.tvDataHeader), R.drawable.ic_disk, R.attr.gAccent);
        IconUtil.leading((android.widget.TextView) findViewById(R.id.btnClearHistory), R.drawable.ic_trash, R.attr.gAccent);
        IconUtil.leading((android.widget.TextView) findViewById(R.id.btnAbout), R.drawable.ic_tab_mine, R.attr.gAccent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSummaries();
    }
}
