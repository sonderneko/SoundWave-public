# Versioned provenance catalog — source implementation status

> Sections describing step 1a below are historical design notes. The production integration section
> supersedes statements that no runtime scanner/codec/gate hookup exists. Source verification only;
> full plan steps 1-8 are **not complete** and no Kotlin/JVM execution has been authorized.

基线：`4eb9ffaa48cec1fac4b077abc69ff57b1fef1c04`，`feat/neumorphic-ui-refresh`。

## 边界

`migration/ProvenanceCatalog.kt` 只描述和判定证据，不读写 SharedPreferences、文件、WorkManager、SAF 或网络；不调用现有账号/WebDAV gate；不激活资料，不关联 legacy 数据，不删除/迁移任何原始数据。step 0 的恢复失效标记、启动快照与现有 gate 保持原样。不存在扫描器、持久化格式或启动接线；下表是后续逐域实现扫描器的工作清单，不能以本批结构 guard 通过声称已扫描设备。

catalog schema VERSION=1，每域 scannerVersion=1，必须两者精确匹配。枚举的全部数据域是固定的必需集合，不能传入“所需域子集”来缩小全局判定。每条 inventory 保存域、版本、扫描时间（调用方注入）、来源、恢复/变更失效 epoch、位置清单与存在性、扫描/导入完成、读/写成功和失效原因。map/set 做 defensive copy 并包装为 unmodifiable；record 属性只读，未使用可能保留外部可变集合的 data-class copy。

位置标识是 **扫描选择器**，不是访问 API：精确键表示 contains(key)，`namespace/*` 表示枚举其全部键（包含未知、空值和孤立键），文件 `/**` 表示整个根及其后代，动态命名空间 `*_*/` 必须完整枚举。inventory.entries 是每个选择器的扫描结果清单；每个选择器必须恰好一项，结果为 ABSENT/PRESENT/UNREADABLE，不是反序列化后的业务列表。文件/键内容、密码、音频 URL 不进入 catalog。后续扫描器若需要详细文件/键报告，可额外设计脱敏明细，不在本批扩张 API。

只要位置存在（即使空字符串、空 JSON 数组、空文件），必须报告 PRESENT。文件目录/URI 列举失败、权限不足、损坏、部分枚举都不能报告 ABSENT；目录本身存在也可保守报告 PRESENT。重复覆盖根有意保守，不做拥有者推断；只有全部域完整、新鲜、已确认读写且位置全部不存在，才能返回全部域 VERIFIED_ABSENT。该结论仅在清单范围内有效，不代表整台手机/外部用户文档“无历史”，更不是同步许可或用户同意。

## 基于当前源码核实的数据域

路径相对 `app/src/main/java/xyz/cncnlp/soundwave/`。所有域必需，尚无生产扫描器。

