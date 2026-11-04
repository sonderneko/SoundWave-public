# SoundWave v2 Local-first 暂停交接计划表

唯一分支：feat/neumorphic-ui-refresh。
前一已推送提交：517adabd952724cc1a2a5685719be3b75d7a0a47（纯来源清单模型）。
本次：用户要求暂停开发，将当前源码作为WIP交接快照提交推送。不是测试通过、可发布或完整实现声明。提交消息含[skip ci]，不构建APK、不触发Release、不停VPS、不改master、不强推。

## 已有成果与边界

| 阶段 | 当前源码 | 完成口径与剩余事项 |
|---|---|---|
| 0 | cue的4eb9ffa恢复持久失效门禁保留 | cue已推；本轮不重做。 |
| 1 | 17域模型/扫描/codec/读回/epoch、UI缓存与IO扫描门禁 | 纯模型517adabd已推；生产接线本次WIP收存。扫描覆盖/文件偏好mutation仍需完整测试。 |
| 2–3 | 不可变ProfileContext/catalog/session；core/history/download/stats/dislike/settings/scripts/sort/scroll/cache owner接线 | 资料激活/切换仍关闭。三方隔离/重启/未知身份零写入尚未执行。 |
| 4 | resolve ticket、历史事件、Service/checkpoint/MediaSession/通知捕获owner | 旧回调/自然结束/恢复/快速切歌未完成执行验收。 |
| 5 | 下载/probe持久owner/epoch与旧legacy任务兼容，任务ID抗碰撞；共享monitor消除已定位相反锁顺序 | 源码复核支持原死锁修复；latch测试仅写出，未执行。普通下载取消/clear与probe取消不接全局epoch撤销。 |
| 6 | profile独立remote binding/document/revision/credentials/ETag/conflict/history/revoke | readiness关闭，inactive opt-in/test/sync在读凭据前拒绝；不是可用profile同步。无真实WebDAV请求。 |
| 7 | 域白名单copy-only inactive迁移、操作receipt、quarantine、回读、可恢复rollback journal和UI | 源码复核支持原误删/不可恢复问题修正；原legacy/files/remote不删，失败/重启/磁盘故障测试未执行。 |
| 8 | 分类/推荐/专辑/歌手/实体搜索/公共分享导入补充直连；KW node→sourceid；取消透传；畸形非空推荐fail-unavailable；公网HTTP/IP脚本资格 | HTTPS端点可用性、KW顺序lookup延迟和平台结果未实测；KG分页为有界前缀非完整性证明。账号/API/proxy未删。 |
| 9 | 未做最终构建/发布/真机 | 先cue完整JVM/lint，失败修复。随后授权构建同提交APK，再设备/脚本/WebDAV验收。 |

## 审查修复进度

原F1–F7已有源码修复：持久任务跨进程身份、锁内IO身份读、auxiliary旧journal保护、回滚恢复、day_sec_*统计迁移、歌词稳定disk key、codec测试schema动态变更。
后续N1共享download monitor、N2rotation failfast/recursive rejection、N3probe捕获epoch permits已写出。

**仍保留未闭环风险：**独立复核发现rotation在已准入外层事务中宣布时，会拒绝其嵌套偏好commit；ListenStats吞异常可能导致部分统计/丢playcount，而ContextData返回成功。生产rotation目前未接线，风险潜在但不能忽略。针对性修复子任务已按用户要求停止；本快照不将该项计为修好。任何停止前草稿不得代替新的独立复核。

## 本次实际验证

在独立快照目录/root/soundwave-local-first-submit重跑：96/96结构守卫通过（比主工作树95项多此前提交的provenance-model-guard）。这只证明结构断言，不能排除上述统计事务风险。
编译、JVM/Robolectric、lint、媒体/账号/WebDAV网络、真机和APK均未执行。源码测试仅写出。无完整行为RED/GREEN。
完整证据：/root/build-logs/local-first-paused-snapshot-guards.json。

## 后续建议（暂停后由接手者执行）

1. 锁定本次提交SHA，确认工作区/远端，勿从旧4eb9ffa冒充最新。
2. 修复已准入外层事务下同线程nested permit，queued rotation只拒绝新事务；统计失败不得假报success。加forced-latch测试并审真实调用链。
3. cue执行所有新增定向测试、完整:app:testDebugUnitTest与:app:lintDebug；不得只测22模型方法。回修编译/测试错误。
4. 检查raw偏好/文件写入路径、下载/恢复、migration receipts、unknown零凭据/零请求和无ETag兼容。
5. 真实设备官方直连/同批外部LX脚本/播放下载重启/备份/WebDAV验收前，不启用资料切换/remote readiness、不删服务端依赖。
6. 最终构建与服务器操作另行授权。暂停期间不继续自动开发。

该文档是WIP源码保存及接手清单，不是Release完成报告。
