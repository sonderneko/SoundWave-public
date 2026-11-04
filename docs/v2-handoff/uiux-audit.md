# SoundWave v2 · UI/UX 精细度审计（1.0.675）

- 审计对象：`/workspace/work/sw`，分支 `hark/r675-sync`（HEAD `36596d7f`，代码等同已安装的 1.0.675）。只读，没有改文件，没有跑 Gradle。
- 路径简写：下文 `S/` = `app/src/main/java/xyz/cncnlp/soundwave/`，引用格式为 `文件:行`。
- 数量怎么来的：在源码里用 grep 或脚本统计（440 个 .kt，约 9.1 万行）。"按压方式归类"是按每处 clickable 前后 4 行判断的，属于**近似值**，误差大约 ±5 处。
- 对照基准：Material 3 motion/easing 规范、Android 触觉反馈规范、Apple HIG（Sheets / Action sheets / Alerts），以及 Apple Music、Spotify、网易云、QQ 音乐的公开做法。来源列在文末。
- 硬约束：只改交互层和视觉层，不改功能，不改布局结构。代码里带「（用户 10-18 / 10-19 / 10-20 / 10-22）」「照原型」注释的数值一律**冻结**，全仓约 70 处（比如设置开关 46×28、设置行 64dp、抽屉圆角 r28、全局珊瑚按压光晕、底栏样式）。本报告的改法只动这些数值背后的实现方式，不改用户已经确认过的外观。

---

## 总体结论（白话版）

这个 App 的"零件"质量其实不低：有统一的按压缩放、珊瑚光晕、减少动效开关、骨架屏、跟手下滑关播放器、错峰入场，好几个地方已经按 Material 3 的强调曲线在做。和大厂差的那"一点意思"，主要不是缺零件，而是三件事：

1. **同一种动作，在不同地方手感不一样。** 按下去，有的地方缩一下，有的缩两下，有的变色，有的要等约 0.1 秒才有反应。抽屉有好几种开法，有的会滑上来，有的直接蹦出来。
2. **几乎没有"手感"反馈。** 全 App 只有播放器里约 6 个按钮会震一下。点喜欢、拨开关、长按、拖动排序、拖到阈值，手上都没有任何感觉。大厂 App 的高级感有一半来自这一下"咔嗒"。
3. **能看出是拼起来的。** 系统原生对话框（灰白 Material 风格）和自家设计抽屉混着用。危险操作的红色有 3 种。提示条（Toast）183 处，又长又技术化，删除操作也没有"撤销"。

---

## 1) 按压反馈

### 现状证据
| 项目 | 数量 | 证据 |
|---|---|---|
| `.clickable` / `combinedClickable` | 362 / 43 | 全仓 grep |
| 走全局默认按压（`PressGlowIndication`：缩到 0.965 + 珊瑚光晕 520ms） | ≈213 | `S/Theme.kt:163-169` 用 `LocalIndication` 下发；`S/ui/common/PressGlowIndication.kt:93,95` |
| 写了 `indication = null` | 57 | 其中约 35 处既没有 pressScale 也没有 rippleGlow，自己另做反馈或者干脆没有反馈（如 `S/MiniPlayer.kt:138`、`S/BottomNav.kt:122`、`S/ui/settings/SettingsDetailKit.kt:121,167`） |
| `pressScale()` 自定义缩放 | 32 处，用了 **6 种比例**：0.92 / 0.94 / 0.96 / 0.965 / 0.98 / SCALE_ICON | `S/ui/common/PressFeedback.kt:34-42` 已经定义了 ROW 0.98 / DEFAULT 0.96 / ICON 0.92，但有 15 处直接写数字（`pressScale(0.94f)` ×5、`pressScale(0.92f)` ×5 等） |
| `rippleGlow()` 第二套光晕 | 16 | `S/ui/common/PressFeedback.kt:130` 起 |
| 自己写 `collectIsPressedAsState` 再做缩放或变色 | 19 个文件、37 处 | 比如底栏 0.97 + tween 100ms（`S/BottomNav.kt:119`，`S/ThemeTokens.kt:188`）、歌词卡 0.975 + 140ms（`ThemeTokens.LyricAction`）、设置行加深底色 220/650/180ms（`ThemeTokens.Settings`） |
| **叠加（同一个元素缩两次）** | ≈19 处 `pressScale` 后面跟着默认 clickable（默认指示又缩一次） | 典型：抽屉选项行 `S/ui/common/DesignSheet.kt:267` 先 0.98 再 0.965，实际约缩到 0.946；抽屉按钮 `S/ui/settings/SettingsValueSheets.kt:157-166` 是 pressScale + rippleGlow + 默认光晕**三重** |
| Material 灰色水波纹 `ripple()` | 1 | 基本已经替换干净 |
| 触觉反馈 | **只有 1 个调用点**：`PressFeedback.kt:94` 的 `KEYBOARD_TAP`，并且只在手指按下时触发 | 用到的地方只有 `PlayerControls.kt:187/190/198`（上一首/播放/下一首）和 `ui/player/DefaultPlayerParts.kt:210/232`（播放器图标钮和播放键），共 6 处 |

缺触觉的地方：点喜欢（`DefaultPlayerMainPage` 的粒子爆发没有配震动），设置开关（`SettingsDetailKit.kt:155` 的 `SettingsSwitch`），长按（27 个 `onLongClick`，没有一个震），拖动排序（`ScriptsPage.kt:386` 和 `ui/player/DefaultPlayerQueuePage.kt:224-238`），滑杆和步进（`SettingsValueSheets.kt` 3 个 Slider、`PlayerMenu.kt` 2 个、`MePage.kt` 2 个），播放器下拉越过关闭阈值（`FullPlayer.kt:670`），下拉刷新触发（`HomePage.kt:643` 等 4 处）。

### 和大厂差在哪（白话）
- 同样是"点一下"，列表行、抽屉行、按钮的下沉深度不一样，有的会"咚咚"缩两下，所以显得软、不干脆。
- 在可以滚动的列表里，只靠默认指示的行要等系统大约 100ms 的按压延迟才有反应（`PressFeedback.kt:28` 的注释里自己也写了），手指按下去感觉"慢半拍"。
- Apple Music 和 Spotify 点喜欢、拨开关、拖动排序都有清脆的一下震动。我们这里手上什么都感觉不到，所以显得"空"。

### 具体改法
- **一个元素只允许一个缩放来源。** 用了 `pressScale` 的地方，统一改成 `clickable(interactionSource, indication = PressGlowIndication.noScale(...))`，只保留光晕不缩放；或者反过来，不加 `pressScale`，交给默认指示。光晕颜色和透明度保持现值（浅色 0.16 / 深色 0.22，属于已确认样式）。
- 比例只用 3 档：列表行 **0.98**，卡片和封面 **0.96**，小图标钮 **0.92**。全局默认 0.965 是用户确认过的，保留，视为"卡片档"。15 处裸数字改用常量。
- 按下 `spring(1.0, 1800)`，松开 `spring(0.55, 400)`，最短可见 70ms：现值不变，只是全部改走 `PressMotion`。底栏的 tween 100 和歌词卡的 tween 140 改成同一套弹簧。
- 列表里的行一律加 `pressScale`（它是在指针最早阶段 Initial pass 观察的，没有 100ms 延迟），或者给默认指示补上"手指落下当帧"的预按态。
- 触觉规则见 B.3。原则是：**状态变了才震，按下本身不震**。播放键的震动从"按下"挪到"确认点击"。

