# SoundWave r657：指定同类项目核查与必要吸收清单

## 范围与结论

- 分析仓库：`/root/soundwave-r657-r3`，固定基线 `fb660e769edfd0c892c48152b55c0d6f96a0b575`。下述 SW 路径均相对仓库根；源码证据取自本机文件，未运行 Android 测试、安装 SDK、构建、发版或修改生产文件。
- 外部证据：复用 `/root/lx-music-desktop`、`/root/sollin-research`、`/root/kgka-research`；其余只取 GitHub 树元数据和选定公开源文件，不克隆大仓库。外部源码固定 commit，保存在 `/tmp/r657-reference-evidence/`；来源索引 `/tmp/r657-citations.json`。
- **真正值得进入后续实现的不是堆功能，而是四件事**：平台错误事实不再在 transport 层丢失；取消不得触发兜底请求；音频缓存按可信媒体身份而非短命 URL 组织；已有减少动效策略覆盖到跑马灯和生命周期。前两项 P1，音频身份 P1 但须先确定跨源/音质/用户隔离契约，动效 P1。
- **已经有、不应重建**：WebDAV 歌单/历史同步，分平台分资源 TTL，失败保留旧数据，严格跨源匹配，播放 generation 守卫，MediaSession，Scheme，Coil/Media3 缓存预算。
- **明确拒绝**：没有 Android 消费方的桌面 HTTP Open API/SSE 控制面；将 Node/Electron 本地 API 包装误称为 Android 的“无服务端”；为了对标重建平台账号登录、常驻可视化、自动 DJ、大面积 blur、第二套图片缓存、通用插件市场。

优先级约定：P1=有明确基线缺口；P2=存在适用条件才做；不采用=目前无消费方、重复或代价不相称。以下均为研究建议，未实现。

## 一、SW 基线现状：先排除伪缺口

| 主题 | 基线真实证据 | 判断 |
|---|---|---|
| WebDAV 同步 | `sync/WebDavSync.kt:27-93` 独立凭据、Keystore 失败不覆盖；`:116-127` HTTPS 地址校验；`:152-207` 禁止携带凭据跟随重定向、响应体上限；`:221-225` 版本化 JSON 排除账号凭据；`:394-423` If-Match/If-None-Match 与 412 竞态处理 | 已有，不要再立“接入 WebDAV 同步”任务 |
| 真 local-first 与服务兜底 | `source/MusicSourceRegistry.kt:49-64,108-110` 直连能力矩阵；`source/PlatformSourceAdapter.kt:49-59,86-99` 直连失败/空/封面缺失过半回 VPS；`ScriptRuntime.kt:1889-1924,1954-1968` 仍可回 `/music/http` | 直连优先≠零服务依赖；不能直接删兜底 |
| 缓存与加载 | `HomeFeedCache.kt:16-50` IO 与过时写入丢弃；`:63-118` 分平台分资源 TTL；`PlaylistFeedRepository.kt:62-90` 成功空快照与失败不写缓存；`:38-50` source+remoteId 去重 | stale-first、三态与平台隔离已在，不再造 Repository 门面 |
| 播放失败与竞态 | `PlaybackFailureRecovery.kt:16-28` 清缓存后再校验歌曲/generation；`playback/PlaybackLoadErrorPolicy.kt:25-34` 4xx/5xx/网络分流；`PlaybackController.kt:94` PlaybackTransactionGate | 已有基础，但媒体重试与平台数据错误仍不是统一事实模型 |
| 跨源身份 | `SongMatcher.kt:41-56` 标题、完整歌手集合、版本和时长门禁；`UrlResolver.kt:515-525` 生产调用 rejectReason/rank | 不许以参考项目的模糊匹配替代此安全底线 |
| 图片/媒体缓存预算 | `perf/AppPerformance.kt:55-70` 统一 Coil、25% 堆/256MB 磁盘；`PlayerService.kt:982-1018` SimpleCache、lease 与错误策略；`CacheManager.kt:558-583` 纳入总量并按资源淘汰 | 不缺缓存引擎；需补的是身份与可观测性，不是另上缓存库 |
| 对外控制 | `PlayerService.kt:1278-1321` MediaSession 回调；`SchemeActionHolder.kt:13-18,70-101,110-117` 冷热路径事件、参数长度/平台白名单、不打断当前播放 | Android 的正常控制出口已具备 |
| 减少动效 | `MotionPolicy.kt:30-62,79-113` 系统+应用设置、实时监听、播放/可见策略；`:119-122` loopMarquee 却只看 Robolectric，不看用户减少动效 | 不是“没有减少动效”，是已有策略接线不完整 |

