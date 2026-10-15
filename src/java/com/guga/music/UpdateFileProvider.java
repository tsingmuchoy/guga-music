package com.guga.music;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 在线更新专用文件提供者（框架-only 实现，照清木图传口径；本项目不依赖 AndroidX 故不用官方 FileProvider）。
 *
 * 系统安装器（包安装进程）需要读到下载好的 APK 文件：App 私有目录它进不去，
 * 故经本 Provider 以 content:// Uri + 临时读授权（Intent.FLAG_GRANT_READ_URI_PERMISSION）递给它。
 * 安全边界：只允许访问 cacheDir/guga-update/ 目录内的文件（规范化路径前缀校验，
 * 防 ../ 穿越），且 Provider 本身 exported=false、仅靠调用方显式授权放行单个 Uri。
 */
public class UpdateFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.guga.music.fileprovider";

    /** 更新包下载目录（与 UpdateChecker 约定一致）：cacheDir/guga-update */
    public static File updateDir(File cacheDir) {
        return new File(cacheDir, "guga-update");
    }

    /** 供安装 Intent 使用的 Uri：content://<authority>/<文件名> */
    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(f.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad path");
        }
        File dir = updateDir(getContext().getCacheDir());
        File f = new File(dir, name);
        try {
            // 规范化后必须仍在下载目录内，杜绝符号链接/穿越逃逸
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("escape");
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!f.isFile()) throw new FileNotFoundException("not found: " + name);
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.contains("r")) throw new FileNotFoundException("read only");
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Exception e) {
            return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        }
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
