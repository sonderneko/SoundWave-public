# SoundWave 审查记录

## r657 第三轮 b（分支 `fix/r657-r3b`，基于 `origin/fix/r657-r3` 69f00d89）

### 1. 合并与冲突决策（38b6ba36：fix/r657-ui-r3 → fix/r657-r3）

- **原则**：逻辑 / 可靠性取远端（fix/r657-r3：播放事务、取链预算、下载断点、隐私闭环、脚本运行时）；视觉取 v5 设计稿（fix/r657-ui-r3）。
- **守卫脚本**：合并时守卫取了远端版本，但对应界面已按用户 10-18 反馈改动，导致 7 个 CI 守卫失败。逐个核对后只更新断言、不放宽语义：
  - `player-default-mode-guard`：主页⇄歌词切换由 `Crossfade` 换成 `PlayerCoverSharedPages`（内部仍交叉淡入 + 共享封面），接受二者之一。
  - `player-menu-sheet-guard`：睡眠定时自定义输入改为统一 `SettingsNumberInputDialog`（`parseCustomNumber` 校验 1–480）+ 5 分钟步进滑杆，断言随实现更新，仍要求 8h 上限 / 精确自定义分钟 / 播完当前 / 可滚动。
  - `ui-classic-appearance-guard`：「下滑迷你条隐藏底栏」「底栏自动隐藏」按用户要求已从设置移除，去掉对应状态断言；关于页「检查更新」为 v5 珊瑚渐变胶囊（非 Material Button），仍要求白色前景、无 onPrimary、Role.Button。
  - `download-manager-theme-guard`：同上，关于页白色标签断言放宽到不限定字重。
  - `home-page-theme-guard`：今日推荐英雄卡遮罩按 v5 `.hero::before`（rgba(6,8,12) 0@25% → .55@62% → .86@100%）。
  - `maintenance-pages-theme-guard`：取链报告展开文案「查看全部 N 条」；注释中避开「最近缓存歌曲」字样（该区块已删除）。
  - `third-party-account-removal-guard`：注释里的禁用词改写为「今日推荐」。
- 未改：`dev`、`master`、`release/*`、`r658/*`、`origin/fix/r657-r3`。

### 2. 今日推荐校验键语义变更（理由）

- **旧**：`TodayRecommendationSlot.fingerprint(platform, profile, tags, batch, enabled)`，其中 profile 的 activitySignature 含实时累加的收听秒数与最近播放。
- **问题**：播放过程中画像每次重建都会换键 → 首页卡片被清空并重新联网生成、已打开的今日推荐详情被置空；叠加分类过滤（要求曲名/专辑名含风格词）几乎把「摇滚」「民谣」等筛空，用户看到「今日推荐没有歌曲且很卡」。
- **新**：`snapshotFingerprint(platform, exposureScope, tags, batch, enabled)`，前缀 `today-snapshot-v2`。今日推荐是时段快照（每天 18:00 一次），键只含平台 / 风格开关 / 标签 / 换一批序号 / 账号作用域；画像变化在下一时段、换一批或改风格时生效。
- 同批：分类歌单成员即风格证据（`acceptsFromCategory`，纯音乐仍排除人声/动漫冲突）；口味不足一半时以「流行 / 华语」分类歌单冷启动召回补齐（不是热榜）。
- **兼容**：旧键快照不再命中，首次升级会重新生成一次今日推荐（一次性）。

### 3. 本轮新增

- 详情「沉浸」样式（设置 › 详情页封面样式：默认 / 标准 / 沉浸；id `immersive_hero`，备份白名单已加入）：封面满幅到顶部无圆角，底部按封面取色（压暗到亮度 ≤ 0.17 保证白字）渐隐到页面背景；浮动半透明返回 / 更多，滚过封面后让位给折叠磨砂顶栏；保留标题、播放全部、喜欢、下载、更多，曲目列表无编号。今日推荐保留专属英雄卡。
- 可靠性 F3：`FailedTransitionFinisher` —— 连续失败 STOP、不可继续的解密失败按捕获的播放意图暂停、发布真实快照并释放过渡唤醒锁；过期事务不动（单测 4 例）。
- 可靠性 F8 补充：弱 ETag（`W/`）不再授权续传（单测 3 例）。

### 4. 可靠性清单核对（docs/r657-r3-evidence/r657-reliability-findings.md，审查基线 fb660e76）

远端 fix/r657-r3 已在 4856b275 / 1f844b5a 处理大部分条目；本轮逐项核对当前代码：

| 项 | 现状 |
|---|---|
| F1 Scheme collector | 已由远端修复（独立分发 + STATE_INTENT_SUBMITTED）。 |
| F2 playback-intent epoch | 已由远端实现（`PlayerService.playbackIntent`，暂停/停止/新意图递增，提交与恢复复核）。 |
| F3 失败终止释放 wake | **本轮补齐**（见上）。 |
| F5 单一 deadline | `resolveResult` 最外层 26s 总预算 + 可取消传输（1f844b5a）；STALE 自动重试是否复用同一预算未再核实。 |
| F6 DOWNLOAD admission / nativeFetch 父子取消 | 已由远端实现（`isDiscardableBackground`、`fetchParents`）。 |
| F7 单流 206 Content-Range | 已由远端实现（`DownloadSingleContentRangeTest`；该测试在本沙箱因 MockWebServer 关闭时 `NativeThread.signal` 报 IOException 失败，CI 通过，属环境问题）。 |
| F8 指纹身份 / 原子注册 | 远端已绑定来源+曲目身份摘要、`DownloadRequestRegistry`；本轮加弱 ETag 拒绝。 |
| F9 脱敏 | 已由远端实现（JSON 键引号、URL userinfo、幂等，`CrashReportRedactorTest`）。 |
| F10 上传前复核 | 已由远端实现（privacyEpoch + reportId）。 |
| F11 重建重投 | 已由远端实现。 |
| F12 ZIP / 备份有界 | 已由远端实现（`BoundedZipReaderTest`、`BackupInputReaderTest`）。 |
| F13 备份白名单 | 已由远端实现（`BackupPreferenceProjection`，crash_auto_upload 不在白名单）。 |

### 5. 遗留风险

- 截图测试（`screenshot/`）仅在设置 `SW_SCREENSHOT_DIR` 时运行，本轮未重拍；`R657R2ScreenshotTest` 返回按钮、`DetailDesignScreenshotTest` 中 `ChartDetailBanner` 的断言仍按旧视觉，下次重拍时需同步。
- 播放链路抽屉只展示取链报告真实记录的阶段；参考图中的「智能换源策略顺序」「校验播放地址」两步当前未被 `PlaybackDiagnostics.Entry` 记录，未臆造。补齐需扩展诊断记录字段。
- 沉浸样式未做真机 / 截图验证（沙箱无设备）；取色为整图均值，极端封面（大面积纯白边框）色带会偏灰。
- `rememberRecommendationLibrary` 改为按输入 remember 后，收听统计变化不会立即进入画像（与时段快照语义一致，见 §2）。
- 本沙箱 python JVM 守卫（`*_jvm.py` 等 15 个）需要本地 Kotlin 编译器 jar / 固定路径，在基线与本分支上同样失败，未纳入 CI。