## 二、逐项目核查

### 1. LX Music Desktop：Open API 与 Scheme 是两个独立 URL

- 项目：`https://github.com/lyswhut/lx-music-desktop`；本地审阅 commit `9c364b482e5621a1d38b50e8610d2fb974457e6e`。
- 用户粘连链接正确拆为 `https://lyswhut.github.io/lx-music-doc/desktop/open-api` 与 `https://lyswhut.github.io/lx-music-doc/desktop/scheme-url`；实际获取分别跳转 `https://lxmusic.toside.cn/desktop/open-api`、`https://lxmusic.toside.cn/desktop/scheme-url`，不是一个网址。[23][24]
- 文档明确 Open API 启动本地 HTTP 服务，状态优先 SSE 而非轮询；本地 `src/common/defaultSetting.ts:148-150` 默认关闭、端口 23330、非 LAN。[23][31]
- Scheme 为 `lxmusic://`，支持歌曲/歌单/搜索及播放器命令；这与 SoundWave 的 `soundwave://` 参数模型不同，**不构成协议互通**。[24]
- SW 对照：MediaSession + 已加固 Scheme 见上表。**不采用 HTTP/SSE 控制面**，没有指定 Android 外部消费者；新增监听端口、鉴权、后台存活成本没有收益。可借鉴“状态事件优于高频轮询”的原则，但使用现有 Service 状态流，不起服务器。Scheme 文档/调用示例可 P2 补齐，不能宣称兼容 LX 协议。

### 2. Sollin

- 项目：`https://github.com/Ryderwe/Sollin-Music-Desktop`；本地 commit `c4ec6403f2abf45fabc5d3c669e7f177d1b6a7dd`。
- README 明确开源版本不含私有账号、激活、私有云备份和私有音乐 API；在线播放依赖用户导入 LX JS 脚本。[26]
- `src/stores/playerStore.ts:905-944` 按歌曲维护失败候选、清 URL 缓存及 sticky 换源记录，最多两次恢复；`:1080-1087` 已被新播放覆盖的请求不再触发重试链。[32]
- SW：`UrlResolver.kt:49-65,81-90` 已按 owner/generation、脚本配置、歌曲、音质分键并隔离脚本熔断；`PlaybackFailureRecovery.kt:16-28` 已有过时恢复屏障。**不再新增恢复管理器**。P1 吸收的是回归场景：A 失败清缓存期间点 B，A 不能继续换源/跳歌；失败候选不得在同次恢复中反复命中。已有能力测试化，而不是移植 TS 播放器。

### 3. SPlayer

- 项目：`https://github.com/SPlayer-Dev/SPlayer`（搜索结果可定位原 imsyy 项目迁移后的当前仓库）。审阅 commit `40d9ec606cc1e323c964ffea7bf0ab02f137e93b`。
- `src/composables/List/useListDataCache.ts:13-29,62-68,103-107,125-148` 保存版本/时间/详情/歌曲，版本不符清理，更新判定先 updateTime 再 count；缓存键 `type-id`，不是面向 SW 五平台/多资料身份的现成契约。[8]
- `src/core/resource/CacheManager.ts:46-111` 将 music/lyrics/local-data/list-data 的读写清理集中到 IPC facade。[9]
- SW：已有 HomeFeedCache v4、按 source+id 去重、分资源 TTL、独立 profile 边界。**只吸收版本迁移/缓存命中与失效的测试表达，P2**。不要照搬 `type-id` 键（跨平台会撞），也不要把“count 相等”当内容未变化的充分条件；不建第二套 CacheManager。

