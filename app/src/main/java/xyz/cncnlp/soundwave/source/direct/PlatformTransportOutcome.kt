package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** The transport fact is retained internally; legacy callers can still consume a String. */
internal sealed interface PlatformTransportOutcome<out T> {
    data class Success<T>(val value: T) : PlatformTransportOutcome<T>
    data class Failure(val error: PlatformTransportError) : PlatformTransportOutcome<Nothing>
}

internal enum class PlatformTransportErrorKind { HTTP_STATUS, TIMEOUT, NETWORK, CANCELLED, PARSE, SSRF, COOLDOWN, POLICY, REMOTE_FAILURE }

internal data class PlatformTransportError(
    val kind: PlatformTransportErrorKind,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    /** Safe diagnostic only; never include URL, response body, cookies, or credentials. */
    val detail: String? = null
)

internal fun <T> PlatformTransportOutcome<T>.legacyString(): String = when (this) {
    is PlatformTransportOutcome.Success -> value as? String ?: ""
    is PlatformTransportOutcome.Failure -> ""
}

/** Parse Retry-After delta-seconds or an RFC 7231 HTTP-date. Negative delays are zero. */
internal fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.let { seconds -> return seconds.coerceAtLeast(0L).coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000L }
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy"
    )
    for (pattern in formats) {
        try {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val position = ParsePosition(0)
            val date = parser.parse(raw, position) ?: continue
            if (position.index != raw.length || position.errorIndex >= 0) continue
            return (date.time - nowMs).coerceAtLeast(0L).coerceAtMost(MAX_RETRY_AFTER_SECONDS * 1000L)
        } catch (_: Exception) {
            // Try the next HTTP-date spelling.
        }
    }
    return null
}

private const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L

internal fun classifyPlatformHttpFailure(
    status: Int,
    retryAfterHeader: String? = null,
    nowMs: Long = System.currentTimeMillis()
): PlatformTransportOutcome<Nothing> = PlatformTransportOutcome.Failure(
    PlatformTransportError(
        kind = PlatformTransportErrorKind.HTTP_STATUS,
        httpStatus = status,
        retryAfterMs = parseRetryAfter(retryAfterHeader, nowMs)
    )
)

/** Parse errors stay distinct from transport/status failures and never become a fake empty success. */
internal inline fun <T> parsePlatformTransport(raw: String, parser: (String) -> T): PlatformTransportOutcome<T> = try {
    PlatformTransportOutcome.Success(parser(raw))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    PlatformTransportOutcome.Failure(
        PlatformTransportError(PlatformTransportErrorKind.PARSE, detail = error::class.java.simpleName)
    )
}

internal suspend fun platformHttpRequestOutcome(request: suspend () -> String): PlatformTransportOutcome<String> = try {
    PlatformTransportOutcome.Success(request())
} catch (cancelled: CancellationException) {
    // Cancellation is control flow. It must not enter String fallback or a server fallback.
    throw cancelled
} catch (status: PlatformHttpStatusException) {
    PlatformTransportOutcome.Failure(
        PlatformTransportError(
            kind = PlatformTransportErrorKind.HTTP_STATUS,
            httpStatus = status.status,
            retryAfterMs = parseRetryAfter(status.retryAfter)
        )
    )
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

internal enum class PlatformRequestIntent { ACTIVE_PLAYBACK, PREFETCH, RECOMMENDATION }

/** A Location header is actionable only on an actual redirect status, never on a 2xx/304 response. */
internal fun platformRedirectLocation(status: Int, location: String?): String? {
    if (status !in setOf(300, 301, 302, 303, 307, 308)) return null
    return location?.takeIf { it.isNotBlank() }
}

internal interface TransportClock { fun nowMs(): Long }

private object SystemTransportClock : TransportClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Platform cooldown is only an admission gate for speculative work; active taps always pass. */
internal class PlatformTransportCooldown(private val clock: TransportClock = SystemTransportClock) {
    private val untilByPlatform = ConcurrentHashMap<String, Long>()

    fun admit(platform: String, intent: PlatformRequestIntent): Boolean =
        intent == PlatformRequestIntent.ACTIVE_PLAYBACK ||
            clock.nowMs() >= (untilByPlatform[platform] ?: 0L)

    fun record(platform: String, error: PlatformTransportError, intent: PlatformRequestIntent) {
        if (intent == PlatformRequestIntent.ACTIVE_PLAYBACK) return
        val delay = error.retryAfterMs ?: when (error.httpStatus) {
            429, 502, 503, 504 -> 1_000L
            else -> null
        } ?: return
        val until = clock.nowMs() + delay
        untilByPlatform.compute(platform) { _, old -> maxOf(old ?: 0L, until) }
    }

    fun clear(platform: String) { untilByPlatform.remove(platform) }
}
