package com.guga.music;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * vivo 原子随身听适配（v1.50.0）：框架 MediaBrowserService，不引 androidx。
 *
 * 发现机制（WorkBuddy 逆向笔记，AI 生成、未经验证，待真机验证）：
 * vivo 原子随身听（com.vivo.musicwidgetmix）的小部件会 queryIntentServices
 * action=com.vivo.musicwidgetmix.support.service，能查到的包走 c0 深度协作通道。
 * 本服务在 Manifest 里以 exported=true + 该 action 声明，纯为被发现而存在；
 * 播放控制复用 PlayerService 已有的 MediaSession token，不另起会话、不改播放行为。
 *
 * 浏览树：
 *   root → bili_favs（B站收藏夹） / local（本地歌单） / queue（正在播放）
 *   bili_favs → fav_<mediaId>（夹子）→ 歌曲
 *   local → local_<pid>（本地歌单）→ 歌曲
 *   queue → 当前队列歌曲
 * 歌曲 mediaId 形如 track:<parentId>:<bvid>，播歌时按缓存的（列表，下标）整单起播。
 *
 * 安全面：本服务 exported，任何应用均可绑定浏览与控制——与一切标准媒体浏览器
 * 服务相同。暴露面仅限：歌单/歌曲标题作者（浏览）与传输控制（经共享会话）。
 * vivo 自定义 metadata（LOOP_MODE / support_event / cast_state /
 * extra_trans_bundle）与 FAVORITE / PLAY_MODE / GET_LIST 事件本版暂不接，
 * 待真机确认发现后再全量接（下一步）。不新增权限，不暴露文件、账号凭证、
 * 设置或任何非媒体能力。
 */
public class VivoMusicBrowserService extends MediaBrowserService {

    public static final String ACTION_SUPPORT = "com.vivo.musicwidgetmix.support.service";

    private static final String ROOT = "root";
    private static final String NODE_FAVS = "bili_favs";
    private static final String NODE_LOCAL = "local";
    private static final String NODE_QUEUE = "queue";

    private static VivoMusicBrowserService inst;
    private static MediaSession.Token lastToken;