**优先级：P0**

---

## 2) 动效体系

### 现状证据
| 项目 | 数量 / 证据 |
|---|---|
| `tween(` | 120 处。写死的时长有 **33 种**（90、120、160、180、200、210、220、240、260、280、300、320、350、360、380、420、460、520、540、600、620、650、700、780、800、900、1100、1400、1600、5500、16000、24000…），另有约 25 个具名常量 |
| `spring(` | 24 处，**17 种**不同的 damping/stiffness 组合（0.22/90、0.35/160、0.38/380、0.45/520、0.5/420、0.55/380、0.55/500、0.62/520、0.72/520、0.78/480、0.86/Low、1.0/1800…） |
| 曲线 | ≥11 种：Linear×35、FastOutSlowIn×11、LinearOutSlowIn×3、FastOutLinearIn×2、ease×3，以及 6 条 CubicBezier。其中 (0.05,0.7,0.1,1) 出现 5 次，就是 M3 的 emphasized-decelerate，这部分做得对 |
| 有没有 MotionTokens | **没有统一出口。** 动效参数分散在 11 个以上的地方：`PressMotion`、`ThemeTokens.Sheet`、`DetailPush`、`TabFadeThrough`（`ui/route/AppRootMainTabRouteRenderer.kt:27`）、`StaggerMotion`、`RippleGlow`、`NumberRollMotion`、`ThemeTokens.Navigation.Motion`、`ThemeTokens.Settings`、`ThemeTokens.LyricAction`、`PLAYER_*`（`PlayerLayout.kt:66-67`、`PlayerPageTransition.kt:19-21`） |
| 页面切换 | 没有用 NavHost。三套自定义转场：①详情推入 360ms emphasized-decelerate，退出 300ms accelerate（`ui/motion/DetailPush.kt:29-33`）；②设置子页 `AnimatedContent` 横推（`ui/motion/SettingsPush.kt:49-55`）；③主 Tab 渐隐渐显，出 90ms、进 210ms、缩放 0.96（`AppRootMainTabRouteRenderer.kt:28-31`） |
| 预测性返回（侧滑预览） | Manifest 已经开了 `enableOnBackInvokedCallback`，但只有首页分区 1 处用了 `PredictiveBackHandler`（`HomePage.kt:536`）。详情页和设置页侧滑时，不会跟着手指露出底下那一页 |
| 共享元素 | 封面飞行转场的开关是关着的：`FLIGHT_ENABLED = false`（`ui/motion/SharedCoverTransition.kt:35`）。播放器内部封面↔歌词有自研飞行封面（`ui/player/PlayerCoverSharedTransition.kt`） |
| 列表插入/删除 | `animateItem` 只用在 2 处（`ExtraPages.kt:1125` 播放历史、`LibraryPage.kt:2310` 排序抽屉）。22 个 LazyColumn 里，歌单详情、下载、队列、脚本、搜索结果在增删时都是"啪"地跳一下 |
| 骨架屏 / 加载 | 有首页 `FeedSkeleton`（`ui/home/HomeFeedStateViews.kt:78`）和 `TrackRowSkeleton`；其他地方用 `CircularProgressIndicator`，9 处 |
| 数字滚动 | 有 `RollingNumber`（`ui/common/NumberRoll.kt:42`），用在 3 个页面（MePage / Downloads / SettingsMaintenance） |
| 底栏切换 | tween 520ms，用"假弹簧"曲线 (0.34,1.56,0.64,1)（`ThemeTokens.kt:190-194`）。每次切换都会 `snapTo(0)` 从上一个 Tab 重新起跳（`BottomNav.kt:472`），**连点时指示块会闪跳**，不能从当前位置接着走 |
| 首屏错峰入场 | 每项 620ms，间隔 60ms，最多 8 级（`ui/common/StaggerEnter.kt:23-25`），全部落定约 1.1 秒。设置页每次进入都会重放（`MePage.kt:560,743`） |
| 迷你条 → 全屏播放器 | 迷你条上滑 8px 就**直接触发**打开（`MiniPlayer.kt:127`），不跟手。打开用 tween 360ms FastOutSlowIn，关闭用 tween **420ms**（`PlayerLayout.kt:66-67`，`MainActivity.kt:3278-3283`），**关比开还慢** |
| 全屏播放器下滑关闭 | 跟手、可打断，回弹用 `spring(0.78, 480)`（`FullPlayer.kt:677`），这是优点。但判断只看距离（`ui/player/PlayerDismissDecision.kt`，阈值 max(112dp, 屏高 18%)），**不看甩动速度**。拖动时页面只平移，不缩小也不变圆角（`FullPlayer.kt:753`） |
| 无限循环动画 | `rememberInfiniteTransition` 28 处，都已经接了减少动效和可见性开关（`MotionPolicy.kt`），这一点做得好 |

### 和大厂差在哪（白话）
- 节奏不统一：同样是"出现"，有 180、200、210、220、240、260ms 好几档，眼睛能感觉到"每个地方快慢不一样"。
- 手势不跟手：Apple Music 和 Spotify 的迷你条可以按住往上拖，播放器跟着手指一点点展开，轻轻一甩就打开或关上。我们这里一碰就自己弹出来，关的时候还比开的时候慢，所以有"拖泥带水"的感觉。
- 连点 Tab 时指示块会闪回再跑，显得"机械"。
- 删歌、加歌、排序完，列表直接跳变，不会滑动让位。

### 具体改法
- 建立 `ui/motion/MotionTokens.kt` 作为唯一出口（B.1）。已经被用户确认过的数值（抽屉 420/220、详情 360/300、Tab 90/210、Nav 520）**原样迁入**，只是不再散写。新代码禁止写裸数字 tween。
- 迷你条上滑改成**跟手**：在 `pointerInput` 里累计位移，驱动同一个 `playerDismissOffset`（0 → 屏高）。松手时 **速度 > 1000dp/s 或者位移 > 30%** 就打开，否则回弹。展开和收起统一用 `spring(dampingRatio = 0.9, stiffness = 380)`，并带上松手速度，这样随时可以打断、随时可以反向。
- 全屏下滑关闭加速度判断：速度 ≥ 1200dp/s 即使没过阈值也关闭。退出改成带速度的 `spring(0.9, 380)`，不再用 tween 420。拖动时可选地加 `scale = 1 − 0.06 × 进度`、圆角 0 → 24dp（类似 Apple Music 的卡片感）。这一条属于视觉变化，需要用户点头，列为可选。
- 底栏、Tab 指示块：用 `Animatable` 保存**当前视觉位置**，`animateTo(新位置, spring(0.8, 380))`，不再 `snapTo(0)`。现有的 520ms 和过冲幅度用弹簧逼近：0.8/380 的落定时间约 475ms，有轻微过冲。
- 列表：歌单详情、下载、队列、脚本、历史、搜索结果这些 `items(key=…)` 加上 `Modifier.animateItem(fadeInSpec = spring(1f, 1600f), placementSpec = spring(0.9f, 700f), fadeOutSpec = spring(1f, 1600f))`。
- 详情页和设置子页接入 `PredictiveBackHandler`：进度 0→1 对应顶层页右移 0 → 屏宽 25%、下层页从 −25% 回到 0，松手后用 DetailPush.EXIT 300ms 收尾，取消时 spring 回弹。
- 错峰入场：设置页只在**首次进入**时播放，返回时不重放。时长保持原型值不变。
- 共享封面飞行（`FLIGHT_ENABLED`）当初关掉应该有原因，可能是性能或闪烁。建议单独评估，本批不动。

