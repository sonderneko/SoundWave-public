# SoundWave 1.0.359 · GPT 分析逐条核实清单

- 核实日期：2026-09-18
- 核实对象：`master`（VERSION=1.0.359，相对 GPT 分析所依据的 1.0.358 仅多一个 feed 修复提交）
- 核实方式：纯静态读码 + grep + 只读网络探针（签名 URL 仅 HEAD，未下载任何密钥材料）
- 代码规模：`app/src/main` 共 117 个 Kotlin 文件、36,821 行；`app/src/test` 共 24 个测试文件
- 状态口径：**已做**=现状满足 GPT 关切；**部分**=方向对但程度/细节有偏差或实现不完整；**未做**=确无对应实现；**过时**=曾经成立、当前代码已处理；**不成立**=与当前代码事实矛盾

---

## 一、10 个核心问题

| 编号 | GPT 结论（压缩） | 状态 | 证据（file:line） | 优先级 | 一句话做法 |
| --- | --- | --- | --- | --- | --- |
| 1 | MediaDecryptCache 整文件读内存解密，100MB 峰值 200MB+；MAX_BYTES=200MB 只防无限大不防 OOM | 部分（GPT 还说轻了） | `MediaDecryptCache.kt:31`(MAX_BYTES=200MB)、`:94`(`part.readBytes()` 全文入堆)、`:105-112`(明文再写一份 ByteArray)；`QmcDecrypt.kt:120`(`val out = encrypted.copyOf()` 又一份全文)、`NcmDecrypt.kt:79`(`copyOfRange` 音频体)；下载侧同病：`DownloadHelper.kt:33`/`:187-188` | P1 | 改流式解密：FileInputStream 按 mask 分块读、边解边写 FileOutputStream，去掉 readBytes+copyOf 双份全文常驻 |
| 2 | QQDirectResolve / MediaDecryptCache 绕过 MusicSource 契约，构成新旁路 | 部分（确有旁路，但是既定设计） | `MusicSource.kt:54-58`、`:78-82`（resolveCapability 默认 NoResolve，注释明写"取链编排仍归 UrlResolver"）；直连分支是 UrlResolver 内平台 if 块 `UrlResolver.kt:214`(kg)/`:225`(mg)/`:237`(kw)/`:249`(wy)/`:261`(tx)；解密在 `PlaybackController.kt:474-477`，不在任何 Source 内 | P2 | 若要契约纯净：把 5 个直连分支下沉为 PlatformSourceAdapter 的 ResolveCapability(CLIENT_OFFICIAL)，UrlResolver 只按能力位分发 |
| 3 | LyricSourceLoader 职责膨胀（本地 lrc/qrc/krc+解密+网络+缓存全在一个对象），应拆 Resolver chain | 部分 | `LyricSourceLoader.kt` 共 149 行 8 函数：`:30-65`(候选枚举+解密派发)、`:67-92`(网络+缓存重试 4 次)、`:100-148`(ID3v2 USLT 二进制解析，readUslt/syncSafe/decodeUslt 占约 1/3 篇幅) | P2 | 把 `:100-148` 抽成 `Id3UsltParser`；loadSidecar 的候选链已近似 chain，可再提成 LyricResolver 列表 |
| 4 | CacheManager/MediaDecryptCache/LocalStore 三方清理边界不清；MediaDecryptCache 只有 clear() 无 LRU/容量上限 | 部分（clear() 甚至是死代码） | `CacheManager.kt:181-191`(clearAll：清 Media3 缓存+cacheDir 除 exo 外+lyrics+audio，**已覆盖 media_decrypt**)、`:304-335`(trimIfNeeded，`:311` 优先级表、`:321` 按最后修改时间排序≈mtime-LRU、`:310/313/332-334` 排除 exo 交给 SimpleCache)；`MediaDecryptCache.kt:133`(clear 存在但全仓零调用=死代码)；`LocalStore.kt:231`(clearHistory)/`:396`(clearDownloads) 各自独立 | P1 | 删掉或接线 `MediaDecryptCache.clear`；在设置页"清空缓存"旁注明清理口径（明文解密缓存随 cacheDir 一并清，但无独立容量上限） |
| 5 | 8 处各自 new OkHttpClient，网络策略分散 | 成立（实为 8 文件 9 处，但头策略已集中） | `ApiClient.kt:67` 与 `:1398`(headContentLength 又一个)、`DownloadHelper.kt:48`、`MediaDecryptCache.kt:35`、`ScriptRuntime.kt:83`、`PlatformPlaybackAdapter.kt:137`、`UrlResolver.kt:954`、`RecommendDirectFetch.kt:75`、`PlatformHttp.kt:19`；缓解证据：请求头已统一走 `MediaRequestPolicy`（`MediaRequestPolicy.kt:6`，5 处调用），`PlatformHttp.kt:19-24` 为进程级共享池+newBuilder 覆写超时 | P2 | 收敛为单例 sharedClient，各业务用 newBuilder 只覆写超时（照 PlatformHttp 模式），连接池/dispatcher 全局共享 |
| 6 | PlayerService 播放器状态过多 | 部分（对外已收敛，对内仍宽） | 对外好消息：状态已合并为单一 `StateFlow<PlaybackSnapshot>`（`ResolutionModels.kt:75-87`，10 字段；`PlayerService.kt:108-110`）；对内：11 个事件回调（`PlayerService.kt:143-161`）+ 双播放器 `:87/:90` + queue `:107` + queueIndex `:125` + pendingNextSong `:127` + 两个令牌 `:102/:103` | P2 | 回调收敛为一个 `PlayerListener` 接口或 SharedFlow 事件流，减少 Service 的可变挂载点 |
| 7 | PlaybackTransaction 是否覆盖 resolve→decrypt→play 全链 | 已做（覆盖到位） | `PlaybackTransactionGate.kt:20`(begin)/`:25`(cancel)/`:33`(isCurrent)；使用点 `PlaybackController.kt:304`(begin)、`:312/:315`(解析中校验)、`:397-400`(STALE 同事务重试一次)、`:486-487`(**解密完成后、playUrl 前再校验 requestIsCurrent**)、`:516-518`(finally finishIfCurrent)；解密本身也响应取消 `MediaDecryptCache.kt:81-83`/`:88-90`/`:161-163` | P3 | 无需改动；可作为良好范式记录到架构文档 |
| 8 | VPS/Script/Cookie 数据流缺 UI 隐私说明 | 部分（无 UI 说明属实，存储侧已加密） | UI 侧全仓无隐私/数据流文案（"隐私"仅见于 `TxDirectSearch.kt:13`/`KwDirectSearch.kt:14`/`KgDirectSearch.kt:14` 代码注释）；Cookie 存 Android Keystore AES-GCM（`PlatformAccountStore.kt:17`）；VPS 代理会收到被拒脚本请求含 Cookie（见既有审计 F-09，`docs/security-audit-2026-09.md`）；服务器地址硬编码 `MainActivity.kt:227` | P1 | 设置页加一段数据流声明（A 永不出设备 / B 登录才出 / C 播放请求出 / D 经 VPS），直接引用现有安全审计结论 |
| 9 | Release 签名供应链安全 | **成立（且是活体 P0）** | `codemagic.yaml:29` 明文 curl 下载 keystore、`:30` 再下载**含密码的 env 脚本**，两个 URL 直接提交在仓库里；只读探针实测两 URL 均返回 `HTTP/2 200`（content-length 4414 / 386，Cloudflare 放行）；对照正解：`.github/workflows/build-apk.yml:41-63` 走 GitHub Secrets+base64+keytool SHA256 指纹校验，`app/build.gradle:26-31` 仅从环境变量取签名配置 | **P0** | 立即吊销并重签发版密钥、删除公开 URL、两个 CI 统一改走 Secrets；已发的包需在更新渠道公示新指纹 |
| 10 | targetSdk 34 工程债务 | 部分（成立但影响面有限） | `app/build.gradle:9`(compileSdk 34)、`:13`(minSdk 26)、`:14`(targetSdk 34)；当前日期 2026-09，已落后两个 API 级别（35/36） | P2 | 若仍要上 Google Play 需升 targetSdk 35+；现行为自建站点侧载分发（CI 产物+`lp.cncnlp.xyz`），可排期不紧急 |

