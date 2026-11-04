# r657 修复与参考项目吸收对账

## 当前结论
研究报告已完成，但参考优化并未全面实施。不能把97/97源码守卫当整批收口，也不能把新增测试文件当行为验收。当前全部本地工作树，未提交/push/升版/Android构建。

## 最新增量复核（覆盖下方早期“未改”状态）
- 播放：显式 Service pause/stop → Controller 取消、replay 与自动推进意图 token、LOOP_ONE 迟到任务、headless child owner/快速暂停恢复已实现。父代理独立复跑自动推进7、headless7、恢复guard8通过；前轮已独立复跑pause13、races17。证据为提取实际方法的聚焦JVM，不是Android服务验收。
- Runtime/Resolver：capability→native网络子请求定向取消、独立子Job、DOWNLOAD隔离、全链26秒预算已实现并独立复跑JS/Kotlin/回环HTTP。旧官方/search/metadata阻塞Call尚不保证立即关闭；共享WebView同步死循环与成功脚本脱离请求的timer仍是边界。
- 下载：来源/候选/音质/script/完整URL摘要续传身份及session注册取消已实现；父复跑纯Kotlin30和真实HTTP writer6通过，保留206校验。Android WorkManager/SAF/最终提交仍未验收。
- 隐私/输入：自动上传撤权、身份匹配清除、备份settings/scripts白名单与类型预检、Activity已提交intent重建保护已实现。父复跑17消费者测试、47隐私守卫负测；进程死亡前未消费action不持久化，备份全跨存储事务原子性不作保证。
- 账号：前台与CloudSync pass_owner契约及退出/删除清理已实现；父复跑32+10 JVM通过。真机KeyStore/冷启动未验收。
- 推荐/心动/付费：多源画像硬过滤及全指纹缓存、仅喜欢心动、KG分档metadata持久化及脚本能力协议已实现并有聚焦回归；真实推荐质量/分类事实与有权益的外部脚本媒体播放仍未验收。
- UI：睡眠选择器已统一（8h/精确自定义/播完当前/关闭）；封面父alpha/列表viewport裁切仍只审计，未修复。
- 最新父代理整树源码守卫97/97，git diff --check通过；仍未Android编译/Lint/CI/APK/真机，未commit/push/升版。下方原始对账保留为研究追溯，不代表上述已实现项仍未实施。

## 参考研究与实施状态
来源报告：/tmp/r657-reference-findings.md；固定源码与引用位于 /tmp/r657-reference-evidence/。

- LX / Sollin：咪咕真实songId/copyrightId/albumId分离已落本地；搜索/榜单/歌单、JSON、脚本payload共同接线，旧数据metadata补齐。独立JVM 7+8测试分别通过；未验证真实外部脚本播放下载。
- Folia减少动效：跑马灯实时读取、共享监听与共享封面中途取消已落本地。后台/离屏装饰循环生命周期尚未完整收口，未测性能。
- Folia / MoeKoe / KA平台错误事实：尚未实施结构化transport错误、Retry-After与主动播放/预取差异冷却。当前PlatformHttp仍以空串吞普通transport失败，不能称已吸收。
- 取消语义：仅部分落地（resolve独立Job、collector独立分发、下载不被PLAY epoch误杀、mg metadata取消传播）。PlatformSourceAdapter search/searchPlaylists仍runCatching吞取消；JS capability到nativeFetch子Call取消映射未收口。
- ArchiveTune可信媒体缓存身份：仅完成设计建议，未实施。不能直接用song.key作跨URL缓存键，需owner/候选/实际音质/内容证据与完整性约束。
- Sollin / Alger恢复矩阵：已有generation基础未重建，但暂停、同曲新意图、迟到清缓存/恢复、重复失败候选的行为矩阵尚未完成。
- SPlayer / Ceru / KA / BitChord：缓存版本迁移、命中/双预算观测、匹配边界测试属于待查缺补漏，尚未整批新增验证。
- 不采用：Android无消费者的桌面HTTP/SSE、重复WebDAV同步/图片缓存、平台账号回归、自动DJ/视觉堆叠、无测量依据的Room全量迁移。青听/Shae缺可审播放器源码，KA与BitChord/Shae名称保留候选，不编造已审实现。

## 可靠性原F1–F13对账（源码层，不是运行验收）
- F1 已改：独立resolve Job、identity finally、Scheme collector分发；仅源码/语法检查，集成行为待执行。
- F2 未改闭环：暂停/停止/同曲新意图epoch贯穿旧恢复、异步取链与提交。
- F3 未改闭环：STOP/解密失败等终止路径真实状态与transition wake一致释放。
- F4 已改：LocalFirst/初始化/提前返回纳入统一finally；行为故障注入待执行。
- F5 未改闭环：lane/缓存探测/官方/脚本/媒体探测/STALE共用单调deadline及Call取消。
- F6 部分：DOWNLOAD admission已改；JS父capability到nativeFetch子请求定向取消未改闭环。
- F7 已改：单流206 Content-Range与正文长度严格校验、200覆盖。新增JUnit尚未执行该组。
- F8 未改闭环：来源/候选/质量绑定的续传指纹；activeCalls原子注册取消；多段writer退出后再删临时文件。
- F9 已改：JSON/字典/URL userinfo脱敏；与ZIP/ScriptStorage组合的独立JVM15项通过，不是Android验证。
- F10 未改闭环：关闭自动上传/清除报告撤销排队与活跃上传、reportId匹配删除。
- F11 未改闭环：Activity重建不重投旧Intent、热Intent更新与处理身份。
- F12 部分：ZIP展开有界且独立JVM通过；传统备份32MiB/深度限制已接，专属JUnit未执行，全面字段预检未闭环。
- F13 部分：备份排除crash_auto_upload已改；显式安全allowlist/类型范围/全量预校验与原子应用未闭环。

## 账号、平台及UI剩余
- 密码provider IV已改；登录/重输安全存储失败的明确返回、切账号防新用户名配旧密文仍需核修，不可只修encrypt即称账号闭环。
- 头像版本身份/同账号迟到云响应已改；稳定owner绑定下离线恢复、accept失败提示及设备冷启动未完整验收。
- 长URL歌单/专辑导入与保存ID平台/类型隔离已改；短链有界解析未做，目前明确拒绝，酷狗collection路线未支持。
- 酷狗按档hash/audioId/albumAudioId的生产→持久化→payload仍待贯通；不能同hash冒充多音质；脚本source/action/quality声明约束仍待收口。
- 咪咕数据与头像已有HTTP/JVM证据；官方响应不含媒体URL；真实用户外部脚本同批A/B及播放下载未验证。
- 咪咕官方广场端点实测可用；历史“无广场只能关键词推荐”已过时，当前接线/回落要继续核对，不直接承诺已修。
- UI：DetailPush整页alpha及列表viewport对封面飞行裁切、睡眠设置/播放器入口重复与统一、大字体/小屏唱机卡、日夜非磨砂实包表现未验收。
- NEW缺真实provider字段不造标；真实音质标依赖上游actualQuality，尚未保证每首可显示。

## 下一步依赖顺序
1. 先关核心正确性：暂停/恢复epoch与终止清理、定向取消、下载指纹/注册并发。
2. 补脚本metadata与声明音质约束，真实咪咕脚本A/B；错误事实/限流接线。
3. 隐私撤回、Intent重建、备份allowlist与账号存储结果。
4. UI父层转场与睡眠入口、生命周期；缓存身份先定契约不盲换key。
5. 独立复核全部diff，运行新增可执行测试及完整守卫，再按授权推送与同轮最终Android编译/Lint；真机反馈另列。
