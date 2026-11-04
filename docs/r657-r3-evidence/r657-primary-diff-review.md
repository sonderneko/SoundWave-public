# r657 主代理 diff 独立只读复核

范围：`/root/soundwave-r657-r3` 中 DetailTopChrome / DetailDesignPages / SearchPage / SearchAllSections / SettingsPush / AppRootMainTabRouteRenderer / MotionPolicy / UrlResolver / BackupStore，以及这些修改的实际调用链。未修改工作树、未运行 SDK/Gradle/编译、未提交。多人并行编辑，以本次读取版本为准。

## 确认问题（源码可确定）

### P1：新不透明 Tab / 设置层永久盖住页面磨砂背景，而不只在转场时遮挡
- `MainActivity.kt:3792-3803` 顺序：根 `FrostedPageBackdrop` → 常驻 Home / Tab renderer；`AppRootMainTabRouteRenderer.kt:60-61` 对 SEARCH / PLAYLISTS / SETTINGS 无条件填充 `background.copy(alpha=1f)`，未读取 `FrostedPrefs.pageFrosted`，也未在此层重新绘制 backdrop。
- 设置还在 `SettingsPush.kt:63-69` 对主页与每个子页增加同样的实色层。`SettingsHub.kt:287` 确实经过这个 host；主页面及 fullChrome 子页内部没有补一层 `FrostedPageBackdrop`。
- 结果是开启“透明磨砂背景”后，以上前景页面无法露出根层的封面模糊/光晕；不是仅压掉下方旧页内容。设置状态仍开、半透明组件/隐藏分隔线仍跟随该状态，唯独整页磨砂底被盖掉。详情路径不同：`MainActivity.kt:1765` 有独立 backdrop，不应一并移除其不透明基底。
- 最小方向：前景页仍保持不透明隔离，但把当前开关下的 `FrostedPageBackdrop` 画到各页自己的不透明基底之上；不是简单删掉基底、重新暴露旧路由。

### P1：sharedCoverTarget 放在已有 clip 内侧，来源位置在封面/hero 范围外时会被裁掉
- `DetailDesignPages.kt:180`：`Box(modifier.sharedCoverTarget(...).background(...))` 将变换追加到调用方 modifier 后。
- 标准详情真实调用是 `StandardDetailHero`，其 `modifier` 已在 `:431-433` 包含 shadow 与 `.clip(shape)`；因此顺序是目标尺寸 → shadow/clip → sharedCoverTarget.graphicsLayer。飞行动画只能在静止的目标圆角框内绘制，不能从搜索列表原位置完整飞入。
- 默认歌单/专辑真实调用是 `PlaylistDesignHero`（`LibraryPage.kt:1081/1099`），虽然 `DetailCoverImage` 本身传入 fillMaxSize，但外层卡在 `DetailDesignPages.kt:318-326` 已 `.clip(shape)`，同样会裁掉卡外飞行内容。仅把 target 移到图片自己的 clip 前，不能解决该父级 clip。
- `AlbumDesignHero:381-385` 也存在同类顺序，但当前 `LibraryPage` 专辑分支用 PlaylistDesignHero，不以这个未调用分支作为主要证据。
- 最小方向：让飞行封面位于裁剪祖先之外的 overlay；或明确调整实际使用 hero 的裁剪层级，保持静态封面/卡的圆角不变。

### P2：loopMarquee 每个实例新增一对独立外部监听，列表路径会放大订阅成本
- `MotionPolicy.kt:122-125` 每次 modifier materialize 都调用 `rememberReduceMotion()`；后者 `:83-106` 独立分配状态、Handler/ContentObserver、偏好监听并注册/注销。
- 实际高频调用：`SongRow.kt:173,185` 标题与歌手各一个，另有 `LibraryDesignRow.kt:101`、`MiniPlayer.kt:182,195` 等。Lazy 列表新增/回收每行时会重复注册/注销系统 observer；不是统一读取现成的页面状态。
- 确认的是订阅数量随已组合 Text 实例增长，不是每次重组重复注册：DisposableEffect key 稳定时不会再次注册。也没有证据证明已出现掉帧/ANR，不能把静态成本写成实测性能故障。
- 最小方向：宿主一次观察，CompositionLocal 下发实时 Boolean，marquee 仅读取；保留当前可实时关闭跑马灯的行为。

