package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import xyz.cncnlp.soundwave.AppHttp
import xyz.cncnlp.soundwave.officialPlatformCall

/** Client-side anonymous platform HTTP transport. Legacy String callers remain supported. */
internal const val PLATFORM_HTTP_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val WY_ANONYMOUS_PROTOCOL_MARKER = "os=pc"

internal suspend fun platformHttpRequest(request: suspend () -> String): String =
    platformHttpRequestOutcome(request).legacyString()

private val sharedClient: okhttp3.OkHttpClient = AppHttp.shared.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .addNetworkInterceptor(ApiUrlPolicyInterceptor())
    .build()

private val platformCooldown = PlatformTransportCooldown()

private fun platformKey(url: String): String =
    runCatching { url.toHttpUrlOrNull()?.host ?: url }.getOrDefault(url)

/**
 * Structured GET. HTTP status, timeout, SSRF/policy rejection and cooldown are retained internally.
 * ACTIVE_PLAYBACK is never silently delayed; callers that opt into PREFETCH/RECOMMENDATION may defer.
 */
internal suspend fun platformHttpGetOutcome(
    url: String,
    referer: String,
    timeoutMs: Long = 8000L,
    includeWyAnonymousProtocolMarker: Boolean = false,
    followRedirects: Boolean = true,
    intent: PlatformRequestIntent = PlatformRequestIntent.ACTIVE_PLAYBACK
): PlatformTransportOutcome<String> {
    if (!isCleartextApiAllowed(url)) {
        return PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.SSRF))
    }
    val key = platformKey(url)
    if (!platformCooldown.admit(key, intent)) {
        return PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.COOLDOWN))
    }
    val outcome = platformHttpRequestOutcome {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PLATFORM_HTTP_UA)
                .header("Referer", referer)
                .header("Accept", "application/json,text/plain,*/*")
                .apply {
                    if (includeWyAnonymousProtocolMarker && url.startsWith("https://music.163.com/")) {
                        header("Cookie", WY_ANONYMOUS_PROTOCOL_MARKER)
                    }
                }
                .build()
            val client = sharedClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .apply {
                    if (!followRedirects) {
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                }
                .build()
            officialPlatformCall(client.newCall(request)) { response ->
                if (!followRedirects) {
                    platformRedirectLocation(response.code, response.header("Location")).orEmpty()
                } else if (response.isSuccessful) {
                    response.body?.string() ?: ""
                } else {
                    throw PlatformHttpStatusException(response.code, response.header("Retry-After"))
                }
            }
        }
    }
    if (outcome is PlatformTransportOutcome.Failure) {
        platformCooldown.record(key, outcome.error, intent)
    }
    return outcome
}

internal suspend fun platformHttpGet(
    url: String,
    referer: String,
    timeoutMs: Long = 8000L,
    includeWyAnonymousProtocolMarker: Boolean = false,
    followRedirects: Boolean = true
): String = platformHttpGetOutcome(
    url, referer, timeoutMs, includeWyAnonymousProtocolMarker, followRedirects
).legacyString()

/** 不跟随重定向时平台的原始应答事实：状态码 + Location 原文（未做有效性判定）。 */
internal data class PlatformRedirectReply(val status: Int, val location: String?)

/**
 * ACCEPT5（A）：只读首个重定向应答的结构化变体。与 [platformHttpGetOutcome] 共用
 * 同一传输面（策略闸门/冷却/共享 client/UA），但把「状态码 + Location」原样带回，
 * 让调用方能区分「跳转到 404（受限）/ 非重定向应答 / 跳转无 Location」——旧
 * String 形态把这些与传输失败（超时/DNS/策略拒绝）全压成空串，官方直连在真机
 * 失败时诊断里无法定性。传输层失败仍走 [PlatformTransportOutcome.Failure] 分类。
 */
internal suspend fun platformHttpRedirectOutcome(
    url: String,
    referer: String,
    timeoutMs: Long = 8000L
): PlatformTransportOutcome<PlatformRedirectReply> {
    if (!isCleartextApiAllowed(url)) {
        return PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.SSRF))
    }
    val key = platformKey(url)
    if (!platformCooldown.admit(key, PlatformRequestIntent.ACTIVE_PLAYBACK)) {
        return PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.COOLDOWN))
    }
    val outcome: PlatformTransportOutcome<PlatformRedirectReply> = try {
        val reply = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PLATFORM_HTTP_UA)
                .header("Referer", referer)
                .header("Accept", "application/json,text/plain,*/*")
                .build()
            val client = sharedClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            officialPlatformCall(client.newCall(request)) { response ->
                PlatformRedirectReply(response.code, response.header("Location"))
            }
        }
        PlatformTransportOutcome.Success(reply)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.TIMEOUT))
    } catch (error: IOException) {
        PlatformTransportOutcome.Failure(
            PlatformTransportError(PlatformTransportErrorKind.NETWORK, detail = error::class.java.simpleName)
        )
    } catch (error: Exception) {
        PlatformTransportOutcome.Failure(
            PlatformTransportError(PlatformTransportErrorKind.NETWORK, detail = error::class.java.simpleName)
        )
    }
    if (outcome is PlatformTransportOutcome.Failure) {
        platformCooldown.record(key, outcome.error, PlatformRequestIntent.ACTIVE_PLAYBACK)
    }
    return outcome
}

