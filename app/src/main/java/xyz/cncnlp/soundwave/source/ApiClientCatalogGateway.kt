package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.ApiClient
import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song

/** Production bridge from the typed MusicSource port to the existing ApiClient implementation. */
internal object ApiClientCatalogGateway : SourceCatalogApi {
    override fun search(keyword: String, source: String, type: String): List<Song> =
        ApiClient.search(keyword, source, type)

    override suspend fun searchCancellable(keyword: String, source: String, type: String): List<Song> =
        ApiClient.searchCancellable(keyword, source, type)

    override suspend fun discover(source: String, limit: Int): List<DiscoverCard> =
        ApiClient.discover(source, limit)

    override suspend fun discoverOutcome(source: String, limit: Int): FeedListOutcome<List<DiscoverCard>> =
        ApiClient.discoverOutcome(source, limit)

    override suspend fun discoverServerOutcome(source: String, limit: Int): FeedListOutcome<List<DiscoverCard>> =
        ApiClient.discoverServerOutcome(source, limit)

    override suspend fun albumDetail(id: String, source: String): Playlist? =
        ApiClient.albumDetail(id, source)

    override suspend fun singerSongs(artistId: String, singerName: String, source: String): Playlist? =
        ApiClient.singerSongs(artistId, singerName, source)

    override suspend fun hot(source: String): List<Song> = ApiClient.hot(source)

    override suspend fun hotServer(source: String): List<Song> = ApiClient.hotServer(source)

    override suspend fun hotKeywords(source: String, limit: Int): List<String> =
        ApiClient.hotKeywords(source, limit)

    override suspend fun hotKeywordsOutcome(source: String, limit: Int): FeedListOutcome<List<String>> =
        ApiClient.hotKeywordsOutcome(source, limit)

    override suspend fun hotKeywordsServerOutcome(source: String, limit: Int): FeedListOutcome<List<String>> =
        ApiClient.hotKeywordsServerOutcome(source, limit)

    override suspend fun rank(source: String): List<RankBoard> = ApiClient.rank(source)

    override suspend fun rankDetail(id: String, source: String): List<Song> =
        ApiClient.rankDetail(id, source)

    override fun lyric(song: Song): String = ApiClient.lyric(song)

    override suspend fun searchPlaylistsCancellable(keyword: String, source: String, limit: Int): List<Playlist> =
        ApiClient.searchPlaylistsCancellable(keyword, source, limit)

    override suspend fun searchPlaylistsCategoryOutcomeCancellable(
        keyword: String,
        source: String,
        limit: Int
    ): PlaylistSearchOutcome = ApiClient.searchPlaylistsCategoryOutcomeCancellable(keyword, source, limit)

    override suspend fun recommendPlaylists(source: String, limit: Int): List<Playlist> =
        ApiClient.recommendPlaylists(source, limit)

    override suspend fun recommendPlaylistsOutcome(
        source: String,
        limit: Int
    ): FeedListOutcome<List<Playlist>> = ApiClient.recommendPlaylistsOutcome(source, limit)

    override suspend fun playlistDetail(id: String, source: String): List<Song> =
        ApiClient.playlistDetail(id, source)

    override suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist? =
        ApiClient.playlistDetailSnapshot(id, source, seed)

    override suspend fun plCatGroups(source: String): CatGroups? = ApiClient.plCatGroups(source)
}
