package xyz.cncnlp.soundwave

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchemeActionHolderTest {
    @Test
    fun `action submitted before collection is delivered once without replay`() = runTest {
        val dispatcher = SchemeActionDispatcher()
        val action = SchemeActionHolder.Action.Search("cold-start")
        dispatcher.submit(action)

        val delivered = async { dispatcher.actions.first() }
        runCurrent()
        assertEquals(action, delivered.await())

        val replay = async { withTimeoutOrNull(1) { dispatcher.actions.first() } }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertNull(replay.await())
    }

    @Test
    fun `action submitted after collection starts is delivered immediately`() = runTest {
        val dispatcher = SchemeActionDispatcher()
        val action = SchemeActionHolder.Action.Play("song-1", "wy", "name", "singer")
        val delivered = async { dispatcher.actions.first() }
        runCurrent()

        dispatcher.submit(action)
        assertEquals(action, delivered.await())
    }

    @Test
    fun `unconsumed actions use last submitted wins semantics`() = runTest {
        val dispatcher = SchemeActionDispatcher()
        dispatcher.submit(SchemeActionHolder.Action.Search("superseded"))
        val latest = SchemeActionHolder.Action.Search("latest")
        dispatcher.submit(latest)

        assertEquals(latest, dispatcher.actions.first())
        val replay = async { withTimeoutOrNull(1) { dispatcher.actions.first() } }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertNull(replay.await())
    }
}
