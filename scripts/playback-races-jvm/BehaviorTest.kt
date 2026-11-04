package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackRaceBehaviorTest {
    private fun song(id: String) = Song(id = id, name = id, singer = "artist", source = "wy")
    @Before fun reset() {
        PlayerService.state = PlayerService.Companion.Snapshot()
        PlayerService.media.clear(); PlayerService.holds.clear(); PlayerService.transferred = null
        PlayerService.currentSong = null; PlayerService.activePlaybackToken = "play-token"
        PlayerService.naturalEndHoldToken = ""; PlayerService.endedHandledToken = ""
        PlayerService.playGen.set(0); PlayerService.playMode = PlayMode.SEQUENCE
        PlayerService.sleepStopAfterCurrent = false; PlayerService.queueIndex = 0
        PlayerService.onNeedNext = null; PlayerService.onPreparedNext = null; PlayerService.onError = null
        PlayerService.onPlayState = null; PlayerService.onSleepTick = null; PlayerService.instance = null
        PlayerService.player = null; PlayerService.preparedNext = false; PlayerService.nextDispatches = 0
        PlayerService.fallbackDispatches = 0
        PlayerService.recoveryPolicy = xyz.cncnlp.soundwave.playback.PlaybackRecoveryPolicy()
        UrlResolver.action = { UrlResolver.Result() }; UrlResolver.clearAction = {}
        LocalFirst.action = { null }; MediaDecryptCache.action = { MediaCacheLease() }
        Toast.messages.clear()
    }
    @Test fun oldRecoveryCannotReplaceNewSelectionOfSameSong() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = RaceController(owner); val s = song("same"); c.currentSong = s
        val clearEntered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var clears = 0
        UrlResolver.clearAction = { if (++clears == 1) { clearEntered.complete(Unit); release.await() } }
        c.handlePlaybackFailure(PlayerService.Companion.PlaybackFailure(s.key(), 0))
        clearEntered.await()
        val resolveEntered = CompletableDeferred<Unit>(); val finishResolve = CompletableDeferred<Unit>()
        UrlResolver.action = { resolveEntered.complete(Unit); finishResolve.await(); UrlResolver.Result(url = "new-selection") }
        val newer = launch { c.resolveAndPlay(song("same")) }; resolveEntered.await()
        release.complete(Unit); yield(); delay(30)
        finishResolve.complete(Unit); newer.join(); delay(30)
        assertEquals("old recovery must not cancel/replace new same-song request", listOf("new-selection"), PlayerService.media)
        assertEquals("old recovery must not allocate a third transaction", 1L, c.queueRevision)
        owner.cancel()
    }
    @Test fun stopDuringLocalLookupSuppressesObsoleteUnavailableFailure() = runBlocking {
        val c = RaceController(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        LocalFirst.action = { entered.complete(Unit); release.await(); null }
        val job = launch { c.resolveAndPlay(song("local").copy(source = "local")) }
        entered.await(); PlayerService.stopPlayback(); release.complete(Unit); job.join()
        assertTrue(Toast.messages.isEmpty()); assertFalse(c.resolving)
    }
    @Test fun obsoleteLookupExceptionDoesNotEraseNewRequestsStatus() = runBlocking {
        val c = RaceController(this)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        LocalFirst.action = { if (it.id == "old") withContext(NonCancellable) {
            entered.complete(Unit); release.await(); error("late lookup failure")
        } else null }
        UrlResolver.action = { awaitCancellation() }
        val old = launch { c.resolveAndPlay(song("old")) }; entered.await()
        val newer = launch { c.resolveAndPlay(song("new")) }
        while (c.status != "智能取链…") yield()
        release.complete(Unit); old.join()
        assertEquals("智能取链…", c.status); assertTrue(c.resolving)
        assertTrue(Toast.messages.isEmpty()); newer.cancelAndJoin()
    }
    @Test fun completedAttemptRejectsLateProgressWithoutNewSelection() = runBlocking {
        val c = RaceController(this)
        UrlResolver.action = { UrlResolver.Result(url = "", failure = ResolveFailure.FAILED) }
        c.resolveAndPlay(song("failed"))
        UrlResolver.progress!!.invoke("late progress")
        assertEquals("", c.status); assertFalse(c.resolving)
    }
    @Test fun lateProgressCannotOverwriteNewSelectionStatus() = runBlocking {
        val c = RaceController(this); val entered = CompletableDeferred<Unit>()
        UrlResolver.action = { entered.complete(Unit); awaitCancellation() }
        val old = launch { c.resolveAndPlay(song("old")) }; entered.await()
        val callback = UrlResolver.progress!!
        old.cancelAndJoin()
        LocalFirst.action = { "/new" }; c.resolveAndPlay(song("new"))
        callback("late old progress")
        assertEquals("", c.status)
    }
    @Test fun pauseInvalidatesResolveFailureAutoSkipQueuedOnScope() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val c = RaceController(owner)
        UrlResolver.action = { UrlResolver.Result(url = "", failure = ResolveFailure.FAILED) }
        c.resolveAndPlay(song("failed"), listOf(song("failed"), song("next")), autoSkipOnFailure = true)
        c.cancelPendingPlayback()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(0, c.skips); owner.cancel()
    }
    @Test fun pauseInvalidatesAlreadyQueuedFailureSkip() = runBlocking {
        val c = RaceController(this); val s = song("tail"); c.currentSong = s
        c.handlePlaybackFailure(PlayerService.Companion.PlaybackFailure(s.key(), 0, positionMs = 199000))
        c.cancelPendingPlayback(); yield()
        assertEquals("queued failure skip must retain intent identity", 0, c.skips)
    }
    @Test fun explicitResumeCreatesFreshSameSongIntentAfterCancel() = runBlocking {
        val c = RaceController(this); val s = song("same")
        val entered = CompletableDeferred<Unit>()
        UrlResolver.action = { entered.complete(Unit); awaitCancellation() }
        val old = launch { c.resolveAndPlay(s) }; entered.await()
        c.cancelPendingPlayback(); old.join()
        UrlResolver.action = { UrlResolver.Result(url = "resumed") }
        c.resolveAndPlay(s)
        assertEquals(listOf("resumed"), PlayerService.media); assertFalse(c.resolving)
        assertEquals(3L, c.queueRevision)
    }
    @Test fun replacementKeepsCallingCollectorAlive() = runBlocking {
        val c = RaceController(this)
        val entered = CompletableDeferred<Unit>(); var survived = false
        UrlResolver.action = { if (it.id == "old") { entered.complete(Unit); awaitCancellation() } else UrlResolver.Result() }
        val collector = launch { try { c.resolveAndPlay(song("old")) } catch (_: CancellationException) {} ; survived = isActive }
        entered.await(); c.resolveAndPlay(song("new")); collector.join()
        assertTrue("cancel only the resolve child, not caller", survived)
        assertEquals(1, PlayerService.media.size); assertFalse(c.resolving)
    }
    @Test fun oldFailureCleanupCannotClearNewRequestsStatusOrSkipQueue() = runBlocking {
        val c = RaceController(this)
        val clearing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        UrlResolver.action = { if (it.id == "old") UrlResolver.Result(url = "", failure = ResolveFailure.FAILED)
            else { awaitCancellation() } }
        UrlResolver.clearAction = { withContext(NonCancellable) { clearing.complete(Unit); release.await() } }
        val old = launch { c.resolveAndPlay(song("old"), listOf(song("old"), song("new")), autoSkipOnFailure = true) }
        clearing.await()
        val newer = launch { c.resolveAndPlay(song("new")) }
        while (c.currentSong?.id != "new" || c.status != "智能取链…") yield()
        release.complete(Unit); old.join()
        assertTrue(c.resolving); assertEquals("智能取链…", c.status); assertEquals(0, c.skips)
        newer.cancelAndJoin()
    }
    @Test fun staleResolveFailureAfterCacheClearCannotCommitAgainstNewIntent() = runBlocking {
        val c = RaceController(this)
        val clearing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        UrlResolver.action = { UrlResolver.Result(url = "", failure = ResolveFailure.FAILED) }
        UrlResolver.clearAction = { withContext(NonCancellable) { clearing.complete(Unit); release.await() } }
        val old = launch { c.resolveAndPlay(song("old")) }
        clearing.await()

        val newer = song("new")
        PlayerService.beginPlaybackIntent() // Replaces the intent without cancelling this in-flight resolve child.
        c.queueRevision += 1L
        c.playbackTransactionGate.begin(newer.key(), c.queueRevision, c.controllerOwner)
        c.currentSong = newer
        c.resolving = true
        c.status = "新选择正在取链…"

        release.complete(Unit)
        old.join()

        assertTrue("stale failure must not clear the newer resolve state", c.resolving)
        assertEquals("新选择正在取链…", c.status)
        assertTrue("stale failure must not notify for the superseded song", Toast.messages.isEmpty())
    }
    @Test fun failuresAndLocalEarlyReturnReleaseHold() = runBlocking {
        for (mode in listOf("lookup", "init", "unavailable", "resolver", "decrypt")) {
            reset(); val c = RaceController(this)
            PlayerService.state = PlayerService.Companion.Snapshot(song = song("old"))
            LocalFirst.action = { if (mode == "lookup") error("lookup failed") else null }
            if (mode == "init") c.scriptRuntime.initAction = { error("init failed") }
            UrlResolver.action = { when (mode) {
                "resolver" -> UrlResolver.Result(url = "", failure = ResolveFailure.FAILED)
                "decrypt" -> UrlResolver.Result(url = "https://media.ncm")
                else -> error("must not resolve: $mode")
            } }
            MediaDecryptCache.action = { null }
            c.resolveAndPlay(if (mode == "unavailable") song("target").copy(source = "local") else song("target"))
            assertFalse(mode, c.resolving); assertEquals(mode, "", c.status)
            assertTrue(mode, PlayerService.holds.isEmpty()); assertTrue(mode, PlayerService.media.isEmpty())
        }
    }
    @Test fun acceptedDecryptTransfersLeaseToServiceExactlyOnce() = runBlocking {
        val c = RaceController(this); val lease = MediaCacheLease()
        UrlResolver.action = { UrlResolver.Result(url = "https://media.ncm") }
        MediaDecryptCache.action = { lease }
        c.resolveAndPlay(song("encrypted"))
        assertSame(lease, PlayerService.transferred); assertEquals(0, lease.closes)
        assertEquals(listOf(lease.path), PlayerService.media); assertFalse(c.resolving)
    }
    @Test fun pauseIntentCancelsPendingRecoveryBeforeCacheClearReturns() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = RaceController(owner); val s = song("paused"); c.currentSong = s
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        UrlResolver.clearAction = { entered.complete(Unit); release.await() }
        c.handlePlaybackFailure(PlayerService.Companion.PlaybackFailure(s.key(), 0)); entered.await()
        PlayerService.pause()
        // Entry hook must be wired by Service/UI explicit pause/stop integration.
        c.javaClass.methods.firstOrNull { it.name == "cancelPendingPlayback" }?.invoke(c)
        release.complete(Unit); delay(80)
        assertTrue("pause intent must not restart failed media", PlayerService.media.isEmpty())
        assertFalse(c.resolving); assertEquals("", c.status)
        owner.cancel()
    }
    @Test fun cancelledDecryptHandoffClosesLeaseAndClearsStatus() = runBlocking {
        val c = RaceController(this); val lease = MediaCacheLease()
        UrlResolver.action = { UrlResolver.Result(url = "https://media.ncm") }
        lateinit var task: Job
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MediaDecryptCache.action = {
            entered.complete(Unit); release.await()
            task.cancel(); lease // Cancellation on withContext return must not orphan this lease.
        }
        task = launch { c.resolveAndPlay(song("encrypted")) }; entered.await()
        release.complete(Unit); task.join()
        assertEquals(1, lease.closes)
        assertFalse(c.resolving); assertEquals("", c.status); assertTrue(PlayerService.holds.isEmpty())
    }
    @Test fun staleResultDoesNotRebindSameSongIdentity() = runBlocking {
        val c = RaceController(this); var calls = 0
        UrlResolver.action = { calls++; UrlResolver.Result(url = "", failure = ResolveFailure.STALE_REQUEST) }
        c.resolveAndPlay(song("same"))
        assertEquals(1, calls); assertEquals(1L, c.queueRevision)
        assertTrue(PlayerService.media.isEmpty()); assertFalse(c.resolving); assertEquals("", c.status)
    }
    @Test fun fastBCommitRejectsLateNonCancellableAResult() = runBlocking {
        val c = RaceController(this)
        val aEntered = CompletableDeferred<Unit>(); val releaseA = CompletableDeferred<Unit>()
        UrlResolver.action = { requested ->
            if (requested.id == "A") withContext(NonCancellable) {
                aEntered.complete(Unit); releaseA.await(); UrlResolver.Result(url = "late-A")
            } else UrlResolver.Result(url = "fast-B")
        }
        val a = launch { c.resolveAndPlay(song("A")) }
        aEntered.await()
        c.resolveAndPlay(song("B"))
        releaseA.complete(Unit); a.join()
        assertEquals("only the latest selection may commit media", listOf("fast-B"), PlayerService.media)
        assertEquals("B", c.currentSong?.id)
        assertFalse(c.resolving)
    }
    @Test fun insertNextDuringResolveRebasesTheCurrentSongAndAllowsCommit() = runBlocking {
        val c = RaceController(this)
        val a = song("A"); val b = song("B"); val next = song("C")
        PlayerService.state = PlayerService.Companion.Snapshot(song = a)
        PlayerService.currentSong = a
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        UrlResolver.action = { requested ->
            assertEquals("B", requested.id)
            entered.complete(Unit)
            release.await()
            UrlResolver.Result(url = "resolved-B")
        }

        val resolvingB = launch { c.resolveAndPlay(b, listOf(a, b), 1) }
        entered.await()
        val oldRevision = c.queueRevision
        c.insertNext(next) // exact production queue edit invoked by the Add Next UI handler

        assertEquals(oldRevision + 1L, c.queueRevision)
        assertTrue(c.playbackTransactionGate.hasCurrent(b.key(), c.queueRevision))
        assertEquals(listOf("A", "B", "C"), PlayerService.state.queue.map { it.id })
        assertEquals(1, PlayerService.state.queueIndex)
        release.complete(Unit)
        withTimeout(2_000L) { resolvingB.join() }

        assertEquals(listOf("resolved-B"), PlayerService.media)
        assertEquals("B", PlayerService.state.song?.id)
        assertEquals(listOf("A", "B", "C"), c.queue.map { it.id })
        assertFalse(c.resolving)
        assertEquals("", c.status)
    }
    @Test fun replacingPendingSelectionCancelsAndRestoresServicePlayback() = runBlocking {
        val c = RaceController(this)
        val a = song("A"); val b = song("B")
        PlayerService.state = PlayerService.Companion.Snapshot(song = a)
        PlayerService.currentSong = a
        val entered = CompletableDeferred<Unit>()
        UrlResolver.action = { entered.complete(Unit); awaitCancellation() }

        val resolvingB = launch { c.resolveAndPlay(b, listOf(a, b), 1) }
        entered.await()
        c.replaceQueue(listOf(a), 0)
        resolvingB.join()

        assertEquals("A", c.currentSong?.id)
        assertEquals(listOf("A"), c.queue.map { it.id })
        assertFalse(c.resolving)
        assertEquals("", c.status)
        assertTrue(PlayerService.media.isEmpty())
        assertTrue("cancellation must not be reported as a playback failure", Toast.messages.isEmpty())
    }
    @Test fun naturalEndDuringBResolutionIsHeldButManualNextCStillCommits() = runBlocking {
        val c = RaceController(this)
        val a = song("A"); val b = song("B"); val manualNext = song("C")
        PlayerService.state = PlayerService.Companion.Snapshot(song = a)
        PlayerService.currentSong = a
        var nextCallbacks = 0
        PlayerService.onNeedNext = { nextCallbacks++ }
        val bEntered = CompletableDeferred<Unit>()
        UrlResolver.action = { requested ->
            if (requested.id == "B") { bEntered.complete(Unit); awaitCancellation() }
            else UrlResolver.Result(url = "unexpected-${requested.id}")
        }
        LocalFirst.action = { if (it.id == "C") "/manual-C" else null }
        val resolvingB = launch { c.resolveAndPlay(b, listOf(a, b, manualNext), 1) }
        bEntered.await()
        assertEquals("the transition owns a hold on old A", "play-token", PlayerService.naturalEndHoldToken)
        PlayerService.handleTrackEnded() // exact production PlayerService method
        assertEquals("natural end during pending B must not auto-advance", 0, nextCallbacks)
        assertEquals("no fallback queue advancement", 0, PlayerService.fallbackDispatches)
        c.resolveAndPlay(manualNext, listOf(a, b, manualNext), 2) // explicit next must supersede B
        resolvingB.join()
        assertEquals(listOf("/manual-C"), PlayerService.media)
        assertEquals("C", c.currentSong?.id)
        assertEquals(0, nextCallbacks)
        assertTrue(PlayerService.holds.isEmpty())
    }
    @Test fun localSuccessClearsPriorStatusAndReleasesTransitionHold() = runBlocking {
        val c = RaceController(this); c.status = "previous attempt"
        PlayerService.state = PlayerService.Companion.Snapshot(song = song("old"))
        LocalFirst.action = { "/readable" }
        c.resolveAndPlay(song("local"))
        assertEquals(listOf("/readable"), PlayerService.media)
        assertEquals("", c.status); assertFalse(c.resolving); assertTrue(PlayerService.holds.isEmpty())
    }
    @Test fun stoppedServiceRejectsInFlightResolveBeforeCommit() = runBlocking {
        val c = RaceController(this)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        UrlResolver.action = { entered.complete(Unit); release.await(); UrlResolver.Result() }
        val task = launch { c.resolveAndPlay(song("target")) }; entered.await()
        PlayerService.stopPlayback(); release.complete(Unit); task.join()
        assertTrue("stop generation must invalidate controller resolve", PlayerService.media.isEmpty())
        assertFalse(c.resolving); assertTrue(PlayerService.holds.isEmpty())
    }
}
