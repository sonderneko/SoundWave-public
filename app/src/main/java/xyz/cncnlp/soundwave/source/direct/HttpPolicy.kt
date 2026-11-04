package xyz.cncnlp.soundwave.source.direct

import java.net.URI
import java.io.IOException
import okhttp3.Interceptor

/**
 * 明文流量策略（Local-first 安全批）。
 *
 * 分层原则（与 GPT 审计「Cloud/Account/Platform API HTTPS only + Media CDN explicit exception」对齐）：
 * - 托管 API 与平台直连 API 只允许 HTTPS，或白名单中的精确 HTTP 主机。
 * - 明文豁免只限实测 HTTPS 不可用的 Kugou mobilecdn API 主机。
 * - 媒体播放 URL 不走此策略：历史 HTTP 音频 CDN 需由独立媒体 URL 策略处理。
 *
 * 使用 URI 解析完整 scheme/host，避免字符串切割造成大小写、userinfo、异常 URL 等边界绕过。
 */
internal val ALLOWED_CLEARTEXT_API_HOSTS: Set<String> = setOf(
    "mobilecdn.kugou.com",
    "mobilecdnbj.kugou.com",
)

/** HTTPS 放行；HTTP 仅允许精确白名单主机及默认 HTTP 端口；其他 scheme、无效 URL 一律拒绝。 */
internal fun isCleartextApiAllowed(url: String): Boolean {
    val uri = try {
        URI(url.trim())
    } catch (_: Exception) {
        return false
    }

    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    if (uri.rawUserInfo != null) return false

    val port = uri.port
    if (port > 65535 || port == 0) return false

    return when (scheme) {
        "https" -> true
        "http" -> host in ALLOWED_CLEARTEXT_API_HOSTS && (port == -1 || port == 80)
        else -> false
    }
}

/**
 * Validate every actual network exchange, including each OkHttp redirect hop.
 * Attach only to API clients; never attach to the shared media/download client because
 * legacy audio CDN URLs may legitimately use HTTP.
 */
internal class ApiPolicyRejectedException : IOException("Blocked API URL by transport policy")

internal class ApiUrlPolicyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val url = chain.request().url.toString()
        if (!isCleartextApiAllowed(url)) {
            throw ApiPolicyRejectedException()
        }
        return chain.proceed(chain.request())
    }
}
