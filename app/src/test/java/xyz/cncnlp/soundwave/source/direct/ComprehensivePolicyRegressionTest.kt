package xyz.cncnlp.soundwave.source.direct

import java.util.concurrent.CancellationException
import org.junit.Test
import org.junit.Assert.*
import xyz.cncnlp.soundwave.source.captureListRequestOutcome

class ComprehensivePolicyRegressionTest {
    @Test fun allApiPathsRejectMalformedCredentialAndNonHttpTargets() {
        listOf("HTTP://evil.example/a", "ftp://example.com/a", "file:///tmp/x", "relative/path", "https://user@example.com/a", "https://@example.com/a", "https://example.com:0/a", "https://example.com:65536/a", "http://mobilecdn.kugou.com:88/a").forEach {
            assertFalse("must reject $it", isCleartextApiAllowed(it))
        }
    }
    @Test fun validHttpsAndOnlyExactDefaultPortHttpExceptionsSurvive() {
        listOf("HTTPS://example.com/a", "http://mobilecdn.kugou.com/a", "http://mobilecdnbj.kugou.com:80/a").forEach {assertTrue(it,isCleartextApiAllowed(it))}
        listOf("http://mobilecdn.kugou.com.evil.example/a", "http://mobilecdn.kugou.com@evil.example/a").forEach {assertFalse(it,isCleartextApiAllowed(it))}
    }
    @Test fun cancelledFeedDoesNotBecomeAnEmptyFailure() {
        val signal=CancellationException("synthetic cancellation")
        try {captureListRequestOutcome<String> {throw signal}; fail("cancellation swallowed")}
        catch(actual: CancellationException) {assertSame(signal,actual)}
    }
    @Test fun retryAfterRequiresCompleteDateConsumption() {
        assertNull(parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT appended",0L))
        assertNotNull(parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT",0L))
    }
    @Test fun retryAfterStillClampsAndRejectsInvalidDates() {
        assertEquals(86400000L,parseRetryAfter("999999999",0L))
        assertNull(parseRetryAfter("Wed, 40 Oct 2015 07:28:00 GMT",0L))
    }
}
