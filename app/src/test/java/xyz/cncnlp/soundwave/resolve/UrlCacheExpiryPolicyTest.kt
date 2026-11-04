package xyz.cncnlp.soundwave.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlCacheExpiryPolicyTest {
    @Test fun noExplicitExpiryRetainsThirtyMinuteMaximum() {
        assertEquals(UrlCacheExpiryPolicy.MAX_SUCCESS_TTL_MS, UrlCacheExpiryPolicy.ttlMillis("https://cdn.example/audio", 1_800_000_000_000L))
    }

    @Test fun explicitEpochExpiryShortensCacheInSecondsOrMilliseconds() {
        val now = 1_800_000_000_000L
        val expiresSeconds = now / 1000L + 45L
        assertEquals(45_000L, UrlCacheExpiryPolicy.ttlMillis("https://cdn.example/a?expires=$expiresSeconds", now))
        assertEquals(30_000L, UrlCacheExpiryPolicy.ttlMillis("https://cdn.example/a?valid_until=${now + 30_000L}", now))
    }

    @Test fun expirationBeyondMaximumIsCappedAndExpiredLinksAreNotCached() {
        val now = 1_800_000_000_000L
        assertEquals(UrlCacheExpiryPolicy.MAX_SUCCESS_TTL_MS, UrlCacheExpiryPolicy.ttlMillis("https://cdn.example/a?deadline=${now + 90_000_000L}", now))
        assertEquals(0L, UrlCacheExpiryPolicy.ttlMillis("https://cdn.example/a?expire=${now / 1000L - 1L}", now))
    }

    @Test fun authKeyEpochHintIsRecognizedWithoutLoggingOrPersistingTheSignature() {
        val now = 1_800_000_000_000L
        val value = "${now / 1000L + 15L}-0123456789abcdef0123456789abcdef"
        assertEquals(15_000L, UrlCacheExpiryPolicy.ttlMillis("https://cdn.example/a?auth_key=$value", now))
    }
}