**优先级：P0（Tokens、播放器手势），P1（底栏可打断、预测性返回），P2（animateItem）**

---

## 3) 弹窗 / 抽屉 / 对话框

### 现状证据：至少 6 套实现
| # | 实现 | 用量 | 进出动画 | 遮罩 | 圆角 | 把手 | 拖拽关闭 |
|---|---|---|---|---|---|---|---|
| 1 | `DesignSheetDialog`（`ui/common/DesignSheet.kt:88-145`） | 42 | 进 420ms emphasized，出 220ms | 黑 38% + API31 以上背景模糊 | 28 | 46×4 | **没有**（文件里搜不到 drag） |
| 2 | `SettingsPickerSheet`（`ui/settings/SettingsDetailKit.kt:247-305`） | 设置里的各种选择 | **没有入场动画**，直接出现 | 黑 42%（`:263`） | 26 | 40×5 | 没有 |
| 3 | 排序抽屉（`LibraryPage.kt:2298-2302`） | 1 | 没有 | 无单独遮罩 | 18 | 42×4 | 没有 |
| 4 | 平台 `Dialog` 居中卡片（`SettingsSheets.kt:66` 加歌单、`AccountUi.kt:238/325`、`LibraryPage.kt:2182`、`MainActivity.kt:1520`） | 6 | 平台默认 | 平台默认 | 18 等 | 无 | — |
| 5 | Material `AlertDialog` | 14（`AppDialogs.kt` 退出登录、`ScriptsPage.kt` 删除脚本、`PlayerMenu.kt` "更多"、`MainActivity.kt:1117` 检测报告等） | 平台默认 | 平台默认 | M3 默认 28 | 无 | — |
| 6 | Material `ModalBottomSheet` | 9（`ScriptIntervalSheet.kt:163`、HomePage、SearchPage） | M3 默认 | M3 默认 | M3 默认 | M3 把手 | 有 |

- 把手不一致：46×4 / 40×5 / 42×4 / M3 默认 32×4。
- 遮罩透明度出现了 0.38、0.42 两种，播放器相关另有 0.22 和 0.30。
- 危险色有 3 种：`0xFFB3261E`（`AppDialogs.kt:59`）、`DesignSheetDanger 0xFFB84F43`（`DesignSheet.kt:80`，17 处）、`colorScheme.error`（33 处）。
- 按钮层级：设计抽屉底部是"左描边次按钮 / 右实心主按钮"（`SettingsValueSheets.kt:151-170`），主按钮永远是黑底，**没有危险样式**。AlertDialog 是 M3 的"文字按钮靠右"。`ExtraPages.kt:1172` 又用 `SettingsSheetButton(danger = true)`。所以确认和取消的位置、样式有 3 种。
- 选项行不统一：`DesignSheetRow` 高 56dp、图标 21dp、15sp ExtraBold（`DesignSheet.kt:267-277`）；导入来源行高 48dp、图标 22dp、14sp SemiBold、间距 12dp（`AppDialogs.kt:69,110-122`）；设置选择卡圆角 13、勾 18dp（`SettingsDetailKit.kt:284,302`）；歌词操作卡 98dp 高（`ThemeTokens.LyricAction`）。勾选标记有 Check 图标 19dp、18dp 和 M3 RadioButton 几种。分隔线有 `FrostDivider` 1dp（磨砂开启时隐藏）和 `SettingsRowDivider`（从 63dp 处开始）。

### 和大厂差在哪（白话）
- 抽屉顶上有一根"把手"，看起来能拖，**手一拖却拖不动**，只能点空白处关。iOS 和网易云的抽屉都能顺手往下一划就关。
- 同样是"选一个选项"，有时从底下滑上来，有时直接蹦出来，有时是屏幕中间一个灰白系统框，一眼能看出不是同一个设计师做的。
- "删除""退出登录"这类危险按钮，有时是红字，有时是黑底大按钮，有时是另一种红，用户要多想一下才能确认自己点的是什么。

### 具体改法
- **只留一个抽屉壳**：`DesignSheetDialog`（r28、遮罩 38%、420/220ms 都是已确认值）。`SettingsPickerSheet` 和排序抽屉保留各自内容，只把外壳换成它，这样就自动有入场动画、统一把手和统一遮罩。9 个 `ModalBottomSheet` 也换壳。
- **把手可拖**：在 `DesignSheetPanel` 上加 `anchoredDraggable` 或自己写 `pointerInput`。只允许向下拖，阻尼 1:1。松手时 **位移 > 面板高 25% 或速度 > 1000dp/s** 就关闭（走 220ms exit），否则 `spring(0.9, 700)` 回弹。把手热区 ≥ 48×24dp。内容区可以滚动时，先滚内容，滚到顶再交给拖拽（nestedScroll）。
- **确认类对话框统一成 `DesignConfirmSheet`**，用来替换 14 个 AlertDialog：眉题 + 标题 24sp + 正文 14sp/20sp 行高 + 底部按钮行。规则：**取消在左（描边），确认在右（实心）**；确认是危险操作时，实心按钮用 `Danger` 色、白字。只有"知道了"一个按钮时，用整宽实心按钮。
- **危险色只留一个 token**：`Danger = 0xFFB84F43`，已经在 17 处使用，和珊瑚主色和谐。`0xFFB3261E` 和用于危险按钮的 `colorScheme.error` 都改成指向它。
- **选项行只留 2 种**：动作行用 `DesignSheetRow`（56dp，用户已确认）；单选行在它基础上右侧加 20dp 勾，选中项文字变珊瑚色。导入来源行改用 `DesignSheetRow`，高 48 → 56dp，属于视觉统一。分隔线统一用 `designLineColor()` 1dp，从文字起点开始，左侧缩进 = 图标 21 + 间距 18 + 起始 4 = 43dp。

**优先级：P0（抽屉拖拽 + 统一壳），P1（确认框与危险色）**

---

## 4) 设置页

### 现状证据
- 主页有 **7 组、25 行**（`MePage.kt:746-830`）：播放与歌词 4｜外观与界面 4｜音频 4（含一行"智能过渡：尚未启用 · 当前不可用，不是开关" `:786`）｜音源与搜索 3｜下载管理 2｜数据与同步 5｜服务与支持 3。
- 层级：主页 → 子页（preload / lyrics / theme / dock / search / download / smart / scripts / cache / crash / stats / history / backup / about / data_flow / feedback / notifications），最深 3 层（设置 → 听歌统计 → 播放历史；设置 → 关于 → 数据流向）。
- 歌词子页是**一个 13 行的长列表，中间没有小标题**（`MePage.kt:858-1080`）：应用内歌词 4 项和悬浮歌词 8 项混在一起，最后还有歌词字体。
- 三类行**看起来几乎一样**：
  - 跳转行 `NavRow`：右侧是 ›；
  - 值行 `ValueRow`：右侧"值 + ›"，点了**弹出抽屉**；
  - 循环行 `EditorialValueRow`：右侧也是"值 + ›"，点了却**直接切换到下一个值**（`MePage.kt:669-681` 和 `SettingsValueChip(picker=false)`，`SettingsDetailKit.kt:205-215`）。播放器界面样式（`:765`）、详情页封面样式（`:770`）、全局字体（`:1090`）、歌词字体（`:1077`）、下载文件名（`:1158`）这 5 项都是这种"点一下就悄悄变了"。
  - 开关行用自绘 `SettingsSwitch` 46×28（用户确认样式）。有轻弹簧 0.62/520，**没有触觉**，按住时滑块也不会拉伸。
