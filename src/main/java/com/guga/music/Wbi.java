package com.guga.music;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Bilibili WBI 签名（算法已用官方文档示例向量验证） */
public class Wbi {
    private static final int[] TAB = {46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52};

    public static String mixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder sb = new StringBuilder();
        for (int idx : TAB) if (idx < s.length()) sb.append(s.charAt(idx));
        return sb.substring(0, Math.min(32, sb.length()));
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20")
                    .replace("*", "%2A").replace("%7E", "~");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 返回带 wts 与 w_rid 的完整 query string */
    public static String signQuery(Map<String, String> params, String mixin) {
        TreeMap<String, String> p = new TreeMap<>(params);
        p.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            String v = e.getValue().replaceAll("[!'()*]", "");
            if (q.length() > 0) q.append('&');
            q.append(enc(e.getKey())).append('=').append(enc(v));
        }
        return q + "&w_rid=" + md5(q.toString() + mixin);
    }
}
