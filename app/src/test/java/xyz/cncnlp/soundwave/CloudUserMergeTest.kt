package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.sync.CloudUserMerge
import xyz.cncnlp.soundwave.sync.SongPlayCountCounterMerge

class CloudUserMergeTest {
    private fun song(id: String, addedAt: Long = 0L) =
        Song(id = id, name = "Song $id", singer = "Artist", source = "wy", syncAddedAt = addedAt)

    private fun playlist(id: String, vararg songs: Song) =
        Playlist(id = id, name = id, local = true, songs = songs.toMutableList())

    @Test
    fun deletedFavoriteDoesNotReturnFromOldCloudSnapshot() {
        val removed = song("removed")
        val local = LocalStore.UserBundle(
            deletedFavoriteKeys = linkedMapOf(removed.key() to 100L)
        )
        val cloud = LocalStore.UserBundle(
            favs = mutableListOf(removed),
            playlists = mutableListOf(playlist("liked", removed))
        )

        val merged = CloudUserMerge.merge(local, cloud)

        assertFalse(merged.favs.any { it.key() == removed.key() })
        assertTrue(merged.deletedFavoriteKeys.containsKey(removed.key()))
        assertTrue(merged.playlists.first { it.id == "liked" }.songs.isEmpty())
    }

    @Test
    fun ordinaryFavoriteAdditionsFromBothDevicesArePreserved() {
        val cloudSong = song("cloud-new", 20L)
        val localSong = song("local-new", 30L)
        val merged = CloudUserMerge.merge(
            LocalStore.UserBundle(favs = mutableListOf(localSong)),
            LocalStore.UserBundle(favs = mutableListOf(cloudSong))
        )

        assertEquals(setOf(cloudSong.key(), localSong.key()), merged.favs.map { it.key() }.toSet())
    }

    @Test
    fun explicitReAddAfterObservedFavoriteDeletionWins() {
        val track = song("re-added")
        val local = LocalStore.UserBundle(
            deletedFavoriteKeys = linkedMapOf(track.key() to 100L)
        )
        local.addFavorite(track, nowMs = 200L)
        val cloud = LocalStore.UserBundle(
            favs = mutableListOf(track),
            deletedFavoriteKeys = linkedMapOf(track.key() to 100L)
        )

        val merged = CloudUserMerge.merge(local, cloud)

        assertEquals(1, merged.favs.size)
        assertTrue(merged.favs.single().syncAddedAt > 100L)
        assertFalse(merged.deletedFavoriteKeys.containsKey(track.key()))
    }

    @Test
    fun deletedPlaylistSongIsNotBackfilledFromLargerCloudSnapshot() {
        val removed = song("removed-in-playlist")
        val cloudNewA = song("cloud-a", 110L)
        val cloudNewB = song("cloud-b", 120L)
        val localNew = song("local-new", 130L)
        val local = LocalStore.UserBundle(
            playlists = mutableListOf(playlist("mix", localNew)),
            deletedPlaylistSongKeys = linkedMapOf("mix" to linkedMapOf(removed.key() to 100L))
        )
        val cloud = LocalStore.UserBundle(
            playlists = mutableListOf(playlist("mix", removed, cloudNewA, cloudNewB))
        )

        val merged = CloudUserMerge.merge(local, cloud)
        val songs = merged.playlists.first { it.id == "mix" }.songs.map { it.key() }.toSet()

        assertFalse(removed.key() in songs)
        assertTrue(cloudNewA.key() in songs)
        assertTrue(cloudNewB.key() in songs)
        assertTrue(localNew.key() in songs)
    }

    @Test
    fun loginMergePreservesPlatformPlaylistPopularity() {
        val localAhead = CloudUserMerge.merge(
            LocalStore.UserBundle(playlists = mutableListOf(playlist("mix").copy(playCount = 9L))),
            LocalStore.UserBundle(playlists = mutableListOf(playlist("mix").copy(playCount = 4L)))
        )
        val cloudAhead = CloudUserMerge.merge(
            LocalStore.UserBundle(playlists = mutableListOf(playlist("mix").copy(playCount = 4L))),
            LocalStore.UserBundle(playlists = mutableListOf(playlist("mix").copy(playCount = 9L)))
        )

        assertEquals(9L, localAhead.playlists.single { it.id == "mix" }.playCount)
        assertEquals(9L, cloudAhead.playlists.single { it.id == "mix" }.playCount)
    }

