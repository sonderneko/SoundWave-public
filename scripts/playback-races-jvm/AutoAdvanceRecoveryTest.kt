package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class AutoAdvanceRecoveryTest {
    private fun queuedAdvance(callback: Int, replace: Boolean) = runBlocking {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val c = RaceController(owner)
        c.currentSong = Song(id="same", name="same", singer="artist", source="wy")
        PlayerService.beginPlaybackIntent(); PlayerService.onPlaybackInterrupted = null
        PlayerService.media.clear(); LocalFirst.action = { "/unexpected-advance" }
        var advances = 0
        c.playDeltaRef[0] = { advances++; c.resolveAndPlay(c.currentSong!!) }
        when(callback) { 0 -> c.onNeedNextCallback(); 1 -> c.onPreparedNextCallback(); else -> c.onNeedPrevCallback() }
        if (replace) PlayerService.beginPlaybackIntent() else PlayerService.pause()
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        owner.cancel(); PlayerService.mainHandler.pending.clear()
        assertEquals("queued callback cannot establish new playback after intent changes", 0, advances)
        assertTrue(PlayerService.media.isEmpty())
    }
    @Test fun currentCallbackStillResolvesAndPlays() = runBlocking {
        val c = RaceController(this)
        c.currentSong = Song(id="same",name="same",singer="artist",source="wy")
        PlayerService.beginPlaybackIntent(); PlayerService.media.clear()
        LocalFirst.action = { "/current-advance" }
        c.playDeltaRef[0] = { c.resolveAndPlay(c.currentSong!!) }
        c.onNeedNextCallback()
        withTimeout(1000) { while(PlayerService.media.isEmpty()) yield() }
        assertEquals(listOf("/current-advance"),PlayerService.media)
    }
    @Test fun nextAfterPause() = queuedAdvance(0, false)
    @Test fun preparedAfterPause() = queuedAdvance(1, false)
    @Test fun previousAfterPause() = queuedAdvance(2, false)
    @Test fun nextAfterSameKeyNewSelection() = queuedAdvance(0, true)
    @Test fun preparedAfterSameKeyNewSelection() = queuedAdvance(1, true)
    @Test fun previousAfterSameKeyNewSelection() = queuedAdvance(2, true)
}
