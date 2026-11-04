# r657 账号/头像/在线导入/付费专辑：只读取证

## 范围与结论

仓库 `/root/soundwave-r657-r3` HEAD `fb660e769edfd0c892c48152b55c0d6f96a0b575`。`/root/sollin-research` 与 `/root/lx-music-desktop` 均存在，已对照 Sollin 自有 services 和 LX 源码。未改生产文件/服务端/用户登录数据，未安装 SDK、未跑 Gradle，只做匿名 GET 探测。下文 Kotlin 路径均相对 `app/src/main/java/xyz/cncnlp/soundwave/`。

核心结论：
1. **密码确定根因：AndroidKeyStore GCM 密钥 randomized=true，却传入 caller-provided IV；官方文档明确禁止。当前三次重试是在重复同一错误。**
2. **头像确定缺口：固定路径原地覆盖，Compose/Coil 只有路径身份，没有内容 revision；启动强制空头像且只从认证云响应恢复，密码未保存会连带回退。**
3. **导入确定缺口：专辑分享直接拒绝，在线入口只有 playlist parser；短链回落的当前服务端实际不支持短链/多平台 URL，反而返回 type=lines 的空 ID 假成功。标准 4 平台歌单端点本机均有真实数据，不能断言全部官方接口失效。**
4. **付费专辑：网易/酷狗真实实体、付费标记和匿名拒绝有原始证据；同时客户端严重丢失 albumId/各档 hash/audio id。未取得用户“长青 svip 二改”脚本，不能宣称修好付费播放。**

## 一、密码：真实链路、证据、最小方案

### 调用链
- `MainActivity.kt:2708-2734`、`:3579-3603` 两个登录入口 → ApiClient.auth 成功 → 先设 loggedIn/user/pass → SecretStore.encrypt；返回 null 仍持久化登录态和用户名，不写新密码。
- `SecretStore.kt:27-42`：随机 12 字节 IV（`:33`）→ `cipher.init(ENCRYPT_MODE,keyForEncrypt(),GCMParameterSpec(128,iv))`（`:35`）→ catch 所有 Exception，重试 3 次后返回 null。
- 同文件 `:149-156` 生成 AES/GCM 密钥时 `.setRandomizedEncryptionRequired(true)`。
- `CloudSyncController.kt:96-104` 分别恢复登录布尔、用户名、密码；`:318-344` 缺密码/坏密文/暂不可读分别标 missing/corrupted/unavailable；`:120-143` 只有密码非空才做启动认证云快照恢复。
- `:353-375` ensurePassPersisted 虽尝试自愈，但仍调用同一错误 encrypt。`:396-419` 显式入口恢复不到密码则开重输 Sheet。
- `AccountPasswordReentry.kt:63-70` → `CloudSyncController.reenterPasswordAndSync(:442-471)`：服务器认证后写内存密码，调用返回 Unit 的 ensurePassPersisted，**不检查落盘是否成功便关闭面板**并同步，可导致“验证成功后重启又没保存”。

### Android 官方原文（已实际联网读取）
https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setRandomizedEncryptionRequired(boolean)

`in block modes which use an IV, such as GCM, CBC, and CTR, caller-provided IVs are rejected when encrypting`。

推荐做法：`let the Cipher generate a random IV ... initialized for encryption without an IV ... Cipher.getIV()`。

原文摘录 `/tmp/r657-official-probes/android-randomized-iv.txt`。AOSP `AndroidKeyStoreKeyGeneratorSpi.java:361-366` 同样显示只有 randomized=false 才添加 KM_TAG_CALLER_NONCE。此为 API 合约+源码证据，本次没 Android 真机执行。

### 最小修复（未实施）
- 保留 randomized=true、原 key alias 和 v2 密文格式；加密改 `cipher.init(ENCRYPT_MODE,keyForEncrypt())`，再取 `cipher.iv`，封装 `v2:` + Base64(iv||ciphertext)。不删钥、不禁安全策略、不回存明文；旧密文仍可原样解密。
- 持久化函数返回明确结果；重输“验证通过/仅内存可用/安全存储成功”分开，失败不能默默关 Sheet。
- 合并两处登录存储 helper，绑定账号 identity：当前换账号 encrypt=null 时可能新用户名配旧账号密文，不能保留这种组合而说已恢复。
- 必须补真 AndroidKeyStore roundtrip/二次加密/重启恢复测试，JVM mock 不能验证 caller-nonce 策略。

