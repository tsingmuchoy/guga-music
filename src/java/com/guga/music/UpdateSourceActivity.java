package com.guga.music;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 二级设置：更新源（从主设置页拆出） */
public class UpdateSourceActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_source);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        buildUpdateSourceRows();
    }

    /** 更新源选择行：GitHub 主源 / Gitee 备用源，检查更新时首选失败自动切换 */
    private void buildUpdateSourceRows() {
        LinearLayout box = findViewById(R.id.llUpdateSource);
        box.removeAllViews();
        String cur = UpdateChecker.sourcePref(this);
        String[][] opts = {
                {UpdateChecker.SRC_GITHUB, "GitHub（主源）"},
                {UpdateChecker.SRC_GITEE, "Gitee（备用源 · 国内直连）"},
        };
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        for (String[] o : opts) {
            final String key = o[0];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * density);
            row.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(o[1]);
            name.setTextSize(15);
            name.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(name);

            if (key.equals(cur)) {
                TextView check = new TextView(this);
                check.setText("✓ 使用中");
                check.setTextSize(13);
                check.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
                row.addView(check);
            }
            row.setOnClickListener(v -> {
                if (!key.equals(UpdateChecker.sourcePref(this))) {
                    UpdateChecker.setSourcePref(this, key);
                    Toast.makeText(this, "更新源已切到 " + UpdateChecker.sourceName(key), Toast.LENGTH_SHORT).show();
                    buildUpdateSourceRows();
                }
            });
            box.addView(row);
        }
    }
}
