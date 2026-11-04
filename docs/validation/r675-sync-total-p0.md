# r675：同步清零听歌总时长的最小修复

## 结论

已在隔离主机测试中复现并修正一条确定性归零路径，不再仅是异步写入竞争的推测。`ListenStatsDailyStore.mergeLocalAndCloud()` 把扁平的 `totalByDevice: {设备: 秒数}` 传给要求嵌套 `名字 → 设备 → 秒数` 的合并器，导致总量字段在合并时被丢弃。歌曲/歌手明细可能仍在，但日、周、累计汇总从缺失的总量表读取为零；继续播放后只加上后续新记录的秒数。

WebDAV 成功回灌会将该零汇总写入本机。账号手动同步的账号投影和上传合并也经过同一函数，会丢上传总量；本次没有证实账号手动同步会直接清零本机统计，不能把两条路径完全等同。

生产改动仅在 `ListenStatsDailyStore.kt`：为总时长使用扁平设备计数合并，同设备取最大值，汇总时不同设备相加。未改动账号归属、授权门禁、保留期限或播放器进度状态。

## 复现与回归

修复前运行4项定向测试，1项通过、3项失败、0跳过。失败断言分别为：

- 通用合并：期望140秒，实际0秒。
- 真实累计器产生时长后，经 WebDAV controller 回灌旧快照：本机期望20秒，实际0秒。
- 账号显式同步：上传总量期望25秒，实际0秒。

新增14项测试，另外加强1项旧测试的日/累计总时长断言。覆盖空远端、同设备新旧值取最大值、不同设备相加、重复同步幂等、远端请求期间新增时长、继续播放、账号上传总量、30/365天保留、跨天、legacy/backfill仅本地、仅总量无歌曲明细、非法分量过滤、Long饱和、WebDAV内存传输往返及HTTP 500失败不修改传入数据。

第一次修复后定向回归34项：33通过、1失败。失败来自新增测试的远端造数器遗漏 `includeDailyListenStats=true`，实际造出了不带统计的远端文档；只修正测试造数，未为通过测试放宽生产检查。

最终扩展回归：**27个suite，192项通过，0失败、0 errors、0跳过**。包括统计、账号同步身份/授权/退出、WebDAV传输/回灌、历史/字段覆盖、保存客户端、累计器和日期相关测试。不是全量测试，也不与此前120项简单相加。

执行命令（SDK仅本地测试环境）：

```sh
ANDROID_HOME=/home/ubuntu/Android/Sdk bash gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' testDebugUnitTest \
  --tests 'xyz.cncnlp.soundwave.*ListenStats*' \
  --tests 'xyz.cncnlp.soundwave.*CloudSync*' \
  --tests 'xyz.cncnlp.soundwave.*WebDav*' \
  --tests xyz.cncnlp.soundwave.ComprehensiveSyncRegressionTest \
  --tests xyz.cncnlp.soundwave.Round3SyncFieldCoverageTest \
  --tests xyz.cncnlp.soundwave.PlaylistOrderSyncTest \
  --tests xyz.cncnlp.soundwave.R658cDataLaneTest \
  --tests xyz.cncnlp.soundwave.UserDataSaveClientTest \
  --tests xyz.cncnlp.soundwave.ListeningCalendarTest
```

原始日志、每轮JUnit XML及汇总：`/workspace/soundwave-sync-p0-20261022/`。`red-test-results`保留修复前失败，`green-focused-test-results`保留中途测试造数失败，`expanded-test-results`为最终成功轮；`test-summary.json`区分每轮，不以覆盖旧失败记录伪造全绿。

## 原计划编号与状态

- **SW-06-01 / SW-06-09（P0跟进）**：本次最小归零路径已修正且定向回归通过；完整统计模型、历史恢复和全部冲突语义仍未完成，不勾选整项完成。
- **SW-02-10 / SW-02-11**：统计异步写队列的可等待快照屏障、写失败诊断和真实并发压力验证仍待后续切片；不是本次确定性结构错误的替代解释，也没有声称已解决。
- **SW-11-10**：全链路/真机验收未执行；原有4项UI等待失败、4项Lint错误、debug D8问题未在本次处理或复测。

## 边界

未打包APK、未安装、未推送、未访问真实账号/WebDAV、未操作两台InstaBox。未修改版本号、现有v1.6母版ZIP或计划任务编号。阴影分支工作树的HEAD、状态及差异指纹前后一致。

本次是防止后续同步继续丢总量的代码修复，**不是已丢失历史时长的恢复工具**。歌曲明细仍在的情况可能有后续恢复线索，但没有擅自从明细重建或改写生产账本，也不能保证既有损失能够完整找回。
