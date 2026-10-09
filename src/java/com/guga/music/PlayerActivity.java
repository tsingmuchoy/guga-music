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
    private TextView tvTitle, tvAuthor, tvPos, tvDur, btnMode, btnQuality;
    private android.widget.ImageView btnToggle;
    private SeekBar sb;
    private ListView lvQueue;
    private LyricsView lyView;
    private android.widget.LinearLayout llQualityChips;
    private final TextView[] chipViews = new TextView[5];
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
        llQualityChips = findViewById(R.id.llQualityChips);
        buildQualityChips();
        sb = findViewById(R.id.sbProgress);
        lvQueue = findViewById(R.id.lvQueue);
        tvQueueTitle = findViewById(R.id.tvQueueTitle);
        tvQueueEmpty = findViewById(R.id.tvQueueEmpty);
        lyView = findViewById(R.id.lyView);
        api = new BiliApi(getApplicationContext());
        lyView.setOnClickListener(v -> {
            Haptics.tick(this);
            PlayerService s = PlayerService.get();
            if (s != null && s.current() != null) {
                startActivity(new android.content.Intent(this, LyricsActivity.class));
            }
        });
        lvQueue.setAdapter(queueAdapter);
        setupQueueSheet();
        lvQueue.setOnItemClickListener((p, v, pos, id) -> {
            Haptics.tick(this);
            PlayerService s = PlayerService.get();
            if (s != null) s.playAt(pos);
        });

        btnToggle.setOnClickListener(v -> { Haptics.press(this); PlayerService s = PlayerService.get(); if (s != null) s.toggle(); });
        findViewById(R.id.btnNext).setOnClickListener(v -> { Haptics.tick(this); PlayerService s = PlayerService.get(); if (s != null) s.next(true); });
        findViewById(R.id.btnPrev).setOnClickListener(v -> { Haptics.tick(this); PlayerService s = PlayerService.get(); if (s != null) s.prev(); });
        btnQuality.setOnClickListener(v -> { Haptics.tick(this); toggleQualityChips(); });
        findViewById(R.id.btnAddList).setOnClickListener(v -> {
            Haptics.tick(this);
            PlayerService svc = PlayerService.get();
            if (svc != null && svc.current() != null) PlaylistPicker.show(this, svc.current());
        });
        btnMode.setOnClickListener(v -> {
            Haptics.tick(this);
            PlayerService s = PlayerService.get();
            if (s != null) {
                s.cycleMode();
                refreshMode();
                Toast.makeText(this, "播放模式：" + modeName(s.getMode()), Toast.LENGTH_SHORT).show();
            }
        });
        setupGradientChrome();
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
        if (s == null) return;
        // 显示实际在播的档位；实际档位未知（加载中）时先显示生效的请求档位
        int disp = s.getActualTier() >= 0 ? s.getActualTier() : s.getEffectiveTier();
        if (btnQuality != null) { btnQuality.setText(QUALITY_SHORT[disp]); ThemeUtil.gradientText(btnQuality); }
        styleQualityChips(disp);
    }

    /** 主题渐变落地（v1.24.2 瘦身：播放键只字形渐变不要大圆底；进度条压回 4dp 细线 + 12dp 小圆钮） */
    private TextView tvQueueTitle, tvQueueEmpty;

    private void refreshQueueMeta() {
        PlayerService s = PlayerService.get();
        int n = s == null ? 0 : s.getQueue().size();
        if (tvQueueTitle != null) tvQueueTitle.setText("播放队列" + (n > 0 ? " · " + n + " 首" : ""));
        if (tvQueueEmpty != null) tvQueueEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (lvQueue != null) lvQueue.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
    }

    private void scrollQueueToCurrent() {
        PlayerService s = PlayerService.get();
        if (s == null || lvQueue == null) return;
        final int idx = s.getIndex();
        if (idx >= 0 && idx < s.getQueue().size()) lvQueue.post(() -> lvQueue.setSelection(idx));
    }

    // ---------------- 播放队列上滑面板 ----------------
    private View llQueueSheet, llSheetHead;
    private android.graphics.Bitmap sheetBlurBmp;
    private boolean sheetTouchActive;
    private final Runnable blurTask = new Runnable() {
        @Override public void run() {
            if (sheetTouchActive) { scheduleSheetBlur(250); return; }
            refreshSheetBlur();
        }
    };

    /** 毛玻璃刷新合并：同一时间只排一个，触摸拖动中先避让，落位后再抓图 */
    private void scheduleSheetBlur(long delayMs) {
        if (llQueueSheet == null) return;
        llQueueSheet.removeCallbacks(blurTask);
        llQueueSheet.postDelayed(blurTask, delayMs);
    }
    private int sheetMinH, sheetMaxH;
    private boolean sheetExpanded;
    private float dragStartY;
    private int dragStartH;
    private boolean sheetDragMoved;

    private void setupQueueSheet() {
        llQueueSheet = findViewById(R.id.llQueueSheet);
        llSheetHead = findViewById(R.id.llSheetHead);
        final float den = getResources().getDisplayMetrics().density;
        sheetMaxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
        llQueueSheet.post(() -> {
            sheetMinH = llSheetHead.getHeight() + (int) (78 * den);
            if (!sheetExpanded) setSheetHeight(sheetMinH);
            scheduleSheetBlur(0);
        });
        llSheetHead.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    sheetTouchActive = true;
                    dragStartY = e.getRawY();
                    dragStartH = llQueueSheet.getHeight();
                    sheetDragMoved = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dy = dragStartY - e.getRawY();
                    if (Math.abs(dy) > 6 * den) sheetDragMoved = true;
                    setSheetHeight(clampSheet(dragStartH + (int) dy));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    sheetTouchActive = false;
                    if (!sheetDragMoved) {
                        animateSheetTo(sheetExpanded ? sheetMinH : sheetMaxH);
                    } else {
                        animateSheetTo(llQueueSheet.getHeight() > (sheetMinH + sheetMaxH) / 2 ? sheetMaxH : sheetMinH);
                    }
                    return true;
                }
            }
            return false;
        });
        lvQueue.setOnTouchListener((v, e) -> sheetListTouch(e));
    }

    /** 列表区的拖拽接管：收起时上滑展开；展开且列表已到顶时下滑收起；其余情况还给列表自己滚 */
    private boolean sheetListTouch(android.view.MotionEvent e) {
        final float den = getResources().getDisplayMetrics().density;
        switch (e.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                sheetTouchActive = true;
                dragStartY = e.getRawY();
                dragStartH = llQueueSheet.getHeight();
                sheetDragMoved = false;
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dy = dragStartY - e.getRawY();
                if (sheetDragMoved) {
                    setSheetHeight(clampSheet(dragStartH + (int) dy));
                    return true;
                }
                if (!sheetExpanded && dy > 24 * den) {
                    sheetDragMoved = true;
                    setSheetHeight(clampSheet(dragStartH + (int) dy));
                    return true;
                }
                if (sheetExpanded && dy < -24 * den && listAtTop()) {
                    sheetDragMoved = true;
                    setSheetHeight(clampSheet(dragStartH + (int) dy));
                    return true;
                }
                return false;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                sheetTouchActive = false;
                if (sheetDragMoved) {
                    animateSheetTo(llQueueSheet.getHeight() > (sheetMinH + sheetMaxH) / 2 ? sheetMaxH : sheetMinH);
                    sheetDragMoved = false;
                    return true;
                }
                return false;
        }
        return false;
    }

    private boolean listAtTop() {
        return lvQueue.getFirstVisiblePosition() == 0
                && (lvQueue.getChildCount() == 0 || lvQueue.getChildAt(0).getTop() >= 0);
    }

    private int clampSheet(int h) {
        return Math.max(sheetMinH, Math.min(sheetMaxH, h));
    }

    private void setSheetHeight(int h) {
        android.view.ViewGroup.LayoutParams lp = llQueueSheet.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            llQueueSheet.setLayoutParams(lp);
        }
    }

    private void animateSheetTo(int target) {
        boolean willExpand = target == sheetMaxH;
        if (willExpand != sheetExpanded) Haptics.tick(this);
        sheetExpanded = willExpand;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(llQueueSheet.getHeight(), target);
        va.setDuration(220);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> setSheetHeight((Integer) a.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { scheduleSheetBlur(0); }
        });
        va.start();
    }


    /** 毛玻璃面板底：把面板背后的画面抓下来降采样重模糊当底，再压一层主题底色保住文字可读性。
     *  纯透明会透出歌词糊成一团、纯实色又没玻璃味（用户两次反馈的折中点）；抓图只在面板落位/切歌时做，成本可忽略 */
    private void refreshSheetBlur() {
        try {
            if (llQueueSheet == null || !llQueueSheet.isAttachedToWindow()) return;
            android.view.View root = (android.view.View) llQueueSheet.getParent();
            int sw = llQueueSheet.getWidth(), sh = llQueueSheet.getHeight();
            if (root == null || sw <= 0 || sh <= 0 || root.getWidth() <= 0 || root.getHeight() <= 0) return;
            final int scale = 8;
            int bw = Math.max(1, root.getWidth() / scale), bh = Math.max(1, root.getHeight() / scale);
            android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(
                    bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(full);
            c.scale(1f / scale, 1f / scale);
            // 逐个子 View 画（跳过面板本身）：不能靠隐藏面板抓图，那会让面板每刷新一次就闪没一帧
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            if (root.getBackground() != null) {
                root.getBackground().setBounds(0, 0, root.getWidth(), root.getHeight());
                root.getBackground().draw(c);
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.View ch = vg.getChildAt(i);
                if (ch == llQueueSheet || ch.getVisibility() != android.view.View.VISIBLE) continue;
                c.save();
                c.translate(ch.getLeft(), ch.getTop());
                ch.draw(c);
                c.restore();
            }
            int[] rl = new int[2], sl = new int[2];
            root.getLocationOnScreen(rl);
            llQueueSheet.getLocationOnScreen(sl);
            int x = Math.max(0, (sl[0] - rl[0]) / scale), y = Math.max(0, (sl[1] - rl[1]) / scale);
            int cw = Math.min(bw - x, Math.max(1, sw / scale));
            int ch = Math.min(bh - y, Math.max(1, sh / scale));
            if (cw <= 0 || ch <= 0) { full.recycle(); return; }
            android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(full, x, y, cw, ch);
            full.recycle();
            boxBlur(crop, Math.max(6, cw / 9));
            int bg = ThemeUtil.color(this, R.attr.gBg);
            android.graphics.Canvas c2 = new android.graphics.Canvas(crop);
            c2.drawColor(android.graphics.Color.argb(105, android.graphics.Color.red(bg),
                    android.graphics.Color.green(bg), android.graphics.Color.blue(bg)));
            float rad = 24 * getResources().getDisplayMetrics().density / scale;
            android.graphics.Bitmap round = android.graphics.Bitmap.createBitmap(
                    cw, ch, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c3 = new android.graphics.Canvas(round);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new android.graphics.BitmapShader(crop,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
            c3.drawRoundRect(new android.graphics.RectF(0, 0, cw, ch), rad, rad, paint);
            crop.recycle();
            android.graphics.drawable.BitmapDrawable bd =
                    new android.graphics.drawable.BitmapDrawable(getResources(), round);
            bd.setFilterBitmap(true);
            android.graphics.drawable.Drawable[] layers;
            if (android.graphics.Color.alpha(ThemeUtil.color(this, R.attr.gGlassHi)) < 250) {
                layers = new android.graphics.drawable.Drawable[]{bd, getDrawable(R.drawable.bg_card)};
            } else {
                layers = new android.graphics.drawable.Drawable[]{bd};
            }
            llQueueSheet.setBackground(new android.graphics.drawable.LayerDrawable(layers));
            if (sheetBlurBmp != null && !sheetBlurBmp.isRecycled()) sheetBlurBmp.recycle();
            sheetBlurBmp = round;
        } catch (Exception ignored) {}
    }

    /** 盒式模糊：横竖滑动窗口各一遍、迭代 3 次近似高斯，就地处理降采样小图 */
    private static void boxBlur(android.graphics.Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 1 || h <= 1 || radius < 1) return;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        int n = 2 * radius + 1;
        for (int it = 0; it < 3; it++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                long sr = 0, sg = 0, sb = 0, sa = 0;
                for (int i = -radius; i <= radius; i++) {
                    int p = px[row + Math.max(0, Math.min(w - 1, i))];
                    sa += android.graphics.Color.alpha(p); sr += android.graphics.Color.red(p);
                    sg += android.graphics.Color.green(p); sb += android.graphics.Color.blue(p);
                }
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = android.graphics.Color.argb(
                            (int) (sa / n), (int) (sr / n), (int) (sg / n), (int) (sb / n));
                    int po = px[row + Math.max(0, x - radius)];
                    int pi = px[row + Math.min(w - 1, x + radius + 1)];
                    sa += android.graphics.Color.alpha(pi) - android.graphics.Color.alpha(po);
                    sr += android.graphics.Color.red(pi) - android.graphics.Color.red(po);
                    sg += android.graphics.Color.green(pi) - android.graphics.Color.green(po);
                    sb += android.graphics.Color.blue(pi) - android.graphics.Color.blue(po);
                }
            }
            for (int x = 0; x < w; x++) {
                long sr = 0, sg = 0, sb = 0, sa = 0;
                for (int i = -radius; i <= radius; i++) {
                    int p = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                    sa += android.graphics.Color.alpha(p); sr += android.graphics.Color.red(p);
                    sg += android.graphics.Color.green(p); sb += android.graphics.Color.blue(p);
                }
                for (int y = 0; y < h; y++) {
                    px[y * w + x] = android.graphics.Color.argb(
                            (int) (sa / n), (int) (sr / n), (int) (sg / n), (int) (sb / n));
                    int po = tmp[Math.max(0, y - radius) * w + x];
                    int pi = tmp[Math.min(h - 1, y + radius + 1) * w + x];
                    sa += android.graphics.Color.alpha(pi) - android.graphics.Color.alpha(po);
                    sr += android.graphics.Color.red(pi) - android.graphics.Color.red(po);
                    sg += android.graphics.Color.green(pi) - android.graphics.Color.green(po);
                    sb += android.graphics.Color.blue(pi) - android.graphics.Color.blue(po);
                }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private void setupGradientChrome() {
        float den = getResources().getDisplayMetrics().density;
        btnToggle.setBackground(null);
        btnToggle.setImageTintList(null);
        btnToggle.setImageDrawable(ThemeUtil.gradientIcon(this, R.drawable.ic_play, 30));
        sb.setProgressTintList(null);
        sb.setThumbTintList(null);
        int barH = (int) (4 * den);
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x33FFFFFF);
        track.setCornerRadius(2 * den);
        track.setSize(-1, barH);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, ThemeUtil.gradColors(this));
        fill.setCornerRadius(2 * den);
        fill.setSize(-1, barH);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        ld.setLayerHeight(0, barH);
        ld.setLayerHeight(1, barH);
        ld.setLayerGravity(0, android.view.Gravity.CENTER_VERTICAL);
        ld.setLayerGravity(1, android.view.Gravity.CENTER_VERTICAL);
        sb.setProgressDrawable(ld);
        android.graphics.drawable.GradientDrawable thumb = ThemeUtil.accentGradient(this, 999);
        thumb.setSize((int) (12 * den), (int) (12 * den));
        sb.setThumb(thumb);
        ThemeUtil.gradientText((TextView) findViewById(R.id.btnAddList));
    }

    private void buildQualityChips() {
        PlayerService s = PlayerService.get();
        int cur = s == null ? 2 : s.getEffectiveTier();
        for (int i = 0; i < 5; i++) {
            final int tier = i;
            TextView chip = new TextView(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, (int) (38 * getResources().getDisplayMetrics().density), 1);
            lp.setMargins(3, 0, 3, 0);
            chip.setLayoutParams(lp);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setText(QUALITY_SHORT[i]);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> {
                Haptics.press(PlayerActivity.this);
                PlayerService svc = PlayerService.get();
                if (svc != null) {
                    svc.setSessionTier(tier); // 播放页切档 = 临时请求，不改设置里的默认档位（军师建议）
                    svc.applyQualityChange();
                    Toast.makeText(this, "已临时切到「" + PlayerService.QUALITY_NAMES[tier] + "」🎵（默认设置未改动）", Toast.LENGTH_SHORT).show();
                }
                refreshQuality();
                llQualityChips.postDelayed(() -> llQualityChips.setVisibility(View.GONE), 350);
            });
            chipViews[i] = chip;
            llQualityChips.addView(chip);
        }
        styleQualityChips(cur);
    }

    private void styleQualityChips(int cur) {
        PlayerService svc2 = PlayerService.get();
        boolean[] avail = svc2 == null ? null : svc2.getAvailTiers();
        for (int i = 0; i < 5; i++) {
            if (chipViews[i] == null) continue;
            boolean on = i == cur;
            chipViews[i].setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip_pill);
            if (on) chipViews[i].setBackground(ThemeUtil.accentGradient(this, 19));
            chipViews[i].setTextColor(ThemeUtil.color(this, on ? R.attr.gOnAccent : R.attr.gTextPri));
            chipViews[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            chipViews[i].setAlpha(avail != null && !avail[i] ? 0.35f : 1f); // 本首没有的档位压暗
        }
    }

    private void toggleQualityChips() {
        if (llQualityChips.getVisibility() == View.VISIBLE) {
            llQualityChips.setVisibility(View.GONE);
        } else {
            PlayerService s = PlayerService.get();
            if (s != null) styleQualityChips(s.getActualTier() >= 0 ? s.getActualTier() : s.getEffectiveTier());
            llQualityChips.setVisibility(View.VISIBLE);
            llQualityChips.setAlpha(0f);
            llQualityChips.animate().alpha(1f).setDuration(160).start();
        }
    }


    private String modeName(int m) {
        return m == PlayerService.MODE_LOOP ? "单曲循环" : m == PlayerService.MODE_SHUFFLE ? "随机播放" : "顺序播放";
    }

    private void refreshMode() {
        PlayerService s = PlayerService.get();
        btnMode.setText(s == null ? "顺序" : modeName(s.getMode()).replace("播放", ""));
        ThemeUtil.gradientText(btnMode);
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
            refreshQueueMeta();
            scrollQueueToCurrent();
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
        scheduleSheetBlur(900);
        tvTitle.setText(t.title);
        tvAuthor.setText(t.author == null ? "" : t.author);
        ImgLoader.loadDisc(ivCover, t.cover);
        if (backdropView != null) ImgLoader.load(backdropView, t.cover);
        Lyrics.fetchCover(this, t, url -> {
            if (url == null) return;
            PlayerService svc = PlayerService.get();
            Track cur = svc == null ? null : svc.current();
            if (cur == null || !t.bvid.equals(cur.bvid)) return;
            ImgLoader.loadDisc(ivCover, url);
            if (backdropView != null) ImgLoader.load(backdropView, url);
        });
        sb.setProgress(0);
        tvPos.setText("00:00");
        queueAdapter.notifyDataSetChanged();
        refreshQueueMeta();
        scrollQueueToCurrent();
    }

    @Override
    public void onStateChanged(boolean playing) {
        btnToggle.setImageDrawable(ThemeUtil.gradientIcon(this, playing ? R.drawable.ic_pause : R.drawable.ic_play, 30));
        refreshQuality(); // 流一就绪就把音质显示刷新成实际档位
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
            if (p == s.getIndex()) ThemeUtil.gradientText(title);
            else ThemeUtil.plainText(title, ThemeUtil.color(PlayerActivity.this, R.attr.gTextPri));
            ((TextView) cv.findViewById(R.id.tvSub)).setText((t.author == null ? "" : t.author) + " · " + Track.fmtDur(t.durationSec));
            cv.setBackgroundResource(p == s.getIndex() ? R.drawable.bg_row_current : 0);
            return cv;
        }
    };
}
