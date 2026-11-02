# vivo 原子随身听适配协议

> 摘自 MC风月无边对 `com.vivo.musicwidgetmix` 6.2.7.1 的静态逆向结论（2026-11-02），随 v1.51.0 采纳进仓库；协议细节改动前请先读本文件。

## 3. 原子随身听（vivo 原子随身听）适配协议

小部件 APK：`com.vivo.musicwidgetmix`（内部工程名 `VivoMusicWidgetMix`）。
它按**包名**挑控制器：命中内置硬编码名单的走各家专属 SDK；名单外的应用，如果声明了
`com.vivo.musicwidgetmix.support.service` 这个 action 的导出服务，会被它
`queryIntentServices(Intent(ACTION), GET_META_DATA)` 扫到并加入 `custom_insert_music_white_list`，
从而路由到**协作控制器**（内部类 `c0`，日志名 `CooperateController`）——深度通道，而不是通用兜底控制器。

> 协议结论来自对 `com.vivo.musicwidgetmix` 的静态逆向（jadx + aapt dump）。

### 3.1 准入：常驻音乐 App 白名单（**最关键的一关，非 root 可解**）

小部件靠 vivo 框架的 `TrackState:` 回调把「谁在放歌」转给自己，再经
`AppUtils.Z(ctx, 包名)` 判断该包在不在**常驻音乐 App 白名单**里，不在就直接忽略：

```
名单 = Settings.Secure["music_app_white_list"]（vivo 云控下发的 JSON 数组）
     ∪ APK 内写死的 13 个 framework_support_list 包名
↑ 若上面这份为空，则退回 s4.m0.a().d() —— 硬编码在 widget APK 里的 61 个音乐/听书 App
```

不在这份名单里 → 小部件收不到本应用的播放事件 → 不会建控制器 → **协作服务永不被绑定**。
注意：声明 `com.vivo.musicwidgetmix.support.service` 只是让小部件 `queryIntentServices`
扫到你，它决定的是「已在名单里时走 c0 深度通道还是 o0 通用通道」，**不是准入本身**。

那份云控名单存在 `Settings.Secure` 里，**shell 权限（adb / Shizuku）可读写**，不需要 root：

```bash
bash tools/vivo-whitelist.sh            # 追加到名单末尾 + 重启小部件（自动备份）
bash tools/vivo-whitelist.sh --status   # 查看当前生效名单
bash tools/vivo-whitelist.sh --restore  # 还原（并清掉派生缓存）
```

⚠️ **必须「追加」，绝不能只写自己一个**：一旦 `music_app_white_list` 有内容，
那 61 个硬编码名单就会被完全屏蔽，只写本应用会让 QQ音乐 / 网易云 / 波点音乐
（`cn.wenyu.bodian`）等全部从小部件里消失。云控名单为空时，脚本会用
`tools/vivo-system-apps.json`（从 `s4/m0.java` 提取的 61 + 22 + 35 个包名）完整重建，
再在末尾追加本应用；云控已有内容时则原样保留、只追加本应用。脚本会校验 QQ音乐 /
网易云 / 波点 / 本应用都在生效名单里。

- vivo 云控下次下发会覆盖回原值，固件升级后可能需要重跑。
- 写完必须重启小部件进程（`am force-stop com.vivo.musicwidgetmix`），它只在启动时读一次。
- Shizuku 不是必需的：adb shell 已有同样权限；Shizuku 的价值仅是让 App 内部也能做这件事。

### 3.2 本项目的接入点

| 位置 | 作用 |
| --- | --- |
| `src/AndroidManifest.xml` 的 `.VivoMusicBrowserService` | 声明 action + `exported=true`，供小部件发现 |
| `VivoMusicBrowserService` | 框架 `MediaBrowserService`：根节点、列表条目、分页、点播解析 |
| `PlayerService` | 会话协议：自定义 metadata、循环模式事件、错误回执 |

### 3.3 必须守住的协议细节（改错就静默失效）

- **发现入口的 intent-filter 必须带 `CATEGORY_DEFAULT`**：`PackageManager.queryIntentServices` 内部会加
  `MATCH_DEFAULT_ONLY`，filter 里没有 `CATEGORY_DEFAULT` 就**匹配不上**，小部件根本扫不到本应用
  （表现为「协作服务被绑定 0 次」）。AOSP `IntentFilter.matchCategories()` 明确：filter 带
  `CATEGORY_DEFAULT` 时，无 category 的隐式 Intent 一样能匹配上——所以「带 category 反而匹配不上」是误解。
  同时建议一并声明标准 action `android.media.browse.MediaBrowserService`：小部件 manifest 的
  `<queries>` 里声明的就是它（包可见性），带上后本应用对系统媒体浏览器体系同样可见。
