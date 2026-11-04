package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import xyz.cncnlp.soundwave.PlayMode

class PlayerServiceEventBridgeTest {
    @Test
    fun registrationReplacementAndDisposalStayPairedAndIdempotent() {
        val first = callbacks()
        val second = callbacks()
        val operations = mutableListOf<String>()
        val bridge = PlayerServiceEventBridge(
            attach = { installed -> operations += "attach:${if (installed === first) "first" else "second"}" },
            detach = { removed -> operations += "detach:${if (removed === first) "first" else "second"}" }
        )

        bridge.register(first)
        bridge.register(first)
        bridge.register(second)
        bridge.dispose()
        bridge.dispose()

        assertEquals(listOf("attach:first", "detach:first", "attach:second", "detach:second"), operations)
    }

    @Test
    fun staleActivityDisposalCannotClearReplacementCallbacks() {
        val oldCallbacks = callbacks()
        val newCallbacks = callbacks()
        var installed: PlayerServiceEventCallbacks? = null
        fun bridge() = PlayerServiceEventBridge(
            attach = { installed = it },
            detach = { callbacks ->
                if (installed?.boundOnSongChanged === callbacks.boundOnSongChanged) installed = null
            }
        )
        val oldActivity = bridge()
        val newActivity = bridge()

        assertSame(oldCallbacks.onSongChanged, newCallbacks.onSongChanged)
        oldActivity.register(oldCallbacks)
        newActivity.register(newCallbacks)
        oldActivity.dispose()

        assertSame(newCallbacks, installed)
        newActivity.dispose()
        assertNull(installed)
    }

    private fun callbacks() = PlayerServiceEventCallbacks(
        onSongChanged = {},
        onError = {},
        onNeedNext = {},
        onNeedPrev = {},
        onPreparedNext = {},
        onNeedReplay = {},
        onLikeToggle = {},
        onLyricFloatToggle = {},
        onSleepTick = {},
        onModeChanged = { _: PlayMode -> }
    )
}