---

## 二、其他具体指控

| 编号 | GPT 结论（压缩） | 状态 | 证据（file:line） | 优先级 | 一句话做法 |
| --- | --- | --- | --- | --- | --- |
| 11 | 解密文件"注释大于代码" | 不成立（GPT 自证矛盾） | 实测注释占比：`LyricQrcKrc.kt` 49/180=27%、`QmcDecrypt.kt` 18%、`NcmDecrypt.kt` 12%、`MediaDecryptCache.kt` 16%——四处注释均为少数，GPT 自己报的百分比就已否证"注释大于代码" | P3 | 无需改动；注释多为算法移植溯源，保留 |
| 12 | 版本号增长太快（1.0.354→359 连续 6 版） | 成立（且比 GPT 说的更快） | 仓内 APK 足迹：`SoundWave-1.0.212-74/76/86/88/90/92.apk` 六个构建挤在 8/25-8/26 两天、`1.0.213.apk` 同日 15:55；1.0.213(8/26)→1.0.359(9/18，VERSION 文件 9/18 08:11)共 23 天跳 146 个次版本 ≈ 6.3 版/天 | P3 | 节奏本身非缺陷，但配合"零 E2E 测试"回归风险偏高，建议关键链路加回归门禁 |
| 13 | 只有 parser 测试，缺 Contract/E2E | 部分 | `app/src/test` 确为 24 个文件，且**不止 parser**：含契约测试 `PlaybackTransactionGateTest.kt`、`SourceCapabilitiesTest.kt`，策略测试 `PlatformCookiePolicyTest.kt`/`ScriptNetworkPolicyTest.kt`/`SourceProvenanceTest.kt`；但全仓无 `androidTest` 目录=零插桩 E2E | P2 | 补一条 instrumented 测试覆盖 resolve→decrypt→play 端到端（事务门已可单测，链路尚不能） |
| 14 | VIP 探测不应是播放主链路硬依赖（超时卡 loading） | 不成立（失败按非 VIP 降级，不阻塞） | `QQDirectResolve.kt:37` 调 `qqIsVipAccount`；`:67` cookie 空→false；`:79` `if (probed == null) return false`（探测失败按非 VIP，不缓存，下次重试）；成功缓存 10 分钟 `:84-86`；残留代价仅为首次取链最坏多等 8s（探测 8s+取链 8s，`PlatformHttp.kt:32` timeoutMs 默认 8000） | P2 | 不改正确性；可与取链并行探测或把 VIP 探测并入 GetVkey 单请求以省一次往返 |
| 15 | 三道杠平台切换是隐藏循环式交互、无反馈 | 成立 | `HomePage.kt:142`：点击执行 `onPlatform(platforms[(i + 1 + platforms.size) % platforms.size].first)` 纯循环、无 Toast/无平台名标签；图标为三横条+错位点 `PlatformBrand.kt:83-95`（注释 `:84` 明写"顶栏左：平台菜单"） | P2 | 点击后 Toast 或顶栏短暂显示当前平台名；或改为弹出平台选择列表 |
| 16 | 动效缺 Level 1-4 分层 | 不成立（无分层但模型够用） | `MotionPolicy.kt` 无层级枚举，采用二态+开关：`allowDecorative`(`:58`)、`decorativeCycle`(`:65`)、系统级 reduce(`:24-35`)、应用级开关(`:41-51`)；规则已覆盖 ≤200ms 反馈预算、非播放/离屏停循环、减少动效降级（`:11-18`） | P3 | 若要做分层，按"微交互/转场/装饰/全屏"四档扩枚举；现状对中端机已够用 |
| 17 | 架构图 Song→UrlResolver→Direct/Script/Server→encrypted?→MediaDecryptCache→Media3 | 成立（准确，两处需修正） | 实际顺序为串行回落：身份缓存命中 `UrlResolver.kt:180` → Direct(kg/mg/kw/wy/tx) `:214-261` → Server(`ApiClient.officialUrl`) `:270` → Script 池 `:274+`；加密判定与解密发生在 **PlaybackController 而非 UrlResolver**：`PlaybackController.kt:474`(`encryptedExtOf`)→`:477`(`MediaDecryptCache.prepare`)→`:491`(`PlayerService.playUrl` = Media3) | P3 | 图中两处修正：链路是 Direct→Server→Script 串行而非三路并列；MediaDecryptCache 画在 UrlResolver 之后、PlaybackController 内 |