/** Anonymous platform JSON POST; QQ musicu.fcg search uses this form. */
internal suspend fun platformHttpPostJson(
    url: String,
    body: String,
    referer: String,
    timeoutMs: Long = 8000L
): String = platformHttpPostJsonOutcome(url, body, referer, timeoutMs).legacyString()

internal suspend fun platformHttpPostJsonOutcome(
    url: String,
    body: String,
    referer: String,
    timeoutMs: Long = 8000L,
    intent: PlatformRequestIntent = PlatformRequestIntent.ACTIVE_PLAYBACK
): PlatformTransportOutcome<String> {
    if (!isCleartextApiAllowed(url)) {
        return PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.SSRF))
    }
    val key = platformKey(url)
    if (!platformCooldown.admit(key, intent)) {
        return PlatformTransportOutcome.Failure(PlatformTransportError(PlatformTransportErrorKind.COOLDOWN))
    }
    val outcome = platformHttpRequestOutcome {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PLATFORM_HTTP_UA)
                .header("Referer", referer)
                .header("Origin", referer.trimEnd('/'))
                .header("Accept", "application/json,text/plain,*/*")
                .header("Cache-Control", "no-cache")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val client = sharedClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            officialPlatformCall(client.newCall(request)) { response ->
                if (response.isSuccessful) response.body?.string() ?: ""
                else throw PlatformHttpStatusException(response.code, response.header("Retry-After"))
            }
        }
    }
    if (outcome is PlatformTransportOutcome.Failure) platformCooldown.record(key, outcome.error, intent)
    return outcome
}

internal class PlatformHttpStatusException(val status: Int, val retryAfter: String?) : IOException("HTTP $status")

/**
 * ACCEPT5 (kw singer): GET with caller-supplied extra headers, for endpoints that
 * demand a seeded token pair (Cookie + csrf). Same transport policy surface as
 * [platformHttpGetOutcome] (cleartext gate, cooldown, shared client/UA); headers
 * are additive only and no existing call site uses this variant.
 */
internal suspend fun platformHttpGetWithHeaders(
    url: String,
    referer: String,
    extraHeaders: Map<String, String>,
    timeoutMs: Long = 8000L
): String {
    if (!isCleartextApiAllowed(url)) return ""
    val key = platformKey(url)
    if (!platformCooldown.admit(key, PlatformRequestIntent.ACTIVE_PLAYBACK)) return ""
    val outcome = platformHttpRequestOutcome {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PLATFORM_HTTP_UA)
                .header("Referer", referer)
                .header("Accept", "application/json,text/plain,*/*")
                .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
            val client = sharedClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            officialPlatformCall(client.newCall(request)) { response ->
                if (response.isSuccessful) response.body?.string() ?: ""
                else throw PlatformHttpStatusException(response.code, response.header("Retry-After"))
            }
        }
    }
    if (outcome is PlatformTransportOutcome.Failure) {
        platformCooldown.record(key, outcome.error, PlatformRequestIntent.ACTIVE_PLAYBACK)
    }
    return outcome.legacyString()
}

/**
 * ACCEPT5 (kw singer): one-shot GET that returns the final response's Set-Cookie
 * header values, for Meting-style token seeding (kuwo www issues `kw_token` to
 * browsers; the value is then replayed as Cookie + `csrf` header). Same policy
 * surface as the other GET variants; body is discarded.
 */
internal suspend fun platformHttpGetSetCookies(
    url: String,
    referer: String,
    timeoutMs: Long = 8000L
): List<String> {
    if (!isCleartextApiAllowed(url)) return emptyList()
    val key = platformKey(url)
    if (!platformCooldown.admit(key, PlatformRequestIntent.ACTIVE_PLAYBACK)) return emptyList()
    val outcome = platformHttpRequestOutcome {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PLATFORM_HTTP_UA)
                .header("Referer", referer)
                .header("Accept", "text/html,application/json,text/plain,*/*")
                .build()
            val client = sharedClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            officialPlatformCall(client.newCall(request)) { response ->
                if (response.isSuccessful) response.headers("Set-Cookie").joinToString("\n")
                else throw PlatformHttpStatusException(response.code, response.header("Retry-After"))
            }
        }
    }
    if (outcome is PlatformTransportOutcome.Failure) {
        platformCooldown.record(key, outcome.error, PlatformRequestIntent.ACTIVE_PLAYBACK)
    }
    return outcome.legacyString().split("\n").filter { it.isNotBlank() }
}
