package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.sync.CloudUserMerge

class LocalPlaylistMetadataSyncTest {
    private fun playlist(bundle: LocalStore.UserBundle, id: String): Playlist =
        bundle.playlists.first { it.id == id }

    private fun cloudPlaylist(json: JSONObject, id: String): JSONObject {
        val playlists = json.getJSONArray("pls")
        return (0 until playlists.length())
            .mapNotNull { playlists.optJSONObject(it) }
            .first { it.optString("id") == id }
    }

    @Test
    fun compactAndFullCloudRoundTripsKeepInlineAndHttpCoversAndTrackDuration() {
        listOf(false, true).forEach { compact ->
          listOf("https://cdn.example.test/playlist.jpg", "data:image/png;base64,aW5saW5l").forEach { playlistCover ->
            val expectedSongs = mutableListOf(
                Song(
                    id = "inline-track",
                    name = "Inline Artwork",
                    singer = "Artist",
                    source = "wy",
                    cover = "data:image/png;base64,aW5saW5l",
                    duration = 123_000L,
                    syncAddedAt = 20L
                ),
                Song(
                    id = "http-track",
                    name = "HTTP Artwork",
                    singer = "Artist",
                    source = "wy",
                    cover = "https://cdn.example.test/cover.jpg",
                    duration = 245_000L,
                    syncAddedAt = 21L
                )
            )
            val original = LocalStore.UserBundle(
                playlists = mutableListOf(
                    Playlist(
                        id = "local_metadata_roundtrip",
                        name = "Metadata Round Trip",
                        cover = playlistCover,
                        songs = expectedSongs,
                        local = true
                    )
                )
            )
            val firstUpload = original.syncSnapshot().toCloudJson(
                includeLocalUris = false,
                compact = compact
            )
            val cloudRestore = LocalStore.UserBundle.fromCloud(firstUpload, allowLocalUris = false)
            val merged = CloudUserMerge.merge(LocalStore.UserBundle(), cloudRestore, nowMs = 100L)
            val secondUpload = merged.syncSnapshot().toCloudJson(
                includeLocalUris = false,
                compact = compact
            )
            val finalRestore = LocalStore.UserBundle.fromCloud(secondUpload, allowLocalUris = false)
            val restored = playlist(finalRestore, "local_metadata_roundtrip")

            assertEquals(!compact, cloudPlaylist(firstUpload, restored.id).has("name"))
            assertEquals(expectedSongs.map { it.id }, restored.songs.map { it.id })
            assertEquals(expectedSongs.map { it.cover }, restored.songs.map { it.cover })
            assertEquals(playlistCover, restored.cover)
            assertEquals(368_000L, restored.songs.sumOf { it.duration })
            assertEquals(368_000L, playlist(merged, restored.id).songs.sumOf { it.duration })
          }
        }
    }

    @Test
    fun localUrisStayInLocalSnapshotButAreFilteredFromCloudProjection() {
        val localReferences = listOf(
            "content://media/external/audio/track/11",
            "file:///data/user/0/example/music/track.mp3",
            "/storage/emulated/0/Music/track.mp3"
        )
        val songs = localReferences.mapIndexed { index, uri ->
            Song(
                id = "device-track-$index",
                name = "Device Track $index",
                singer = "Artist",
                source = "local",
                cover = uri,
                duration = 60_000L + index,
                resolvedUrl = uri
            )
        }.toMutableList()
        val original = LocalStore.UserBundle(
            playlists = mutableListOf(
                Playlist(
                    id = "local_device_paths",
                    name = "Device Paths",
                    cover = "content://media/external/images/media/22",
                    songs = songs,
                    local = true
                )
            )
        )
        val localJson = original.syncSnapshot().toCloudJson(includeLocalUris = true)
        val localRestore = LocalStore.UserBundle.fromCloud(localJson, allowLocalUris = true)
        val locallyStored = playlist(localRestore, "local_device_paths")
        assertEquals("content://media/external/images/media/22", locallyStored.cover)
        assertEquals(localReferences, locallyStored.songs.map { it.cover })
        assertEquals(localReferences, locallyStored.songs.map { it.resolvedUrl })

        val cloudJson = localRestore.syncSnapshot().toCloudJson(includeLocalUris = false)
        val serializedCloud = cloudJson.toString()
        localReferences.forEach { reference -> assertFalse(serializedCloud.contains(reference)) }
        assertFalse(serializedCloud.contains("content://media/external/images/media/22"))
        val projected = cloudPlaylist(cloudJson, "local_device_paths")
        val projectedSongs = projected.getJSONArray("songs")
        assertEquals(3, projectedSongs.length())
        for (index in 0 until projectedSongs.length()) {
            val track = projectedSongs.getJSONObject(index)
            assertEquals("device-track-$index", track.getString("id"))
            assertFalse(track.has("resolvedUrl"))
            assertFalse(track.has("cover") && track.getString("cover").isNotEmpty())
        }
    }

