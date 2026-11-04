package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class PauseReplayIntegrationTest {
    @org.junit.Before fun resetTransport() {
        PlayerService.mainHandler.pending.clear()
        PlayerService.onPlaybackInterrupted = null; PlayerService.onNeedReplay = null
        PlayerService.player.playing = true; PlayerService.player.playable = true
        PlayerService.media.clear(); PlayerService.state = PlayerService.Companion.Snapshot()
    }
    @Test fun realBridgeDeliversPauseAndPreservesReplacementOwner() = runBlocking {
        val c = RaceController(this)
        var first = 0; var second = 0
        fun callbacks(cancel: () -> Unit) = xyz.cncnlp.soundwave.ui.player.PlayerServiceEventCallbacks(
            onSongChanged = {}, onError = {}, onNeedNext = {}, onNeedPrev = {}, onPreparedNext = {},
            onNeedReplay = { c.replay(it) }, onPlaybackInterrupted = cancel,
            onLikeToggle = {}, onLyricFloatToggle = {}, onSleepTick = {}, onModeChanged = {})
        val old = xyz.cncnlp.soundwave.ui.player.PlayerServiceEventBridge()
        val replacement = xyz.cncnlp.soundwave.ui.player.PlayerServiceEventBridge()
        old.register(callbacks { first++ })
        replacement.register(callbacks { second++; c.cancelPendingPlayback() })
        old.dispose()
        val entered = CompletableDeferred<Unit>()
        LocalFirst.action = { entered.complete(Unit); awaitCancellation() }
        val job = launch { c.resolveAndPlay(Song(id="same", name="same", singer="artist", source="wy")) }
        entered.await(); PlayerService.stopPlayback()
        val resolving = c.resolving
        job.cancelAndJoin()
        assertFalse(resolving); assertEquals(0, first); assertEquals(1, second)
        replacement.dispose()
        assertNull(PlayerService.onPlaybackInterrupted)
    }
    @Test fun delayedFailureAfterExplicitPauseCannotStartFreshRecovery() = runBlocking {
        val c = RaceController(this)
        val s = Song(id="same", name="same", singer="artist", source="wy")
        c.currentSong = s; PlayerService.state = PlayerService.Companion.Snapshot(song=s)
        LocalFirst.action = { "/should-not-recover" }
        PlayerService.onPlaybackInterrupted = { c.cancelPendingPlayback() }
        PlayerService.pause()
        c.handlePlaybackFailure(PlayerService.Companion.PlaybackFailure(s.key(), PlayerService.currentGeneration()))
        delay(50)
        assertTrue("late media errors cannot create a fresh intent after explicit pause", PlayerService.media.isEmpty())
    }
    @Test fun postedResumeFromOldIntentCannotPlayAfterPause() {
        PlayerService.onPlaybackInterrupted = null
        PlayerService.player.playing = false
        PlayerService.resumeInternal(PlayerService.beginPlaybackIntent())
        PlayerService.pause()
        // Simulate a delayed resume runnable delivered after pause's actual player command.
        val resume = PlayerService.mainHandler.pending.removeFirst()
        PlayerService.mainHandler.drain()
        resume()
        assertFalse(PlayerService.player.playing)
    }
    @Test fun queuedPauseCannotSilenceNewerSameSongIntent() {
        PlayerService.onPlaybackInterrupted = null
        PlayerService.player.playing = true
        PlayerService.pause()
        PlayerService.beginPlaybackIntent()
        PlayerService.mainHandler.drain()
        assertTrue("old posted pause may not mutate newer selection", PlayerService.player.playing)
    }
    @Test fun queuedServiceReplayIsDroppedBeforeCallbackAfterStop() {
        var replays = 0
        PlayerService.onNeedReplay = { replays++ }
        PlayerService.onPlaybackInterrupted = null
        PlayerService.resume(); PlayerService.stopPlayback(); PlayerService.mainHandler.drain()
        assertEquals(0, replays)
        PlayerService.onNeedReplay = null
    }
    @Test fun explicitResumeAfterPauseStillPlays() {
        PlayerService.onPlaybackInterrupted = null
        PlayerService.onNeedReplay = null
        PlayerService.player.playable = true
        PlayerService.pause(); PlayerService.mainHandler.drain()
        PlayerService.resume(); PlayerService.mainHandler.drain()
        assertTrue(PlayerService.player.playing)
    }
    @Test fun servicePauseWithoutUiCallbackInvalidatesInFlightResolve() = runBlocking {
        PlayerService.onPlaybackInterrupted = null
        val c = RaceController(this)
        PlayerService.media.clear()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        LocalFirst.action = { entered.complete(Unit); release.await(); "/late" }
        val job = launch { c.resolveAndPlay(Song(id="same", name="same", singer="artist", source="wy")) }
        entered.await(); PlayerService.pause(); release.complete(Unit); job.join()
        assertTrue("Service intent must invalidate result even without an Activity callback", PlayerService.media.isEmpty())
    }
    @Test fun queuedReplayCannotReplaceNewSameSongSelection() = runBlocking {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val owner = CoroutineScope(SupervisorJob() + dispatcher); val c = RaceController(owner)
        val s = Song(id="same", name="same", singer="artist", source="wy")
        c.currentSong = s; LocalFirst.action = { "/new" }
        c.replay(PlayerService.beginPlaybackIntent())
        c.resolveAndPlay(s)
        while (tasks.isNotEmpty()) { tasks.removeFirst().run(); delay(10) }
        owner.cancel()
        assertEquals(listOf("/new"), PlayerService.media)
    }
    @Test fun replayWithoutPlayableMediaStillResolves() = runBlocking {
        val c = RaceController(this)
        c.currentSong = Song(id="same", name="same", singer="artist", source="wy")
        LocalFirst.action = { "/resume" }
        c.replay(PlayerService.beginPlaybackIntent())
        while (PlayerService.media.isEmpty()) yield()
        assertEquals(listOf("/resume"), PlayerService.media)
    }
    @Test fun queuedReplayCannotReviveAfterPause() = runBlocking {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val c = RaceController(owner)
        val s = Song(id="same", name="same", singer="artist", source="wy")
        c.currentSong = s
        PlayerService.state = PlayerService.Companion.Snapshot()
        PlayerService.media.clear()
        PlayerService.onPlaybackInterrupted = { c.cancelPendingPlayback() }
        LocalFirst.action = { "/local" }
        c.replay(PlayerService.beginPlaybackIntent())
        PlayerService.pause()
        while (tasks.isNotEmpty()) { tasks.removeFirst().run(); delay(10) }
        owner.cancel()
        assertTrue("replay queued before pause must not acquire a fresh play transaction", PlayerService.media.isEmpty())
    }
    @Test fun actualServicePauseCancelsForegroundResolveSynchronously() = runBlocking {
        val c = RaceController(this)
        PlayerService.onPlaybackInterrupted = { c.cancelPendingPlayback() }
        val entered = CompletableDeferred<Unit>()
        LocalFirst.action = { entered.complete(Unit); awaitCancellation() }
        val job = launch { c.resolveAndPlay(Song(id="same", name="same", singer="artist", source="wy")) }
        entered.await()
        PlayerService.pause()
        val stillResolving = c.resolving; val cancelled = c.activeResolveJob == null
        job.cancelAndJoin()
        assertFalse("explicit pause must synchronously cancel controller, not infer snapshot false", stillResolving)
        assertTrue(cancelled)
        PlayerService.mainHandler.drain()
        assertFalse(PlayerService.player.playing)
    }
}
