package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Runs full-snapshot JSON work off the caller thread and drops writes superseded before they start. */
internal class HomeFeedSnapshotIo(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val writeMutex = Mutex()
    private val writeSequence = AtomicLong()

    suspend fun load(readRaw: () -> String?): HomeFeedCache.Snapshot = withContext(ioDispatcher) {
        try {
            val raw = readRaw()
            if (raw.isNullOrBlank()) HomeFeedCache.Snapshot() else HomeFeedCache.decodeSnapshot(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HomeFeedCache.Snapshot()
        }
    }

    suspend fun save(
        snapshot: HomeFeedCache.Snapshot,
        writeRaw: (String) -> Unit,
        encode: (HomeFeedCache.Snapshot) -> String = { HomeFeedCache.encodeSnapshot(it) }
    ) {
        val requestedVersion = writeSequence.incrementAndGet()
        withContext(ioDispatcher) {
            writeMutex.withLock {
                if (requestedVersion != writeSequence.get()) return@withLock
                try {
                    writeRaw(encode(snapshot))
                } catch (_: Exception) {
                    // A failed cache write must not fail the feed refresh.
                }
            }
        }
    }
}

/** 首页/详情/搜索磁盘缓存：冷启动与切平台秒开，失败不冲掉旧数据。 */
object HomeFeedCache {
    private const val PREF = "sw_feed_cache_v4"
    private const val KEY_HOME = "home"
    private const val KEY_DETAIL = "detail_"
    private const val KEY_SEARCH = "search_"
    private const val KEY_BROWSE_PLAYLISTS = "browse_playlists_"
    private val LEGACY_KG_SEARCH_RANK_IDS = setOf("kg_hot", "kg_new", "kg_douyin")
    private val snapshotIo = HomeFeedSnapshotIo()

    /**
     * 资源级 TTL 表（任务 5）：每类资源独立新鲜度，禁止散落魔法毫秒数。
     * 热词/热门歌曲波动快、榜单目录稳定、歌单详情次之。
     */
    object ResourceTtl {
        const val HOT_KEYWORDS_MS = 20L * 60L * 1000L          // 热词 20 分钟
        const val HOT_SONGS_MS = 20L * 60L * 1000L             // 热门歌曲 20 分钟
        const val RANK_CATALOG_MS = 6L * 60L * 60L * 1000L     // 榜单目录 6 小时
        const val RANK_DETAIL_MS = 20L * 60L * 1000L           // 榜单详情 20 分钟
        const val PLAYLIST_CATALOG_MS = 20L * 60L * 1000L      // 精选歌单 20 分钟
        const val PLAYLIST_DETAIL_MS = 2L * 60L * 60L * 1000L  // 歌单详情 2 小时
    }

    /** 资源类型：决定缓存新鲜度判定使用哪条 TTL。 */
    enum class Resource { HOT, RANK, PLAYLIST, KEYWORDS }

    data class Snapshot(
        val hot: Map<String, List<Song>> = emptyMap(),
        val rank: Map<String, List<RankBoard>> = emptyMap(),
        val pl: Map<String, List<Playlist>> = emptyMap(),
        val keywords: Map<String, List<String>> = emptyMap(),
        val discover: Map<String, List<DiscoverCard>> = emptyMap(),
        val sourceStates: Map<String, FeedSourceState> = emptyMap(),
        val savedAt: Long = 0L
    ) {
        val hasData: Boolean get() = hot.values.any { it.isNotEmpty() } || rank.values.any { it.isNotEmpty() } || pl.values.any { it.isNotEmpty() }
        fun isFresh(now: Long = System.currentTimeMillis(), ttlMs: Long = 60 * 60 * 1000L): Boolean =
            savedAt > 0L && now - savedAt in 0..ttlMs

        /** 资源年龄按所属平台独立计算；全局 ts 仅表示整份快照最近一次落盘时间。 */
        fun isResourceFresh(resource: Resource, platform: String, now: Long = System.currentTimeMillis()): Boolean {
            val state = sourceStates[platform] ?: return false
            val fetchedAt = when (resource) {
                Resource.HOT -> state.hotSongsFetchedAt
                Resource.RANK -> state.rankBoardsFetchedAt
                Resource.PLAYLIST -> state.featuredPlaylistFetchedAt
                Resource.KEYWORDS -> state.hotKeywordsFetchedAt
            }
            val ttl = when (resource) {
                Resource.HOT -> ResourceTtl.HOT_SONGS_MS
                Resource.RANK -> ResourceTtl.RANK_CATALOG_MS
                Resource.PLAYLIST -> ResourceTtl.PLAYLIST_CATALOG_MS
                Resource.KEYWORDS -> ResourceTtl.HOT_KEYWORDS_MS
            }
            return isTimestampFresh(fetchedAt, ttl, now)
        }

        /** The top-level stale indicator follows the active platform, not whichever platform saved last. */
        fun isPlatformFeedStale(platform: String, now: Long = System.currentTimeMillis()): Boolean {
            val hasHot = hot[platform].orEmpty().isNotEmpty()
            val hasRank = rank[platform].orEmpty().isNotEmpty()
            val hasPlaylist = pl[platform].orEmpty().isNotEmpty()
            if (!hasHot && !hasRank && !hasPlaylist) return false
            return (hasHot && !isResourceFresh(Resource.HOT, platform, now)) ||
                (hasRank && !isResourceFresh(Resource.RANK, platform, now)) ||
                (hasPlaylist && !isResourceFresh(Resource.PLAYLIST, platform, now))
        }
    }

    internal fun isTimestampFresh(fetchedAt: Long, ttlMs: Long, now: Long): Boolean =
        fetchedAt > 0L && now - fetchedAt in 0..ttlMs

    /** Same JSON codec backs SharedPreferences and deterministic JVM persistence tests. */
    internal fun encodeSnapshot(snapshot: Snapshot, savedAt: Long = System.currentTimeMillis()): String {
        val o = JSONObject()
        o.put("hot", songMap(snapshot.hot, Int.MAX_VALUE))
        o.put("rank", rankMap(snapshot.rank))
        o.put("pl", plMap(snapshot.pl, Int.MAX_VALUE))
        o.put("kws", strMap(snapshot.keywords))
        if (snapshot.discover.isNotEmpty()) o.put("disc", discoverMap(snapshot.discover))
        o.put("state", sourceStateMap(snapshot.sourceStates))
        o.put("ts", savedAt)
        return o.toString()
    }

    internal fun decodeSnapshot(raw: String): Snapshot = runCatching {
        val o = JSONObject(raw)
        Snapshot(
            hot = parseSongMap(o.optJSONObject("hot")),
            rank = parseRankMap(o.optJSONObject("rank")).mapValues { (platform, boards) ->
                // 旧版把三个搜索关键词结果伪装成榜单；升级后不把旧缓存当真实目录继续显示。
                if (platform.equals("kg", ignoreCase = true)) boards.filterNot { it.id in LEGACY_KG_SEARCH_RANK_IDS } else boards
            },
            pl = parsePlMap(o.optJSONObject("pl")),
            keywords = parseStrMap(o.optJSONObject("kws")),
            discover = parseDiscoverMap(o.optJSONObject("disc")),
            sourceStates = parseSourceStates(o.optJSONObject("state")),
            savedAt = o.optLong("ts", 0L)
        )
    }.getOrDefault(Snapshot())

    suspend fun load(ctx: Context): Snapshot = snapshotIo.load {
        ctx.getSharedPreferences(PREF, 0).getString(KEY_HOME, "")
    }

    suspend fun save(
        ctx: Context,
        hot: Map<String, List<Song>>,
        rank: Map<String, List<RankBoard>>,
        pl: Map<String, List<Playlist>>,
        keywords: Map<String, List<String>>,
        sourceStates: Map<String, FeedSourceState> = emptyMap(),
        discover: Map<String, List<DiscoverCard>> = emptyMap()
    ) {
        val snapshot = Snapshot(hot, rank, pl, keywords, discover, sourceStates)
        snapshotIo.save(snapshot, writeRaw = { encoded ->
            ctx.getSharedPreferences(PREF, 0).edit().putString(KEY_HOME, encoded).apply()
        })
    }

    data class BrowsePlaylistSnapshot(
        val playlists: List<Playlist>,
        val savedAt: Long,
        /** null means legacy/unknown continuation, not an authoritative end of the category. */
        val hasMore: Boolean? = null
    ) {
        fun isFresh(ttlMs: Long = ResourceTtl.PLAYLIST_CATALOG_MS, now: Long = System.currentTimeMillis()): Boolean =
            savedAt > 0L && now - savedAt in 0..ttlMs
    }

    /** Persistent query-result cache for the home playlist tabs and personalized playlist carousel. */
    internal fun personalRecommendationCacheKey(platform: String): String =
        "personal|${platform.trim().lowercase(Locale.ROOT)}"

    internal fun browsePlaylistCacheNeedsRefresh(snapshot: BrowsePlaylistSnapshot?, now: Long = System.currentTimeMillis()): Boolean =
        snapshot == null || !snapshot.isFresh(now = now)

    fun loadBrowsePlaylists(ctx: Context, key: String): BrowsePlaylistSnapshot? {
        val raw = runCatching {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_BROWSE_PLAYLISTS + browseKey(key), null)
        }.getOrNull() ?: return null
        return decodeBrowsePlaylistSnapshot(raw, key)
    }

    /** Same JSON codec is used by SharedPreferences and JVM persistence round-trip tests. */
    internal fun encodeBrowsePlaylistSnapshot(
        key: String,
        playlists: List<Playlist>,
        savedAt: Long = System.currentTimeMillis(),
        hasMore: Boolean? = null
    ): String {
        val items = JSONArray()
        playlists.take(240).forEach { items.put(plJson(it, 0)) }
        return JSONObject().put("key", key).put("items", items).put("ts", savedAt)
            .apply { if (hasMore != null) put("hasMore", hasMore) }.toString()
    }

    internal fun decodeBrowsePlaylistSnapshot(raw: String, key: String): BrowsePlaylistSnapshot? =
        runCatching {
            val root = JSONObject(raw)
            if (root.optString("key") != key) null else BrowsePlaylistSnapshot(
                playlists = parsePlList(root.optJSONArray("items") ?: JSONArray()),
                savedAt = root.optLong("ts"),
                hasMore = root.opt("hasMore") as? Boolean
            )
        }.getOrNull()

    /** Successful empty results are cached; category request failures must not be persisted as empty snapshots. */
    fun saveBrowsePlaylists(ctx: Context, key: String, playlists: List<Playlist>, hasMore: Boolean? = null) {
        if (key.isBlank()) return
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_BROWSE_PLAYLISTS + browseKey(key), encodeBrowsePlaylistSnapshot(key, playlists, hasMore = hasMore)).apply()
        }
    }

    private fun browseKey(key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun detailKey(id: String, src: String) = "${src}|$id"

    /**
     * 读取歌单/榜单详情缓存。新格式 JSONObject 带 ts（可用于 TTL 判定）；
     * 兼容旧版纯 JSONArray（无时间戳，视为不新鲜但可显示，由调用方决定是否后台刷新）。
     * @param maxAgeMs 资源级 TTL；<=0 表示不判新鲜（仅作离线兜底显示）。
     */
    fun loadDetail(ctx: Context, id: String, src: String, maxAgeMs: Long = 0L): List<Song> {
        return try {
            val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY_DETAIL + detailKey(id, src), "") ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            val o = try { JSONObject(raw) } catch (_: Exception) { null }
            if (o != null && o.has("songs")) {
                val savedAt = o.optLong("ts", 0L)
                val now = System.currentTimeMillis()
                if (maxAgeMs > 0L && savedAt > 0L && now - savedAt > maxAgeMs) return emptyList()
                parseRankSongs(o.optJSONArray("songs") ?: JSONArray())
            } else {
                // 旧格式纯数组：没有时间戳，只能当“有数据”用，调用方负责后台刷新。
                parseRankSongs(JSONArray(raw))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Loads detail data without discarding expired songs, so callers can keep them visible while refreshing. */
    internal fun loadDetailSnapshot(ctx: Context, id: String, src: String): DetailCacheSnapshot {
        return try {
            val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY_DETAIL + detailKey(id, src), "") ?: return DetailCacheSnapshot(emptyList(), 0L)
            if (raw.isBlank()) return DetailCacheSnapshot(emptyList(), 0L)
            val o = try { JSONObject(raw) } catch (_: Exception) { null }
            if (o != null && o.has("songs")) {
                DetailCacheSnapshot(
                    songs = parseRankSongs(o.optJSONArray("songs") ?: JSONArray()),
                    savedAt = o.optLong("ts", 0L),
                )
            } else {
                // Legacy arrays lack a timestamp: displayable offline, but never a fresh-cache hit.
                DetailCacheSnapshot(parseRankSongs(JSONArray(raw)), 0L)
            }
        } catch (_: Exception) {
            DetailCacheSnapshot(emptyList(), 0L)
        }
    }

    internal suspend fun loadDetailSnapshotOnIo(ctx: Context, id: String, src: String): DetailCacheSnapshot =
        withContext(Dispatchers.IO) { loadDetailSnapshot(ctx, id, src) }

    suspend fun loadDetailOnIo(ctx: Context, id: String, src: String, maxAgeMs: Long = 0L): List<Song> =
        withContext(Dispatchers.IO) { loadDetail(ctx, id, src, maxAgeMs) }

    fun saveDetail(ctx: Context, id: String, src: String, songs: List<Song>) {
        if (id.isBlank() || songs.isEmpty()) return
        try {
            val o = JSONObject()
            val arr = JSONArray()
            // 详情不能只保留前 80 首：大歌单再次打开会像“加载不全”。上限与接口解析保持一致。
            songs.take(1_100).forEach { song ->
                // Detail containers may carry chart-context facts; generic Song JSON remains unchanged.
                arr.put(song.toJson().apply { song.rankChange?.takeIf { it != 0 }?.let { put("rankChange", it) } })
            }
            o.put("songs", arr)
            o.put("ts", System.currentTimeMillis())
            ctx.getSharedPreferences(PREF, 0).edit().putString(KEY_DETAIL + detailKey(id, src), o.toString()).apply()
        } catch (_: Exception) {}
    }

    fun searchKey(kw: String, mode: String, src: String, cross: Boolean) =
        "${mode}|${if (cross) "all" else src}|${kw.trim().lowercase()}"

    /** Persistent cache identity must not expose the query or collapse distinct searches to a 32-bit hash. */
    internal fun searchCacheStorageKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hexDigest = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
        return "${KEY_SEARCH}v2_$hexDigest"
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * 读取搜索缓存。保存时已带 ts；超过 maxAgeMs 视为过期返回 null，让调用方走网络。
     * @param maxAgeMs 搜索缓存 TTL；<=0 表示不判新鲜。
     */
    fun loadSearch(ctx: Context, key: String, maxAgeMs: Long = 0L): Pair<List<Song>, List<Playlist>>? {
        return try {
            val raw = ctx.getSharedPreferences(PREF, 0).getString(searchCacheStorageKey(key), "") ?: return null
            if (raw.isBlank()) return null
            val o = JSONObject(raw)
            val savedAt = o.optLong("ts", 0L)
            val now = System.currentTimeMillis()
            if (maxAgeMs > 0L && savedAt > 0L && now - savedAt > maxAgeMs) return null
            parseSongs(o.optJSONArray("songs") ?: JSONArray()) to parsePlList(o.optJSONArray("pls") ?: JSONArray())
        } catch (_: Exception) {
            null
        }
    }

    fun saveSearch(ctx: Context, key: String, songs: List<Song>, pls: List<Playlist>) {
        try {
            val o = JSONObject()
            val sa = JSONArray(); songs.take(40).forEach { sa.put(it.toJson()) }
            val pa = JSONArray(); pls.forEach { pa.put(plJson(it, 0)) }
            o.put("songs", sa); o.put("pls", pa); o.put("ts", System.currentTimeMillis())
            ctx.getSharedPreferences(PREF, 0).edit().putString(searchCacheStorageKey(key), o.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun songMap(m: Map<String, List<Song>>, n: Int): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) ->
            val a = JSONArray()
            v.forEach { a.put(it.toJson()) }
            o.put(k, a)
        }
        return o
    }

    private fun parseSongMap(o: JSONObject?): Map<String, List<Song>> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, List<Song>>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = parseSongs(o.optJSONArray(k) ?: JSONArray())
        }
        return out
    }

    private fun parseSongs(a: JSONArray): List<Song> {
        val out = ArrayList<Song>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val s = Song.fromJson(o)
            if (s.id.isNotEmpty() && s.name.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun rankMap(m: Map<String, List<RankBoard>>): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) ->
            val a = JSONArray()
            v.forEach { b ->
                a.put(JSONObject().apply {
                    put("id", b.id); put("name", b.name); put("source", b.source); put("cover", b.cover); put("count", b.count)
                    b.updatedAt?.takeIf { it > 0L }?.let { put("updatedAt", it) }
                    val sa = JSONArray()
                    b.songs.take(8).forEach { song ->
                        sa.put(song.toJson().apply { song.rankChange?.takeIf { it != 0 }?.let { put("rankChange", it) } })
                    }
                    put("songs", sa)
                })
            }
            o.put(k, a)
        }
        return o
    }

    private fun parseRankMap(o: JSONObject?): Map<String, List<RankBoard>> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, List<RankBoard>>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val a = o.optJSONArray(k) ?: continue
            val list = ArrayList<RankBoard>()
            for (i in 0 until a.length()) {
                val b = a.optJSONObject(i) ?: continue
                list.add(
                    RankBoard(
                        name = b.optString("name"),
                        source = b.optString("source").ifEmpty { k },
                        songs = parseRankSongs(b.optJSONArray("songs") ?: JSONArray()),
                        id = b.optString("id"),
                        cover = b.optString("cover"),
                        count = b.optInt("count"),
                        updatedAt = b.optLong("updatedAt", 0L).takeIf { it > 0L }
                    )
                )
            }
            out[k] = list
        }
        return out
    }

    /** Rank-only codec restores movement without leaking that chart context into generic Song snapshots. */
    private fun parseRankSongs(a: JSONArray): List<Song> {
        val out = ArrayList<Song>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val song = Song.fromJson(o).copy(rankChange = explicitRankChange(o))
            if (song.id.isNotEmpty() && song.name.isNotEmpty()) out.add(song)
        }
        return out
    }

    private fun discoverMap(m: Map<String, List<DiscoverCard>>): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) ->
            val a = JSONArray()
            v.forEach { c ->
                a.put(
                    JSONObject().apply {
                        put("id", c.id); put("name", c.name); put("cover", c.cover)
                        put("artist", c.artist); put("kind", c.kind); put("title", c.title)
                        put("album", c.album); put("source", c.source); put("songId", c.songId)
                    }
                )
            }
            o.put(k, a)
        }
        return o
    }

    private fun parseDiscoverMap(o: JSONObject?): Map<String, List<DiscoverCard>> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, List<DiscoverCard>>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val a = o.optJSONArray(k) ?: continue
            val list = ArrayList<DiscoverCard>()
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val name = c.optString("name")
                val id = c.optString("id")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    list.add(
                        DiscoverCard(
                            id = id,
                            name = name,
                            cover = c.optString("cover"),
                            artist = c.optString("artist"),
                            kind = c.optString("kind"),
                            title = c.optString("title"),
                            album = c.optString("album"),
                            source = c.optString("source"),
                            songId = c.optString("songId")
                        )
                    )
                }
            }
            out[k] = list
        }
        return out
    }

    private fun plJson(p: Playlist, songN: Int): JSONObject = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("cover", p.cover)
        put("source", p.source); put("local", p.local); put("count", p.count)
        put("author", p.author); put("remoteId", p.remoteId); put("description", p.description)
        put("playCount", p.playCount); put("favorited", p.favorited)
        val sa = JSONArray()
        p.songs.take(songN).forEach { sa.put(it.toJson()) }
        put("songs", sa)
    }

    private fun plMap(m: Map<String, List<Playlist>>, n: Int): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) ->
            val a = JSONArray()
            v.forEach { a.put(plJson(it, 6)) }
            o.put(k, a)
        }
        return o
    }

    private fun parsePlMap(o: JSONObject?): Map<String, List<Playlist>> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, List<Playlist>>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            // 缓存按平台分组；恢复时缺 source 用组 key 补位，绝不隐式网易云。
            out[k] = parsePlList(o.optJSONArray(k) ?: JSONArray(), k)
        }
        return out
    }

    private fun parsePlList(a: JSONArray, fallbackSrc: String = ""): List<Playlist> {
        val out = ArrayList<Playlist>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() && name.isBlank()) continue
            out.add(
                Playlist(
                    id = id,
                    name = name,
                    cover = o.optString("cover"),
                    songs = parseSongs(o.optJSONArray("songs") ?: JSONArray()).toMutableList(),
                    local = o.optBoolean("local", false),
                    source = o.optString("source").ifEmpty { fallbackSrc },
                    author = o.optString("author"),
                    description = o.optString("description"),
                    count = o.optInt("count"),
                    playCount = o.optLong("playCount"),
                    remoteId = o.optString("remoteId"),
                    favorited = o.optBoolean("favorited", false)
                )
            )
        }
        return out
    }

    private fun sourceStateMap(m: Map<String, FeedSourceState>): JSONObject {
        val out = JSONObject()
        m.forEach { (src, state) ->
            out.put(src, JSONObject().apply {
                put("source", state.source)
                put("fetchedAt", state.fetchedAt)
                put("refreshedAt", state.refreshedAt)
                put("hotCount", state.hotCount)
                put("rankCount", state.rankCount)
                put("playlistCount", state.playlistCount)
                put("lastError", state.lastError)
                put("featuredPlaylistFetchedAt", state.featuredPlaylistFetchedAt)
                put("hotSongsFetchedAt", state.hotSongsFetchedAt)
                put("rankBoardsFetchedAt", state.rankBoardsFetchedAt)
                put("hotKeywordsFetchedAt", state.hotKeywordsFetchedAt)
            })
        }
        return out
    }

    private fun parseSourceStates(o: JSONObject?): Map<String, FeedSourceState> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, FeedSourceState>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val state = o.optJSONObject(key) ?: continue
            out[key] = FeedSourceState(
                source = state.optString("source").ifBlank { key },
                fetchedAt = state.optLong("fetchedAt"),
                refreshedAt = state.optLong("refreshedAt"),
                hotCount = state.optInt("hotCount"),
                rankCount = state.optInt("rankCount"),
                playlistCount = state.optInt("playlistCount"),
                lastError = state.optString("lastError"),
                featuredPlaylistFetchedAt = state.optLong("featuredPlaylistFetchedAt", 0L),
                hotSongsFetchedAt = state.optLong("hotSongsFetchedAt", 0L),
                rankBoardsFetchedAt = state.optLong("rankBoardsFetchedAt", 0L),
                hotKeywordsFetchedAt = state.optLong("hotKeywordsFetchedAt", 0L)
            )
        }
        return out
    }

    private fun strMap(m: Map<String, List<String>>): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) ->
            val a = JSONArray()
            v.take(12).forEach { a.put(it) }
            o.put(k, a)
        }
        return o
    }

    private fun parseStrMap(o: JSONObject?): Map<String, List<String>> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val a = o.optJSONArray(k) ?: continue
            val list = ArrayList<String>()
            for (i in 0 until a.length()) {
                val s = a.optString(i).trim()
                if (s.isNotEmpty()) list.add(s)
            }
            out[k] = list
        }
        return out
    }
}