    @Test
    fun localCoverAndPortableSyncCoverSurviveLocalJsonRestartWithoutCloudPrivateKey() {
        val localCover = "content://media/external/images/media/77"
        val portableCover = "https://cdn.example.test/portable-playlist.jpg"
        val original = LocalStore.UserBundle(
            playlists = mutableListOf(
                Playlist(
                    id = "local_cover_fallback",
                    name = "Custom Cover",
                    cover = localCover,
                    syncCover = portableCover,
                    local = true
                )
            )
        )

        val persistedLocalJson = original.syncSnapshot().toCloudJson(includeLocalUris = true)
        assertEquals(portableCover, cloudPlaylist(persistedLocalJson, "local_cover_fallback").getString("_swSyncCover"))
        val afterRestart = LocalStore.UserBundle.fromCloud(persistedLocalJson, allowLocalUris = true)
        assertEquals(localCover, playlist(afterRestart, "local_cover_fallback").cover)
        assertEquals(portableCover, playlist(afterRestart, "local_cover_fallback").syncCover)

        val cloudProjection = afterRestart.syncSnapshot().toCloudJson(includeLocalUris = false)
        val projectedPlaylist = cloudPlaylist(cloudProjection, "local_cover_fallback")
        assertEquals(portableCover, projectedPlaylist.getString("cv"))
        assertFalse(projectedPlaylist.has("_swSyncCover"))
        assertFalse(cloudProjection.toString().contains("_swSyncCover"))
    }

    @Test
    fun deviceLocalSyncCoverIsFilteredOnPersistenceAndIgnoredFromCloud() {
        val devicePath = "file:///storage/emulated/0/Pictures/private-cover.jpg"
        val bundle = LocalStore.UserBundle(
            playlists = mutableListOf(
                Playlist(
                    id = "local_invalid_sync_cover",
                    name = "Invalid Fallback",
                    cover = "content://media/external/images/media/99",
                    syncCover = devicePath,
                    local = true
                )
            )
        )

        val localJson = bundle.syncSnapshot().toCloudJson(includeLocalUris = true)
        val localPlaylist = cloudPlaylist(localJson, "local_invalid_sync_cover")
        assertFalse(localPlaylist.has("_swSyncCover"))
        assertFalse(localJson.toString().contains(devicePath))
        assertEquals("", playlist(LocalStore.UserBundle.fromCloud(localJson, allowLocalUris = true), "local_invalid_sync_cover").syncCover)

        // Even a malformed/legacy payload cannot use the private fallback key to inject a device URI.
        localPlaylist.put("_swSyncCover", devicePath)
        val cloudRestore = LocalStore.UserBundle.fromCloud(localJson, allowLocalUris = false)
        assertEquals("", playlist(cloudRestore, "local_invalid_sync_cover").syncCover)
    }