- 选择芯片里的值只有 **10sp**（`SettingsDetailKit.kt:227`），比正文小两档。
- 没有设置搜索，没有"恢复默认"（全仓只有歌词修正有恢复默认）。"减少动效"藏在"主题"里（`:1103`）。"听歌统计 / 播放历史"其实是内容，却放在设置里。

### 和大厂差在哪（白话）
- 用户分不清哪一行点了会进新页面、哪一行会弹出选择、哪一行会直接改掉，容易"手滑改了还不知道"。iOS 设置的规矩很清楚：› 表示进下一页；右侧灰字值表示弹出选择；开关表示立刻生效。
- 歌词页一屏十几项没有分段，找"悬浮歌词透明度"要从头扫到尾。
- 一行写着"尚未启用、不是开关"，让人感觉产品没做完。

### 具体改法（只动交互和视觉，不动结构）
- 5 个循环行改成"点了弹出 `SettingsPickerSheet` 让用户选"，**选项内容不变**。值芯片字号 10 → **12sp**（13sp 行高 16）。三类行的右侧统一成：跳转 = 只有 ›（15×21）；选择 = 灰色值 13sp + 竖向小 ⌄ 13dp；开关 = Switch。这样一眼能区分。
- 开关：按下时滑块宽 22 → 26dp（用 `spring(0.9, 1400)`），松开回 22，切换时触发 `TOGGLE_ON` / `TOGGLE_OFF` 触觉。外观尺寸不变。
- "智能过渡"行：在功能可用之前，把说明并进"淡入淡出"抽屉里展示，主页不单独占一行。这一条要用户确认，否则保留。

### 重分组方案（建议稿，涉及结构，**需用户拍板，未放进本批任务**）
对照：iOS 设置每组 3–6 项、组名就是名词、顶部有搜索；网易云手机版设置里有「播放和下载 → 下载音质」「音质 → 播放/下载」这类按"任务"分组（来源见文末，属于第三方教程转述）。

| 新分组 | 行（›=跳转，◇=选择，○=开关） | 项数 |
|---|---|---|
| 播放 | 在线播放音质◇｜淡入淡出◇（智能过渡说明并入）｜播放速度◇｜均衡器◇｜睡眠定时◇｜预解析› | 6 |
| 歌词 | 歌词显示›（翻译/罗马音/逐字高亮/当前词上浮/歌词字体）｜悬浮歌词›（开关/锁定/行数/字号/透明度×2/宽度/颜色） | 2 |
| 外观 | 主题◇（纯白/夜间/自适应）｜全局字体◇｜播放器样式◇｜详情页封面◇｜底栏与迷你条›｜透明磨砂›｜减少动效○ | 7 |
| 音源与搜索 | 智能换源›｜音源脚本›｜跨平台搜索○ | 3 |
| 下载 | 下载音质◇｜下载设置›（多线程/封面/歌词/文件名）｜查看下载› | 3 |
| 存储与数据 | 缓存管理›｜备份与恢复›｜数据流向› | 3 |
| 帮助与关于 | 一键反馈›｜崩溃诊断›｜关于 APP› | 3 |
| （移到"我的"页） | 听歌统计、播放历史 | — |

另外建议：顶部加"搜索设置"，按行标题和副标题做本地匹配；每个子页底部加"恢复本页默认"文字按钮，危险色，点了二次确认。

**优先级：P1（三类行区分 + 循环改选择 + 开关手感），重分组待定**

---

## 5) 布局与排版

### 现状证据
- **字号 43 种**（671 处 `fontSize`），里面有 11.5 / 12.5 / 9.5 / 13.5 / 39.6 这种半档。**12sp 以下（≤11sp）有 270 处，约占 40%**：11sp×127、10sp×86、9sp×22、8sp×16、11.5×15、9.5×4。行高 35 种。
- 字重：Bold 224、ExtraBold 79、SemiBold 79、Medium 93、Normal 只有 9。**几乎全是粗体**，粗细拉不开层级。
- 字体 4 款：Manrope 177、DmMono 107、Newsreader 20、SerifCJK 6。属于原型设定，保留。
- 间距：padding / Spacer / spacedBy 一共 2239 处、45 种值。**符合 4 的倍数只有 40.5%**，偶数 86%。高频的非 4 倍数：14dp×303、10dp×218、6dp×215、18dp×92、3dp×91、5dp×71、7dp×37、22dp×36。
- 圆角 25 种：999/99（胶囊）、12、16、14、10、18、8、28、24、22、20、11、23、17、13、5、4、2、9、3、7、6、26、15。
- 固定高度 `.height(N.dp)` 有 319 处，`heightIn(min)` 只有 58 处。`maxLines = 1` 有 323 处，系统字体调大后容易被截断（见 8）。
- 空状态：`EmptyHint` 只是一行 13sp 灰字（`ListChrome.kt:312-316`，12 处用），没有图标、没有按钮。首页 `FeedEmptySlot` 有重试按钮（`ui/home/HomeFeedStateViews.kt:46`）。所以空状态有两种样子。
- 文案不统一："暂无歌单 / 还没有导入歌单 / 暂无脚本，请到音源脚本页导入。/ 还没有脚本，请导入本地文件或链接"，有的带句号有的不带；网络错误有 6 种说法（"网络请求失败，请检查网络后重试 / 网络异常 / 网络连接中断 / 网络传输失败，请检查网络连接。/ 网络中断，网络恢复后自动重试…"）。

### 和大厂差在哪（白话）
- 字太多档、太多小字、全是粗体，看起来"挤"和"吵"。Apple Music 正文大多是 13–17pt，层级只有 5–6 档，靠"粗/常规 + 灰度"来分主次。
- 间距 14、10、6、18、5、3 混用，对齐线不齐，细看是"差不多齐"。
- 空页面只有一行灰字，不告诉用户下一步能干什么。

### 具体改法
- 字号收成 **9 档**（B.7）。新代码只能用 token。存量文件里没有被用户冻结的，逐步迁移：半档 .5sp 先映射到相邻档，≤9sp 只留给角标。
- 字重规则：标题 ExtraBold（原型），正文和行标题 **SemiBold**，说明文字 **Medium**，数值 Bold。减少"满屏 Bold"。
- 间距 token 用 4 档栅格加 2 个例外（B.6）。14dp 已经是原型里的页边距，保留作为 `PageGutter` 例外；新出现的 3/5/7/9/11/13 不再允许。
- 统一状态组件 `StateBlock`：48dp 线性图标 + 15sp SemiBold 主句 + 13sp Muted 副句 + 可选 40dp 高胶囊按钮，上下间距 32dp。文案规则："暂无 X"做主句，副句说"能做什么"，不加句号；网络错误统一成"网络不太稳定" + 按钮"重试"。

**优先级：P2（体量大，按文件逐步迁）**

---

## 6) 视觉反馈

