package com.guga.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 播放统计：日/周/月/年/总 五档 ×（播放次数 / 累计时长 / 曲目数）+ 最常播放榜（军师建议） */
public class StatsActivity extends Activity {

    private static final String[] PERIOD_NAMES = {"日", "周", "月", "年", "总"};
    private static final int[] PERIOD_DAYS = {1, 7, 30, 365, 0}; // 0 = 全部

    private StatsDb db;
    private TextView tvSumPlays, tvSumTime, tvSumTracks, tvEmpty;
    private ListView lvStats;
    private final TextView[] chips = new TextView[5];
    private int period = 1; // 默认「周」
    private final List<StatsDb.Row> rows = new ArrayList<>();
    private long maxPlays = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        db = new StatsDb(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tvSumPlays = findViewById(R.id.tvSumPlays);
        tvSumTime = findViewById(R.id.tvSumTime);
        tvSumTracks = findViewById(R.id.tvSumTracks);
        tvEmpty = findViewById(R.id.tvStatsEmpty);
        lvStats = findViewById(R.id.lvStats);
        lvStats.setAdapter(adapter);
        buildChips();
        reload();
    }

    private void buildChips() {
        LinearLayout box = findViewById(R.id.llPeriods);
        float den = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (36 * den), 1);
            lp.setMargins(3, 0, 3, 0);
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);
            chip.setText(PERIOD_NAMES[i]);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> {
                Haptics.tick(this);
                if (period != idx) {
                    period = idx;
                    styleChips();
                    reload();
                }
            });
            chips[i] = chip;
            box.addView(chip);
        }
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < 5; i++) {
            boolean on = i == period;
            if (on) {
                chips[i].setBackground(ThemeUtil.accentGradient(this, 18));
                chips[i].setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
                chips[i].setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                chips[i].setBackgroundResource(R.drawable.bg_chip_pill);
                chips[i].setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
                chips[i].setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void reload() {
        String to = StatsDb.todayKey();
        String from = PERIOD_DAYS[period] == 0 ? "0000-01-01" : StatsDb.daysAgoKey(PERIOD_DAYS[period]);
        StatsDb.Sum s = db.summary(from, to);
        tvSumPlays.setText(String.valueOf(s.plays));
        tvSumTime.setText(fmtListen(s.seconds));
        tvSumTracks.setText(String.valueOf(s.tracks));
        rows.clear();
        rows.addAll(db.top(from, to, 20));
        maxPlays = 1;
        for (StatsDb.Row r : rows) if (r.plays > maxPlays) maxPlays = r.plays;
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        lvStats.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 累计时长格式化：秒 → 分钟 → 小时 */
    static String fmtListen(long sec) {
        if (sec < 60) return sec + " 秒";
        long m = sec / 60;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(StatsActivity.this).inflate(R.layout.row_stat, parent, false);
            StatsDb.Row r = rows.get(p);
            TextView rank = cv.findViewById(R.id.tvRank);
            rank.setText(String.valueOf(p + 1));
            rank.setTextColor(ThemeUtil.color(StatsActivity.this, p < 3 ? R.attr.gAccent : R.attr.gTextFaint));
            rank.setTypeface(null, p < 3 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            ImgLoader.load((ImageView) cv.findViewById(R.id.ivCover), r.cover);
            ((TextView) cv.findViewById(R.id.tvTitle)).setText(r.title == null || r.title.isEmpty() ? r.bvid : r.title);
            ((TextView) cv.findViewById(R.id.tvAuthor)).setText(r.author == null ? "" : r.author);
            ((TextView) cv.findViewById(R.id.tvMeta)).setText(r.plays + " 次" + (r.seconds >= 60 ? "\n" + fmtListen(r.seconds) : ""));
            // 渐变横条：填充与留白按次数比例分配权重
            View fill = cv.findViewById(R.id.barFill);
            View gap = cv.findViewById(R.id.barGap);
            fill.setBackground(ThemeUtil.accentGradient(StatsActivity.this, 3));
            ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = r.plays;
            ((LinearLayout.LayoutParams) gap.getLayoutParams()).weight = maxPlays - r.plays;
            cv.setOnClickListener(v -> {
                Haptics.press(StatsActivity.this);
                PlayerService svc = PlayerService.get();
                if (svc == null) {
                    Toast.makeText(StatsActivity.this, "播放服务还没就绪，稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Track> ts = new ArrayList<>();
                int start = 0;
                for (int i = 0; i < rows.size(); i++) {
                    StatsDb.Row rr = rows.get(i);
                    Track t = new Track();
                    t.bvid = rr.bvid;
                    t.title = rr.title;
                    t.author = rr.author;
                    t.cover = rr.cover;
                    if (rr.bvid != null && rr.bvid.equals(r.bvid)) start = i;
                    ts.add(t);
                }
                svc.playQueue(ts, start);
                startActivity(new Intent(StatsActivity.this, PlayerActivity.class));
            });
            return cv;
        }
    };
}
