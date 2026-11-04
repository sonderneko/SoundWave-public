package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.ApiClient
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.ResolveResult
import xyz.cncnlp.soundwave.ResolveTicket
import xyz.cncnlp.soundwave.Song
import java.util.concurrent.CancellationException

/** Category browsing needs to distinguish an authoritative empty page from a failed request. */
internal sealed interface PlaylistSearchOutcome {
    data class Success(val playlists: List<Playlist>) : PlaylistSearchOutcome
    object Failure : PlaylistSearchOutcome
}

/** Prefer a valid direct empty page over a failed fallback; never turn two failures into an empty success. */
internal fun chooseCategoryPlaylistSearchResult(
    direct: PlaylistSearchOutcome?,
    server: PlaylistSearchOutcome
): PlaylistSearchOutcome = when (server) {
    is PlaylistSearchOutcome.Success -> server
    PlaylistSearchOutcome.Failure -> (direct as? PlaylistSearchOutcome.Success) ?: PlaylistSearchOutcome.Failure
}

/**
 * 平台能力三态（批21，GPT v4 P1，additive）：
 * - [CapabilityLevel.DIRECT]：该平台该能力有客户端直连实现（无 cookie / 匿名可用）。
 * - [CapabilityLevel.SERVER_FALLBACK]：无客户端直连实现，但服务端路径可用（直连不可用时回落）。
 * - [CapabilityLevel.UNSUPPORTED]：客户端与服务端均无可用路径。
 */
internal enum class CapabilityLevel { DIRECT, SERVER_FALLBACK, UNSUPPORTED }

/**
 * 音源能力声明：由各 MusicSource 实现方填充，供上层按能力分发。
 *
 * 批19 #5（additive）：补 album/artist/resolve 能力位与两个 wy 独有产品兼容位；
 * 批21（GPT v4 P1）：7 个平台能力位（search/playlist/rank/lyrics/album/artist/resolve）
 * 升级为 [CapabilityLevel] 三态，区分客户端直连与服务端回落；四个产品兼容 flag
 * （legacySinglePlatformFeed/previewClipRisk/isScript/isCloud）保持 Boolean 不动。
 * 「是否有该能力」判定 = `level != CapabilityLevel.UNSUPPORTED`，「是否直连」判定 =
 * `level == CapabilityLevel.DIRECT`。固定平台的常量矩阵见 [MusicSourceRegistry.capabilitiesOf]，
 * UI 判定应查能力位而非散落字符串比较。
 */
internal data class SourceCapabilities(
    val platform: String,
    val supportsSearch: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    val supportsPlaylist: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    val supportsRank: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    val supportsLyrics: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    val supportsAlbum: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    val supportsArtist: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    val supportsResolve: CapabilityLevel = CapabilityLevel.UNSUPPORTED,
    /** wy 独有产品兼容位：旧版单平台 feed 字段（hot/ranks/plReco 冷启动兜底）仅由 wy 数据链填充。 */
    val legacySinglePlatformFeed: Boolean = false,
    /** wy 独有播放风控位：wy 版权受限曲可能返回 ~30s 试听片段，播放层需预检废弃。 */
    val previewClipRisk: Boolean = false,
    val isScript: Boolean = false,
    val isCloud: Boolean = false,
)

/**
 * 统一音源接口：把 ApiClient / UrlResolver / ScriptRuntime 的现有能力
 * 收敛为一组标准挂起点，具体映射见同目录 DESIGN.md。
 */
internal interface MusicSource {
    val id: String
    val name: String
    val capabilities: SourceCapabilities

    /**
     * 取链能力：默认 NONE，即该源在 MusicSource 边界内暂无独立取链路径。
     * 取链编排仍归 UrlResolver，见 ResolveCapability 契约说明。
     */
    val resolveCapability: ResolveCapability
        get() = NoResolve

    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): List<Song>

    /** Server-only search for legacy callers that must not gain direct-result candidates. */
    suspend fun searchServer(keyword: String, type: String = "song"): List<Song> = emptyList()

    /** Search a specific result kind (for example artist or album); unsupported sources return no typed results. */
    suspend fun searchTyped(keyword: String, type: String): List<Song> = emptyList()

    /**
     * 歌单搜索：Local-first 批次补全（additive）。默认空实现，脚本源等
     * 未覆盖歌单能力的实现方自动继承空结果，不改变现有行为。
     */
    suspend fun searchPlaylists(keyword: String, source: String, limit: Int = 100): List<Playlist> = emptyList()

    /** Category browse pagination; implementations without a paged native route keep first-page behavior only. */
    suspend fun searchPlaylistsPage(keyword: String, source: String, page: Int, limit: Int = 100): List<Playlist> =
        if (page <= 1) searchPlaylists(keyword, source, limit) else emptyList()

    /** Category-only typed path; existing search callers keep their List-based contract unchanged. */
    suspend fun searchPlaylistsPageOutcome(
        keyword: String,
        source: String,
        page: Int,
        limit: Int = 100
    ): PlaylistSearchOutcome = try {
        PlaylistSearchOutcome.Success(searchPlaylistsPage(keyword, source, page, limit))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaylistSearchOutcome.Failure
    }

    /** Home-feed official recommendation route; implementations without it keep an empty result. */
    suspend fun recommendPlaylists(limit: Int = 50): List<Playlist> = emptyList()

    suspend fun albumDetail(id: String, page: Int = 1, limit: Int = 50): Playlist?

    /** Fetch a same-source playlist snapshot and its songs; unsupported sources return null. */
    suspend fun playlistDetailSnapshot(id: String, fallback: Playlist): Playlist? = null

    /** 获取歌手作品详情；未实现该能力的音源返回 null。 */
    suspend fun artistDetail(artistId: String, singerName: String): Playlist? = null

    /** 首页热门歌曲只读能力；未实现该能力的音源默认返回空列表。 */
    suspend fun hotSongs(): List<Song> = emptyList()

    /** 首页发现卡片只读能力；未实现该能力的音源默认返回空列表。 */
    suspend fun discoverCards(limit: Int): List<DiscoverCard> = emptyList()

    /** Typed home-only path; implementations may distinguish a valid empty response from failure. */
    suspend fun discoverCardsOutcome(limit: Int): FeedListOutcome<List<DiscoverCard>> =
        FeedListOutcome.Success(discoverCards(limit))

    /** 首页热词只读能力；未实现该能力的音源默认返回空列表。 */
    suspend fun hotKeywords(limit: Int): List<String> = emptyList()

    /** Typed home-only path; implementations may distinguish a valid empty response from failure. */
    suspend fun hotKeywordsOutcome(limit: Int): FeedListOutcome<List<String>> =
        FeedListOutcome.Success(hotKeywords(limit))

    /** 榜单目录元数据，与 [rank] 返回的单榜歌曲详情分离。 */
    suspend fun rankBoards(): List<RankBoard> = emptyList()

    /** 获取歌单分类树（taxonomy/category groups）；不支持该能力的音源返回 null。 */
    suspend fun playlistCategoryGroups(): ApiClient.CatGroups? = null

    /** 获取单个榜单的歌曲详情。 */
    suspend fun rank(id: String): List<Song>

    suspend fun lyric(song: Song): String?
}

/**
 * 缺省取链能力：无独立路径，resolve 恒为 null（未接线 ≠ 真实空结果）。
 */
private val NoResolve = object : ResolveCapability {
    override val resolveKind = ResolveKind.NONE

    override suspend fun resolve(song: Song, quality: String?, ticket: ResolveTicket?): ResolveResult? = null
}