    @Test
    fun undatedLegacySongCountersRemainLocalAndCloudCountersAreIgnored() {
        val key = "wy:track"
        val local = LocalStore.UserBundle(songPlayCountCounters = linkedMapOf(
            key to linkedMapOf("device-a" to 3L)
        ))
        val cloud = LocalStore.UserBundle(songPlayCountCounters = linkedMapOf(
            key to linkedMapOf("device-a" to 2L, "device-b" to 4L)
        ))

        val merged = CloudUserMerge.merge(local, cloud)
        val repeated = CloudUserMerge.merge(merged, cloud)

        assertEquals(mapOf("device-a" to 3L), merged.songPlayCountCounters[key])
        assertEquals(3L, SongPlayCountCounterMerge.total(merged.songPlayCountCounters.getValue(key)))
        assertEquals(merged.songPlayCountCounters, repeated.songPlayCountCounters)
    }

    @Test
    fun legacySongCountersStayLocalAndAreSuppressedFromCloudProjection() {
        val bundle = LocalStore.UserBundle(songPlayCountCounters = linkedMapOf(
            "wy:track" to linkedMapOf("device-a" to 7L)
        ))

        val localJson = bundle.toCloudJson(includePlayCountCounters = true)
        val cloudJson = bundle.toCloudJson(includePlayCountCounters = true, includeLocalUris = false)

        assertFalse(bundle.toCloudJson().has("playCountCounters"))
        assertEquals(7L, localJson.getJSONObject("playCountCounters")
            .getJSONObject("wy:track").getLong("device-a"))
        assertFalse(cloudJson.has("playCountCounters"))
        assertEquals(7L, LocalStore.UserBundle.fromCloud(localJson)
            .songPlayCountCounters.getValue("wy:track").getValue("device-a"))
    }

    @Test
    fun cloudSerializationDropsDeviceLocalUrisButLocalStorageCanKeepThem() {
        val localPath = "file:///mock-device/Music/private-track.mp3"
        val track = song("local-track").copy(
            source = "local",
            cover = "content://mock-device/covers/private-track.jpg",
            resolvedUrl = localPath,
            playedAt = System.currentTimeMillis()
        )
        val playlistTrack = song("playlist-track").copy(cover = "/mock-device/covers/playlist-track.jpg")
        val remoteTrack = song("remote-track").copy(cover = "https://cdn.example/cover.jpg")
        val localPlaylist = Playlist(
            id = "local-playlist",
            name = "Local playlist",
            cover = "file:///mock-device/covers/local-playlist.jpg",
            songs = mutableListOf(playlistTrack),
            local = true
        )
        val bundle = LocalStore.UserBundle(
            favs = mutableListOf(track, remoteTrack),
            playlists = mutableListOf(localPlaylist),
            history = mutableListOf(track)
        )

        val localJson = bundle.toCloudJson()
        assertEquals(localPath, localJson.getJSONArray("favs").getJSONObject(0).getString("resolvedUrl"))
        assertEquals("content://mock-device/covers/private-track.jpg", localJson.getJSONArray("favs").getJSONObject(0).getString("cover"))
        val localPlaylistsJson = localJson.getJSONArray("pls")
        val localPlaylistJson = (0 until localPlaylistsJson.length()).mapNotNull { localPlaylistsJson.optJSONObject(it) }
            .first { it.optString("id") == "local-playlist" }
        assertEquals("file:///mock-device/covers/local-playlist.jpg", localPlaylistJson.getString("cover"))
        val remoteJson = bundle.toCloudJson(includeLocalUris = false)
        assertFalse(remoteJson.getJSONArray("favs").getJSONObject(0).has("resolvedUrl"))
        assertFalse(remoteJson.getJSONArray("history").getJSONObject(0).has("resolvedUrl"))
        assertEquals("", remoteJson.getJSONArray("favs").getJSONObject(0).getString("cover"))
        assertEquals("https://cdn.example/cover.jpg", remoteJson.getJSONArray("favs").getJSONObject(1).getString("cover"))
        assertEquals("", filterDeviceLocalReference("C:\\Users\\mock\\cover.jpg", includeLocalUris = false))
        assertEquals("//cdn.example/protocol-relative.jpg", filterDeviceLocalReference("//cdn.example/protocol-relative.jpg", includeLocalUris = false))
        val remotePlaylistsJson = remoteJson.getJSONArray("pls")
        val remotePlaylistJson = (0 until remotePlaylistsJson.length()).mapNotNull { remotePlaylistsJson.optJSONObject(it) }
            .first { it.optString("id") == "local-playlist" }
        assertEquals("", remotePlaylistJson.getString("cv"))
        assertEquals("", remotePlaylistJson.getJSONArray("songs").getJSONObject(0).getString("cover"))
        assertFalse(remoteJson.toString().contains("mock-device"))

        val loadedRemote = LocalStore.UserBundle.fromCloud(localJson, allowLocalUris = false)
        assertEquals("", loadedRemote.favs.first { it.id == "local-track" }.resolvedUrl)
        assertEquals("", loadedRemote.favs.first { it.id == "local-track" }.cover)
        assertEquals("https://cdn.example/cover.jpg", loadedRemote.favs.first { it.id == "remote-track" }.cover)
        assertEquals("", loadedRemote.history.single().resolvedUrl)
        assertEquals("", loadedRemote.history.single().cover)
        assertEquals("", loadedRemote.playlists.first { it.id == "local-playlist" }.cover)
        assertEquals("", loadedRemote.playlists.first { it.id == "local-playlist" }.songs.single().cover)
    }

