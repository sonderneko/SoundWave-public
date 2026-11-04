package xyz.cncnlp.soundwave.script

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEvaluationLanesTest {
    @Test
    fun differentSlotsProceedWhileSameSlotRemainsSingleFlight() = runBlocking {
        val lanes = ScriptEvaluationLanes()
        val firstSubmitted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            lanes.withSlot("script-a") {
                lanes.submit { firstSubmitted.complete(Unit) }
                releaseFirst.await()
            }
        }

        try {
            withTimeout(500L) { firstSubmitted.await() }
            val second = withTimeout(500L) {
                lanes.withSlot("script-b") { lanes.submit { "submitted" } }
            }
            assertEquals("submitted", second)

            var sameSlotEntered = false
            val sameSlot = launch {
                lanes.withSlot("script-a") { sameSlotEntered = true }
            }
            yield()
            assertFalse(sameSlotEntered)

            releaseFirst.complete(Unit)
            first.join()
            sameSlot.join()
            assertTrue(sameSlotEntered)
        } finally {
            releaseFirst.complete(Unit)
            first.join()
        }
    }
}
