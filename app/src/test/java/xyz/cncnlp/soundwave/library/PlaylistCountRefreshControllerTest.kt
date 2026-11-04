package xyz.cncnlp.soundwave.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.Playlist

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistCountRefreshControllerTest {
    @Test
    fun filtersLocalSpecialAndUnverifiedTargetsAndPrefersRemoteIdentity() = runTest {
        val target = Playlist("saved-1", "Old title", source = "wy", remoteId = "remote-55", playCount = 10)
        val calls = mutableListOf<Pair<String, String>>()
        var result: Map<PlaylistCountIdentity, Long> = emptyMap()
        val controller = PlaylistCountRefreshController(this, StandardTestDispatcher(testScheduler)) { name, source ->
            calls += name to source
            listOf(
                Playlist("wrong", "Wrong", source = source, remoteId = "other", playCount = 99),
                Playlist("remote-55", "Renamed", source = source, remoteId = "remote-55", playCount = 75),
            )
        }

        controller.refresh(
            listOf(
                target,
                Playlist("liked", "Liked", source = "wy"),
                Playlist("local", "Local", local = true, source = "wy"),
                Playlist("unverified", "No source"),
            ),
            onCounts = { result = it },
        )
        runCurrent()

        assertEquals(listOf("Old title" to "wy"), calls)
        assertEquals(75L, result[PlaylistCountIdentity.of(target)])
    }

    @Test
    fun newerRefreshCancelsOlderRequestAndOnlyLatestMayCommit() = runTest {
        val oldResponse = CompletableDeferred<List<Playlist>>()
        val oldTarget = Playlist("old", "old target", source = "wy", remoteId = "old-remote")
        val newTarget = Playlist("new", "new target", source = "kw", remoteId = "new-remote")
        val commits = mutableListOf<Map<PlaylistCountIdentity, Long>>()
        val controller = PlaylistCountRefreshController(this, StandardTestDispatcher(testScheduler)) { name, source ->
            if (name == oldTarget.name) oldResponse.await()
            else listOf(Playlist("new-remote", name, source = source, remoteId = "new-remote", playCount = 42))
        }

        val oldJob = controller.refresh(listOf(oldTarget)) { commits += it }
        runCurrent()
        controller.refresh(listOf(newTarget)) { commits += it }
        runCurrent()
        oldResponse.complete(listOf(Playlist("old-remote", "old target", playCount = 11)))
        runCurrent()

        assertTrue(oldJob!!.isCancelled)
        assertEquals(1, commits.size)
        assertEquals(42L, commits.single()[PlaylistCountIdentity.of(newTarget)])
    }

    @Test
    fun ownerChangeRejectsLateResult() = runTest {
        val response = CompletableDeferred<List<Playlist>>()
        val target = Playlist("p1", "Playlist", source = "wy", remoteId = "r1")
        var ownerIsCurrent = true
        var committed = false
        val controller = PlaylistCountRefreshController(
            this,
            StandardTestDispatcher(testScheduler),
            currentContext = { if (ownerIsCurrent) "account-a" else null },
        ) { _, _ -> response.await() }

        controller.refresh(listOf(target)) { committed = true }
        runCurrent()
        ownerIsCurrent = false
        response.complete(listOf(Playlist("r1", "Playlist", playCount = 9)))
        runCurrent()

        assertFalse(committed)
    }

    @Test
    fun inactiveRouteDoesNotStartNetworkLoader() = runTest {
        val target = Playlist("p1", "Playlist", source = "wy", remoteId = "r1")
        var calls = 0
        val controller = PlaylistCountRefreshController(
            this,
            StandardTestDispatcher(testScheduler),
            currentContext = { null },
        ) { _, _ -> calls++; emptyList() }

        val job = controller.refresh(listOf(target)) { error("inactive route must not commit") }

        assertNull(job)
        assertEquals(0, calls)
    }

    @Test
    fun invalidateCancelsInFlightWorkOnRouteExit() = runTest {
        val response = CompletableDeferred<List<Playlist>>()
        val target = Playlist("p1", "Playlist", source = "wy", remoteId = "r1")
        var committed = false
        val controller = PlaylistCountRefreshController(this, StandardTestDispatcher(testScheduler)) { _, _ -> response.await() }

        val job = controller.refresh(listOf(target)) { committed = true }
        runCurrent()
        controller.invalidate()
        runCurrent()

        assertTrue(job!!.isCancelled)
        assertFalse(committed)
    }

    @Test
    fun cancellationFromLoaderIsNotConvertedToEmptySuccess() = runTest {
        val target = Playlist("p1", "Playlist", source = "wy", remoteId = "r1")
        var committed = false
        val controller = PlaylistCountRefreshController(this, StandardTestDispatcher(testScheduler)) { _, _ ->
            throw CancellationException("cancelled fake request")
        }

        val job = controller.refresh(listOf(target)) { committed = true }
        runCurrent()

        assertTrue(job!!.isCancelled)
        assertFalse(committed)
    }

    @Test
    fun ordinaryLoaderFailureIsSkippedAndMergeIsProviderAware() = runTest {
        val target = Playlist("same", "Playlist", source = "wy", remoteId = "same-remote")
        var committed = false
        val failureController = PlaylistCountRefreshController(this, StandardTestDispatcher(testScheduler)) { _, _ ->
            throw IllegalStateException("fake local failure")
        }
        failureController.refresh(listOf(target)) { committed = true }
        runCurrent()
        assertFalse(committed)

        val counts = mapOf(PlaylistCountIdentity.of(target) to 100L)
        val updated = applyPlaylistCountUpdates(
            listOf(target, target.copy(source = "kw")),
            counts,
        )
        assertEquals(100L, updated[0].playCount)
        assertEquals(target.playCount, updated[1].playCount)
    }
}
