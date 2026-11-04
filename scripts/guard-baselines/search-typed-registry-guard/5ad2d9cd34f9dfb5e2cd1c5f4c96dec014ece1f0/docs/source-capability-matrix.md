# Source 能力矩阵（代码审计）

原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 `1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 `1.0.562 / 562`。本轮核对前的代码 HEAD 为 `567cfbb5d10f3605dc2f9ba6e095faec8a02a3f7`（版本 `1.0.562 / 562`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，不把源码注释当成已验证的服务端事实。

## 边界、证据和术语

- **DIRECT**：客户端直接调用平台公开接口；不是登录态接口，也不保证平台此刻一定返回数据。
- **SERVER_FALLBACK**：客户端构造 SoundWave 服务端路径；仅表示客户端会尝试该路径。该仓库没有 `music_server.py` 或服务端部署代码，故不能据此断言远端路由仍存在、参数被接受或一定返回成功。
- **SCRIPT**：官方/服务端匿名取链未成功后，交给已启用的洛雪脚本；脚本自身 HTTP 还会先尝试客户端直连，再按条件走 `/music/http` 代理。
- 网络异常、非 2xx、无效/空载荷通常在 `PlatformHttp` 或解析器收敛成空串、`null` 或空列表；只有各入口明确判断“非空/有效”才算命中并停止回退。
- 外部平台端点以代码中的 URL 常量/调用表达式记录；SoundWave 端点以相对路径记录，不写入可配置的服务地址。
- 若无从客户端源码确认参数、服务端行为或真实在线结果，明确写“代码未构造 / 未能验证”；不补猜测，不恢复第三方登录或 Cookie。

## Source 设计核对与真实接入面

`app/src/main/java/xyz/cncnlp/soundwave/source/DESIGN.md` 的渐进迁移约束仍适用：小步增量、保持现有 `UrlResolver` 的播放/下载/探测编排和三个互斥泳道；在新路径具备等价降级与审计前，不切换播放/下载消费方。不得借 Source 引入 Repository/Manager/UseCase 包装层。

但 `DESIGN.md` 的早期“仅新增 MusicSource 与 DESIGN / 未改动 Registry”等段落是历史快照，不是当前文件清单；当前已经存在 `MusicSourceRegistry`、`PlatformSourceAdapter`、`CloudSourceAdapter`、`ScriptSourceAdapter`、直接数据源与能力测试。其 `rank(id)` 映射说明也已过时：适配器实际调用 `ApiClient.rankDetail(id, source)`，不是取目录后本地筛选。本文矩阵按当前实现优先。

| 能力/实际入口 | 当前真实路径 | Registry 覆盖边界 |
| --- | --- | --- |
| 歌曲搜索：`SearchController.runSearch` → `searchSongs` | 平台源走 `MusicSourceRegistry.platformSource(id).search()`；歌曲搜索直连优先，异常/空结果或封面低于一半时回退 `ApiClient.search`。全平台搜索以协程并行调用相同路径。 | 已接入普通歌曲搜索；非 TX 的单来源 artist/album 类型搜索仅在原始 source 精确命中五个 canonical ID 时使用新增 searchTyped，跨平台并行类型搜索与 TX 特例不变。 |
| 心动/相似候选搜索：`PlaybackController.playHeartSimilar` | 歌手搜索循环与标题关键词补充循环仍按原顺序请求服务端歌曲搜索；精确 canonical 平台经 `platformSource(source).searchServer(keyword)`，其它 source 原样回到 `ApiClient.search(keyword, source)`。 | 仅委派相同 server-only `/music/search` 请求，不使用可能增加直连候选的 `search()`；source 列表、查询/调用顺序、配额、截断、筛选、异常处理与随机选择不变。 |
| 歌单搜索：`SearchController.runSearch` | 五平台均有匿名直连。非 QQ 走 `platformSource(...).searchPlaylists()`；QQ 先用带 `available` 状态的 `searchTxPlaylistResult`，无结果再走服务端。 | 已接入，但 QQ 有 SearchController 特例；不能据“Registry 支持”推断全链都经 Registry。 |
| 歌单详情、分类浏览 | `ApiClient.playlistDetail` / `playlistDetailSnapshot`；MainActivity 首页 `onOpenPl` 的普通远端详情读取仍在既有 `ProviderRegistry.playlistDetail` gate 后；歌单/榜单详情分别沿用 `PLAYLIST_DETAIL_MS`/`RANK_DETAIL_MS`，有效非空缓存命中跳过自动回源，过期曲目可先显示并后台刷新；已有歌曲后台补全另保留 `canRemote`（排除 liked/local/history 且检查 ID 形状）门槛。搜索页 `onOpenPl` 使用原有 `src0 = pl.source.ifEmpty { "wy" }`，并在现有 `initialSongs.size >= 20 && src0 != "tx"` 时仍跳过补拉；其它情况下仅 `src0` 精确命中五个 canonical 平台 ID 才走 `MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(pl.id, pl)`，其余 source 保留原 `ApiClient.playlistDetailSnapshot(pl.id, src0, pl)`。首页两处同样只对规范五平台走 Registry。`PlaylistFeedRepository` 和 `HomePage` 另有分类、缓存、分页与精选数据逻辑。 | 主业务并非统一通过 Registry。alias、大小写变体、未知、script、cloud 不规范化；推荐/个性化相关路径不扩展、不重构。 |
| 榜单目录/详情 | 固定平台目录：`HomeFeedController` → `MusicSourceRegistry.platformSource(p).rankBoards()` → `PlatformSourceAdapter.rankBoards()` → `ApiClient.rank(source=platform)`；非平台值仍走 `ApiClient.rank(p)`。首页“新歌速递”详情继续经 `platformSource(platform).rank(nb.id)`。 | `rankBoards()` 只表示目录元数据；`rank(id)` 只表示单榜歌曲详情。首页固定平台目录、热榜缓存后刷新、榜单详情页缓存 miss 各有独立路由守卫；新歌详情与其它详情入口保持各自路径。 |
| 首页榜单详情页：缓存 miss 后刷新完整歌曲 | `LaunchedEffect(platform, selectedBoard?.id)` 中，以 `rankContext, board.id, source` 读取含时间戳快照；非空且在原 20 分钟 TTL 内时直接展示并跳过自动网络请求，缺失/过期/无时间戳旧缓存则保留原刷新路径，过期曲目可先展示。source 仍为 `board.source.ifBlank { platform }`，规范平台继续经 `platformSource(source).rank(board.id)`，其余 source 原样调用 `ApiClient.rankDetail(board.id, source)`。 | 缓存键、榜单 ID、未规范化 source、20 分钟 TTL 不变；缓存命中不回源，过期/缺失仍回源，显式强制刷新仍可绕过 TTL。alias、大小写变体与未知值不标准化；热榜、新歌榜与其它详情入口仍独立。 |
| 首页热榜详情：`HomePage` 缓存后刷新 | `LaunchedEffect(requestKey, retryTick)` 使用原始 `board.id/source` 读取榜单详情快照；非空且未超过原 20 分钟 TTL 时跳过自动回源，缺失或过期时先显示可用旧曲再刷新。`retryTick` 明确变化时以 `forceRefresh` 绕过 TTL。仅原始 `source` 精确属于 `wy/kg/kw/tx/mg` 时走 `MusicSourceRegistry.platformSource(source).rank(board.id)`，否则保留 `ApiClient.rankDetail(board.id, source)`。 | 榜单 ID、未规范化 `source` 与缓存键不变。`selectHomeHotBoard` 的 alias 匹配不改变请求来源；显式 retry 仍回源，过期缓存可见同时后台刷新；榜单详情页缓存 miss、新歌榜与其它详情入口仍独立。 |
| 首页热门歌曲、榜单、歌单、热词与发现：`HomeFeedController.refreshHomeFeed` 并发事务 | 原样取得 `p = getHomePlatform()`。热门歌曲、榜单仅 `p in MusicSourceRegistry.platformIds` 时分别走 `platformSource(p).hotSongs()` / `.rankBoards()`，非规范值仍走原 `ApiClient.hot(p)` / `ApiClient.rank(p)`；歌单经原 `PlaylistFeedRepository.fetchFeaturedResult(...)`。并发 `discoverReq` 规范五平台走 `platformSource(p).discoverCardsOutcome(20)` → `PlatformSourceAdapter` → `ApiClient.discoverOutcome(source=platform, limit=20)`，其他 `p` 调 `ApiClient.discoverOutcome(p, 20)`；`keywordReq` 对应走 `platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)` → adapter → `ApiClient.hotKeywordsOutcome(source=platform, limit=limit)`，其他值调用 `ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)`。`.553` 官方精选歌单仍在原第三个并发 batch 调 `recommendPlaylistsOutcome(50)`。 | typed outcome 仅用于首页发现/热词/精选歌单；旧 `ApiClient.discover`、`hotKeywords`、`recommendPlaylists` 和普通 `MusicSource` List 调用保留旧值映射；`MainActivity`/search 热词入口仍不变。精选歌单仍走原 `/music/playlist?action=recommend`，双编码 `source`/`src` 与原 `limit`，不走直连；客户端复用 `parsePlaylistsPayload`。客户端有效歌单数组（包括空数组）为 `Success`；非 2xx、空响应、传输/解析失败、显式失败或缺少认可列表外层为 `Failure`。精选合并选择为空且请求集合有失败时，`fetchFeaturedResult` 返回失败，首页保留旧值、不推进精选成功时间并阻止短路；有效空结果仍按现有 20 分钟 TTL 负缓存。保留三并发 batch 启动顺序、筛选/合并、请求数和时机；以上仅陈述已核对的客户端源码，不推断上游服务端行为。 |
| 首页手动发现刷新：`HomeFeedController.refreshDiscover()` | 原样取得 `p0 = getHomePlatform()`；规范五平台走 `platformSource(p0).discoverCardsOutcome(20)` → `PlatformSourceAdapter` → `ApiClient.discoverOutcome(source=platform, limit=20)`；其他值走 `ApiClient.discoverOutcome(p0, 20)`。 | 与 `refreshHomeFeed` 并发发现仍为两个独立入口，触发时机不合并。成功空列表记录为负缓存；失败不覆盖旧 `discoverMap` / 可见卡片；规范判断保持 exact canonical IDs，不标准化 alias、大小写或其他值。普通调用方仍使用旧 `List` 接口。 |
| 歌词：`LyricSourceLoader.load` / `loadNetworkCached` | `priority=embedded` 时 ID3 先于外部来源；否则 sidecar 先于网络。缓存未命中后，五平台及既有 alias 通过 Registry 单次进入 adapter：匿名直连只尝试一次，未命中后最多 4 轮服务端回退；成功仍由 loader 写原缓存键。非五大来源维持旧直连探测与 `ApiClient.lyric` 路径。 | Registry 只承接五平台网络能力；本地 sidecar/ID3、缓存和非五大来源仍归 loader。 |
| 播放/下载取链 | 播放与 `DownloadScheduler` 最终使用 `UrlResolver.resolve/resolveResult`；服务端匿名取链后才进入脚本级联。下载使用独立 `DownloadScriptRuntimeHolder`。 | 虽然 `SourceCapabilities.supportsResolve` 给部分平台标为 DIRECT，`PlatformSourceAdapter.resolveCapability` 仍默认 `NONE`；实际取链不可改由 Registry 绕过 `UrlResolver`。 |
| 歌手、专辑 | `MainActivity.openSingerCb` 保持 `src0 = s.source.ifBlank { searchPlatform }`；仅当 `src0` 精确命中 `MusicSourceRegistry.platformIds` 时调用 `platformSource(src0).artistDetail(s.id, s.name)` → `PlatformSourceAdapter` → 原 `ApiClient.singerSongs(artistId, singerName, source=platform)`。alias（如 `netease`/`qq`）、大小写不一致、未知值（如 `Meting`/`script:*`/`cloud`）和非规范空值都不标准化，仍原样走旧 `ApiClient.singerSongs(s.id, s.name, src0)`，包含服务端原有空 source 默认值行为；占位 Playlist、空态 Toast、切走守卫和封面回填不变。搜索页 `MainActivity.onOpenAlbum` 仅当原始 `song.source` 精确属于五个 `platformIds` 时调用 `platformSource(song.source).albumDetail(song.albumId)` → `PlatformSourceAdapter` → 同一个 `ApiClient.albumDetail(id, source=platform)`；空 source、alias、大小写不一致以及 `Meting`/`script:*`/`cloud`/unknown 均保留旧 `ApiClient.albumDetail(song.albumId, song.source.ifBlank { "wy" })`（空值仍只在原调用处回退为 `wy`）。该入口只替换 API 调用：`albumId` 空值跳过、返回 Playlist（含 `source`）、详情歌曲判定、同专辑搜索结果回退、空态 Toast 与导航行为不变。发现卡片 `MainActivity.onOpenDiscover` 保持 `src = card.source.ifBlank { "wy" }`；所有卡片类型（包括 `new_song`）仅当该原始 `src` 精确命中 canonical 五平台 ID 时走 `platformSource(src).albumDetail(card.id)` → `PlatformSourceAdapter` → 同一 `ApiClient.albumDetail(id, source=platform)`；其他来源仍原样走 `ApiClient.albumDetail(card.id, src)`。适配器不增加新的直连或回退，`ApiClient.albumDetail` 的既有匿名直连优先、失败/空结果后服务端多路径探测、空 ID 返回 `null` 均不变。现有表达式 `directSong?.let { null } ?: withContext(...)` 中 `let` 返回 `null`，因此右侧请求仍会执行；详情展示仍优先使用 `directSong` 的歌曲。本次保留来源委派、卡片/歌曲来源、缓存键、返回 Playlist、切走守卫、空态与导航；另由原有 `PLAYLIST_DETAIL_MS` 控制有效详情缓存命中时跳过自动回源，缺失/过期仍走原来源请求，`directSong` 展示优先级不变。 | 本轮新增搜索页 `onOpenAlbum` 及发现卡片全类型的规范来源路由；`openSingerCb` 既有路由、非规范来源 fallback 和其他页面均不变。不改其它专辑/歌单、路由、缓存或 API 本体。Registry 不因此成为统一歌手页或通用类型搜索入口；类型搜索只覆盖下述 SearchController 单来源平台调用。 |

`MusicSourceRegistry.capabilitiesOf` 是静态声明，不触发网络；测试 `SourceCapabilitiesTest` 只验证声明矩阵，不验证各上游端点在线。其余真实业务仍直接使用 `ApiClient`、`FeedDirectFetch`、`PlaylistFeedRepository`、`LyricSourceLoader`、`UrlResolver` 和 `ScriptRuntime`；**Registry 尚未覆盖所有业务**。

## 认证与 HTTP 边界

- 五平台歌曲/歌单/榜单/歌词/发现/热门直连均为匿名公开读取。`source/direct/PlatformHttp.kt` 统一使用固定桌面 UA、按调用点指定 Referer、`Accept`，默认不带第三方账号 Cookie。
- 唯一 Cookie 头是发往 `https://music.163.com/` 时可选加入的固定 `os=pc` 协议标记；不含用户身份或凭据。`third-party-account-removal-guard.sh` 对该限制有静态守卫。
- QQ musicu 搜索/歌单部分路径使用匿名 JSON POST；没有 Cookie。
- 直连层没有平台登录、个人 Cookie 绑定或账号态歌单接口。SoundWave 自有账号/同步与本矩阵的平台匿名源请求分离；本次不碰其实现。
- `PlatformHttp` 复用 `AppHttp.shared`：连接/读取超时各 5s，单次 GET 默认 call timeout 8s（具体路径可覆写）；非 2xx、网络或解析异常收敛为空。重定向默认跟随；网易外链取链明确关闭重定向以读首个 `Location`。仅既有酷狗 `mobilecdn` HTTP 主机可通过明文策略。
- `ApiClient` 的 SoundWave 服务端 API 客户端连接/读取/写入/总超时分别为 18/45/25/60s，跟随 HTTP 重定向但禁止 HTTPS 降级；`get` 最多两次请求，中间约 200ms；文本响应有大小上限，歌单详情调用提高到 8 MiB。直接平台 HTTP 与服务端 API 是两条不同通道。