### 现状证据
- `Toast.makeText` 183 处，分布在 23 个文件，最多的是 `MainActivity.kt` 105 处、`PlaybackController.kt` 19 处、`ExtraPages.kt` 17 处、`FullPlayer.kt` 14 处。**Snackbar 0 处。**
- 其中 `LENGTH_LONG` 63 处。154 条字面文案平均 15 字，**超过 20 字的有 45 条**。有 18 处把 `e.message` 原样拼进去（比如"导出失败: ${e.message}"）。冒号半角 5 处、全角 19 处混用。文案技术化，例如"本机安全检查未放行清理，未退出""云同步门禁仍保留""持久化头像隔离标记"。
- 撤销：**全仓没有任何 undo 能力**。删歌、删下载、清空历史、移出歌单都是"确认 → 立即删除"（`DownloadsPage.kt:238,254`、`LibraryPage.kt:2174-2176`、`ExtraPages.kt:1169-1172`）。播放历史左滑删除已经带速度判断（`ExtraPages.kt:979`），但删了就是删了。
- 下拉刷新：只有首页 4 处 `PullToRefreshBox`，用了自绘蜘蛛和唱片指示器（`ui/common/SpiderRefreshIndicator.kt`、`VinylRefreshIndicator.kt`）。音乐库、歌单详情、下载页不能下拉刷新。
- 乐观更新：喜欢是本地同步写入（`LocalStore.kt:174`），表现是即时的。

### 和大厂差在哪（白话）
- 系统 Toast 是灰色小条，位置、样式不受 App 控制，又长又技术化，还会叠成一串。Spotify 和 Apple Music 用的是自家风格的底部小条，一句话说清楚，删除后给个"撤销"。
- 不能撤销，所以每个删除都只好先弹确认框，多点一步。

### 具体改法
- 新增 `AppSnackbar`（宿主放在 `AppOverlayHostLayer` 同一层）：胶囊圆角 999，高 48dp，左右边距 16dp，位置在迷你条上方 8dp；文字 14sp SemiBold，最多 1 行；可选右侧"撤销"按钮（珊瑚色 14sp Bold）。进场从下往上 16dp + 淡入，`spring(0.9, 700)`；4 秒后（带按钮时 5 秒）淡出 200ms。成功配 `CONFIRM` 触觉，失败配 `REJECT`。
- 文案规范：≤ 14 个汉字，动词开头（"已加入下载""已删除 3 首"），**不拼异常原文**（原文写进诊断日志），全角标点，不用句号。
- 撤销：删歌单歌曲、删下载记录、清空历史、移出歌单这 4 类改成"先隐藏 + 显示带撤销的 Snackbar，5 秒后才真正执行"。数据层只需要延迟提交，不改功能语义。有撤销兜底之后，对应的确认抽屉可以去掉；"删除文件"（不可恢复）的确认保留。
- 迁移顺序：先替换 `MainActivity.kt` 的 105 处，用一个 `showMessage()` 收口，Toast 作为不在前台时的兜底。

**优先级：P1**

---

## 7) 图标

### 现状证据
- 来源混用：Material Icons 108 处（Outlined 75、Filled 24、Rounded 5、AutoMirrored 4），分布在 25 个文件，最多的是 LibraryPage 16、DetailDesignPages 12、TodayRecommendationDesign 7、PlayerMenu 7、ScriptsPage 6。另有自绘线性图标 8 个文件约 1100 行（`ui/common/DesignLineGlyphs.kt` 等，`WhiteGlyph.kt`）。
- 线宽：自绘图标大多是 1.8（11 处）；也有 1.75（2）、1.6、1.9、1.0，以及 Canvas 上的 1.15 / 1.35 / 1.5 / 2.2 / 3.2。Material Outlined 在 24 网格上约是 2dp 实线。**同一屏里粗细不一**，比如设置主页用自绘 1.8，`AppDialogs.kt` 导入来源用的是 Material `Outlined.Link` / `FolderOpen`。
- 尺寸 17 种：20、18、22、16、17、21、15、13、12、28、24、23、19、14、11、30、34、36、56。

### 和大厂差在哪（白话）
- 有的图标细、有的粗，有的圆头有的方头，放在一起像"不是一套"。Apple Music 全用 SF Symbols、Spotify 全用自家 Encore 图标，粗细都是统一的。

### 具体改法
- 规定"**自绘线性 1.8 线宽、圆端点、24 网格**"为唯一风格。剩下的 108 处 Material 图标，优先替换成已有的自绘同义图标，没有同义的补画。实心图标只用于"选中态"，比如喜欢后的实心心形、当前 Tab。
- 尺寸 5 档：12（角标）、16（行内说明）、20（列表和抽屉）、24（工具栏）、28（播放器次级）。已确认的 21dp 设置图标、播放器 34/50 保留为例外。

**优先级：P2**

---

## 8) 无障碍与系统偏好

### 现状证据
- 减少动效：同时读系统"动画时长缩放 = 0"和 App 内开关（`MotionPolicy.kt:33-75`），用 CompositionLocal 下发。按压缩放、光晕、抽屉、Tab、装饰循环都已经接入。**这部分做得比很多大厂还细。** 不足的是只有缩放等于 0 才算"减少"，系统设成 0.5x 时不会缩短自定义的 Animatable/tween。
- 字体缩放：`fontScale` 只在 19 处被拿来手算布局（`DetailDesignPages.kt:271-276`、`LibrarySleeveCards.kt:96-104,208`）。319 处固定 dp 高度加 323 处 `maxLines = 1`，在 1.3x 字体下标题和抽屉行容易被截断或溢出（比如 `DesignSheetRow` 固定 56dp）。
- 对比度（WCAG 计算）：浅色主题下珊瑚 `#FF5C45` 在纸色 `#F4F4F0` 上只有 **2.77:1**。用作小字（眉题 10–11sp、选择值、"清空"等）低于 4.5:1。白字放在珊瑚底上是 3.06:1。Muted `#686D68` 是 4.79:1，合格。夜间珊瑚是 6.15:1，合格。
- 语义：`contentDescription` 212 处，写了文字描述的 68 处；`Icon(…, null)` 95 处，`contentDescription = null` 27 处，大多数是装饰图标，合理。`Role.` 182 处，`stateDescription` 16 处，`heading()` **只有 2 处**，`liveRegion` 4 处。最小触控尺寸只有导入来源行写死了 48dp，没有统一使用 `minimumInteractiveComponentSize`。

### 和大厂差在哪（白话）
- 把系统字体调大的用户（很多长辈）会看到字被切掉。浅色主题下珊瑚色小字偏淡，看不太清。读屏用户在设置页里没法按"分组标题"跳转。

### 具体改法
- 文字所在的行一律 `heightIn(min = 原高度)` 代替 `height()`；`maxLines = 1` 的标题在 fontScale ≥ 1.3 时放宽到 2 行。
- **浅色主题下 < 14sp 的珊瑚文字**改用"文字专用珊瑚" `AccentText = #C8381F`（纸色上 4.71:1、白底上 5.2:1）。大字、图标、填充继续用 `#FF5C45`。这一条会改变已确认的颜色观感，**需要用户确认**。
- 设置分组标题、抽屉标题、页头加 `semantics { heading() }`；Snackbar 加 `liveRegion = Polite`。
- 减少动效：读取 `ANIMATOR_DURATION_SCALE`，把它乘到自定义 tween 的时长上（0.5x 时缩短一半）。

