# r675：四项 UI 失败与四项 Lint 错误的处理

两组问题已在独立分支做最小处理。最终全量主机测试连续两次均为 **2095 项：1685 通过、0 失败、0 errors、410 跳过**；完整 `lintDebug` 为 **0 errors、90 warnings**。跳过和既有基线债务不算修复；这些结果不等于真机、性能或发布验收。

## 隔离与保护

工作区 `/workspace/soundwave-675-ui-lint`，分支 `cue/r675-ui-lint-investigation`，从同步修复提交 `1f02c91f205cca1e602603a39dc04b8822a7d4b3` 派生。Lint 修复移植提交为 `cd00424e`（原独立提交 `f06babb90c21276701a354a3ad3b98dc767a22e9`）。本批只改测试配置、测试工厂与验证文档，`app/src/main` 相对同步提交无差异。

原工作区 `/workspace/soundwave-675-improve` 仍在 `1f02c91f`，保持干净；没有覆盖、重做或改写同步实现。没有 push、合并回原分支、升版本或打包，未运行 assemble、bundle 或签名任务。

## 四项 Lint：测试源码分析把构造表达式误判成 Unit

原样 `lintDebug` 重现四项 `RememberReturnType`，涉及 `Playback669ServiceWiringTest`、`Player666RoutesTest`、`Player667RoutesTest`、`Player668RoutesTest` 的 `remember { PlayerDismissOffsetHolder() }`。

`PlayerDismissOffsetHolder` 实际是有状态类，不是 Unit；测试和生产 Kotlin 均能编译，生产 `MainActivity` 的同型构造未产生该诊断。仅显式写 `remember<PlayerDismissOffsetHolder>` 仍报四项；将构造放进显式返回 `PlayerDismissOffsetHolder` 的测试工厂，再在原 `remember` 块内调用，四项诊断消失。这些对照支持测试源码的 Lint 类型解析误报，而非实际返回 Unit。尚未定位到分析器内部的具体缺陷或上游 issue。

最小修复新增 `PlayerDismissOffsetTestSupport.kt` 中的有类型工厂并替换四处调用，继续由 `remember` 保持实例及组合生命周期。不改生产持有器、不禁规则、不扩大 `lint-baseline.xml`，也不删测试。

独立验证中四个受影响类各选一项相关测试，4/4 通过；最终全量亦通过。原有 34 errors、56 warnings 仍由既有 baseline 过滤，不宣称消债。

## 四项 UI：全量上下文下的默认图形模式空闲同步失败

四个原失败分别是：

- 弹层主题更新后等待空闲。
- 上层弹层返回移除后等待空闲。
- Host 卸载清理后等待空闲。
- 600 行列表 `setContent` 阶段等待空闲，尚未运行回顶逻辑。

先不改任何 UI 生产或测试源码，在同步修复基线上单独跑两个目标类：5/5 全过。随后在包含 Lint 修复但 UI 源未变的分支跑全量：2095 项中同样四项以 `AppNotIdleException` 失败，410 跳过。因此已复现全量相关性，不能把原失败直接解释成回顶函数故障或已证实的无限重组。

只给两个目标类加 `@GraphicsMode(GraphicsMode.Mode.NATIVE)` 后，全量 2095 项零失败；用 `:app:testDebugUnitTest --rerun` 再执行一次，同样零失败。保留全部断言、600 行实际 LazyColumn、逐帧采样和原超时限制，不改弹层、滚动算法、时钟推进或动画参数。测试配置使用与仓库其他真实 Compose 布局测试一致的原生图形模式。

两次回顶 CSV 都记录了 77 个实际位置，从列表底部可视起点 `(584,46)` 到 `(0,0)`；原有单调、逐帧位移上限和中间位置断言均通过。此处是 Compose 虚拟时钟行为验证，不是设备帧率证明。

**结论边界：**已验证“目标测试显式 NATIVE 模式能消除本次全量上下文下的四项失败”。尚未确定是哪一个前置测试、图形后端行为或 Robolectric 沙箱状态触发默认模式的问题，不能声称底层根因已完全定位，更不能宣传修好了真机卡顿。额外有序对照（Native 图片解码测试→目标类、Native Compose 详情测试→目标类、主题启动及守卫→目标类）全部通过，不能据此认定单纯 Native→默认模式切换就是根因。

## 验证表

| 阶段 | 范围 | 结果 |
|---|---|---|
| UI 原样单跑 | 两类、5 项 | 5 通过 |
| UI 原样全量，已含 Lint 修复 | 338 组、2095 项 | 1681 通过、4 失败、410 跳过 |
| 两类显式 NATIVE 后全量 | 338 组、2095 项 | 1685 通过、0 失败、410 跳过 |
| 同一最终代码强制重跑 | 338 组、2095 项 | 1685 通过、0 失败、410 跳过 |
| 完整 lintDebug | 保持既有规则和 baseline | 0 errors、90 warnings |

SDK 为 `/home/ubuntu/Android/Sdk`。所有主任务 Gradle 运行使用 `--no-daemon --max-workers=1`、JVM `-Xmx2g -XX:MaxMetaspaceSize=512m`。最终联合命令为：

```bash
export ANDROID_HOME=/home/ubuntu/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
bash gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  :app:testDebugUnitTest :app:lintDebug --console=plain
# 不复用 test 任务结果的第二次全量：
bash gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  :app:testDebugUnitTest --rerun --console=plain
```

## 证据位置

所有原始结果在 `/workspace/soundwave-ui-lint-results/`：

- `summary.json`：各阶段 XML 汇总与轨迹端点。
- `ui/before.log`、`ui/before-xml/`：未改 UI 的单跑通过。
- `ui/full-suite-before.log`、`ui/full-suite-before-xml/`：全量四项复现。
- `ui/full-suite-native.log`、`ui/full-suite-native-xml/`：最终全量与完整 Lint。
- `ui/full-suite-native-repeat.log`、`ui/full-suite-native-repeat-xml/`：最终全量第二次实跑。
- `ui/native-trajectory.csv`、`ui/native-repeat-trajectory.csv`：两次实际列表位置。
- `lint/r675-before-lintDebug.log`、`lint/r675-after-lintDebug.log`、`lint/r675-targeted-unit-tests.log`：独立 Lint 修复验证。
- `lint/final-lint-results-debug.xml` 与 `.txt`：合并后完整 Lint 报告。
- `repro/summary.md`：未能缩小触发源的有序对照与日志索引。

Debug D8 打包问题、其余静态守卫、真机播放/弹层/滚动性能及正式安装包均不在本次通过结论内；本次未打包。