## 能力矩阵：歌曲与分类搜索

### 歌曲搜索（当前平台默认结果）

| 平台 | DIRECT 函数 → URL / 关键参数 | 页码、数量 | SERVER_FALLBACK 与触发条件 |
| --- | --- | --- | --- |
| 网易云 `wy` | `searchWy` → `music.163.com/api/cloudsearch/pc?s={q}&type=1&limit={limit}&offset={(page-1)*limit}`；缺封面时 `api/song/detail/?ids=[...]` 补图 | 1-based page 转 offset；请求 limit 原样使用，补图最多本页 limit 首 | `PlatformSourceAdapter.search` 在直连异常、空、或有封面的条数不足半数时，调 `/music/search?keyword={q}&source=wy&type=song`；空正文再试无 `type` 版本。 |
| 酷狗 `kg` | `searchKg` → `songsearch.kugou.com/song_search_v2?keyword={q}&page=1&pagesize={limit}`；结果不足 3 条追加 `mobilecdn.kugou.com/api/v3/search/song?format=json&keyword={q}&page=1&pagesize={limit}&showtype=1` | `page` 仅保留在函数签名，服务请求固定 page=1；按 limit 截取 | 同上；fallback 尝试 `/music/search?keyword={q}&source=kg&type=song`，响应空才试不带 `type`。 |
| 酷我 `kw` | `searchKw` → `search.kuwo.cn/r.s?all={q}&ft=music&client=kt&cluster=0&pn=0&rn=max(limit,30)&rformat=json&encoding=utf8&vipver=1`；主结果不足 3 条追加 `oiapi.net/api/Kuwo/search?msg={q}&pn=1&rn={limit}` | 服务端页号固定为 0；`page` 忽略；主请求最少取 30 | 同上；回落 `/music/search?keyword={q}&source=kw&type=song`，空正文再试不带 `type`。 |
| 咪咕 `mg` | `searchMg` → `app.c.nf.migu.cn/MIGUM3.0/v1.0/content/search_all.do?text={q}&pageNo={page}&pageSize={limit}&searchSwitch={song:1}` | pageNo 一基；pageSize=limit | 同上；回落 `/music/search?keyword={q}&source=mg&type=song`，空正文再试不带 `type`。 |
| QQ `tx` | `searchTx` → POST `u.y.qq.com/cgi-bin/musicu.fcg`，JSON block `music.search.SearchCgiService` / `DoSearchForQQMusicDesktop`；`param={search_type:0,query,page_num,num_per_page,grp:1}` | `page_num=max(page,1)`；`num_per_page=limit` 限在 1..50 | 同上；回落 `/music/search?keyword={q}&source=tx&type=song`，空正文再试不带 `type`。 |