**优先级：P1（字体缩放截断、heading），P2（对比度，需要确认）**

---

# A. 十大最影响高级感的点（按投入产出比排序）

| # | 问题（用户感受） | 改法要点 | 投入 | 收益 |
|---|---|---|---|---|
| 1 | **点了"没手感"**：只有播放器 6 个按钮会震 | 加一个 `AppHaptics` 封装，接入喜欢、开关、长按、拖动排序、步进吸附、下拉阈值、成功/失败（B.3） | 小（约 15 个调用点） | 很大 |
| 2 | **按下去"软"、深浅不一**：19 处缩两次，6 种比例，列表行慢半拍 | 一个元素一个缩放源；3 档比例；列表行改用无延迟的 pressScale | 小 | 很大 |
| 3 | **抽屉把手拖不动**，6 套弹窗各开各的 | DesignSheet 加拖拽关闭（25% 或 1000dp/s）；其余换壳 | 中 | 很大 |
| 4 | **设置行看着一样、行为不一样**（5 项点了就悄悄变） | 循环改为弹出选择；三类行右侧样式区分；值字号 10 → 12sp | 小 | 大 |
| 5 | **系统灰框混在设计里**，危险红有 3 种 | 14 个 AlertDialog 换成 DesignConfirmSheet；危险色只留 B84F43；取消左、确认右 | 中 | 大 |
| 6 | **提示又长又技术化、删了不能撤销** | AppSnackbar + 文案规范 + 4 类删除可撤销 | 中 | 大 |
| 7 | **播放器一碰就弹出，关得比开还慢** | 迷你条上滑跟手；按速度判断甩动；用 spring 收放 | 中 | 大 |
| 8 | **节奏乱**：约 40 种时长、17 种弹簧，连点底栏会闪 | MotionTokens 单一出口；底栏从当前位置用 spring 起跳 | 中 | 中 |
| 9 | **侧滑返回不跟手、列表增删会跳** | 详情页和设置页接入预测性返回；6 个主列表加 animateItem | 中 | 中 |
| 10 | **字太碎、太小、太粗，空页面只有一行灰字** | 9 档字号、字重规则、StateBlock 统一状态 | 大 | 中 |

---

# B. UI 规范草案（可直接落地的 token 表）

> 说明：**粗体值**表示沿用用户已确认或原型的现值，只是迁进 token；其余是建议新值。Compose BOM 2024.09.02 对应 material3 1.3，**没有 `MotionScheme`**，所以下面的 token 用自建 object 实现。弹簧参数参考 M3 的 standard/expressive 弹簧：standard fast 0.9/1400、default 0.9/700、slow 0.9/300；expressive default 0.8/380；effects 1.0/1600。

### B.1 Motion（`ui/motion/MotionTokens.kt`）
| Token | 值 | 用途 |
|---|---|---|
| `Duration.Micro` | 90ms | 颜色、透明度的微变化，**Tab 出场 90** |
| `Duration.Short` | 150ms | 图标替换、小元素淡入 |
| `Duration.Medium` | 250ms | 卡片展开、行高变化 |
| `Duration.Enter` | **360ms** | 详情推入、设置子页推入、播放器打开（tween 兜底） |
| `Duration.SheetEnter` | **420ms** | 抽屉进场 |
| `Duration.Exit` | **220ms** | 抽屉和浮层退场（退场必须 ≤ 进场的 60%） |
| `Duration.PageExit` | **300ms** | 详情返回 |
| `Duration.Stagger` / `Step` | **620ms / 60ms，最多 8 级** | 首屏错峰，只播一次 |
| `Easing.Emphasized` | (0.2, 0, 0, 1) | 在屏内移动 |
| `Easing.EmphasizedDecelerate` | **(0.05, 0.7, 0.1, 1)** | 进入屏幕 |
| `Easing.EmphasizedAccelerate` | **(0.3, 0, 0.8, 0.15)** | 离开屏幕 |
| `Easing.Linear` | Linear | 只用于进度、循环、光晕扩散 |
| `Spring.Press` | **1.0 / 1800** | 按下 |
| `Spring.Release` | **0.55 / 400** | 松开回弹 |
| `Spring.FastSpatial` | 0.9 / 1400 | 开关滑块、芯片、小图标位移 |
| `Spring.DefaultSpatial` | 0.85 / 500（**播放器回弹现值 0.78/480 可保留**） | 抽屉回弹、列表项让位、指示块 |
| `Spring.SlowSpatial` | 0.9 / 380 | 全屏播放器开合（带松手速度） |
| `Spring.Expressive` | 0.8 / 380 | 底栏指示块（替代 520ms 假弹簧曲线） |
| `Spring.Pop` | 0.5 / 600 | 喜欢心形放大 1.0 → 1.18 → 1.0 |
| `Spring.Effects` | 1.0 / 1600 | 透明度、颜色、遮罩 |
| `ReduceMotion` | 位移类改成 120ms Linear 淡入淡出；缩放和光晕关闭；时长 × 系统动画倍率 | 全局 |

### B.2 Press（`PressMotion`）
| Token | 值 |
|---|---|
| `Scale.Row` | **0.98**（整行、抽屉行、设置行） |
| `Scale.Card` | **0.965**（全局默认指示）/ 0.96（封面卡片） |
| `Scale.Icon` | **0.92**（圆形图标钮、播放键） |
| `MinVisible` | **70ms** |
| `Glow` | 珊瑚，浅色 **α0.16** / 深色 **α0.22**，扩散 **520ms** |
| `RowTint` | 设置行加深：**initial 0.04/0.06 → held 0.10/0.14**，加深 **220ms**，松开 **180ms** |
| 规则 | 每个可点元素**只有一个**缩放来源；`indication = null` 时必须自带 pressScale 或 RowTint；禁止 pressScale + 默认指示叠加 |

### B.3 Haptic（新增 `ui/common/AppHaptics.kt`；minSdk 26，API 34 以下自动降级）
| 场景 | 常量（API 34+） | 降级 | 触发时机 |
|---|---|---|---|
| 播放/暂停、上一首/下一首 | `KEYBOARD_TAP`（现状） | 同左 | **确认点击时**（现状是按下时） |
| 喜欢 / 收藏 | `CONFIRM`（API 30+） | `CONTEXT_CLICK` | 状态由未选变已选 |
| 取消喜欢 | `CLOCK_TICK` | 同左 | 状态由已选变未选 |
| 开关 | `TOGGLE_ON` / `TOGGLE_OFF` | `CLOCK_TICK` | 值改变后 |
| 长按出菜单 / 多选 | `LONG_PRESS` | 同左 | 长按识别成功时 |
| 拖动排序：拿起 / 跨过一格 / 放下 | `DRAG_START` / `SEGMENT_TICK` / `GESTURE_END` | `LONG_PRESS` / `CLOCK_TICK` / 无 | 每次交换位置时震一下 |
| 滑杆、步进吸附（速度、定时、数量） | `SEGMENT_FREQUENT_TICK` | `CLOCK_TICK` | 每跨一个刻度，**节流 ≥ 40ms** |
| 播放器下拉、抽屉下拉、下拉刷新越过阈值 | `GESTURE_THRESHOLD_ACTIVATE` | `CONTEXT_CLICK` | 越过阈值时一次；退回阈值以内用 `GESTURE_THRESHOLD_DEACTIVATE` |
| 操作成功（导入、删除完成） | `CONFIRM` | `CONTEXT_CLICK` | 和 Snackbar 同时 |
| 操作失败 | `REJECT`（API 30+） | `LONG_PRESS` | 和失败 Snackbar 同时 |
| 规则 | 只用 `View.performHapticFeedback`（会跟随系统触觉开关）；滚动中不震；一个手势最多 1 次阈值震动 | | |

