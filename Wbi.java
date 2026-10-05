import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class Wbi {
    private static final int[] MIXIN_TAB = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};
    public static String mixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder sb = new StringBuilder();
        for (int idx : MIXIN_TAB) if (idx < s.length()) sb.append(s.charAt(idx));
        return sb.substring(0, 32);
    }
    public static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
    public static String sign(Map<String,String> params, String mixin, long wts) throws Exception {
        TreeMap<String,String> p = new TreeMap<>(params);
        p.put("wts", String.valueOf(wts));
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String,String> e : p.entrySet()) {
            String v = e.getValue().replaceAll("[!'()*]", "");
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"));
        }
        return q + "&w_rid=" + md5(q.toString() + mixin);
    }
    public static void main(String[] a) throws Exception {
        // 官方文档示例向量
        String mixin = mixinKey("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45"); System.out.println("mixin2=" + mixinKey("653657f524a547ac981ded72ea172057", "6e4909c702f846728e64f6007736a338") + " expect 72136226c6a73669787ee4fd02a74c27");
        System.out.println("mixin=" + mixin + " expect ea1db124af3c7062474693fa704f4ff8");
    }
}
