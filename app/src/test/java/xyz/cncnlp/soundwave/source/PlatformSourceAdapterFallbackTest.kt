package xyz.cncnlp.soundwave.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song

class PlatformSourceAdapterFallbackTest {
    private data class SearchCall(val keyword: String, val source: String, val type: String)
    private data class PlaylistCall(val keyword: String, val source: String, val limit: Int)
    private data class HotKeywordsCall(val source: String, val limit: Int)
    private data class DiscoverCall(val source: String, val limit: Int)

    private open class FakeCatalogApi : SourceCatalogApi {
        val searchCalls = mutableListOf<SearchCall>()
        val playlistCalls = mutableListOf<PlaylistCall>()
        var searchResult: List<Song> = emptyList()
        var playlistResult: List<Playlist> = emptyList()
        var categoryResult: PlaylistSearchOutcome = PlaylistSearchOutcome.Failure
        val hotServerCalls = mutableListOf<String>()
        var hotServerResult: List<Song> = emptyList()
        val hotKeywordsServerCalls = mutableListOf<HotKeywordsCall>()
        var hotKeywordsServerResult: FeedListOutcome<List<String>> = FeedListOutcome.Success(emptyList())
        val discoverServerCalls = mutableListOf<DiscoverCall>()
        var discoverServerResult: FeedListOutcome<List<DiscoverCard>> = FeedListOutcome.Success(emptyList())

        override fun search(keyword: String, source: String, type: String): List<Song> = searchResult

        override suspend fun searchCancellable(keyword: String, source: String, type: String): List<Song> {
            searchCalls += SearchCall(keyword, source, type)
            return searchResult
        }

        override suspend fun discover(source: String, limit: Int): List<DiscoverCard> = emptyList()
        override suspend fun discoverOutcome(source: String, limit: Int): FeedListOutcome<List<DiscoverCard>> =
            FeedListOutcome.Success(emptyList())
        override suspend fun discoverServerOutcome(source: String, limit: Int): FeedListOutcome<List<DiscoverCard>> {
            discoverServerCalls += DiscoverCall(source, limit)
            return discoverServerResult
        }
        override suspend fun albumDetail(id: String, source: String): Playlist? = null
        override suspend fun singerSongs(artistId: String, singerName: String, source: String): Playlist? = null
        override suspend fun hot(source: String): List<Song> = emptyList()
        override suspend fun hotServer(source: String): List<Song> {
            hotServerCalls += source
            return hotServerResult
        }
        override suspend fun hotKeywords(source: String, limit: Int): List<String> = emptyList()
        override suspend fun hotKeywordsOutcome(source: String, limit: Int): FeedListOutcome<List<String>> =
            FeedListOutcome.Success(emptyList())
        override suspend fun hotKeywordsServerOutcome(source: String, limit: Int): FeedListOutcome<List<String>> {
            hotKeywordsServerCalls += HotKeywordsCall(source, limit)
            return hotKeywordsServerResult
        }
        override suspend fun rank(source: String): List<RankBoard> = emptyList()
        override suspend fun rankDetail(id: String, source: String): List<Song> = emptyList()
        override fun lyric(song: Song): String = ""

        override suspend fun searchPlaylistsCancellable(keyword: String, source: String, limit: Int): List<Playlist> {
            playlistCalls += PlaylistCall(keyword, source, limit)
            return playlistResult
        }

        override suspend fun searchPlaylistsCategoryOutcomeCancellable(
            keyword: String,
            source: String,
            limit: Int
        ): PlaylistSearchOutcome {
            playlistCalls += PlaylistCall(keyword, source, limit)
            return categoryResult
        }

        override suspend fun recommendPlaylists(source: String, limit: Int): List<Playlist> = emptyList()
        override suspend fun recommendPlaylistsOutcome(source: String, limit: Int): FeedListOutcome<List<Playlist>> =
            FeedListOutcome.Success(emptyList())
        override suspend fun playlistDetail(id: String, source: String): List<Song> = emptyList()
        override suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist? = seed
        override suspend fun plCatGroups(source: String): CatGroups? = null
    }

    @Test
    fun directSearchFailureFallsBackThroughTypedPortWithPlatformIdentity() = runBlocking {
        val api = FakeCatalogApi()
        val expected = listOf(Song(id = "server-song", name = "本地假数据", singer = "", source = "kg", cover = "fake-cover"))
        api.searchResult = expected
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "kg",
            directSearch = { _, _, _ -> throw IllegalStateException("synthetic direct failure") }
        )