### B.4 Sheet（`ThemeTokens.Sheet`，唯一外壳 `DesignSheetDialog`）
| Token | 值 |
|---|---|
| Radius | **28dp**（顶部两角） |
| Scrim | **黑 38%**，跟随进度淡入；API 31+ 背景模糊沿用 `frostedSheetBlurDp()` |
| Enter / Exit | **420ms EmphasizedDecelerate / 220ms FastOutLinearIn** |
| Handle | **46×4dp**，r4，`designLineColor()`；热区 ≥ 48×24dp，可拖 |
| Drag-dismiss | 位移 > 面板高 25% **或**速度 > 1000dp/s 时关闭；否则 `Spring.DefaultSpatial` 回弹；越过阈值震一次 |
| Padding | 左右 **26**，上 **16**，下 **14** + 导航栏；把手到标题 **30** |
| Header | 眉题 **11sp DmMono 珊瑚 0.18em**，标题 **24/30sp ExtraBold** |
| Row | **56dp**，图标 **21dp**，图标到文字 **18**，标题 **15sp ExtraBold**，右侧说明 12sp |
| Divider | 1dp `designLineColor()`，从文字起点开始（左缩进 43dp） |
| Buttons | 高 48，r999，间距 10；**取消在左（描边）/ 确认在右（实心）**；危险确认 = `Danger #B84F43` 底白字 |
| Danger | `#B84F43`（唯一） |
| 单选 | 选中行文字珊瑚色 + 右侧 Check 20dp |

### B.5 SettingsRow
| 类型 | 右侧样式 | 点击行为 | 尺寸 |
|---|---|---|---|
| 跳转 Nav | 只有 ›（15×21dp，Muted） | 推入子页 | 行 **64dp** |
| 选择 Value | 值 **13sp Bold Muted** + ⌄ 13dp | 弹出 `SettingsPickerSheet` | 值最大宽 160dp |
| 开关 Toggle | **SettingsSwitch 46×28**，按下滑块 22 → 26dp | 立即切换 + TOGGLE 触觉 | — |
| 动作 Action | 无图标，文字珊瑚色；危险项用 Danger 色 | 执行或确认 | — |
| 通用 | 左右 **7dp**，图标区 **30×34** 内放 **21dp** 图标，标题 **16/21sp**，眉题 **10/12sp**，分隔线从 **63dp** 处开始，组间距 **28–34dp** | | |

### B.6 Spacing
| Token | 值 | 说明 |
|---|---|---|
| `Space.xxs` | 2dp | 文字与角标 |
| `Space.xs` | 4dp | 图标与文字（紧凑） |
| `Space.s` | 8dp | 行内元素 |
| `Space.m` | 12dp | 卡片内边距（紧凑） |
| `Space.l` | 16dp | 卡片内边距、模块内 |
| `Space.xl` | 24dp | 模块之间 |
| `Space.xxl` | 32dp | 分组之间、空状态 |
| 例外 `PageGutter` | **14dp** | 原型页边距 |
| 例外 `SheetGutter` | **26dp** | 抽屉页边距 |
| 规则 | 新代码只能用上面的值；3/5/7/9/11/13 只允许出现在带「用户确认」注释的冻结处 | |

### B.7 Type（9 档；Manrope 正文 / DmMono 眉题与数字 / Newsreader 大标题）
| Token | 字号 / 行高 | 字重 | 用途 |
|---|---|---|---|
| `Display` | 31/38 | Newsreader Medium | 页头大标题（**设置页 31**） |
| `Title` | 24/30 | ExtraBold | 抽屉标题、详情标题 |
| `Headline` | 18/24 | ExtraBold | 模块标题 |
| `BodyStrong` | 16/21 | SemiBold | 设置行标题 |
| `Body` | 15/21 | SemiBold | 列表主标题、抽屉行（抽屉行沿用 ExtraBold） |
| `BodySmall` | 13/17 | Medium | 副标题、说明、值 |
| `Caption` | 12/16 | Medium | 次要说明、时间 |
| `Micro` | 11/14 | Medium | 眉题、标签 |
| `Badge` | 9/10 | Bold | 角标（无损、新） |
| 规则 | 不用 .5sp；≤ 11sp 只用于眉题和角标；一屏最多 4 档 | | |

---

# C. 计划任务（SW-12-01 ~ SW-12-15）

约束：只改交互和视觉层，不改功能与布局结构；带「用户 10-xx / 照原型」注释的数值冻结。同样的内容也写在 `/workspace/work/analysis/uiux-tasks.json`。

