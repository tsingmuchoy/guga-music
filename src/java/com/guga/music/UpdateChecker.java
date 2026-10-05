package com.guga.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 在线更新：查 GitHub Releases 最新版 -> 系统 DownloadManager 下载 -> 拉起安装 */
public class UpdateChecker {

    private static final String API = "https://api.github.com/repos/tsingmuchoy/guga-music/releases/latest";

    public static class Info {
        public String version;
        public String notes;
        public String apkUrl;
    }

    public interface Cb { void onResult(Info info, String error); }

    public static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    static int cmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parseNum(x[i]) : 0;
            int yi = i < y.length ? parseNum(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parseNum(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    public static void check(final Context ctx, final Cb cb) {
        new Thread(() -> {
            Info found = null;
            String err = null;
            try {
                String json = Lyrics.httpGet(API, null);
                JSONObject r = new JSONObject(json);
                String tag = r.optString("tag_name").replaceFirst("^v", "");
                if (cmp(tag, currentVersion(ctx)) > 0) {
                    found = new Info();
                    found.version = tag;
                    String body = r.optString("body");
                    found.notes = body.length() > 700 ? body.substring(0, 700) + "…" : body;
                    JSONArray assets = r.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").endsWith(".apk")) {
                                found.apkUrl = a.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                    if (found.apkUrl == null) found = null;
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Info f = found;
            final String er = err;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(f, er));
        }).start();
    }

    /** 打开 App 时的静默自动检查：一天最多一次，有新版才弹窗 */
    public static void autoCheck(final Activity act) {
        SharedPreferences sp = act.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - sp.getLong("last_check", 0) < 20L * 3600 * 1000) return;
        sp.edit().putLong("last_check", System.currentTimeMillis()).apply();
        check(act, (info, err) -> {
            if (info != null && !act.isFinishing()) showUpdateDialog(act, info);
        });
    }

    public static void showUpdateDialog(final Activity act, final Info info) {
        new AlertDialog.Builder(act)
                .setTitle("发现新版本 v" + info.version + " 🎉")
                .setMessage(info.notes == null || info.notes.isEmpty() ? "有新版啦，快来更新！" : info.notes)
                .setPositiveButton("立即更新", (d, w) -> download(act, info))
                .setNegativeButton("下次再说", null)
                .show();
    }

    public static void download(Context ctx, Info info) {
        try {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl));
            req.setTitle("咕嘎音乐 v" + info.version);
            req.setDescription("新版安装包下载中…");
            req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS,
                    "guga-music-v" + info.version + ".apk");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            long id = dm.enqueue(req);
            ctx.getSharedPreferences("update", Context.MODE_PRIVATE).edit()
                    .putLong("dl_id", id).putString("dl_ver", info.version).apply();
            Toast.makeText(ctx, "已开始下载 📦 下完会自动弹出安装", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "下载失败，打开 GitHub 手动下载吧", Toast.LENGTH_LONG).show();
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/tsingmuchoy/guga-music/releases/latest")));
            } catch (Exception ignored) {}
        }
    }
}
