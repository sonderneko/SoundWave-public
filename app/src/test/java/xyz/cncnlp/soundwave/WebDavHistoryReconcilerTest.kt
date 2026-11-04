package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import xyz.cncnlp.soundwave.sync.WebDavHistoryReconciler

class WebDavHistoryReconcilerTest {
    @Test
    fun duplicateSongUsesNewestPlayedAtInsteadOfRemoteFirstOrder() {
        val localNewer = song("duplicate", playedAt = 300L, name = "Local newer")
        val remoteOlder = song("duplicate", playedAt = 200L, name = "Remote older")

        val result = WebDavHistoryReconciler.reconcile(
            independentHistory = listOf(localNewer),
            localBundleHistory = emptyList(),
            webDavHistory = listOf(remoteOlder),
            nowMs = 1_000L
        )

        assertEquals(listOf(localNewer), result)
    }

    @Test
    fun exactPlayedAtTiePrefersIndependentOnDeviceHistory() {
        val onDevice = song("same-song", playedAt = 500L, name = "On device")
        val webDav = song("same-song", playedAt = 500L, name = "WebDAV copy")

        val result = WebDavHistoryReconciler.reconcile(
            independentHistory = listOf(onDevice),
            localBundleHistory = emptyList(),
            webDavHistory = listOf(webDav),
            nowMs = 1_000L
        )

        assertEquals(listOf(onDevice), result)
    }

    @Test
    fun differentSongsWithEqualTimestampsHaveStableKeyOrdering() {
        val localZ = song("z-song", playedAt = 700L)
        val localY = song("y-song", playedAt = 700L)
        val localA = song("a-song", playedAt = 700L)
        val remoteM = song("m-song", playedAt = 700L)
        val remoteB = song("b-song", playedAt = 700L)

        val first = WebDavHistoryReconciler.reconcile(
            independentHistory = listOf(localZ, localY),
            localBundleHistory = listOf(localA),
            webDavHistory = listOf(remoteM, remoteB),
            nowMs = 1_000L
        )
        val second = WebDavHistoryReconciler.reconcile(
            independentHistory = listOf(localY, localZ),
            localBundleHistory = listOf(localA),
            webDavHistory = listOf(remoteB, remoteM),
            nowMs = 1_000L
        )

        val expectedIds = listOf("a-song", "b-song", "m-song", "y-song", "z-song")
        assertEquals(expectedIds, first.map { it.id })
        assertEquals(expectedIds, second.map { it.id })
    }

    @Test
    fun historyCeilingIsAppliedAfterOrderingAndRetainsNewestLocalRecords() {
        val localHistory = (1..305).map { index ->
            song("local-$index", playedAt = index.toLong())
        }
        val oldRemoteHistory = (1..30).map { index ->
            song("remote-$index", playedAt = 0L)
        }

        val result = WebDavHistoryReconciler.reconcile(
            independentHistory = localHistory,
            localBundleHistory = emptyList(),
            webDavHistory = oldRemoteHistory,
            nowMs = 1_000L
        )

        assertEquals(300, result.size)
        assertEquals((305 downTo 6).map { "local-$it" }, result.map { it.id })
        assertFalse(result.any { it.id.startsWith("remote-") })
    }

    @Test
    fun expiredAndUntimedRowsAreDroppedBeforeWebDavDuplicateResolution() {
        val now = 10_000_000_000L
        val localOld = song("duplicate", playedAt = now - PlaybackHistoryPolicy.RETENTION_MS - 1L, name = "old local")
        val remoteNew = song("duplicate", playedAt = now - 1_000L, name = "new remote")
        val unknown = song("unknown", playedAt = 0L)

        val result = WebDavHistoryReconciler.reconcile(
            independentHistory = listOf(localOld, unknown),
            localBundleHistory = emptyList(),
            webDavHistory = listOf(remoteNew),
            nowMs = now
        )

        assertEquals(listOf("duplicate"), result.map { it.id })
        assertEquals("new remote", result.single().name)
    }

    @Test
    fun deletionMarkerSuppressesMatchingLocalAndWebDavPlayButNotLaterReplay() {
        val now = 10_000_000_000L
        val deletedPlay = song("same-song", playedAt = now - 2_000L)
        val replay = song("same-song", playedAt = now - 1_000L)
        val deletedKey = PlaybackHistoryPolicy.historyEventKey(deletedPlay)

        val result = WebDavHistoryReconciler.reconcile(
            independentHistory = listOf(deletedPlay),
            localBundleHistory = emptyList(),
            webDavHistory = listOf(deletedPlay, replay),
            nowMs = now,
            deletedHistoryEventKeys = setOf(deletedKey)
        )

        assertEquals(listOf(replay), result)
    }

    private fun song(id: String, playedAt: Long, name: String = id) = Song(
        id = id,
        name = name,
        singer = "Test Artist",
        source = "wy",
        playedAt = playedAt
    )
}
