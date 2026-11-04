# SoundWave 安全审计报告（网络与第三方脚本攻击面）

- 审计日期：2026-09
- 审计对象：`/root/soundwave-v2`（分支 `master`，纯静态审计，未运行构建）
- 本报告为只读审计产物，不修改仓库其他文件，未提交、未推送。

> **时效说明：**下方 F-01～F-16、攻击链、统计与原结论是 2026-09 的历史审计记录，不应直接视为当前分支状态。当前代码状态以文末“2026-10-08 当前分支静态复核”为准；已修复项的历史分析予以保留，避免误把旧风险当成仍未修复。

## 审计范围

| 范围 | 文件 / 依据 |
| --- | --- |
| 权限与组件暴露 | `app/src/main/AndroidManifest.xml` |
| 明文流量 / 信任锚 | `app/src/main/res/xml/network_security_config.xml` |
| 第三方脚本运行时与 JS 桥 | `ScriptRuntime.kt`、`ScriptNetworkPolicy.kt`、`ScriptStorage.kt` |
| API 网络层 | `ApiClient.kt`、`DownloadHelper.kt`、`UrlResolver.kt` |
| 凭据存储 | `LocalStore.kt`、`PlatformAccountStore.kt`、`MainActivity.kt` |
| WebView 使用 | `ScriptRuntime.kt`、`PlatformLoginActivity.kt` |
| 脚本来源 | `.gitignore`、`git ls-files`、`ScriptStorage.kt` |

范围事实：

- 权限：`INTERNET`、前台服务（媒体播放/数据同步）、通知、网络/多播状态、媒体读取、`READ_EXTERNAL_STORAGE`(≤API32)、`SYSTEM_ALERT_WINDOW`（`AndroidManifest.xml:3-15`）。无定位、通讯录、短信、相机、麦克风权限。
- 导出组件：仅 `MainActivity` 导出（LAUNCHER，`AndroidManifest.xml:32-42`）；两个 service 与 FileProvider 均 `exported=false`（`AndroidManifest.xml:43-60`）。未发现 deep link / 自定义 scheme 的 intent-filter。
- `app/src/main/assets/scripts/` 在 `.gitignore:8` 中被忽略，且 `git ls-files app/src/main/assets` 为空：仓库不含任何脚本资产，运行脚本全部来自用户导入（`ScriptStorage.kt:80-135`）。
- 全仓库 Kotlin 代码未发现 `ProcessBuilder` / `Runtime.getRuntime` / `exec(` 调用：脚本与宿主均无本地命令执行面。

## 发现清单

### F-01 全局明文流量放行 — 严重度：高

- 位置：`app/src/main/AndroidManifest.xml:24`；`app/src/main/res/xml/network_security_config.xml:4`
- `usesCleartextTraffic="true"` 且 networkSecurityConfig 的 `base-config cleartextTrafficPermitted="true"`，对所有域名生效（`network_security_config.xml:3` 注释说明动机是兼容外部脚本的历史 HTTP CDN）。任意 HTTP 明文域名均可被同网攻击者窃听/篡改。

### F-02 脚本可见响应头包含 Cookie — 严重度：高

- 位置：`ScriptNetworkPolicy.kt:22-25`；使用点 `ScriptRuntime.kt:974`、`ScriptRuntime.kt:1003`；回传 JS `ScriptRuntime.kt:520-522`、`629-694`
- `isResponseHeaderAllowed` 白名单显式包含 `set-cookie` 与 `cookie`，原生层会把目标服务器响应中的 Set-Cookie（含新会话凭据）回传给发起请求的脚本。脚本可读取后经自身另一次 `nativeFetch` 外发。

### F-03 Cookie 按 URL 注入脚本请求，且 host 匹配过宽 — 严重度：高

- 位置：注入点 `ScriptRuntime.kt:897-901`（直连与代理共用，代理 payload 见 `903-911`）；匹配逻辑 `PlatformAccountStore.kt:88-99`（`91-95` 用 `host.contains(...)`）
- 任何被启用脚本只要对命中 `music.163/netease/.126.net/qq.com/kuwo/kugou/migu/cmvideo` 等子串的 URL 发请求，原生层就自动附带对应平台登录 Cookie（来自 Keystore，`PlatformAccountStore.kt:17`）。`contains` 匹配过宽（如 `evil-qq.com`、`netease.evil.io`、`.126.net` 任意子域均命中）。
- 2026-09-10 复核：代码已是精确后缀匹配（`PlatformAccountStore.kt:104` `host == domain || host.endsWith(".$domain")` + `cookieHosts` 域名表），原文 `contains` 描述为修复前状态。

