package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchControllerCancellationTest {
    @Test
    fun cancellationIsRethrownInsteadOfBecomingAnEmptySearchResult() = runBlocking {
        val cancellation = CancellationException("search superseded")
        val thrown = try {
            preserveCancellationOrDefault(emptyList<String>()) { throw cancellation }
            null
        } catch (caught: CancellationException) {
            caught
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun ordinaryFailureStillUsesTheExistingFallback() = runBlocking {
        val fallback = listOf("cached result")
        val result = preserveCancellationOrDefault(fallback) { throw IllegalStateException("offline") }

        assertEquals(fallback, result)
    }
}