### 4. Alger Music Player

- 项目：`https://github.com/algerkong/AlgerMusicPlayer`；commit `b15617ce8652e12a4314be66e2c35ff1f7602a10`。
- `src/renderer/services/playbackRequestManager.ts:1-4,16-36` 自称薄请求 ID 追踪器，真正取消由 playbackController generation 执行，不能把它误称独立取消引擎。[11]
- `src/main/modules/cache.ts:206-210,423-431,594-628` 管理缓存目录/配置和文件大小核对。该实现是 Electron 文件系统模块，不是 Android SAF 的可直接替代物。[12]
- SW：已有 PlaybackTransactionGate、owner/generation、Media3 lease、Coil。**不采用额外请求管理层、Electron 本地 Node 服务、视觉效果堆叠**；P2 可借鉴缓存迁移/配置操作的单一入口，但必须以 SW 已有 CacheManager 为所有者，不另套壳。

### 5. 青听

- 可核查候选：`https://github.com/kejichangqing/QingMusic`；commit `e02ba2c46c97f2517431e4176184e28bc3142e9e`。该仓库 Git tree 实际仅 README、LICENSE、logo.png、music.json，**没有播放器实现源码**。README 明确当前为不内置音源、由用户导入来源的本地播放器。[17]
- SW：平台账号已移除（`MusicSourceRegistry.kt:64`）；现有脚本导入信任边界已经适用，无法从青听公开代码证明其缓存/性能/错误实现优于 SW。
- **不据营销或发布日志制造技术移植项**；只保留“来源可替换、内置不绑定私有音源”的产品边界，已具备，不新增代码。若用户指另一款青听，需补唯一链接。

### 6. MoeKoe Music

- 项目：`https://github.com/MoeKoeMusic/MoeKoeMusic`；commit `f80b5ad0f68e9cc816a189756c46793b22c22b59`。
- `src/utils/request.js:8-15` axios 对配置 API base 发请求，10s 超时且 withCredentials；`:20-43` 附加酷狗账号/设备会话；`:51-75` 风控应答专门交 handleRiskResponse。[13]
- SW：`source/direct/PlatformHttp.kt:23-29,46-76` 匿名直连，不接账号凭据，但普通失败与非 2xx 都压成空串。**P1 借鉴区分风控/网络/无结果而非吞成 empty 的思路**；只保留状态/错误事实，不能因此恢复已移除的平台账号、设备指纹或自动风控验证。MoeKoe 的 API base + 会话链不是 SW 无后端模板。

### 7. ka music：名称确实不唯一

- 候选 A：`https://github.com/Linsxyx/KugouMusic.NET`，README 自称 KA Music（.NET/Avalonia 桌面）；commit `d86e7a359d7d2c62c87f87ade1c0b2a8a400b6c6`。[29]
- 候选 B：`https://github.com/umr-xiaomai/kgka_Music_hl` 也自称 KA Music，Flutter；本机现成仓库 origin 为 `https://github.com/1970905901/kgka_Music_hl.git`、commit `e96750b4a6fdef83546890a938b8fa0bd10cf5e8`。**未把这两个 owner 的仓库硬认作同一历史**。[30][33]
- A 的 `BoundedDiskCachedWebImageLoader.cs:27-39,250-252` 同时约束内存条目/内存字节/磁盘字节；`PersistentLyricParseCache.cs:14-15,173-195` 歌词解析缓存约束文件数和容量。[21][22]
- B 的本机 `lib/core/api_client.dart:73-99` 只对网络/5xx 有界退避，4xx/格式失败不重试；同一 `_sendWithRetry` 同时用于 get/post，不是可无条件复制的幂等性保障。[33]
- SW：Coil/Media3 已有容量预算，无需新增图片库。**P2 只吸收双预算/清理测量方法；错误分流并入 P1 平台错误契约**。不复制账号 API、整个 .NET/Flutter 网络栈，不重试非幂等写操作。用户未给链接前保留 A/B 标识，不能说 ka music 已唯一定位。

