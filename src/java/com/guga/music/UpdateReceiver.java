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
            android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(doneId));
            int status = -1;
            if (c != null) {
                if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                c.close();
            }
            Diag.log(ctx, "⬆️ 更新：下载完成广播，状态=" + status);
            if (status != DownloadManager.STATUS_SUCCESSFUL) return;
            UpdateChecker.fireInstall(ctx, dm, doneId);
        } catch (Exception e) {
            Diag.log(ctx, "⬆️ 更新：完成广播处理失败 " + e);
        }
    }
}
