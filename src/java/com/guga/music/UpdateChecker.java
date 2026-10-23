package com.guga.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 在线更新：查 GitHub/Gitee Releases 最新版 -> App 内前台下载（卡内进度，停滞自动换源）
 * -> 大小+包名/版本校验 -> 当场拉起系统安装器（未授权先跳设置页、回来自动续装）。
 *
 * v1.42.1 起弃用系统 DownloadManager + 完成广播拉安装器的旧链：安卓 10+ 后台启动限制
 * 会把广播里的 startActivity 静默拦掉（用户实测「下完不自动跳安装」），改为照清木图传
 * 口径的前台下载流：下载全程在前台卡片里进行，下完当场从前台拉安装器，不被系统拦。
 */
public class UpdateChecker {

    private static final String API = "https://api.github.com/repos/tsingmuchoy/guga-music/releases/latest";

    public static class Info {
        public String version;
        public String notes;
        public String apkUrl;
        public String sourceName;
        public String src;      // 实际命中的源（SRC_GITHUB / SRC_GITEE），换源时取另一边
        public long apkSize;    // Release 资产元数据大小（字节）；0 = 没拿到，校验时走包解析兜底
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
        long apkSize = 0;
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
                        apkSize = a.optLong("size", 0);
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
            notes = "";
            // 尽力而为走一次 API：补更新日志 + 资产直链与大小（校验用）；失败（限额等）不影响检测本身
            try {
                JSONObject r = new JSONObject(Lyrics.httpGet(API, null));
                if (tag.equals(r.optString("tag_name").replaceFirst("^v", ""))) {
                    notes = r.optString("body");
                    JSONArray assets = r.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").endsWith(".apk")) {
                                String u = a.optString("browser_download_url");
                                if (u != null && !u.isEmpty()) apkUrl = u;
                                apkSize = a.optLong("size", 0);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        Info info = new Info();
        info.version = tag;
        info.apkUrl = apkUrl;
        info.src = src;
        info.apkSize = apkSize;
        info.sourceName = sourceName(src);
        info.notes = notes == null || notes.isEmpty()
                ? "新版来啦 🎉 点「立即更新」下载安装"
                : notes.length() > 700 ? notes.substring(0, 700) + "…" : notes;
        return info;
    }

    /** 不走 GitHub API（有每小时 60 次限额，手机共享 IP 很容易被限）：
     *  访问 releases/latest 的重定向地址，从最终 URL 里取最新 tag */
    private static String latestTag() throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL("https://github.com/tsingmuchoy/guga-music/releases/latest").openConnection();
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

    // ---------------- 前台下载（卡内进度 + 停滞自动换源，只换一次） ----------------
    private static volatile boolean sDownloading = false;
    private static volatile boolean sCancelDownload = false;
    private static boolean sPermHintShown = false;      // 本进程内「去授权」提醒只弹一次
    private static boolean sLaunchFallbackShown = false; // 本进程内「已拉起但未装上」兜底确认只弹一次

    private static String fmtSize(long b) {
        if (b <= 0) return "0 MB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    /** 同版本在另一源的直链（两边文件名与路径规则一致） */
    private static String altUrlFor(Info info) {
        if (SRC_GITEE.equals(info.src)) {
            return "https://github.com/tsingmuchoy/guga-music/releases/download/v"
                    + info.version + "/guga-music-v" + info.version + ".apk";
        }
        return "https://gitee.com/tsingmuchoy/guga-music/releases/download/v"
                + info.version + "/guga-music-v" + info.version + ".apk";
    }

    /** 点「立即更新」：前台卡内下载；已有同版本校验通过的待装包则直接拉安装，不重复下载 */
    public static void download(final Activity act, final Info info) {
        if (sDownloading) {
            Toast.makeText(act, "正在下载中啦，稍等一下 🐧", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences sp = act.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (info.version.equals(sp.getString("pending_ver", null))) {
            String path = sp.getString("pending_path", null);
            if (path != null) {
                File f = new File(path);
                if (f.isFile() && verifyDownloaded(act, f, info) == null) {
                    Diag.log(act, "⬆️ 更新：已有校验通过的待装包 v" + info.version + "，直接拉起安装");
                    tryInstall(act);
                    return;
                }
            }
        }
        sDownloading = true;
        sCancelDownload = false;
        sPermHintShown = false;
        sLaunchFallbackShown = false;
        // 进行中标记：进程中途被切走/被杀时，onResume 靠它提示「下载中断可重下」，不静默
        sp.edit().putString("dl_active_ver", info.version)
                .putString("int_url", info.apkUrl)
                .putLong("int_size", info.apkSize)
                .putString("int_src", info.src)
                .apply();
        Diag.log(act, "⬆️ 更新：开始前台下载 v" + info.version + "，源=" + info.sourceName
                + (info.apkSize > 0 ? "，资产大小=" + info.apkSize + "B" : "，资产大小未知（校验走包解析）"));
        final UiDialog.Progress dlg = UiDialog.progress(act,
                "下载新版 v" + info.version,
                "准备下载…（更新源：" + info.sourceName + "）",
                () -> sCancelDownload = true);
        dlg.setProgress(-1);
        new Thread(() -> runDownload(act, info, dlg), "guga-update-dl").start();
    }

    private static void runDownload(final Activity act, final Info info, final UiDialog.Progress dlg) {
        final Context app = act.getApplicationContext();
        final SharedPreferences sp = app.getSharedPreferences("update", Context.MODE_PRIVATE);
        File dir = UpdateFileProvider.updateDir(app.getCacheDir());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // 轻量纪律：更新目录只留当前这一个包，下新包前清旧包/半包
        File[] olds = dir.listFiles();
        if (olds != null) for (File f : olds) { /*noinspection*/ f.delete(); }
        File tmp = new File(dir, "guga-music-v" + info.version + ".apk.part");
        File out = new File(dir, "guga-music-v" + info.version + ".apk");
        String url = info.apkUrl;
        boolean switched = false;
        while (true) {
            try {
                downloadOnce(url, tmp, dlg);
                if (!tmp.renameTo(out)) {
                    // 个别文件系统 rename 失败时退化为拷贝
                    java.nio.file.Files.copy(tmp.toPath(), out.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                break;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                if (sCancelDownload) {
                    sp.edit().remove("dl_active_ver").apply();
                    sDownloading = false;
                    Diag.log(app, "⬆️ 更新：用户取消下载 v" + info.version);
                    post(act, () -> Toast.makeText(act, "已取消下载", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (!switched) {
                    switched = true;
                    url = altUrlFor(info);
                    Diag.log(app, "⬆️ 更新：下载失败/停滞（" + e.getClass().getSimpleName()
                            + "），自动切换下载源重试：" + url);
                    dlg.setMessage("这个源有点慢，已自动切换下载源重试 🐧");
                    dlg.setProgress(-1);
                    continue;
                }
                sp.edit().remove("dl_active_ver").apply();
                sDownloading = false;
                Diag.log(app, "⬆️ 更新：两个源都下载失败：" + e);
                post(act, () -> {
                    dlg.dismiss();
                    if (!act.isFinishing()) {
                        UiDialog.confirm(act, "下载失败 📦",
                                "两个下载源都没下动（多半是网络原因）。检查一下网络，点「重试」再下一次。",
                                "重试", () -> download(act, info));
                    }
                });
                return;
            }
        }
        // 校验：大小（有元数据时严格对） + 包解析（包名/版本号必须对得上）
        String verr = verifyDownloaded(act, out, info);
        if (verr != null) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            sp.edit().remove("dl_active_ver").apply();
            sDownloading = false;
            post(act, () -> {
                dlg.dismiss();
                if (!act.isFinishing()) {
                    UiDialog.confirm(act, "安装包校验没过 📦",
                            verr + "，已删掉这个包。点「重试」重新下载。",
                            "重试", () -> download(act, info));
                }
            });
            return;
        }
        sp.edit().putString("pending_ver", info.version)
                .putString("pending_path", out.getAbsolutePath())
                .putString("pending_state", "ready")
                .remove("dl_active_ver")
                .apply();
        sDownloading = false;
        Diag.log(app, "⬆️ 更新：v" + info.version + " 下载完成并校验通过（" + out.length() + "B），拉起安装");
        post(act, () -> {
            dlg.setProgress(100);
            dlg.setMessage("下载完成 ✅ 正在打开安装器…");
            dlg.dismiss();
            tryInstall(act);
        });
    }

    /** 单次下载：流式写 .part；20 秒读不到新字节视为停滞抛异常（沿用旧看门狗「失败/长时间无增长」口径） */
    private static void downloadOnce(String urlStr, File tmp, final UiDialog.Progress dlg) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GugaMusic-Updater");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        final long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[32768];
        long done = 0;
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                if (sCancelDownload) throw new InterruptedIOException("cancelled");
                fos.write(buf, 0, n);
                done += n;
                final long d = done;
                if (total > 0) {
                    dlg.setProgress((int) (d * 100 / total));
                    dlg.setMessage("下载中 " + fmtSize(d) + " / " + fmtSize(total) + "…");
                } else {
                    dlg.setMessage("下载中 " + fmtSize(d) + "…");
                }
            }
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
            try { in.close(); } catch (Exception ignored) {}
            c.disconnect();
        }
        if (total > 0 && done != total) {
            throw new IOException("长度不符：收到 " + done + "，应为 " + total);
        }
    }

    /** 校验待装包：合格返回 null，不合格返回中文原因（并已记诊断）。大小有元数据时严格对；
     *  无论大小是否已知，都再用包解析核对包名与版本号，防下错/下坏包 */
    private static String verifyDownloaded(Context ctx, File f, Info info) {
        if (info.apkSize > 0 && f.length() != info.apkSize) {
            Diag.log(ctx, "⬆️ 更新：校验失败，文件大小 " + f.length()
                    + " != 资产元数据 " + info.apkSize + "，删包");
            return "下到的文件大小对不上（" + f.length() + " 字节，应为 " + info.apkSize + " 字节）";
        }
        PackageInfo ai;
        try {
            ai = ctx.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        } catch (Exception e) {
            ai = null;
        }
        if (ai == null) {
            Diag.log(ctx, "⬆️ 更新：校验失败，安装包解析不出来");
            return "下到的文件不是完整的安装包";
        }
        if (!ctx.getPackageName().equals(ai.packageName)) {
            Diag.log(ctx, "⬆️ 更新：校验失败，包名不对：" + ai.packageName);
            return "下到的安装包包名不对";
        }
        if (ai.versionName == null || cmp(info.version, ai.versionName) != 0) {
            Diag.log(ctx, "⬆️ 更新：校验失败，版本号不对：" + ai.versionName + "，应为 " + info.version);
            return "下到的安装包版本号不对（" + ai.versionName + "）";
        }
        Diag.log(ctx, "⬆️ 更新：安装包校验通过（" + f.length() + "B，包名/版本 v" + ai.versionName + " 对上）");
        return null;
    }

    // ---------------- 拉起安装（图传口径：未授权跳设置、回来自动续；已授权当场拉） ----------------
    public static void tryInstall(final Activity act) {
        SharedPreferences sp = act.getSharedPreferences("update", Context.MODE_PRIVATE);
        String ver = sp.getString("pending_ver", null);
        String path = sp.getString("pending_path", null);
        if (ver == null || path == null) return;
        File f = new File(path);
        if (!f.isFile()) {
            sp.edit().remove("pending_ver").remove("pending_path").remove("pending_state").apply();
            Diag.log(act, "⬆️ 更新：待装包文件已不存在，清除待装状态");
            Toast.makeText(act, "安装包不见了，重新检查更新下载吧", Toast.LENGTH_LONG).show();
            return;
        }
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            sp.edit().putString("pending_state", "wait_perm").apply();
            Diag.log(act, "⬆️ 更新：未授予「安装未知应用」，跳系统设置授权，回来后自动续装");
            Toast.makeText(act, "需要先允许「安装未知应用」，授权后回来会自动继续安装 🔐",
                    Toast.LENGTH_LONG).show();
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception e) {
                Diag.log(act, "⬆️ 更新：打开安装授权设置失败 " + e);
                Toast.makeText(act, "打开设置失败，请到系统设置→应用→Apollo 里手动允许「安装未知应用」",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(UpdateFileProvider.uriFor(f),
                    "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
            sp.edit().putString("pending_state", "launched")
                    .putLong("launch_ts", System.currentTimeMillis()).apply();
            Diag.log(act, "⬆️ 更新：已拉起系统安装器 v" + ver);
        } catch (Exception e) {
            Diag.log(act, "⬆️ 更新：拉起安装器失败 " + e);
            Toast.makeText(act, "拉起安装器失败，稍后回到本页会自动再试", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * onResume 残留状态处理（新链只剩这几种）：
     *  1. 已装上新版 → 清待装状态；
     *  2. wait_perm 且已授权 → 自动续装；仍没授权 → 本进程只提醒一次去授权；
     *  3. ready（下载完还没拉起，如进程曾被杀）→ 直接 tryInstall；
     *  4. launched 但过了一会儿版本还没变（安装器被系统拦了/用户在安装页取消）→ 本进程只兜底弹一次确认框；
     *  5. dl_active 残留（上次下载中途进程没了）→ 删半包 + 弹「重新下载」，不静默。
     *  另：旧 DownloadManager 链的 dl_id/dl_ver 残留直接作废清理。
     */
    public static void checkPendingInstall(final Activity act) {
        try {
            SharedPreferences sp = act.getSharedPreferences("update", Context.MODE_PRIVATE);
            if (sp.contains("dl_id") || sp.contains("dl_ver")) {
                sp.edit().remove("dl_id").remove("dl_ver").apply();
                Diag.log(act, "⬆️ 更新：已清理旧版（系统下载）残留标记");
            }
            String cur = currentVersion(act);
            String pv = sp.getString("pending_ver", null);
            if (pv != null) {
                if (cmp(pv, cur) <= 0) {
                    sp.edit().remove("pending_ver").remove("pending_path")
                            .remove("pending_state").remove("launch_ts").apply();
                    Diag.log(act, "⬆️ 更新：待装版本 v" + pv + " 已是当前版本，清除待装状态");
                } else {
                    String state = sp.getString("pending_state", "ready");
                    if ("wait_perm".equals(state)) {
                        if (act.getPackageManager().canRequestPackageInstalls()) {
                            Diag.log(act, "⬆️ 更新：安装授权已授予，自动续装 v" + pv);
                            tryInstall(act);
                        } else if (!sPermHintShown && !act.isFinishing()) {
                            sPermHintShown = true;
                            UiDialog.confirm(act, "就差一步授权 🔐",
                                    "新版 v" + pv + " 已经下载好啦，需要允许「安装未知应用」才能装。"
                                            + "去设置里打开后，回来会自动继续安装。",
                                    "去授权", () -> tryInstall(act));
                        }
                        return;
                    }
                    if ("ready".equals(state)) {
                        Diag.log(act, "⬆️ 更新：发现已下载待装的新版 v" + pv + "，拉起安装");
                        tryInstall(act);
                        return;
                    }
                    if ("launched".equals(state)) {
                        long ts = sp.getLong("launch_ts", 0);
                        if (!sLaunchFallbackShown && !act.isFinishing()
                                && System.currentTimeMillis() - ts > 4000) {
                            sLaunchFallbackShown = true;
                            Diag.log(act, "⬆️ 更新：安装器已拉起但版本未变，兜底弹确认框");
                            UiDialog.confirm(act, "新版 v" + pv + " 已下载好 📦",
                                    "安装包就在手机里啦，点「立即安装」完成更新。",
                                    "立即安装", () -> tryInstall(act));
                        }
                        return;
                    }
                }
            }
            String active = sp.getString("dl_active_ver", null);
            if (active != null && !sDownloading) {
                final String iver = active;
                final String iurl = sp.getString("int_url", null);
                final long isize = sp.getLong("int_size", 0);
                final String isrc = sp.getString("int_src", SRC_GITHUB);
                sp.edit().remove("dl_active_ver").apply();
                try {
                    File dir = UpdateFileProvider.updateDir(act.getCacheDir());
                    File[] fs = dir.listFiles();
                    if (fs != null) for (File x : fs) {
                        if (x.getName().endsWith(".part")) { /*noinspection*/ x.delete(); }
                    }
                } catch (Exception ignored) {}
                Diag.log(act, "⬆️ 更新：检测到上次 v" + iver + " 下载中断（进程中途没了），提示重新下载");
                if (cmp(iver, cur) > 0 && iurl != null && !act.isFinishing()) {
                    UiDialog.confirm(act, "更新下载中断了 📦",
                            "上次下载新版 v" + iver + " 时 App 被切走或关掉了，安装包没下完。"
                                    + "点「重新下载」再下一次就好。",
                            "重新下载", () -> {
                                Info info = new Info();
                                info.version = iver;
                                info.apkUrl = iurl;
                                info.apkSize = isize;
                                info.src = isrc;
                                info.sourceName = sourceName(isrc);
                                info.notes = "";
                                download(act, info);
                            });
                }
            }
        } catch (Exception ignored) {}
    }

    private static void post(final Activity act, final Runnable r) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { r.run(); } catch (Exception ignored) {}
        });
    }
}