`ApiClient.search` 没有 page/limit 参数；服务端搜索请求不带分页或数量上限。响应载荷解析失败/空则返回空列表。Registry 的歌曲 search 不是 `ApiClient.search` 的别名：先直连，再按以上阈值决定是否服务端回退。

### 类型搜索、歌单搜索与分页

- **艺术家/专辑类型搜索**：`SearchController` 先按既有 `src = canonicalSourceId(source)` 处理输入（trim/lowercase；只有 `qq` 特殊映射为 `tx`），空关键词仍提前返回。单来源 TX 特例经 `platformSource(src).searchTypedResult(q, typ)` 委派原 `searchTxTypedResult`，保留 `songs + available` 返回状态、`musicu.fcg` POST 与 `search_type=1`（artist）/`2`（album）；QQ 搜索别名与大小写/空白变体归为 TX 的行为不变。非 TX 单来源 artist/album 查询仅在既有 `src in MusicSourceRegistry.platformIds` 时调用 `platformSource(src).searchTyped(q, typ)` → `PlatformSourceAdapter.searchTyped` → 完全相同的 `ApiClient.search(keyword, source=platform, type=type)`。因此 exact canonical 值路径不变，大小写或首尾空白规范平台值现在也只切换委派包装：旧 fallback 已将同一个 `src` 传给 `ApiClient.search`，请求仍在同一位置、同一门槛发出，source/query/type、服务端 URL、空响应移除 `type` 的重试、Song 筛选/排序、返回 `List` 与异常收敛均不变。没有被既有 `canonicalSourceId` 映射的非 TX alias（如 `netease`）、unknown、`script:*`、`cloud` 仍原样保留 `ApiClient.search(q, src, typ)` fallback。跨平台并行 artist/album 查询只将精确 canonical `p` 的 TX 直连结果投影为 `.songs` 经 `platformSource(p).searchTypedResult(q, typ)` 委派；其它 canonical `p` 仍将原 `ApiClient.search(q, p, typ)` 改经 `platformSource(p).searchTyped(q, typ)`，参数与结果不变，非 canonical 值仍留有原 API fallback。`typ == "song"` 分支、跨平台源集合/并发/合并/去重、generation 竞态与缓存键/写入不变。`MusicSource.search(keyword,page,limit)` 仍是歌曲搜索；独立 `searchTyped(keyword,type)` 默认空结果，TX typed-result 委派只复用同一 direct helper、不新增 API/回退。
- **相似歌曲搜索（`PlaybackController.playSimilarSong`）**：查询仍由原来的 `item.singer` 与 `item.name.take(8)` 拼接，来源仍为 `item.source.ifEmpty { "wy" }`；仅 source 精确属于五个 canonical ID 时调用 `platformSource(source).searchServer(query)`，适配器原样调用 `ApiClient.search(keyword, source=platform, type="song")`。其他 source 保留原 `ApiClient.search(query, source)`。此处刻意使用 server-only 方法，不调用会增加平台直连候选的 `MusicSource.search()`；第一个不同 `Song.key()` 的筛选、异常/取消处理、事务门与提示行为不变。
- **歌单搜索 `searchPlaylistDirect(source,keyword,limit,page)`**：统一先把 page 限在 1..10000；`limit` 在直连入口按 `max(50,min(limit,200))` 形成有效请求量。平台页码编码如下：

| 平台 | DIRECT URL / 分页 | 服务端回退 |
| --- | --- | --- |
| wy | `api/playlist/list?cat={q}&order=hot&limit={lim}&offset={(page-1)*lim}`；关键词备用 `api/search/get?type=1000&s={q}&limit={lim}&offset=...` | `/music/playlist?action=search&keyword={q}&source=wy&src=wy&limit={lim}` |
| tx | `c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no={page-1}&num_per_page={lim}&...&query={q}` | 同路径，`source/src=tx` |
| kg | `mobilecdnbj.kugou.com/api/v3/search/special?keyword={q}&page={page}&pagesize={lim}` | 同路径，`source/src=kg` |
| kw | `search.kuwo.cn/r.s?client=kt&all={q}&pn={page-1}&rn={lim}&uid=794762570&ver=kwplayer_ar_9.2.2.1&...` | 同路径，`source/src=kw` |
| mg | `app.c.nf.migu.cn/MIGUM3.0/v1.0/content/search_all.do?text={q}&pageNo={page}&pageSize={lim}&searchSwitch={song:0,songlist:1}` | 同路径，`source/src=mg` |

