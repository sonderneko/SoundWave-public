package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RankDetailMetadataCacheTest {
    @Test fun rankDetailCachePreservesSignedMovementAndFreshTimestamp() {
        val context = RuntimeEnvironment.getApplication()
        val songs = listOf(
            Song("a", "Up", "Artist", "wy", rankChange = 4),
            Song("b", "Down", "Artist", "wy", rankChange = -2),
            Song("c", "Unknown", "Artist", "wy"),
        )
        HomeFeedCache.saveDetail(context, "rank-metadata", "wy", songs)
        val snapshot = HomeFeedCache.loadDetailSnapshot(context, "rank-metadata", "wy")
        assertTrue(snapshot.savedAt > 0L)
        assertEquals(listOf("a", "b", "c"), snapshot.songs.map { it.id })
        assertEquals(listOf(4, -2, null), snapshot.songs.map { it.rankChange })
        assertEquals(listOf(4, -2, null), HomeFeedCache.loadDetail(context, "rank-metadata", "wy").map { it.rankChange })
        assertTrue(snapshot.songs.all { !it.toJson().has("rankChange") })
    }

    @Test fun ordinaryPlaylistCacheNeverCreatesChartMetadata() {
        val context = RuntimeEnvironment.getApplication()
        HomeFeedCache.saveDetail(context, "playlist-metadata", "wy", listOf(Song("x", "Track", "Artist", "wy")))
        val restored = HomeFeedCache.loadDetailSnapshot(context, "playlist-metadata", "wy").songs.single()
        assertNull(restored.rankChange)
    }
}
