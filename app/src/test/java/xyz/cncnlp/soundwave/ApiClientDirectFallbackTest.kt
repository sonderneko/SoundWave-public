package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ApiClientDirectFallbackTest {
    @Test
    fun cancellationIsRethrownInsteadOfStartingTheServerFallback() = runBlocking {
        val expected = CancellationException("fake direct request cancelled")
        val actual = try {
            runDirectFallback<String> { throw expected }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertSame(expected, actual)
    }

    @Test
    fun ordinaryDirectFailureStillAllowsTheServerFallback() = runBlocking {
        assertNull(runDirectFallback<String> { throw IllegalStateException("fake request failure") })
    }

    @Test
    fun directSuccessIsReturnedWithoutChangingTheResult() = runBlocking {
        assertEquals("local-fake-result", runDirectFallback { "local-fake-result" })
    }
}