`ApiClient.searchPlaylists` 实际只发 `/music/playlist?action=search&keyword={q}&source={src}&src={src}&limit={n}`，不发 page/offset。`PlatformSourceAdapter.searchPlaylistsPage` 对 page>1 的服务器回退会取至多 200 条前缀后本地切片；起始下标 `>=200` 则空列表。第一层直连失败/空才回退。QQ 的 `SearchController` 页面保留“结果为空 vs 不可用”的 `available` 状态，并与通用适配器走法不同。

- **分类歌单搜索与分页（`.520`，仅首页分类筛选链路）**：`HomePage` 的分类首屏与加载更多请求使用 `MusicSourceRegistry.platformSource(platform).searchPlaylistsPageOutcome(...)`；这只是该分类分页链的显式结果契约，不代表所有 `SourceRegistry` 或歌单搜索入口已统一迁移。DIRECT 优先与空结果/失败后的服务端回退顺序保持既有平台策略；page>1 的服务端回退仍最多取 200 条前缀后本地切片。分类请求明确区分 `Success(playlists)`（允许成功空列表）与 `Failure`，不把失败折叠成成功空列表。首次分类刷新中，成功空列表仍作为有效快照写入原 20 分钟歌单浏览缓存；失败不写空缓存，保留当前展示状态并可重试同一页。通用 `searchPlaylists` / `searchPlaylistsPage`、搜索页、精选/推荐及其它普通搜索调用仍保持原 `List<Playlist>` 语义。相关测试源码存在；本次未运行测试，CI 未验证。

### 歌单详情与类别数据

- 真实入口分别是 `ApiClient.playlistDetail(id,source)` 与 `ApiClient.playlistDetailSnapshot(id,source,seed)`：前者先 `fetchPlaylistDetailDirect`；后者先取 `fetchPlaylistDetailPayloadDirect`（歌曲及同源元数据），直连异常或歌曲为空时再用 `playlistDetailServer(id,source)`，最终歌曲仍为空则返回 `null`。二者均不以热门歌填充歌单。详情客户端上限为 1100 首（平台接口可能有更低硬上限）。
- 直连函数 `fetchPlaylistDetailDirect` 按来源自派发：wy `/api/playlist/detail?id={pid}`（空结果再试 `/api/playlist/detail?id={pid}&n={cap}&s=0` 变体，必要时按 trackIds 调 `/api/song/detail/?ids=[...]`）；tx GET `u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=...`，其中 `song_begin=0,song_num=500`，并发读取同源歌单元数据；kg `mobilecdn.kugou.com/api/v3/special/song?specialid={pid}&page=1&pagesize=...`；kw `nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid={pid}&pn=0&rn=...`；mg `app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?pageNo=1&pageSize=...&playlistId={pid}`。KG/KW/MG 请求量由详情常量限制（最高 1000）；QQ URL 固定声明 500；WY 由 `MAX_DETAIL_SONGS=1100` 控制。详情没有上层 page/offset。
- 详情服务器回退依次探测 `/music/playlist?action=detail&id=...&source=...&limit=1100`、将 `source` 换 `src`、`/music/playlist/detail?id=...&source=...&limit=1100`、`/music/playlist?id=...&source=...&limit=1100`；只有解析出非空真实歌曲列表才命中。是否每个端点在线有效未能从客户端仓库验证。
- 分类组：仅原始 `platform` 精确为 `wy/kg/kw/tx/mg` 时，`PlaylistFeedRepository.fetchPlaylistCategoryGroups` 才经 `MusicSourceRegistry.platformSource(platform).playlistCategoryGroups()` 路由。WY 适配器继续调用同一 `fetchWyPlaylistCategoryGroupsDirect()` → `music.163.com/api/playlist/catalogue`，保持空列表→`null`、非空→`ApiClient.CatGroups`；KG/KW/TX/MG 仍委派至同一 `ApiClient.plCatGroups(platform)` → `/music/plcats?source={source}`。别名、大小写/空白变体、空值、script/cloud 与 unknown 均保留旧 `ApiClient.plCatGroups(canonicalSourceId(platform))` 回退；请求 source 规范化及端点行为未变。分类组 TTL 缓存、分类查询词、网易分类歌单搜索请求与分页、后续类别歌单/推荐分支均未改。

## 能力矩阵：榜单、热词与发现

### 榜单

榜单**目录**与单榜**详情**使用不同 API。`ApiClient.rank(source)` 调用 `fetchRankBoardsDirect(source)`，非空则直接返回，异常/空结果才调用原 `rankServer(source)`：非空 `source` 请求 `/music/rank?src={source}`，空来源请求 `/music/rank` 聚合。字面值 `source="all"` 并不等同空来源，源码会请求 `/music/rank?src=all`。`HomeFeedController` 仅对 `MusicSourceRegistry.platformIds` 中的固定平台经 `rankBoards()`；其它值继续原样调用 `ApiClient.rank(p)`，不强行交给固定平台 adapter。平台目录直连摘要：

| 平台 | DIRECT 实际路径与 limit/page | 详情 SERVER_FALLBACK |
| --- | --- | --- |
| wy | `fetchRankBoardsDirect` → `/api/toplist/detail` 合并榜单目录；目录卡并发 6 路调用 `/api/playlist/detail?id={id}` 预览 3 首。详情 `wyPlaylistDetail(pid,limit)` 最多默认 1000；空时 `?id&n={cap}&s=0`，trackIds 再按 200 个一批请求 `/api/song/detail/?ids=[...]`。 | `rankDetailServer` 顺序试 `/music/rank?id&src`、`id&source`、`bangId&src`、`topId&src`、`topid&src`、`/music/rank/detail?id&src`、`/music/playlist?action=detail&id&source`、相同路由 `src`；首个实际解析到歌曲的结果胜出。 |
| kw | 目录 `/api/www/bang/bang/bangMenu?httpsStatus=1`；详情 `/api/www/bang/bang/musicList?bangId&pn&rn=30&httpsStatus=1`，pn 从 1 开始；最多 100 首。目录预览按 6 路分批。 | 同上多路径服务端回退。 |
| tx | 目录 `/v8/fcg-bin/fcg_myqq_toplist.fcg?...`；详情 `/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid={id}&page=detail&type=top&tpl=3&format=json`，最多 80 首；详情不接收页码。 | 同上多路径服务端回退。 |
| kg | 目录 `http://mobilecdn.kugou.com/api/v3/rank/list?withsong=0`；按 `rankid` 获取 `/rank/vol?rankid&ranktype&plat=0&with_res_tag=1` 最新期号，再分批请求 `/rank/song?version=9108&ranktype&plat=0&pagesize={<=100}&area_code=1&page={1..N}&volid&rankid&with_res_tag=1`；默认最多 80 首。 | 同上多路径服务端回退。 |
| mg | 目录 `c.musicapp.migu.cn/pc/bmw/rank/rank-index/v1.0`；曲目 `app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do?columnId={id}&needAll=1`，上层最多 100 首；目录分组最多 7 路并发，首页预览 8 首使用 `needAll=0`。 | 同上多路径服务端回退。 |