| 数据域 | V1 扫描位置/重点 | 当前源码证据 | 后续边界 |
|---|---|---|---|
| CORE_BUNDLE | soundwave_userdata/user_data_v1；收藏/歌单、内嵌历史、删除标记 | LocalStore.kt:14-17、UserBundle | 键存在而解析结果为空仍 PRESENT |
| PLAY_HISTORY | soundwave_userdata/play_history_v1 独立历史 | LocalStore.kt:16；CloudSyncController.kt 历史提交 | 不能只看 core bundle |
| DOWNLOAD_RECORDS | soundwave_userdata/downloads_v1 | LocalStore.kt:17 | 只有下载记录也不是全局空 |
| LIBRARY_ORDER | soundwave/lib_order_v1 | MainActivity.kt:624、631、744 | 只有排序键也不是全局空 |
| LISTEN_STATS | listen_stats_v1 全键；total_sec/today/week/song_map_v1/artist_map_v1 | ListenStats.kt:14-21 | 不把零秒/空 map 当不存在 |
| DISLIKES | soundwave/dislike_keys_v1 | DislikeStore.kt:7-11 | 空 set 但键存在仍 PRESENT |
| SETTINGS_AND_SESSION | soundwave、soundwave_sort、soundwave_prefs、soundwave_webdav 全键；包括外观/播放/下载、user/logged_in/pass、crash/probe、download_tree_uri | MainActivity.kt:246、LibraryPage.kt:429、MotionPolicy.kt:26、sync/WebDavSync.kt:39-42、BackupStore.kt:13-16 | 凭据仅看存在性；不能解密/外发；设置不是账号归属证据 |
| SMART_SOURCE | smart_source_v1 与 smart_source_profile_* 全键 | SmartSourceStore.kt:15-28、61 | 绑定、顺序、记忆直链与偏好，不能跨 profile 默认认领 |
| SCRIPTS | soundwave_scripts 全键；script_meta、script_code_*（含孤立 code 键） | ScriptStorage.kt:22-38、MainActivity.kt:247 | 不执行脚本、不探测媒体链接 |
| SCRIPT_PROBES | script_probe_cache_v3 / v4 全键，quick_records/full_records | ScriptProbeCache.kt:24-27 | 旧缓存也纳入；定时巡检偏好在 soundwave 域 |
| PLAYBACK | soundwave_playback_v1 与 soundwave_playback_profile_* 全键，state/explicit_resume | PlaybackStore.kt:10-14、168 | 仅盘点 checkpoint；不得恢复播放/修改队列 |
| DOWNLOAD_RESOURCES | soundwave_download_scheduler 全键、WorkManager download-work、external-files/Music/SoundWave、files/music/SoundWave、SAF download-targets | DownloadScheduler.kt:41-44、DownloadHelper.kt:112-114、179、187、277 | 记录归属≠文件/URI权限；盘点 .part/歌词/封面、历史目标与持久授权，不取消旧任务；SAF失权/不可确认来源则 UNKNOWN |
| AVATARS | avatar_map、files/user_avatar*.jpg、files/avatars | AvatarStore.kt:12-21、CacheManager.kt:128 | 用户名/头像文件不自动关联账号 |
| BROWSE_AND_DIAGNOSTICS | sw_feed_cache_v4、home_page_view_state_v1、home_feed_refresh_v1、list_scroll_v1、pl_import_check_v1、playback_diag_v1、personal_recommendation_state_v1 全键 | HomeFeedCache.kt:55-59、HomePageStateStore.kt:9、HomeFeedController.kt:20、ListScrollStore.kt:7、PlaylistImportChecker.kt:15、PlaybackDiagnostics.kt:19、PersonalRecommendations.kt:20 | 浏览、推荐状态、诊断/检查缓存不能视为“无人使用过” |
| LOCAL_IMPORTS_AND_SNAPSHOTS | soundwave_legacy_local、soundwave_local_backup_profile_index_v1、soundwave_userdata_profile_*、soundwave_userdata 全键；files/staged/snapshot-vault | migration/LegacyUnassignedSnapshot.kt:13-19、LocalStore.kt:18-26、ProfileId.kt:13、sync/ServerSnapshotVault.kt:33 | 包括旧快照/step0失效标记、inactive导入、未知/孤立profile命名空间；禁止激活或首个profile兜底 |
| LOCAL_FILES | files、cache、external-files 全根兜底 | CacheManager.kt:123-128、212-220、DownloadHelper.kt:112-114 | 覆盖 covers/lyrics/audio_cache/avatars/exo_media_cache、解密缓存、backup/staged/未知文件；只读，不清缓存；纯技术文件也可保守阻止“全空” |
| UNCLASSIFIED_PREFERENCES | 未归入上述域的 SharedPreferences 命名空间全部键 | migration/LegacyPlatformAuthCleanup.kt:13-17（sw_plat_acct_v1、sw_migrations_v1）及未知旧版命名空间 | 必须枚举安装内 preference 文件；不能靠硬编码几个 store 自证覆盖完整 |