### F-04 JS 桥未按方法白名单收敛，任何页面级 JS 可用全部桥能力 — 严重度：中

- 位置：`ScriptRuntime.kt:141`（`addJavascriptInterface(Bridge(), "NativeBridge")`）；桥方法 `799-812`（`onUrlResult`/`nativeCancel`/`nativeFetch`）；内核引用 `496-511`
- 桥类未用注解/接口白名单限制可达方法（`@JavascriptInterface` 语义下所有公开方法均暴露）。当前 WebView 只加载 `loadDataWithBaseURL` 注入的内核页（`143-152`），未发现导航到外部页面的路径（待人工确认：未来迭代是否引入外部页面/iframe 加载；一旦引入，页面 JS 自动获得网络直连+取链回调全部桥能力）。
- 审计更正 (2026-09-09)：核实桥方法 `onUrlResult`/`nativeCancel`/`nativeFetch` 均已带 `@JavascriptInterface` 注解（`ScriptRuntime.kt:800/805/811`），暴露面本就是注解白名单；剩余风险仅为未来引入外部页面/iframe 加载时桥面扩大（原"待人工确认"保留）。

### F-05 所有脚本共享同一 JS realm，可互相干扰 — 严重度：中

- 位置：`ScriptRuntime.kt:752-771`（`window.__ensureScript` 用 `new Function` 在全局 realm 执行脚本，`fakeGlobal` 仅是参数别名）
- 脚本与内核、其他脚本共享 `window` 与内置对象原型。恶意脚本可篡改 `Array.prototype`/`Promise` 等影响其他脚本与内核回调逻辑，或探测其他脚本的存在与行为。内核关键全局在 `776-786` 被钉死（writable/configurable=false），回调表在闭包内（`513-520`）不可枚举，这两点缓解了部分风险，但 realm 隔离仍不存在。
- 补充事实：`runner(fakeGlobal, lx, console)` 为普通调用（`ScriptRuntime.kt:767`），非严格模式下脚本顶层 `this` 指向 WebView 真实全局对象，可借此触达页面原生 `fetch`/`XMLHttpRequest` 等能力（绕过 `fakeGlobal` 白名单直接由 WebView 出网，受同源/CORS 与混合内容策略约束；各脚本实际可达范围待人工确认）。即脚本的自主出网通道并非仅 `nativeFetch` 一条。
- 审计更正 (2026-09-19)：已收窄——`new Function` 前置 `"use strict"` + `runner.call(undefined, ...)`，脚本顶层 `this` 变为 `undefined`，切断「非严格模式 this→真实 window」逃逸通道。残留：正式函数体仍可经 `(0, eval)('this')` 逃逸（V8 级限制，Android 无 node:vm 等价物），完整隔离待可隔离 JS 引擎方案。⚠️ 真机验证义务：严格模式禁用的八进制字面量/`with`/`arguments.callee` 会让老脚本 init 时 SyntaxError 静默失效（走脚本池下一脚本兜底，不崩溃）——提交后必须做「五平台脚本取链 A/B」验证（含现役 http 端点脚本族）确认无脚本因严格模式失效。

### F-06 脚本直连允许明文 HTTP — 严重度：中

- 位置：`ScriptNetworkPolicy.kt:37`（scheme 白名单 `http`+`https`）；出口策略 `ScriptRuntime.kt:118-119`、`832-851`
- 脚本请求经 SSRF 校验后可直接走 `http://`（且受 F-01 全局明文允许），请求与响应（含被注入的 Cookie，见 F-03）均可被同网攻击者窃听篡改。
- 2026-09-10 复核：维持明文放行——脚本直连 http 属用户导入信任边界（SSRF/localhost/私网/DNS-rebinding 防护已覆盖，F-02 响应头已过滤 cookie）；现役脚本含 http 取链接口：175.27.166.236、music.nxinxz.com、api.vsaa.cn、hywmusicsource.xn--9tra.work、api-v2.yuafeng.cn、ynx.de5.net、103.79.184.97、api.liuyunidc.cn，风险记录在案。

### F-07 云服务器密码明文存于 SharedPreferences — 严重度：中

- 位置：`MainActivity.kt:2201`、`MainActivity.kt:3178`（`putString("user", ...).putString("pass", p)`）；登录后每次云同步复用（`MainActivity.kt:1056-1060`、`CloudSyncController.kt:113/164`）
- root/备份/提权本地攻击者可直接读取明文密码。对比：平台 Cookie 采用 Android Keystore AES-GCM 加密（`PlatformAccountStore.kt:17`、`193-221`），两者存储强度不一致。用户数据 JSON 亦明文存于 prefs（`LocalStore.kt:12-13`、`187-197`，无密码字段）。