- **会话 token 走 `setSessionToken()`**：框架 `MediaBrowser` 是从 Binder 回调
  `onConnect(root, session, extras)` 拿到 token 的，**塞进 `BrowserRoot` extras 无效**。
  且框架只在客户端连接那一刻下发一次，之后再改不会补发——所以 `PlayerService` 在会话建立瞬间
  就要把 token 推给协作服务（`VivoMusicBrowserService.onSessionReady`），晚一步小部件就拿不到控制器。
- **根节点 id 只有三个，固定不变**：小部件直接用它们调 `subscribe(parentId, options)`，不做树遍历。
  - `vivomusicmix_current_list`（正在播放）、`vivomusicmix_favorite_list`（收藏）、`vivomusicmix_local_list`（本地）
  - options 里带 `vivomusicmix_key_media_page`（页码，从 0 开始）。
- **条目字段**：mediaId→歌单标识（`guga:<列表>:<序号>`）、title→曲名、subtitle→歌手；
  最后一条的 description extras 放 `vivomusicmix_key_has_more`(boolean) / `vivomusicmix_key_media_page`(int)。
- **点播**：小部件调 `playFromMediaId(mediaId, extras)`，extras 里 `vivomusicmix_key_list` 指明来自哪个列表。
- **循环模式**：小部件用 `sendCustomAction` 下发 `vivomusicmix.media.action.PLAY_MODE`，
  `vivomusicmix.media.metadata.LOOP_MODE` = 1 顺序 / 2 单曲 / 3 随机；本项目内部是 0/1/2，故 **+1 换算**。
  注意方向：平台 `MediaSession.Callback` **没有** `onSessionEvent`（只有 `MediaController.Callback` 有），
  所以请求侧一律走 `onCustomAction`；回执侧（应用 → 小部件）才用 `MediaSession.sendSessionEvent`。
- **能力位** `vivomusicmix.media.metadata.support_event`：23 = 基础传输(1|2|4) + 支持 seek(16)。
  小部件本身也是按会话 `PlaybackState.actions` 是否含 `ACTION_SEEK_TO` 推导出 23/7，保持一致。
- **错误回执**：平台 `MediaBrowserService.Result` 没有 `sendError`，改用会话事件
  `vivomusicmix.session.event.error`，bundle 里 `key_action` + `error_code`（505=未登录，201=其它）。
- **平台 `MediaMetadata` 允许自定义 key**（只对系统已知 key 做类型校验），所以 vivo 私有字段可以直接 put。

### 3.4 现状与边界

- 已实现：发现、传输控制（播/停/上下曲/进度）、正在播放 / 本地 / 收藏三个列表、点播、循环模式。
- 未实现：歌词透传（`MediaSession.setExtras()` 透传逐行歌词，当前未接，按需再加）、投屏（`cast_state` 恒为 0）、本地收藏（能力位里不声明收藏，小部件不会发收藏动作；
  真发过来平台默认实现什么都不做，不会假装收藏成功）、歌单同步（`GET_LIST` 目前只在未登录时回执 505）。
- 收藏列表 = B 站各收藏夹的歌曲拉平；未登录时按协议回 505。
- 暴露面与标准媒体浏览器服务一致：可浏览歌单标题、可控制播放；不暴露文件、账号凭证、设置等非媒体能力。

**验证状态（2026-11-02，V2339FA / Android 16 / 原子随身听 6.2.7.1）**：
白名单追加后实测小部件绑定成功：`dumpsys activity services com.guga.music` 出现
`cp:com.vivo.musicwidgetmix CR CAPS com.guga.music/.VivoMusicBrowserService`，
`Settings.Secure["MusicWidgetMix_MusicSource"]` 变为 `com.guga.music`，会话
`state=PLAYING(3)` 且 `controllers` 增加。列表拉取为按需触发，展开歌单面板时才调
`onLoadChildren`。验证方式（不依赖 App 内日志）：
`adb shell dumpsys activity services com.guga.music | grep VivoMusicBrowserService`。