## 二、头像：固定路径、启动回退、旧云响应竞态

- `MainActivity.kt:3350-3362` 裁剪确认 → `AvatarStore.saveFromBitmap` → `account.acceptExplicitAuthenticatedAvatar`；未检查 accept 返回值仍提示“头像已保存”。
- `AvatarStore.kt:20-22,193-209` 文件固定为 user_avatar_<safeUser>.jpg 原地覆盖；`:265-277` 云端恢复也覆盖同路径。
- `CloudSyncController.kt:237-247` 接受头像只赋 `avatarPath=path`；同一路径等值写 mutableState 不产生内容版本。`AccountUi.kt:151-157` Coil AsyncImage 直接 `model=avatarPath`，没有 revision/cache key，也没 error placeholder。
- `MainActivity.kt:493` 所有页读同一 account.avatarPathState；`:797-800` avatarNeedSync 只负责 persistBundle，不负责内容身份。确定存在“文件换了但首页不立刻刷新”的机制；黑图具体是缓存/解码/回退哪一种无真机证据。
- 冷启动 `CloudSyncController.kt:83-85` 强制空头像；认证会话开始 `:146-150` 也清空，只允许非空认证云 avatar 在 `:775-782` 恢复。密码故障会阻断 `:120-143` 云恢复，本地文件存在也不显示。这是当前防同名账号继承旧头像边界，不能简单加 AvatarStore.path() 绕过。
- `LocalStore.kt:109,431,566` 保存/读取 avatarB64；但账号上传 `CloudSyncController.kt:723-728` 只取本会话 verifiedAvatar 文件，不会把未认证本地回退头像上传。
- 同账号竞态：启动云读取 `:120-138` 只校验 account generation；显式换头像不改变头像 generation，早先发起的旧云 avatar 可通过 `:1045`→`:775-780` 覆盖刚选的新文件。

最小方案：引入 avatarRevision（每次成功落盘递增），Coil 请求缓存身份带 revision 或改不可变版本文件；本地路径不可随意加 query 当文件名。云读取捕获 avatar mutation generation，迟到旧响应不得覆盖新选择。检查 accept 返回值/加载错误。先修密码；要离线恢复头像必须先定义稳定 owner 绑定，保留退役账号隔离。真机验收连续换图不切页即变、冷启动、慢云响应不覆盖新图。

## 三、导入：系统分享 → 直连 → VPS → 本机/云存储

### 调用链与缺口
- `SchemeActionHolder.kt:56` ACTION_SEND → `ShareLinkParser.kt:30-72`：显式歌单识别；`:62` 显式专辑/歌手 **直接 Notice 拒绝**；短链只归平台后转 ImportPlaylist，不展开。
- `ui/common/AppDialogs.kt:126-141` 入口只有“在线歌单/歌单链接或ID”；`MainActivity.kt:1296-1314` → ApiClient.importPlaylist → 成功入 userBundle 本地 persistBundle，**不要求 SoundWave/WebDAV 登录**；可播性脚本检查在成功导入之后，不是导入前置门。
- `ApiClient.kt:1396-1398` → `source/direct/PlaylistImportDirect.kt:13-59`：有限五平台长 URL/网易裸数字 ID → fetchPlaylistDetailPayloadDirect。没有 album parser、短链展开、酷狗新版 /songlist/... 解析。
- `PlaylistDetailDirect.kt:64-108,122-173` 分派官方：wy (`RankDirectFetch.kt:276-293`)、tx musicu aiDissInfo、kg special/song、kw pl.svc、mg playlist/song。
- 失败 `ApiClient.kt:1412` 调 VPS `/music/playlist?action=import&text=...`；仅**响应空串**才走 `:1413-1418` detail fallback。非空 ok=true/type=lines 不触发该分支。
- 当前线上 `/opt/soundwave/music_server.py:3119-3158` parse_playlist_import 只优先猜网易 ID，之后把每行当普通歌曲文本，返回 id='' / src='kw' / type=lines；无 redirect resolver、无多平台 URL parser、无 album parser。路由 `:4778-4785` 直接调用它。
- `ApiClient.kt:417-425` 丢弃无 ID 曲目；`:1435-1443` 再猜六位 ID 去 playlistDetail。专辑 ID 也走 playlistDetail 而非 albumDetail，短链无 ID 则失败。
- 专辑详情本身已有 `AlbumDirectFetch.kt:51-58`、`AdditionalAlbumDirect.kt:12-42`，只是没接导入。

