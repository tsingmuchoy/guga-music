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
    private final TextView[] tabViews = new TextView[4];

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

        EditText etSearch = findViewById(R.id.etSearch);
        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch(etSearch.getText().toString().trim()));
        etSearch.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(etSearch.getText().toString().trim());
                return true;
            }
            return false;
        });

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabViews[i].setOnClickListener(v -> selectTab(idx));
        }

        lvMain.setOnItemClickListener((p, v, pos, id) -> {
            if (tab == 1 && favLocalMode) {
                if (pos < localLists.size()) {
                    LocalDb.Playlist pl = localLists.get(pos);
                    Intent it = new Intent(this, LocalPlaylistActivity.class);
                    it.putExtra("pid", pl.id);
                    it.putExtra("name", pl.name);
                    startActivity(it);
                }
            } else if (tab == 1 && !favShowingTracks) {
                if (pos < folders.size()) openFolder(folders.get(pos));
            } else if (tab != 3) {
                if (pos < displayTracks.size()) playTracks(new ArrayList<>(displayTracks), pos);
            }
        });
        lvMain.setOnItemLongClickListener((p, v, pos, id) -> {
            if (tab == 1 && favLocalMode) {
                if (pos < localLists.size()) {
                    final LocalDb.Playlist pl = localLists.get(pos);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(pl.name)
                            .setItems(new String[]{"✏️ 重命名", "🗑️ 删除歌单"}, (d, w) -> {
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
            if (PlayerService.get() != null && PlayerService.get().current() != null) {
                startActivity(new Intent(this, PlayerActivity.class));
            }
        });
        btnMiniToggle.setOnClickListener(v -> {
            PlayerService s = PlayerService.get();
            if (s != null) s.toggle();
        });
        findViewById(R.id.btnMiniNext).setOnClickListener(v -> {
            PlayerService s = PlayerService.get();
            if (s != null) s.next(true);
        });

        btnLogin.setOnClickListener(v -> {
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
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        localDb = new LocalDb(this);
        llFavSwitch = findViewById(R.id.llFavSwitch);
        chipFavBili = findViewById(R.id.chipFavBili);
        chipFavLocal = findViewById(R.id.chipFavLocal);
        btnNewLocal = findViewById(R.id.btnNewLocal);
        chipFavBili.setOnClickListener(v -> {
            favLocalMode = false;
            styleFavChips();
            favShowingTracks = false;
            btnFavBack.setVisibility(View.GONE);
            loadFolders();
        });
        chipFavLocal.setOnClickListener(v -> {
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
    private void selectTab(int i) {
        tab = i;
        for (int k = 0; k < 4; k++) {
            tabViews[k].setTextColor(ThemeUtil.color(this, k == i ? R.attr.gAccent : R.attr.gTextSec));
        }
        llSearchBar.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
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
        api.search(kw, 1, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> v) {
                if (tab != 0) return;
                displayTracks.clear();
                displayTracks.addAll(v);
                lvMain.setAdapter(trackAdapter);
                trackAdapter.notifyDataSetChanged();
                hint(v.isEmpty() ? "没搜到结果，换个词试试" : null);
            }
            @Override public void onErr(String msg) {
                if (tab != 0) return;
                hint("搜索失败：" + msg);
            }
        });
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
            ((TextView) cv.findViewById(R.id.tvFolderTitle)).setText("🎵 " + pl.name);
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