### 8. YesPlayMusic

- 项目：`https://github.com/qier222/YesPlayMusic`；commit `5d5fa23b7429c82a9932bbc2c9f6036403c5757b`。
- README 部署步骤要求配置 NeteaseCloudMusicApi；`src/utils/request.js:5-20` 按 Electron/Web 选择 API base，`:69-85` 区分配置错误与账号 code 301。[14][28]
- SW：五平台匿名直连已有，不宜倒退为自托管 API 的薄客户端。**不采用其服务依赖/账号体系，不以桌面界面图作为性能证据**。可借鉴 API base 缺失的清楚提示，但仅适用于用户主动启用的 SW 同步/代理功能，P2。

### 9. Ceru Music

- 项目：`https://github.com/timeshiftsauce/CeruMusic`；commit `3f0fcc8406e4561749faafd3b23b9d2b908c3050`。
- `src/main/services/musicCache/MetaCache.ts:20-25,41-60,94-109,135-147` 小文本元数据放 SQLite、音频仍为文件，记录命中/访问时间并按限额淘汰。[20]
- `src/common/pluginCapabilities.ts:1-24,43-61` 明确只有 host 实际消费的能力参与跨插件路由，旧能力别名在迁移处处理。[36]
- SW：`MusicSourceRegistry.kt:49-64` 能力矩阵已在且注释承认“实现存在≠运行可用”；`PlatformSourceAdapter.kt:39-42` 当前 capabilities 没有消费者。**P1/P2 的正确吸收是按真实 consumer 收敛接口与测试，先修语义不新建万能插件 API**。SQLite/Room 元数据迁移仅 P2：需真实大库持久化耗时/容量证据后才做，不为“竞品用了 DB”发起全量存储重写。

### 10. ArchiveTune

- 项目：`https://github.com/rukamori/ArchiveTune`；commit `57732a1859cc42e7f4edba5de64fb68dcba4dd48`。
- `playback/DownloadUtil.kt:151-193,317,667-722` 通过 ResolvingDataSource/CacheDataSource 协调下载和播放缓存，读取 `dataSpec.key` 的 mediaId，完整缓存才允许离线重用；不是只判断某个缓存文件存在。[19]
- `playback/MusicService.kt:1235,3691-3728,3760-3787` MediaLibrarySession 与缓存/容器错误恢复可核查；该单文件有 8730 行，**不是值得照搬的可维护性结构**。[18]
- SW：`PlayerService.kt:364` 只 `setUri(url).setMediaId(mediaId)`；`:1010-1014` CacheKeyFactory.DEFAULT，未设置 customCacheKey。因此默认媒体缓存身份仍受 URL 变化影响，mediaId 不会自动成为 Media3 缓存键。
- **P1 采用可信稳定媒体身份设计与完整缓存校验的回归用例，不复制 YouTube 解析器/巨大 Service**。约束：键必须含 profile/真实 candidate/实际音质/内容版本；**不能直接用原曲 song.key()**，因为跨脚本同音质并不保证字节完全相同，错误复用 Range 分片会混音频。没有内容稳定性证据的来源仍保留 URL 身份；来源/音质变更及当前 lease 必须安全失效。

### 11. `Bitchord.shae player` 中的 BitChord 候选

