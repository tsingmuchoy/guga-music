package com.guga.music;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 状态栏歌词二级设置：总开关 + 五根滑杆 + 字体颜色；本页打开时服务以实时预览模式在顶部显示真歌词条 */
public class SbLyricsSettingsActivity extends Activity {

    private SharedPreferences sp;
    private TextView btnSbMaster;
    private TextView lblY, lblX, lblW, lblBg, lblFont;
    private SeekBar skY, skX, skW, skBg, skFont;
    private LinearLayout llColors;

    private static final int[] COLOR_VALS = {
            0xFF00E676, 0xFFFF5252, 0xFFFF8A00, 0xFFFFD740,
            0xFF18FFFF, 0xFF448AFF, 0xFFE040FB, 0xFFFFFFFF};
    private static final String[] COLOR_NAMES = {
            "亮绿", "红", "橙", "黄", "青", "蓝", "紫", "白"};

    private SharedPreferences prefs() {
        if (sp == null) sp = getSharedPreferences("player", MODE_PRIVATE);
        return sp;
    }

    private int getI(String k, int def) { return prefs().getInt(k, def); }
    private boolean getB(String k, boolean def) { return prefs().getBoolean(k, def); }
    private void putI(String k, int v) { prefs().edit().putInt(k, v).apply(); poke(); }
    private void putB(String k, boolean v) { prefs().edit().putBoolean(k, v).apply(); poke(); }

    private void poke() {
        PlayerService svc = PlayerService.get();
        if (svc != null) svc.refreshSbLyrics();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sb_lyrics);
        Haptics.ratchetPage(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSbMaster = findViewById(R.id.btnSbMaster);
        lblY = findViewById(R.id.lblSbY);
        lblX = findViewById(R.id.lblSbX);
        lblW = findViewById(R.id.lblSbW);
        lblBg = findViewById(R.id.lblSbBg);
        lblFont = findViewById(R.id.lblSbFont);
        skY = findViewById(R.id.skSbY);
        skX = findViewById(R.id.skSbX);
        skW = findViewById(R.id.skSbW);
        skBg = findViewById(R.id.skSbBg);
        skFont = findViewById(R.id.skSbFont);
        llColors = findViewById(R.id.llSbColors);

        refreshMaster();
        btnSbMaster.setOnClickListener(v -> onMasterClicked());

        skY.setProgress(getI("sb_y_off", 0) + 20);
        skX.setProgress(getI("sb_x_off", 0) + 40);
        skW.setProgress(getI("sb_width", 100) - 40);
        skBg.setProgress(getI("sb_bg_alpha", 0));
        skFont.setProgress(getI("sb_font_sp", 13) - 10);
        bindSeek(skY, v -> { putI("sb_y_off", v - 20); refreshLabels(); });
        bindSeek(skX, v -> { putI("sb_x_off", v - 40); refreshLabels(); });
        bindSeek(skW, v -> { putI("sb_width", v + 40); refreshLabels(); });
        bindSeek(skBg, v -> { putI("sb_bg_alpha", v); refreshLabels(); });
        bindSeek(skFont, v -> { putI("sb_font_sp", v + 10); refreshLabels(); });
        refreshLabels();
        buildColorRows();
    }

    private interface IntFn { void go(int v); }

    private void bindSeek(SeekBar sk, IntFn fn) {
        sk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) fn.go(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void refreshLabels() {
        lblY.setText("上下调节：" + signed(getI("sb_y_off", 0)) + "dp");
        lblX.setText("左右调节：" + signed(getI("sb_x_off", 0)) + "dp");
        lblW.setText("宽度调节：" + getI("sb_width", 100) + "%");
        lblBg.setText("背景透明度：" + getI("sb_bg_alpha", 0) + "%");
        lblFont.setText("字体大小：" + getI("sb_font_sp", 13) + "sp");
    }

    private String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    private void buildColorRows() {
        llColors.removeAllViews();
        addColorRow(-1, "跟随封面（默认，和悬浮岛律动条同逻辑）", 0);
        for (int i = 0; i < COLOR_VALS.length; i++) {
            addColorRow(i, COLOR_NAMES[i], COLOR_VALS[i]);
        }
    }

    private void addColorRow(final int idx, String name, int color) {
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0,
                (int) (10 * getResources().getDisplayMetrics().density));
        android.util.TypedValue tvv = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.gTextPri, tvv, true);
        tv.setTextColor(tvv.data);
        boolean selected = idx < 0 ? getB("sb_follow", true)
                : !getB("sb_follow", true) && getI("sb_color", 0xFF00E676) == color;
        String label = (selected ? "✓ " : "　") + (idx < 0 ? name : "● " + name);
        SpannableString ss = new SpannableString(label);
        if (idx >= 0) {
            int dot = label.indexOf('●');
            if (dot >= 0) ss.setSpan(new ForegroundColorSpan(color), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(ss);
        tv.setOnClickListener(v -> {
            if (idx < 0) {
                putB("sb_follow", true);
            } else {
                prefs().edit().putBoolean("sb_follow", false).putInt("sb_color", color).apply();
                poke();
            }
            buildColorRows();
        });
        llColors.addView(tv);
    }

    private void refreshMaster() {
        boolean on = getB("status_lyrics", false);
        boolean perm = android.provider.Settings.canDrawOverlays(this);
        btnSbMaster.setText(!on ? "使用状态栏歌词：关（点开启）"
                : perm ? "使用状态栏歌词：开"
                : "使用状态栏歌词：已开启，但缺悬浮窗权限（点此去授权）");
    }

    private void onMasterClicked() {
        if (getB("status_lyrics", false) && android.provider.Settings.canDrawOverlays(this)) {
            putB("status_lyrics", false);
            refreshMaster();
            return;
        }
        putB("status_lyrics", true);
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "状态栏歌词需要「显示在其他应用上层」权限，带你去开", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "打不开权限页，请在系统设置里手动给咕嘎音乐开悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        }
        refreshMaster();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMaster();
        buildColorRows();
        PlayerService svc = PlayerService.get();
        if (svc != null) {
            svc.setSbPreview(true);
        } else {
            Toast.makeText(this, "先回主页播放一首歌，这里就能边调边看实时预览啦", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        PlayerService svc = PlayerService.get();
        if (svc != null) svc.setSbPreview(false);
        super.onPause();
    }
}