---

## 三、核实结论摘要

- **真正需要立刻处理的只有 1 项**：编号 9（发版签名密钥与密码公开可下载，P0）。这是本次核实中唯一达到"上线前必改"级别的问题，且与 GPT 的定性一致、证据比 GPT 更具体（两个 URL 实测 200 可达）。
- **成立且值得排期的**：编号 1（解密双份全文常驻，P1）、4（死代码 clear()+清理语义，P1）、8（UI 隐私说明缺失，P1）、5（9 处 OkHttpClient，P2）、13（零 E2E，P2）、15（平台切换无反馈，P2）。
- **GPT 说轻或说错的**：编号 1 低估了峰值（200MB 文件实际 ~400MB 堆）；编号 14 方向错（VIP 探测有非 VIP 降级，不卡 loading）；编号 11 自相矛盾（27%/18%/12%/16% 均未过半）；编号 13 低估了测试覆盖（已有契约+策略测试）。
- **GPT 描述准确但属既定设计的**：编号 2（resolve 编排本就不在 MusicSource 契约内，注释明确委托给 UrlResolver）、编号 7（事务门已覆盖 resolve→decrypt→play 全链，含解密后二次校验）。
- **观察性结论**：版本节奏 ~6 版/天（编号 12）在零 E2E 测试的前提下确实是质量风险放大器，建议与编号 13 一并处理。