`MusicSource.rankBoards()` 是目录接口，`PlatformSourceAdapter.rankBoards()` 原样委派 `ApiClient.rank(source=platform)`，因此保留同一 `fetchRankBoardsDirect`、非空命中规则、空/异常后 `rankServer` 回退和平台 source。`rankDetail(id,source)` 则由 `fetchRankDirect` 按平台取歌曲；异常/空曲目才运行 8 路 server 探测。`PlatformSourceAdapter.rank(id)` 原样委派同一 `ApiClient.rankDetail(id,source=platform)`。首页热榜详情仅在原始 source 精确命中五个 canonical ID 时接入该适配器；有效缓存跳过自动请求，过期/无时间戳缓存先展示再刷新，显式 retry 绕过 TTL，alias/大小写/未知 source 继续旧调用，board ID 与原始 source 缓存键不变。首页榜单详情页在详情缓存 miss 或过期时请求歌曲：规范 source 使用同一 `rank(id)` 委派，非规范 source 保留原样 `ApiClient.rankDetail(board.id, source)` fallback；缓存命中继续跳过请求。空结果及各自现有错误/loading/retry 行为不变。首页新歌详情保持独立；`OfficialRankCard` 的缺曲补拉路由见本文末尾单独记录，其余搜索/歌单/发现/缓存实现均未改。

### 热门歌曲 / 热搜词 / 发现卡

这些能力在 `ApiClient` 中本地优先，但不属于 `MusicSource` 接口；以下 server path 只记录客户端当前请求，不证明远端行为。

| 资源 / 函数 | DIRECT 顺序与边界 | SERVER_FALLBACK |
| --- | --- | --- |
| 热门 `ApiClient.hot` → `fetchHotDirect` | wy 热歌歌单 `3778678` 前 30，空则 kw 搜“流行”；kw 榜 `17` 前 30，少于 10 再搜索“热歌”并合并封顶 30；kg 搜“热歌”18；tx 榜 `26` 18；mg 栏目 `27186466` 18。`source=all` 合并 wy→kw→kg→tx→mg，每源最多 30、总量最多 80。 | `/music/hot?source={source}`，空再 `/music/hot?src={source}`。 |
| 热词 `ApiClient.hotKeywords` → `fetchHotWordsDirect` | limit 夹在 1..20；kw `/api/www/search/searchKey?key=`；tx `/splcloud/fcgi-bin/gethotkey.fcg`，不足再搜“热歌”；kg `/api/v3/search/hot?format=json&plat=0&count={n}`；wy 取热歌歌单曲名；mg 搜“热歌”。不足时按来源再搜索平台歌曲名；`all` 最后再试 wy 热歌单。 | `/music/hotword?source={source}&limit={limit}`。 |
| 发现 `ApiClient.discover` → `fetchDiscoverDirect` | wy 数字专辑 `/api/vipmall/albumproduct/list?limit&offset=0&total=true&area=ALL` → 新碟 `/api/album/new?area=ALL&limit&offset=0` → 新歌 `/api/personalized/newsong?limit`；每段异常独立忽略、limit 4..30，三段最多并发 3。tx `/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=26&format=json&newsong=1&g_tk=5381`；kg/kw 搜“新歌”且至少取 18；mg 栏目 `27553319` 且至少取 18。 | `/music/discover?source={source}&src={source}&limit={limit}`；解析时拒绝与请求 source 不一致的记录。 |

## 能力矩阵：歌词、专辑、歌手、媒体取链

### 歌词

| 平台 | DIRECT `fetchLyricDirect` URL 顺序 | SERVER_FALLBACK / 本地 | 分页、缓存和错误 |
| --- | --- | --- | --- |
| wy | `/api/song/lyric?id={id}&lv=1&kv=1&tv=-1&rv=-1&yv=1&ytv=1`（yrc），无有效 yrc 再同路径不带 `yv/ytv` 取 lrc；同一响应的可选 `tlyric` 与原生 `romalrc` 仅按完全相同时间戳配对；id 须纯数字 | `/music/lyric?name&singer&id&source` | 无分页；每请求 6s；上游未提供或时间戳不匹配时罗马音留空。 |
| kw | `wapi.kuwo.cn/openapi/v1/www/lyric?musicId={rid}`，空再 `m.kuwo.cn/newh5/singles/songinfoandlrc?musicId={rid}` | 同上 | 无分页；6s；rid 清洗 `MUSIC_` 与非数字。 |
| kg | `lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword={name}&hash={id}` 取首候选，再 `lyrics.kugou.com/download?ver=1&client=pc&id={candidate}&accesskey={key}&fmt=lrc` | 同上 | 无分页；每请求 7s；歌词内容 Base64 解码。 |
| mg | `music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId={id}` | 同上 | 无分页；7s。 |
| tx | `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid={id}&format=json&nobase64=0&g_tk=5381` | 同上 | 无分页；7s；JSONP + Base64 解码。 |

直连未实现服务器的酷我/网易关键词二段歌词兜底；服务端 `/music/lyric` 是现存客户端回退点。网易云仅在主歌词同一响应中读取可选 `tlyric` 和原生 `romalrc`，罗马音不匹配时间戳时留空；其他来源仍按既有字段处理。`LyricSourceLoader.load` 在 `priority=embedded` 时先尝试 ID3v2 `USLT`，再走外部来源；否则先查同名 `.lrc/.qrc/.krc` sidecar，再走网络缓存/请求，网络和 sidecar 均无结果后再试 ID3。`loadNetworkCached` 先读 `CacheManager.loadLyric(song.key()+"|v2yrc")`，非空即用；缓存仍无 TTL，且写入仍受 `CacheManager.saveLyric` 的可选缓存空间闸门限制。

五平台 alias 只用于挑选 adapter：`wy/netease`、`kg/kugou`、`kw/kuwo`、`mg/migu`、`tx/qq/tencent`；不改写传给服务端的 `Song.source`、平台原始 `id` 或 `Song.key()`。`PlatformSourceAdapter.lyric` 在一次调用中先调用 `fetchLyricDirect(song.source, song)` 一次；命中原样返回，未命中则服务端最多 4 轮，每轮先请求原 `Song`，仅当返回空且 `id` 非空时再请求原有 singer 变体，轮间等待 220/440/660ms。故歌词直接端点自身的二级请求最多运行一趟，不会被四轮 server fallback 重新触发。`ApiClient.get` 的内部两次尝试语义不变。

`meting` 等非五大来源不匹配平台 alias 时，仍留在 loader 原有匿名直连探测与 `ApiClient.lyric` 服务端回退链；没有改为 Script/Cloud adapter。直连仍使用现有无账号态 `PlatformHttp`；本次不恢复第三方 Cookie、登录态或会员能力。

### 专辑与歌手

- 专辑 `ApiClient.albumDetail(id,source)`：wy `music.163.com/api/v1/album/{id}`、tx `c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg` 为 DIRECT；空/失败回退 `/music/album?id&source`、`id&src`、`albumId&source`、`/music/album/detail?id&source`。`PlatformSourceAdapter.albumDetail(page,limit)` 不向底层传 page/limit，返回完整列表。
- Registry 静态声明 kg/kw/mg 专辑为 `SERVER_FALLBACK`；但同一 `ApiClient.albumDetail` 注释明确称这些平台的服务端路由“本就 404”。客户端确实尝试多路径，**此声明与实现注释有冲突，服务端实际支持情况未能验证**；不将 404 注释扩写为实时事实，也不擅自改能力位。
- 歌手详情 `ApiClient.singerSongs(id,name,source)` → `/music/singer?id={id}&name={name}&source={source}`，解析同源歌曲后组装 Playlist；`openSingerCb` 只对精确 canonical 平台 id 经 `MusicSource.artistDetail` 原样委派该方法，其余来源维持原调用和 source 值。该路径仍是服务端能力，未增加客户端直连。歌手/专辑类型搜索另见上文，不能与歌手详情混为一项能力。