- 原始名称不作自动修复：`Bitchord.shae player` 不是唯一有效仓库标识。公开搜索可找到 **BitChord 候选** `https://github.com/kushagrasinghx/BitChord`，commit `edc1e6f36febf71a118e649411d2fc21b70ead45`，但这不证明用户原文只有一个项目。
- `shared/.../sources/TrackMatcher.kt:30-48,149-164,231-244` 区分版本标记、歌手、时长与专辑版本信息；`app/.../webdav/WebDavClient.kt:120-143,216-231` 是目录 PROPFIND/认证播放库的代码，而非 SW 的 JSON 同步。[15][16]
- SW：SongMatcher 已是严格完整歌手集合/版本/时长，不应被更宽松共享歌手规则替换；WebDAVSync 也不缺。
- **P2 吸收匹配边界测试样例**（同名 remix/翻唱/伴奏/不同录音）；**不采用自动 DJ/交叉淡化/高开销视觉**。WebDAV 媒体库仅在用户确有 NAS 音乐消费需求时单独立项，不借同步需求扩成远程文件浏览器。

### 12. 同一原文中的 Shae player 候选（未唯一定位）

- 可核查候选是 App Store `https://apps.apple.com/us/app/music-player-vinyl-shae/id6753968019`，标题 Music, Player, Vinyl - Shae，开发者茂华崔，页面描述为黑胶/同步歌词、Apple Music/Spotify/本地音乐。[34]
- 未找到可与该应用唯一对应的公开播放器源码，不能声称其使用某缓存算法、WebDAV、Android 实现或无后端。页面推荐区出现其他应用的 NAS/WebDAV 文案，**不能算 Shae 的能力证据**。
- SW：现有减少动效/封面播放器足以承载静态黑胶外观。**不采用常转黑胶等纯装饰，也无可移植源码项**；如要 UI 对标，仅 P2 视觉参考，需用户确认确是此 Shae，不加入后端/技术清单。

### 13. Any Listen

- 项目：`https://github.com/any-listen/any-listen`；commit `9b9eafd7f8ccd4075abbdb6e1679f9c8b860b49f`。
- README 同时列出“WebDAV 上播放歌曲”和桌面/Web 服务两个版本；**WebDAV 音乐库≠WebDAV 用户数据同步**。[27]
- `packages/shared/app/modules/sync/webdav/sync.ts:12-60,63-80` 有连接检查、运行状态、锁、合并模式和取消；`.../extensions/webdav/resourceAction.ts:7-29` 对远程曲目分别取媒体/歌词/封面；`listProviderAction.ts` 处理远程目录列表。[1][2][3]
- SW：JSON 同步已具备 ETag CAS、迁移兼容、凭据隔离。**不重新实现 WebDAV 同步**；现有同步状态/冲突/认证失败提示可对照回归。P2 远程音乐库是独立能力，须明确目录扫描、Range、证书、host 绑定凭据、容量/分页、取消及离线行为的需求后才做。不能因 Any Listen 有 Web 服务就让 SW 重新依赖一个常驻私有服务。

### 14. Folia Major

- 项目：`https://github.com/chthollyphile/folia-major`；本轮树/API 固定 commit `ced47852c64c2709faebe0190d430c3ab75ca2a4`（页面入口会继续更新，结论以固定源码为准）。
- `src/services/onlineMusic/failure/onlineFailureCase.ts:6-17,34-50,69-97,118-143` 分离平台事实/统一错误/用户提示，429 或 retryAfter→retry-later，错误不一律自动重试。[4]
- `onlineRequestCooldown.ts:9-17,23-50` 区分主动播放和预取：明确 Retry-After 可同时限流，否则短暂停预取、不无端挡用户主动点歌，且冷却命中不续期。[5]
- `src/hooks/useReducedMotionFor.ts:7-17` 将减少动效读到统一 surface 策略；`resourceCache.ts:16-32,77-85` 版本键/旧缓存迁移路径集中化。[6][7]
- SW：PlatformHttp 压平状态、PlaybackLoadRetry 不消费 Retry-After；已有脚本歌曲级 cooldown 不能误称为平台级主动/预取策略。**P1 采用“保留事实→策略→提示”的边界及预取退让；P1 补减少动效一致消费；不照抄登录/会员整个错误体系，不拷贝绚丽动效/模型/桌面窗口**。只声明匿名 SW 能证实的事实，不能由 403 猜 VIP/登录原因。

