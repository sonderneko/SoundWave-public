# SoundWave v2 v1.7 非 UI 更新交接

**日期：2026-10-30（Asia/Shanghai）**

## 交接内容

此包以 `/workspace/soundwave-r675-work/docs/plan/` 中比用户上传 ZIP 更新的 v1.7 CSV、Markdown、JSON 为基础生成独立副本；原项目工作树未改动。包内包括更新后的任务 CSV、计划 Markdown、`roadmap.json`、[非 UI 进度与待完成项](非UI进度与待完成项.md)，以及从用户 ZIP 原样提取的 `uiux-audit.md` 和原样复制的 `SwDesign.kt.txt`。

CSV 仍为 154 项，状态总数为：进行中 12、待做 97、已实现待验收 40、已完成 4、阻塞 1。7 个有明确窄修复证据的非 UI 任务由“待做”调为“已实现待验收”；没有将任何窄修复标为整项完成。另将 JSON 中滞后的任务状态同步到当前 CSV/Markdown，并重算 JSON 的 `status_counts`。UI/UX 状态以当前 CSV/Markdown 已有状态为准；没有改变 UI 任务在当前计划中的语义状态、任务描述、审计或设计稿。`SW-03-11` 仅在 JSON 中镜像 CSV/Markdown 已有的“进行中”状态，未改其证据或任务内容。

## 最新远端与本地安全边界

- GitHub：`projects258036-svg/soundwave-v2`，分支 `hark/r718-cue-reference-hardening`，tip `fd63eeb6f971142ee5c9ce0efe0f9ec81f3f311b`。
- 本机项目 checkout 仍是旧 HEAD `f439ad8530d524a7a468fac0cd0b60560abd736d`，存在 5 个 tracked 修改和 1 个 untracked 文件；本地 HEAD/历史未与该远端分支同步。**不要从这个旧 checkout 继续提交或推送，也不要修改、reset、clean 或 commit 这个工作树。**
- 本次只生成 `/workspace/soundwave-plan-update-20261030/final/` 下的交接副本；未改代码、未 commit、未 push。
- 已推送的 10 个窄修复：SW-06-10 `629e57a32fc787b5c34a23d75956a713e1b00c36`；SW-06-11 `3dfc4879f342aa6f23d584922c9da59836839fdc`；SW-06-07 `07a4b73b104c6d35ef1dd8f0c8a136bf015bb5b0`；SW-02-01/SW-04-04 `beaa5458c1dbbbb9ff5baf1704c16cc24e4e18e0`；SW-02-14 `53f8f42aaedd43e234153cf7dca275f8a1ae3422`；播放列表导入检查并发（未认领整项）`4bd018848c774eddaeba89d4af28b9fe2190e138`；SW-02-09 `f439ad8530d524a7a468fac0cd0b60560abd736d`；短曲起播容错（未认领整项）`b8734f409df07d4db6fa2877f76d8a8fe2a179a8`；SW-05-07 `bbfe3d5700b5a5ac8197f286126ec13a36573b19`、`2e167a582044ad204383a7c0dfb429753ca86ab7`；SW-05-09 `f1d9f27c240a93af1c5ab3018cbbda3162fd1e41`、`fd63eeb6f971142ee5c9ce0efe0f9ec81f3f311b`。行为、测试范围与边界见 `非UI进度与待完成项.md` 和对应任务证据；所有网页提交均带 `[skip ci]`。

## 尚待接手处理

按非 UI 方向继续：先基于干净且与远端 tip 对齐的副本处理已知守卫/测试夹具问题，再进行隔离数据下的真实双设备同步、真实 WebDAV 与实际播放路径验收。已知未解决基线失败包括：排序旧守卫仍要求 1.0.659（当前 1.0.675）；播放列表导入未修改的 `ApiClient` 静态断言失败；`step8-direct-source` 守卫在未改动 HEAD 上也失败；两套旧短曲行为脚本在父提交上已有夹具编译失败。不要把这些失败描述成已修复，也不要通过放宽守卫掩盖。没有构建 APK、没有触发 GitHub Actions/CI、没有真机或真实账号/音源/媒体/WebDAV/NAS/VPS 验收；fake、mock、JVM 与 Robolectric 结果不等于设备或生产服务通过。Codemagic 维持暂停，不要擅自恢复或触发构建。不要把用户数据、生产同步、远端合并/删除或发布流程纳入本批。

UI/UX 冻结：不要编辑 UI/UX 任务、审计或设计 token；`uiux-audit.md` 和 `SwDesign.kt.txt` 是只读交接附件。

## 包内文件

- `SoundWave-v2-任务清单-v1.7.csv`
- `SoundWave-v2-计划-v1.7.md`
- `roadmap.json`
- `非UI进度与待完成项.md`
- `uiux-audit.md`（ZIP 内原样提取）
- `SwDesign.kt.txt`（原样复制）
- 本文件 `HANDOFF.md`