    @Test
    fun localPlaylistMergeKeepsKnownDurationForUnknownWinnerButAcceptsKnownWinner() {
        fun bundle(song: Song) = LocalStore.UserBundle(
            playlists = mutableListOf(
                Playlist(id = "local_revision", name = "Revision", local = true, songs = mutableListOf(song))
            )
        )
        val knownCloudSong = Song(
            id = "same-song",
            name = "Known Version",
            singer = "Artist",
            source = "wy",
            duration = 240_000L,
            syncAddedAt = 10L
        )
        val unknownNewerLocalSong = knownCloudSong.copy(
            name = "Newer Unknown Version",
            duration = 0L,
            syncAddedAt = 20L
        )
        val afterUnknownWinner = CloudUserMerge.merge(
            local = bundle(unknownNewerLocalSong),
            cloud = bundle(knownCloudSong),
            nowMs = 100L
        )
        val unknownMergeSong = playlist(afterUnknownWinner, "local_revision").songs.single()
        assertEquals("Newer Unknown Version", unknownMergeSong.name)
        assertEquals(240_000L, unknownMergeSong.duration)
        val reverse = CloudUserMerge.merge(
            local = bundle(knownCloudSong), cloud = bundle(unknownNewerLocalSong), nowMs = 100L
        )
        assertEquals(240_000L, playlist(reverse, "local_revision").songs.single().duration)

        val knownNewerLocalSong = unknownNewerLocalSong.copy(name = "Known Newer Version", duration = 305_000L)
        val afterKnownWinner = CloudUserMerge.merge(
            local = bundle(knownNewerLocalSong),
            cloud = bundle(knownCloudSong),
            nowMs = 100L
        )
        val knownMergeSong = playlist(afterKnownWinner, "local_revision").songs.single()
        assertEquals("Known Newer Version", knownMergeSong.name)
        assertEquals(305_000L, knownMergeSong.duration)
    }

    @Test
    fun fixedLocalLibrarySongsAreNotUploadedButLocalCustomSongMetadataIs() {
        val deviceSong = Song(
            id = "device-only",
            name = "On-device Song",
            singer = "Artist",
            source = "local",
            cover = "file:///storage/emulated/0/Music/cover.jpg",
            duration = 91_000L,
            resolvedUrl = "file:///storage/emulated/0/Music/audio.mp3"
        )
        val customSong = Song(
            id = "custom-playlist-track",
            name = "Metadata Travels",
            singer = "Artist",
            source = "local",
            cover = "https://cdn.example.test/song.jpg",
            duration = 182_000L,
            resolvedUrl = "content://media/external/audio/track/88"
        )
        val original = LocalStore.UserBundle(
            playlists = mutableListOf(
                Playlist(id = "local", name = "Local Library", songs = mutableListOf(deviceSong), local = true),
                Playlist(id = "local_custom", name = "Custom Local", songs = mutableListOf(customSong), local = true)
            )
        )
        val localJson = original.syncSnapshot().toCloudJson(includeLocalUris = true)
        assertEquals(1, cloudPlaylist(localJson, "local_custom").getJSONArray("songs").length())
        assertTrue(localJson.toString().contains("content://media/external/audio/track/88"))

        val cloudJson = original.syncSnapshot().toCloudJson(includeLocalUris = false)
        val fixedLibraryCloud = cloudPlaylist(cloudJson, "local")
        assertEquals(0, fixedLibraryCloud.getJSONArray("songs").length())
        val customCloudSong = cloudPlaylist(cloudJson, "local_custom").getJSONArray("songs").getJSONObject(0)
        assertEquals("custom-playlist-track", customCloudSong.getString("id"))
        assertEquals("Metadata Travels", customCloudSong.getString("name"))
        assertEquals(182_000L, customCloudSong.getLong("duration"))
        assertFalse(customCloudSong.has("resolvedUrl"))
        assertFalse(cloudJson.toString().contains("content://media/external/audio/track/88"))
        assertFalse(cloudJson.toString().contains("file:///storage/emulated/0/Music/audio.mp3"))
    }

    @Test
    fun oversizedInlineCloudCoverFailsWithoutChangingLocalPlaylist() {
        val oversizedCover = "data:image/png;base64," + "A".repeat(MAX_CLOUD_INLINE_IMAGE_CHARS + 1)
        val bundle = LocalStore.UserBundle(
            playlists = mutableListOf(
                Playlist(
                    id = "local_oversized_cover",
                    name = "Oversized Cover",
                    cover = oversizedCover,
                    local = true
                )
            )
        ).also { it.ensureDefaults() }
        val before = bundle.playlists.map { it.copy(songs = it.songs.toMutableList()) }

        assertNotNull(
            assertThrows(CloudInlineImageTooLargeException::class.java) {
                bundle.syncSnapshot().toCloudJson(includeLocalUris = false)
            }
        )
        assertEquals(before, bundle.playlists)
        assertEquals(oversizedCover, playlist(bundle, "local_oversized_cover").cover)
    }
}
