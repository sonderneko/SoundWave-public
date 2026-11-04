package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

class PlatformTransportOutcomeTest {
    @Test
    fun retryAfterParsesDeltaSecondsAndHttpDate() {
        assertEquals(45_000L, parseRetryAfter("45", nowMs = 1_000_000L))
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("GMT")
        val date = format.format(Date(1_000_000L + 90_000L))
        assertEquals(90_000L, parseRetryAfter(date, nowMs = 1_000_000L))
        assertEquals(null, parseRetryAfter("garbage", nowMs = 1_000_000L))
    }

    @Test
    fun httpStatusOutcomeRetainsStatusAndRetryAfterWithoutBody() {
        val outcome = classifyPlatformHttpFailure(429, retryAfterHeader = "12", nowMs = 0L)
        assertTrue(outcome is PlatformTransportOutcome.Failure)
        val failure = (outcome as PlatformTransportOutcome.Failure).error
        assertEquals(429, failure.httpStatus)
        assertEquals(12_000L, failure.retryAfterMs)
        assertEquals(PlatformTransportErrorKind.HTTP_STATUS, failure.kind)
    }

    @Test
    fun httpRequestBoundaryRetainsRetryAfterFromResponseException() = runBlocking {
        val outcome = platformHttpRequestOutcome {
            throw PlatformHttpStatusException(503, "7")
        }
        val failure = (outcome as PlatformTransportOutcome.Failure).error
        assertEquals(503, failure.httpStatus)
        assertEquals(7_000L, failure.retryAfterMs)
    }

    @Test
    fun locationIsReturnedOnlyForRedirectStatuses() {
        assertEquals("https://music.example/next", platformRedirectLocation(302, "https://music.example/next"))
        assertEquals("/selected", platformRedirectLocation(300, "/selected"))
        assertEquals(null, platformRedirectLocation(200, "/not-a-redirect"))
        assertEquals(null, platformRedirectLocation(304, "/not-a-redirect"))
        assertEquals(null, platformRedirectLocation(302, null))
    }

    @Test
    fun parseFailureIsStructuredAndLegacyValueStaysEmpty() {
        val outcome = parsePlatformTransport("not-json") { throw IllegalArgumentException("bad json") }
        assertTrue(outcome is PlatformTransportOutcome.Failure)
        assertEquals(PlatformTransportErrorKind.PARSE, (outcome as PlatformTransportOutcome.Failure).error.kind)
        assertEquals("", outcome.legacyString())
    }

    @Test
    fun cancellationIsNeverConvertedToFallbackOutcome() = runBlocking {
        val expected = CancellationException("cancelled")
        try {
            platformHttpRequestOutcome { throw expected }
            throw AssertionError("cancellation must escape")
        } catch (actual: CancellationException) {
            assertEquals(expected, actual)
        }
    }

    @Test
    fun cooldownDefersOnlySpeculativeRequests() {
        val clock = FakeTransportClock(1_000L)
        val cooldown = PlatformTransportCooldown(clock)
        val failure = PlatformTransportError(
            kind = PlatformTransportErrorKind.HTTP_STATUS,
            httpStatus = 429,
            retryAfterMs = 10_000L
        )
        cooldown.record("wy", failure, PlatformRequestIntent.PREFETCH)
        assertFalse(cooldown.admit("wy", PlatformRequestIntent.PREFETCH))
        assertFalse(cooldown.admit("wy", PlatformRequestIntent.RECOMMENDATION))
        assertTrue(cooldown.admit("wy", PlatformRequestIntent.ACTIVE_PLAYBACK))
        clock.now += 10_000L
        assertTrue(cooldown.admit("wy", PlatformRequestIntent.PREFETCH))
    }

    private class FakeTransportClock(var now: Long) : TransportClock {
        override fun nowMs(): Long = now
    }
}