### 播放 / 下载取链（本次不迁移）

`UrlResolver.resolve/resolveResult` 是唯一跨源编排入口。普通匿名音质请求（非 local，且非 flac/hires/master，并非 `originOnly/forceCross`）先走平台官方取链：

| 来源 | DIRECT 取链函数/URL | SERVER_FALLBACK / SCRIPT |
| --- | --- | --- |
| kg | `kugouDirectResolveUrl(id)` → `m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash={id}` | 空/失败走 `ApiClient.officialUrl` → `/music/url?source&id&br[&keyword]`；再脚本。 |
| mg | `miguDirectResolveUrl(id)` → `app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceId={id}&resourceType=2&channel=0&format=2` | 同上。 |
| kw | `kuwoDirectResolveUrl(id,br)` → `antiserver.kuwo.cn/anti.s?type=convert_url3&rid={rid}&format=mp3&response=url&br={br}`；br 依次当前值/flac/320/192/128 去重，URL 需通过 500000-byte 媒体探测闸门 | 同上。 |
| wy | `wangyiDirectResolveUrl(id)` → `music.163.com/song/media/outer/url?id={id}.mp3`，关闭重定向读取首个 Location，拒绝非 http 或含 `404`，不带账号 Cookie | 同上。 |
| tx | 未找到 TX 客户端官方直连取链；代码直接尝试 `/music/url`，失败后脚本 | `SERVER_FALLBACK → SCRIPT`。 |

脚本顺序由 `SmartSourceStore.orderedScripts`、首选平台及熔断状态决定；最多纳入 4 个脚本，总取链预算 26s。首脚本先快速尝试首选/320k，之后才有音质降级、其它脚本、跨平台候选等阶段（是否启用受设置和 `originOnly/forceCross` 影响）。`ScriptRuntime.nativeFetch` 先做 SSRF allowlist 检查并尝试直连；被策略拒绝或直连失败后，在 `proxyBase` 非空且原 URL 不是 `/music/http` 时 POST 到 `{proxyBase}/music/http`，请求 URL、method、过滤后的 headers 和 body 放 HTTPS JSON body；代理响应解包后再交脚本。脚本能力不意味着平台登录。

取链 URL 缓存为进程内 LRU，键含歌曲与请求音质，TTL 30 分钟、最多 80 条；播放/探测缓存命中仍验证媒体可播放性，下载路径不做该次播放探测。`UrlResolver` 有播放、下载、探测三条独立 Mutex 泳道；下载的 `DownloadScheduler` 通过独立脚本 runtime 调用 `resolveResult`。Source 层不得绕过这些语义。

## 缓存、错误和并发汇总

| 边界 | 现有实现 |
| --- | --- |
| HTTP 响应缓存 | `AppHttp`/`PlatformHttp` 未配置 OkHttp response cache；不把磁盘业务缓存误称为 HTTP 缓存。 |
| 首页磁盘缓存 | `HomeFeedCache` 资源 TTL 常量：热词 20 分、热门 20 分、榜单目录 6 小时、榜单详情 20 分、精选歌单 20 分、歌单详情 2 小时；查询/列表缓存是否使用具体 TTL 仍看各调用点。搜索缓存读写使用热词 TTL 20 分。 |
| 歌词 | `CacheManager` 文件缓存键 `song.key()|v2yrc`，无 TTL 字段；读缓存命中时不发网络。 |
| 播放/下载 URL | `UrlResolver` 进程内 LRU 30 分钟；失败脚本熔断按“歌曲+脚本”90 秒；媒体流缓存另由 `PlayerService` 管理，本次不改。 |
| 直连失败 | `PlatformHttp` 把网络异常、超时、非 2xx 变为空串；解析函数返回空/null；调用点决定是否回退。无端点的成功行为可从静态代码外推。 |
| 并发 | `PlatformHttp` 每个请求在 `Dispatchers.IO`；`FeedDirectFetch.fetchDiscoverSectionsConcurrently` 用 `Semaphore(3)`；榜单目录请求 wy/kw 分批 6 路、mg 分批 7 路，QQ 歌单详情歌曲/元数据并发；首页多个 feed 使用 coroutine 并发；无全局 Source 并发限流。 |
| 搜索竞态 | `SearchController.requestGeneration` 用 generation 丢弃过期 UI 结果；跨平台搜索逐平台并发，结果按 source+远端身份去重；搜索缓存的实际新鲜度依调用点传入的 20 分 TTL。 |

## 歌词迁移审查结论

歌词能力已按单能力边界接入 Registry。`LyricSourceRouting` 把整段匿名直连与有界服务端重试封装成一次 adapter 调用；loader 在调用 Registry 前不再独立触发同一平台直连。纯 JVM `PlatformLyricRoutingTest` 覆盖 alias、直连命中、一次直连上限、4 轮 / 最多 8 次逻辑服务端调用、空 ID 跳过 singer 变体、退避与 source/id/key 保真；`lyric-source-contract-guard.sh` 静态守护这些契约和缓存/本地优先级。

迁移没有改变同步 `load` / `runBlocking` 边界或播放器调用点。播放器刷新、预加载和悬浮歌词的后台工作、代次校验以及取消行为维持原实现；测试没有运行编译或 Gradle 构建（当前任务明确禁止）。

此前歌词、首页目录/新歌、专辑与歌手详情迁移边界继续适用。后续按单点逐步迁移：HomePage 首页热榜缓存后刷新、首页榜单详情缓存 miss、MainActivity 榜单详情/普通歌单快照及 OfficialRankCard 缺曲补拉均有各自的精确路由说明；每一处非 canonical source 仍保留其原 ApiClient 分支，其余 rank detail 入口维持原路径。五平台匿名访问保留，认证代码未恢复；推荐/首页 UI、播放器 UI/转场、私人雷达和每日推荐均未触碰。

## MainActivity 热门详情的 Source Registry 迁移点

首页热门歌单详情 loader 保持 `src0 = pl.source.ifEmpty { homePlatform }` 原样值；`hot_` ID 分支仍先读取 `homeFeed.multiHot[src0].orEmpty()`，仅缓存为空时继续请求。只有 `src0 in MusicSourceRegistry.platformIds` 才走 `MusicSourceRegistry.platformSource(src0).hotSongs().take(40)`，适配器原样委派 `ApiClient.hot(source = platform)`；其它来源仍走 `ApiClient.hot(src0).take(40)`。因此缓存优先级、source、40 首上限和非规范值回退不变；ID 匹配、榜单/歌单详情及其他分支未迁移。

## MainActivity 榜单歌单详情的 Source Registry 迁移点

本地/云歌单详情 loader 继续保留 `ProviderRegistry.capability(src0, prefs).rank` gate，并仍由 `pl.id` 去掉 `rank_` 得到 `rid`。仅当原始 `src0` 精确属于 `MusicSourceRegistry.platformIds` 时，主请求仍走 `platformSource(src0).rank(rid)`，由 `PlatformSourceAdapter.rank` 委派同一 `ApiClient.rankDetail(rid, source = platform)`。榜单结果为空时，规范平台的 fallback 现在走 `platformSource(src0).playlistDetail(rid)`；适配器只原样调用 `ApiClient.playlistDetail(id, source = platform)`，保持传入的 `rid`、同一个规范 source、`List<Song>` 返回契约以及 ApiClient 内部的 direct-first/`playlistDetailServer` 回退，不改 ID、异常或空结果处理。非规范 source 仍原样走 `ApiClient.rankDetail(rid, src0).ifEmpty { ApiClient.playlistDetail(rid, src0) }`，不标准化 source。详情缓存读写、读取后请求时机、fallback 顺序、loading/空态、导航及其它 rank/hot/分类请求均不变。

