package com.guga.music;

import android.content.Context;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * vivo 原子随身听（包名 com.vivo.musicwidgetmix）适配 —— 框架 MediaBrowserService（协作通道）。
 *
 * <p>为什么需要这个服务：原子随身听的小部件按「包名」挑控制器。命中它内置硬编码名单的
 * （QQ音乐/网易云/酷狗/喜马拉雅…）走各家的专属 SDK；名单外的应用，如果声明了
 * action=com.vivo.musicwidgetmix.support.service 的导出服务，会被它
 * {@code queryIntentServices(Intent(ACTION), GET_META_DATA)} 扫到并加入
 * {@code custom_insert_music_white_list}，从而路由到协作控制器 CooperateController（c0）——
 * 即深度协作通道，而不是通用兜底控制器。
 *
 * <p>协作控制器实际用到的接口（按逆向结论实现，改动前请先看 docs/vivo-protocol.md）：
 * <ul>
 *   <li>连接：{@code onGetRoot} 必须返回非 null；会话 token 通过 {@link #setSessionToken} 下发——
 *       框架 MediaBrowser 由 Binder 回调 {@code onConnect(root, session, extras)} 取到，
 *       <b>不是</b>塞进 BrowserRoot extras，塞 extras 无效。</li>
 *   <li>浏览：它只认三个固定根 id —— {@code vivomusicmix_current_list}（正在播放）、
 *       {@code vivomusicmix_favorite_list}（收藏）、{@code vivomusicmix_local_list}（本地），
 *       通过 subscribe(parentId, options) 拉取，options 里带 {@code vivomusicmix_key_media_page}（页码）。</li>
 *   <li>条目：mediaId→歌单标识，title→曲名，subtitle→歌手；最后一条的 description extras 带上
 *       {@code vivomusicmix_key_has_more} / {@code vivomusicmix_key_media_page} 表示还有没有下一页。</li>
 *   <li>点播：{@code playFromMediaId(mediaId, extras)}，extras 里 {@code vivomusicmix_key_list}
 *       指明来自哪个列表。</li>
 * </ul>
 *
 * <p>安全面：exported 服务，任何应用都能浏览歌单标题并控制播放，与一切标准媒体浏览器服务相同；
 * 不暴露文件、账号凭证、设置等任何非媒体能力。
 */
public class VivoMusicBrowserService extends MediaBrowserService {

    /** 小部件发现用 action，勿改。 */
    public static final String ACTION_SUPPORT = "com.vivo.musicwidgetmix.support.service";

    /** 小部件只认这三个根节点 id，勿改。 */
    public static final String LIST_CURRENT = "vivomusicmix_current_list";
    public static final String LIST_FAVORITE = "vivomusicmix_favorite_list";
    public static final String LIST_LOCAL = "vivomusicmix_local_list";

    /** 小部件传来的页码 / 我们回给它的分页信息。 */
    private static final String KEY_PAGE = "vivomusicmix_key_media_page";
    private static final String KEY_HAS_MORE = "vivomusicmix_key_has_more";
    /** 小部件点播时回传它取自哪个列表。 */
    private static final String KEY_LIST = "vivomusicmix_key_list";

    private static final String ROOT_ID = "guga_root";
    private static final int PAGE_SIZE = 50;

    /** 列表快照：点播时按 mediaId 里的序号取回同一份数据，避免列表刷新导致错位。 */
    private static volatile List<Track> favSnapshot = Collections.emptyList();
    private static volatile List<Track> localSnapshot = Collections.emptyList();
    private static volatile long favStamp = 0L;

    private static volatile VivoMusicBrowserService inst;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int tokenRetry = 0;
    private boolean favLoading = false;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        publishToken(null);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 会话 token

    /**
     * 把 PlayerService 的会话 token 交给框架，框架会随 Binder 回调 {@code onConnect()} 下发给客户端。
     * 时序很关键：客户端连接那一刻框架就把当时的 token 一次性发过去，之后再改不会补发，
     * 所以 token 必须尽早就绪（PlayerService 会在会话建立时立刻调 {@link #onSessionReady}）。
     */
    private void publishToken(MediaSession.Token ready) {
        MediaSession.Token t = ready;
        if (t == null) {
            PlayerService ps = PlayerService.get();
            if (ps != null) t = ps.getSessionToken();
        }
        if (t == null) {
            if (tokenRetry < 40) { // 最多等约 20 秒
                tokenRetry++;
                try {
                    PlayerService.ensureStarted(this);
                } catch (Exception ignored) {
                    // 后台起前台服务在 Android 12+ 可能被拒，等应用自己起来即可
                }
                main.postDelayed(new Runnable() {
                    @Override public void run() { publishToken(null); }
                }, 500);
            }
            return;
        }
        try {
            // 平台的 setSessionToken 只能设一次，重复调会抛 IllegalStateException
            if (getSessionToken() == null) setSessionToken(t);
        } catch (Exception ignored) {}
    }

    /** PlayerService 会话建立后立刻调用：第一时间把 token 交给框架，避免客户端先连上拿到空 token。 */
    public static void onSessionReady(MediaSession.Token token) {
        VivoMusicBrowserService s = inst;
        if (s != null) {
            try {
                s.publishToken(token);
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ 浏览树

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        publishToken(null);
        Bundle extras = new Bundle();
        extras.putString("guga_app", "com.guga.music");
        return new BrowserRoot(ROOT_ID, extras);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        onLoadChildren(parentId, result, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result, Bundle options) {
        int page = 0;
        if (options != null) page = Math.max(0, options.getInt(KEY_PAGE, 0));
        // options 来自任意可绑定本导出服务的客户端：先夹住上界，别让 page*PAGE_SIZE 溢出成负数
        page = Math.min(page, Integer.MAX_VALUE / PAGE_SIZE);
        if (LIST_CURRENT.equals(parentId)) {
            PlayerService ps = PlayerService.get();
            List<Track> q = ps == null ? Collections.<Track>emptyList() : ps.getQueue();
            result.sendResult(pageItems(q, page, LIST_CURRENT));
        } else if (LIST_LOCAL.equals(parentId)) {
            loadLocal(result, page);
        } else if (LIST_FAVORITE.equals(parentId)) {
            loadFavorite(result, page);
        } else {
            result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
        }
    }

    /** 本地音乐：本地歌单里的全部歌曲。 */
    private void loadLocal(final Result<List<MediaBrowser.MediaItem>> result, final int page) {
        if (localSnapshot.isEmpty()) {
            try {
                List<Track> all = new ArrayList<>();
                LocalDb db = new LocalDb(this);
                for (LocalDb.Playlist p : db.playlists()) {
                    List<Track> ts = db.tracks(p.id);
                    if (ts != null) all.addAll(ts);
                }
                localSnapshot = all;
            } catch (Exception ignored) {}
        }
        result.sendResult(pageItems(localSnapshot, page, LIST_LOCAL));
    }

    /** 收藏：B 站收藏夹里各收藏夹的歌曲（拉平）。未登录时按协议回 error_code=505。 */
    private void loadFavorite(final Result<List<MediaBrowser.MediaItem>> result, final int page) {
        PlayerService ps = PlayerService.get();
        final BiliApi api = ps == null ? null : ps.getApi();
        if (api == null || !api.isLoggedIn()) {
            result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
            reportNotLogin(ps);
            return;
        }
        long now = System.currentTimeMillis();
        if (!favSnapshot.isEmpty() && now - favStamp < 60_000L) {
            result.sendResult(pageItems(favSnapshot, page, LIST_FAVORITE));
            return;
        }
        if (favLoading) { // 正在拉取，先给缓存
            result.sendResult(pageItems(favSnapshot, page, LIST_FAVORITE));
            return;
        }
        favLoading = true;
        result.detach();
        api.favFolders(new BiliApi.Cb<List<BiliApi.FavFolder>>() {
            @Override public void onOk(List<BiliApi.FavFolder> folders) {
                final List<Track> all = new ArrayList<>();
                if (folders == null || folders.isEmpty()) {
                    finishFav(result, page, all);
                    return;
                }
                collectFolder(api, folders, 0, all, result, page);
            }

            @Override public void onErr(String msg) {
                finishFav(result, page, Collections.<Track>emptyList());
            }
        });
    }

    private void collectFolder(final BiliApi api, final List<BiliApi.FavFolder> folders, final int idx,
                               final List<Track> acc, final Result<List<MediaBrowser.MediaItem>> result, final int page) {
        if (idx >= folders.size()) {
            finishFav(result, page, acc);
            return;
        }
        final BiliApi.FavFolder f = folders.get(idx);
        api.favTracks(f.id, new BiliApi.Cb<List<Track>>() {
            @Override public void onOk(List<Track> tracks) {
                if (tracks != null) acc.addAll(tracks);
                collectFolder(api, folders, idx + 1, acc, result, page);
            }

            @Override public void onErr(String msg) {
                collectFolder(api, folders, idx + 1, acc, result, page);
            }
        });
    }

    private void finishFav(Result<List<MediaBrowser.MediaItem>> result, int page, List<Track> all) {
        favLoading = false;
        favSnapshot = all;
        favStamp = System.currentTimeMillis();
        result.sendResult(pageItems(favSnapshot, page, LIST_FAVORITE));
    }

    /**
     * 切一页并生成条目。mediaId 形如 {@code guga:cur:12}，点播时按它回查同一份列表。
     * 最后一条的 extras 带上分页信息，小部件据此判断还有没有下一页。
     */
    private List<MediaBrowser.MediaItem> pageItems(List<Track> src, int page, String listId) {
        List<MediaBrowser.MediaItem> out = new ArrayList<>();
        if (src == null) return out;
        // 双保险：即使内部调用方传了超大页码，也不做乘法溢出、不返回负下标
        if (page < 0 || page > Integer.MAX_VALUE / PAGE_SIZE) return out;
        int from = Math.min(page * PAGE_SIZE, src.size());
        int to = Math.min(src.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Track t = src.get(i);
            if (t == null) continue;
            Bundle extras = null;
            if (i == to - 1) {
                extras = new Bundle();
                extras.putBoolean(KEY_HAS_MORE, to < src.size());
                extras.putInt(KEY_PAGE, page);
            }
            out.add(item(listId + ":" + i, t, extras));
        }
        return out;
    }

    private MediaBrowser.MediaItem item(String mediaId, Track t, Bundle extras) {
        // 与播放页/通知栏同口径：匹配到歌词后用原歌名/歌手（服务本身即 Context）
        String name = Lyrics.displayName(this, t);
        String artist = Lyrics.displayArtist(this, t);
        MediaDescription.Builder d = new MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(name)
                .setSubtitle(artist)
                .setDescription(artist);
        if (extras != null) d.setExtras(extras);
        return new MediaBrowser.MediaItem(d.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    // ------------------------------------------------------------------ 点播

    /**
     * 供 PlayerService 的 onPlayFromMediaId 调用：小部件点了某个条目。
     * mediaId 形如 {@code guga:cur:12}；列表 id 以 mediaId 前缀为准，extras 里的
     * {@code vivomusicmix_key_list} 作为交叉校验。
     */
    public static void playMediaId(Context ctx, String mediaId, String listId) {
        if (mediaId == null) return;
        int c = mediaId.lastIndexOf(':');
        if (c < 0) return;
        String kind = mediaId.substring(0, c);
        int idx;
        try {
            idx = Integer.parseInt(mediaId.substring(c + 1));
        } catch (Exception e) {
            return;
        }
        if (listId != null && !listId.isEmpty() && !listId.equals(kind)) return; // 列表对不上，丢弃
        PlayerService ps = PlayerService.get();
        if (ps == null) {
            try {
                PlayerService.ensureStarted(ctx);
            } catch (Exception ignored) {
            }
            return;
        }
        if (LIST_CURRENT.equals(kind)) {
            ps.playAt(idx);
        } else if (LIST_LOCAL.equals(kind)) {
            List<Track> l = localSnapshot;
            if (idx >= 0 && idx < l.size()) ps.playQueue(new ArrayList<>(l), idx);
        } else if (LIST_FAVORITE.equals(kind)) {
            List<Track> l = favSnapshot;
            if (idx >= 0 && idx < l.size()) ps.playQueue(new ArrayList<>(l), idx);
        }
    }

    /**
     * 未登录回执：平台 MediaBrowserService.Result 没有 sendError，
     * 改用协议里的会话事件回执（key_action=GET_LIST + error_code=505），小部件会转成「未登录」提示。
     */
    static void reportNotLogin(PlayerService ps) {
        if (ps == null) return;
        ps.sendVivoError("vivomusicmix.media.action.GET_LIST", 505);
    }
}