## 接线 / API 已核对，未发现新增明确错误

- `DetailCollapsedChrome` 唯一调用 `LibraryPage.kt:978-984` 已传 `onBack = onBack`；回调经 `MainActivity.kt:1804` hide keyboard → closeDetail，非空接线。ArrowBack AutoMirrored import、IconButton import、Box/size 均有定义/既有导入。必填参数放在有默认值参数之后并不自动构成 Kotlin 错误，调用方使用命名参数。
- LocalSharedCoverKey 并未缺供：`LibraryPage.kt:1025-1027` 在真实 hero item 内提供 `detail.id`。搜索两种歌单入口均先 arm 同一个 `pl.id`；`MainActivity.kt:2280-2285` 立即创建相同 id 的详情，不等待网络再挂载，随后异步补全。
- 范围边界：默认 Chart 分支是 `ChartDetailBanner`，今日推荐是 `TodayRecommendationHero`，并不调用本次挂 target 的 `DetailCoverImage`；不能把“提供了 Local key”当作所有详情都有封面共享动画。标准样式图像路径有 target。
- `UrlResolver.kt:640-641` 改为只对 PREFETCH/PROBE 检查 PLAY epoch，真实下载 `DownloadScheduler.kt:265-284` 使用独立 runtime、DOWNLOAD ticket、`requestStillCurrent = { !isCancelled() }`。owner/runtime 匹配与自有取消检查仍在 `UrlResolver.kt:670-683`。PLAY 的 `cancelSpeculativeUrlRequests` 只选择 PREFETCH/PROBE（`ScriptRuntime.kt:256-272`），未发现这处最小 diff 放行已取消/跨 owner 下载的证据。
- `BackupStore.kt:141` 添加的字面 key 与 `CrashUploadPrefs.KEY_AUTO_UPLOAD` 一致。export :44 与 restore :71 都对 settings 使用 redactSecrets=true，过滤器实际双向生效；restore 不清空 prefs，现有本机 consent 保留，而备份不能覆盖它。脚本 prefs 仍 redactSecrets=false，但上传开关读取的是 soundwave，未发现该旁路能授予 consent。

## 不确定 / 需要运行时或需求确认

1. 共享封面窗口坐标与详情父级推入叠加：`SharedCoverTransition.kt:80` 用 `boundsInWindow()` 取得目标，父 `rememberDetailPushFrame` 同时平移整页（`DetailPush.kt:77-81`）。目标坐标若包含当前父层 translation，后续帧是否重新定位回调、如何与保存的 origin 合成，可能造成路径漂移/结束跳变。不能仅从当前源码断言具体像素；建议在 clip 问题修完后做慢放录屏或记录首中尾帧坐标。
2. 新折叠顶栏左右占位由 42dp 改为 48dp，BarHeight 仍 56dp、页边距仍 14dp。按钮居中对称，但中间标题的可用宽度缩小，长标题省略阈值改变，播放圆本体仍 42dp，位置随新外槽移动。这是确认的几何变化；是否属于未授权布局回归，需主代理对照本轮“恢复返回键”的用户验收范围。并无页面高度增加证据。
3. `SharedCoverTransition` 本轮未修改；其 `remember(key)` 不含 reduce，已开始的动画不会仅因 reduce 改变而重新选 origin。属于新接线激活的既有 helper 行为，未做真机减少动效中途切换验证，非编译错误。
4. 源码未发现明显新增 unresolved import / 无效 Compose 参数，但不代表编译通过。没有 SDK / APK / 视觉证据；不宣称像素验收。

## 实际验证

- 工作树 `git diff --check`：退出 0，无空白错误。
- 只读 Python ad-hoc 源码断言：onBack 接线、Local key provider、target 追加顺序、Tab/Settings opaque 背景、DOWNLOAD ticket+取消回调、consent key 过滤均匹配；全部通过。断言证明源码形态，不证明 UI 效果或 Android API 执行。
- 仓库生产文件零写入；仅产出本报告。
