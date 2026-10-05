package com.guga.music;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class PlayerActivity extends Activity implements PlayerService.Listener {

    private ImageView ivCover;
    private ImageView backdropView;
    private TextView tvTitle, tvAuthor, tvPos, tvDur, btnToggle, btnMode, btnQuality;
    private SeekBar sb;
    private ListView lvQueue;
    private LyricsView lyView;
    private BiliApi api;
    private boolean seeking = false;
    private final Handler handler = new Handler();
    private ObjectAnimator discSpin;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            PlayerService s = PlayerService.get();
            if (s != null && s.isPrepared() && !seeking) {
                int dur = s.getDuration();
                int pos = s.getPosition();
                if (dur > 0) {
                    sb.setMax(dur);
                    sb.setProgress(pos);
                    lyView.setPosition(pos);
                    tvPos.setText(Track.fmtDur(pos / 1000));
                    tvDur.setText(Track.fmtDur(dur / 1000));
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_player);
        ivCover = findViewById(R.id.ivCover);
        ImageView ivBackdrop = findViewById(R.id.ivBackdrop);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ivBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    45f, 45f, android.graphics.Shader.TileMode.CLAMP));
        }
        ivBackdrop.setAlpha(0.85f);
        backdropView = ivBackdrop;
        tvTitle = findViewById(R.id.tvTitle);
        tvAuthor = findViewById(R.id.tvAuthor);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        btnToggle = findViewById(R.id.btnToggle);
        btnMode = findViewById(R.id.btnMode);
        btnQuality = findViewById(R.id.btnQuality);
        sb = findViewById(R.id.sbProgress);
        lvQueue = findViewById(R.id.lvQueue);
        lyView = findViewById(R.id.lyView);
        api = new BiliApi(getApplicationContext());
        lyView.setOnClickListener(v -> {
            PlayerService s = PlayerService.get();
            if (s != null && s.current() != null) {
                startActivity(new android.content.Intent(this, LyricsActivity.class));
            }
        });
        lvQueue.setAdapter(queueAdapter);
        lvQueue.setOnItemClickListener((p, v, pos, id) -> {
            PlayerService s = PlayerService.get();
            if (s != null) s.playAt(pos);
        });

        btnToggle.setOnClickListener(v -> { PlayerService s = PlayerService.get(); if (s != null) s.toggle(); });
        findViewById(R.id.btnNext).setOnClickListener(v -> { PlayerService s = PlayerService.get(); if (s != null) s.next(true); });
        findViewById(R.id.btnPrev).setOnClickListener(v -> { PlayerService s = PlayerService.get(); if (s != null) s.prev(); });
        btnQuality.setOnClickListener(v -> showQualityDialog());
        btnMode.setOnClickListener(v -> {
            PlayerService s = PlayerService.get();
            if (s != null) {
                s.cycleMode();
                refreshMode();
                Toast.makeText(this, "播放模式：" + modeName(s.getMode()), Toast.LENGTH_SHORT).show();
            }
        });
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (fromUser) tvPos.setText(Track.fmtDur(prog / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar b) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar b) {
                seeking = false;
                PlayerService s = PlayerService.get();
                if (s != null) s.seekTo(b.getProgress());
            }
        });
        // 唱片旋转动画：18 秒一圈，匀速
        discSpin = ObjectAnimator.ofFloat(ivCover, "rotation", 0f, 360f);
        discSpin.setDuration(18000);
        discSpin.setRepeatCount(ObjectAnimator.INFINITE);
        discSpin.setInterpolator(new LinearInterpolator());
        discSpin.start();
        discSpin.pause();
        refreshMode();
    }

    private void loadLyrics(final Track t) {
        lyView.setPlaceholder("歌词加载中…");
        Lyrics.fetchFor(this, t, api, r -> {
            PlayerService s = PlayerService.get();
            if (s == null || s.current() == null || !t.bvid.equals(s.current().bvid)) return;
            if (r.has()) lyView.setLines(r.lines, r.source);
            else lyView.setPlaceholder("暂无歌词（点我看全屏）");
        });
    }

    private static final String[] QUALITY_SHORT = {"64K", "132K", "192K", "Hi-Res", "杜比"};

    private void refreshQuality() {
        PlayerService s = PlayerService.get();
        if (s != null && btnQuality != null) btnQuality.setText(QUALITY_SHORT[s.getQualityTier()]);
    }

    private void showQualityDialog() {
        PlayerService s = PlayerService.get();
        if (s == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择音质（立即生效）")
                .setSingleChoiceItems(PlayerService.QUALITY_NAMES, s.getQualityTier(), (d, which) -> {
                    s.setQualityTier(which);
                    s.applyQualityChange();
                    refreshQuality();
                    Toast.makeText(this, "已切到「" + PlayerService.QUALITY_NAMES[which] + "」🎵", Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String modeName(int m) {
        return m == PlayerService.MODE_LOOP ? "单曲循环" : m == PlayerService.MODE_SHUFFLE ? "随机播放" : "顺序播放";
    }

    private void refreshMode() {
        PlayerService s = PlayerService.get();
        btnMode.setText(s == null ? "顺序" : modeName(s.getMode()).replace("播放", ""));
    }

    @Override
    public void onBackPressed() {
        // 回到主界面而不是退到桌面
        android.content.Intent it = new android.content.Intent(this, MainActivity.class);
        it.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(it);
        finish();
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
            refreshQuality();
            onStateChanged(s.isPlaying());
            queueAdapter.notifyDataSetChanged();
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        PlayerService s = PlayerService.get();
        if (s != null) s.removeListener(this);
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    public void onTrackChanged(Track t) {
        loadLyrics(t);
        tvTitle.setText(t.title);
        tvAuthor.setText(t.author == null ? "" : t.author);
        ImgLoader.loadDisc(ivCover, t.cover);
        if (backdropView != null) ImgLoader.load(backdropView, t.cover);
        sb.setProgress(0);
        tvPos.setText("00:00");
        queueAdapter.notifyDataSetChanged();
    }

    @Override
    public void onStateChanged(boolean playing) {
        btnToggle.setText(playing ? "⏸" : "▶");
        if (discSpin != null) {
            if (playing) discSpin.resume();
            else discSpin.pause();
        }
    }

    @Override
    public void onError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private final BaseAdapter queueAdapter = new BaseAdapter() {
        @Override public int getCount() {
            PlayerService s = PlayerService.get();
            return s == null ? 0 : s.getQueue().size();
        }
        @Override public Object getItem(int p) {
            PlayerService s = PlayerService.get();
            return s == null ? null : s.getQueue().get(p);
        }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(PlayerActivity.this).inflate(R.layout.item_track, parent, false);
            PlayerService s = PlayerService.get();
            if (s == null) return cv;
            Track t = s.getQueue().get(p);
            ImgLoader.load(cv.findViewById(R.id.ivCover), t.cover);
            TextView title = cv.findViewById(R.id.tvTitle);
            title.setText(t.title);
            title.setTextColor(ThemeUtil.color(PlayerActivity.this, p == s.getIndex() ? R.attr.gAccent : R.attr.gTextPri));
            ((TextView) cv.findViewById(R.id.tvSub)).setText((t.author == null ? "" : t.author) + " · " + Track.fmtDur(t.durationSec));
            return cv;
        }
    };
}