    @Test
    fun oldPersonalPlaylistPlayCountIsIgnoredAndNeverReemitted() {
        val legacy = JSONObject().put("pls", JSONArray().put(JSONObject()
            .put("id", "mix")
            .put("name", "Mix")
            .put("userPlayCount", 12L)))

        val loaded = LocalStore.UserBundle.fromCloud(legacy)
        val encodedPlaylist = loaded.toCloudJson().getJSONArray("pls").getJSONObject(0)

        assertTrue(loaded.songPlayCountCounters.isEmpty())
        assertFalse(encodedPlaylist.has("userPlayCount"))
    }

    @Test
    fun deviceLocalPlaylistSyncsItsCoverButNeitherTracksNorTrackCount() {
        val localSong = song("device-local-file").copy(source = "local", resolvedUrl = "file:///private/Music/song.mp3")
        val localPlaylist = Playlist(
            id = "local", name = "本地歌单", cover = "https://covers.example/local-list.jpg",
            songs = mutableListOf(localSong), local = true, count = 1
        )
        val bundle = LocalStore.UserBundle(playlists = mutableListOf(localPlaylist))

        val remoteJson = bundle.toCloudJson(includeLocalUris = false)
        val encoded = remoteJson.getJSONArray("pls").let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.first { it.optString("id") == "local" }
        }
        assertEquals("https://covers.example/local-list.jpg", encoded.getString("cover"))
        assertEquals(0, encoded.getInt("count"))
        assertEquals(0, encoded.getJSONArray("songs").length())
        assertFalse(remoteJson.toString().contains("device-local-file"))

