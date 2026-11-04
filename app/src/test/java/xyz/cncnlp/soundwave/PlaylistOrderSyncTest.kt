package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.sync.CloudUserMerge

class PlaylistOrderSyncTest {
    private fun playlist(
        id: String,
        order: Int? = null,
        orderAt: Long = 0L,
        hidden: Boolean = false,
        songs: List<Song> = emptyList()
    ) = Playlist(
        id = id,
        name = id,
        local = true,
        songs = songs.toMutableList(),
        hidden = hidden,
        syncOrder = order,
        syncOrderAt = orderAt
    )

    @Test
    fun reorderMetadataRoundTripsInsidePlaylistElementsAndRestoresAfterCloudMerge() {
        val original = listOf(
            playlist("first"),
            playlist("second"),
            playlist("third"),
            playlist("liked"),
            playlist("local")
        )
        val reordered = PlaylistOrderSync.withOrder(
            original,
            requestedOrder = listOf("third", "first", "second"),
            nowMs = 5_000L
        )
        val expected = listOf("third", "first", "second")
        assertEquals(expected, PlaylistOrderSync.displayOrder(reordered, emptyList()))

        val json = LocalStore.UserBundle(playlists = reordered.toMutableList()).toCloudJson()
        assertFalse(json.has("_swLibOrder"))
        val playlistElements = json.getJSONArray("pls").toObjects()
        val rankedElements = playlistElements.filter { it.optInt("_swLibOrder", -1) >= 0 }
        assertEquals(3, rankedElements.size)
        assertTrue(rankedElements.all { it.optLong("_swLibOrderAt", 0L) == 5_000L })
        assertEquals(expected, rankedElements.sortedBy { it.optInt("_swLibOrder") }.map { it.getString("id") })

        val cloudBundle = LocalStore.UserBundle.fromCloud(json)
        val merged = CloudUserMerge.merge(LocalStore.UserBundle(), cloudBundle)
        assertEquals(expected, PlaylistOrderSync.displayOrder(merged.playlists, emptyList()))
        assertEquals(5_000L, merged.playlists.first { it.id == "third" }.syncOrderAt)
    }

    @Test
    fun legacyCloudWithoutOrderFieldsUsesFilteredLocalPreferenceOrder() {
        val json = JSONObject().put(
            "pls",
            JSONArray()
                .put(JSONObject().put("id", "first").put("name", "first"))
                .put(JSONObject().put("id", "second").put("name", "second"))
                .put(JSONObject().put("id", "third").put("name", "third"))
        )
        val loaded = LocalStore.UserBundle.fromCloud(json)

        assertTrue(loaded.playlists.all { it.syncOrder == null })
        assertEquals(
            listOf("third", "second", "first"),
            PlaylistOrderSync.displayOrder(loaded.playlists, listOf("third", "missing", "second", "third"))
        )
    }

    @Test
    fun newerCloudOrderRevisionWinsOnLoginMerge() {
        val local = LocalStore.UserBundle(playlists = mutableListOf(
            playlist("first", order = 1, orderAt = 300L),
            playlist("second", order = 0, orderAt = 300L)
        ))
        val cloud = LocalStore.UserBundle(playlists = mutableListOf(
            playlist("first", order = 0, orderAt = 200L),
            playlist("second", order = 1, orderAt = 200L)
        ))

        val merged = CloudUserMerge.merge(local, cloud)

        assertEquals(listOf("second", "first"), PlaylistOrderSync.displayOrder(merged.playlists, emptyList()))
    }

    @Test
    fun hiddenAndDeletedEntriesDoNotBreakOrderOrLoseTombstones() {
        val removedSong = Song(id = "removed", name = "Removed", singer = "Artist", source = "wy")
        val local = LocalStore.UserBundle(
            playlists = mutableListOf(
                playlist("hidden", order = 0, orderAt = 500L, hidden = true),
                playlist("visible", order = 1, orderAt = 500L)
            ),
            deletedPlaylistIds = linkedMapOf("deleted" to 600L),
            deletedPlaylistSongKeys = linkedMapOf("visible" to linkedMapOf(removedSong.key() to 700L))
        )
        val cloud = LocalStore.UserBundle(playlists = mutableListOf(
            playlist("visible", order = 1, orderAt = 500L),
            playlist("deleted", order = 2, orderAt = 500L),
            playlist("hidden", order = 0, orderAt = 500L, hidden = true)
        ))

        val merged = CloudUserMerge.merge(local, cloud)
        assertEquals(listOf("hidden", "visible"), PlaylistOrderSync.displayOrder(merged.playlists, emptyList()))
        assertTrue(merged.playlists.first { it.id == "hidden" }.hidden)
        assertFalse(merged.playlists.any { it.id == "deleted" })
        assertEquals(600L, merged.deletedPlaylistIds["deleted"])
        assertTrue(merged.deletedPlaylistSongKeys["visible"]?.containsKey(removedSong.key()) == true)

        val roundTrip = LocalStore.UserBundle.fromCloud(merged.toCloudJson())
        assertFalse(roundTrip.playlists.any { it.id == "deleted" })
        assertEquals(600L, roundTrip.deletedPlaylistIds["deleted"])
        assertEquals(listOf("hidden", "visible"), PlaylistOrderSync.displayOrder(roundTrip.playlists, emptyList()))
    }

    @Test
    fun newPlaylistsAppendStablyUntilTheNextExplicitReorder() {
        val playlists = listOf(
            playlist("first", order = 0, orderAt = 100L),
            playlist("second", order = 1, orderAt = 100L),
            playlist("new-z"),
            playlist("new-a"),
            playlist("liked"),
            playlist("local")
        )
        val expected = listOf("first", "second", "new-z", "new-a")

        assertEquals(expected, PlaylistOrderSync.displayOrder(playlists, emptyList()))
        assertEquals(expected, PlaylistOrderSync.displayOrder(playlists, emptyList()))

        val explicit = PlaylistOrderSync.withOrder(playlists, listOf("missing", "second", "first"), nowMs = 200L)
        assertEquals(listOf("second", "first", "new-z", "new-a"), PlaylistOrderSync.displayOrder(explicit, emptyList()))
        assertNull(explicit.first { it.id == "liked" }.syncOrder)
    }

    private fun JSONArray.toObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
