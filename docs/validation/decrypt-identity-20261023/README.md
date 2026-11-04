# 解密播放缓存：隔离账号、来源与实际内容

基线：`b4af969c0b5fa1941a8c7501b1b4dd710d8c5de3`；工作区：`/workspace/soundwave-675-improve`。

## 修复

播放端不再使用 `song.key() + "|" + quality`。缓存范围来自本次 ResolveResult 捕获的账号归属与 generation、原歌曲、实际平台、候选、脚本、实际音质和加密格式。来源不完整时使用一次性范围，不猜测或复用旧明文。

解析成功及 URL 缓存命中时，都直接回传本次可信身份，不再从共享 URL 缓存事后推断赢家；同时修正原先把候选 SHA-256 当作平台的映射。

播放准备先下载密文，流式计算 SHA-256，再以逻辑范围与内容摘要定位明文。因此同 URL、同长度但内容变化不会复用旧明文；签名 URL 轮换但字节相同可复用解密产物。URL/token 不参与文件命名，也没有新增 URL/token 日志。摘要只证明实际密文字节一致，不把 MIME、长度或探测成功冒充强内容证据。

暂存文件仍先持有租约；目标文件认领、活跃播放清理保护及取消交接沿用原租约管理器。旧版明文缓存不会被新的播放入口认领，也不会主动删除仍持有租约的旧文件。旧通用缓存 API 保留兼容，生产 PlaybackController 使用新的类型化入口。

## 实际验证

| 阶段 | 测试类 | 测试项 | 失败 / errors / 跳过 |
| --- | ---: | ---: | --- |
| 修复前 RED | 1 | 7 | 7 / 0 / 0 |
| 首轮 GREEN | 7 | 30 | 0 / 0 / 0 |
| 最终扩展 | 14 | 58 | 0 / 0 / 0 |

最终新增 `PlaybackDecryptIdentityTest` 有 11 项。覆盖账号 generation 切换、不同记录账号同代次、平台/候选/脚本/音质独立隔离、同 URL 同大小换密文、token 轮换、缺失身份、旧版缓存隔离、文件缺失/空文件重建、活跃播放清理、并发暂存清理、取消和失败下载不能退回旧明文。其余测试覆盖原并发锁、租约、启动清理、Media3 读租约、播放事务、账号边界、失败恢复与历史策略。

所有新增测试只用合成密文、注入的下载器和解密器，不请求实际媒体、不登录账号，不访问真实用户数据。Robolectric/JVM 测试不代表真机或可听解码通过。

`red-seam.patch` 是基线上只加入测试入口的补丁，仍保留原 `songKey|quality` 实现；`red-test.kt` 是当时的 7 项测试原样副本。RED 复现的是与旧播放键一致的真实缓存引擎，不是假称运行了真机 PlaybackController。若需重现，在独立临时基线副本应用该补丁，将该测试副本放入 `app/src/test/java/xyz/cncnlp/soundwave/PlaybackDecryptIdentityTest.kt`，执行下面命令并仅保留该类的 `--tests` 条件；预期 7 项失败。不要在累计主工作区倒退提交。

最终命令（在上述工作区执行）：

```sh
ANDROID_HOME=/home/ubuntu/Android/Sdk bash gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' :app:testDebugUnitTest \
  --tests 'xyz.cncnlp.soundwave.PlaybackDecryptIdentityTest' \
  --tests 'xyz.cncnlp.soundwave.MediaDecryptCacheConcurrencyTest' \
  --tests 'xyz.cncnlp.soundwave.MediaCacheLeaseTest' \
  --tests 'xyz.cncnlp.soundwave.Batch3MediaCancellationTest' \
  --tests 'xyz.cncnlp.soundwave.StartupTempFileCleanerTest' \
  --tests 'xyz.cncnlp.soundwave.AnonymousResolutionPolicyTest' \
  --tests '*TrustedMediaCacheIdentityTest' \
  --tests 'xyz.cncnlp.soundwave.MediaCacheClearTest' \
  --tests 'xyz.cncnlp.soundwave.Media3CacheReadLeaseTest' \
  --tests 'xyz.cncnlp.soundwave.PlaybackTransactionGateTest' \
  --tests 'xyz.cncnlp.soundwave.PlaybackOwnerBoundaryTest' \
  --tests 'xyz.cncnlp.soundwave.PlaybackFailureRecoveryTest' \
  --tests 'xyz.cncnlp.soundwave.PlaybackHistoryPolicyTest' \
  --tests 'xyz.cncnlp.soundwave.PlaybackHistoryThemeGuardTest'
```

原始日志为 `red.log`、`green.log`、`final.log`，对应 XML 分别在 `red-xml/`、`green-xml/`、`final-xml/`；`summary.json` 从 XML 汇总，不把多轮相同测试重复累计。最终 Kotlin 生产代码与测试代码实际重新编译；SDK XML 工具版本警告存在，但最终任务成功。

## 代价与边界

由于没有可依赖的强远端内容验证器，本轮选择安全优先：重复播放加密媒体仍需下载完整密文，缓存节省的是再次解密，不承诺节省流量或离线复用。实际账号激活流程未接入的能力不由合成归属测试证明。

没有全套单测、Lint、仪器测试或真机测试通过的宣称；没有打包、生成 APK、安装或推送。只修改 3 个生产文件与新增回归/本轮验证证据；未改详情页、播放器布局/间距、版本或签名；未操作 InstaBox、VPS、独立 UI/Lint 工作区或阴影工作区。
