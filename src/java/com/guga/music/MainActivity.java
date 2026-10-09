package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements PlayerService.Listener {

    private BiliApi api;
    private HistoryDb history;

    private int tab = 0; // 0搜索 1收藏 2历史 3我的
    private boolean favShowingTracks = false;
    private final List<Track> displayTracks = new ArrayList<>();
    private String searchKw = "";
    private int searchPage = 1;
    private boolean searchLoading;
    private boolean searchMore = true;
    private TextView searchFooter;
    private android.widget.LinearLayout llSortChips;
    private static final String[][] SORTS = {
            {"综合", ""}, {"最多播放", "click"}, {"最新发布", "pubdate"}, {"最多弹幕", "dm"}, {"最多收藏", "stow"}};
    private final TextView[] sortChips = new TextView[SORTS.length];
    private String searchOrder = "";
    private final List<BiliApi.FavFolder> folders = new ArrayList<>();
    private View llFavSwitch;
    private TextView chipFavBili, chipFavLocal, btnNewLocal;
    private boolean favLocalMode = false;
    private final List<LocalDb.Playlist> localLists = new ArrayList<>();
    private LocalDb localDb;

    private View llSearchBar, btnFavBack, svMine;
    private ListView lvMain;
    private View rootMain;
    private TextView tvHint, tvUname, tvMid, btnLogin;
    private ImageView ivFace, ivMiniCover;
    private TextView tvMiniTitle;
    private android.widget.ImageView btnMiniToggle;
    private final View[] tabViews = new View[4];
    private final ImageView[] tabIcons = new ImageView[4];
    private final TextView[] tabLabels = new TextView[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_main);
        UpdateChecker.autoCheck(this);
        api = new BiliApi(this);
        history = new HistoryDb(this);
        PlayerService.ensureStarted(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        llSearchBar = findViewById(R.id.llSearchBar);
        btnFavBack = findViewById(R.id.btnFavBack);
        svMine = findViewById(R.id.svMine);
        lvMain = findViewById(R.id.lvMain);
        searchFooter = new TextView(this);
        searchFooter.setGravity(android.view.Gravity.CENTER);
        float fden = getResources().getDisplayMetrics().density;
        searchFooter.setPadding(0, (int) (12 * fden), 0, (int) (12 * fden));
        searchFooter.setTextSize(12);
        searchFooter.setTextColor(ThemeUtil.color(this, R.attr.gTextFaint));
        searchFooter.setVisibility(View.GONE);
        lvMain.addFooterView(searchFooter);
        buildSortChips();
        lvMain.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {}
            @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                if (tab == 0 && searchMore && !searchLoading && total > 0 && first + visible >= total - 3) loadMoreSearch();
            }
        });
        rootMain = findViewById(R.id.rootMain);
        tvHint = findViewById(R.id.tvHint);
        tvUname = findViewById(R.id.tvUname);
        tvMid = findViewById(R.id.tvMid);
        btnLogin = findViewById(R.id.btnLogin);
        ivFace = findViewById(R.id.ivFace);
        ivMiniCover = findViewById(R.id.ivMiniCover);
        tvMiniTitle = findViewById(R.id.tvMiniTitle);
        btnMiniToggle = findViewById(R.id.btnMiniToggle);
        tabViews[0] = findViewById(R.id.tabSearch);
        tabViews[1] = findViewById(R.id.tabFav);
        tabViews[2] = findViewById(R.id.tabHistory);
        tabViews[3] = findViewById(R.id.tabMine);
        tabIcons[0] = findViewById(R.id.tabIcon0);
        tabIcons[1] = findViewById(R.id.tabIcon1);
        tabIcons[2] = findViewById(R.id.tabIcon2);
        tabIcons[3] = findViewById(R.id.tabIcon3);
        tabLabels[0] = findViewById(R.id.tabLabel0);
        tabLabels[1] = findViewById(R.id.tabLabel1);
        tabLabels[2] = findViewById(R.id.tabLabel2);
        tabLabels[3] = findViewById(R.id.tabLabel3);

        EditText etSearch = findViewById(R.id.etSearch);
        findViewById(R.id.btnSearch).setOnClickListener(v -> { Haptics.tick(this); doSearch(etSearch.getText().toString().trim()); });
        etSearch.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(etSearch.getText().toString().trim());
                return true;
            }
            return false;
        });

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabViews[i].setOnClickListener(v -> { Haptics.tick(this); selectTab(idx); });
        }

        lvMain.setOnItemClickListener((p, v, pos, id) -> {
            Haptics.tick(this);
            final int idx = pos - lvMain.getHeaderViewsCount();
            if (tab == 1 && favLocalMode) {
                if (idx < localLists.size()) {
                    LocalDb.Playlist pl = localLists.get(idx);
                    Intent it = new Intent(this, LocalPlaylistActivity.class);
                    it.putExtra("pid", pl.id);
                    it.putExtra("name", pl.name);
                    startActivity(it);
                }
            } else if (tab == 1 && !favShowingTracks) {
                if (idx < folders.size()) openFolder(folders.get(idx));
            } else if (tab != 3) {
                if (idx < displayTracks.size()) playTracks(new ArrayList<>(displayTracks), idx);
            }
        });
        lvMain.setOnItemLongClickListener((p, v, pos, id) -> {
            if (tab == 1 && favLocalMode) {
                if (pos < localLists.size()) {
                    final LocalDb.Playlist pl = localLists.get(pos);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(pl.name)
                            .setItems(new String[]{"重命名", "删除歌单"}, (d, w) -> {
                                if (w == 0) {
                                    PlaylistPicker.createDialog(this, "重命名歌单", pl.name, "保存", name -> {
                                        localDb.renamePlaylist(pl.id, name);
                                        showLocalLists();
                                    });
                                } else {
                                    new android.app.AlertDialog.Builder(this)
                                            .setMessage("删除歌单「" + pl.name + "」？里面记录的歌曲会一起删掉")
                                            .setPositiveButton("删除", (d2, w2) -> {
                                                localDb.deletePlaylist(pl.id);
                                                showLocalLists();
                                            })
                                            .setNegativeButton("取消", null)
                                            .show();
                                }
                            })
                            .show();
                }
                return true;
            }
            if (tab == 1 && !favShowingTracks) return false;
            if (tab != 3 && pos < displayTracks.size()) {
                PlaylistPicker.show(this, displayTracks.get(pos));
                return true;
            }
            return false;
        });
        btnFavBack.setOnClickListener(v -> {
            favShowingTracks = false;
            showFolders();
        });

        findViewById(R.id.llMini).setOnClickListener(v -> {
            Haptics.tick(this);
            if (PlayerService.get() != null && PlayerService.get().current() != null) {
                startActivity(new Intent(this, PlayerActivity.class));
            }
        });
        btnMiniToggle.setOnClickListener(v -> {
            Haptics.press(this);
            PlayerService s = PlayerService.get();
            if (s != null) s.toggle();
        });
        findViewById(R.id.btnMiniNext).setOnClickListener(v -> {
            Haptics.tick(this);
            PlayerService s = PlayerService.get();
            if (s != null) s.next(true);
        });

        btnLogin.setOnClickListener(v -> {
            Haptics.tick(this);
            if (api.isLoggedIn()) {
                new AlertDialog.Builder(this)
                        .setMessage("退出登录？")
                        .setPositiveButton("退出", (d, w) -> { api.logout(); refreshMine(); Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show(); })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                startActivity(new Intent(this, LoginActivity.class));
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> { Haptics.tick(this); startActivity(new Intent(this, SettingsActivity.class)); });
        localDb = new LocalDb(this);
        llFavSwitch = findViewById(R.id.llFavSwitch);
        chipFavBili = findViewById(R.id.chipFavBili);
        chipFavLocal = findViewById(R.id.chipFavLocal);
        btnNewLocal = findViewById(R.id.btnNewLocal);
        chipFavBili.setOnClickListener(v -> {
            Haptics.tick(this);
            favLocalMode = false;
            styleFavChips();
            favShowingTracks = false;
            btnFavBack.setVisibility(View.GONE);
            loadFolders();
        });
        chipFavLocal.setOnClickListener(v -> {
            Haptics.tick(this);
            favLocalMode = true;
            styleFavChips();
            showLocalLists();
        });
        btnNewLocal.setOnClickListener(v -> PlaylistPicker.createDialog(this, name -> {
            localDb.createPlaylist(name);
            showLocalLists();
        }));

        selectTab(0);
        hint("搜一首歌，开始听吧 🎧");
    }

    @Override
    public void onBackPressed() {
        // 返回键先在 App 内逐级退：收藏内容 -> 收藏夹列表；其他页 -> 搜索页；已在搜索页才退出
        if (tab == 1 && favShowingTracks) {
            favShowingTracks = false;
            btnFavBack.setVisibility(View.GONE);
            showFolders();
            return;
        }
        if (tab != 0) {
            selectTab(0);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeUtil.consumeDirty(this)) { recreate(); return; }
        UpdateChecker.checkPendingInstall(this);
        PlayerService s = PlayerService.get();
        if (s != null) {
            s.addListener(this);
            Track t = s.current();
            if (t != null) onTrackChanged(t);
            onStateChanged(s.isPlaying());
        }
        if (tab == 3) refreshMine();
        if (tab == 1 && favLocalMode) showLocalLists();
    }

    @Override
    protected void onPause() {
        PlayerService s = PlayerService.get();
        if (s != null) s.removeListener(this);
        super.onPause();
    }

    // ---------------- 标签页 ----------------
    private static final int[] TAB_ICONS = {
            R.drawable.ic_tab_search, R.drawable.ic_tab_fav, R.drawable.ic_tab_history, R.drawable.ic_tab_mine};

    /** 底栏竖排：图标在上、文字在下，未选次级色，选中主题色（收藏星选中变实心） */
    private void styleTabs(int sel) {
        for (int k = 0; k < 4; k++) {
            boolean on = k == sel;
            int res = k == 1 && on ? R.drawable.ic_tab_fav_fill : TAB_ICONS[k];
            tabIcons[k].setImageResource(res);
            tabIcons[k].setColorFilter(ThemeUtil.color(this, on ? R.attr.gAccent : R.attr.gTextSec),
                    android.graphics.PorterDuff.Mode.SRC_IN);
            tabLabels[k].setTextColor(ThemeUtil.color(this, on ? R.attr.gAccent : R.attr.gTextSec));
            tabLabels[k].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void selectTab(int i) {
        tab = i;
        styleTabs(i);
        llSearchBar.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        if (llSortChips != null) llSortChips.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        svMine.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        lvMain.setVisibility(i == 3 ? View.GONE : View.VISIBLE);
        btnFavBack.setVisibility(i == 1 && favShowingTracks ? View.VISIBLE : View.GONE);
        llFavSwitch.setVisibility(i == 1 && !favShowingTracks ? View.VISIBLE : View.GONE);
        tvHint.setVisibility(View.GONE);
        switch (i) {
            case 0:
                lvMain.setAdapter(trackAdapter);
                trackAdapter.notifyDataSetChanged();
                if (displayTracks.isEmpty()) hint("搜一首歌，开始听吧 🎧");
                break;
            case 1:
                favShowingTracks = false;
                btnFavBack.setVisibility(View.GONE);
                llFavSwitch.setVisibility(View.VISIBLE);
                styleFavChips();
                if (favLocalMode) showLocalLists();
                else loadFolders();
                break;
            case 2:
                showHistory();
                break;
            case 3:
                refreshMine();
                break;
        }
        if (i == 2) attachStatsHeader(); else detachStatsHeader();
        updateSearchFooter();
    }

    private void hint(String msg) {
        if (msg == null) {
            tvHint.setVisibility(View.GONE);
        } else {
            tvHint.setText(msg);
            tvHint.setVisibility(View.VISIBLE);
        }
    }

    // ---------------- 搜索 ----------------
    private void doSearch(String kw) {
        if (kw.isEmpty()) {
            Toast.makeText(this, "先输入关键词啦 🐧", Toast.LENGTH_SHORT).show();
            return;
        }
        hint("搜索中…");
        searchKw = kw;
        searchPage = 1;
        searchMore = true;
        searchLoading = true;
        api.search(kw, 1, searchOrder, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                searchLoading = false;
                if (tab != 0) return;
                displayTracks.clear();
                displayTracks.addAll(v);
                lvMain.setAdapter(trackAdapter);
                trackAdapter.notifyDataSetChanged();
                searchMore = v.size() >= 50;
                updateSearchFooter();
                hint(v.isEmpty() ? "没搜到结果，换个词试试" : null);
            }
            @Override public void onErr(String msg) {
                searchLoading = false;
                if (tab != 0) return;
                hint("搜索失败：" + msg);
            }
        });
    }

    /** 搜索翻页：滑到接近底部时自动续接下一页（每页 50 条，去重追加） */
    private void loadMoreSearch() {
        if (searchKw.isEmpty() || searchLoading || !searchMore) return;
        searchLoading = true;
        updateSearchFooter();
        final int next = searchPage + 1;
        api.search(searchKw, next, searchOrder, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                searchLoading = false;
                if (tab != 0) { updateSearchFooter(); return; }
                searchPage = next;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Track t : displayTracks) seen.add(t.bvid);
                for (Track t : v) if (seen.add(t.bvid)) displayTracks.add(t);
                trackAdapter.notifyDataSetChanged();
                searchMore = v.size() >= 50;
                updateSearchFooter();
            }
            @Override public void onErr(String msg) {
                searchLoading = false;
                updateSearchFooter();
            }
        });
    }

    private void updateSearchFooter() {
        if (searchFooter == null) return;
        if (tab != 0 || displayTracks.isEmpty() || searchKw.isEmpty()) {
            searchFooter.setVisibility(View.GONE);
            return;
        }
        searchFooter.setVisibility(View.VISIBLE);
        if (searchLoading) searchFooter.setText("加载中…");
        else if (searchMore) searchFooter.setText("第 " + searchPage + " 页 · 已加载 " + displayTracks.size() + " 条 · 继续下滑加载更多");
        else searchFooter.setText("— 全部加载完 · 共 " + displayTracks.size() + " 条 —");
    }

    // ---------------- 播放统计（内嵌历史页顶部，与历史列表以分割线分开） ----------------
    private View statsHeaderView;
    private boolean statsAttached;
    private StatsDb statsDb;
    private int statPeriod = 1;
    private final TextView[] statChips = new TextView[5];
    private final List<StatsDb.Row> statRows = new ArrayList<>();
    private static final String[] STAT_PERIOD_NAMES = {"日", "周", "月", "年", "总"};
    private static final int[] STAT_PERIOD_DAYS = {1, 7, 30, 365, 0};

    private void attachStatsHeader() {
        if (statsDb == null) statsDb = new StatsDb(this);
        if (statsHeaderView == null) buildStatsHeader();
        if (!statsAttached) {
            lvMain.addHeaderView(statsHeaderView);
            statsAttached = true;
        }
        refreshStatsHeader();
    }

    private void detachStatsHeader() {
        if (statsAttached && statsHeaderView != null) {
            lvMain.removeHeaderView(statsHeaderView);
            statsAttached = false;
        }
    }

    private void buildStatsHeader() {
        statsHeaderView = android.view.LayoutInflater.from(this).inflate(R.layout.view_stats_header, lvMain, false);
        statPeriod = getSharedPreferences("ui", MODE_PRIVATE).getInt("stats_period", 1);
        LinearLayout box = statsHeaderView.findViewById(R.id.llStatPeriods);
        float den = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (34 * den), 1);
            lp.setMargins(3, 0, 3, 0);
            chip.setLayoutParams(lp);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setText(STAT_PERIOD_NAMES[i]);
            chip.setTextSize(12.5f);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> {
                if (statPeriod != idx) {
                    Haptics.tick(this);
                    statPeriod = idx;
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("stats_period", idx).apply();
                    styleStatChips();
                    refreshStatsHeader();
                }
            });
            statChips[i] = chip;
            box.addView(chip);
        }
        styleStatChips();
    }

    private void styleStatChips() {
        for (int i = 0; i < 5; i++) {
            boolean on = i == statPeriod;
            if (on) {
                statChips[i].setBackground(ThemeUtil.accentGradient(this, 17));
                statChips[i].setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
                statChips[i].setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                statChips[i].setBackgroundResource(R.drawable.bg_chip_pill);
                statChips[i].setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
                statChips[i].setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void refreshStatsHeader() {
        if (statsHeaderView == null || statsDb == null) return;
        String to = StatsDb.todayKey();
        String from = STAT_PERIOD_DAYS[statPeriod] == 0 ? "0000-01-01" : StatsDb.daysAgoKey(STAT_PERIOD_DAYS[statPeriod]);
        String rangeText = STAT_PERIOD_DAYS[statPeriod] == 0 ? "全部记录"
                : STAT_PERIOD_DAYS[statPeriod] == 1 ? "今天 · " + to : from + " ~ " + to;
        ((TextView) statsHeaderView.findViewById(R.id.tvStatRange)).setText(rangeText);
        StatsDb.Sum sum = statsDb.summary(from, to);
        ((TextView) statsHeaderView.findViewById(R.id.tvStatPlays)).setText(String.valueOf(sum.plays));
        ((TextView) statsHeaderView.findViewById(R.id.tvStatTime)).setText(fmtListen(sum.seconds));
        ((TextView) statsHeaderView.findViewById(R.id.tvStatTracks)).setText(String.valueOf(statsDb.distinctSongs(from, to)));
        statRows.clear();
        statRows.addAll(statsDb.top(from, to, 10));
        long max = 1;
        for (StatsDb.Row r : statRows) if (r.plays > max) max = r.plays;
        LinearLayout box = statsHeaderView.findViewById(R.id.llStatTop);
        box.removeAllViews();
        statsHeaderView.findViewById(R.id.tvStatEmpty).setVisibility(statRows.isEmpty() ? View.VISIBLE : View.GONE);
        float den = getResources().getDisplayMetrics().density;
        for (int i = 0; i < statRows.size(); i++) {
            final int idx = i;
            final StatsDb.Row r = statRows.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (8 * den), 0, (int) (8 * den));
            TextView rank = new TextView(this);
            rank.setLayoutParams(new LinearLayout.LayoutParams((int) (26 * den), LinearLayout.LayoutParams.WRAP_CONTENT));
            rank.setGravity(android.view.Gravity.CENTER);
            rank.setText(String.valueOf(i + 1));
            rank.setTextSize(14);
            rank.setTextColor(ThemeUtil.color(this, i < 3 ? R.attr.gAccent : R.attr.gTextFaint));
            if (i < 3) rank.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(rank);
            LinearLayout mid = new LinearLayout(this);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            mlp.leftMargin = (int) (10 * den);
            mid.setLayoutParams(mlp);
            mid.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(r.title == null || r.title.isEmpty() ? r.bvid : r.title);
            title.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
            title.setTextSize(13.5f);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(title);
            TextView author = new TextView(this);
            author.setText(r.author == null ? "" : r.author);
            author.setTextColor(ThemeUtil.color(this, R.attr.gTextFaint));
            author.setTextSize(11);
            author.setSingleLine(true);
            mid.addView(author);
            LinearLayout bar = new LinearLayout(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (6 * den));
            blp.topMargin = (int) (5 * den);
            bar.setLayoutParams(blp);
            bar.setBackgroundResource(R.drawable.bg_bar_track);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (float) r.plays));
            fill.setBackground(ThemeUtil.accentGradient(this, 3));
            bar.addView(fill);
            View gap = new View(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (float) (max - r.plays)));
            bar.addView(gap);
            mid.addView(bar);
            row.addView(mid);
            TextView meta = new TextView(this);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = (int) (10 * den);
            meta.setLayoutParams(tlp);
            meta.setGravity(android.view.Gravity.RIGHT);
            meta.setText(r.plays + " 次" + (r.seconds >= 60 ? "\n" + fmtListen(r.seconds) : ""));
            meta.setTextColor(ThemeUtil.color(this, R.attr.gTextSec));
            meta.setTextSize(11.5f);
            row.addView(meta);
            row.setOnClickListener(v -> {
                Haptics.press(this);
                List<Track> ts = new ArrayList<>();
                for (StatsDb.Row rr : statRows) {
                    Track t = new Track();
                    t.bvid = rr.bvid;
                    t.title = rr.title;
                    t.author = rr.author;
                    t.cover = rr.cover;
                    ts.add(t);
                }
                playTracks(ts, idx);
            });
            box.addView(row);
        }
    }

    static String fmtListen(long sec) {
        if (sec < 60) return sec + " 秒";
        long m = sec / 60;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    /** 搜索排序条：B 站官方五种排序，用户自选并记住；切换后自动按当前词重搜 */
    private void buildSortChips() {
        llSortChips = findViewById(R.id.llSortChips);
        searchOrder = getSharedPreferences("ui", MODE_PRIVATE).getString("search_order", "");
        float den = getResources().getDisplayMetrics().density;
        for (int i = 0; i < SORTS.length; i++) {
            final String ord = SORTS[i][1];
            TextView chip = new TextView(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, (int) (34 * den), 1);
            lp.setMargins(3, 0, 3, 0);
            chip.setLayoutParams(lp);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setText(SORTS[i][0]);
            chip.setTextSize(12.5f);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> {
                if (!ord.equals(searchOrder)) {
                    Haptics.tick(this);
                    searchOrder = ord;
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putString("search_order", ord).apply();
                    styleSortChips();
                    if (!searchKw.isEmpty()) doSearch(searchKw);
                }
            });
            sortChips[i] = chip;
            llSortChips.addView(chip);
        }
        styleSortChips();
    }

    private void styleSortChips() {
        for (int i = 0; i < SORTS.length; i++) {
            boolean on = SORTS[i][1].equals(searchOrder);
            if (on) {
                sortChips[i].setBackground(ThemeUtil.accentGradient(this, 17));
                sortChips[i].setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
                sortChips[i].setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                sortChips[i].setBackgroundResource(R.drawable.bg_chip_pill);
                sortChips[i].setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
                sortChips[i].setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    // ---------------- 收藏 ----------------
    private void loadFolders() {
        if (!api.isLoggedIn()) {
            folders.clear();
            lvMain.setAdapter(folderAdapter);
            folderAdapter.notifyDataSetChanged();
            hint("登录后就能看你的收藏夹啦\n点「我的」去登录 👇");
            return;
        }
        hint("加载收藏夹…");
        api.favFolders(new BiliApi.Cb<List<BiliApi.FavFolder>>() {
            @Override public void onOk(List<BiliApi.FavFolder> v) {
                if (tab != 1) return;
                folders.clear();
                folders.addAll(v);
                showFolders();
                hint(v.isEmpty() ? "你还没有收藏夹" : null);
            }
            @Override public void onErr(String msg) {
                if (tab != 1) return;
                hint("收藏夹加载失败：" + msg);
            }
        });
    }

    private void showFolders() {
        lvMain.setAdapter(folderAdapter);
        folderAdapter.notifyDataSetChanged();
        btnFavBack.setVisibility(View.GONE);
        if (tab == 1) llFavSwitch.setVisibility(View.VISIBLE);
    }

    private void styleFavChips() {
        chipFavBili.setBackgroundResource(favLocalMode ? R.drawable.bg_chip_pill : R.drawable.bg_chip_selected);
        chipFavBili.setTextColor(ThemeUtil.color(this, favLocalMode ? R.attr.gTextSec : R.attr.gOnAccent));
        chipFavLocal.setBackgroundResource(favLocalMode ? R.drawable.bg_chip_selected : R.drawable.bg_chip_pill);
        chipFavLocal.setTextColor(ThemeUtil.color(this, favLocalMode ? R.attr.gOnAccent : R.attr.gTextSec));
        btnNewLocal.setVisibility(favLocalMode ? View.VISIBLE : View.GONE);
        IconUtil.leading(chipFavBili, R.drawable.ic_tab_fav, favLocalMode ? R.attr.gTextSec : R.attr.gOnAccent, 6);
        IconUtil.leading(chipFavLocal, R.drawable.ic_note, favLocalMode ? R.attr.gOnAccent : R.attr.gTextSec, 6);
    }

    private void showLocalLists() {
        localLists.clear();
        localLists.addAll(localDb.playlists());
        lvMain.setAdapter(localAdapter);
        localAdapter.notifyDataSetChanged();
        btnFavBack.setVisibility(View.GONE);
        hint(localLists.isEmpty() ? "还没有本地歌单 🎵\n点右上角「＋ 新建」建一个\n或长按任意歌曲加入歌单" : null);
    }

    private void openFolder(BiliApi.FavFolder f) {
        hint("加载「" + f.title + "」…");
        api.favTracks(f.id, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                if (tab != 1) return;
                favShowingTracks = true;
                llFavSwitch.setVisibility(View.GONE);
                displayTracks.clear();
                displayTracks.addAll(v);
                lvMain.setAdapter(trackAdapter);
                trackAdapter.notifyDataSetChanged();
                btnFavBack.setVisibility(View.VISIBLE);
                hint(v.isEmpty() ? "这个收藏夹是空的" : null);
            }
            @Override public void onErr(String msg) {
                if (tab != 1) return;
                hint("加载失败：" + msg);
            }
        });
    }

    // ---------------- 历史 / 我的 ----------------
    private void showHistory() {
        displayTracks.clear();
        displayTracks.addAll(history.list());
        lvMain.setAdapter(trackAdapter);
        trackAdapter.notifyDataSetChanged();
        hint(displayTracks.isEmpty() ? "还没有播放历史" : null);
    }

    private void refreshMine() {
        api.myInfo(new BiliApi.Cb<BiliApi.MyInfo>() {
            @Override public void onOk(BiliApi.MyInfo mi) {
                if (mi.login) {
                    tvUname.setText(mi.uname.isEmpty() ? "已登录" : mi.uname);
                    tvMid.setText(mi.mid > 0 ? "UID " + mi.mid : "");
                    btnLogin.setText("退出");
                    if (!mi.face.isEmpty()) ImgLoader.load(ivFace, mi.face);
                } else {
                    tvUname.setText("未登录");
                    tvMid.setText("登录后可用收藏夹、更高音质");
                    btnLogin.setText("登录");
                    ivFace.setImageBitmap(null);
                }
            }
            @Override public void onErr(String msg) {
                tvUname.setText(api.isLoggedIn() ? "已登录" : "未登录");
                btnLogin.setText(api.isLoggedIn() ? "退出" : "登录");
            }
        });
    }

    // ---------------- 播放 ----------------
    private void playTracks(List<Track> tracks, int pos) {
        PlayerService.ensureStarted(this);
        new Handler().postDelayed(() -> {
            PlayerService s = PlayerService.get();
            if (s == null) {
                Toast.makeText(this, "播放服务还没起来，再点一次试试", Toast.LENGTH_SHORT).show();
                return;
            }
            s.playQueue(tracks, pos);
            startActivity(new Intent(this, PlayerActivity.class));
        }, 350);
    }

    @Override
    public void onTrackChanged(Track t) {
        tvMiniTitle.setText(t.title);
        ImgLoader.load(ivMiniCover, t.cover);
    }

    @Override
    public void onStateChanged(boolean playing) {
        btnMiniToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override
    public void onError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------- adapters ----------------
    private final BaseAdapter trackAdapter = new BaseAdapter() {
        @Override public int getCount() { return displayTracks.size(); }
        @Override public Object getItem(int p) { return displayTracks.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_track, parent, false);
            Track t = displayTracks.get(p);
            ImgLoader.load(cv.findViewById(R.id.ivCover), t.cover);
            ((TextView) cv.findViewById(R.id.tvTitle)).setText(t.title);
            String sub = (t.author == null ? "" : t.author) + " · " + Track.fmtDur(t.durationSec);
            ((TextView) cv.findViewById(R.id.tvSub)).setText(sub);
            return cv;
        }
    };

    private final BaseAdapter localAdapter = new BaseAdapter() {
        @Override public int getCount() { return localLists.size(); }
        @Override public Object getItem(int p) { return localLists.get(p); }
        @Override public long getItemId(int p) { return localLists.get(p).id; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_folder, parent, false);
            LocalDb.Playlist pl = localLists.get(p);
            ((TextView) cv.findViewById(R.id.tvFolderTitle)).setText(pl.name);
            ((TextView) cv.findViewById(R.id.tvFolderCount)).setText(pl.count + " 首");
            return cv;
        }
    };

    private final BaseAdapter folderAdapter = new BaseAdapter() {
        @Override public int getCount() { return folders.size(); }
        @Override public Object getItem(int p) { return folders.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_folder, parent, false);
            BiliApi.FavFolder f = folders.get(p);
            ((TextView) cv.findViewById(R.id.tvFolderTitle)).setText(f.title);
            ((TextView) cv.findViewById(R.id.tvFolderCount)).setText(f.count + " 个视频");
            return cv;
        }
    };
}
