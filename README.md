# SoundWave

本地优先（local-first）的 Android 音乐播放器，Kotlin / Jetpack Compose 实现。
聚合网易云、QQ 音乐、酷狗、酷我、咪咕五个平台的公开搜索、榜单、歌单与播放，
支持本地曲库画像推荐、WebDAV 同步与备份恢复。

- 当前版本：见 `VERSION`（导入基线 1.0.675 源码快照，2026-10-25）
- 架构与数据边界：`docs/architecture-local-first.md`
- 审查记录：`REVIEW.md`
- v2 规划与交接：`docs/v2-handoff/`（计划 v1.7、154 项任务清单、非 UI 进度、UI/UX 审计）
- 历史设计稿与原型：`docs/design-uploads/`、`design/`
- 构建：仓库自带 GitHub Actions（`.github/workflows/build-apk.yml`）与 Codemagic 配置

> 本仓库为私有仓库。导入基线不含签名密钥与构建产物（见 `.gitignore`）。
