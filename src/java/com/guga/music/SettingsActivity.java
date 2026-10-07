package com.guga.music;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private TextView btnFollowSystem;
    private TextView btnAlbumCover;
    private TextView btnFloatIsland;
    private TextView btnSbLyrics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        buildThemeRows();

        btnFollowSystem = findViewById(R.id.btnFollowSystem);
        btnAlbumCover = findViewById(R.id.btnAlbumCover);
        refreshAlbumCover();
        btnAlbumCover.setOnClickListener(v -> { Lyrics.setCoverArt(this, !Lyrics.isCoverArt(this)); refreshAlbumCover(); });
        btnFloatIsland = findViewById(R.id.btnFloatIsland);
        refreshFloatIsland();
        btnFloatIsland.setOnClickListener(v -> onFloatIslandClicked());
        btnSbLyrics = findViewById(R.id.btnSbLyrics);
        refreshSbLyricsBtn();
        btnSbLyrics.setOnClickListener(v -> onSbLyricsClicked());
        refreshFollowSystem();
        btnFollowSystem.setOnClickListener(v -> {
            boolean on = !ThemeUtil.isFollowSystem(this);
            ThemeUtil.setFollowSystem(this, on);
            Toast.makeText(this, on ? "已开启跟随系统（夜间深色 · 日间瓷白）" : "已关闭跟随系统",
                    Toast.LENGTH_SHORT).show();
            recreate();
        });

        buildQualityRows();

        buildLyricOrderRows();
        buildUpdateSourceRows();
        findViewById(R.id.btnLyricOrderReset).setOnClickListener(v -> {
            Lyrics.setSourceOrder(this, java.util.Arrays.asList(Lyrics.SRC_KEYS));
            Toast.makeText(this, "歌词源已恢复默认顺序", Toast.LENGTH_SHORT).show();
            buildLyricOrderRows();
        });

        findViewById(R.id.btnClearHistory).setOnClickListener(v -> {
            new HistoryDb(this).clear();
            Toast.makeText(this, "播放历史已清空", Toast.LENGTH_SHORT).show();
        });
        refreshDiag();
        findViewById(R.id.btnDiagRefresh).setOnClickListener(v -> refreshDiag());
        findViewById(R.id.btnDiagClear).setOnClickListener(v -> {
            Diag.clear(this);
            refreshDiag();
        });
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
    }

    private boolean islandPref() {
        return getSharedPreferences("player", MODE_PRIVATE).getBoolean("float_island", false);
    }

    private void refreshFloatIsland() {
        if (btnFloatIsland == null) return;
        boolean on = islandPref();
        boolean perm = android.provider.Settings.canDrawOverlays(this);
        btnFloatIsland.setText(!on ? "🫧 悬浮岛（仿原子岛）：关（点开启）"
                : perm ? "🫧 悬浮岛（仿原子岛）：开（在别的 App 上方显示播控胶囊）"
                : "🫧 悬浮岛（仿原子岛）：已开启，但缺悬浮窗权限（点此去授权）");
    }

    private void onFloatIslandClicked() {
        if (islandPref() && android.provider.Settings.canDrawOverlays(this)) {
            getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("float_island", false).apply();
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.refreshIsland();
            refreshFloatIsland();
            return;
        }
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("float_island", true).apply();
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮岛需要「显示在其他应用上层」权限，带你去开", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "打不开权限页，请在系统设置里手动给咕嘎音乐开悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        } else {
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.refreshIsland();
            Toast.makeText(this, "悬浮岛已开启，放首歌切到别的 App 看看", Toast.LENGTH_SHORT).show();
        }
        refreshFloatIsland();
    }

    private boolean sbPref() {
        return getSharedPreferences("player", MODE_PRIVATE).getBoolean("status_lyrics", false);
    }

    private void refreshSbLyricsBtn() {
        if (btnSbLyrics == null) return;
        boolean on = sbPref();
        boolean perm = android.provider.Settings.canDrawOverlays(this);
        btnSbLyrics.setText(!on ? "🎤 状态栏歌词：关（点开启）"
                : perm ? "🎤 状态栏歌词：开（切出 App 后在顶部逐行显示）"
                : "🎤 状态栏歌词：已开启，但缺悬浮窗权限（点此去授权）");
    }

    private void onSbLyricsClicked() {
        if (sbPref() && android.provider.Settings.canDrawOverlays(this)) {
            getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("status_lyrics", false).apply();
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.refreshSbLyrics();
            refreshSbLyricsBtn();
            return;
        }
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("status_lyrics", true).apply();
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "状态栏歌词需要「显示在其他应用上层」权限，带你去开", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "打不开权限页，请在系统设置里手动给咕嘎音乐开悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        } else {
            PlayerService svc = PlayerService.get();
            if (svc != null) svc.refreshSbLyrics();
            Toast.makeText(this, "状态栏歌词已开启，放首歌切到桌面看看", Toast.LENGTH_SHORT).show();
        }
        refreshSbLyricsBtn();
    }

    private void refreshAlbumCover() {
        btnAlbumCover.setText("🖼 专辑封面：" + (Lyrics.isCoverArt(this) ? "开（歌曲自动换专辑原图）" : "关（用视频封面）"));
    }

    private void refreshFollowSystem() {
        boolean on = ThemeUtil.isFollowSystem(this);
        btnFollowSystem.setText(on
                ? "🌗 主题跟随系统：开（夜间用所选主题 · 日间瓷白玻璃）"
                : "🌗 主题跟随系统：关（点开启）");
    }

    /** 音质五档选择行（样式与主题行一致），档位存 player 偏好 quality_tier */
    private void buildQualityRows() {
        LinearLayout box = findViewById(R.id.llQuality);
        box.removeAllViews();
        PlayerService svc = PlayerService.get();
        int cur = svc != null ? svc.getQualityTier()
                : getSharedPreferences("player", MODE_PRIVATE).getBoolean("lowq", false) ? 0 : 2;
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        for (int tier = 0; tier < PlayerService.QUALITY_NAMES.length; tier++) {
            final int t = tier;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
            row.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(PlayerService.QUALITY_NAMES[t]);
            name.setTextSize(15);
            name.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(name);

            if (t == cur) {
                TextView check = new TextView(this);
                check.setText("✓ 使用中");
                check.setTextSize(13);
                check.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                PlayerService s2 = PlayerService.get();
                boolean hasTrack = s2 != null && s2.current() != null;
                if (s2 != null) {
                    s2.setQualityTier(t);
                    s2.applyQualityChange();
                } else {
                    getSharedPreferences("player", MODE_PRIVATE).edit().putInt("quality_tier", t).apply();
                }
                Toast.makeText(this, "音质已切到「" + PlayerService.QUALITY_NAMES[t] + "」"
                                + (hasTrack ? "，当前歌曲已原地切换 🎵" : ""),
                        Toast.LENGTH_SHORT).show();
                buildQualityRows();
            });
            box.addView(row);
        }
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
                    Toast.makeText(this, "已切换到「" + d.name + "」", Toast.LENGTH_SHORT).show();
                    recreate();
                }
            });
            box.addView(row);
        }
    }

    private void refreshDiag() {
        TextView st = findViewById(R.id.tvDiagState);
        TextView lg = findViewById(R.id.tvDiagLog);
        if (st == null || lg == null) return;
        PlayerService svc = PlayerService.get();
        st.setText(svc == null ? "播放服务未运行（先回主页点一首歌再回来）" : svc.debugState());
        lg.setText(Diag.read(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDiag();
        refreshFloatIsland();
        refreshSbLyricsBtn();
        PlayerService svc = PlayerService.get();
        if (svc != null) { svc.refreshIsland(); svc.refreshSbLyrics(); }
    }
}
