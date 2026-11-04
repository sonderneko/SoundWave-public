# r657 resolve Job / local cleanup 修复报告

## 范围与结果

已对照 `/tmp/r657-reliability-findings.md` F1/F4 核实真实源码并修复。
工作树 `/root/soundwave-r657-r3`，未 commit/push/升版，未运行 Gradle/SDK 编译，未触碰 PlayerService、恢复 epoch 或其他协作者文件。

修改：
- `app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt:413`：`resolveAndPlay` 改为显式 `Unit = coroutineScope resolve@{ ... }`，记录的是 ScopeCoroutine 自有子 Job，不再记录长期调用者 Job；整个登记后的执行放入外层 try/finally，只有 `activeResolveJob === thisResolveJob` 才清空，旧任务 finalizer 不会清新请求。原 resolve 函数中的全部提前返回改为 `return@resolve`；CancellationException 仍传播。
- 同文件 `:452-455,674-680`：提前声明 pendingCacheLease，在事务身份建立后、首次 Service 队列变更前获取 natural-end hold，并立即进入资源 try；队列设置、LocalFirst、本地成功/不可读返回、scriptRuntime.init 与在线解析均经过同一 catch/finally。保持 token 定向 release 与当前事务 resolving 清理，lease 转交语义不变。
- `app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt:958-959`：唯一 Scheme collector 的 Play 路径用 `scope.launch { resolveAndPlay(...) }` 独立分发，慢解析不再阻塞后续 Search，取代解析不再取消 collector。此文件只有这一处调用及紧邻注释变更，没有整文件覆盖。
- 新增 `scripts/test_playback_resolve_lifecycle.py`：5 个便携源码级 unittest，覆盖自有 coroutineScope、identity finalizer、Scheme 分发，以及 hold/LocalFirst/init/取消清理边界。

## RED → GREEN 真实证据

1. F1 先写源码守卫，运行得到 3 failures：未创建自有子 Scope、缺少 identity finalizer、collector 同步等待 resolve。日志 `/tmp/r657-resolve-owner-red.log`。
2. F1 修复后 3 tests OK。
3. F4 再增加 2 个守卫，运行得到 2 failures：hold 后没有立即进入资源 try，LocalFirst/init 在资源 try 之前。日志 `/tmp/r657-resolve-cleanup-red.log`（测试命令 exit=1）。
4. F4 修复后最终 `python3 scripts/test_playback_resolve_lifecycle.py`：Ran 5 tests / OK（exit=0）。

## 其他执行核验

全部 PASS：
- `bash scripts/playback-recovery-guard.sh`
- `bash scripts/scheme-action-delivery-guard.sh`
- `bash scripts/playback-transaction-guard.sh`
- `bash scripts/player-transition-guard.sh`
- `python3 scripts/local-first-runtime-boundary-guard.py`
- `python3 scripts/local-first-review-regression-guard.py`
- `bash scripts/media-cache-lease-guard.sh`
- `git diff --check`

额外利用现有 `/tmp/r657-security-jvm` 中 Kotlin compiler-embeddable 创建临时 PSI 语法解析器 `/tmp/R657KotlinSyntax.java`，执行：

`java -cp '/tmp/r657-security-jvm/*' /tmp/R657KotlinSyntax.java app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt`

两文件均 `Kotlin PSI syntax PASS`。首次尝试 javac 发现其不在 PATH，改用 Java source-launcher 成功；没有为此安装 SDK 或构建 app。PSI 检查不是 Kotlin 类型检查/Android 编译。

另以 HEAD 原文对比断言：PlaybackController 的 resolveAndPlay 区段外完全未变；MainActivity 等于 HEAD 仅替换这一处调用及注释。没有拆函数、增加大型内联算法或改变解析业务；`git diff -w` 仅小范围结构/标签变动。无法在禁止构建条件下验证 JVM 64KiB 方法实际字节码大小，不声称已编译确认。

## 验收边界 / 剩余

本次测试是直接读取真实源码的结构守卫与 PSI 语法检查，不是 Kotlin/JUnit 协程行为测试，也不是 APK/真机证据；未声称已实际执行 ACTION_VIEW→点歌→Search 或 LocalFirst 故障注入。后续 CI/设备仍应覆盖慢解析期间 Search、旧任务 finally 晚于新任务登记、本地成功/不可读/查找异常后自然结束继续工作的集成场景。
F2/F3（暂停意图 epoch / transition wake）与其他可靠性风险按委派要求不改，仍属另批处理。
