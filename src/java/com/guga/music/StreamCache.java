package com.guga.music;

import android.content.Context;

import java.io.File;
import java.util.Arrays;

/** 旋转音频缓存：存系统缓存区，总量硬顶 LRU 挤旧，绝不越用越大。
 *  文件命名 <bvid>_t<tier>.mp3（完整）/ .part（未下完，可断点续传）。 */
public class StreamCache {

    public static String key(String bvid, int tier) {
        return bvid + "_t" + tier;
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "audio_cache");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File partFile(Context c, String key) {
        return new File(dir(c), key + ".part");
    }

    public static File finalFile(Context c, String key) {
        return new File(dir(c), key + ".bin");
    }

    /** 完整缓存命中返回文件（并刷新 LRU 时间），否则 null */
    public static File hitFile(Context c, String key) {
        File f = finalFile(c, key);
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    /** 下载完成：.part 转正 + 全局修剪到上限内 */
    public static void finish(Context c, String key) {
        File p = partFile(c, key);
        File f = finalFile(c, key);
        if (p.exists()) {
            if (f.exists()) f.delete();
            p.renameTo(f);
        }
        trim(c);
    }

    public static long capBytes(Context c) {
        int mb = c.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("cache_cap_mb", 48);
        return mb * 1024L * 1024L;
    }

    public static long usedBytes(Context c) {
        long sum = 0;
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) sum += f.length();
        return sum;
    }

    /** 超上限时按最旧优先删（含 .part），正在写的最新文件天然排最后 */
    public static synchronized void trim(Context c) {
        try {
            long cap = capBytes(c);
            File[] fs = dir(c).listFiles();
            if (fs == null) return;
            Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            long used = 0;
            for (File f : fs) used += f.length();
            for (File f : fs) {
                if (used <= cap) break;
                used -= f.length();
                f.delete();
            }
        } catch (Exception ignored) {}
    }

    public static void deleteKey(Context c, String key) {
        try {
            finalFile(c, key).delete();
            partFile(c, key).delete();
        } catch (Exception ignored) {}
    }

    public static void clearAll(Context c) {
        try {
            File[] fs = dir(c).listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Exception ignored) {}
    }
}
