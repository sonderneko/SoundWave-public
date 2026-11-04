package xyz.cncnlp.soundwave

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HeartControllerBehaviorTest {
    private fun song(id: String, singer: String = "Favorite Artist", name: String = id) = Song(id, name, singer, "wy")

    @Test fun searchesOnlyFavoritesAndPersistsSemanticSeenAfterCommit() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        val favorite = song("liked")
        c.favorites = listOf(favorite)
        c.currentSong = song("current", "Queue Artist")
        PlayerService.setQueue(listOf(c.currentSong!!), 0)
        val queries = mutableListOf<String>()
        c.search = { query, source ->
            synchronized(queries) { queries += query }
            listOf(song("new-$source", name = "new"), song("noise", "Queue Artist"), song("impostor", "Other Artist", "liked"))
        }
        UrlResolver.action = { UrlResolver.Result() }
        c.playHeartSimilar()
        assertTrue(queries.isNotEmpty())
        // R 波一期：查询集合 = 种子歌手（亲和加权抽样）+ 当前歌歌手（会话锚）；
        // 候选资格仍只认种子歌手，故锚歌手的结果不会被选中（noise/impostor 仍被过滤）。
        assertEquals(setOf("Favorite Artist", "Queue Artist"), queries.toSet())
        assertEquals("new-wy", PlayerService.media.single().id)
        assertTrue(ContextPreferences.prefs.value.contains("new|favoriteartist"))
        assertNull(c.activeHeartJob)
    }

    @Test fun lateSearchCannotReclaimNewerTransaction() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        c.favorites = listOf(song("liked")); c.currentSong = song("old")
        c.search = { _, _ ->
            c.queueRevision++
            c.currentSong = song("newer", "New Artist")
            c.playbackTransactionGate.begin(c.currentSong!!.key(), c.queueRevision, c.controllerOwner)
            listOf(song("candidate"))
        }
        c.playHeartSimilar()
        assertEquals("newer", c.currentSong!!.id)
        assertTrue(PlayerService.media.isEmpty())
        assertTrue(c.heartCandidates.isEmpty())
    }

    @Test fun cancellationEscapesSearchAndDoesNotCancelCallerScopeOnStop() = runBlocking {
        PlayerService.reset(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        c.favorites = listOf(song("liked")); c.currentSong = song("old")
        c.search = { _, _ -> throw CancellationException("network cancelled") }
        try { c.playHeartSimilar(); fail("search cancellation swallowed") } catch (_: CancellationException) {}
        assertNull(c.activeHeartJob)
        PlayerService.setMode(PlayMode.HEART)
        c.stopHeartMode()
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun resolverFailureRestoresOldSongAndDoesNotMarkSeen() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        c.favorites = listOf(song("liked")); c.currentSong = song("old")
        c.search = { _, _ -> listOf(song("candidate")) }
        UrlResolver.action = { UrlResolver.Result(isSuccess = false) }
        c.playHeartSimilar()
        assertEquals("old", c.currentSong!!.id)
        assertFalse(c.resolving)
        assertTrue(PlayerService.media.isEmpty())
        assertEquals("", ContextPreferences.prefs.value)
    }

    @Test fun serviceGenerationChangeDuringResolveCannotCommit() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        c.favorites = listOf(song("liked")); c.currentSong = song("old")
        c.search = { _, _ -> listOf(song("candidate")) }
        UrlResolver.action = {
            PlayerService.state = PlayerService.state.copy(generation = 99)
            UrlResolver.Result()
        }
        c.playHeartSimilar()
        assertTrue(PlayerService.media.isEmpty())
        assertEquals("", ContextPreferences.prefs.value)
        assertNull(c.activeHeartJob)
    }

    @Test fun recommendationSessionQueueRemainsBounded() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        c.favorites = listOf(song("liked")); c.currentSong = song("old")
        var step = 0
        c.search = { _, _ -> listOf(song("candidate-$step")) }
        UrlResolver.action = { UrlResolver.Result() }
        repeat(30) { step = it; c.playHeartSimilar() }
        assertTrue(PlayerService.snapshot().queue.size <= 24)
        assertEquals(30, HeartRecommendationPolicy.decodeSeen(ContextPreferences.prefs.value).size)
    }

    @Test fun startAndToggleIgnorePassedQueueAndRejectEmptyLikes() = runBlocking {
        val c = HeartController(this)
        PlayerService.reset(); Toast.messages.clear()
        c.currentSong = song("old", "Queue Artist")
        c.startHeartMode(listOf(song("chart", "Chart Artist")))
        yield()
        assertEquals(PlayMode.SEQUENCE, PlayerService.snapshot().mode)
        assertTrue(c.played.isEmpty())
        assertTrue(Toast.messages.any { it.contains("喜欢") })
        c.favorites = listOf(song("liked"))
        c.toggleHeartMode(listOf(song("chart", "Chart Artist")))
        yield()
        assertEquals(listOf("liked"), c.played.map { it.id })
        c.toggleHeartMode(emptyList())
        assertEquals(PlayMode.SEQUENCE, PlayerService.snapshot().mode)
    }

    @Test fun playlistSessionUsesOwnSeedsWithoutLikesAndKeepsRealCandidateIdentity() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        c.favorites = listOf(song("global-like", "Other Artist"))
        val seed = song("source-song", "Playlist Artist")
        val playlist = Playlist("imported-1", "Imported", source = "tx", remoteId = "remote-1", songs = mutableListOf(seed))
        c.togglePlaylistHeartMode(playlist)
        yield()
        assertEquals(listOf(seed), c.played)
        assertEquals(heartPlaylistKey(playlist), c.activeHeartPlaylistKeyState.value)
        val queries = mutableListOf<Pair<String, String>>()
        val candidate = Song("real-mid", "New song", "Playlist Artist", "tx", albumId = "real-album", mediaMid = "real-media")
        c.search = { q, source ->
            synchronized(queries) { queries += q to source }
            listOf(candidate, song("noise", "Other Artist"))
        }
        UrlResolver.action = { UrlResolver.Result(platform = "tx") }
        c.playHeartSimilar()
        assertEquals(listOf("Playlist Artist"), queries.map { it.first }.distinct())
        assertEquals(setOf("tx", "wy"), queries.map { it.second }.toSet())
        assertSame(candidate, PlayerService.media.single())
        assertEquals("real-media", PlayerService.media.single().mediaMid)
        assertEquals("real-album", PlayerService.media.single().albumId)
        assertEquals("", ContextPreferences.prefs.value)
        assertTrue(ContextPreferences.prefs.values[HeartPlaylistContext.from(playlist, playlist.songs).seenPreferenceKey].orEmpty().contains("newsong|playlistartist"))
    }

    @Test fun switchingPlaylistResetsCandidatesAndUsesIndependentHistoryAndArtistQueries() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        val a = Playlist("a", "A", source = "wy", songs = mutableListOf(song("seed-a", "Artist A")))
        val b = Playlist("b", "B", source = "kg", songs = mutableListOf(song("seed-b", "Artist B").copy(source = "kg")))
        c.togglePlaylistHeartMode(a); yield()
        c.heartCandidates = listOf(song("cached-a", "Artist A"))
        c.togglePlaylistHeartMode(b); yield()
        assertEquals(PlayMode.HEART, PlayerService.snapshot().mode)
        assertEquals(heartPlaylistKey(b), c.activeHeartPlaylistKeyState.value)
        assertTrue(c.heartCandidates.isEmpty())
        val queries = mutableListOf<String>()
        c.search = { query, _ -> synchronized(queries) { queries += query }; listOf(song("new-b", "Artist B"), song("wrong-a", "Artist A")) }
        UrlResolver.action = { UrlResolver.Result() }
        c.playHeartSimilar()
        assertEquals(listOf("Artist B"), queries.distinct())
        assertEquals("new-b", PlayerService.media.single().id)
        val ka = HeartPlaylistContext.from(a, a.songs).seenPreferenceKey
        val kb = HeartPlaylistContext.from(b, b.songs).seenPreferenceKey
        assertFalse(ContextPreferences.prefs.values.containsKey(ka))
        assertTrue(ContextPreferences.prefs.values[kb].orEmpty().contains("newb|artistb"))
        c.togglePlaylistHeartMode(a); yield()
        assertEquals(heartPlaylistKey(a), c.activeHeartPlaylistKeyState.value)
        c.togglePlaylistHeartMode(a)
        assertEquals(PlayMode.SEQUENCE, PlayerService.snapshot().mode)
        assertNull(c.activeHeartPlaylistKeyState.value)
    }

    @Test fun emptyOrUnsupportedPlaylistCannotFallBackToGlobalLikes() = runBlocking {
        PlayerService.reset(); Toast.messages.clear()
        val c = HeartController(this)
        c.favorites = listOf(song("global-like"))
        c.togglePlaylistHeartMode(Playlist("empty", "Empty", source = "wy"))
        c.togglePlaylistHeartMode(Playlist("script", "Script", source = "script:test", songs = mutableListOf(song("s").copy(source = "script:test"))))
        yield()
        assertTrue(c.played.isEmpty())
        assertEquals(PlayMode.SEQUENCE, PlayerService.snapshot().mode)
        assertTrue(Toast.messages.any { it.contains("不支持") })
        assertNull(c.activeHeartPlaylistKeyState.value)
    }

    @Test fun rapidPlaylistChangesCannotLaunchTheSupersededSeedQueue() = runBlocking {
        PlayerService.reset()
        val c = HeartController(this)
        val a = Playlist("a", "A", source = "wy", songs = mutableListOf(song("a")))
        val b = Playlist("b", "B", source = "wy", songs = mutableListOf(song("b")))
        c.togglePlaylistHeartMode(a)
        c.togglePlaylistHeartMode(b)
        yield()
        assertEquals(listOf("b"), c.played.map { it.id })
        assertEquals(heartPlaylistKey(b), c.activeHeartPlaylistKeyState.value)
    }


    @Test fun latePlaylistSearchCannotCommitAfterSwitchingToAnotherPlaylist() = runBlocking {
        PlayerService.reset(); PlayerService.media.clear(); ContextPreferences.prefs.value = ""
        val c = HeartController(this)
        val a = Playlist("a", "A", source = "wy", songs = mutableListOf(song("a", "Artist A")))
        val b = Playlist("b", "B", source = "wy", songs = mutableListOf(song("b", "Artist B")))
        c.togglePlaylistHeartMode(a); yield()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        c.search = { _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            listOf(song("late-a", "Artist A"))
        }
        val oldRequest = launch { c.playHeartSimilar() }
        started.await()
        c.togglePlaylistHeartMode(b); yield()
        release.complete(Unit)
        oldRequest.join()
        assertEquals("b", c.currentSong!!.id)
        assertEquals(heartPlaylistKey(b), c.activeHeartPlaylistKeyState.value)
        assertTrue(PlayerService.media.isEmpty())
        assertTrue(c.heartCandidates.isEmpty())
        assertTrue(ContextPreferences.prefs.values.values.all { it.isEmpty() })
        assertTrue(currentCoroutineContext().isActive)
    }

}
