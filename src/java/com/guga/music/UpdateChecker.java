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
        public String sourceName;
    }

    // ---------------- 更新源（GitHub 主源 / Gitee 备用源，首选连不上自动切换） ----------------
    public static final String SRC_GITHUB = "github";
    public static final String SRC_GITEE = "gitee";
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/tsingmuchoy/guga-music/releases/latest";

    public static String sourcePref(Context ctx) {
        String v = ctx.getSharedPreferences("update", Context.MODE_PRIVATE).getString("source", SRC_GITHUB);
        return SRC_GITEE.equals(v) ? SRC_GITEE : SRC_GITHUB;
    }

    public static void setSourcePref(Context ctx, String src) {
        ctx.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putString("source", src).apply();
    }

    public static String sourceName(String src) {
        return SRC_GITEE.equals(src) ? "Gitee 备用源" : "GitHub";
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
            String pref = sourcePref(ctx);
            String other = SRC_GITEE.equals(pref) ? SRC_GITHUB : SRC_GITEE;
            Info found = null;
            String err = null;
            try {
                found = checkSource(ctx, pref);
            } catch (Exception e1) {
                try {
                    found = checkSource(ctx, other);
                } catch (Exception e2) {
                    err = e2.getMessage();
                }
            }
            final Info f = found;
            final String er = err;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(f, er));
        }).start();
    }

    /** 查一个源：有新版返回 Info，已是最新返回 null，源不可用抛异常（触发切换到另一个源） */
    private static Info checkSource(Context ctx, String src) throws Exception {
        String tag;
        String apkUrl;
        String notes;
        if (SRC_GITEE.equals(src)) {
            JSONObject r = new JSONObject(Lyrics.httpGet(GITEE_API, null));
            tag = r.optString("tag_name").replaceFirst("^v", "");
            if (!tag.matches("[0-9]+(\\.[0-9]+)*")) throw new Exception("Gitee 没返回有效版本号");
            if (cmp(tag, currentVersion(ctx)) <= 0) return null;
            apkUrl = null;
            JSONArray assets = r.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url");
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                apkUrl = "https://gitee.com/tsingmuchoy/guga-music/releases/download/v"
                        + tag + "/guga-music-v" + tag + ".apk";
            }
            notes = r.optString("body");
        } else {
            tag = latestTag();
            if (tag == null) throw new Exception("没读到最新版本信息");
            if (cmp(tag, currentVersion(ctx)) <= 0) return null;
            apkUrl = "https://github.com/tsingmuchoy/guga-music/releases/download/v"
                    + tag + "/guga-music-v" + tag + ".apk";
            notes = fetchNotes(tag);
        }
        Info info = new Info();
        info.version = tag;
        info.apkUrl = apkUrl;
        info.sourceName = sourceName(src);
        info.notes = notes == null || notes.isEmpty()
                ? "新版来啦 🎉 点「立即更新」下载安装"
                : notes.length() > 700 ? notes.substring(0, 700) + "…" : notes;
        return info;
    }

    /** 不走 GitHub API（有每小时 60 次限额，手机共享 IP 很容易被限）：
     *  访问 releases/latest 的重定向地址，从最终 URL 里取最新 tag */
    private static String latestTag() throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("https://github.com/tsingmuchoy/guga-music/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        c.getResponseCode();
        String eff = c.getURL().toString();
        c.disconnect();
        int i = eff.indexOf("/tag/");
        if (i < 0) return null;
        String tag = eff.substring(i + 5);
        if (tag.startsWith("v")) tag = tag.substring(1);
        return tag.matches("[0-9]+(\\.[0-9]+)*") ? tag : null;
    }

    /** 更新日志尽力而为：走 API 取一次，失败就空着（不影响检测本身） */
    private static String fetchNotes(String tag) {
        try {
            String json = Lyrics.httpGet(API, null);
            JSONObject r = new JSONObject(json);
            if (tag.equals(r.optString("tag_name").replaceFirst("^v", ""))) {
                JSONArray assets = r.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) {
                            // 资产在，说明发布完整
                            String body = r.optString("body");
                            return body.length() > 700 ? body.substring(0, 700) + "…" : body;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 打开 App 时的自动检查：每次打开都查（军师提议），有新版才弹窗、用户自行决定更不更；
     *  2 分钟内重复触发（主题重建等）不重复查，防无谓请求 */
    public static void autoCheck(final Activity act) {
        SharedPreferences sp = act.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - sp.getLong("last_check", 0) < 120_000) return;
        sp.edit().putLong("last_check", System.currentTimeMillis()).apply();
        check(act, (info, err) -> {
            if (info != null && !act.isFinishing()) showUpdateDialog(act, info);
        });
    }

    public static void showUpdateDialog(final Activity act, final Info info) {
        UiDialog.confirm(act, "发现新版本 v" + info.version + " 🎉",
                (info.notes == null || info.notes.isEmpty() ? "有新版啦，快来更新！" : info.notes)
                        + (info.sourceName == null ? "" : "\n\n（更新源：" + info.sourceName + "）"),
                "立即更新", () -> download(act, info));
    }

    /** 检查有没有「已下载好但还没装」的新版：有就从当前界面弹窗问装（前台拉起安装器不被系统拦） */
    public static void checkPendingInstall(final Activity act) {
        try {
            SharedPreferences sp = act.getSharedPreferences("update", Context.MODE_PRIVATE);
            long id = sp.getLong("dl_id", -1);
            String ver = sp.getString("dl_ver", null);
            if (id < 0 || ver == null) return;
            if (cmp(ver, currentVersion(act)) <= 0) {
                sp.edit().remove("dl_id").remove("dl_ver").apply();
                return;
            }
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            int status = -1;
            if (c != null) {
                if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                c.close();
            }
            Diag.log(act, "⬆️ 更新：查到待装下载 id=" + id + " 状态=" + status + " 版本=v" + ver);
            if (status == DownloadManager.STATUS_FAILED) {
                sp.edit().remove("dl_id").remove("dl_ver").apply();
                Toast.makeText(act, "上次新版下载失败了，打开「关于」页点检查更新重试", Toast.LENGTH_LONG).show();
                return;
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return;
            final long fid = id;
            UiDialog.confirm(act, "新版 v" + ver + " 已下载好 📦",
                    "安装包已经下到手机里啦，点「立即安装」完成更新。",
                    "立即安装", () -> fireInstall(act, dm, fid));
        } catch (Exception ignored) {}
    }

    /** 拉起系统安装器（下载管理器给的地址）；清掉待装标记防重复弹 */
    public static void fireInstall(Context ctx, DownloadManager dm, long id) {
        try {
            Uri uri = dm.getUriForDownloadedFile(id);
            ctx.getSharedPreferences("update", Context.MODE_PRIVATE).edit()
                    .remove("dl_id").remove("dl_ver").apply();
            if (uri == null) {
                Diag.log(ctx, "⬆️ 更新：安装包地址为空，拉不起安装器");
                Toast.makeText(ctx, "安装包地址丢了，去「关于」页重新检查更新", Toast.LENGTH_LONG).show();
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(install);
        } catch (Exception e) {
            Diag.log(ctx, "⬆️ 更新：拉起安装器失败 " + e);
        }
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
            // 同版本在另一源的直链（两边文件名与路径规则一致）：下载停滞时自动换源
            String altUrl = info.apkUrl != null && info.apkUrl.contains("gitee.com")
                    ? "https://github.com/tsingmuchoy/guga-music/releases/download/v"
                            + info.version + "/guga-music-v" + info.version + ".apk"
                    : "https://gitee.com/tsingmuchoy/guga-music/releases/download/v"
                            + info.version + "/guga-music-v" + info.version + ".apk";
            startDownloadWatchdog(ctx, info.version, altUrl);
        } catch (Exception e) {
            Toast.makeText(ctx, "下载失败，去更新源手动下载吧", Toast.LENGTH_LONG).show();
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/tsingmuchoy/guga-music/releases/latest")));
            } catch (Exception ignored) {}
        }
    }

    // ---------------- 下载看门狗：一个源卡住就自动换另一个源 ----------------
    // GitHub 下载服务器在国外、国内直连常被限速卡死；Gitee 在国内一般快但也偶有抽风。
    // 每 4 秒看一次进度：失败、或 25 秒字节数没涨，就取消当前下载、改用备用源直链重下（只换一次）。
    private static Handler dlWatchdog;
    private static Runnable dlWatchTask;
    private static long dlLastBytes;
    private static long dlLastGrowAt;
    private static boolean dlSwitched;

    private static void startDownloadWatchdog(Context ctx, final String ver, final String altUrl) {
        stopDownloadWatchdog();
        final Context app = ctx.getApplicationContext();
        dlWatchdog = new Handler(Looper.getMainLooper());
        dlLastBytes = -1;
        dlLastGrowAt = System.currentTimeMillis();
        dlSwitched = false;
        dlWatchTask = new Runnable() {
            @Override public void run() {
                try {
                    SharedPreferences sp = app.getSharedPreferences("update", Context.MODE_PRIVATE);
                    long id = sp.getLong("dl_id", -1);
                    if (id < 0) { stopDownloadWatchdog(); return; }
                    DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm == null) { stopDownloadWatchdog(); return; }
                    android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                    int status = -1;
                    long bytes = 0;
                    if (c != null) {
                        if (c.moveToFirst()) {
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            bytes = c.getLong(c.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        }
                        c.close();
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == -1) {
                        stopDownloadWatchdog();
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (bytes > dlLastBytes) {
                        dlLastBytes = bytes;
                        dlLastGrowAt = now;
                    }
                    boolean failed = status == DownloadManager.STATUS_FAILED;
                    if ((failed || now - dlLastGrowAt > 25000) && !dlSwitched) {
                        dlSwitched = true;
                        dm.remove(id);
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(altUrl));
                        req.setTitle("咕嘎音乐 v" + ver);
                        req.setDescription("新版安装包下载中（已切换下载源）…");
                        req.setMimeType("application/vnd.android.package-archive");
                        req.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS,
                                "guga-music-v" + ver + ".apk");
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        long nid = dm.enqueue(req);
                        sp.edit().putLong("dl_id", nid).putString("dl_ver", ver).apply();
                        dlLastBytes = -1;
                        dlLastGrowAt = now;
                        Diag.log(app, "⬆️ 更新：下载停滞/失败，已自动切换下载源重试");
                        Toast.makeText(app, "下载较慢，已自动切换下载源重试 🐧", Toast.LENGTH_LONG).show();
                        dlWatchdog.postDelayed(this, 4000);
                        return;
                    }
                    if (failed) { stopDownloadWatchdog(); return; }
                    dlWatchdog.postDelayed(this, 4000);
                } catch (Exception e) {
                    stopDownloadWatchdog();
                }
            }
        };
        dlWatchdog.postDelayed(dlWatchTask, 4000);
    }

    private static void stopDownloadWatchdog() {
        if (dlWatchdog != null && dlWatchTask != null) dlWatchdog.removeCallbacks(dlWatchTask);
        dlWatchTask = null;
    }
}
