# source 层设计（MusicSource）

## 当前职责与类型边界

`MusicSource` 是业务侧音源接口，向上层返回 `Song`、`Playlist`、`RankBoard`、`DiscoverCard` 等领域对象，不暴露上游 JSON。歌单分类树用 source 域的 `CatGroups` 表示，不再嵌套在 `ApiClient` 中。

`SourceCatalogApi` 是适配器使用的 typed catalog port；它排除账号凭据和播放取链，并在需要区分状态的请求上保留 `FeedListOutcome` / `PlaylistSearchOutcome`（成功空列表与失败不同）。生产 `ApiClientCatalogGateway` 将它接到现有 `ApiClient` 实现；测试可直接注入 fake port，不构造真实网络 client。这个 port 是渐进依赖反转，不代表 `ApiClient` 已完成拆分：现有 facade 仍包含自有服务路由、若干平台直连优先路径和 legacy fallback。

`MusicSourceRegistry` 是生产工厂：`platformSource(id)` 创建 `PlatformSourceAdapter`，并注入 `ApiClientCatalogGateway` 及五个平台的 direct search / playlist search 函数。`ScriptSourceAdapter` 只承接脚本 source 身份，播放取链仍由 `UrlResolver` 编排。

## 真实入口与兼容语义

- 搜索页 `SearchController` 经 `MusicSourceRegistry.platformSource()` 调用平台 adapter。歌曲搜索先尝试平台 direct callback，非取消失败/空结果才通过 typed port 回退自有 `/music/search`；QQ typed search 保留 `available`，可区分有效空结果与不可用。
- 首页 `HomeFeedController` 通过同一注册表取得平台发现卡片、热门歌曲、榜单和热词；详情页、榜单卡片及歌单/专辑页也有真实 adapter 调用。平台解析器只在数据层映射 JSON 到领域对象。
- 平台热门歌曲由 `PlatformSourceAdapter` 调用注册表注入的 direct callback；失败/空结果只走 `SourceCatalogApi.hotServer`，避免再次进入 `ApiClient.hot` 的 direct-first facade。非平台兼容入口仍可使用 `ApiClient.hot`，原请求与候选顺序不变。
- 平台热搜词也由 adapter 承接 direct callback；typed 路径只走 `hotKeywordsServerOutcome` 回退，保留失败与有效空列表的区别。`hotKeywords()` 仍是 legacy List 兼容入口；非平台调用继续使用 ApiClient 原 facade。
- 仍有旧入口直接调用 `ApiClient`，包括 `MainActivity` 的仅歌手名查询；部分聚合首页方法也仍由 `ApiClient` 自己组织 direct-first/fallback。迁移必须逐项比较候选集、来源身份与取消语义，不能仅为统一入口而改变结果。
- 播放地址继续走 `UrlResolver` 的 play/download/probe 泳道；不把平台或脚本取链移入 `MusicSource`，避免绕过限流、互斥和取消合同。

## 错误、鉴权与分页不等价

`source/direct/PlatformTransportOutcome` 保留 HTTP 状态、timeout、network、parse、policy 等类别；`FeedListOutcome` 和 `PlaylistSearchOutcome` 保留成功空列表与失败，但旧 `List` / nullable 兼容接口以及部分 `ApiClient` 解析器仍会把错误归为空结果。当前 port 不试图伪造统一的第三方登录状态：`ApiClient.auth` 处理的是 SoundWave 自有账号及 UserBundle；平台 adapter 只面向匿名公开目录，不包含平台账号、Cookie 或 VIP 权限。

统一接口的分页参数不代表行为相同：平台 direct playlist search 使用各平台页码/offset 规则；VPS 搜索端不接受 offset，adapter 只拉最多 200 条前缀再本地切片。后续错误/能力模型要显式说明来源和权限，不能把平台差异折叠成同一个“成功/失败”。

## 本地验收与剩余工作

`PlatformSourceAdapterFallbackTest` 使用 fake direct callbacks 与 fake `SourceCatalogApi`，覆盖 direct 失败回退、用户取消不触发 fallback、VPS 后续页的有界前缀切片，以及 category valid-empty 与双失败的区别；不访问真实平台、服务器或媒体。现有 direct parser、transport、playlist catalog 与模型测试继续提供 fixture/mock 覆盖。

SW-04-06 仍进行中：逐步将 `ApiClient` 的平台直连、SoundWave 自有服务传输与 legacy 解析职责分开；把残留平台旁路迁到注册表前先锁定原结果语义；为真实 JSON→领域模型补充 fixtures，并谨慎扩展 HTTP/解析错误分类。不得将账号认证混进匿名平台 port，不更改 UI/UX。

### r717 本地进展（父提交 r716 `9cce773c6663027843823eced2026978cb06e2d8`）

平台首页发现卡片现在由 `PlatformSourceAdapter` 持有直连回调，并只经 `discoverServerOutcome` 回退；typed 路径保留失败与成功空列表的区别，legacy List 仍按兼容约定将失败映射为空。离线 fake/JVM `PlatformSourceAdapterFallbackTest` 17/17、平台注册路由静态测试 6/6、arch-guard 与 suspend-cancellation-guard 通过；未调用真实平台、服务器或媒体，未构建 APK。其他 ApiClient 混合路径、旧 List 错误折叠与设备验收仍待处理。

## resolve 边界

### r715 本地进展（父提交 r714 `3c3c3f46ef3b3238c4374105cd395e97c8e06e05`）

本切片将五平台热门歌曲的 direct callback 放入注册表注入的 `PlatformSourceAdapter`，失败/空结果复用原 server-only 热门接口；`ApiClient.hot` 的兼容入口及非平台路由未改。离线 fake/JVM 测试为 `PlatformSourceAdapterFallbackTest` 9/9、`FeedDirectFetchTest` 10/10；arch-guard、suspend-cancellation-guard 与新增路由静态测试通过。`main-playlist-import-fallback-registry-guard`、`singer-detail-source-registry-guard`、`step8-direct-source-guard` 在 r714 基线也失败，作为既有守卫问题保留，未放宽断言。未构建 APK、未调用真实平台/服务器/媒体，SW-04-06 仍进行中；ApiClient 其他混合路径、旧 List 错误折叠及设备验收仍待处理。

### r716 本地进展（父提交 r715 `c26f01c86bb12551ad2a5f9d6bb4472a38b10b6a`）

平台热搜词直连现在从 `ApiClient.hotKeywordsOutcome` 拆到平台注册表注入和 `PlatformSourceAdapter`；失败/空直连结果走原 server-only typed fallback，`FeedListOutcome.Failure` 不在 typed 路径中折叠。非平台 ApiClient facade、平台请求参数及 UI 调用点保留；回归只用 fake direct/port，不触达网络。

目标形态为 `Song → ResolveRequest → MusicSourceResolver → MusicSource → ResolveResult`，但跨源编排权仍属于 `UrlResolver`。播放、下载、脚本探测分别受 `playResolveLane`、`downloadResolveLane`、`probeLane` 互斥；source adapter 不得直接调用泳道原语或自行发起取链，以免破坏限流、取消与探测隔离。
