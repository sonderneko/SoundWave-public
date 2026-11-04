package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.ApiClient
import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Platforms
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.source.direct.fetchLyricDirect
import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryGroupsDirect
import java.util.concurrent.CancellationException

/**
 * Additive prototype adapter exposing a single fixed platform (WY/KG/KW/TX/MG)
 * through the unified [MusicSource] interface by forwarding to [ApiClient]
 * with the platform as `source`.
 *
 * Display name comes from the canonical helper [Platforms.label].
 *
 * 不在本适配器范围内：登录态/VIP 与真实取链（UrlResolver）；
 * 只读能力包括搜索、专辑、榜单、歌词、首页热门歌曲、发现卡片与首页热词。
 */
internal class PlatformSourceAdapter(
    private val api: ApiClient,
    private val platform: String,
    private val directSearch: (suspend (String, Int, Int) -> List<Song>)? = null,
    private val directPlaylistSearch: (suspend (String, Int, Int) -> List<Playlist>)? = null,
    private val directPlaylistSearchOutcome: (suspend (String, Int, Int) -> PlaylistSearchOutcome)? = null,
) : MusicSource {

    override val id: String = platform

    override val name: String = Platforms.label(platform)

    // 批19 #5：能力声明统一取自注册表常量矩阵（单一事实源，防两处声明漂移）。
    // 矩阵较旧「四能力全 true」声明更细（album/resolve 按直连实现声明）；capabilities 当前
    // 无读取方，行为零回归。
    override val capabilities: SourceCapabilities = MusicSourceRegistry.capabilitiesOf(platform)

    // 官方取链走 UrlResolver 编排链，单独源头无隔离路径：保持默认 NONE。

    // Local-first 批1（additive）：directSearch 存在时客户端直连、page/limit 原样透传；
    // 批23c：直连失败/空结果回落原服务端路径（ApiClient.search → /music/search，服务端路由已
    // 恢复），与 searchPlaylists 同语义——真机直连被平台风控/超时时搜索页仍有兜底，不再直接"无歌曲"。
    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> {
        directSearch?.let { fn ->
            val direct = runCatching { fn(keyword, page, limit) }.getOrNull()
            // 直连解析偶发丢封面（kg/kw 部分条目无图字段）；封面缺失过半时回落服务端
            // （服务端聚合路径带封面兜底），避免搜索页满屏默认蓝色风景图。
            if (direct != null && direct.isNotEmpty()) {
                val withCover = direct.count { it.cover.isNotBlank() }
                if (withCover * 2 >= direct.size) return direct
            }
        }
        return api.search(keyword, source = platform, type = "song")
    }

    // Keep legacy server-only callers separate from search(), whose direct-first path may return a different candidate set.
    override suspend fun searchServer(keyword: String, type: String): List<Song> =
        api.search(keyword, source = platform, type = type)

    // Typed search stays on ApiClient's existing server route and preserves its type fallback and filtering.
    override suspend fun searchTyped(keyword: String, type: String): List<Song> =
        api.search(keyword, source = platform, type = type)

    // Local-first 批13（additive）：歌单搜索客户端直连优先（注入的 directPlaylistSearch 内部
    // 按平台自派发；五平台服务端均匿名可搜、零 cookie）；直连抛错/空结果一律回落原服务端路径
    // （ApiClient.searchPlaylists → /music/playlist?action=search），行为零回归。
    override suspend fun searchPlaylists(keyword: String, source: String, limit: Int): List<Playlist> {
        return searchPlaylistsPage(keyword, source, page = 1, limit = limit)
    }

    override suspend fun searchPlaylistsPage(keyword: String, source: String, page: Int, limit: Int): List<Playlist> {
        val pageNumber = page.coerceAtLeast(1)
        val pageSize = limit.coerceIn(1, 200)
        directPlaylistSearch?.let { fn ->
            runCatching { fn(keyword, pageNumber, pageSize) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (pageNumber == 1) return api.searchPlaylists(keyword, source, pageSize)

        // The VPS search route currently ignores page/offset; request a bounded prefix and slice locally.
        // Native direct sources above provide deeper paging; this fallback only exposes pages within 200 rows.
        val start = (pageNumber - 1L) * pageSize
        if (start >= 200L) return emptyList()
        val prefixLimit = (start + pageSize).coerceAtMost(200L).toInt()
        return api.searchPlaylists(keyword, source, prefixLimit).drop(start.toInt()).take(pageSize)
    }

    /** Category-only typed path; regular search still uses the original List-returning methods above. */
    override suspend fun searchPlaylistsPageOutcome(
        keyword: String,
        source: String,
        page: Int,
        limit: Int
    ): PlaylistSearchOutcome {
        val pageNumber = page.coerceAtLeast(1)
        val pageSize = limit.coerceIn(1, 200)
        var directEmpty: PlaylistSearchOutcome.Success? = null
        val direct = when {
            directPlaylistSearchOutcome != null -> try {
                directPlaylistSearchOutcome.invoke(keyword, pageNumber, pageSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlaylistSearchOutcome.Failure
            }
            directPlaylistSearch != null -> try {
                PlaylistSearchOutcome.Success(directPlaylistSearch.invoke(keyword, pageNumber, pageSize))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlaylistSearchOutcome.Failure
            }
            else -> null
        }
        when (direct) {
            is PlaylistSearchOutcome.Success -> {
                if (direct.playlists.isNotEmpty()) return direct
                directEmpty = direct
            }
            PlaylistSearchOutcome.Failure, null -> Unit
        }

        val serverResult = if (pageNumber == 1) {
            api.searchPlaylistsCategoryOutcome(keyword, source, pageSize)
        } else {
            val start = (pageNumber - 1L) * pageSize
            if (start >= 200L) return PlaylistSearchOutcome.Success(emptyList())
            val prefixLimit = (start + pageSize).coerceAtMost(200L).toInt()
            when (val prefix = api.searchPlaylistsCategoryOutcome(keyword, source, prefixLimit)) {
                is PlaylistSearchOutcome.Success -> PlaylistSearchOutcome.Success(
                    prefix.playlists.drop(start.toInt()).take(pageSize)
                )
                PlaylistSearchOutcome.Failure -> PlaylistSearchOutcome.Failure
            }
        }
        return chooseCategoryPlaylistSearchResult(directEmpty, serverResult)
    }

    override suspend fun playlistCategoryGroups(): ApiClient.CatGroups? =
        if (platform == "wy") {
            fetchWyPlaylistCategoryGroupsDirect().takeIf { it.isNotEmpty() }?.let { ApiClient.CatGroups(it) }
        } else {
            api.plCatGroups(platform)
        }

    // page/limit not forwarded: ApiClient.albumDetail returns the full track list.
    override suspend fun albumDetail(id: String, page: Int, limit: Int): Playlist? {
        return api.albumDetail(id, source = platform)
    }

    override suspend fun playlistDetailSnapshot(id: String, fallback: Playlist): Playlist? {
        return api.playlistDetailSnapshot(id, source = platform, seed = fallback)
    }

    override suspend fun artistDetail(artistId: String, singerName: String): Playlist? {
        return api.singerSongs(artistId, singerName, source = platform)
    }

    override suspend fun rankBoards(): List<RankBoard> {
        return api.rank(source = platform)
    }

    override suspend fun rank(id: String): List<Song> {
        return api.rankDetail(id, source = platform)
    }

    override suspend fun recommendPlaylists(limit: Int): List<Playlist> =
        api.recommendPlaylists(source = platform, limit = limit)

    override suspend fun hotSongs(): List<Song> = api.hot(source = platform)

    override suspend fun discoverCards(limit: Int): List<DiscoverCard> =
        api.discover(source = platform, limit = limit)

    override suspend fun discoverCardsOutcome(limit: Int): FeedListOutcome<List<DiscoverCard>> =
        api.discoverOutcome(source = platform, limit = limit)

    override suspend fun hotKeywords(limit: Int): List<String> =
        api.hotKeywords(source = platform, limit = limit)

    override suspend fun hotKeywordsOutcome(limit: Int): FeedListOutcome<List<String>> =
        api.hotKeywordsOutcome(source = platform, limit = limit)

    override suspend fun lyric(song: Song): String? {
        return fetchPlatformLyric(
            song = song,
            direct = { request -> fetchLyricDirect(request.source, request) },
            server = api::lyric,
        )
    }
}
