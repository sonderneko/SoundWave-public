# r675：统计同步快照屏障与失败诊断

继 `1f02c91f` 修正总量表合并归零后，本轮继续补强正在播放、统计待写与同步快照之间的时序。不改前次总量合并算法，不打包。

## 本轮补了什么

- 统计写入、启动时历史回补及忙队列中的快照读取使用同一进程级串行队列。快照在前序已接受写入之后读取不可变统计 JSON，不是先拍旧快照再只等一个标记。服务实例销毁时提交末尾整秒，队列不随实例关闭。
- 同步前在主线程交接一次服务采样/整秒刷出；等待是挂起式，不用 `Future.get()`、`join()` 或主线程 latch。未满一秒的余量保留，频繁同步不反复丢掉它。
- 账号显式同步、最终上传及 WebDAV 请求前使用已确认的统计快照。自动保存最终在 debounce 后重新读取统计，不沿用早先 bundle 中的旧统计字段。账号等待后复核身份；WebDAV 成功回灌前复核本机资料会话代次。
- 统计投影默认在 IO 调度器或 writer 上读取，避免空闲队列快路径在 Main 解析统计。控制器沿用可注入的 IO 调度器，便于隔离测试。
- 事件入队时捕获账号归属，执行时不再把 A 的已排队事件归到后来登录的 B。
- 明确记录布尔未确认、异常、拒绝执行、过期 owner、读取失败和等待超时。日志只含序号、操作类别、原因枚举，不含歌曲、账号、URL 或异常原文。写失败对该本机 owner/代次保留未确认状态，后续成功不能掩盖前面的失败；相关同步停止上传。不盲目重放增量，避免内存已应用时重复计数。

主要接入文件：`ListenStatsWriteQueue.kt`、`ListenStatsSync.kt`、`PlayerService.kt`、`ListenStatsRecorder.kt`、`ListenStats.kt`、`ContextData.kt`、`CloudSyncController.kt`、`ui/settings/WebDavSyncOverlay.kt`。

## 实际测试结果

新增4个测试类、24项测试。全部用本地假账号、模拟传输/回调与隔离偏好设置，不访问真实账号/WebDAV，也不创建真实播放音频的服务生命周期。

| 轮次 | 实际结果 |
| --- | --- |
| 修复前服务边界复现 | 2项失败：队列阻塞时同步先返回；写入 false 没有失败诊断 |
| 首轮聚焦回归 | 6组、39项通过，0失败、0跳过 |
| 首轮扩展回归 | 32组、221项，220通过、1失败；新超时恢复用例的 fixture 只设 controller 登录态、未设持久化 logged_in，事件按未登录归属导致账号上传为0 |
| 修正测试登录造数后的相同扩展回归 | **32组、221项通过，0失败、0 errors、0跳过** |

最后一轮包含统计、账号授权/身份/退出/上传、WebDAV、历史/字段覆盖、保存客户端、累计器、日期与资料会话协调器。轮次互相重叠，不把39、221及此前192相加，也不据此宣称全量通过。

新增边界中已实际验证：阻塞 writer 时同步挂起且普通协程可运行，释放后包含此前排队的25秒；等待期间换账号不上传；超时不上传且队列恢复后重试仍含25秒；取消同步不上传但已接受写继续完成；A入队后切B仍归A；写失败即便修复偏好内容仍阻止账号/WebDAV快照；WebDAV旧/空结果回灌不清本机25秒；服务两次快照正确保留半秒余量；队列异常/拒绝/stale owner/诊断回调异常及FIFO顺序均有可控执行器覆盖。

最终命令：

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
  --tests xyz.cncnlp.soundwave.ListeningCalendarTest \
  --tests 'xyz.cncnlp.soundwave.*ProfileSessionCoordinatorTest'
```

证据目录：`/workspace/soundwave-sync-barrier-20261023/`。保留初次编译定位日志、修复前测试源码与XML、聚焦轮、扩展中途失败轮及最终成功轮；`test-summary.json`由`summarize-tests.py`从每轮XML计算。最初生产编译发现启动回补仍引用旧writer，随后已接入共享队列；最终测试链包含生产Kotlin编译通过。

## 保持原编号与边界

- **SW-02-10 / SW-02-11**：本轮屏障、入队归属和失败诊断切片已实现并聚焦回归通过，不能把完整耐久性与并发验收整项勾完。
- **SW-06-01 / SW-06-09**：保留前次归零修复；此次降低待写/快照竞争风险，不宣称所有统计模型、冲突或历史恢复都完成。
- **SW-11-10**：全量、真实播放负载、真机与统一打包验收未执行。

仍未解决：进程被杀前尚未提交的内存增量没有持久 outbox；SharedPreferences已有的内存回读确认及固定值重写约定未重构，不能把本轮屏障当作磁盘耐久性证明；5秒限制针对挂起等待，不承诺能抢占同步文件系统调用。未确认写的失败状态只保存在进程内，本轮没有提供自动修复/清除入口，也不保证重启后能找回丢失增量。账号旧键大小写规范化规则未迁移；不把正常A/B切换测试外推为所有账号标识兼容性已解决。历史损失恢复、跨设备真实冲突及更长并发压力仍需后续切片。

没有打包、安装、推送或操作真实账号/WebDAV、InstaBox、测试构建机。没有修改版本号或母版ZIP。`/workspace/soundwave-675-ui-lint`与`/workspace/soundwave-shadow-675`的HEAD、状态和diff指纹前后一致；未合入、覆盖或重复实施独立UI/Lint修复。前次`ListenStatsDailyStore.kt`相对`1f02c91f`没有差异。

## 后续：磁盘确认边界

后续 [r675-stats-disk-durability.md](r675-stats-disk-durability.md) 已替换上文第56行所述的内存回读确认约定：统计成功必须有同步磁盘 commit 确认，快照重验磁盘状态，并补充失败/重启/备份恢复回归及独立 Linux JVM SIGKILL 证据。仍无持久 outbox，不保证尚未提交的累计器/队列增量或已丢失历史的恢复；不能据此勾完完整耐久性、真机 force-stop 或并发验收项。