### 匿名端点实测
原始响应与每批 index 在 `/tmp/r657-official-probes/`。

| 文件前缀 | 原始端点/样本 | 结果 |
|---|---|---|
| wy_playlist_actual | music.163.com/api/playlist/detail?id=3778678（客户端实际端点） | HTTP200，result.tracks=200 |
| wy_playlist | api/v6/playlist/detail 同 ID（对照） | HTTP200，playlist.tracks=10，新旧结构/数量不同 |
| tx_playlist | u.y.qq.com/cgi-bin/musicu.fcg，aiDissInfo，disstid=9204839136 | HTTP200，code=0，detail.data.songlist=30 |
| kg_playlist_http | http://mobilecdn.kugou.com/api/v3/special/song?specialid=125032&page=1&pagesize=1000 | HTTP200，status=1，data.info=15；text/html 但正文 JSON |
| kg_playlist_https / kg_album_search | mobilecdn.kugou.com 同域 HTTPS | TLS CERTIFICATE_VERIFY_FAILED/hostname mismatch；不可机械把当前白名单 HTTP 改 HTTPS |
| kw_playlist | nplserver.kuwo.cn/pl.svc，pid=1082685104，rn=1000 | HTTP200，musiclist=545，正文约1.66MB |
| vps_import | lp.cncnlp.xyz/music/playlist?action=import，网易长歌单 | HTTP200，ok=true,type=netease,list=200 |
| vps_kg_import/vps_kw_import | 同 import，酷狗/酷我长歌单 | HTTP200，ok=true,type=lines，唯一行 id=''、name=整条URL；服务端没真解析 |
| vps_actual_album_import | 同 import，网易 album?id=404060139 | HTTP200，ok=true,type=lines，id=''；专辑导入未实现 |
| wy_vps_album | /music/album?id=404060139&source=wy | HTTP200，正确专辑，songs=1；专辑详情正常但没接导入 |

用户原分享链接/设备网络未提供，不能认定本机正例已复现其“全失败”。已证明不是四平台官方目录全线失效。

最小方案：统一 typed reference(playlist/album/song/unsupported/shortlink)，专辑路由已有 albumDetail；短链客户端有限跳转、每跳校验平台域/scheme/禁止私网及异常 userinfo/端口；URL 不能退化文本歌曲，返回 typed outcome 区分不识别/网络/空/结构错误。导入先本机落盘，云失败单独报告。补五平台长链接、短链、整段文案、片段路由、专辑、坏 ID 用例。

## 四、Rise Without A Sound：实体、付费、脚本上下文

### 官方证据
- 网易搜索第一条是别人的同名单曲，不可直接取首条；确认林俊杰：专辑 `404060139` → 曲目 `3448583869`，dt=220160ms，fee=4，h/sq/hr/l 元数据均有。原始 wy_album_search.txt、wy_jj_album.txt。
- 匿名 `/api/song/enhance/player/url?ids=[3448583869]&br=320000`：**HTTP200、顶层code=200，但 item code=404/url=null/payed=0/cannotListenReason=1**。这是匿名不可播放结果，不是 transport404，也不能看顶层200说可播。
- 酷狗专辑 `210575933`；album/song 返回真实曲目：
  - 128 hash `0B183AB16D68361FAAAB44723C46EE1C`
  - 320 hash `8C6044C8A11492C391B420A5E4EA7932`
  - FLAC hash `962C7904162897702DE6E49E43EA167F`
  - album/song audio_id=1108659761、album_audio_id=971872296；搜索 Audioid=661713079，**接口身份不同，不可自行当等价**。
  - pay_type/pay_type_320/pay_type_sq=2；搜索 TopicRemark=购买专辑。目录可读≠音频授权。

