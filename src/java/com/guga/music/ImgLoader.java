package com.guga.music;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简图片加载：内存缓存 + 异步下载（封面图） */
public class ImgLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    /** 唱片合成图（720x720 约 2MB 一张）单独放小缓存，别把列表小图挤出共享缓存害它们反复重载闪烁 */
    private static final LruCache<String, Bitmap> DISC_CACHE = new LruCache<String, Bitmap>(3) {
        @Override protected int sizeOf(String k, Bitmap b) { return 1; }
    };
    /** 流量网络时列表图取小图（由播放服务按网络状态更新） */
    public static volatile boolean meteredSmall = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void load(ImageView iv, String url) {
        loadInternal(iv, url, false);
    }

    /** 加载并合成黑胶唱片图（黑盘+纹路+圆形封面+中心点） */
    public static void loadDisc(ImageView iv, String url) {
        loadInternal(iv, url, true);
    }

    public interface BmpCb { void onBitmap(Bitmap b); }

    /** 只取位图不绑 View（给媒体会话/通知大图用），走同一内存缓存 */
    public static void loadBitmap(String url, BmpCb cb) {
        if (url == null || url.isEmpty()) { cb.onBitmap(null); return; }
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        else if (url.startsWith("//")) url = "https:" + url;
        final String furl = url;
        Bitmap hit = CACHE.get(furl);
        if (hit != null) { cb.onBitmap(hit); return; }
        POOL.execute(() -> {
            Bitmap src = null;
            try {
                src = CACHE.get(furl);
                if (src == null) {
                    HttpURLConnection c = (HttpURLConnection) new URL(furl).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    c.setRequestProperty("Referer", "https://www.bilibili.com");
                    InputStream in = c.getInputStream();
                    src = BitmapFactory.decodeStream(in);
                    in.close();
                    if (src != null) CACHE.put(furl, src);
                }
            } catch (Exception ignored) {}
            final Bitmap out = src;
            MAIN.post(() -> cb.onBitmap(out));
        });
    }

    private static void loadInternal(ImageView iv, String url, boolean disc) {
        if (url == null || url.isEmpty()) {
            iv.setImageBitmap(null);
            return;
        }
        // B 站封面大量是 http 地址，新系统默认拦截明文流量，统一升级 https
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        else if (url.startsWith("//")) url = "https:" + url;
        if (!disc && meteredSmall && url.contains("hdslb.com") && !url.contains("@")) {
            url = url + "@512w";
        }
        final String furl = url;
        final int accent = disc ? ThemeUtil.color(iv.getContext(), R.attr.gAccent) : 0;
        String key = disc ? furl + "#disc" + accent : furl;
        Object oldTag = iv.getTag();
        iv.setTag(key);
        LruCache<String, Bitmap> store = disc ? DISC_CACHE : CACHE;
        Bitmap hit = store.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        // 换图不先清空：旧图垫着、新图到位再换，切歌/滚动都不空闪；
        // 加载失败走下面回调清掉旧图，不会把上一张错当成本张留着
        POOL.execute(() -> {
            Bitmap out = null;
            try {
                Bitmap src = CACHE.get(furl);
                if (src == null) {
                    HttpURLConnection c = (HttpURLConnection) new URL(furl).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    c.setRequestProperty("Referer", "https://www.bilibili.com");
                    InputStream in = c.getInputStream();
                    src = BitmapFactory.decodeStream(in);
                    in.close();
                    if (src != null) CACHE.put(furl, src);
                }
                if (src != null) {
                    out = disc ? makeDisc(src, accent) : src;
                    store.put(key, out);
                }
            } catch (Exception ignored) { out = null; }
            final Bitmap fout = out;
            MAIN.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(fout);
            });
        });
    }

    private static Bitmap makeDisc(Bitmap cover, int accent) {
        int size = 720;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float c = size / 2f;
        // 黑胶盘面
        p.setColor(0xFF0C0C0E);
        cv.drawCircle(c, c, c - 2, p);
        // 纹路
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF232327);
        for (float r = c * 0.99f; r > c * 0.70f; r -= size * 0.028f) {
            p.setStrokeWidth(2f);
            cv.drawCircle(c, c, r, p);
        }
        p.setStyle(Paint.Style.FILL);
        // 圆形封面（中心裁切）
        float cr = c * 0.66f;
        BitmapShader shader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = (cr * 2) / Math.min(cover.getWidth(), cover.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(c - cover.getWidth() * scale / 2f, c - cover.getHeight() * scale / 2f);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        cv.drawCircle(c, c, cr, p);
        p.setShader(null);
        // 封面外圈细环
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(0xFF3A3A40);
        cv.drawCircle(c, c, cr + 3, p);
        p.setStyle(Paint.Style.FILL);
        // 中心轴孔
        p.setColor(0xFF121417);
        cv.drawCircle(c, c, size * 0.035f, p);
        p.setColor(accent);
        cv.drawCircle(c, c, size * 0.014f, p);
        return out;
    }
}