### F-08 API 服务无证书固定 — 严重度：中

- 位置：`ApiClient.kt:35-46`（主 client）；`rg` 全仓库无 `CertificatePinner`/pin 配置
- 默认服务 `https://lp.cncnlp.xyz`（`MainActivity.kt:227`）仅依赖系统信任锚（`network_security_config.xml:5-7`）。设备装入了恶意 CA（用户证书/MDM/恶意 root）时登录凭据与云同步数据可被中间人截获。登录/保存接口明文传输密码字段（`ApiClient.kt:646`、`701-708`），加剧该风险。

### F-09 脚本取链回退代理指向云端自建服务器，脚本可借宿主身份请求任意 URL — 严重度：中

- 位置：`ScriptRuntime.kt:143-152`（baseURL=proxyBase）、`170-172`（setProxyBase）；赋值点 `MainActivity.kt:691/929/2703`、`PlayerService.kt:1091/1143`、`DownloadScheduler.kt:160/249`、`ScriptProbeScheduler.kt:109`（均为 `ApiClient.baseUrl`，可被用户改为自填域名，`MainActivity.kt:346-348/688`）
- 策略拒绝直连（HTTP/IP/内网）的脚本请求会以 POST JSON 打到该服务器 `/music/http`（`ScriptRuntime.kt:843-851`、`903-911`），由服务端代为出网。恶意脚本可把宿主当作匿名出网代理（源 IP 为服务器而非脚本方），并在服务器不可信/被接管时把 Cookie（F-03）投递给该服务器。另注：脚本请求超过 2MB 即拒绝（`ScriptRuntime.kt:37-48`），该限制同样约束了代理回包。

### F-10 部分网络客户端跟随 HTTPS 重定向 — 严重度：低

- 位置：`ApiClient.kt:1178-1179`（`headContentLength`）、`DownloadHelper.kt:42-44`、`UrlResolver.kt:897-898`（`followSslRedirects(true)`）
- 与脚本取链的严格策略（`ScriptRuntime.kt:88-89` 关闭自动跟随 + `970-995` 手动逐跳重验 SSRF）不一致。这些路径不注入 Cookie，影响限于重定向后的目标探测/下载（待人工确认：重定向目标是否需要同样的策略重验）。

### F-11 自填服务器仅强制 https，无域名校验 — 严重度：低

- 位置：`MainActivity.kt:346-348`、`688`（`takeIf { it.startsWith("https://") } ?: DEFAULT_SERVER_URL`）；`ApiClient.kt:82-119`（normalizeBase 对无 scheme 输入默认补 `https://`，`85-87`）、`122`
- 用户可把 API/脚本代理指向任意 https 域名；持久化与恢复路径一致（`DownloadScheduler.kt:41-45`）。风险是供应链方向（用户被诱导填恶意服务器），且叠加 F-08 无 pinning。属设计取舍，记录在案。

### F-12 WebView 加固现状（正面，非缺陷）— 严重度：低

- 位置：脚本 WebView `ScriptRuntime.kt:128-134`；登录 WebView `PlatformLoginActivity.kt:97-102`
- 两处 WebView 均 `javaScriptEnabled=true` 但 `allowFileAccess=false`、`mixedContentMode=NEVER_ALLOW`；登录页另设 `allowContentAccess=false`，且仅 `loadUrl` 平台官方登录页（`PlatformLoginActivity.kt:124`，URL 来自静态表 `PlatformAccountStore.kt:111-127`），未挂载任何 JS 桥，未发现请求拦截/应答注入逻辑。

### F-13 FileProvider 暴露整个 external-files 根 — 严重度：低

- 位置：`app/src/main/res/xml/file_paths.xml:5`（`ext_root` path="."）；provider `exported=false`（`AndroidManifest.xml:52-60`）
- `ext_root` 使 `Music/`、`Music/SoundWave/` 两条更窄声明（`file_paths.xml:3-4`）冗余。仅配合 `grantUriPermissions` 临时授权使用，外部 App 无法直接查询；收窄路径可减小误授权影响面。

### F-14 脚本导入链路的来源约束 — 严重度：低

- 位置：`ScriptStorage.kt:80-135`；`ApiClient.kt:127-136`（`httpGetRaw`）
- URL 导入强制 `https://`（`ScriptStorage.kt:132`），内容无哈希/签名校验，任何 https 端点返回体即可成为常驻脚本。脚本存储于 prefs（`soundwave_scripts`，`DownloadScheduler.kt:163`）。属第三方脚本模式的固有风险，结合 F-02~F-06 放大。

