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
        Haptics.ratchetPage(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        buildUpdateSourceRows();
        initUpdateCard();
    }

    /** 版本与更新卡（v1.25.0 起从「关于」页迁入，与更新源同页管理） */
    private void initUpdateCard() {
        final TextView tvHint = findViewById(R.id.tvUpdateHint);
        tvHint.setText("当前版本 v" + UpdateChecker.currentVersion(this) + " · 打开 App 时也会每天自动检查一次");
        findViewById(R.id.tvUpdate).setOnClickListener(v -> {
            Haptics.tick(this);
            tvHint.setText("正在检查更新…");
            UpdateChecker.check(this, (info, err) -> {
                if (info != null) {
                    tvHint.setText("发现新版本 v" + info.version + " 🎉");
                    UpdateChecker.showUpdateDialog(this, info);
                } else if (err != null) {
                    tvHint.setText("检查失败（网络原因），稍后再试");
                } else {
                    tvHint.setText("已是最新版啦 ✅ 当前 v" + UpdateChecker.currentVersion(this));
                }
            });
        });
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
                    Haptics.press(this);
                    Toast.makeText(this, "更新源已切到 " + UpdateChecker.sourceName(key), Toast.LENGTH_SHORT).show();
                    buildUpdateSourceRows();
                }
            });
            box.addView(row);
        }
    }
}