| ID | 标题 | 优先级 | 执行范围 | 验收标准 |
|---|---|---|---|---|
| SW-12-01 | 按压反馈去叠加、统一三档比例 | P0 | `ui/common/PressFeedback.kt`、`PressGlowIndication.kt`、`DesignSheet.kt:267`、`ui/settings/SettingsValueSheets.kt:157-166`，以及 pressScale 和默认 clickable 同时出现的约 19 处；15 处裸比例改用常量 | grep 找不到同一元素上 pressScale 与默认指示并存；pressScale 不再有裸数字；抽屉行按下实测缩放 = 0.98±0.005；列表行按下后首帧（<16ms）开始缩放；光晕颜色和透明度不变 |
| SW-12-02 | 建立 AppHaptics 并接入关键交互 | P0 | 新增 `ui/common/AppHaptics.kt`（B.3），接入：喜欢、SettingsSwitch、27 个 onLongClick、脚本和队列拖动排序、Slider/步进、播放器下拉阈值、下拉刷新阈值、导入成功/失败；播放键震动改到确认点击时 | API 34 和 API 30 真机上各场景都有对应触感；系统关闭触觉后不震；滚动列表时不误震；拖动排序每换一格震一次；单个手势阈值震动不超过 1 次 |
| SW-12-03 | DesignSheet 把手可拖拽关闭 | P0 | `ui/common/DesignSheet.kt`（DesignSheetPanel / DesignSheetDialog），内容可滚动时接 nestedScroll | 向下拖超过 25% 面板高或速度 > 1000dp/s 时关闭，否则弹簧回弹；拖动中可反向；内容滚到顶后才开始拖面板；减少动效下无回弹动画；42 个调用点无回归 |
| SW-12-04 | 其余抽屉换成统一外壳 | P0 | `SettingsPickerSheet`（`SettingsDetailKit.kt:247`）、排序抽屉（`LibraryPage.kt:2298`）、9 个 ModalBottomSheet；内容与选项不变 | 全仓只剩 DesignSheetDialog 一种底部抽屉外壳；圆角 28、遮罩 38%、把手 46×4、进出 420/220ms 一致；SettingsPickerSheet 有入场动画 |
| SW-12-05 | MotionTokens 单一出口 | P0 | 新增 `ui/motion/MotionTokens.kt`（B.1）；把 PressMotion、SheetMotion、DetailPush、TabFadeThrough、StaggerMotion、Navigation.Motion、PLAYER_* 改为引用；已确认数值原样迁入 | 动效相关 tween/spring 不再写裸数字（装饰循环除外）；裸时长从约 33 种降到 ≤ 10 种；逐屏录屏对比，已确认动效无可见差异 |
| SW-12-06 | 迷你条上滑跟手，播放器按速度开合 | P1 | `MiniPlayer.kt:119-130`、`MainActivity.kt:3276-3284`、`FullPlayer.kt:668-712`、`PlayerDismissDecision.kt` | 迷你条上滑时播放器跟着手指展开；松手按速度（>1000dp/s）或位移（>30%）决定开或关；下滑速度 ≥ 1200dp/s 时即使没过阈值也关闭；开合中途可以反向；退场时长 ≤ 进场；越过阈值震一次 |
| SW-12-07 | 底栏和 Tab 指示可打断 | P1 | `BottomNav.kt:460-478`、`ThemeTokens.Navigation.Motion` | 连点 3 个 Tab 时指示块从当前视觉位置连续移动，不闪回；用 spring(0.8, 380) 时视觉过冲与现版接近（录屏对比）；减少动效路径不变 |
| SW-12-08 | 确认对话框统一 + 危险色唯一 | P1 | 14 个 AlertDialog 改成新的 `DesignConfirmSheet`；`AppDialogs.kt:59`、`colorScheme.error` 用作危险操作的地方、`SettingsSheetButton(danger)` 改成指向 `Danger #B84F43`；SettingsSheetButtons 增加 danger 主按钮样式 | 全仓没有 Material AlertDialog；危险按钮只有一种颜色；所有确认框都是"取消左 / 确认右"；危险确认是红底白字 |
| SW-12-09 | 设置行三类可区分，循环改为选择 | P1 | `MePage.kt:669-681,765,770,1077,1090,1158`、`SettingsDetailKit.kt:155-230`；选项集合不变 | 5 个循环行改成点了弹出选择抽屉；跳转、选择、开关三类右侧样式不同；值字号 12sp；开关按下时滑块拉伸、切换时触觉；设置主页行高和图标不变 |
| SW-12-10 | AppSnackbar 与可撤销删除 | P1 | 新增 `ui/common/AppSnackbar.kt`（挂在 AppOverlayHost 层）；先收口 `MainActivity.kt` 的 105 处 Toast；4 类删除（歌单内歌曲、下载记录、播放历史、移出歌单）改成延迟提交 + 撤销 | 前台提示都走 Snackbar，位于迷你条上方；文案 ≤ 14 字、不含 e.message、全角标点；4 类删除 5 秒内可撤销且数据完整恢复；"删除文件"的确认保留 |
| SW-12-11 | 预测性返回：详情页和设置页侧滑跟手 | P1 | `ui/motion/DetailPush.kt`、`ui/motion/SettingsPush.kt`、对应 BackHandler | Android 14+ 侧滑时顶层页跟着手指右移、露出下层页；取消时回弹；完成后用 300ms 收尾；API 33 及以下行为不变 |
| SW-12-12 | 主列表增删排序动画 | P2 | 歌单详情、下载、队列、脚本、搜索结果、音乐库的 LazyColumn 补 key + `animateItem`；`ScriptsPage.kt:396` 的拖动阈值 48px 改成按行高的 dp；拖起时加 4dp 阴影 | 删除时邻行平滑上移，插入时淡入；拖动排序跟手，换位平滑；各设备阈值一致；减少动效下没有位移动画 |
| SW-12-13 | 统一空、错、加载状态组件 | P2 | 新增 `StateBlock`，替换 `EmptyHint`（12 处）和各页自写的空/错文案；网络错误文案统一 | 空、错、加载三态视觉一致（图标 48 + 主句 15sp + 副句 13sp + 可选按钮）；网络错误只有一种说法加"重试"；文案不带句号 |
| SW-12-14 | 字号、字重、间距、图标 token 收敛（非冻结区） | P2 | 新增 `Type` / `Space` / `IconSize` token（B.6、B.7）；先迁抽屉、设置子页、搜索、下载这 4 个模块；108 处 Material Icons 换成 1.8 线宽自绘 | 迁移模块内 fontSize 不超过 9 档、不出现 .5sp；间距只用 token；Material Icons 引用降到 0；冻结注释处数值不变（diff 校验） |
| SW-12-15 | 字体放大与读屏可用性 | P1 | 抽屉行、设置行、列表标题把 `height()` 改成 `heightIn(min)`；fontScale ≥ 1.3 时标题放宽到 2 行；分组标题和页头加 `heading()`；Snackbar 加 liveRegion；自定义 tween 乘系统动画倍率。浅色小号珊瑚字改为 `#C8381F` 需用户确认后单独执行 | 系统字体 1.3x 下设置、抽屉、歌单详情无截断无溢出；TalkBack 能按标题跳转；系统动画 0.5x 时自定义动效同步缩短 |

---

## 来源
- Material 3 Easing & duration（emphasized-decelerate 400ms 进入、accelerate 200ms 退出、standard 300ms；duration 档位 50–1000ms）：https://m3.material.io/styles/motion/easing-and-duration 、https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
- Material 3 motion physics / MotionScheme 弹簧值（expressive default 0.8/380、fast 0.6/800；standard 0.9/1400/700/300；effects 1.0/1600）：https://m3.material.io/styles/motion/overview/specs ，以及 androidx 源码 `MotionScheme.kt`：https://android.googlesource.com/platform/frameworks/support/+/43a3dc60a427d3fffa9cbc3f83e5f9a1c5cec29a/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MotionScheme.kt
- Compose spring 能平滑处理打断（保持速度连续）：https://developer.android.com/develop/ui/compose/animation/customize
- Android 触觉规范（优先用预定义常量，要清脆不要嗡嗡，注意频率）：https://developer.android.com/develop/ui/views/haptics/haptics-principles ；常量 CONFIRM/REJECT/DRAG_START/SEGMENT_FREQUENT_TICK：https://developer.android.com/reference/android/view/HapticFeedbackConstants ；CONFIRM/REJECT 示例：https://developer.android.com/develop/ui/views/haptics/haptic-feedback
- Apple HIG Sheets（可调大小的 sheet 要有把手、支持下滑关闭、Cancel 在左 Done 在右）：https://developer.apple.com/design/human-interface-guidelines/sheets ；Action sheets（破坏性选项要醒目、Cancel 在底部）：https://developer.apple.com/design/human-interface-guidelines/action-sheets ；Alerts（默认按钮在右，Cancel 在左）：https://developer.apple.com/design/human-interface-guidelines/alerts
- 网易云设置分组（第三方教程转述，非官方文档）："设置 → 播放和下载 → 下载音质" https://mip.wendaso.com/article/a3761b2bef319b5476f9aac2.html ；"设置 → 音质 → 下载" https://www.itmop.com/article/52359.html
- 说明：web_search 共用了 5 次。Spotify 和 QQ 音乐的对照来自公开产品的常识性描述（底部自家风格提示条、喜欢触感、迷你条跟手展开），没有找到可引用的官方设计文档，按经验判断处理。HapticFeedbackConstants 中 TOGGLE_ON/OFF、SEGMENT_TICK、GESTURE_THRESHOLD_* 标为 API 34+，是根据参考页和平台源码判断的，落地时请以 SDK 34 的实际常量为准。
