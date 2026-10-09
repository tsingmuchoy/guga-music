package com.guga.music;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 二级设置：歌词源顺序（从主设置页拆出） */
public class LyricsOrderActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics_order);
        Haptics.ratchetPage(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        buildLyricOrderRows();
        findViewById(R.id.btnLyricOrderReset).setOnClickListener(v -> {
            Lyrics.setSourceOrder(this, java.util.Arrays.asList(Lyrics.SRC_KEYS));
            Toast.makeText(this, "歌词源已恢复默认顺序", Toast.LENGTH_SHORT).show();
            buildLyricOrderRows();
        });
    }

    /** 歌词源顺序调整行：每行一个源，点 ↑ / ↓ 与相邻源换位，立即保存 */
    private void buildLyricOrderRows() {
        LinearLayout box = findViewById(R.id.llLyricOrder);
        box.removeAllViews();
        final java.util.List<String> order = Lyrics.sourceOrder(this);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        for (int i = 0; i < order.size(); i++) {
            final int idx = i;
            final String key = order.get(i);
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
            name.setText((i + 1) + ". " + Lyrics.srcName(key));
            name.setTextSize(15);
            name.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(name);

            TextView up = new TextView(this);
            up.setText("↑");
            up.setTextSize(17);
            up.setPadding((int) (10 * density), (int) (4 * density), (int) (10 * density), (int) (4 * density));
            up.setTextColor(ThemeUtil.color(this, idx > 0 ? R.attr.gAccent : R.attr.gTextSec));
            up.setOnClickListener(v -> {
                if (idx <= 0) return;
                java.util.Collections.swap(order, idx, idx - 1);
                Lyrics.setSourceOrder(this, order);
                buildLyricOrderRows();
            });
            row.addView(up);

            TextView down = new TextView(this);
            down.setText("↓");
            down.setTextSize(17);
            down.setPadding((int) (10 * density), (int) (4 * density), (int) (6 * density), (int) (4 * density));
            down.setTextColor(ThemeUtil.color(this, idx < order.size() - 1 ? R.attr.gAccent : R.attr.gTextSec));
            down.setOnClickListener(v -> {
                if (idx >= order.size() - 1) return;
                java.util.Collections.swap(order, idx, idx + 1);
                Lyrics.setSourceOrder(this, order);
                buildLyricOrderRows();
            });
            row.addView(down);

            box.addView(row);
        }
    }
}
