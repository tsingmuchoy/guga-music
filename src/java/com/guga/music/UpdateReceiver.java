package com.guga.music;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** 新版安装包下载完成后，自动拉起系统安装器 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long doneId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long wantId = ctx.getSharedPreferences("update", Context.MODE_PRIVATE).getLong("dl_id", -2);
        if (doneId != wantId) return;
        try {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(doneId);
            if (uri == null) return;
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(install);
        } catch (Exception ignored) {}
    }
}
