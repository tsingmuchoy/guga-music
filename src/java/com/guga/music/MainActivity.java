package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;
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
    private boolean recommendStarted = false; // 主页推荐只在进 App 时拉一次
    private boolean hasSearched = false; // 搜过之后推荐回调不再回填，避免盖掉搜索结果
    // 推荐换一换：整榜缓存 + 每批 30 条轮换，换完一轮切榜单维度（总榜→日榜→周榜→月榜→总榜）
    private List<Track> recommendAll = new ArrayList<>();
    private int recommendBatch = 0;
    private int recommendTypeIdx = 0;
    private static final String[] RECOMMEND_TYPES = {"all", "day", "week", "month"};
    private static final String[] RECOMMEND_TYPE_NAMES = {"总榜", "日榜", "周榜", "月榜"};
    private View searchFooter;
    private List<Track> favTracksAll = new ArrayList<>();
    private int favPage = 1;
    private int favTotalPages = 0;
    private BiliApi.FavFolder favOpenFolder;
    private float swipeBackPx, swipeBackSlopPx;
    private TextView btnPagePrev, btnPageNext, tvPageInfo;
    private int searchTotalPages = 0; // 接口给总条数时算出，0 表示未知（下一页按本页是否满 20 条判断）
    // 下拉刷新（纯框架手写，主流逻辑：到顶下拉→箭头跟手旋转→松手过阈值触发→转圈→丝滑收回）
    private View pullHeader;
    private android.widget.ImageView ivPullArrow;
    private TextView tvPullText;
    private float pullHeightPx, touchSlopPx;
    private float pullReveal = 0;
    private boolean pullEngaged = false;
    private boolean pullRefreshing = false;
    private boolean pullQuiet = false; // 刷新中不刷“加载中…”居中提示（头上已有转圈）
    private android.animation.ObjectAnimator pullSpin;
    private android.widget.LinearLayout llSortChips;
    private static final String[][] SORTS = {
            {"综合", ""}, {"最多播放", "click"}, {"最新发布", "pubdate"}, {"最多弹幕", "dm"}, {"最多收藏", "stow"}};
    private final TextView[] sortChips = new TextView[SORTS.length];
    private String searchOrder = "";
    private final List<BiliApi.FavFolder> folders = new ArrayList<>();
    private View llFavSwitch;
    private TextView chipFavBili, chipFavLocal, btnNewLocal, btnImportLocal;
    private boolean favLocalMode = false;
    private final List<LocalDb.Playlist> localLists = new ArrayList<>();
    private LocalDb localDb;

    private View llSearchBar, svMine;
    private ListView lvMain;
    private View rootMain;
    private GlassBarLayout barMini, barTabs;
    private boolean lensBars;
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
        Haptics.attachScrollRatchet(findViewById(R.id.svMine));
        Haptics.attachScrollRatchet(findViewById(R.id.svStats));
        UpdateChecker.autoCheck(this);
        api = new BiliApi(this);
        history = new HistoryDb(this);
        PlayerService.ensureStarted(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        llSearchBar = findViewById(R.id.llSearchBar);
        svMine = findViewById(R.id.svMine);
        lvMain = findViewById(R.id.lvMain);
        // 搜索页底部分页条：像 B 站那样上一页 / 第 X / 共 Y 页 / 下一页
        android.widget.LinearLayout pageBar = new android.widget.LinearLayout(this);
        pageBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        pageBar.setGravity(android.view.Gravity.CENTER);
        float fden = getResources().getDisplayMetrics().density;
        pageBar.setPadding(0, (int) (12 * fden), 0, (int) (16 * fden));
        btnPagePrev = pageBtn("◀ 上一页");
        btnPagePrev.setOnClickListener(v -> { Haptics.tick(this); if (tab == 1 && favShowingTracks) gotoFavPage(favPage - 1); else gotoSearchPage(searchPage - 1); });
        tvPageInfo = new TextView(this);
        tvPageInfo.setTextSize(12);
        tvPageInfo.setTextColor(ThemeUtil.color(this, R.attr.gTextFaint));
        tvPageInfo.setPadding((int) (16 * fden), 0, (int) (16 * fden), 0);
        btnPageNext = pageBtn("下一页 ▶");
        btnPageNext.setOnClickListener(v -> { Haptics.tick(this); if (tab == 1 && favShowingTracks) gotoFavPage(favPage + 1); else gotoSearchPage(searchPage + 1); });
        pageBar.addView(btnPagePrev);
        pageBar.addView(tvPageInfo);
        pageBar.addView(btnPageNext);
        searchFooter = pageBar;
        searchFooter.setVisibility(View.GONE);
        lvMain.addFooterView(searchFooter);
        buildSortChips();
        lvMain.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) { lvScrollState = state; }
            @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                // 滚动棘轮触感：划过每个条目轻震一下（与 Haptics.attachRatchet 同逻辑，本列表已有监听器故内联）
                if (lvScrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                        && ratchetIdx >= 0 && first != ratchetIdx) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - ratchetAt >= 45) { ratchetAt = now; Haptics.tick(MainActivity.this); }
                }
                ratchetIdx = first;
                // 真液态玻璃：列表滚动时底衬在变，底栏需重录
                if (lensBars) invalidateGlassBars();
            }
        });
        rootMain = findViewById(R.id.rootMain);
        // 下拉刷新：只观察不消费，列表原有滚动/点击不受影响
        pullHeader = findViewById(R.id.pullHeader);
        ivPullArrow = findViewById(R.id.ivPullArrow);
        tvPullText = findViewById(R.id.tvPullText);
        pullHeightPx = 64f * getResources().getDisplayMetrics().density;
        touchSlopPx = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        swipeBackPx = 100f * getResources().getDisplayMetrics().density;
        swipeBackSlopPx = 60f * getResources().getDisplayMetrics().density;
        lvMain.setOnTouchListener(new View.OnTouchListener() {
            private float downY, downX;
            private int downPointer = -1;
            private float pullBaseline;
            private boolean atTopAtDown;
            private boolean swipeBackClaimed = false;
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = ev.getY();
                        downX = ev.getX();
                        downPointer = ev.getPointerId(0);
                        atTopAtDown = isListAtTop();
                        swipeBackClaimed = false;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (swipeBackClaimed) return true; // 已认领右滑返回：后续事件不再进列表，防松手误点新页条目
                        if (pullRefreshing) break;
                        int idx = downPointer < 0 ? -1 : ev.findPointerIndex(downPointer);
                        if (idx < 0) break;
                        float y = ev.getY(idx);
                        if (!pullEngaged) {
                            // 接管条件：只在搜索/推荐页（tab 0），手指从按下点向下滑过系统触摸阈值、已到顶、非飞滚。
                            // 收藏夹、历史页不要下拉（用户定），那两页拉了也没头。
                            if (tab == 0 && y - downY > touchSlopPx
                                    && lvScrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_FLING
                                    && isListAtTop()) {
                                pullEngaged = true;
                                // 基线：按下时已在顶——从按下点起算（快拉也不丢距离）；
                                // 中途滚到顶才接管——从接管点起算（不突然蹦出一大截）
                                pullBaseline = atTopAtDown ? downY + touchSlopPx : y;
                                pullHeader.setVisibility(View.VISIBLE);
                                lvMain.setOverScrollMode(View.OVER_SCROLL_NEVER);
                            }
                        }
                        if (pullEngaged) {
                            float pull = (y - pullBaseline) * 0.5f; // 阻尼 0.5：拉着有韧劲
                            if (pull <= 0) {
                                setPullReveal(0);
                                pullEngaged = false;
                                pullHeader.setVisibility(View.GONE);
                                lvMain.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                            } else {
                                setPullReveal(Math.min(pull, pullHeightPx * 1.5f));
                            }
                        }
                        // 收藏夹内容页右滑返回（与下拉互斥：竖向幅度大就不算右滑）
                        if (!pullEngaged && tab == 1 && favShowingTracks) {
                            float dx = ev.getX(idx) - downX;
                            float ddy = y - downY;
                            if (dx > swipeBackPx && Math.abs(ddy) < swipeBackSlopPx) {
                                swipeBackClaimed = true;
                                backFromFolder();
                            }
                        }
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        if (swipeBackClaimed) {
                            swipeBackClaimed = false;
                            downPointer = -1;
                            return true;
                        }
                        if (pullEngaged) {
                            pullEngaged = false;
                            lvMain.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                            if (pullReveal >= pullHeightPx) startPullRefresh();
                            else animatePullReveal(0, null);
                        }
                        downPointer = -1;
                        break;
                    }
                }
                return swipeBackClaimed;
            }
        });
        // 真液态玻璃：仅启用透镜的主题 + API 33+ 才实例化透镜引擎，其他主题零成本
        lensBars = GlassTheme.isLensActive(this);
        barMini = findViewById(R.id.llMini);
        barTabs = findViewById(R.id.llTabs);
        GlassTheme.bindRoot(rootMain);
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
                    UiDialog.menu(this, pl.name, new String[]{"重命名", "删除歌单"}, w -> {
                        if (w == 0) {
                            PlaylistPicker.createDialog(this, "重命名歌单", pl.name, "保存", name -> {
                                localDb.renamePlaylist(pl.id, name);
                                showLocalLists();
                            });
                        } else {
                            UiDialog.confirm(this, "删除歌单",
                                    "删除歌单「" + pl.name + "」？里面记录的歌曲会一起删掉", "删除", () -> {
                                        localDb.deletePlaylist(pl.id);
                                        showLocalLists();
                                    });
                        }
                    });
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
                UiDialog.confirm(this, "退出登录？", null, "退出", () -> {
                    api.logout();
                    refreshMine();
                    Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
                });
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
        btnImportLocal = findViewById(R.id.btnImportLocal);
        btnImportLocal.setOnClickListener(v -> {
            Haptics.tick(this);
            startActivity(new android.content.Intent(this, PlaylistImportActivity.class));
        });

        selectTab(0);
        // 透镜绑定后重刷芯片样式：选中态改走透镜染色，不再盖实色背景
        if (lensBars) {
            styleSortChips();
            styleFavChips();
            View btnSearch = findViewById(R.id.btnSearch);
            if (btnSearch instanceof GlassTextView && ((GlassTextView) btnSearch).isLensOn()) {
                ((GlassTextView) btnSearch).setLensTint(GlassTheme.selectedTint(this));
            }
        }
        hint("搜一首歌，开始听吧 🎧");
    }

    @Override
    public void onBackPressed() {
        // 返回键先在 App 内逐级退：收藏内容 -> 收藏夹列表；其他页 -> 搜索页；已在搜索页才退出
        if (tab == 1 && favShowingTracks) {
            backFromFolder();
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
        llFavSwitch.setVisibility(i == 1 && !favShowingTracks ? View.VISIBLE : View.GONE);
        tvHint.setVisibility(View.GONE);
        switch (i) {
            case 0:
                lvMain.setAdapter(trackAdapter);
                trackAdapter.notifyDataSetChanged();
                if (displayTracks.isEmpty()) {
                    // 进主页先推音乐：第一次进搜索页且还没搜过，拉音乐区排行填满空页
                    if (!recommendStarted) {
                        recommendStarted = true;
                        loadRecommendations();
                    } else {
                        hint("搜一首歌，开始听吧 🎧");
                    }
                }
                break;
            case 1:
                favShowingTracks = false;
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
        // 真液态玻璃：切页帧先让新内容上屏，底栏透镜下一帧再重录。
        // 同帧重录要连做数次整页捕获（底栏+搜索区），正是切页顿一下的元凶之一；
        // 底栏只占底部小条，晚一帧的旧折射看不出来。搜索区玻璃随显隐自动重绘，不用另行刷。
        if (lensBars) {
            if (barMini != null) barMini.post(barMini::invalidate);
            if (barTabs != null) barTabs.post(barTabs::invalidate);
        }
    }

    /** 真液态玻璃重录：底栏 + 搜索区（列表滚动/切页后底衬变了时调用） */
    private void invalidateGlassBars() {
        if (barMini != null) barMini.invalidate();
        if (barTabs != null) barTabs.invalidate();
        if (!lensBars) return;
        GlassTheme.invalidateGlass(llSearchBar);
        GlassTheme.invalidateGlass(llSortChips);
        GlassTheme.invalidateGlass(llFavSwitch);
    }

    /** 芯片透镜染色：透镜开着时选中态不再盖实色背景，改走 85% accent 染色；返回是否走了透镜分支 */
    private boolean styleChipLens(TextView chip, boolean on) {
        if (chip instanceof GlassTextView && ((GlassTextView) chip).isLensOn()) {
            ((GlassTextView) chip).setLensTint(on ? GlassTheme.selectedTint(this) : 0);
            return true;
        }
        return false;
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
        if (!pullQuiet) hint("搜索中…");
        searchKw = kw;
        hasSearched = true;
        searchPage = 1;
        searchMore = true;
        searchLoading = true;
        api.search(kw, 1, searchOrder, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                searchLoading = false;
                boolean wasPull = pullRefreshing;
                finishPullRefresh();
                if (tab != 0) return;
                boolean identical = wasPull && sameBvids(displayTracks, v);
                displayTracks.clear();
                displayTracks.addAll(v);
                lvMain.setAdapter(trackAdapter);
                trackAdapter.notifyDataSetChanged();
                searchMore = v.size() >= 20;
                refreshSearchTotal();
                updateSearchFooter();
                if (identical) Toast.makeText(MainActivity.this, "搜索结果已是最新", Toast.LENGTH_SHORT).show();
                else lvMain.setSelection(0);
                hint(v.isEmpty() ? "没搜到结果，换个词试试" : null);
            }
            @Override public void onErr(String msg) {
                searchLoading = false;
                boolean wasPull = pullRefreshing;
                finishPullRefresh();
                if (tab != 0) return;
                if (wasPull) Toast.makeText(MainActivity.this, "刷新失败：" + msg, Toast.LENGTH_SHORT).show();
                else hint("搜索失败：" + msg);
            }
        });
    }

    /** 搜索翻页：像 B 站那样上一页/下一页整页切换（每页 20 条），不再下滑追加 */
    private void gotoSearchPage(int page) {
        if (searchKw.isEmpty() || searchLoading || page < 1) return;
        if (searchTotalPages > 0 && page > searchTotalPages) return;
        searchLoading = true;
        updateSearchFooter();
        final int target = page;
        api.search(searchKw, target, searchOrder, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                searchLoading = false;
                if (tab != 0) { updateSearchFooter(); return; }
                searchPage = target;
                displayTracks.clear();
                displayTracks.addAll(v);
                trackAdapter.notifyDataSetChanged();
                searchMore = v.size() >= 20;
                refreshSearchTotal();
                updateSearchFooter();
                lvMain.setSelection(0);
                if (v.isEmpty()) hint("这页没结果");
            }
            @Override public void onErr(String msg) {
                searchLoading = false;
                if (tab == 0) hint("翻页失败：" + msg);
                updateSearchFooter();
            }
        });
    }

    /** 分页条按钮：主题强调色，禁用时变淡 */
    private TextView pageBtn(String t) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(13);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        float d = getResources().getDisplayMetrics().density;
        b.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
        b.setTextColor(ThemeUtil.color(this, R.attr.gAccent));
        return b;
    }

    private void setPageBtn(TextView b, boolean enabled) {
        if (b == null) return;
        b.setEnabled(enabled);
        b.setTextColor(ThemeUtil.color(this, enabled ? R.attr.gAccent : R.attr.gTextFaint));
    }

    /** 按接口总条数算总页数；接口没给时总页数未知（0） */
    private void refreshSearchTotal() {
        int total = api.lastSearchTotal;
        searchTotalPages = total > 0 ? (total + 19) / 20 : 0;
    }

    /** 两批搜索结果是否同一批 bvid 同顺序（下拉刷新判“已是最新”用） */
    private boolean sameBvids(List<Track> a, List<Track> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i).bvid, y = b.get(i).bvid;
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    private void updateSearchFooter() {
        if (searchFooter == null) return;
        if (tab == 1 && favShowingTracks) { updateFavFooter(); return; }
        if (tab != 0 || displayTracks.isEmpty() || searchKw.isEmpty()) {
            searchFooter.setVisibility(View.GONE);
            return;
        }
        searchFooter.setVisibility(View.VISIBLE);
        if (searchLoading) {
            tvPageInfo.setText("加载中…");
            setPageBtn(btnPagePrev, false);
            setPageBtn(btnPageNext, false);
            return;
        }
        boolean hasPrev = searchPage > 1;
        boolean hasNext = searchTotalPages > 0 ? searchPage < searchTotalPages : searchMore;
        tvPageInfo.setText(searchTotalPages > 0
                ? "第 " + searchPage + " / 共 " + searchTotalPages + " 页"
                : "第 " + searchPage + " 页");
        setPageBtn(btnPagePrev, hasPrev);
        setPageBtn(btnPageNext, hasNext);
    }

    // ---------------- 播放统计（历史页上半独立滚动区，与下方历史列表分栏） ----------------
    private int statsGen;
    private int lvScrollState;
    private int ratchetIdx = -1;
    private long ratchetAt;
    private View statsHeaderView;
    private View llSplitHandle;
    private boolean splitInitDone;
    private float splitStartY, splitLastY;
    private int splitStartH, splitTotalH;
    private boolean splitArmed, splitAtLimit;
    private final Runnable splitArmTask = new Runnable() {
        @Override public void run() {
            splitArmed = true;
            splitAtLimit = false;
            splitStartY = splitLastY;
            splitStartH = statsHeaderView.getHeight();
            splitTotalH = splitStartH + ((View) lvMain.getParent()).getHeight();
            Haptics.press(MainActivity.this); // 长按激活拖动：一下 3D 触感确认
        }
    };
    private StatsDb statsDb;
    private int statPeriod = 1;
    private final TextView[] statChips = new TextView[6];
    private final List<StatsDb.Row> statRows = new ArrayList<>();
    private static final String[] STAT_PERIOD_NAMES = {"日", "周", "月", "年", "总", "自定义"};
    private static final int[] STAT_PERIOD_DAYS = {1, 7, 30, 365, 0, -1};
    private static final int REQ_STAT_EXPORT = 7101, REQ_STAT_IMPORT = 7102;

    private void attachStatsHeader() {
        if (statsDb == null) statsDb = new StatsDb(this);
        if (statsHeaderView == null) buildStatsHeader();
        statsHeaderView.setVisibility(View.VISIBLE);
        if (llSplitHandle != null) llSplitHandle.setVisibility(View.VISIBLE);
        if (!splitInitDone) {
            splitInitDone = true;
            statsHeaderView.post(this::applySavedSplitRatio);
        }
        refreshStatsHeader();
    }

    private void detachStatsHeader() {
        if (statsHeaderView != null) statsHeaderView.setVisibility(View.GONE);
        if (llSplitHandle != null) llSplitHandle.setVisibility(View.GONE);
    }

    /** 分栏比例恢复（存的是统计区占分栏总高的比例，换机/转屏都稳） */
    private void applySavedSplitRatio() {
        float ratio = getSharedPreferences("ui", MODE_PRIVATE).getFloat("stats_pane_ratio", -1f);
        if (ratio < 0f) return;
        View frame = (View) lvMain.getParent();
        int total = statsHeaderView.getHeight() + frame.getHeight();
        if (total <= 0) return;
        setStatsPaneHeight(Math.round(total * ratio));
    }

    private int setStatsPaneHeight(int px) {
        View frame = (View) lvMain.getParent();
        int total = statsHeaderView.getHeight() + frame.getHeight();
        if (total <= 0) return statsHeaderView.getHeight();
        float den = getResources().getDisplayMetrics().density;
        // 上限：手柄底边不能越过底部功能栏区（预留 150dp，就是历史列表原有的底部留白）
        int max = Math.max(0, total - (int) (150 * den));
        int h = Math.max(0, Math.min(px, max));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) statsHeaderView.getLayoutParams();
        lp.height = h;
        lp.weight = 0;
        statsHeaderView.setLayoutParams(lp);
        return h;
    }

    private void setupSplitDrag() {
        llSplitHandle = findViewById(R.id.llSplitHandle);
        if (llSplitHandle == null) return;
        final float den = getResources().getDisplayMetrics().density;
        llSplitHandle.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // 先按下不动：等长按激活才允许拖（防滚动列表时误触分隔条）
                    splitLastY = e.getRawY();
                    splitStartY = e.getRawY();
                    splitArmed = false;
                    llSplitHandle.postDelayed(splitArmTask,
                            android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dyDown = e.getRawY() - splitStartY;
                    splitLastY = e.getRawY();
                    if (!splitArmed) {
                        if (Math.abs(dyDown) > 12 * den) llSplitHandle.removeCallbacks(splitArmTask);
                        return true;
                    }
                    int want = splitStartH + (int) (e.getRawY() - splitStartY);
                    int applied = setStatsPaneHeight(want);
                    boolean atLimit = applied != want;
                    if (atLimit && !splitAtLimit) Haptics.tick(MainActivity.this); // 顶/底到界提醒
                    splitAtLimit = atLimit;
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    llSplitHandle.removeCallbacks(splitArmTask);
                    if (splitArmed && splitTotalH > 0) {
                        getSharedPreferences("ui", MODE_PRIVATE).edit()
                                .putFloat("stats_pane_ratio",
                                        statsHeaderView.getHeight() / (float) splitTotalH)
                                .apply();
                    }
                    splitArmed = false;
                    return true;
            }
            return true;
        });
    }

    private void buildStatsHeader() {
        statsHeaderView = findViewById(R.id.llStatsPane);
        setupSplitDrag();
        statsHeaderView.findViewById(R.id.btnStatExport).setOnClickListener(v -> { Haptics.tick(this); exportStats(); });
        statsHeaderView.findViewById(R.id.btnStatImport).setOnClickListener(v -> { Haptics.tick(this); importStats(); });
        statsHeaderView.findViewById(R.id.btnStatClear).setOnClickListener(v -> { Haptics.tick(this); confirmClearStats(); });
        statPeriod = getSharedPreferences("ui", MODE_PRIVATE).getInt("stats_period", 1);
        LinearLayout box = statsHeaderView.findViewById(R.id.llStatPeriods);
        float den = getResources().getDisplayMetrics().density;
        for (int i = 0; i < STAT_PERIOD_NAMES.length; i++) {
            final int idx = i;
            GlassTextView chip = new GlassTextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (34 * den), 1);
            lp.setMargins(3, 0, 3, 0);
            chip.setLayoutParams(lp);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setText(STAT_PERIOD_NAMES[i]);
            chip.setTextSize(12.5f);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> {
                if (idx == 5) { Haptics.tick(this); pickCustomRange(); return; }
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
        // 统计头是后建的：绑定透镜后再刷一次样式，选中态走透镜染色
        GlassTheme.bindActivity(this);
        styleStatChips();
    }

    // ---------- 统计：清空 / 备份导出 / 恢复 / 自定义范围 ----------

    /** 弹窗换上 App 自家深色圆角底（系统默认灰板跟主题不搭） */
    private void dlgDarkBg(AlertDialog dlg) {
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
    }

    private void confirmClearStats() {
        UiDialog.confirm(this, "清空播放统计？",
                "日/周/月/年所有听歌记录都会删掉，不可恢复。建议先导出一份备份。", "清空", () -> {
                    // 二级确认：第一层点过去还不够，误触到这里还能刹车
                    UiDialog.confirm(this, "最后确认一次",
                            "清空后播放次数、累计时长、曲目数和最常播放榜立刻归零，无法找回。确定要清空吗？",
                            "确认清空", () -> {
                                statsDb.clearAll();
                                refreshStatsHeader();
                                android.widget.Toast.makeText(this, "播放统计已清空", android.widget.Toast.LENGTH_SHORT).show();
                            });
                });
    }

    private void exportStats() {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/json");
        it.putExtra(Intent.EXTRA_TITLE, "guga-stats-" + StatsDb.todayKey() + ".json");
        try { startActivityForResult(it, REQ_STAT_EXPORT); }
        catch (Exception e) { android.widget.Toast.makeText(this, "打不开文件保存界面", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    private void importStats() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try { startActivityForResult(it, REQ_STAT_IMPORT); }
        catch (Exception e) { android.widget.Toast.makeText(this, "打不开文件选择界面", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_STAT_EXPORT) writeStatsBackup(data.getData());
        else if (req == REQ_STAT_IMPORT) readStatsBackup(data.getData());
    }

    private void writeStatsBackup(final android.net.Uri uri) {
        new Thread(() -> {
            String msg;
            try {
                java.util.List<String[]> rows = statsDb.exportRows();
                JSONObject root = new JSONObject();
                root.put("app", "guga-music");
                root.put("kind", "stats-backup");
                root.put("ver", 1);
                root.put("exportedAt", StatsDb.todayKey());
                JSONArray arr = new JSONArray();
                for (String[] r : rows) {
                    JSONArray jr = new JSONArray();
                    jr.put(r[0]); jr.put(r[1]);
                    jr.put(Long.parseLong(r[2])); jr.put(Long.parseLong(r[3]));
                    jr.put(r[4]); jr.put(r[5]); jr.put(r[6]);
                    arr.put(jr);
                }
                root.put("rows", arr);
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(root.toString().getBytes("UTF-8"));
                os.flush(); os.close();
                msg = "备份已导出（" + rows.size() + " 天×歌 记录）";
            } catch (Exception e) { msg = "导出失败：" + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show());
        }).start();
    }

    private void readStatsBackup(final android.net.Uri uri) {
        new Thread(() -> {
            final java.util.List<String[]> rows = new java.util.ArrayList<>();
            String err = null;
            try {
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                if (!"stats-backup".equals(root.optString("kind"))) { err = "这不是 Apollo 的统计备份文件"; }
                else {
                    JSONArray arr = root.optJSONArray("rows");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONArray jr = arr.optJSONArray(i);
                        if (jr == null || jr.length() < 4) continue;
                        rows.add(new String[]{jr.optString(0), jr.optString(1),
                                String.valueOf(jr.optLong(2)), String.valueOf(jr.optLong(3)),
                                jr.optString(4), jr.optString(5), jr.optString(6)});
                    }
                }
            } catch (Exception e) { err = "读取备份失败：" + e.getMessage(); }
            final String ferr = err;
            runOnUiThread(() -> {
                if (ferr != null) { android.widget.Toast.makeText(this, ferr, android.widget.Toast.LENGTH_LONG).show(); return; }
                AlertDialog dlgImport = new AlertDialog.Builder(this)
                        .setTitle("恢复这份备份？")
                        .setMessage("将导入 " + rows.size() + " 条记录，并覆盖当前全部播放统计。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复", (d, w) -> new Thread(() -> {
                            statsDb.importRows(rows);
                            runOnUiThread(() -> {
                                refreshStatsHeader();
                                android.widget.Toast.makeText(this, "备份已恢复", android.widget.Toast.LENGTH_SHORT).show();
                            });
                        }).start())
                        .create();
                dlgDarkBg(dlgImport);
            });
        }).start();
    }

    private static int[] parseDayKey(String key) {
        try {
            String[] p = key.split("-");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2])};
        } catch (Exception e) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            return new int[]{c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)};
        }
    }

    private static long dayMillis(int[] ymd) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(ymd[0], ymd[1], ymd[2], 0, 0, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private interface DatePickCb { void onPick(int y, int m0, int d); }

    /** 自绘日期弹窗：系统 DatePickerDialog 是块大灰板，跟 App 风格不搭；
     *  改成自家深色圆角卡 + 内嵌日历控件，选中色仍走系统强调色 */
    private void showDatePick(String title, int[] init, long minMs, long maxMs, DatePickCb cb) {
        View root = getLayoutInflater().inflate(R.layout.dialog_date_pick, null);
        ((TextView) root.findViewById(R.id.tvPickTitle)).setText(title);
        final android.widget.DatePicker dp = root.findViewById(R.id.dpPick);
        dp.init(init[0], init[1], init[2], null);
        if (minMs > 0) dp.setMinDate(minMs);
        if (maxMs > 0) dp.setMaxDate(maxMs);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();
        root.findViewById(R.id.btnPickCancel).setOnClickListener(v -> dlg.dismiss());
        root.findViewById(R.id.btnPickOk).setOnClickListener(v -> {
            Haptics.press(this);
            cb.onPick(dp.getYear(), dp.getMonth(), dp.getDayOfMonth());
            dlg.dismiss();
        });
        // 系统 DatePicker 日历头在部分系统版本上忽略 headerBackground：
        // 把 DatePicker 第一个子容器（即日历头）的背景也清掉，头文字直接落在深色卡上
        try {
            if (dp.getChildCount() > 0 && dp.getChildAt(0) instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) dp.getChildAt(0)).setBackground(null);
            }
        } catch (Exception ignored) {}
        try {
            dlg.show();
        } catch (Exception ignored) {}
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        GlassTheme.bindWithBackdrop(root, GlassTheme.activityBackdrop(this));
    }

    /** 自定义统计范围：连选两次日期（开始 → 结束），选完存档并切到「自定义」档 */
    private void pickCustomRange() {
        SharedPreferences up = getSharedPreferences("ui", MODE_PRIVATE);
        int[] f0 = parseDayKey(up.getString("stats_custom_from", StatsDb.daysAgoKey(30)));
        final long today = dayMillis(parseDayKey(StatsDb.todayKey()));
        java.util.Calendar minC = java.util.Calendar.getInstance();
        minC.add(java.util.Calendar.DAY_OF_YEAR, -399);
        final long minAll = minC.getTimeInMillis();
        showDatePick("选择开始日期", f0, minAll, today, (y, m, d) -> {
            final String fromKey = String.format(java.util.Locale.CHINA, "%04d-%02d-%02d", y, m + 1, d);
            int[] t0 = parseDayKey(up.getString("stats_custom_to", StatsDb.todayKey()));
            showDatePick("选择结束日期", t0, dayMillis(parseDayKey(fromKey)), today, (y2, m2, d2) -> {
                String toKey = String.format(java.util.Locale.CHINA, "%04d-%02d-%02d", y2, m2 + 1, d2);
                up.edit().putString("stats_custom_from", fromKey).putString("stats_custom_to", toKey).apply();
                statPeriod = 5;
                getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("stats_period", 5).apply();
                styleStatChips();
                refreshStatsHeader();
            });
        });
    }

    private void styleStatChips() {
        for (int i = 0; i < STAT_PERIOD_NAMES.length; i++) {
            boolean on = i == statPeriod;
            if (on) {
                if (!styleChipLens(statChips[i], true)) statChips[i].setBackground(ThemeUtil.accentGradient(this, 17));
                statChips[i].setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
                statChips[i].setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                if (!styleChipLens(statChips[i], false)) statChips[i].setBackgroundResource(R.drawable.bg_chip_pill);
                statChips[i].setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
                statChips[i].setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void refreshStatsHeader() {
        if (statsHeaderView == null || statsDb == null) return;
        String to, from, rangeText;
        if (statPeriod == 5) { // 自定义：日历里选的起止日期（存在 ui 配置）
            SharedPreferences up = getSharedPreferences("ui", MODE_PRIVATE);
            from = up.getString("stats_custom_from", StatsDb.daysAgoKey(30));
            to = up.getString("stats_custom_to", StatsDb.todayKey());
            rangeText = from + " ~ " + to + " · 自定义";
        } else {
            to = StatsDb.todayKey();
            from = STAT_PERIOD_DAYS[statPeriod] == 0 ? "0000-01-01" : StatsDb.daysAgoKey(STAT_PERIOD_DAYS[statPeriod]);
            rangeText = STAT_PERIOD_DAYS[statPeriod] == 0 ? "全部记录"
                    : STAT_PERIOD_DAYS[statPeriod] == 1 ? "今天 · " + to : from + " ~ " + to;
        }
        ((TextView) statsHeaderView.findViewById(R.id.tvStatRange)).setText(rangeText);
        // 汇总/曲目数/Top 榜的查询与聚类挪到后台线程，数据攒多后切历史页也不在主线程卡帧；
        // gen 守卫：连点档位时只认最后一次的结果
        final int gen = ++statsGen;
        final String fFrom = from, fTo = to;
        new Thread(() -> {
            final StatsDb.Sum sum = statsDb.summary(fFrom, fTo);
            final int distinct = statsDb.distinctSongs(fFrom, fTo);
            final java.util.List<StatsDb.Row> top = statsDb.top(fFrom, fTo, 10);
            runOnUiThread(() -> {
                if (gen != statsGen || statsHeaderView == null) return;
                // sum/distinct/top 已在后台算好
                ((PixelNumberView) statsHeaderView.findViewById(R.id.tvStatPlays)).setText(String.valueOf(sum.plays));
                ((PixelNumberView) statsHeaderView.findViewById(R.id.tvStatTime)).setText(fmtListen(sum.seconds));
                ((PixelNumberView) statsHeaderView.findViewById(R.id.tvStatTracks)).setText(String.valueOf(distinct));
                statRows.clear();
                statRows.addAll(top);
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
                    PixelNumberView rank = new PixelNumberView(this, null);
                    rank.setLayoutParams(new LinearLayout.LayoutParams((int) (26 * den), LinearLayout.LayoutParams.WRAP_CONTENT));
                    rank.setTextSize(14);
                    rank.setTextColor(ThemeUtil.color(this, i < 3 ? R.attr.gAccent : R.attr.gTextFaint));
                    rank.setText(String.valueOf(i + 1));
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
                    LinearLayout metaBox = new LinearLayout(this);
                    metaBox.setOrientation(LinearLayout.VERTICAL);
                    metaBox.setGravity(android.view.Gravity.END);
                    LinearLayout.LayoutParams mbp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    mbp.leftMargin = (int) (10 * den);
                    metaBox.setLayoutParams(mbp);
                    PixelNumberView metaLine1 = new PixelNumberView(this, null);
                    metaLine1.setTextSize(11.5f);
                    metaLine1.setTextColor(ThemeUtil.color(this, R.attr.gTextSec));
                    metaLine1.setText(r.plays + " 次");
                    metaBox.addView(metaLine1);
                    if (r.seconds >= 60) {
                        PixelNumberView metaLine2 = new PixelNumberView(this, null);
                        metaLine2.setTextSize(11.5f);
                        metaLine2.setTextColor(ThemeUtil.color(this, R.attr.gTextSec));
                        metaLine2.setText(fmtListen(r.seconds));
                        metaBox.addView(metaLine2);
                    }
                    row.addView(metaBox);
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
    
            });
        }).start();
    }

    static String fmtListen(long sec) {
        if (sec < 60) return sec + "S";
        long m = sec / 60;
        if (m < 60) return m + "M";
        return (m / 60) + "H" + (m % 60) + "M";
    }

    /** 搜索排序条：B 站官方五种排序，用户自选并记住；切换后自动按当前词重搜 */
    private void buildSortChips() {
        llSortChips = findViewById(R.id.llSortChips);
        searchOrder = getSharedPreferences("ui", MODE_PRIVATE).getString("search_order", "");
        float den = getResources().getDisplayMetrics().density;
        for (int i = 0; i < SORTS.length; i++) {
            final String ord = SORTS[i][1];
            GlassTextView chip = new GlassTextView(this);
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
                if (!styleChipLens(sortChips[i], true)) sortChips[i].setBackground(ThemeUtil.accentGradient(this, 17));
                sortChips[i].setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
                sortChips[i].setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                if (!styleChipLens(sortChips[i], false)) sortChips[i].setBackgroundResource(R.drawable.bg_chip_pill);
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
        if (!pullQuiet) hint("加载收藏夹…");
        // 切页当帧立刻换上收藏夹适配器（先用上次缓存的 folders，没有就空列表），
        // 别等网络回来才换——否则这帧 ListView 里还是上个页的内容，
        // 看着就是“先显示上个页面、等网络回来才刷新”
        lvMain.setAdapter(folderAdapter);
        folderAdapter.notifyDataSetChanged();
        api.favFolders(new BiliApi.Cb<List<BiliApi.FavFolder>>() {
            @Override public void onOk(List<BiliApi.FavFolder> v) {
                finishPullRefresh();
                if (tab != 1) return;
                folders.clear();
                folders.addAll(v);
                showFolders();
                hint(v.isEmpty() ? "你还没有收藏夹" : null);
            }
            @Override public void onErr(String msg) {
                boolean wasPull = pullRefreshing;
                finishPullRefresh();
                if (tab != 1) return;
                if (wasPull) Toast.makeText(MainActivity.this, "刷新失败：" + msg, Toast.LENGTH_SHORT).show();
                else hint("收藏夹加载失败：" + msg);
            }
        });
    }

    private void showFolders() {
        lvMain.setAdapter(folderAdapter);
        folderAdapter.notifyDataSetChanged();
        if (tab == 1) llFavSwitch.setVisibility(View.VISIBLE);
    }

    private void styleFavChips() {
        if (!styleChipLens(chipFavBili, !favLocalMode)) chipFavBili.setBackgroundResource(favLocalMode ? R.drawable.bg_chip_pill : R.drawable.bg_chip_selected);
        chipFavBili.setTextColor(ThemeUtil.color(this, favLocalMode ? R.attr.gTextSec : R.attr.gOnAccent));
        if (!styleChipLens(chipFavLocal, favLocalMode)) chipFavLocal.setBackgroundResource(favLocalMode ? R.drawable.bg_chip_selected : R.drawable.bg_chip_pill);
        chipFavLocal.setTextColor(ThemeUtil.color(this, favLocalMode ? R.attr.gOnAccent : R.attr.gTextSec));
        btnNewLocal.setVisibility(favLocalMode ? View.VISIBLE : View.GONE);
        if (btnImportLocal != null) btnImportLocal.setVisibility(favLocalMode ? View.VISIBLE : View.GONE);
        IconUtil.leading(chipFavBili, R.drawable.ic_tab_fav, favLocalMode ? R.attr.gTextSec : R.attr.gOnAccent, 6);
        IconUtil.leading(chipFavLocal, R.drawable.ic_note, favLocalMode ? R.attr.gOnAccent : R.attr.gTextSec, 6);
    }

    private void showLocalLists() {
        localLists.clear();
        localLists.addAll(localDb.playlists());
        lvMain.setAdapter(localAdapter);
        localAdapter.notifyDataSetChanged();
        hint(localLists.isEmpty() ? "还没有本地歌单 🎵\n点右上角「＋ 新建」建一个，或「⇩ 导入」把其他软件的歌单搬进来\n或长按任意歌曲加入歌单" : null);
    }

    /** 收藏夹内容页退回列表：右滑与系统返回键共用同一条路 */
    private void backFromFolder() {
        favShowingTracks = false;
        showFolders();
    }

    private void openFolder(BiliApi.FavFolder f) {
        favOpenFolder = f;
        if (!pullQuiet) hint("加载「" + f.title + "」…");
        api.favTracks(f.id, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                finishPullRefresh();
                if (tab != 1) return;
                favShowingTracks = true;
                llFavSwitch.setVisibility(View.GONE);
                favTracksAll.clear();
                favTracksAll.addAll(v);
                favPage = 1;
                favTotalPages = (v.size() + 19) / 20;
                lvMain.setAdapter(trackAdapter);
                showFavPage();
                hint(v.isEmpty() ? "这个收藏夹是空的" : null);
            }
            @Override public void onErr(String msg) {
                boolean wasPull = pullRefreshing;
                finishPullRefresh();
                if (tab != 1) return;
                if (wasPull) Toast.makeText(MainActivity.this, "刷新失败：" + msg, Toast.LENGTH_SHORT).show();
                else hint("加载失败：" + msg);
            }
        });
    }

    /** 收藏夹内容页分页：每页 20 条（夹子几百首也不怕翻） */
    private void showFavPage() {
        int from = (favPage - 1) * 20;
        int to = Math.min(from + 20, favTracksAll.size());
        displayTracks.clear();
        displayTracks.addAll(favTracksAll.subList(from, to));
        trackAdapter.notifyDataSetChanged();
        updateFavFooter();
    }

    private void gotoFavPage(int page) {
        if (!favShowingTracks || page < 1 || page > favTotalPages || page == favPage) return;
        favPage = page;
        showFavPage();
        lvMain.setSelection(0);
    }

    private void updateFavFooter() {
        if (searchFooter == null) return;
        if (favTracksAll.isEmpty() || favTotalPages <= 1) {
            searchFooter.setVisibility(View.GONE);
            return;
        }
        searchFooter.setVisibility(View.VISIBLE);
        tvPageInfo.setText("第 " + favPage + " / 共 " + favTotalPages + " 页");
        setPageBtn(btnPagePrev, favPage > 1);
        setPageBtn(btnPageNext, favPage < favTotalPages);
    }

    // ---------------- 历史 / 我的 ----------------
    // ---------------- 下拉刷新 ----------------

    private boolean isListAtTop() {
        if (lvMain.getChildCount() == 0) return true;
        return lvMain.getFirstVisiblePosition() == 0 && lvMain.getChildAt(0).getTop() >= 0;
    }

    /** 设置揭示高度：头从顶边露出 r，列表跟着下移 r，箭头随揭示度旋转 */
    private void setPullReveal(float r) {
        pullReveal = r;
        pullHeader.setTranslationY(r - pullHeightPx);
        lvMain.setTranslationY(r);
        if (!pullRefreshing) {
            ivPullArrow.setRotation(Math.min(r / pullHeightPx, 1f) * 180f);
            tvPullText.setText(r >= pullHeightPx ? "松开刷新" : "下拉刷新");
        }
    }

    /** 丝滑收/放到目标高度（减速插值，280ms）；到 0 时彻底藏起并复位 */
    private void animatePullReveal(float target, final Runnable onEnd) {
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(pullReveal, target);
        va.setDuration(280);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> setPullReveal((Float) a.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (target == 0) {
                    pullHeader.setVisibility(View.GONE);
                    lvMain.setTranslationY(0);
                    ivPullArrow.setRotation(0);
                    tvPullText.setText("下拉刷新");
                }
                if (onEnd != null) onEnd.run();
            }
        });
        va.start();
    }

    private void startPullRefresh() {
        pullRefreshing = true;
        pullQuiet = true;
        tvPullText.setText("正在刷新…");
        pullSpin = android.animation.ObjectAnimator.ofFloat(ivPullArrow, "rotation", 0f, 360f);
        pullSpin.setDuration(900);
        pullSpin.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        pullSpin.setInterpolator(new android.view.animation.LinearInterpolator());
        pullSpin.start();
        animatePullReveal(pullHeightPx, null); // 先稳稳停在完全展开
        dispatchPullRefresh();
    }

    /** 刷新完成：停转、丝滑收回；没在刷新时是空操作，各回调里可无条件调 */
    private void finishPullRefresh() {
        if (!pullRefreshing) return;
        pullRefreshing = false;
        pullQuiet = false;
        if (pullSpin != null) { pullSpin.cancel(); pullSpin = null; }
        animatePullReveal(0, null);
    }

    /** 按当前页派发刷新：推荐重拉、搜索重查第 1 页、收藏重载、历史重算 */
    private void dispatchPullRefresh() {
        switch (tab) {
            case 0:
                if (searchKw.isEmpty()) {
                    refreshRecommendations();
                } else if (!searchLoading) {
                    doSearch(searchKw);
                } else {
                    finishPullRefresh();
                }
                break;
            case 1: // 收藏夹不要下拉（用户定）
            case 2:
            default:
                finishPullRefresh();
                break;
        }
    }

    /** 主页推荐：进 App 时把 B 站音乐区排行填进搜索页，点行就能直接播 */
    private void loadRecommendations() {
        fetchRanking(recommendTypeIdx, false);
    }

    /** 拉指定维度的榜单；成功则整榜缓存、回到第 1 批；换榜下拉成功时 Toast 告诉用户换到哪个榜 */
    private void fetchRanking(final int typeIdx, final boolean toastOnSuccess) {
        if (!pullQuiet) hint("正在为你找今日音乐推荐… 🎵");
        api.musicRanking(RECOMMEND_TYPES[typeIdx], new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                finishPullRefresh();
                // 切走了就别回填（displayTracks 与历史页共用），下次回搜索页再拉；
                // 拉取途中用户已搜过也别盖掉搜索结果
                if (tab != 0) { recommendStarted = false; return; }
                if (hasSearched) return;
                recommendTypeIdx = typeIdx;
                recommendAll.clear();
                recommendAll.addAll(v);
                recommendBatch = 0;
                showRecommendBatch();
                if (toastOnSuccess) Toast.makeText(MainActivity.this, "已换成" + RECOMMEND_TYPE_NAMES[typeIdx], Toast.LENGTH_SHORT).show();
                hint(v.isEmpty() ? "搜一首歌，开始听吧 🎧" : null);
            }
            @Override public void onErr(String msg) {
                boolean wasPull = pullRefreshing;
                finishPullRefresh();
                if (tab != 0) { recommendStarted = false; return; }
                // 首屏总榜失败退日榜（保留旧行为）；下拉换榜失败不退，老内容不动
                if (!wasPull && typeIdx == 0) { fetchRanking(1, false); return; }
                Diag.log(MainActivity.this, "🎵 音乐推荐加载失败：" + msg);
                if (wasPull) Toast.makeText(MainActivity.this, "刷新失败：" + msg, Toast.LENGTH_SHORT).show();
                else hint("搜一首歌，开始听吧 🎧");
            }
        });
    }

    /** 推荐当前批（每批 30 条）上屏 */
    private void showRecommendBatch() {
        int from = recommendBatch * 30;
        int to = Math.min(from + 30, recommendAll.size());
        displayTracks.clear();
        if (to > from) displayTracks.addAll(recommendAll.subList(from, to));
        lvMain.setAdapter(trackAdapter);
        trackAdapter.notifyDataSetChanged();
    }

    /** 推荐页下拉 = 换一换：有下一批直接切（秒换、无需联网），一轮换完或只有一批就换榜单维度重拉 */
    private void refreshRecommendations() {
        recommendStarted = false;
        hasSearched = false;
        if (recommendAll.isEmpty()) {
            loadRecommendations();
            return;
        }
        int batches = (recommendAll.size() + 29) / 30;
        if (batches > 1) {
            int next = (recommendBatch + 1) % batches;
            if (next == 0) {
                fetchRanking((recommendTypeIdx + 1) % RECOMMEND_TYPES.length, true);
            } else {
                recommendBatch = next;
                showRecommendBatch();
                finishPullRefresh();
            }
        } else {
            fetchRanking((recommendTypeIdx + 1) % RECOMMEND_TYPES.length, true);
        }
    }

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
