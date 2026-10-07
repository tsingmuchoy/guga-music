package com.guga.music;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 二级设置：播放诊断（从主设置页拆出） */
public class DiagSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag_settings);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        refreshDiag();
        findViewById(R.id.btnDiagRefresh).setOnClickListener(v -> refreshDiag());
        findViewById(R.id.btnDiagClear).setOnClickListener(v -> {
            Diag.clear(this);
            refreshDiag();
        });
    }

    private void refreshDiag() {
        TextView st = findViewById(R.id.tvDiagState);
        TextView lg = findViewById(R.id.tvDiagLog);
        if (st == null || lg == null) return;
        PlayerService svc = PlayerService.get();
        st.setText(svc == null ? "播放服务未运行（先回主页点一首歌再回来）" : svc.debugState());
        lg.setText(Diag.readDisplay(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDiag();
    }
}