### F-15 用户可控字符串到 URL 的构建路径（现状梳理）— 严重度：低

- 搜索关键词/发现页 source：`URLEncoder.encode` 后拼接（`ApiClient.kt:379-383`、`415-419`）。
- 相对路径到绝对 URL：`abs()` 仅拼 `baseUrl`（`ApiClient.kt:337-341`）。
- 脚本返回的可播链接：要求 `http(s)://` 且 ≤2048 字符（`ScriptRuntime.kt:315` 附近 `pickUrl`；`ApiClient.kt:1164-1172` `playableUrl` 原样放行 http，交由播放器）。
- 下载地址强制 http(s) 前缀（`DownloadHelper.kt:115`）。未发现拼接后未编码/未校验 scheme 的路径。

### F-16 重定向策略（脚本链路，正面）— 严重度：低

- 位置：`ScriptRuntime.kt:88-89`（`followRedirects(false)`+`followSslRedirects(false)`）、`970-995`（手动 ≤4 跳，`983` 每跳重验 SSRF）
- 脚本网络层的重定向处理为全仓库最严格实现，307/308 保留方法与请求体（`988`）。与 F-10 形成对比，记录为现有正面控制。

统计：共 16 项发现，其中缺陷类 14 项（高 3 / 中 6 / 低 5），加固现状类（正面）2 项（F-12、F-16）。无凭猜测给出的结论；两处显式标注"待人工确认"的均为前瞻性风险而非现状结论。

## 风险评估（第三方脚本运行时的最小攻击链）

**攻击链 A：脚本读取平台会话 Cookie（无需其他漏洞，现实可行）**

1. 诱导用户导入并启用恶意 LX 脚本（用户导入是常规操作，`ScriptStorage.kt:80-135`）。
2. 脚本调用 `lx.request("https://music.163.com/...")`（或任意含 `qq.com` 等子串的 URL）；SSRF 校验放行公网域名（`ScriptNetworkPolicy.kt:37-44`）。
3. 原生层自动注入平台 Cookie（`ScriptRuntime.kt:897-901`）。
4. 目标服务器响应的 `Set-Cookie`（含会话刷新）被列入白名单回传脚本（`ScriptNetworkPolicy.kt:24-25`、`ScriptRuntime.kt:974/1003/520`）。
5. 脚本对自有公网域名再发起一次 `nativeFetch` 外发数据（公网直连策略放行）。

前置条件仅一项：用户导入恶意脚本。后果：网易云/QQ/酷我/酷狗/咪咕任一平台的登录会话泄露，账号被盗用。

**攻击链 B：同网中间人篡改**

1. 攻击者与受害者同处可控网络（公共 Wi-Fi 等）。
2. 受 F-01/F-06 影响：脚本可请求 `http://` 源、任意域名可被明文访问。
3. 篡改脚本响应（取链结果、歌词、脚本更新内容）或替换 F-14 中经 URL 导入的脚本（若为 http 则直接被拦，若 https 则不可篡改）。

**攻击链 C：服务器侧信任放大**

1. 自建/被接管的 `proxyBase` 服务器（F-09）接收脚本的全部被拒直连请求（URL/头/体，含 Cookie）。
2. 服务器可返回伪造取链结果或记录投递内容。

**不成立的链（现有控制有效）**：脚本无法读写文件、无法执行命令（无对应桥方法，无 `exec`）；脚本间回调表不可枚举（`ScriptRuntime.kt:513-520` 闭包）；内核全局钉死后不可覆盖（`776-786`）；脚本请求不能到达内网/元数据地址（`ScriptNetworkPolicy.kt:39-78`）；`onUrlResult` 伪造只能完成自己的 capability（UUID 不可猜，`ScriptRuntime.kt:95`）。

## 修复优先级

**P0（上线前必改）**

1. `ScriptNetworkPolicy.kt:24-25`：从响应头白名单移除 `set-cookie`、`cookie`。脚本无正当理由需要读取会话 Cookie；这一行同时掐断攻击链 A 的第 4 步。
2. `network_security_config.xml:4`：`base-config cleartextTrafficPermitted="false"`，确需 HTTP 的既有 CDN 改用 `<domain-config cleartextTrafficPermitted="true">` 显式枚举最小集合；同步评估 `AndroidManifest.xml:24` 改为 `false`。

**P1**