### 15. Greasy Fork 438148

- 页面 `https://greasyfork.org/zh-CN/scripts/438148`，已定位为 lyswhut 的“LX Music 辅助脚本”，不是音源解析服务。[25]
- 实际 userscript 已获取；构造 `dom_a.href = lxmusic://${type}/${action}?data=${encodeData(data)}`，在官方歌曲/歌单页面发起 LX Scheme 调用。[35]
- SW：soundwave Scheme 与 ACTION_SEND 歌单链接导入已有，但参数格式不同。**P2 只有明确用户要“浏览器点一下交给 SW”时才写小桥接/文档**，以 SW 的参数白名单与导入确认复用现有通路。不原样导入这个 userscript 充当音源，不实现手机上的 HTTP 控制，不把 `lxmusic://` silently 注册成自己的 scheme 争抢 LX。

## 三、建议的实施顺序与验收（均未执行）

| 顺序 | 需要吸收的最小切片 | 为什么确有必要 | 边界与验收 |
|---|---|---|---|
| P1-A | 平台 transport 返回内部结构化失败事实，保持现有 String/List API 的兼容适配 | `PlatformHttp.kt:76,98` 丢掉非 2xx/超时等区别；Folia/MoeKoe 的失败分流有源码可查 | 至少 success-empty / network / timeout / HTTP status / parse error；429 读 Retry-After（秒和日期）；UI 不泄漏 URL/正文/凭据；不大改取链泳道、不把匿名失败猜成会员问题 |
| P1-B | 统一取消传播，禁止取消当失败进 VPS 回落 | `PlatformSourceAdapter.kt:51,90` 用 runCatching 吞取消，而同文件 `:113-125` 的 typed 分支已明确重抛；`PlaylistFeedRepository.kt:169,195-220` 也有 runCatching 边界 | 用假 provider 验证取消后 server fallback/缓存写/错误 Toast 均零调用；先纯策略测试，再最小接线。不将此次源码发现冒充已证实真机 bug |
| P1-C | 复用现有 MotionPolicy，补跑马灯/背景循环可见性生命周期 | `MotionPolicy.kt:121-122` 只冻测试机；`ui/common/GlowCardBackground.kt:45-51` 仅 reduceMotion，无前后台参数；唯一调用 `MePage.kt:575` | 系统 scale=0 或 app 减少动效时无 marquee；后台/离屏不创建装饰无限 transition；前台交互反馈保留；采帧/耗电验收另交真机，不能只数动画调用 |
| P1-D（先设计） | 稳定且可信的音频缓存身份与完整性验证 | ArchiveTune 的 mediaId-key/完整缓存检查，SW 默认 URL key 易因签名 URL 更新产生重复缓存 | 先定义 owner/candidate/quality/content 证据；短命 URL 更新不重复，实际音质/文件变更不复用，跨资料不泄漏，lease 中不淘汰；无内容稳定性则不跨 URL 复用。不是简单加 setCustomCacheKey(song.key()) |
| P1-E（测试优先） | 播放恢复和换源边界回归矩阵 | Sollin/Alger 提供同类 generation/失败候选场景；SW 已有基础而非缺架构 | A→B 抢占、A 迟到失败、清缓存后 generation 变化、相同错误候选不重复循环；沿用 PlaybackTransactionGate 与 Recovery，不加第二状态所有者 |
| P2 | 缓存版本/匹配边界/预算命中可观测 | SPlayer/Ceru/KA/BitChord 有对应具体实现；SW 大半已覆盖 | 先查已有测试再加缺项，不无条件上 Room；聚合计数不记录敏感临时 URL；匹配只增严格测试不放宽安全门禁 |
| P2（用户确认） | 浏览器/Scheme 桥接、WebDAV 远程音乐库 | 有真实外部用户/远程音乐消费方才有收益 | 前者先文档+小入口；后者与 JSON 同步分模块、凭据域/扫描预算/Range/取消独立验收 |

