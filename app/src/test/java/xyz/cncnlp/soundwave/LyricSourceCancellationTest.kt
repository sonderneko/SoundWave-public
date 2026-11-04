package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LyricSourceCancellationTest {
    @Test
    fun `source cancellation is rethrown instead of converted to a fallback`() = runBlocking {
        val expected = CancellationException("fake lyric source cancelled")
        val actual = try {
            lyricSourceAttemptOrNull<String> { throw expected }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertSame(expected, actual)
    }

    @Test
    fun `ordinary source failure falls back but fatal errors propagate`() = runBlocking {
        assertNull(lyricSourceAttemptOrNull<String> { throw IllegalStateException("fake source failure") })
        val expected = AssertionError("fake fatal source error")
        val actual = try {
            lyricSourceAttemptOrNull<String> { throw expected }
            null
        } catch (error: AssertionError) {
            error
        }
        assertSame(expected, actual)
    }
}