        val restored = LocalStore.UserBundle.fromCloud(remoteJson, allowLocalUris = false)
        val localRestored = restored.playlists.first { it.id == "local" }
        assertEquals("https://covers.example/local-list.jpg", localRestored.cover)
        assertTrue(localRestored.songs.isEmpty())
        assertEquals(0, localRestored.count)
    }

    @Test
    fun cloudCustomPlaylistCoverWinsOverDerivedTrackCover() {
        val track = song("derived-track").copy(cover = "https://cdn.example/track.jpg")
        val customCloudCover = "data:image/png;base64,custom-cloud-cover"
        val local = LocalStore.UserBundle(playlists = mutableListOf(Playlist(
            id = "mix", name = "Mix", cover = track.cover, songs = mutableListOf(track), local = false
        )))
        val cloud = LocalStore.UserBundle(playlists = mutableListOf(Playlist(
            id = "mix", name = "Mix", cover = customCloudCover, local = false
        )))

        val merged = CloudUserMerge.merge(local, cloud)
        val encoded = merged.toCloudJson(includeLocalUris = false).getJSONArray("pls")
            .let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }.first { it.optString("id") == "mix" } }

        assertEquals(customCloudCover, merged.playlists.first { it.id == "mix" }.cover)
        assertEquals(customCloudCover, encoded.getString("cover"))
    }

    @Test
    fun deviceLocalPlaylistCoverRemainsLocalAndPortableCloudCoverIsProjected() {
        val localCover = "content://mock-device/covers/custom.jpg"
        val cloudCover = "https://cdn.example/cloud-cover.jpg"
        val local = LocalStore.UserBundle(playlists = mutableListOf(Playlist(
            id = "mix", name = "Mix", cover = localCover, local = false
        )))
        val cloud = LocalStore.UserBundle(playlists = mutableListOf(Playlist(
            id = "mix", name = "Mix", cover = cloudCover, local = false
        )))

        val merged = CloudUserMerge.merge(local, cloud)
        val localJson = merged.toCloudJson(includeLocalUris = true).getJSONArray("pls")
            .let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }.first { it.optString("id") == "mix" } }
        val cloudJson = merged.toCloudJson(includeLocalUris = false).getJSONArray("pls")
            .let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }.first { it.optString("id") == "mix" } }

        assertEquals(localCover, localJson.getString("cover"))
        assertEquals(cloudCover, cloudJson.getString("cover"))
    }

    @Test
    fun oversizedInlineCoverFailsCloudProjectionWithoutDroppingLocalValue() {
        val largeCover = "data:image/png;base64," + "a".repeat(MAX_CLOUD_INLINE_IMAGE_CHARS)
        val bundle = LocalStore.UserBundle(favs = mutableListOf(song("large-cover").copy(cover = largeCover)))

        val failure = runCatching { bundle.toCloudJson(includeLocalUris = false) }.exceptionOrNull()

        assertTrue(failure is CloudInlineImageTooLargeException)
        assertEquals(largeCover, bundle.favs.single().cover)
    }

    @Test
    fun accountHistoryMergeKeepsNewestDuplicateAndDropsExpiredOrUntimedRows() {
        val now = System.currentTimeMillis()
        val localNew = song("same").copy(name = "Local newest", playedAt = now - 1_000L)
        val cloudOld = song("same").copy(name = "Cloud old", playedAt = now - 2_000L)
        val expired = song("expired").copy(playedAt = now - PlaybackHistoryPolicy.CLOUD_RETENTION_MS - 1L)
        val unknown = song("unknown")

        val merged = CloudUserMerge.merge(
            LocalStore.UserBundle(history = mutableListOf(localNew, expired, unknown)),
            LocalStore.UserBundle(history = mutableListOf(cloudOld)),
            nowMs = now
        )

        assertEquals(listOf("same"), merged.history.map { it.id })
        assertEquals("Local newest", merged.history.single().name)
    }

    @Test
    fun singleHistoryDeletionWinsAcrossDevicesAndSurvivesCloudJsonRoundTrip() {
        val now = System.currentTimeMillis()
        val deletedPlay = song("deleted-play").copy(playedAt = now - 2_000L)
        val replay = deletedPlay.copy(playedAt = now - 1_000L)
        val eventKey = PlaybackHistoryPolicy.historyEventKey(deletedPlay)
        val local = LocalStore.UserBundle(
            history = mutableListOf(deletedPlay, replay),
            historyDeletionTombstones = linkedMapOf(eventKey to now - 500L)
        )
        val cloud = LocalStore.UserBundle(history = mutableListOf(deletedPlay))

        val merged = CloudUserMerge.merge(local, cloud, nowMs = now)
        val encoded = merged.toCloudJson(includeLocalUris = false)
        val restored = LocalStore.UserBundle.fromCloud(encoded, allowLocalUris = false)

        assertEquals(listOf(replay), merged.history)
        assertEquals(listOf(replay), restored.history)
        assertEquals(now - 500L, restored.historyDeletionTombstones[eventKey])
        assertFalse((0 until encoded.getJSONArray("history").length()).any { index ->
            encoded.getJSONArray("history").getJSONObject(index).getLong("playedAt") == deletedPlay.playedAt
        })
        assertTrue(encoded.getJSONObject("_swDeletedHistoryEvents").has(eventKey))
    }

    @Test
    fun removedPlaylistDoesNotReturnFromCloudSnapshot() {
        val local = LocalStore.UserBundle(deletedPlaylistIds = linkedMapOf("mix" to 100L))
        val cloud = LocalStore.UserBundle(playlists = mutableListOf(playlist("mix", song("old"))))

        val merged = CloudUserMerge.merge(local, cloud)

        assertFalse(merged.playlists.any { it.id == "mix" })
        assertEquals(100L, merged.deletedPlaylistIds["mix"])
    }

    @Test
    fun legacyJsonWithoutTombstonesLoadsNormally() {
        val favorite = song("legacy-favorite")
        val oldPlaylistSong = song("legacy-playlist-song")
        val json = JSONObject()
            .put("favs", JSONArray().put(favorite.toJson()))
            .put("pls", JSONArray().put(JSONObject()
                .put("id", "legacy-list")
                .put("name", "Legacy")
                .put("songs", JSONArray().put(oldPlaylistSong.toJson()))))

        val loaded = LocalStore.UserBundle.fromCloud(json)

        assertEquals(listOf(favorite.key()), loaded.favs.map { it.key() })
        assertEquals(listOf(oldPlaylistSong.key()), loaded.playlists.first { it.id == "legacy-list" }.songs.map { it.key() })
        assertTrue(loaded.deletedFavoriteKeys.isEmpty())
        assertTrue(loaded.deletedPlaylistIds.isEmpty())
        assertTrue(loaded.deletedPlaylistSongKeys.isEmpty())
    }

    @Test
    fun tombstonesRoundTripInsideAcceptedArrayElements() {
        val activeFavorite = song("active-favorite", 20L)
        val activePlaylistSong = song("active-playlist-song", 30L)
        val bundle = LocalStore.UserBundle(
            favs = mutableListOf(activeFavorite),
            playlists = mutableListOf(playlist("mix", activePlaylistSong).copy(syncCreatedAt = 40L)),
            deletedFavoriteKeys = linkedMapOf("wy:deleted-favorite" to 50L),
            deletedPlaylistIds = linkedMapOf("deleted-list" to 60L),
            deletedPlaylistSongKeys = linkedMapOf("mix" to linkedMapOf("wy:deleted-song" to 70L))
        )

        val json = bundle.toCloudJson()
        val favs = json.getJSONArray("favs")
        val playlists = json.getJSONArray("pls")
        assertFalse(json.has("_swDeletedFavorites"))
        assertFalse(json.has("_swDeletedPlaylists"))
        assertNotNull((0 until favs.length()).mapNotNull { favs.optJSONObject(it) }
            .firstOrNull { it.has("_swDeletedFavorites") })
        assertNotNull((0 until playlists.length()).mapNotNull { playlists.optJSONObject(it) }
            .firstOrNull { it.has("_swDeletedPlaylists") && it.has("_swDeletedPlaylistSongs") })

        val loaded = LocalStore.UserBundle.fromCloud(json)

        assertEquals(50L, loaded.deletedFavoriteKeys["wy:deleted-favorite"])
        assertEquals(60L, loaded.deletedPlaylistIds["deleted-list"])
        assertEquals(70L, loaded.deletedPlaylistSongKeys["mix"]?.get("wy:deleted-song"))
        assertEquals(20L, loaded.favs.single { it.key() == activeFavorite.key() }.syncAddedAt)
        assertEquals(30L, loaded.playlists.first { it.id == "mix" }.songs.single().syncAddedAt)
        assertEquals(40L, loaded.playlists.first { it.id == "mix" }.syncCreatedAt)
    }
}
