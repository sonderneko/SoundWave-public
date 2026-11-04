# 播放器事务、转场与首页分页回归基线

本清单是稳定化计划第 1 步的行为基线，供后续 `FullPlayer` / `HomePage` 纯结构拆分前后对照。静态守卫和 JVM 单测不能替代真实设备上的动画、手势、音频切换及网络交互验收。

## 自动检查与证据边界

静态守卫无需 Android SDK，可在仓库根目录运行：

```bash
bash scripts/mainactivity-route-overlay-guard.sh
bash scripts/playback-transaction-guard.sh
bash scripts/player-transition-guard.sh
bash scripts/home-pagination-guard.sh
bash scripts/home-topbar-presentation-guard.sh
```

`mainactivity-route-overlay-guard.sh` 检查 MainActivity 的纯路由 renderer 映射、播放器/详情/设置/搜索返回优先级、详情/首页/main-tab 调用顺序、固定播放器宿主高度及全屏播放器和全局弹层的叠放顺序；它只验证源码结构，不会运行 Compose。

JVM 回归用例位于 `PlaybackTransactionGateTest`；该测试使用可控闸门复现“A 开始取链 → B 成为当前事务 → A 迟到返回”，并断言 B 的歌曲、链接和处理中状态不变。`playback-transaction-guard.sh` 另外检查 `PlaybackController.resolveAndPlay` 是否仍把同一门闩接到实际解析回调、成功提交及事务清理边界。两者合起来是源码级回归证据；它不是对真实 `PlayerService`、Media3 或 Compose 状态的端到端测试。

`PlayerLayoutModesTest` 覆盖沉浸封面布局策略与部分纯函数；`player-transition-guard.sh` 检查沉浸背景和前景共用关闭位移父层、抽离后的背景渲染仍使用相同封面 painter/crop/透明度/缩放/模糊及 tint/scrim 曲线、Hero 仍保留原封面淡出与状态栏渐暗，以及全屏播放器路由维持纵向进出场结构。静态结构通过不证明动画观感或帧率。

首页分页静态守卫检查分类/平台/分组分页状态、首屏从第 1 页开始、后续请求顺序、重复请求锁、来源内去重合并、无新增时停止、空/异常页可重试、加载反馈和锁释放；顶部栏守卫检查无状态渲染器与 pager/平台副作用仍留在 `HomePage`。这些均不执行实际请求或 Compose 列表。

**运行边界：**本轮没有运行 Gradle、Android SDK、ADB、模拟器或本地构建。`PlaylistFeedRepositoryTest.emptyOrFailedCategoryPagePreservesRetryStateAndReleasesLoading` 的测试源码已补充，但本轮未执行 JVM 测试；它通过纯 Kotlin 状态函数覆盖空列表与失败映射 `null` 时保留当前页码/列表/`hasMore` 并解除 loading。仓库现有 Codemagic `android-release` workflow 只执行 `assembleRelease`，不运行 `:app:testDebugUnitTest`；因此 JVM 测试结果仍待用户后续配置/触发包含该测试任务的 CI。静态守卫的执行结果会单独记录，不计作 JVM 或设备测试通过。

## 真机手动验收

### 播放 A/B 取链竞争

1. 选中一首需要较长时间取链的 A，立即点播另一首 B。
2. 等待 B 开始或播放后，观察 A 的旧响应返回时播放器当前歌曲、实际音频、进度、取链提示和 resolving 状态均仍属于 B。
3. 在 A 仍等待时连续切换 B/另一首，再观察播放器、通知栏和媒体会话是否一致；记录设备型号、Android 版本、音源、网络类型及时间顺序。

### 沉浸封面与播放器转场

1. 选择“沉浸封面”布局，打开全屏播放器，确认封面英雄图和模糊背景都来自当前曲目；首帧不应分离、闪黑/闪白或出现明显硬边。
2. 点击封面进入沉浸歌词，再返回封面；歌词页下滑应回到封面，而不是关闭整个播放器。
3. 打开队列后返回，确认此前的封面/歌词模式恢复；队列页下滑关闭行为保持不变。
4. 从迷你播放器打开全屏页并关闭，观察封面、背景和前景同向移动；快速重复开关，检查是否出现双重动画或明显掉帧。
5. 对照“Apple Music”“歌词样式”布局，确认沉浸专用背景没有泄漏到其它布局。

### 首页分类分页

1. 选一个确实有多页结果的分类，记录第一页数量/底部计数；点击“加载更多”，确认列表增长且同一来源歌单不重复。
2. 加载期间快速重复点击，确认没有并发重复加载；完成后再点，观察是否继续取下一页。
3. 切换平台、一级分组或分类，确认新分类回到第一页，旧分类的加载状态不串入。
4. 下拉刷新、离开再返回并滚动回底部，记录实际分页和滚动位置行为。
5. **可复现的失败场景：**加载完第一页后断网，再请求第 2 页；恢复网络后重新点加载更多。版本 `1.0.507` 修复了异常被映射为空列表后仍推进 `catPage`、令 `catHasMore=false` 的问题：空/异常后续页保留当前列表和页码，恢复网络后重试同一页。由于 `MusicSource.searchPlaylistsPage` 目前只返回列表、无法区分成功空页与网络失败，成功空页也保持可手动重试（可能重复请求）；非空响应仍按原去重/推进规则处理。该行为已由静态守卫检查，但实际断网/恢复网络的设备验收、JVM 测试与构建均未运行。

## 本轮判定

静态守卫只保护源码结构与接线关系；JVM 测试、Codemagic 含测试任务的运行结果以及上述真机验收，须分别记录，不能相互替代。未完成适用的自动测试与设备验收前，不应把本基线描述成播放器/分页体验已经完整验收。