## MainActivity 普通远端歌单详情快照的 Source Registry 迁移点

`onOpenPl` 的普通远端详情分支仍先经过 `!ProviderRegistry.capability(src0, prefs).playlistDetail -> emptyList()` gate；原始 source 仍是 `src0 = pl.source.ifEmpty { homePlatform }`，`displayPlaylist = pl.copy(source = src0)`、`remotePlaylistId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }` 均未改。只有此 gate 后、且 `src0 in MusicSourceRegistry.platformIds` 的普通详情请求改走 `MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(remotePlaylistId, displayPlaylist)`；`PlatformSourceAdapter` 只委派同一个 `api.playlistDetailSnapshot(id, source = platform, seed = fallback)`。alias、大小写变体、未知值以及 `Meting`/`script:*`/`cloud` 等仍原样调用 `ApiClient.playlistDetailSnapshot(remotePlaylistId, src0, displayPlaylist)`，不标准化 source。

`ApiClient` 仍负责 snapshot 的 direct-first：直接 payload 异常或无歌曲时原样调用服务端 `playlistDetailServer(id, source)`，服务端也无歌曲时返回 `null`；快照元数据仍由同源直连结果补充、缺失字段回用 `displayPlaylist`。本段记录的普通远端快照迁移不改变 `rank_` / `hot_` 分支；榜单歌单的 legacy List fallback 是上段单独记录的等价委派。普通详情的非空 seed、ProviderRegistry gate、`remotePlaylistId`、缓存键与读写、`fullDetail`/歌曲 merge、空列表、loading、导航，以及 MainActivity 其它 snapshot 调用点均保持不变。`PlaylistFeedRepository` 仅处理分类与首页精选歌单数据，不拥有单歌单详情请求；HomePage、Album、推荐及所有 ProviderRegistry/Meting/script/cloud 分支不迁移。


## OfficialRankCard 缺曲补拉的 Source Registry 迁移点

`OfficialRankCard` 仅在 `board.songs.size < 3` 且榜单 ID 非空时补拉详情。原始 `board.source` 精确属于 `MusicSourceRegistry.platformIds` 时调用 `platformSource(board.source).rank(board.id)`，由适配器委派同一个 `ApiClient.rankDetail(id, source=platform)`；alias、大小写变体、未知、`script:*`、`cloud` 和空 source 均保留原样的 `ApiClient.rankDetail(board.id, board.source)`。取前三首、loading 收束、卡片点击与封面逻辑均不变；已有三首歌曲时仍不发请求。

## PlaylistFeedRepository 官方推荐歌单 Source Registry 迁移点

`PlaylistFeedRepository.fetchFeatured` 先按既有逻辑 `trim().lowercase(Locale.ROOT)` 并拒绝五个规范平台以外的值；官方推荐仍是与分类查询、个性化查询并发启动的第三个 batch。适配器只转发 `ApiClient.recommendPlaylistsOutcome(source = platform, limit = 50)`，保留原 `/music/playlist?action=recommend` URL、`source`/`src` 两个编码参数、limit 与 `parsePlaylistsPayload`，不调用可能增加候选的 `searchPlaylists` 或任何直连。`.553` 仅为首页增加 `Success(list)`/`Failure` 状态：客户端成功 HTTP 文本需包含认可的列表数组外层（空数组也是 `Success(emptyList())`）；非 2xx、空文本、传输/JSON/结构解析失败或显式失败标志为 `Failure`。旧 `ApiClient.recommendPlaylists` 与旧 `MusicSource.recommendPlaylists` 仍将结果映射为原 `List`（失败映射空列表），其他入口不变。若合并结果为空且请求集合含失败，Repository 结果失败，首页保留旧精选缓存且不推进精选成功时间、失败标志阻止短路；成功空结果仍沿用 20 分钟 TTL 负缓存。此处记录的是客户端请求与解析源码，不声明或验证上游服务端行为。请求数量/时机、server-only 路径、批次启动及后续合并顺序、筛选、缓存时序均不扩展。

## MainActivity 本地歌单播放量刷新 Source Registry 迁移点

`.555` 的 `onRefreshPlaylistCounts` 仍筛选同一批非本地且 source 非空的用户歌单，并保留原有 `remoteId` / ID / 名称匹配、首个结果回退、仅正数且变化时更新的逻辑。仅当原始 `pl.source` 精确属于 `wy/kg/kw/tx/mg` 时，搜索改走 `platformSource(pl.source).searchPlaylistsServer(pl.name, 20)`；适配器原样委派 `ApiClient.searchPlaylists(keyword, source = platform, limit = limit)`，因 source 已精确匹配 adapter ID，保留同一个服务端 URL、source、limit、解析与 `List<Playlist>` 结果。其他 source 继续原样调用 `ApiClient.searchPlaylists(pl.name, pl.source, 20)`，不 trim、lowercase 或别名规范化。这里特意使用 server-only 方法：通用 `searchPlaylists()` 会先增加平台直连候选，改变此刷新任务原先只查询服务端的结果集合；缓存/播放量状态更新顺序与异常收敛均未改变。

## ApiClient 在线导入歌单空列表补详情的 Source Registry 迁移点

`.556` 的 `MainActivity` 仍把用户原始输入交给 `ApiClient.importPlaylist(raw)`；导入入口先 `trim()`，空输入立即返回 `null`。ApiClient 仍优先请求服务端多格式 `/music/playlist?action=import&text=...`；只有响应文本为空才按原优先级从输入提取 `id`、6 位以上数字或整段输入，并请求原服务端详情路由。source 仍按输入字符串映射到固定 canonical `wy/kg/kw/tx/mg`（未识别时默认 `wy`），仅当响应 `type` 含 `netease` 时将 `realSrc` 设为 `wy`。

歌单响应解析仍先消费 `list` 或 `songs`；仅该列表为空时，才取响应 `id`，否则取输入中 6 位以上数字，并在 `id.isNotEmpty() && realSrc.isNotBlank()` 门槛通过后调用 `MusicSourceRegistry.platformSource(realSrc).playlistDetail(id)`。`realSrc` 由上述固定映射产生，故这里选择 Registry 不会规范化或替换原 source；`MusicSourceRegistry` 仍构造绑定同一 `ApiClient` 单例的 `PlatformSourceAdapter`，其方法只委派同一 `ApiClient.playlistDetail(id, source = platform)`。`ApiClient.playlistDetail` 仍先直连，未命中再走原 `playlistDetailServer(id, source)`，返回的 `List<Song>`、空结果及失败收敛不变。此导入路径没有业务缓存；不改变嵌入歌曲优先、空 ID 跳过补拉、请求顺序、补全时机、作者/封面/source/ID 组装。异常、空响应、显式失败或最终无歌曲仍使导入返回 `null`；MainActivity 仍只持久化非空歌单，再异步启动 `PlaylistImportChecker`，否则提示导入失败。


## UrlResolver 跨平台候选搜索的 Source Registry 迁移点

`UrlResolver` 的 `STAGE_CROSS_PLAT` 编排、`originOnly` 门槛、按原序取得并排除当前 source 的启用平台列表、最多 4 个目标平台、关键词和音质选择、全局超时余量判断均不变。每个平台请求仍在同一位置同步发出，原始 `plat` 精确属于 `MusicSourceRegistry.platformIds` 时仅将歌曲候选搜索改为 `platformSource(plat).searchServer(kw)`；该 server-only 方法默认 `type="song"`，`PlatformSourceAdapter.searchServer` 原样调用同一个 `ApiClient.search(keyword, source=platform, type=type)`。因此请求仍走原 `/music/search` 路由，不增加平台直连候选；同一 `List<Song>` 返回、异常仍由原 `catch (Exception)` 收敛为空列表，后续诊断、最多 8 个候选身份校验、`SongMatcher.rank(limit=2)` 与脚本尝试顺序不变。

