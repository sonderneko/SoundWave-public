# r675 设置页通知中心：已实现，待设备验收

基线：`cue/r675-reference-hardening` / `c139d80d`。本批为其后的未提交工作区改动，不代表已发布版本。

## 实施

- 设置主页面、分类与深层页面右上角头像替换为通知铃铛，复用既有圆形按钮设计与设置子页路由。通知中心自身不再重复显示铃铛。
- 保留设置主页面主题切换、账户卡片头像/登录入口和原有系统返回规则；未修改详情页、播放器页或既定间距。
- 应用更新只读取既有 GitHub Releases 检查器的真实结果；独立状态流避免播放状态覆盖更新结果。未检查、检查中、失败与成功结果分别展示；检查期间禁用该页重复检查按钮。
- 展示实际已检查发布的版本、说明摘要及用户点击后打开的完整发布页面。只检查版本，不自动下载或安装；更新检查状态只在本次进程运行中保留，不伪造历史或未读数量。
- 脚本通知读取本机 ScriptProbeCache，沿用“完整报告优先，无完整报告才回退旧版抽检”的既有规则，并在页面说明中明确。排序按当前展示报告的原始时间；不是所有历史报告的事件流。
- 明确区分无报告、脚本内容/运行时变化后过期、未完成和旧版快速抽检。报告无有效时间时不编造时间。代码/运行时失配才按既有规则判过期，没有新增按天过期阈值。
- 展示已验证项/实际尝试项及检测覆盖范围限制，不把部分成功说成所有平台/音质可用。通过原持久化偏好监听刷新打开中的页面。
- 查看通知本身不发起检测、不修改定时检测计划；“前往音源脚本”复用现有页面查看和手动操作。

## 已通过的针对性验证

命令（SDK 环境变量均为 `/home/ubuntu/Android/Sdk`）：

```sh
ANDROID_HOME=/home/ubuntu/Android/Sdk ANDROID_SDK_ROOT=/home/ubuntu/Android/Sdk \
./gradlew --offline --no-daemon :app:testDebugUnitTest \
  --tests 'xyz.cncnlp.soundwave.ScriptNotificationTest' \
  --tests 'xyz.cncnlp.soundwave.SettingsNotificationsUiTest' \
  --tests 'xyz.cncnlp.soundwave.ScriptProbeCacheValidityTest' \
  --tests 'xyz.cncnlp.soundwave.ScriptProbeFullReportFormatTest' \
  --tests 'xyz.cncnlp.soundwave.AppRouteSettingsBackTest' \
  --tests 'xyz.cncnlp.soundwave.Round6BehaviorTest' \
  --tests 'xyz.cncnlp.soundwave.SettingsNavigationGlyphTest'
```

最终结果：BUILD SUCCESSFUL，7 个测试类、38 项测试，0 失败/错误/跳过；其中新增摘要单测 5 项、Robolectric/Compose 交互测试 5 项。包括铃铛可访问名称和点击、空状态不自动执行、检查中禁用/失败恢复、真实发布元数据展示及明确点击打开、保存报告后界面响应更新。发布说明测试使用测试夹具，不代表线上存在该版本。

新增 `bash scripts/settings-notifications-guard.sh` 通过，核对两个真实设置页头路径、通知路由、账户/主题入口保留、真实状态订阅和无自动探测接线。既有 `smart-source-autoconfigure-guard.sh` 通过。`git diff --check` 通过。

以下两个既有静态守卫失败；已在 `git archive c139d80d` 临时快照重跑，失败内容相同，本批未放宽守卫或修改无关文件：

- `script-probe-report-isolation-guard.sh`：manual sorting and smart configuration must use current-script full-report evidence。
- `theme-navigation-mini-player-guard.sh`：旧 MiniPlayer 颜色源码文本断言不匹配。

## 尚未验收

未跑全仓单测/完整回归、release 构建、设备或真机验收；未实时访问 GitHub 检查当前线上版本，也未发起真实脚本检测。仍需后续统一 APK 中验收深浅主题、大字体、实际点击区域、铃铛进入/系统返回、更新网络失败和报告刷新体验。铃铛沿用现有 42dp 视觉组件，未为本批改动全局页头几何。

本批未打包 APK、未 push、未安装、未访问生产服务，未检查两台 InstaBox。

外部原始日志目录：`/workspace/soundwave-notifications-validation-20261023/`，包含 `final-targeted-tests.log`、`notification-guard.log`、`related-guards.log`、`baseline-guards.log`。JUnit XML 位于项目 `app/build/test-results/testDebugUnitTest/`。
