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
        TextView tv = findViewById(R.id.tvVersion);
        try {
            tv.setText("版本 v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            tv.setText("");
        }
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.tvBili).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://space.bilibili.com/1432258677")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.tvGithub).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/tsingmuchoy/guga-music")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        final TextView tvHint = findViewById(R.id.tvUpdateHint);
        tvHint.setText("当前版本 v" + UpdateChecker.currentVersion(this) + " · 打开 App 时也会每天自动检查一次");
        findViewById(R.id.tvUpdate).setOnClickListener(v -> {
            tvHint.setText("正在检查更新…");
            UpdateChecker.check(this, (info, err) -> {
                if (info != null) {
                    tvHint.setText("发现新版本 v" + info.version + " 🎉");
                    UpdateChecker.showUpdateDialog(this, info);
                } else if (err != null) {
                    tvHint.setText("检查失败（网络原因），稍后再试");
                } else {
                    tvHint.setText("已是最新版啦 ✅ 当前 v" + UpdateChecker.currentVersion(this));
                }
            });
        });
        findViewById(R.id.tvEmail).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("email", "tsingmu35607@gmail.com"));
            android.widget.Toast.makeText(this, "邮箱已复制 📋", android.widget.Toast.LENGTH_SHORT).show();
        });
    }
}
