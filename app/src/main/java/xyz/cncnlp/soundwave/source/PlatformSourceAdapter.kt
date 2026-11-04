package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Platforms
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult
import xyz.cncnlp.soundwave.source.direct.fetchLyricDirect
import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryDirect
import xyz.cncnlp.soundwave.source.direct.searchOfficialEntitiesDirect

import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Production adapter exposing one fixed platform (WY/KG/KW/TX/MG) through
 * [MusicSource], typed catalog calls and injected anonymous direct providers.
 *
 * Display name comes from the canonical helper [Platforms.label].
 *
 * 不在本适配器范围内：登录态/VIP 与真实取链（UrlResolver）；
 * 只读能力包括搜索、专辑、榜单、歌词、首页热门歌曲、发现卡片与首页热词。
 */
internal class PlatformSourceAdapter(
    private val api: SourceCatalogApi,
    private val platform: String,
    private val directSearch: (suspend (String, Int, Int) -> List<Song>)? = null,
    private val directPlaylistSearch: (suspend (String, Int, Int) -> List<Playlist>)? = null,
    private val directPlaylistSearchOutcome: (suspend (String, Int, Int) -> PlaylistSearchOutcome)? = null,
    private val directTypedSearchResult: (suspend (String, String) -> TxTypedSearchResult)? = null,
    private val directHotSongs: (suspend () -> List<Song>)? = null,
    private val directHotKeywords: (suspend (Int) -> List<String>)? = null,
    private val directDiscoverCards: (suspend (Int) -> List<DiscoverCard>)? = null,
) : MusicSource {

    override val id: String = platform

    override val name: String = Platforms.label(platform)

    // 批19 #5：能力声明统一取自注册表常量矩阵（单一事实源，防两处声明漂移）。
    // 矩阵较旧「四能力全 true」声明更细（album/resolve 按直连实现声明）；capabilities 当前
    // 无读取方，行为零回归。
    override val capabilities: SourceCapabilities = MusicSourceRegistry.capabilitiesOf(platform)

    // 官方取链走 UrlResolver 编排链，单独源头无隔离路径：保持默认 NONE。

    // Local-first 批1：directSearch 存在时客户端直连、page/limit 原样透传；
    // 批23c：直连失败/空结果经 typed catalog port 回落原 /music/search 路径；与
    // searchPlaylists 同语义，平台风控/超时时搜索页仍有服务端兜底，不再直接"无歌曲"。
    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> {
        directSearch?.let { fn ->
            val direct = try { fn(keyword, page, limit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            // 直连解析偶发丢封面（kg/kw 部分条目无图字段）；封面缺失过半时回落服务端
            // （服务端聚合路径带封面兜底），避免搜索页满屏默认蓝色风景图。
            if (direct != null && direct.isNotEmpty()) {
                val withCover = direct.count { it.cover.isNotBlank() }
                if (withCover * 2 >= direct.size) return direct
            }
        }
        currentCoroutineContext().ensureActive()
        return api.searchCancellable(keyword, source = platform, type = "song")
    }

    // Keep legacy server-only callers separate from search(), whose direct-first path may return a different candidate set.
    override suspend fun searchServer(keyword: String, type: String): List<Song> =
        api.searchCancellable(keyword, source = platform, type = type)

    // Official QQ typed status distinguishes successful empty from unavailable.
    // Song requests on other platforms use the existing direct-first adapter.
    override suspend fun searchTyped(keyword: String, type: String): List<Song> {
        currentCoroutineContext().ensureActive()
        directTypedSearchResult?.invoke(keyword, type)?.let {
            currentCoroutineContext().ensureActive()
            if (it.available) return it.songs
        }
        if (type.isBlank() || type == "song") return search(keyword, 1, 30)
        currentCoroutineContext().ensureActive()
        searchOfficialEntitiesDirect(platform, keyword, type).let {
            currentCoroutineContext().ensureActive()
            if (it.available) return it.songs
        }
        return api.searchCancellable(keyword, source = platform, type = type)
    }

    override suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =
        directTypedSearchResult?.invoke(keyword, type)
            ?: TxTypedSearchResult(searchTyped(keyword, type), available = true)

    // Local-first 批13：歌单搜索客户端直连优先（注入的 directPlaylistSearch 内部按平台自派发）；
    // 直连抛错/空结果一律经 typed catalog port 回落 /music/playlist?action=search。
    override suspend fun searchPlaylists(keyword: String, source: String, limit: Int): List<Playlist> {
        return searchPlaylistsPage(keyword, source, page = 1, limit = limit)
    }

    override suspend fun searchPlaylistsPage(keyword: String, source: String, page: Int, limit: Int): List<Playlist> {
        val pageNumber = page.coerceAtLeast(1)
        val pageSize = limit.coerceIn(1, 200)
        directPlaylistSearch?.let { fn ->
            val direct = try { fn(keyword, pageNumber, pageSize) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            direct?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        currentCoroutineContext().ensureActive()
        if (pageNumber == 1) return api.searchPlaylistsCancellable(keyword, source, pageSize)

        // The VPS search route currently ignores page/offset; request a bounded prefix and slice locally.
        // Native direct sources above provide deeper paging; this fallback only exposes pages within 200 rows.
        val start = (pageNumber - 1L) * pageSize
        if (start >= 200L) return emptyList()
        val prefixLimit = (start + pageSize).coerceAtMost(200L).toInt()
        return api.searchPlaylistsCancellable(keyword, source, prefixLimit).drop(start.toInt()).take(pageSize)
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

        currentCoroutineContext().ensureActive()
        val serverResult = if (pageNumber == 1) {
            api.searchPlaylistsCategoryOutcomeCancellable(keyword, source, pageSize)
        } else {
            val start = (pageNumber - 1L) * pageSize
            if (start >= 200L) return PlaylistSearchOutcome.Success(emptyList())
            val prefixLimit = (start + pageSize).coerceAtMost(200L).toInt()
            when (val prefix = api.searchPlaylistsCategoryOutcomeCancellable(keyword, source, prefixLimit)) {
                is PlaylistSearchOutcome.Success -> PlaylistSearchOutcome.Success(
                    prefix.playlists.drop(start.toInt()).take(pageSize)
                )
                PlaylistSearchOutcome.Failure -> PlaylistSearchOutcome.Failure
            }
        }
        return chooseCategoryPlaylistSearchResult(directEmpty, serverResult)
    }

    override suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> =
        api.searchPlaylistsCancellable(keyword, source = platform, limit = limit)

    // Keep NetEase's category-square-only route separate from generic keyword playlist search.
    override suspend fun playlistCategorySquare(category: String, limit: Int, page: Int): List<Playlist> =
        if (platform == "wy") fetchWyPlaylistCategoryDirect(category, limit, page) else emptyList()

    override suspend fun playlistCategoryGroups(): CatGroups? =
        api.plCatGroups(platform)

    // page/limit not forwarded: the catalog album route returns the full track list.
    override suspend fun albumDetail(id: String, page: Int, limit: Int): Playlist? {
        return api.albumDetail(id, source = platform)
    }

    override suspend fun playlistDetail(id: String): List<Song> =
        api.playlistDetail(id, source = platform)

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

    override suspend fun recommendPlaylistsOutcome(limit: Int): FeedListOutcome<List<Playlist>> =
        api.recommendPlaylistsOutcome(source = platform, limit = limit)

    override suspend fun hotSongs(): List<Song> {
        currentCoroutineContext().ensureActive()
        directHotSongs?.let { fetch ->
            val direct = try { fetch() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            direct?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        currentCoroutineContext().ensureActive()
        return api.hotServer(source = platform)
    }

    override suspend fun discoverCards(limit: Int): List<DiscoverCard> =
        discoverCardsOutcome(limit).legacyListValue()

    override suspend fun discoverCardsOutcome(limit: Int): FeedListOutcome<List<DiscoverCard>> {
        currentCoroutineContext().ensureActive()
        directDiscoverCards?.let { fetch ->
            val direct = try { fetch(limit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            direct?.takeIf { it.isNotEmpty() }?.let { return FeedListOutcome.Success(it) }
        }
        currentCoroutineContext().ensureActive()
        return api.discoverServerOutcome(source = platform, limit = limit)
    }

    override suspend fun hotKeywords(limit: Int): List<String> =
        hotKeywordsOutcome(limit).legacyListValue()

    override suspend fun hotKeywordsOutcome(limit: Int): FeedListOutcome<List<String>> {
        currentCoroutineContext().ensureActive()
        directHotKeywords?.let { fetch ->
            val direct = try { fetch(limit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            direct?.takeIf { it.isNotEmpty() }?.let { return FeedListOutcome.Success(it) }
        }
        currentCoroutineContext().ensureActive()
        return api.hotKeywordsServerOutcome(source = platform, limit = limit)
    }

    override suspend fun lyric(song: Song): String? {
        return fetchPlatformLyric(
            song = song,
            direct = { request -> fetchLyricDirect(request.source, request) },
            server = api::lyric,
        )
    }
}
