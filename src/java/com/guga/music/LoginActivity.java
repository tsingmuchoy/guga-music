package com.guga.music;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends Activity {

    private BiliApi api;
    private boolean done = false;
    private final Handler handler = new Handler();
    private WebView wv;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!done) {
                checkCookies();
                handler.postDelayed(this, 1200);
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_login);
        EdgeToEdge.apply(this);
        api = new BiliApi(this);
        wv = findViewById(R.id.wvLogin);
        android.webkit.WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // 登录只需要 https 网页：关掉本地文件/内容访问、混合内容与定位，缩小 JS 的可达面
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setGeolocationEnabled(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new WebViewClient() {
            /** 主框架导航只放行 https；http 一律升级，其它 scheme 不让进登录 WebView。
             *  子资源（验证码 iframe、图片等）不拦，避免破坏 B 站登录流程。 */
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                android.net.Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("https".equalsIgnoreCase(scheme)) return false;
                if ("http".equalsIgnoreCase(scheme)) {
                    view.loadUrl(u.buildUpon().scheme("https").toString());
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                checkCookies();
            }
        });
        wv.loadUrl("https://passport.bilibili.com/login");
        handler.postDelayed(poll, 1200);
    }

    private void checkCookies() {
        if (done) return;
        Map<String, String> map = new HashMap<>();
        for (String url : new String[]{"https://www.bilibili.com", "https://passport.bilibili.com", "https://api.bilibili.com"}) {
            String raw = CookieManager.getInstance().getCookie(url);
            if (raw == null) continue;
            for (String part : raw.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
            }
        }
        String sess = map.get("SESSDATA");
        if (sess != null && !sess.isEmpty()) {
            done = true;
            api.saveLoginCookies(map);
            Toast.makeText(this, "登录成功 🎉", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (wv != null) {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.removeAllViews();
            wv.destroy();
            wv = null;
        }
        super.onDestroy();
    }
}
