package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song

/**
 * Typed catalog/data boundary used by MusicSource adapters.
 *
 * It deliberately excludes SoundWave account credentials and playback resolution. Some direct
 * providers are injected at the source/direct boundary; legacy direct-first calls still remain behind
 * the ApiClient gateway. This port carries domain values and typed valid-empty/failure outcomes.
 */
internal interface SourceCatalogApi {
    fun search(keyword: String, source: String, type: String): List<Song>
    suspend fun searchCancellable(keyword: String, source: String, type: String): List<Song>

    suspend fun discover(source: String, limit: Int): List<DiscoverCard>
    suspend fun discoverOutcome(source: String, limit: Int): FeedListOutcome<List<DiscoverCard>>
    suspend fun discoverServerOutcome(source: String, limit: Int): FeedListOutcome<List<DiscoverCard>>
    suspend fun albumDetail(id: String, source: String): Playlist?
    suspend fun singerSongs(artistId: String, singerName: String, source: String): Playlist?
    suspend fun hot(source: String): List<Song>
    /** Server-only fallback; platform direct providers are owned by their source adapters. */
    suspend fun hotServer(source: String): List<Song>
    suspend fun hotKeywords(source: String, limit: Int): List<String>
    suspend fun hotKeywordsOutcome(source: String, limit: Int): FeedListOutcome<List<String>>
    suspend fun hotKeywordsServerOutcome(source: String, limit: Int): FeedListOutcome<List<String>>
    suspend fun rank(source: String): List<RankBoard>
    suspend fun rankDetail(id: String, source: String): List<Song>
    fun lyric(song: Song): String

    suspend fun searchPlaylistsCancellable(keyword: String, source: String, limit: Int): List<Playlist>
    suspend fun searchPlaylistsCategoryOutcomeCancellable(
        keyword: String,
        source: String,
        limit: Int
    ): PlaylistSearchOutcome
    suspend fun recommendPlaylists(source: String, limit: Int): List<Playlist>
    suspend fun recommendPlaylistsOutcome(source: String, limit: Int): FeedListOutcome<List<Playlist>>
    suspend fun playlistDetail(id: String, source: String): List<Song>
    suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist?
    suspend fun plCatGroups(source: String): CatGroups?
}