**服务依赖收缩原则**：先用源/能力矩阵把“本地可用 / 直连可用 / 可选兜底 / 用户主动启用同步”分清；再按真实客户端调用下线路由。现有 `PlatformSourceAdapter` 空结果回落和 `ScriptRuntime` 代理链在未经断网/匿名平台/脚本 A/B 验证前不能删。桌面内嵌 Node API 与 Android 直连不是同一种 local-first；本次参考项目不构成移除服务回退的证据。

## 四、取证限制与文件边界

- 已对每个指定名称做公开检索或源码核查；ka music 保留 A/B 候选，`Bitchord.shae player` 分别列 BitChord/Shae 候选，未伪造唯一性。青听仅发布/配置仓库，无可审播放器实现；Shae 仅商店页面，无可核查公开实现。
- Shae 的 web_extract 失败，浏览器因 Chromium 缺失无法启动；随后标准库 HTTPS 获取 App Store 真实页面成功。未为此安装浏览器或 SDK。
- 搜索/README 是功能范围证据，不是性能实测。所有性能提升均为待验收建议，没有声称运行竞品或测过 SW 真机。
- 工作树由其他执行者并行编辑：起始 `git status --short` 为空；末次观察出现 `DetailTopChrome.kt`、`ui/motion/SettingsPush.kt`、`ui/route/AppRootMainTabRouteRenderer.kt` 的修改。本研究未触碰这些文件，也未把它们作为研究缺口证据。生产 HEAD 仍为固定基线。
- 本报告只引用理念/边界，不直接移植代码；将来复制实现前必须逐仓核验 LICENSE/附加条款与 SW 分发兼容性，尤其 Any Listen/Folia/SPlayer 等，不能把“公开可读”当任意复制许可。

## Sources