3. `PlatformAccountStore.kt:91-95`：host 匹配改为精确后缀（`.music.163.com`、`.126.net` 等域名后缀表），并评估脚本请求默认不注入 Cookie、改为按脚本声明的域名显式授权。
4. `ScriptRuntime.kt:141/799-812`：桥收敛为最小接口对象（仅暴露 `nativeFetch/nativeCancel/onUrlResult` 的白名单注解实现），为后续页面加载路径消除桥面扩大风险。
5. `ScriptRuntime.kt:752-771`：为每个脚本提供独立隔离（逐脚本 IIFE 已做，建议补 prototype 冻结或迁移到可隔离的 JS 引擎）；至少在文档与导入提示中声明脚本非沙箱。
6. `MainActivity.kt:2201/3178`：云服务密码迁移到 Keystore 加密（对齐 `PlatformAccountStore.kt:193-221` 的 AES-GCM 方案），存量明文做一次性迁移清除。
7. `ApiClient.kt:35-46`：对 `DEFAULT_SERVER_URL` 增加 certificate pinning（或至少 pin 备用 SPKI），降低恶意 CA 场景下密码/同步数据暴露。

**P2**

8. `ApiClient.kt:1178-1179`、`DownloadHelper.kt:42-44`、`UrlResolver.kt:897-898`：统一 `followSslRedirects(false)`，确需重定向的路径手动逐跳校验（对齐 `ScriptRuntime.kt:970-995`）。
9. `file_paths.xml:5`：删除 `ext_root` 通配路径，保留业务所需窄路径。
10. `ScriptStorage.kt:130-135`：URL 导入脚本时展示来源域名 + 内容 SHA-256，启用前二次确认；可结合服务器侧脚本市场白名单（待人工确认是否有该规划）。
11. `ScriptRuntime.kt:903-911`：代理回退请求默认剥离 Cookie 头，或仅对脚本显式声明的目标域注入。

## 结论

**历史结论（2026-09）：**当时报告认定 Cookie 注入与响应头回传构成可窃取会话的攻击链。该结论已被后续代码修复及本次复核取代；仍存在的明文流量、共享脚本运行环境与可配置代理风险，见下方当前状态补记。

## 2026-10-08 当前分支静态复核

- **范围与边界：**本次复核对象为 `feat/neumorphic-ui-refresh`，HEAD `c9d5858858b591871f937ca4088e3522f340e888`，版本 `1.0.546`；只读检查，没有运行 Gradle、Android SDK、ADB、模拟器或 Codemagic，也没有在线请求。本节仅更新审计记录，未改网络、脚本、代理或 `UrlResolver` 策略。
- **已不再成立的历史描述：**F-02/F-03 的会话 Cookie 攻击链已被切断：`ScriptNetworkPolicy` 请求头白名单不含 `Cookie`，响应头白名单不含 `Cookie`/`Set-Cookie`；当前 `ScriptRuntime` 请求路径不再读取或注入平台账号 Cookie。`third-party-account-removal-guard.sh` 静态检查通过。F-04 桥方法现有 `@JavascriptInterface` 注解；F-05 的脚本执行已加 strict mode 并以 `runner.call(undefined, ...)` 运行，历史报告所述顶层 `this` 逃逸已被收窄，但这不等于脚本 realm 隔离。F-07 当前新凭证使用 Android Keystore AES-GCM，旧明文读取路径会尽力迁移为密文。F-13 当前 FileProvider 仅声明 Music、backup 和 app 内 covers 路径，没有旧报告所述的 external-files 根通配路径。F-14 的设置页 URL 导入先取回脚本，再展示来源域名与 SHA-256 并由用户确认后导入。
- **仍需保留的风险：**Manifest 与 network security config 仍全局允许明文 HTTP，`ScriptNetworkPolicy` 也仍接受 `http` URL；这是兼容现有脚本端点的既定风险，不能据本次静态复核视为消失。脚本仍共享 WebView JavaScript realm，未提供完整隔离。直连失败/策略拒绝后的 `/music/http` 代理路径仍存在；当前代理请求会携带脚本提供的 URL、method、过滤后的 headers 和 body，但代码路径不再自动加入平台 Cookie。默认 `lp.cncnlp.xyz` 已配置证书 pin；用户自填 HTTPS 服务只受系统 CA 信任、无默认域名限制与 pin。通用 `AppHttp` 仍跟随 TLS 重定向，`ApiClient` 则禁止 HTTPS 降级，脚本请求使用手动重定向和逐跳策略校验。
- **历史统计与攻击链 A/B/C 的适用性已变化：**旧统计数字、P0/P1 建议及旧“攻击链 A 可窃取平台会话”的表述仅供追溯；攻击链 A 不符合本次检查的当前代码，不能作为当前结论。残余风险的确认仅为静态源码证据，不构成构建、真机网络行为或上游服务验收。
