# r657 播放 / 下载 / 隐私 / 输入边界只读审查

## 证据与结论范围

- 仓库：`/root/soundwave-r657-r3`；审查 HEAD：`fb660e769edfd0c892c48152b55c0d6f96a0b575`。
- 下文源码路径均相对 `app/src/main/java/xyz/cncnlp/soundwave/`，例如 `PlaybackController.kt:419` 可直接定位该目录中的文件。
- **存在且已核实当前 SHA 的成功 CI**：[run 39077563766](https://github.com/projects258036-svg/soundwave-v2/actions/runs/39077563766)。`gh run view` 返回 success；日志明确包含 `:app:testDebugUnitTest`、`:app:lintDebug`、两次 `BUILD SUCCESSFUL` 和 `:app:assembleRelease`。本报告不是“没有 CI/没有测试”。
- 本次没有安装 SDK，没有运行 Gradle/kotlinc，没有修改生产文件。实际运行五个现有源码守卫：playback-recovery、scheme-action-delivery、script-import-bounded-read、download-clear-fence、privacy-data-flow-copy，全部 PASS。它们不能证明下面未覆盖的行为竞态不存在。
- 做了 Python focused probes（非 Kotlin/JUnit 执行）：从源码抽取 secretParam 正则验证 JSON 漏脱敏；构造 ZIP 验证压缩/展开量不等价；演示错误 Content-Range 可满足现有长度条件。未声称真机复现。
- 审查期间其他工作流修改了 `DetailTopChrome.kt`、`ui/motion/SettingsPush.kt`、`ui/route/AppRootMainTabRouteRenderer.kt`；未触碰这些文件。审查核心文件逐一与 `git show HEAD:<path>` 比较，均 HEAD_MATCH。

## 逐项现状

| 主题 | 判断 | 证据 / 限定 |
|---|---|---|
| Service 播放事件代次 | 已做，仍有意图取消缺口 | `PlayerService.kt:1607-1618` 校验 owner、播放器、token、songKey、generation；不是完全没有隔离。暂停不使在途恢复失效，见 F2。 |
| 清缓存后恢复的二次校验 | 部分 | `PlaybackFailureRecovery.kt:16-28` 在异步清理后再验 songKey/generation，但没有 resolve revision / pause epoch；同曲重播仍可能被旧恢复覆盖，见 F2。 |
| 播放 / 下载运行时隔离 | 已做，部分取消仍有漏洞 | `DownloadScheduler.kt:263-265` 使用独立 DownloadScriptRuntimeHolder；`ScriptRuntime.kt:256-272` 按 purpose 选择取消。Resolver admission 仍把 DOWNLOAD 当可丢弃后台，见 F6。 |
| 多层重试预算 | 部分 | `playback/PlaybackRecoveryPolicy.kt:46-78,103-118` 有单曲恢复与连续跳过预算；`playback/PlaybackLoadErrorPolicy.kt:25-34` 有 4xx 快失败和六次最小重试；`DownloadScheduler.kt:289,353,367` 最多初次加三次 Worker 重试。26 秒只覆盖脚本段，不是端到端，见 F5。 |
| 暂停与失败 wake lock | 部分 | `PlayerService.kt:799-806` 暂停释放；`:1588-1598` 45 秒系统兜底；无界面 STOP `:1452-1457` 释放。前台 STOP/解密失败未释放，见 F3。 |
| 下载 200 / 206 | 部分 | `DownloadHelper.kt:579` 200 安全覆盖、不追加；多段 `:625-646` 严格范围/字节数验证。单流 206 缺 Content-Range 校验，见 F7。 |
| 下载指纹与取消并发 | 部分 | `download/DownloadResumePolicy.kt:29-36` 指纹比对；`DownloadScheduler.kt:49-50` 共用重入锁解决元数据锁序问题；SAF `DownloadHelper.kt:307-363` 新目标、复制回读、提交、旧文件清理已有。指纹未绑定来源、调用集合删除竞态仍存，见 F8。 |
| 崩溃上传默认关闭 / 副本清除 | 已做，撤回授权有竞态 | `CrashGuard.kt:77-84` + `CrashReportRedactor.kt:58-61` 默认关闭；`SettingsMaintenancePages.kt:467-473` 一次明确确认清两份，不应误报“清除只删 last”。启动检查之后的上传线程不再检查开关，见 F10。 |
| redactor | 部分 | `CrashGuard.kt:28-30,38-40,90,112` 写盘、读取、发送均脱敏；不读 Android ID；JSON 键和 URL userinfo 漏洞见 F9。 |
| Intent 排队 / 生命周期 | 部分 | `SchemeActionHolder.kt:110-117` 是有意设计的单槽 last-submitted-wins，不是 FIFO 缺陷；现有单测覆盖提交前/后与不重放。Activity 重新创建重复 submit、collector Job 被播放取消，见 F1/F11。 |
| 脚本重定向 | 关键防护已做 | `ScriptRuntime.kt:2031-2036,2043-2045,2053-2097` 每跳公网校验并钉 DNS、跨源剥凭据、拒跨源有正文 307/308、代理不跟跳、不将代理 envelope 送 Location。`ScriptNetworkPolicy.kt:96-122` 有对应策略。公开 HTTP 是显式兼容策略，不能泛称“未禁 HTTP=漏洞”。 |
| 脚本 / 备份输入 | 部分，有真缺陷 | 裸脚本 `script/BoundedScriptImportReader.kt:16-36` 有 8MiB+哨兵；URL 导入 `ApiClient.kt:155-162` 有有界读取且 `:76-78` 不跟跨协议重定向。ZIP 展开与传统备份预览仍无界，见 F12；备份直接恢复隐私授权见 F13。 |

## 真缺陷与最小可实施修复

### F1 — P1：一次外部 Play 可把唯一 Scheme collector 的 Job 留成“可取消的播放 Job”

**证据：** `MainActivity.kt:942-958` 在 `LaunchedEffect(Unit)` 的 `collect` 内直接 suspend 调用 resolveAndPlay；`PlaybackController.kt:418-420` 保存的是调用者当前 Job，不是自有子任务。函数返回后未清 `activeResolveJob`（`:672-675` 仅关 lease / hold / resolving）。之后普通 UI 点歌进入 `:419`，取消的仍是长期运行的 Scheme collector Job。它不会因同一个 Unit key 自动重启，后续 onNewIntent 可不再被处理；解析过程中还会阻塞其它 action 的消费。

**最小补丁：** 外部 Play 用独立 `scope.launch { resolveAndPlay(...) }`，collector 只分发；Controller 的 resolve body 放入自有 `coroutineScope` / child Job，finally 仅在 `activeResolveJob === 本请求Job` 时清空，禁止记录/取消长期调用者 Job。二者一起做才不会把其它长期调用入口也留下。

**行为测试：** ACTION_VIEW Play A → resolve 完成 → 普通点歌 B → 热 Intent Search C，断言 C 被消费且 collector Job active；A 慢解析时发 Search C，断言导航不必等待 A 超时。现有 SchemeActionHolderTest 只测 dispatcher，不覆盖接线。

### F2 — P1：暂停不是事务失效边界，同曲新意图也挡不住旧恢复

**证据：** `PlayerService.kt:793-807` 暂停只 pause、置 flag、释放 wake，没有递增取消 epoch / 取消 resolve；`PlaybackFailureRecovery.kt:16-28` 仅认 songKey+Service generation。新同曲取链直到 `PlayerService.kt:531` 提交才增加 generation；`PlaybackController.kt:437-456` 已建立新的 revision，但旧 afterCacheClear 不检查它。无 UI 的 `PlayerService.kt:1492,1507-1515` 也只验 songKey+playGen，暂停不会改变二者；迟到 URL 最终执行 `:565-572` 强制播放。

**后果：** 错误恢复/自动切歌解析期间按暂停，稍后会自行重新播放；清缓存挂起期间用户同曲重播，新请求尚未提交时旧恢复仍可复活并取消新请求。

**最小补丁：** Service 加独立 playback-intent epoch（暂停/stop/用户新意图即递增）；恢复、取链、posted commit 捕获并复核；Controller afterCacheClear 额外注入 request/revision + owner 守卫，暂停入口统一取消自有 resolve Job。不能只依赖换媒体时才增长的 playGen。

**行为测试：** (1) clear deferred → 暂停 → clear 完成，0 次 recover/play；(2) 同曲新 revision 建立后旧 clear 完成，旧 recover 0 次；(3) headless resolve deferred → pause → URL 完成，不 play、wake 已释放。

### F3 — P2：前台失败终止路径未释放 transition wake，且 STOP 只改 UI

**证据：** `PlayerService.kt:1706-1708` 错误前持锁后转前台；`PlaybackController.kt:372-376` STOP 只有状态和 Toast；`:622-632` 解密失败退出亦未释放。对照 Service 自己的 STOP `PlayerService.kt:1452-1457` 明确 release。45 秒超时存在，故不是无限持锁，但连续失败会重复 acquire。

**最小补丁：** 统一 `PlayerService.finishFailedTransition(expectedIntentEpoch)`：核验当前事务后 pause/置真实 flag/发布快照/release；Controller 的 STOP、不能继续推进的 resolve/decrypt failure 都调用它。不要对过期事务无条件 release 以免释放新事务锁。

**行为测试：** 强制连续失败触发 STOP，以及解密失败不跳下一首，断言 player paused、真实快照 false、wake 不 held；旧事务 finally 不释放新事务 wake。

### F4 — P2：本地播放与 LocalFirst 异常绕过 finally，naturalEndHold 可遗留

**证据：** `PlaybackController.kt:433` 获取 hold；LocalFirst IO 在 `:465`，本地命中 `:472-474` return、本地失效 `:478-483` return，均在 `try` 的 `:495` 之前。唯一 release 在 `:674`。若 old A→本地 B 或本地不可读 B，旧 A 的 hold 未配对释放；LocalFirst IO 异常也绕开统一错误处理。

**最小补丁：** try/finally 从获取 hold 后立即开始，把 LocalFirst、scriptRuntime.init 和本地 early return 全纳入，pending lease 变量提前。保持现有 token 条件释放。

**行为测试：** A 正播→本地 B 成功 / 本地 B 不可读 / LocalFirst 抛异常，三条路径均断言 hold 为空，A 自然结束不被残留 hold 吞掉。

### F5 — P2：所谓“全局预算”只包脚本阶段，官方链 / 锁等待 / 同步探测不受同一 deadline 控制

**证据：** `UrlResolver.kt:194-205` 缓存媒体探测，`:229-281` 官方直连+officialUrl，均早于 `:371-372` 的 26s 起点；lane 等待 `:678-691` 在外。脚本成功后 `:401` 同步 validateProbeMedia，探测 client `:983-985` 可阻塞 8s，协程 timeout 不是同步 execute 的可取消连接。Controller `:521-570` STALE 可额外发起完整事务。45s wake 与这些预算没有协调。

**最小补丁：** 在 resolveResult 最外层创建单调时钟 deadline，admission/lane/cache probe/官方/脚本/跨源共享；派生每个 Call 的剩余 timeout 并将 Job 取消接到 Call.cancel；STALE 自动重试消费同一预算而非重新给满。保留合理慢脚本窗口，不简单把 26 改小。

**行为测试：** fake clock + fake transports：lane 等待、官方超时、脚本超时、媒体探测、STALE 都从一份预算扣减；预算耗尽后后续阶段 0 次网络调用。此问题是“不是总预算”，不是断言现有每层无超时。

### F6 — P2：DOWNLOAD 仍被 PLAY epoch 判 stale；Runtime 取消 URL 请求未取消其 nativeFetch 子请求

**证据一：** `UrlResolver.kt:640-641` `purpose != PLAY` 包括 DOWNLOAD；`:657,670-674` 任意 PLAY 增 epoch 后，下载即不再 admitted，即便它有独立 lane/runtime。Worker `DownloadScheduler.kt:287-294` 对 STALE_REQUEST 不重试，而标 failed。

**最小补丁一：** isDiscardableBackground 仅 PREFETCH/PROBE；DOWNLOAD 只受 owner/cancellationEpoch/Worker lifecycle 取消。

**证据二：** `ScriptRuntime.kt:355-358` pendingUrl key 是原生 UUID URL capability；`:1521,1587-1591` nativeFetch cid 是独立 `sw_...`；`:1789-1795,2037` scheduler/activeCalls 用后者。`:265-272,382-386` 用前者查 activeCalls，通常查不到；cancelPlay/cancelSpeculative 并没有 cancel scheduler 中旧脚本子网络请求。取消协程释放 evalMutex 后，旧 JS timer / fetch callback 仍可消耗队列并进入旧 handler。

**最小补丁二：** 显式 parent URL capability → 子 fetch cid 集合；JS request 发起时携带 parent capability，nativeFetch 记录映射；取消/超时/finally 按父节点取消 scheduler 子项并清 JS callback/timer；不得 cancelOwner 全杀而误伤其它 purpose。

**行为测试：** 下载 admitted 后用户点歌，下载仍成功；旧 PLAY 的 nativeFetch 正在 execute，点新 PLAY 后该 Call.cancel 被调用且新请求不等旧队列；DOWNLOAD 子请求不受影响。

### F7 — P1：单流 206 不验 Content-Range，可拼入错误区间且通过长度验收

**证据：** `DownloadHelper.kt:578-583` 只认成功码、resumeFrom 和 206，append 时仅验 validator；完全不读 Content-Range。`:527-542` 长度+容器头+音轨解析无法证明每个区间正确。多段分支 `:626-646` 已有正确方向。

**示例：** 现有 400 字节、总长 1000，服务器回同 ETag、`206 bytes 0-599/1000`、600 字节，会按 append 产生 1000 字节，现有长度条件通过（focused probe 已执行；不是声称真实音频必然过 MediaExtractor）。无 Range 请求收到非零起点 206 也没有拒绝。

**最小补丁：** 抽严格 ContentRange parser。续传必须 code=206、start=resumeFrom、end-start+1 等于实际读取字节数、total 匹配指纹；offset=0 的 206 也必须 start=0 并覆盖完整实体。200 始终 truncate，刷新实际响应元数据。异常范围立即失败且不提交 done。

**行为测试：** MockWebServer 同 ETag 的错 start、错 total、短读、超长、缺 Content-Range 各失败；正常 206 成功；Range 被忽略回 200 产生完整新文件不拼接。

### F8 — P2：断点指纹证据不足，activeCalls 空集合删除存在竞态

**指纹证据：** `DownloadHelper.kt:58,216-225` 只存 total+ETag/Last-Modified，`:223` 在真实 GET 前写指纹；`:580` 只在 append 验 validator。`DownloadResumePolicy.kt:32-35` 接受弱 ETag、单独 Last-Modified、未知 total，也未绑定 URL 来源。换源 CDN 可以复用 `ETag: "1"` / 同 Last-Modified；200 响应实体与 probe 不同，失败留下 part 后，旁边仍可能是 probe 指纹。

**最小补丁：** 保存规范化 origin+path（不要明文 token query）、解析候选平台/曲目/质量身份、总长、强 ETag；未知身份/弱 validator 不续传。指纹在实际响应验证且准备写入时与 truncate/append 配对更新。Last-Modified-only 如要兼容应明确更弱策略，不能声称“可证明同实体”。

**并发证据：** `DownloadHelper.kt:94-108` 中 untrack remove → isEmpty → map.remove 与 track.getOrPut.add 非原子：新 Call 可加入刚判空的 set 后整个 set 被 remove；`:68-75` 随后 cancel 无法找到该 Call。循环 shouldCancel 是兜底，但阻塞 read 最多仍等待 readTimeout，不能等价于即时取消。

**最小补丁：** taskId 的 track/untrack/cancel 用 ConcurrentHashMap.compute/computeIfPresent 管理原子增删，或 task-scoped registry lock；cancel 设 task tombstone 并取消快照，后注册立即拒绝。多段异常/取消在 finally shutdown 并有界 join 全部 writer 后再删除 staging，避免 `:237` 删除时 writer 未退出。

**行为测试：** 同一 validator/size 不同 origin 不续传；probe A→GET 200 B 中断不留下 A 指纹；barrier 卡在 isEmpty 与 remove 之间注册新 Call，再 cancel，断言所有 Call 均 canceled 且无后台 writer。

### F9 — P1：redactor 漏常见 JSON 凭据与 URL userinfo

**证据：** `CrashReportRedactor.kt:25-27` secretParam 要求键名后直接空白/冒号，不接受 JSON 键末双引号；`:23,45` URL 仅去 query/fragment，不去 userinfo。Throwable.message 可包含 API JSON / URL，`CrashGuard.kt:103` 原样纳入后交 redactor。

**实际 focused probe：** 从源码提取正则：`token=SECRET123` → `token=<redacted>`，但 `"token":"SECRET123"`、`{"password":"SECRET123","api_key":"SECRET123"}` 原样保留。短凭据不会被 hex/JWT 等其它规则兜住。`https://alice:SECRET123@example.com/a` 的 userinfo 同样无对应清理规则。

**最小补丁：** 支持键前后引号、单双引号值和空格值的结构化 key-value 脱敏；URL 统一剥 userInfo 后再去 query/fragment；保留堆栈可定位信息。对支持 JSON 的字段优先结构化递归，任意异常文本使用补充正则。

**行为测试：** JSON token/password/api_key、带转义引号和空格值、单引号字典、URL userinfo（有/无 query）、重复 redact 幂等；last/pending/submitNow 三路径均不得包含 sentinel secret。

### F10 — P2：关闭自动上传或清本机副本不能撤销已排队线程

**证据：** `CrashGuard.kt:58-67` 主线程读文本和开关后创建 Thread；`:67,108-113` 真发网络前不再验开关、报告代次或清理 epoch。Settings `:422` 只改 preference，清除 `:472` 只删磁盘。即使用户先关闭/清除，再由已排队线程运行，内存中的旧文本仍会上传。成功 `:73` 还会无条件 clearPending，可能删除之后新写入的报告。

**最小补丁：** autoUpload 与手动 submit 分离；自动任务捕获 reportId+privacyEpoch，在网络提交前复核，关闭/清除递增 epoch 并 cancel 活跃 auto Call。成功只删除匹配 reportId 的 pending。已送到远端的字节无法撤回，UI 应准确说明。

**行为测试：** 阻塞 uploader 队列→关闭/清除→放行，0 次 request；A 上传中创建 B，A 成功不删除 B；手动发送在自动开关关闭时仍允许。

### F11 — P2：Activity 重建重投旧 Intent，onNewIntent 又没有更新 Activity.intent

**证据：** `MainActivity.kt:203-212` 无条件 submit，即 savedInstanceState 非空也重投；`:214-217` 热 Intent 只 submit，没有 `setIntent(intent)`。Channel 本身不 replay 并不等于 Activity 不 replay。

**最小补丁：** 冷启动仅在没有已处理恢复标记时提交；onNewIntent 更新 intent 并设置/消费 action token，处理完成后清 data/显式记录已处理身份。若需要未消费跨重建保留，保存 pending actionId，而非无条件重读原 intent。

**行为测试：** Play A consumed→recreate 不再次插队；冷启动 A→热 Search B→recreate 不复活 A/B；collector 未建立前的最新 action 仍只消费一次。声明的 conflation 策略保留。

### F12 — P1：ZIP 展开、传统 JSON 备份预览仍无界，可由导入触发 OOM

**证据：** `ScriptStorage.kt:169` 只限压缩输入 8MiB；`:177` zip.readBytes 无界分配，`:178` 分配完成才判断 2MiB，且没有总展开/条目数限额。`ExtraPages.kt:403-405` content stream readText 无界后 JSONObject；不是已加严格上限的独立 `LocalProfileBackupImporter.kt` 路径。

**实际 focused probe：** 一个 16,423 字节 ZIP 内单条目展开 16,777,216 字节；证明“压缩输入≤8MiB”不约束展开量，没有在 APK 上实际制造 OOM。

**最小补丁：** ZIP 每条目用有界 reader（2MiB+1），累计展开字节和条目计数双限；超限整个导入失败且不写 prefs，不依赖 ZipEntry.size。传统备份先统一 32MiB+1 有界读，再限制 JSON 深度/数组数量/字符串；沿用 prepared→preview→confirm→commit，不能在解析失败时改状态。

**行为测试：** 小 ZIP 巨大条目、未知 entry.size、多小条目累计超限、截断 ZIP；超限后 scripts/prefs 不变。JSON 恰好上限/上限+1、深嵌套、provider 不提供大小都覆盖。

### F13 — P2：通用备份能恢复“自动上传崩溃报告”授权，且只做黑名单式配置恢复

**证据：** `BackupStore.kt:71-72,123-141` 接受任意 scalar preference，仅排 pass/password/token/user/logged_in；`crash_auto_upload` 不在敏感过滤；`:44` 导出同样带此字段。`ExtraPages.kt:405` preview 只显示业务数量，用户并不知道文件里把崩溃上传开关设 true。确认恢复可在未明确选择诊断上传时改变隐私授权，下一次冷启动触发 `CrashGuard.flushPending`。亦允许恢复不适合从外部备份接受的 server/download URI 等运行环境配置；不要把“用户确认恢复”当“逐项隐私同意”。

**最小补丁：** 设置恢复改显式安全 allowlist + 类型/范围校验；crash_auto_upload、账号/凭据、URI 授权、服务地址等不从不可信通用备份自动启用。隐私上传授权留本机当前值，若确需迁移则 preview 单列且显式 opt-in。所有字段预校验后再写，避免中途类型异常留下半恢复。

**行为测试：** 备份含 crash_auto_upload=true，恢复后当前 false 不变；未知字段拒绝/忽略、错误类型不污染 prefs；明示允许的音量/音质/外观字段仍可恢复。

## 建议最小实施顺序

1. 先 F1/F2：保护长期 collector 与暂停意图，避免“已暂停却开播/后续外部链接失效”。
2. F7/F9/F12：分别封住静默下载区间错误、凭据外发、导入内存耗尽。
3. F6/F8：下载不被点歌误杀，真正取消脚本网络和 task Call 注册竞态。
4. F3/F4/F5/F10/F11/F13：完善终止清理、统一预算、隐私撤回、重建幂等与备份授权。

**验收边界：** 本报告的补丁与行为测试均为待实施方案；当前 HEAD 的成功 CI 是基线，不是这些新场景已经测过的证明。未进行生产代码修改、未重新构建、未做真机验证。
