package com.guga.music;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 二级设置：主题配色（从主设置页拆出） */
public class ThemeSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_settings);
        EdgeToEdge.apply(this);
        Haptics.ratchetPage(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        buildThemeRows();
        GlassTheme.bindActivity(this);
    }

    private void buildThemeRows() {
        LinearLayout box = findViewById(R.id.llThemes);
        String cur = ThemeUtil.currentId(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        for (ThemeUtil.Def d : ThemeUtil.DEFS) {
            GlassLinearLayout row = new GlassLinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.bg_card);
            row.setFocusable(true);
            row.setMinimumHeight((int) (88 * getResources().getDisplayMetrics().density));
            row.setContentDescription(d.name + (d.id.equals(cur) ? "，使用中" : "，点按切换"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
            row.setLayoutParams(lp);

            View preview = new ThemePreviewView(this, d);
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(preview, new LinearLayout.LayoutParams(
                    (int) (64 * getResources().getDisplayMetrics().density),
                    (int) (56 * getResources().getDisplayMetrics().density)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            labelParams.leftMargin = pad;
            labels.setLayoutParams(labelParams);

            TextView name = new TextView(this);
            name.setText(d.name);
            name.setTextSize(15);
            name.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            labels.addView(name);
            TextView detail = new TextView(this);
            detail.setText(d.id.equals("material") ? "纯色表面 · 简洁轻盈" : "通透表面 · 柔和折光");
            detail.setTextSize(12);
            detail.setTextColor(ThemeUtil.color(this, R.attr.gTextSec));
            detail.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, 0);
            labels.addView(detail);
            row.addView(labels);

            if (d.id.equals(cur)) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextSize(13);
                check.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                if (!d.id.equals(ThemeUtil.currentId(this))) {
                    ThemeUtil.setTheme(this, d.id);
                    Haptics.press(this);
                    Toast.makeText(this, "已切换到「" + d.name + "」", Toast.LENGTH_SHORT).show();
                    recreate();
                }
            });
            box.addView(row);
        }
    }
}
