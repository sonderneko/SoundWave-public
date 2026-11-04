package xyz.cncnlp.soundwave.resolve

import java.net.URI
import java.net.URLDecoder

/** Uses explicit signed-link expiry hints when present; otherwise retains the 30-minute ceiling. */
internal object UrlCacheExpiryPolicy {
    const val MAX_SUCCESS_TTL_MS = 30 * 60 * 1000L
    private val expiryNames = setOf("expires", "expire", "expires_at", "expiry", "deadline", "valid_until", "endtime")
    private val authKeyExpiry = Regex("^(\\d{10,13})-[a-fA-F0-9]{16,}$")

    fun ttlMillis(url: String, nowEpochMillis: Long): Long {
        val hintedExpiry = explicitExpiryEpochMillis(url) ?: return MAX_SUCCESS_TTL_MS
        return (minOf(hintedExpiry, nowEpochMillis + MAX_SUCCESS_TTL_MS) - nowEpochMillis).coerceAtLeast(0L)
    }

    fun expiresAtEpochMillis(url: String, nowEpochMillis: Long): Long =
        nowEpochMillis + ttlMillis(url, nowEpochMillis)

    private fun explicitExpiryEpochMillis(url: String): Long? {
        val query = try { URI(url).rawQuery } catch (_: Exception) { null } ?: return null
        val values = query.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) return@mapNotNull null
            val name = decode(part.substring(0, separator)).lowercase()
            val value = decode(part.substring(separator + 1))
            val raw = when {
                name in expiryNames -> value.toLongOrNull()
                name == "auth_key" -> authKeyExpiry.matchEntire(value)?.groupValues?.getOrNull(1)?.toLongOrNull()
                else -> null
            } ?: return@mapNotNull null
            when {
                raw >= 100_000_000_000L -> raw
                raw >= 1_000_000_000L -> raw * 1000L
                else -> null
            }
        }
        return values.minOrNull()
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}
