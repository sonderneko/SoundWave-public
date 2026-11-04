package xyz.cncnlp.soundwave.script

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightNegativeCacheTest {
    @Test fun identicalConcurrentCallsShareOneProducerAndNegativeResultsExpire() = runTest {
        var now = 10L
        val cache = SingleFlightNegativeCache<String, String>(60_000L, 2, { it.isEmpty() }, { now })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        var producers = 0
        val first = async {
            cache.run("same-request") { producers++; started.complete(Unit); release.await() }
        }
        started.await()
        val duplicate = async { cache.run("same-request") { producers++; "duplicate" } }
        yield()
        assertEquals(1, producers)
        release.complete("")
        assertEquals("", first.await())
        assertEquals("", duplicate.await())
        assertEquals(1, cache.failureCount)
        assertEquals("", cache.run("same-request") { producers++; "unexpected" })
        assertEquals(1, producers)
        now += 60_000L
        assertEquals("recovered", cache.run("same-request") { producers++; "recovered" })
        assertEquals(2, producers)
        assertEquals(0, cache.failureCount)
    }

    @Test fun cancelledProducerWakesWaiterToRetryWithoutCancellingIt() = runTest {
        val cache = SingleFlightNegativeCache<String, String>(60_000L, 2, { it.isEmpty() })
        val started = CompletableDeferred<Unit>()
        var producers = 0
        val owner = async {
            cache.run("same-request") {
                producers++
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val waiter = async { cache.run("same-request") { producers++; "retry-result" } }
        yield()
        owner.cancelAndJoin()
        assertEquals("retry-result", waiter.await())
        assertEquals(2, producers)
        assertEquals(0, cache.inFlightCount)
        assertTrue(cache.failureCount == 0)
    }

    @Test fun failureCacheIsBoundedAndSuccessesAreNotMemoized() = runTest {
        val cache = SingleFlightNegativeCache<Int, String>(1_000L, 2, { it.isEmpty() }, { 0L })
        cache.run(1) { "" }
        cache.run(2) { "" }
        cache.run(3) { "" }
        assertEquals(2, cache.failureCount)
        var successCalls = 0
        repeat(2) { cache.run(99) { successCalls++; "url" } }
        assertEquals(2, successCalls)
    }

    @Test fun classifiedTransientFailureCanRetryImmediatelyButSemanticEmptyIsMemoized() = runTest {
        data class UrlResult(val url: String, val failureCategory: String)
        val cache = SingleFlightNegativeCache<String, UrlResult>(
            60_000L,
            8,
            isFailure = { it.url.isBlank() && it.failureCategory.isBlank() },
        )
        var producers = 0

        assertEquals(UrlResult("", "network"), cache.run("transient") { producers++; UrlResult("", "network") })
        assertEquals(UrlResult("https://fixture.invalid/retry", ""), cache.run("transient") {
            producers++
            UrlResult("https://fixture.invalid/retry", "")
        })
        assertEquals(UrlResult("", ""), cache.run("semantic-empty") { producers++; UrlResult("", "") })
        assertEquals(UrlResult("", ""), cache.run("semantic-empty") { producers++; UrlResult("unexpected", "") })

        assertEquals("transient errors must not be cached", 3, producers)
        assertEquals(1, cache.failureCount)
    }
}
