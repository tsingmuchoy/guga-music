package com.guga.music;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
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
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        buildThemeRows();
    }

    private void buildThemeRows() {
        LinearLayout box = findViewById(R.id.llThemes);
        String cur = ThemeUtil.currentId(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        for (ThemeUtil.Def d : ThemeUtil.DEFS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
            row.setLayoutParams(lp);

            // 三个预览色点：底色 / 表面 / 点缀
            int[] dots = {d.bg, d.surface, d.accent};
            for (int c : dots) {
                View dot = new View(this);
                int sz = (int) (22 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(sz, sz);
                dlp.rightMargin = (int) (6 * getResources().getDisplayMetrics().density);
                dot.setLayoutParams(dlp);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(c);
                g.setStroke(1, 0x3AFFFFFF);
                dot.setBackground(g);
                row.addView(dot);
            }

            TextView name = new TextView(this);
            name.setText(d.name);
            name.setTextSize(15);
            name.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            nlp.leftMargin = (int) (6 * getResources().getDisplayMetrics().density);
            name.setLayoutParams(nlp);
            row.addView(name);

            if (d.id.equals(cur)) {
                TextView check = new TextView(this);
                check.setText("✓ 使用中");
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
