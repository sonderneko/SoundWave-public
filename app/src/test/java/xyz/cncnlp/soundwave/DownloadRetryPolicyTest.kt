package xyz.cncnlp.soundwave

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {
    @Test fun transportRetriesAreBoundedAndCancellationAlwaysStops() {
        val offline = DownloadTransportException(IOException("offline"))
        assertTrue(DownloadRetryPolicy.shouldRetry(offline, runAttemptCount = 0, cancelled = false))
        assertTrue(DownloadRetryPolicy.shouldRetry(offline, runAttemptCount = 1, cancelled = false))
        assertTrue(DownloadRetryPolicy.shouldRetry(offline, runAttemptCount = 2, cancelled = false))
        assertFalse(DownloadRetryPolicy.shouldRetry(offline, runAttemptCount = 3, cancelled = false))
        assertFalse(DownloadRetryPolicy.shouldRetry(offline, runAttemptCount = 0, cancelled = true))
        assertFalse(DownloadRetryPolicy.shouldRetry(java.util.concurrent.CancellationException(), 0, false))
    }

    @Test fun retryableHttpStatusesAreNarrowAndExpiredLinksAreIdentified() {
        listOf(403, 404, 410).forEach { code ->
            assertTrue("expired link $code can be re-resolved", DownloadRetryPolicy.shouldRetry(DownloadHttpStatusException(code), 0, false))
            assertTrue(DownloadRetryPolicy.isExpiredLinkStatus(code))
        }
        listOf(408, 429, 500, 503, 599).forEach { code ->
            assertTrue("temporary HTTP status $code may retry", DownloadRetryPolicy.shouldRetry(DownloadHttpStatusException(code), 0, false))
            assertFalse(DownloadRetryPolicy.isExpiredLinkStatus(code))
        }
        listOf(400, 401, 407, 422, 499, 600).forEach { code ->
            assertFalse("permanent HTTP status $code must not retry", DownloadRetryPolicy.shouldRetry(DownloadHttpStatusException(code), 0, false))
        }
    }

    @Test fun localValidationAndStorageFailuresAreNotMisclassifiedAsNetwork() {
        assertFalse(DownloadRetryPolicy.shouldRetry(IOException("disk full"), 0, false))
        assertFalse(DownloadRetryPolicy.shouldRetry(IllegalStateException("invalid media container"), 0, false))
    }
}