SharedPreferences 有交叠（例如 soundwave/* 与 lib_order_v1），根选择器有交叠（files/** 与头像/暂存）。这是保守盘点不是迁移/上传范围；后续同步范围必须独立显式选择。尚未设计 catalog 自身存储命名空间，未来其技术元数据和纯技术文件如何排除需要版本化规则/测试，不能为求“空”现在悄悄排除；所有扫描器接入和原子持久化另批实施。

## 判定与失效规则

- 缺 catalog/损坏读取由后续读取层映射为 readSucceeded=false；任意未知 schema、catalog 读/写失败、catalog epoch 不匹配或负 epoch：UNKNOWN。
- 缺少必需域：NOT_SCANNED；域扫描时间未记录：NOT_SCANNED，不能当空。
- 域错配、scanner 版本不匹配、域 epoch 陈旧、负时间、失效原因（即使空字符串）、读/写失败、部分扫描/导入、未知来源、缺/多位置、UNREADABLE：UNKNOWN。
- 完整当前 LOCAL_IMPORT：IMPORTED_LOCAL_NOT_ACTIVE，即使导入内容为空；不能提升为全局不存在，也不意味着已获 consent。
- 完整当前 LEGACY_SCAN：任一选择器 PRESENT → PRESENT_UNASSIGNED；全部 ABSENT → VERIFIED_ABSENT。
- 全局判定必须对枚举全部域成立；空 map、缺域、只有 downloads_v1 或 lib_order_v1、部分导入都不成立。
- 当前 epoch 必须来自独立可信失效源，不能从 catalog 自身“读出后原样传回”。恢复/导入/数据写入必须先持久失效（或与写入原子失效）；扫描与提交竞态必须核对 epoch。失败不提升、保留原数据。现有 step0 bool 标记未接成 epoch，本批不改变它；不能宣称已有重启级 catalog 失效闭环。

## Step 1 production integration (source-only)

`migration/ProvenanceScanner.kt` now scans the fixed manifest through a narrow presence adapter; it never parses data values. `RuntimeProvenanceInventory` commits a versioned metadata catalog and verifies exact readback plus an independently stored invalidation epoch. The account sync gate remains fail-closed unless the two-key legacy marker and the complete current runtime inventory both prove absence. Generic backup restore atomically increments the epoch with its legacy invalidation marker and bundle. Existing data is never cleared or copied into profiles.

`ProfileContext` is an immutable owner/generation token used by readonly inactive-profile preview; it cannot activate, claim, sync, or fall back to legacy. `CloudSyncController` accepts an injectable gate provider so tests can exercise transport behavior without weakening the production runtime gate. Script egress now directly accepts public IPv4/IPv6 literals, preserves HTTP, and rejects private/loopback/reserved/mapped/link-local destinations.

The remaining profile data domains still have legacy/global call sites; no claim of complete profile activation or full domain migration is made. Playback and WorkManager ownership work remains queued for the next dependency phase.

## 验证与下一批交接

JUnit：`app/src/test/java/xyz/cncnlp/soundwave/migration/ProvenanceCatalogTest.kt`。
结构 guard：`bash scripts/provenance-catalog-guard.sh`，只依赖 bash/python3；不依赖仓库绝对路径。它检查合同/隔离/测试结构，不执行 Kotlin，**结构 RED/GREEN 不等于 JVM 行为 RED/GREEN**。

本批按授权只跑结构 guard、90 个现有 guard、`git diff --check`。没有 JVM、lint 或编译结果；完整 JVM/lint 由用户 cue 后在获准环境进行：

```bash
./gradlew :app:testDebugUnitTest --tests 'xyz.cncnlp.soundwave.migration.ProvenanceCatalogTest'
./gradlew :app:testDebugUnitTest :app:lintDebug
```

下一批先 review scanner manifest/状态矩阵和完整 JVM/lint，再逐域定义只读扫描器与可信原子 catalog/epoch 存储。未来门禁接入单独评审；本批不改账号/WebDAV、不开放资料激活/切换、不发真实请求，不构建/提交/推送/触发 CI/Release。step 1 仅完成纯逻辑基础，整项计划远未完成。
