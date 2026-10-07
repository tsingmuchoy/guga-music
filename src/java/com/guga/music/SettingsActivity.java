package com.guga.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/** 设置主页（v1.22.0 起）：只放入口行，各分组进二级页（主题/播放/歌词源/更新源/诊断） */
public class SettingsActivity extends Activity {

    private TextView eTheme, ePlayback, eLyrics, eUpdate, eDiag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        eTheme = findViewById(R.id.btnEntryTheme);
        ePlayback = findViewById(R.id.btnEntryPlayback);
        eLyrics = findViewById(R.id.btnEntryLyrics);
        eUpdate = findViewById(R.id.btnEntryUpdate);
        eDiag = findViewById(R.id.btnEntryDiag);
        eTheme.setOnClickListener(v -> startActivity(new Intent(this, ThemeSettingsActivity.class)));
        ePlayback.setOnClickListener(v -> startActivity(new Intent(this, PlaybackSettingsActivity.class)));
        eLyrics.setOnClickListener(v -> startActivity(new Intent(this, LyricsOrderActivity.class)));
        eUpdate.setOnClickListener(v -> startActivity(new Intent(this, UpdateSourceActivity.class)));
        eDiag.setOnClickListener(v -> startActivity(new Intent(this, DiagSettingsActivity.class)));
        refreshSummaries();

        findViewById(R.id.btnClearHistory).setOnClickListener(v -> {
            new HistoryDb(this).clear();
            Toast.makeText(this, "播放历史已清空", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
    }

    private void refreshSummaries() {
        String themeName = ThemeUtil.currentId(this);
        for (ThemeUtil.Def d : ThemeUtil.DEFS) {
            if (d.id.equals(themeName)) { themeName = d.name; break; }
        }
        eTheme.setText("🎨 主题配色：" + themeName + " ›");
        PlayerService svc = PlayerService.get();
        int tier = svc != null ? svc.getQualityTier()
                : getSharedPreferences("player", MODE_PRIVATE).getBoolean("lowq", false) ? 0 : 2;
        ePlayback.setText("🎵 播放：" + PlayerService.QUALITY_NAMES[tier] + " ›");
        eLyrics.setText("🎤 歌词源顺序 ›");
        eUpdate.setText("🔄 更新源：" + UpdateChecker.sourceName(UpdateChecker.sourcePref(this)) + " ›");
        eDiag.setText("🩺 播放诊断 ›");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSummaries();
    }
}