        assertSame(expected, adapter.search("关键词", page = 3, limit = 12))
        assertEquals(listOf(SearchCall("关键词", "kg", "song")), api.searchCalls)
    }

    @Test
    fun serverOnlyTypedLookupForwardsSingerKindWithoutUsingDirectSearch() = runBlocking {
        val api = FakeCatalogApi()
        val expected = listOf(Song(id = "artist-id", name = "艺人名", singer = "艺人名", source = "wy"))
        api.searchResult = expected
        var directCalled = false
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "wy",
            directSearch = { _, _, _ -> directCalled = true; emptyList() }
        )

        assertSame(expected, adapter.searchServer("艺人名", "singer"))
        assertFalse(directCalled)
        assertEquals(listOf(SearchCall("艺人名", "wy", "singer")), api.searchCalls)
    }

    @Test
    fun directHotSongsSkipServerFallbackWhenDirectRowsExist() = runBlocking {
        val api = FakeCatalogApi()
        val expected = listOf(Song(id = "direct-hot", name = "假直连数据", singer = "", source = "wy"))
        val adapter = PlatformSourceAdapter(api = api, platform = "wy", directHotSongs = { expected })

        assertSame(expected, adapter.hotSongs())
        assertTrue(api.hotServerCalls.isEmpty())
    }

    @Test
    fun emptyAndFailedDirectHotResultsFallBackToServerOnlyPort() = runBlocking {
        val directAttempts: List<suspend () -> List<Song>> = listOf(
            { emptyList() },
            { throw IllegalStateException("synthetic direct failure") },
        )
        directAttempts.forEachIndexed { index, direct ->
            val api = FakeCatalogApi()
            val serverRows = listOf(Song(id = "server-$index", name = "假服务端数据", singer = "", source = "wy"))
            api.hotServerResult = serverRows
            val adapter = PlatformSourceAdapter(api = api, platform = "wy", directHotSongs = direct)

            assertSame(serverRows, adapter.hotSongs())
            assertEquals(listOf("wy"), api.hotServerCalls)
        }
    }

    @Test
    fun directHotCancellationPropagatesWithoutStartingServerFallback() {
        val api = FakeCatalogApi()
        val cancelled = CancellationException("synthetic user cancellation")
        val adapter = PlatformSourceAdapter(api = api, platform = "wy", directHotSongs = { throw cancelled })

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { adapter.hotSongs() }
        }
        assertSame(cancelled, thrown)
        assertTrue(api.hotServerCalls.isEmpty())
    }

    @Test
    fun directHotKeywordsReturnTypedSuccessWithoutServerFallback() = runBlocking {
        val api = FakeCatalogApi()
        val expected = listOf("假直连热词", "另一个假热词")
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "kg",
            directHotKeywords = { limit -> assertEquals(3, limit); expected },
        )

        assertEquals(FeedListOutcome.Success(expected), adapter.hotKeywordsOutcome(3))
        assertEquals(expected, adapter.hotKeywords(3))
        assertTrue(api.hotKeywordsServerCalls.isEmpty())
    }

    @Test
    fun emptyAndFailedDirectHotKeywordsUseServerOnlyOutcomePort() = runBlocking {
        val directAttempts: List<suspend (Int) -> List<String>> = listOf(
            { emptyList() },
            { throw IllegalStateException("synthetic hot keyword direct failure") },
        )
        directAttempts.forEachIndexed { index, direct ->
            val api = FakeCatalogApi()
            val serverOutcome = FeedListOutcome.Success(listOf("假服务端热词$index"))
            api.hotKeywordsServerResult = serverOutcome
            val adapter = PlatformSourceAdapter(api = api, platform = "kg", directHotKeywords = direct)

            assertEquals(serverOutcome, adapter.hotKeywordsOutcome(7))
            assertEquals(listOf(HotKeywordsCall("kg", 7)), api.hotKeywordsServerCalls)
        }
    }

    @Test
    fun typedHotKeywordFailureIsPreservedAndLegacyListRemainsEmpty() = runBlocking {
        val api = FakeCatalogApi().apply { hotKeywordsServerResult = FeedListOutcome.Failure }
        val adapter = PlatformSourceAdapter(api = api, platform = "wy", directHotKeywords = { emptyList() })

        assertSame(FeedListOutcome.Failure, adapter.hotKeywordsOutcome(5))
        assertTrue(adapter.hotKeywords(5).isEmpty())
        assertEquals(
            listOf(HotKeywordsCall("wy", 5), HotKeywordsCall("wy", 5)),
            api.hotKeywordsServerCalls,
        )
    }

    @Test
    fun directHotKeywordCancellationPropagatesWithoutStartingServerFallback() {
        val api = FakeCatalogApi()
        val cancelled = CancellationException("synthetic user cancellation")
        val adapter = PlatformSourceAdapter(api = api, platform = "wy", directHotKeywords = { throw cancelled })

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { adapter.hotKeywordsOutcome(4) }
        }
        assertSame(cancelled, thrown)
        assertTrue(api.hotKeywordsServerCalls.isEmpty())
    }

    @Test
    fun directDiscoverCardsReturnTypedSuccessWithoutServerFallback() = runBlocking {
        val api = FakeCatalogApi()
        val expected = listOf(DiscoverCard(id = "direct", name = "假发现卡", source = "kg"))
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "kg",
            directDiscoverCards = { limit -> assertEquals(4, limit); expected },
        )

        assertEquals(FeedListOutcome.Success(expected), adapter.discoverCardsOutcome(4))
        assertEquals(expected, adapter.discoverCards(4))
        assertTrue(api.discoverServerCalls.isEmpty())
    }

    @Test
    fun emptyAndFailedDirectDiscoverCardsUseServerOnlyOutcomePort() = runBlocking {
        val directAttempts: List<suspend (Int) -> List<DiscoverCard>> = listOf(
            { emptyList() },
            { throw IllegalStateException("synthetic discover direct failure") },
        )
        directAttempts.forEachIndexed { index, direct ->
            val api = FakeCatalogApi()
            val serverOutcome = FeedListOutcome.Success(
                listOf(DiscoverCard(id = "server-$index", name = "假服务端发现卡", source = "mg"))
            )
            api.discoverServerResult = serverOutcome
            val adapter = PlatformSourceAdapter(api = api, platform = "mg", directDiscoverCards = direct)

            assertEquals(serverOutcome, adapter.discoverCardsOutcome(8))
            assertEquals(listOf(DiscoverCall("mg", 8)), api.discoverServerCalls)
        }
    }

    @Test
    fun typedDiscoverFailureIsPreservedAndLegacyListRemainsEmpty() = runBlocking {
        val api = FakeCatalogApi().apply { discoverServerResult = FeedListOutcome.Failure }
        val adapter = PlatformSourceAdapter(api = api, platform = "wy", directDiscoverCards = { emptyList() })

        assertSame(FeedListOutcome.Failure, adapter.discoverCardsOutcome(5))
        assertTrue(adapter.discoverCards(5).isEmpty())
        assertEquals(
            listOf(DiscoverCall("wy", 5), DiscoverCall("wy", 5)),
            api.discoverServerCalls,
        )
    }

    @Test
    fun directDiscoverCancellationPropagatesWithoutStartingServerFallback() {
        val api = FakeCatalogApi()
        val cancelled = CancellationException("synthetic user cancellation")
        val adapter = PlatformSourceAdapter(api = api, platform = "tx", directDiscoverCards = { throw cancelled })

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { adapter.discoverCardsOutcome(6) }
        }
        assertSame(cancelled, thrown)
        assertTrue(api.discoverServerCalls.isEmpty())
    }

    @Test
    fun directSearchCancellationPropagatesAndDoesNotStartFallback() {
        val api = FakeCatalogApi()
        val cancelled = CancellationException("synthetic user cancellation")
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "wy",
            directSearch = { _, _, _ -> throw cancelled }
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { adapter.search("关键词", page = 1, limit = 10) }
        }
        assertSame(cancelled, thrown)
        assertTrue(api.searchCalls.isEmpty())
    }

    @Test
    fun laterPlaylistPageUsesOnlyBoundedServerPrefixAndSlicesLocally() = runBlocking {
        val api = FakeCatalogApi()
        api.playlistResult = (1..4).map { Playlist(id = "p$it", name = "歌单$it", source = "kw") }
        val adapter = PlatformSourceAdapter(api = api, platform = "kw")

        val page = adapter.searchPlaylistsPage("关键词", source = "kw", page = 2, limit = 2)

        assertEquals(listOf("p3", "p4"), page.map { it.id })
        assertEquals(listOf(PlaylistCall("关键词", "kw", 4)), api.playlistCalls)
    }

    @Test
    fun validDirectEmptyIsPreservedWhenFallbackFailsInsteadOfBecomingFailure() = runBlocking {
        val api = FakeCatalogApi().apply { categoryResult = PlaylistSearchOutcome.Failure }
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "mg",
            directPlaylistSearchOutcome = { _, _, _ -> PlaylistSearchOutcome.Success(emptyList()) }
        )

        assertEquals(
            PlaylistSearchOutcome.Success(emptyList()),
            adapter.searchPlaylistsPageOutcome("关键词", source = "mg", page = 1, limit = 20)
        )
        assertEquals(listOf(PlaylistCall("关键词", "mg", 20)), api.playlistCalls)
    }

    @Test
    fun twoCategoryFailuresRemainFailureRatherThanSuccessfulEmpty() = runBlocking {
        val api = FakeCatalogApi()
        val adapter = PlatformSourceAdapter(
            api = api,
            platform = "tx",
            directPlaylistSearchOutcome = { _, _, _ -> PlaylistSearchOutcome.Failure }
        )

        assertSame(
            PlaylistSearchOutcome.Failure,
            adapter.searchPlaylistsPageOutcome("关键词", source = "tx", page = 1, limit = 20)
        )
    }
}