    /** PlayerService 会话 token 就绪时调用：存下来并转发给已创建的本服务实例。 */
    public static void pushSessionToken(MediaSession.Token token) {
        lastToken = token;
        VivoMusicBrowserService s = inst;
        if (s != null && token != null) {
            try { s.setSessionToken(token); } catch (Exception ignored) {}
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private int tokenRetries = 0;

    private static class PlayEntry {
        final List<Track> list;
        final int index;
        PlayEntry(List<Track> l, int i) { list = l; index = i; }
    }
    private static final Map<String, PlayEntry> playCache = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        attachToken();
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    /** 双向取 token：PlayerService 已在就直接拿；不在就拉起它并重试几次。 */
    private void attachToken() {
        PlayerService ps = PlayerService.get();
        MediaSession.Token t = null;
        if (ps != null) t = ps.getSessionToken();
        if (t == null) t = lastToken;
        if (t != null) {
            try { setSessionToken(t); } catch (Exception ignored) {}
            return;
        }
        try { PlayerService.ensureStarted(this); } catch (Exception ignored) {}
        if (tokenRetries < 10) {
            tokenRetries++;
            main.postDelayed(this::attachToken, 500);
        }
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // 不限制调用方包名：vivo 小部件可能以不同进程/包名连接；本服务只暴露媒体能力。
        return new BrowserRoot(ROOT, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        if (ROOT.equals(parentId)) {
            List<MediaBrowser.MediaItem> out = new ArrayList<>();
            out.add(browsable(NODE_FAVS, "B站收藏夹", "B站收藏夹"));
            out.add(browsable(NODE_LOCAL, "本地歌单", "本地歌单"));
            out.add(browsable(NODE_QUEUE, "正在播放", "正在播放的队列"));
            result.sendResult(out);
            return;
        }
        if (NODE_FAVS.equals(parentId)) {
            PlayerService ps = PlayerService.get();
            if (ps == null || !ps.isLoggedIn()) { result.sendResult(new ArrayList<>()); return; }
            result.detach();
            ps.getApi().favFolders(new BiliApi.Cb<List<BiliApi.FavFolder>>() {
                @Override public void onOk(List<BiliApi.FavFolder> v) {
                    List<MediaBrowser.MediaItem> out = new ArrayList<>();
                    if (v != null) {
                        for (BiliApi.FavFolder f : v) {
                            out.add(browsable("fav_" + f.id, f.title, f.count + " 首"));
                        }
                    }
                    result.sendResult(out);
                }
                @Override public void onErr(String msg) { result.sendResult(new ArrayList<>()); }
            });
            return;
        }
        if (parentId.startsWith("fav_")) {
            PlayerService ps = PlayerService.get();
            if (ps == null || !ps.isLoggedIn()) { result.sendResult(new ArrayList<>()); return; }
            long mediaId;
            try { mediaId = Long.parseLong(parentId.substring(4)); }
            catch (Exception e) { result.sendResult(new ArrayList<>()); return; }
            result.detach();
            ps.getApi().favTracks(mediaId, new BiliApi.Cb<List<Track>>() {
                @Override public void onOk(List<Track> v) {
                    result.sendResult(trackItems(parentId, v == null ? new ArrayList<>() : v));
                }
                @Override public void onErr(String msg) { result.sendResult(new ArrayList<>()); }
            });
            return;
        }
        if (NODE_LOCAL.equals(parentId)) {
            List<MediaBrowser.MediaItem> out = new ArrayList<>();
            try {
                for (LocalDb.Playlist p : new LocalDb(this).playlists()) {
                    out.add(browsable("local_" + p.id, p.name, p.count + " 首"));
                }
            } catch (Exception ignored) {}
            result.sendResult(out);
            return;
        }
        if (parentId.startsWith("local_")) {
            long pid;
            try { pid = Long.parseLong(parentId.substring(6)); }
            catch (Exception e) { result.sendResult(new ArrayList<>()); return; }
            List<Track> tracks = new ArrayList<>();
            try { tracks = new LocalDb(this).tracks(pid); } catch (Exception ignored) {}
            result.sendResult(trackItems(parentId, tracks));
            return;
        }
        if (NODE_QUEUE.equals(parentId)) {
            PlayerService ps = PlayerService.get();
            List<Track> q = (ps == null) ? new ArrayList<>() : new ArrayList<>(ps.getQueue());
            result.sendResult(trackItems(parentId, q));
            return;
        }
        result.sendResult(new ArrayList<>());
    }

    /** 播歌入口：由 PlayerService 的会话 callback（onPlayFromMediaId）调用。
     *  框架里播歌方法不在 MediaBrowserService 上，而在 MediaSession.Callback 上，
     *  故缓存与解析放这里静态共享，会话侧只转交。 */
    public static void playMediaId(PlayerService ps, String mediaId) {
        if (ps == null || mediaId == null) return;
        PlayEntry e = playCache.get(mediaId);
        if (e != null && e.list != null && !e.list.isEmpty()
                && e.index >= 0 && e.index < e.list.size()) {
            ps.playQueue(new ArrayList<>(e.list), e.index);
            return;
        }
        // 缓存未命中（如服务重建后）：若该 bvid 就在当前队列里，直接跳过去。
        if (mediaId.startsWith("track:")) {
            String bvid = mediaId.substring(mediaId.lastIndexOf(':') + 1);
            List<Track> q = ps.getQueue();
            for (int i = 0; i < q.size(); i++) {
                if (bvid.equals(q.get(i).bvid)) { ps.playAt(i); return; }
            }
        }
    }

    private MediaBrowser.MediaItem browsable(String id, String title, String subtitle) {
        MediaDescription d = new MediaDescription.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle).build();
        return new MediaBrowser.MediaItem(d, MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private List<MediaBrowser.MediaItem> trackItems(String parentId, List<Track> tracks) {
        List<MediaBrowser.MediaItem> out = new ArrayList<>();
        if (playCache.size() > 1000) playCache.clear();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t == null || t.bvid == null) continue;
            String mediaId = "track:" + parentId + ":" + t.bvid;
            playCache.put(mediaId, new PlayEntry(tracks, i));
            MediaDescription.Builder db = new MediaDescription.Builder()
                    .setMediaId(mediaId)
                    .setTitle(t.title == null ? "" : t.title)
                    .setSubtitle(t.author == null ? "" : t.author)
                    .setDescription(t.author == null ? "" : t.author);
            Bundle ex = new Bundle();
            ex.putString("bvid", t.bvid);
            db.setExtras(ex);
            if (t.cover != null && t.cover.startsWith("http")) {
                try { db.setIconUri(Uri.parse(t.cover)); } catch (Exception ignored) {}
            }
            out.add(new MediaBrowser.MediaItem(db.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }
}
