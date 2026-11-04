package xyz.cncnlp.soundwave

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistEntryBehaviorTest {
    private fun reset() {
        PlayerService.reset()
        AlbumFavoriteMarks.saved = emptySet()
        HomeFeedCache.cache = DetailCacheSnapshot(emptyList(), 0L)
        Toast.messages.clear()
    }
    private fun song(id: String) = Song(id, "Song $id", "Artist $id", "wy")

    @Test fun persistedAlbumMarkUsesAlbumEndpointAndRetainsOriginalCollectionIdentity() = runBlocking {
        reset()
        val c = HeartController(this)
        val entry = PlaylistEntry(this, c)
        val album = Playlist("fav_wy_42", "Album", source = "wy", remoteId = "42", favorited = true)
        AlbumFavoriteMarks.saved = setOf(album.id)
        val requests = mutableListOf<Triple<String, String, String>>()
        FixtureCatalog.fetch = { src, id, kind ->
            requests += Triple(src, id, kind)
            Playlist("provider-result-id", "Provider name", source = src, songs = mutableListOf(song("album-song")))
        }
        entry.togglePlaylistHeartMode(album)
        entry.playlistHeartLoadJob!!.join(); yield()
        assertEquals(listOf(Triple("wy", "42", "album")), requests)
        assertEquals(heartPlaylistKey(album), c.activeHeartPlaylistKeyState.value)
        assertEquals(listOf("album-song"), c.played.map { it.id })
    }

    @Test fun remotePlaylistUsesSongListEndpointAndRetainsCollectionIdentity() = runBlocking {
        reset()
        val c = HeartController(this)
        val entry = PlaylistEntry(this, c)
        val playlist = Playlist("saved-playlist", "Saved", source = "wy", remoteId = "remote-id")
        val requests = mutableListOf<Triple<String, String, String>>()
        FixtureCatalog.fetch = { src, id, kind ->
            requests += Triple(src, id, kind)
            Playlist("provider-result", "Provider", songs = mutableListOf(song("playlist-song")))
        }
        entry.togglePlaylistHeartMode(playlist)
        entry.playlistHeartLoadJob!!.join(); yield()
        assertEquals(listOf(Triple("wy", "remote-id", "playlist")), requests)
        assertEquals(listOf("playlist-song"), c.played.map { it.id })
        assertEquals(heartPlaylistKey(playlist), c.activeHeartPlaylistKeyState.value)
    }

    @Test fun emptyOrFailedAlbumAndPlaylistRefreshRetainsStaleCachedSongs() = runBlocking {
        for (kind in listOf("album", "playlist")) {
            for (outcome in listOf("empty", "null", "failure")) {
                reset()
                val c = HeartController(this)
                val entry = PlaylistEntry(this, c)
                val playlist = Playlist("saved", "Saved", source = "wy", detailContentKind = kind)
                HomeFeedCache.cache = DetailCacheSnapshot(listOf(song("stale")), 1L)
                var calls = 0
                FixtureCatalog.fetch = { _, _, requestedKind ->
                    calls++
                    assertEquals(kind, requestedKind)
                    when (outcome) {
                        "failure" -> throw IllegalStateException("offline")
                        "null" -> null
                        else -> Playlist("empty", "Empty")
                    }
                }
                entry.togglePlaylistHeartMode(playlist)
                entry.playlistHeartLoadJob!!.join(); yield()
                assertEquals(1, calls)
                assertEquals(listOf("stale"), c.played.map { it.id })
                assertEquals(heartPlaylistKey(playlist), c.activeHeartPlaylistKeyState.value)
            }
        }
    }

    @Test fun failedDetailDoesNotCrashOrReplacePlaylistWithGlobalFavorites() = runBlocking {
        reset()
        val c = HeartController(this)
        c.favorites = listOf(song("global"))
        val entry = PlaylistEntry(this, c)
        FixtureCatalog.fetch = { _, _, _ -> throw IllegalStateException("offline") }
        entry.togglePlaylistHeartMode(Playlist("remote", "Remote", source = "wy"))
        entry.playlistHeartLoadJob!!.join(); yield()
        assertTrue(c.played.isEmpty())
        assertEquals(PlayMode.SEQUENCE, PlayerService.snapshot().mode)
        assertTrue(Toast.messages.any { it.contains("当前歌单") })
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun cachedSongsUseRealSnapshotWithoutReplacingPlaylistIdentity() = runBlocking {
        reset()
        val c = HeartController(this)
        val entry = PlaylistEntry(this, c)
        val playlist = Playlist("saved", "Saved", source = "wy", remoteId = "catalog-id")
        HomeFeedCache.cache = DetailCacheSnapshot(listOf(song("cached")), System.currentTimeMillis())
        FixtureCatalog.fetch = { _, _, _ -> fail("fresh cache should skip network"); null }
        entry.togglePlaylistHeartMode(playlist)
        entry.playlistHeartLoadJob!!.join(); yield()
        assertEquals(listOf("cached"), c.played.map { it.id })
        assertEquals(heartPlaylistKey(playlist), c.activeHeartPlaylistKeyState.value)
    }

    @Test fun laterPlaybackIntentPreventsSlowDetailFromStartingAnObsoleteSession() = runBlocking {
        reset()
        val c = HeartController(this)
        val entry = PlaylistEntry(this, c)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        FixtureCatalog.fetch = { _, _, _ ->
            started.complete(Unit)
            release.await()
            Playlist("result", "Result", songs = mutableListOf(song("late")))
        }
        entry.togglePlaylistHeartMode(Playlist("remote", "Remote", source = "wy"))
        started.await()
        PlayerService.beginPlaybackIntent()
        release.complete(Unit)
        entry.playlistHeartLoadJob!!.join(); yield()
        assertTrue(c.played.isEmpty())
        assertNull(c.activeHeartPlaylistKeyState.value)
    }

    @Test fun stoppingHeartModeInvalidatesPendingPlaylistLoad() = runBlocking {
        reset()
        val c = HeartController(this)
        val entry = PlaylistEntry(this, c)
        PlayerService.setMode(PlayMode.HEART)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        FixtureCatalog.fetch = { _, _, _ ->
            started.complete(Unit)
            release.await()
            Playlist("late", "Late", songs = mutableListOf(song("late")))
        }
        entry.togglePlaylistHeartMode(Playlist("remote", "Remote", source = "wy"))
        started.await()
        c.stopHeartMode()
        release.complete(Unit)
        entry.playlistHeartLoadJob!!.join(); yield()
        assertEquals(PlayMode.SEQUENCE, PlayerService.snapshot().mode)
        assertTrue(c.played.isEmpty())
        assertNull(c.activeHeartPlaylistKeyState.value)
    }

    @Test fun switchingToLoadedPlaylistCancelsSlowOldDetail() = runBlocking {
        reset()
        val c = HeartController(this)
        val entry = PlaylistEntry(this, c)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        FixtureCatalog.fetch = { _, _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            Playlist("late", "Late", songs = mutableListOf(song("late")))
        }
        entry.togglePlaylistHeartMode(Playlist("remote-a", "A", source = "wy"))
        val oldJob = entry.playlistHeartLoadJob!!
        started.await()
        val b = Playlist("loaded-b", "B", source = "wy", songs = mutableListOf(song("b")))
        entry.togglePlaylistHeartMode(b)
        release.complete(Unit)
        oldJob.join(); yield()
        assertEquals(listOf("b"), c.played.map { it.id })
        assertEquals(heartPlaylistKey(b), c.activeHeartPlaylistKeyState.value)
    }
}
