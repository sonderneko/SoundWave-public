package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject

/**
 * 网易云取链的两段本机实现（批8e outer 段 + I 波阶段 1 的 2b oiapi 段），
 * ACCEPT5 起两段在链中的位置分离（用户裁定一：脚本先于 oiapi）：
 *
 * 第一段 outer（位置不变：官方阶段、低音质档、脚本之前）：1:1 移植服务端
 * /music/url wy 官方段的 outer 部分（线上 /opt/soundwave/music_server.py）：
 * - GET https://music.163.com/song/media/outer/url?id={song_id}.mp3，不跟随重定向
 *   （followRedirects=false），自读首个 Location（等价服务端 HTTPRedirectHandler 捕获）
 * - 有效性判定：Location 以 http 开头且不含 '404' 子串，否则视为无直链
 * - 不携带账号 Cookie；匿名请求可用（免费歌曲由平台决定可用音质）
 * ACCEPT5（A）：outer 改走结构化结果 [wangyiOuterResolveOutcome]——旧 String
 * 形态把「跳转 404（受限）/ 非重定向应答 / 传输超时 / DNS 失败 / 策略拒绝」
 * 全压成空串，真机失败时诊断无法定性；现以 detail 短码逐类带回（见
 * [WyResolveOutcome.Empty]）。注意一个已证事实：用户实际音质档（flac/master）
 * 下官方阶段被音质谓词整体关闭，outer 在那些档位**根本不会被调用**——其
 * 「零产出」首先是谓词事实而非网络结论；低档 trace 里若再现失败，detail
 * 即可在上述类别间定性。
 *
 * 第二段 oiapi（ACCEPT5 起的位置：脚本链之后、本机兜底，不再于官方阶段
 * 与脚本之前出场）：outer 对受限歌曲返回 302→404 时的等价解，等价服务端
 * wy 分支的 oiapi 段（2026-11-04 对线上实现只读核对 + 黑盒探针验证）：
 * - GET https://oiapi.net/api/Music_163?id={song_id}，匿名、无密钥/无 Cookie、
 *   **无音质参数**，总超时 8s（与服务端 timeout=8 对齐）
 * - 解析：JSON `data` 为非空数组时取首项 `url`（`picurl` 仅服务端回包封面
 *   字段，取链闭环不消费，与 kg 直连不回写 cover 的先例一致）
 * - 音质真实性（ACCEPT5 B）：oiapi 响应全字段（2026-11-04 黑盒实测：name/
 *   picurl/id/singers/url/jumpurl/pay）**不含任何音质/码率/大小信息**——
 *   它给的档位无从由响应自证。命中后的实际档位由调用方以探针实测
 *   （格式 + 总字节 ÷ 时长）定档，无证据时按保守地板标注，禁止拿请求档
 *   冒充结果档（ACCEPT4 时代曾把低码率 mp3 标成「MASTER 成功」）。
 * - 与服务端顺序的差异：服务端在 oiapi 之前还有一段登录 Cookie 的官方
 *   eapi（本 app 按既定设计永不持有平台登录态，不可搬）；本机顺序按用户
 *   裁定一为 outer（低档）→ 脚本链 → oiapi 兜底。
 * - 出链归一：复用 normalizeKgMediaUrl（http→https 提升，等价服务端响应层
 *   normalize_media_url；失配由播放前 probe 兜底）。
 * oiapi 是纯本机段且为 wy 链尾：outer 与脚本链皆空后由它兜底出场，其后
 * 再无服务端段（/music/url 服务端兜底已整条删除）。API 请求本身走 platformHttp
 * （https 过 ApiUrlPolicyInterceptor 放行）；返回的媒体 URL 属播放路径
 * 豁免面，不经 HttpPolicy API 闸门。
 */

/** 取链一段的结构化结果：命中带 URL；未命中带可诊断的 detail 短码（不含 URL/正文/凭证）。 */
internal sealed interface WyResolveOutcome {
    data class Found(val url: String) : WyResolveOutcome
    data class Empty(val detail: String) : WyResolveOutcome
}