`SmartSourceStore.platformOrder()` 会读取持久化平台码并保留非空自定义值，因此只有精确 canonical 五平台走 Registry；alias、大小写变体及其它自定义/未知 source 继续调用原 `ApiClient.search(kw, plat)`，不规范化。无搜索缓存或其它状态写入被改动；播放/下载/探测泳道与 UrlResolver 主取链/缓存策略不变。固定样本脚本探测搜索已单独迁移，见下一节；这不改变此处 `STAGE_CROSS_PLAT` 请求。本条只描述客户端源码的同路由委派，不验证服务端在线行为；本轮未运行 Gradle、JUnit、lint、APK、Actions 或设备验收。

### UrlResolver 脚本探测固定样本搜索的 Source Registry 委派

`UrlResolver.probeScriptScored` 仍先拒绝空代码和不兼容脚本，再按固定 canonical 顺序 `wy → kw → tx → kg → mg` 取样；完整模式每个平台使用两首固定歌曲样本（共 10 首样本记录），快速模式仍只取 `wy` 的一首样本。完整模式固定逐首、逐平台串行尝试引擎的七档 `master/atmos/hires/flac24bit/flac/320k/128k`，用户当前播放档不会额外插入或替代该矩阵；仅覆盖部分平台的完整类型报告标为不完整，不能用于排序。每个样本仍以歌曲名和歌手构造查询，并仅将搜索调用委派为 `MusicSourceRegistry.platformSource(plat).searchServer(kw)`；其默认 `type="song"`，经 `PlatformSourceAdapter` 原样调用同一 `ApiClient.search(keyword, source=platform, type=type)` 服务端路径，不增加平台直连候选。
搜索结果筛选次序仍是 `probeSongMatches`、规范化标题精确匹配、规范化标题包含匹配；搜索异常按每个请求档记录 `FAILED`，未匹配样本单独记为 `NO_MATCH`，不误报为不支持；脚本未返回 URL 才记为无可验证链接，运行时失败仍记为 `FAILED`。样本查询与真实匹配歌曲分别持久化并在报告中并列展示。每个样本的七档结果均按请求档记录；未提供独立实测码率时不把请求档当作实际音质。探测报告仍只在全部样本完成后构造并仅调用一次 `ScriptProbeCache.save(data)`，搜索前不读取 `ScriptProbeCache` 的报告或评分缓存。此处仅描述客户端源码可证的等价委派，不验证服务端在线行为。

## SearchController QQ 歌单搜索服务端兜底的 Source Registry 委派

`SearchController.runSearch` 仍先以 `canonicalSourceId(source)` 得到 `src`，对关键词执行 `trim()`，空关键词立即返回；搜索类型为歌单且 `src == "tx"` 时仍先调用 `searchTxPlaylistResult(q, 100)`。只有该直连结果的 `playlists` 为空才请求服务端；本次仅把原 `ApiClient.searchPlaylists(q, source = "tx", limit = 100)` 改为 `MusicSourceRegistry.platformSource("tx").searchPlaylistsServer(q, 100)`。该 server-only 适配器原样转发同一 `ApiClient.searchPlaylists(keyword, source = platform, limit = limit)`，保留关键词、canonical source `tx`、固定 limit 100、`List<Playlist>` 返回与 `/music/playlist?action=search` 同一列表解析，不增加平台直连候选或分页请求。非 TX 的首轮 `searchPlaylists(q, src)`、跨平台补充搜索与 source+ID 去重均未改。

QQ 直连结果的 `available` 与列表是否为空仍是两项状态：有效但空的直连列表也会照旧触发服务端兜底；服务端非空结果将可用状态置真，服务端仍为空则沿用原 `direct.available`，所以最终 UI 的 Empty/Unavailable 判断不变。两层解析器原有空歌单 ID 过滤及服务端请求结果的同源检查不变；此关键词入口没有歌单 ID 输入或 ID 门槛。`ApiClient.searchPlaylists` 的空关键词替换“推荐”仍保留，但本调用在更早处拒绝空关键词；其异常转空列表与外层 `runCatching`、后续 UI 状态映射保持不变。

搜索缓存键仍按 query/mode/source 构造，加载缓存后仍执行请求；只在当前 generation 且最终结果非空时写回原搜索缓存。请求竞态、QQ 不可用文案、跨平台触发条件、合并顺序、去重以及异常后的旧值/Unavailable 行为均未改。本条仅描述客户端源码可证的同路由委派，不验证服务端在线状态。

## SearchController QQ 类型搜索直连结果的 Source Registry 委派

`SearchController.runSearch` 仍先执行 `canonicalSourceId(source)`，再对关键词 `trim()` 并在空关键词处提前返回；artist/singer、album 的 `typ` 映射不变。单来源仅当既有 `src == "tx"` 门槛成立时，改由 `MusicSourceRegistry.platformSource(src).searchTypedResult(q, typ)` 取得结果；TX adapter 通过 Registry 工厂注入原 `searchTxTypedResult(keyword, type)`，且不传 page/limit，因此仍使用默认第 1 页和每页 30 条。原 `TxTypedSearchResult.songs` 与 `available` 原样返回：有效空列表仍是 available，直连失败仍是 unavailable，歌曲列表/解析/排序、POST、`search_type` 映射和异常收敛不变。

跨平台补充搜索中，原 `searchTxTyped(q, typ)` 等价于同一 helper 的 `.songs` 投影，现经 `platformSource(p).searchTypedResult(q, typ).songs`；该位置仍只消费歌曲列表、忽略 availability，平台顺序、并发、筛选、合并/去重和缓存写入不变。非 TX canonical 类型搜索继续使用原 `searchTyped` server 路径；非规范 source 的 API fallback、QQ alias 经 `canonicalSourceId` 命中 TX 的规则、缓存键与读写、UI Empty/Unavailable 映射及取消/失败边界均未改变。无歌曲 ID 参数或新增 ID 门槛。本条只描述客户端同一匿名 musicu 请求及结果契约，不验证上游在线状态。

## PlaylistFeedRepository 网易分类广场 Source Registry 委派

`PlaylistFeedRepository.fetchFeaturedResult` 的精选分类查询仍取同一批 `PlaylistCategoryCatalog` query terms，在同一批并发 `Dispatchers.IO` 请求中保留查询顺序、每项 limit `40`、外层 `runCatching` 及后续选择/缓存逻辑。仅 `platform == "wy"` 的既有 `fetchWyPlaylistCategoryDirect(term, 40)` 调用改由 `MusicSourceRegistry.platformSource(platform).playlistCategorySquare(term, 40)` 委派；适配器在 `wy` 上只调用原函数并原样传入 category、limit、默认 page `1`，不新增 HTTP 或回退。该函数仍只请求网易 `/api/playlist/list?cat=...&order=hot&limit=...&offset=...` 分类广场端点、归一化同一解析结果，并把异常/空响应收敛成空列表；因此原有批次仍将其视为成功空列表，而不改成失败状态。

此调用不可改用通用 `searchPlaylists`：该入口会同时取分类广场与关键词结果、关键词结果置前去重，并在结果不足时再次查询关键词端点，扩大候选集合并改变顺序。非 `wy` 平台仍调用原 `provider.searchPlaylists(term, platform, 40)`，其它推荐/个性化、详情、缓存及首页分类分页链路均未改。本条只记录客户端可证的同函数委派，不验证网易接口在线行为。