[1] https://github.com/any-listen/any-listen/blob/9b9eafd7f8ccd4075abbdb6e1679f9c8b860b49f/packages/shared/app/modules/sync/webdav/sync.ts
[2] https://github.com/any-listen/any-listen/blob/9b9eafd7f8ccd4075abbdb6e1679f9c8b860b49f/packages/shared/app/modules/worker/extensionService/internalExtension/extensions/webdav/resourceAction.ts
[3] https://github.com/any-listen/any-listen/blob/9b9eafd7f8ccd4075abbdb6e1679f9c8b860b49f/packages/shared/app/modules/worker/extensionService/internalExtension/extensions/webdav/listProviderAction.ts
[4] https://github.com/chthollyphile/folia-major/blob/ced47852c64c2709faebe0190d430c3ab75ca2a4/src/services/onlineMusic/failure/onlineFailureCase.ts
[5] https://github.com/chthollyphile/folia-major/blob/ced47852c64c2709faebe0190d430c3ab75ca2a4/src/services/onlineMusic/failure/onlineRequestCooldown.ts
[6] https://github.com/chthollyphile/folia-major/blob/ced47852c64c2709faebe0190d430c3ab75ca2a4/src/hooks/useReducedMotionFor.ts
[7] https://github.com/chthollyphile/folia-major/blob/ced47852c64c2709faebe0190d430c3ab75ca2a4/src/services/onlineMusic/resourceCache.ts
[8] https://github.com/SPlayer-Dev/SPlayer/blob/40d9ec606cc1e323c964ffea7bf0ab02f137e93b/src/composables/List/useListDataCache.ts
[9] https://github.com/SPlayer-Dev/SPlayer/blob/40d9ec606cc1e323c964ffea7bf0ab02f137e93b/src/core/resource/CacheManager.ts
[11] https://github.com/algerkong/AlgerMusicPlayer/blob/b15617ce8652e12a4314be66e2c35ff1f7602a10/src/renderer/services/playbackRequestManager.ts
[12] https://github.com/algerkong/AlgerMusicPlayer/blob/b15617ce8652e12a4314be66e2c35ff1f7602a10/src/main/modules/cache.ts
[13] https://github.com/MoeKoeMusic/MoeKoeMusic/blob/f80b5ad0f68e9cc816a189756c46793b22c22b59/src/utils/request.js
[14] https://github.com/qier222/YesPlayMusic/blob/5d5fa23b7429c82a9932bbc2c9f6036403c5757b/src/utils/request.js
[15] https://github.com/kushagrasinghx/BitChord/blob/edc1e6f36febf71a118e649411d2fc21b70ead45/app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt
[16] https://github.com/kushagrasinghx/BitChord/blob/edc1e6f36febf71a118e649411d2fc21b70ead45/shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt
[17] https://github.com/kejichangqing/QingMusic/blob/e02ba2c46c97f2517431e4176184e28bc3142e9e/README.md
[18] https://github.com/rukamori/ArchiveTune/blob/57732a1859cc42e7f4edba5de64fb68dcba4dd48/app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt
[19] https://github.com/rukamori/ArchiveTune/blob/57732a1859cc42e7f4edba5de64fb68dcba4dd48/app/src/main/kotlin/moe/rukamori/archivetune/playback/DownloadUtil.kt
[20] https://github.com/timeshiftsauce/CeruMusic/blob/3f0fcc8406e4561749faafd3b23b9d2b908c3050/src/main/services/musicCache/MetaCache.ts
[21] https://github.com/Linsxyx/KugouMusic.NET/blob/d86e7a359d7d2c62c87f87ade1c0b2a8a400b6c6/src/Apps/KugouAvaloniaPlayer/Services/BoundedDiskCachedWebImageLoader.cs
[22] https://github.com/Linsxyx/KugouMusic.NET/blob/d86e7a359d7d2c62c87f87ade1c0b2a8a400b6c6/src/Apps/KugouAvaloniaPlayer/Services/PersistentLyricParseCache.cs
[23] https://lxmusic.toside.cn/desktop/open-api
[24] https://lxmusic.toside.cn/desktop/scheme-url
[25] https://greasyfork.org/zh-CN/scripts/438148
[26] https://github.com/Ryderwe/Sollin-Music-Desktop
[27] https://github.com/any-listen/any-listen
[28] https://github.com/qier222/YesPlayMusic
[29] https://github.com/Linsxyx/KugouMusic.NET
[30] https://github.com/umr-xiaomai/kgka_Music_hl
[31] https://github.com/lyswhut/lx-music-desktop/blob/9c364b482e5621a1d38b50e8610d2fb974457e6e/src/common/defaultSetting.ts
[32] https://github.com/Ryderwe/Sollin-Music-Desktop/blob/c4ec6403f2abf45fabc5d3c669e7f177d1b6a7dd/src/stores/playerStore.ts
[33] https://github.com/1970905901/kgka_Music_hl/blob/e96750b4a6fdef83546890a938b8fa0bd10cf5e8/lib/core/api_client.dart
[34] https://apps.apple.com/us/app/music-player-vinyl-shae/id6753968019
[35] https://update.greasyfork.org/scripts/438148/LX%20Music%20%E8%BE%85%E5%8A%A9%E8%84%9A%E6%9C%AC.user.js
[36] https://github.com/timeshiftsauce/CeruMusic/blob/3f0fcc8406e4561749faafd3b23b9d2b908c3050/src/common/pluginCapabilities.ts
