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
        api = new BiliApi(this);
        wv = findViewById(R.id.wvLogin);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new WebViewClient() {
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
        if (map.containsKey("SESSDATA")) {
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
        if (wv != null) wv.destroy();
        super.onDestroy();
    }
}