### 客户端确定丢失
- `Models.kt:144-169` 只存一个 hash，没有按档 hash/types/audio id 字段。
- `source/direct/KgDirectSearch.kt:78-95` 仅保留 FileHash 或 HQFileHash 之一；`PlaylistDetailDirect.kt:271-292` 也仅保留一个 hash，抛弃其它 hash/audio id。
- `UrlResolver.kt:1130-1166` buildMusicInfo **albumId=''、interval=''、types=[]**；酷狗 songmid=hash，而 LX 是 audio id；`:1151-1154` 给 128k/320k/flac/hires **复用同一个 hash**，与本曲官方三个不同 hash 直接矛盾。
- 网易专辑解析 `AlbumDirectFetch.kt:124-133` 本身未写 albumId；其它来源即使写了也被 buildMusicInfo 清空。
- LX `kg/musicSearch.js:15-65` 保存各档 FileHash/HQFileHash/SQFileHash、songmid=Audioid；`kg/songList.js:700-750` 保存 albumId/audio_id/types。网易 `wy/musicSearch.js:78-90` 保存 albumId/interval/types。Sollin 自有 `src/services/officialMusicApi.ts:233-261` 也保留 albumId/songId/strMediaMid/hash/types/_types。

### quality/Runtime 链
- `UrlResolver.kt:353-395` 平台偏好→首选/320/128→直接 runtime.getMusicUrl；未按脚本 source 声明音质求交集。
- `ScriptRuntime.kt:507-523` source key 映射后直接发 `h({source,action:'musicUrl',info:{type:__q,musicInfo:__info}})`，不检查其声明 musicUrl action/quality。hires 与 flac24bit 等需根据实际声明兼容，不能盲猜。
- `UrlResolver.kt:398-410` 出链后还做媒体 GET；403/非音频/试听是媒体层失败，不等价脚本协议失败。不能删门禁冒充可播。
- 未取得用户“长青 svip 二改”脚本文件，未运行其真实签名/网络/取链；QQ/酷我可播不证明网易/酷狗该买的专辑已获同样授权。

最小方案：贯通可选 provider/LX metadata（albumId、duration、audio/album_audio id、按档 hash/types/size），持久化→搜索→详情→导入→musicInfo 一致，缺失保持未知，不造 hash。保留 Song.id 缓存/事务身份，另映射 provider songmid。声明能力时约束 source/action/quality，未知声明兼容；用同脚本同实体同音质对照 Sollin request envelope，只记安全字段/业务状态/媒体状态。**修 metadata 不等于绕过购买限制；官方拒绝仍要如实显示。**

## 五、WebDAV / VPS 依赖

| 功能 | 当前真实依赖 |
|---|---|
| SoundWave账号/启动云快照/账号同步 | CloudSyncController.kt:35-38,120-138,705 → ApiClient auth/load/save；默认 MainActivity.kt:243 `https://lp.cncnlp.xyz`，非音乐平台账号 |
| 标准歌单导入 | 官方直连优先，失败仍用 VPS import/detail；不要求用户登录，不可宣称全无 VPS |
| 专辑详情 | 官方直连+VPS album fallback，导入未接 |
| LX取链网络 | 本机脚本；ScriptRuntime.kt:1895,1954 保留 `/music/http` POST 代理 fallback，不是所有请求都不依赖VPS |
| WebDAV凭据 | 独立用户配置HTTPS；sync/WebDavSavedSession.kt:49-68 成功同步后才存，使用 SecretStore，**同一 IV bug 连带影响 WebDAV 密码保存** |
| WebDAV协议 | sync/WebDavSync.kt:366-438 PROPFIND→MKCOL SoundWave子目录→GET→条件PUT；409/412有限重读；`:450-467` profile独立授权 |
| WebDAV本机应用 | CloudSyncController.kt:1102-1125 仅本地应用不调用 persistBundle，避免自动二次传VPS；无账号头像/账号凭据/脚本代码同步 |

## 验证边界与产物

- `/tmp/r657-anonymous-probe.py` 为实际匿名请求脚本；`/tmp/r657-official-probes/` 为原始响应与四批索引。TLS失败未跳过证书验证，未用合成数据替代。
- 未编译/未真机/未跑 JUnit，本报告为源码调用链+官方API合约+真实匿名端点证据，不能越级声称 APK 已修。
- execute_code内部terminal曾默认指向不存在的 /root/Horizon，后续显式workdir=/root；一次只读Python访问服务源码被网关扫描误拦，改 read_file 成功，未操作服务。
- 末次git status出现 DetailTopChrome.kt、ui/motion/SettingsPush.kt、ui/route/AppRootMainTabRouteRenderer.kt 改动，可能为并行代理；本子代理未写/回滚这些文件。本次只创建 /tmp 产物。
