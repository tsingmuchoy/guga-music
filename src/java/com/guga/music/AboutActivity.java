package com.guga.music;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_about);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.tvBili).setOnClickListener(v -> {
            Haptics.tick(this);
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://space.bilibili.com/1432258677")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.tvAdvisorBili).setOnClickListener(v -> {
            Haptics.tick(this);
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://space.bilibili.com/432245733")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.tvAdvisorGithub).setOnClickListener(v -> {
            Haptics.tick(this);
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/MCfywb")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.tvGithub).setOnClickListener(v -> {
            Haptics.tick(this);
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/tsingmuchoy/guga-music")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.tvEmail).setOnClickListener(v -> {
            Haptics.tick(this);
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("email", "tsingmu35607@gmail.com"));
            android.widget.Toast.makeText(this, "邮箱已复制 📋", android.widget.Toast.LENGTH_SHORT).show();
        });
    }
}