/** 传输层失败 → detail 短码（与 PlatformTransportErrorKind 一一对应，HTTP 状态单独带出）。 */
private fun transportDetail(error: PlatformTransportError): String = when (error.kind) {
    PlatformTransportErrorKind.TIMEOUT -> "transport_timeout"
    PlatformTransportErrorKind.HTTP_STATUS -> "transport_http_${error.httpStatus ?: 0}"
    PlatformTransportErrorKind.SSRF, PlatformTransportErrorKind.POLICY -> "transport_policy"
    PlatformTransportErrorKind.COOLDOWN -> "transport_cooldown"
    else -> "transport_network_${error.detail ?: "io"}"
}

/** 第一段：网易云官方 outer 直连的结构化形态（批8e 语义不变，结果带失败定性）。 */
internal suspend fun wangyiOuterResolveOutcome(songId: String): WyResolveOutcome {
    if (songId.isBlank()) return WyResolveOutcome.Empty("blank_id")
    val target = "https://music.163.com/song/media/outer/url?id=$songId.mp3"
    return when (val outcome = platformHttpRedirectOutcome(target, referer = "https://music.163.com/")) {
        is PlatformTransportOutcome.Failure -> WyResolveOutcome.Empty(transportDetail(outcome.error))
        is PlatformTransportOutcome.Success -> {
            val reply = outcome.value
            val location = platformRedirectLocation(reply.status, reply.location)
            when {
                location == null ->
                    if (reply.status in setOf(300, 301, 302, 303, 307, 308)) {
                        WyResolveOutcome.Empty("redirect_no_location")
                    } else {
                        WyResolveOutcome.Empty("no_redirect_${reply.status}")
                    }
                !location.startsWith("http") -> WyResolveOutcome.Empty("redirect_invalid")
                location.contains("404") -> WyResolveOutcome.Empty("redirect_404")
                else -> {
                    val normalized = normalizeKgMediaUrl(location)
                    if (normalized.isEmpty()) WyResolveOutcome.Empty("redirect_invalid")
                    else WyResolveOutcome.Found(normalized)
                }
            }
        }
    }
}

/** 第一段的 String 兼容形态（结果不带定性；需要定性的调用方用 [wangyiOuterResolveOutcome]）。 */
internal suspend fun wangyiOuterResolveUrl(songId: String): String? =
    (wangyiOuterResolveOutcome(songId) as? WyResolveOutcome.Found)?.url

/** 第二段：oiapi Music_163 匿名取链的结构化形态（I 波 2b 语义不变，结果带失败定性）。 */
internal suspend fun wangyiOiapiResolveOutcome(songId: String): WyResolveOutcome {
    if (songId.isBlank()) return WyResolveOutcome.Empty("blank_id")
    return when (
        val outcome = platformHttpGetOutcome(
            url = "https://oiapi.net/api/Music_163?id=$songId",
            referer = "https://music.163.com/",
            timeoutMs = 8000L
        )
    ) {
        is PlatformTransportOutcome.Failure -> WyResolveOutcome.Empty(transportDetail(outcome.error))
        is PlatformTransportOutcome.Success -> parseWyOiapiOutcome(outcome.value)
    }
}

/** 第二段的 String 兼容形态。 */
internal suspend fun wangyiOiapiResolveUrl(songId: String): String? =
    (wangyiOiapiResolveOutcome(songId) as? WyResolveOutcome.Found)?.url

/** 纯解析（与网络分离，可测）：data 首项 url + http→https 归一；无出链按形态分 detail。 */
internal fun parseWyOiapiOutcome(raw: String): WyResolveOutcome {
    val d = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return WyResolveOutcome.Empty("oiapi_bad_json")
    }
    val data = d.optJSONArray("data") ?: return WyResolveOutcome.Empty("oiapi_no_data")
    if (data.length() == 0) return WyResolveOutcome.Empty("oiapi_no_data")
    val item = data.optJSONObject(0) ?: return WyResolveOutcome.Empty("oiapi_no_data")
    val url = if (item.isNull("url")) "" else item.optString("url")
    if (url.isBlank()) return WyResolveOutcome.Empty("oiapi_no_url")
    val normalized = normalizeKgMediaUrl(url)
    return if (normalized.isEmpty()) WyResolveOutcome.Empty("oiapi_no_url") else WyResolveOutcome.Found(normalized)
}

/** 纯解析的 String 兼容形态（既有调用/测试口径）。 */
internal fun parseWyOiapiResolve(raw: String): String? =
    (parseWyOiapiOutcome(raw) as? WyResolveOutcome.Found)?.url
