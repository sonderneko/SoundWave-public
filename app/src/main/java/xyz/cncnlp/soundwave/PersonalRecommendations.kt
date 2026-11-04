package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Locale
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.recommend.TasteProfileV2
import xyz.cncnlp.soundwave.recommend.recommendationAttempt
import java.text.Normalizer

/**
 * Shared, data-backed recommendation primitives for the home and search pages.
 * Song currently has no persisted genre/style field and no generic similar-song
 * endpoint exists, so this intentionally uses only observed artists/albums and
 * real provider search results rather than inventing genre or popularity tags.
 */
internal object PersonalRecommendations {
    private const val PREFS = "personal_recommendation_state_v1"
    private const val RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    private const val RECENT_FALLBACK_COUNT = 30
    private const val MAX_SEEN_KEYS = 360
    private val platforms = setOf("wy", "kg", "kw", "tx", "mg")
    private val lock = Any()

    enum class QueryKind { ARTIST, ALBUM, TRACK }
    data class TasteQuery(val term: String, val weight: Int, val kind: QueryKind, val expectedArtists: Set<String> = emptySet())
    data class Profile(
        val queries: List<TasteQuery>,
        val excludedSongKeys: Set<String>,
        val signature: String,
        val activitySignature: String,
        val exposureScope: String = "",
        /** R 波一期统一画像；仅当日粒度统计数据在场时构建，旧存量/旧序列化画像为 null（走旧逻辑）。 */
        val taste: TasteProfileV2? = null
    )

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private fun artistTerms(value: String): List<String> = value
        .split(Regex("[/、,|&]+"))
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() && normalized(it) !in setOf("unknown", "null", "undefined", "未知", "未知歌手") }
        .distinctBy(::normalized)

    // ACCEPT6-sortfreeze：token/songIdentity 均为纯函数，但单次计算含 SHA-256 + hex 格式化
    // （identity 另含 NFKC 归一化）。推荐画像在根组合主线程按输入 remember 重建，歌单排序落盘、
    // 切歌、收藏变化都会使输入列表身份/顺序变化而整表重跑，对全库逐首反复计算 identity 的
    // 量级实测达数百 ms（30,400 首约 0.8s 服务器 CPU，真机数秒）——即用户感知的「排序卡几秒」。
    // 备忘后同一输入只算一次，重建只剩查表与集合操作；输出逐字不变。缓存按条目封顶，
    // 超限整表清空（仅丢失备忘，不影响正确性）；ConcurrentHashMap 保证多线程下至多重复计算。
    private const val TOKEN_CACHE_LIMIT = 4_096
    private const val IDENTITY_CACHE_LIMIT = 100_000
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val identityCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Stable opaque token, used both for cross-platform track identity and on-device seen history. */
    fun token(value: String): String {
        tokenCache[value]?.let { return it }
        val out = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (tokenCache.size >= TOKEN_CACHE_LIMIT) tokenCache.clear()
        tokenCache[value] = out
        return out
    }

    /** Cross-platform identity: same title and artist is one recommendation, regardless of provider ID. */
    fun songIdentity(song: Song): String {
        // 备忘键取尽 identity 的全部输入字段：主分支只依赖 name/singer，回退分支依赖
        // source/id 与 Song.key()（其字段集为 source/id/name/singer/duration）——五字段相同则
        // identity 必然相同，与 Song 的其余字段（封面/专辑等）无关。
        val cacheKey = buildString {
            append(song.source).append('\u0001').append(song.id).append('\u0001')
                .append(song.name).append('\u0001').append(song.singer).append('\u0001')
                .append(song.duration)
        }
        identityCache[cacheKey]?.let { return it }
        val title = normalized(song.name)
        val artists = artistTerms(song.singer).map(::normalized).sorted().joinToString("|")
        val identity = if (title.isNotBlank() && artists.isNotBlank()) token("$title|$artists")
        else token("${song.source.lowercase(Locale.ROOT)}:${song.id.ifBlank { song.key() }}")
        if (identityCache.size >= IDENTITY_CACHE_LIMIT) identityCache.clear()
        identityCache[cacheKey] = identity
        return identity
    }

    fun playlistIdentity(playlist: Playlist, fallbackPlatform: String): String = token(
        "${playlist.source.ifBlank { fallbackPlatform }.lowercase(Locale.ROOT)}:" +
            playlist.remoteId.ifBlank { playlist.id }
    )

    fun buildProfile(
        history: List<Song>,
        likedSongs: List<Song>,
        listenedArtists: List<Pair<String, Long>> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        listenedSongs: List<ListenStats.SongStat> = emptyList(),
        profileOwner: String = "local",
        favoritePlaylists: List<Playlist> = emptyList(),
        favoriteAlbums: List<Playlist> = emptyList(),
        importedPlaylists: List<Playlist> = emptyList(),
        librarySongs: List<Song> = emptyList(),
        dailyListenJson: String = "",
        // ACCEPT6-FREEZE：调用方已在 IO 线程算好衰减事实时直接传入，跳过主线程全量解析
        // 365 天日统计 JSON；为空时维持旧口径（按 dailyListenJson 现算），行为等价。
        precomputedDecayedFacts: ListenStatsDailyStore.DecayedFacts? = null,
        dislikedSongKeys: Set<String> = emptySet(),
        dislikedIdentities: Set<String> = emptySet()
    ): Profile {
        val scores = linkedMapOf<Pair<QueryKind, String>, Pair<String, Int>>()
        fun add(raw: String, weight: Int, kind: QueryKind) {
            val term = raw.trim().replace(Regex("\\s+"), " ")
            val key = kind to normalized(term)
            if (term.length < 2 || key.second in setOf("unknown", "null", "undefined", "未知", "未知歌手", "未知专辑")) return
            val previous = scores[key]
            scores[key] = term to ((previous?.second ?: 0) + weight.coerceAtLeast(1))
        }
        likedSongs.distinctBy(::songIdentity).take(80).forEach { song ->
            artistTerms(song.singer).forEach { add(it, 8, QueryKind.ARTIST) }
            if (song.album.isNotBlank()) add(song.album, 4, QueryKind.ALBUM)
        }
        // Loaded library tracks inform taste, but are NOT "liked" exclusions. Never infer per-track genres from a playlist title.
        // ACCEPT6-sortfreeze：只需要前 240 个 distinct identity；改惰性序列后取满即停，
        // 不再为取样把全库 materialize 并逐首计算 identity（结果与急切版本逐项一致）。
        (librarySongs.asSequence() + (favoritePlaylists + favoriteAlbums + importedPlaylists).asSequence()
            .flatMap { it.songs.asSequence() })
            .distinctBy(::songIdentity).take(240).forEach { song ->
                artistTerms(song.singer).forEach { add(it, 3, QueryKind.ARTIST) }
                add(song.album, 2, QueryKind.ALBUM)
            }
        favoriteAlbums.forEach { add(it.name, 5, QueryKind.ALBUM) }
        val uniqueHistory = history.distinctBy(::songIdentity).take(100)
        uniqueHistory.forEachIndexed { index, song ->
            val recencyWeight = (8 - index / 12).coerceAtLeast(2)
            artistTerms(song.singer).forEach { add(it, recencyWeight, QueryKind.ARTIST) }
            if (song.album.isNotBlank()) add(song.album, (recencyWeight / 2).coerceAtLeast(1), QueryKind.ALBUM)
        }
        // R 波一期：日粒度统计在场时，统计证据改走 45 天半衰期衰减事实并构建统一画像 TasteProfileV2；
        // 总播放 ≥5 才由统计生成查询（中层冷启动），衰减口径下被软降权的歌手不再贡献统计查询。
        // 日数据缺失（极旧存量）回落终身 Top 列表旧口径，输入放宽为 Top20 歌手 / Top60 歌曲。
        val decayed = precomputedDecayedFacts ?: if (dailyListenJson.isNotBlank()) ListenStatsDailyStore.decayedFacts(dailyListenJson, nowMs) else null
        val facts = decayed?.takeIf { it.songs.isNotEmpty() }
        val taste = if (facts != null) buildTasteProfileV2(
            decayed = facts,
            likedSongs = likedSongs,
            librarySongs = librarySongs,
            favoritePlaylists = favoritePlaylists,
            favoriteAlbums = favoriteAlbums,
            importedPlaylists = importedPlaylists,
            history = history,
            dislikedSongKeys = dislikedSongKeys,
            dislikedIdentities = dislikedIdentities
        ) else null
        val statsSignatureParts = mutableListOf<String>()
        val demotedArtists = taste?.demotedArtists ?: emptySet()
        if (facts != null) {
            if (facts.totalPlays >= STATS_MIN_PLAYS) {
                val artistSeconds = linkedMapOf<String, Pair<String, Double>>()
                facts.songs.forEach { fact ->
                    artistTerms(fact.singer).forEach { part ->
                        val key = normalized(part)
                        val previous = artistSeconds[key]
                        artistSeconds[key] = part to ((previous?.second ?: 0.0) + fact.seconds)
                    }
                }
                artistSeconds.values.sortedByDescending { it.second }.take(20).forEachIndexed { index, (artist, seconds) ->
                    if (seconds <= 0.0 || TasteProfileV2.normalized(artist) in demotedArtists) return@forEachIndexed
                    add(artist, (10 - index / 2).coerceAtLeast(2) + (seconds / 1800.0).toInt().coerceAtMost(12), QueryKind.ARTIST)
                }
                facts.songs.sortedWith(compareByDescending<ListenStatsDailyStore.DecayedSongFact> { it.count }.thenByDescending { it.seconds })
                    .take(60).forEach { fact ->
                        val weight = (fact.count.toInt().coerceIn(1, 12) + (fact.seconds / 1800.0).toInt().coerceAtMost(6)).coerceAtLeast(1)
                        add(fact.name, weight, QueryKind.TRACK)
                        // 注意：衰减口径下不再像终身口径那样逐曲给歌手保底加权——衰减到近零的
                        // 旧歌每首 +1 会把衰减完全抵消；歌手证据只走上方按衰减秒数聚合的一条路。
                    }
            }
            statsSignatureParts += facts.songs.take(60).map { "${it.key}:${it.count}:${it.seconds}" }
            statsSignatureParts += listOf("plays:${facts.totalPlays}")
        } else {
            listenedArtists.take(20).forEachIndexed { index, (artist, seconds) ->
                if (seconds > 0) artistTerms(artist).forEach { add(it, (10 - index).coerceAtLeast(2) + (seconds / 1800L).coerceAtMost(12L).toInt(), QueryKind.ARTIST) }
            }
            listenedSongs.sortedWith(compareByDescending<ListenStats.SongStat> { it.cnt }.thenByDescending { it.sec })
                .take(60).forEach { stat ->
                    val weight = (stat.cnt.coerceIn(1, 12) + (stat.sec / 1_800L).toInt().coerceAtMost(6)).coerceAtLeast(1)
                    add(stat.name, weight, QueryKind.TRACK)
                    artistTerms(stat.singer).forEach { add(it, (weight / 2).coerceAtLeast(1), QueryKind.ARTIST) }
                }
            statsSignatureParts += listenedSongs.take(60).map { "${it.key}:${it.cnt}:${it.sec}" }
            statsSignatureParts += listenedArtists.map { "${normalized(it.first)}:${it.second}" }
        }
        // TRACK 查询的期望歌手：终身列表与衰减事实按歌名并集（衰减口径下 TRACK 词来自事实，不在此并集就会永假）。
        val trackArtistLookup: Map<String, Set<String>> = if (facts != null) {
            (listenedSongs.map { normalized(it.name) to it.singer } + facts.songs.map { normalized(it.name) to it.singer })
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, singers) -> singers.flatMap { artistTerms(it) }.map(::normalized).toSet() }
        } else emptyMap()
        val rankedQueries = scores.entries
            .map { entry ->
                val artists = if (entry.key.first == QueryKind.TRACK) {
                    trackArtistLookup[entry.key.second] ?: listenedSongs
                        .filter { normalized(it.name) == entry.key.second }
                        .flatMap { artistTerms(it.singer) }.map(::normalized).toSet()
                } else emptySet()
                TasteQuery(entry.value.first, entry.value.second, entry.key.first, artists)
            }
            .sortedWith(compareByDescending<TasteQuery> { it.weight }.thenBy { it.kind.ordinal }.thenBy { normalized(it.term) })
        val queries = buildList<TasteQuery> {
            addAll(rankedQueries.filter { it.kind == QueryKind.ARTIST }.take(8))
            addAll(rankedQueries.filter { it.kind == QueryKind.ALBUM }.take(4))
            addAll(rankedQueries.filter { it.kind == QueryKind.TRACK }.take(4))
            if (size < 16) {
                val selected = mapTo(mutableSetOf()) { it.kind to normalized(it.term) }
                addAll(rankedQueries.filterNot { (it.kind to normalized(it.term)) in selected }.take(16 - size))
            }
        }
        val recentKeys = history.withIndex().asSequence()
            .filter { (index, song) ->
                if (song.playedAt > 0L) nowMs - song.playedAt in 0..RECENT_WINDOW_MS
                else index < RECENT_FALLBACK_COUNT
            }
            .map { songIdentity(it.value) }
            .toSet()
        val likedKeys = likedSongs.mapTo(linkedSetOf(), ::songIdentity)
        val signature = token(queries.map { "${it.kind}:${normalized(it.term)}" }.sorted().joinToString("|"))
        val activitySignature = token(
            (history.take(RECENT_FALLBACK_COUNT).map { "${songIdentity(it)}:${it.playedAt / 86_400_000L}" } + likedSongs.map(::songIdentity) +
                statsSignatureParts +
                queries.map { "${it.kind}:${it.term}:${it.weight}:${it.expectedArtists.sorted()}" } +
                (favoritePlaylists + favoriteAlbums + importedPlaylists).map { "${playlistIdentity(it, "")}:${it.name}:${it.songs.map(::songIdentity).sorted()}" })
                .joinToString("|")
        )
        val owner = profileOwner.trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) } ?: "local"
        val exposureScope = token("featured-playlists|${owner.lowercase(Locale.ROOT)}")
        return Profile(queries, recentKeys + likedKeys, signature, activitySignature, exposureScope, taste)
    }

    /** 中层冷启动门槛：日粒度总播放达到此数才启用统计画像（收藏/历史证据不受此门影响）。 */
    private const val STATS_MIN_PLAYS = 5L

    /**
     * 由衰减事实装配统一画像：已拥有 = 曲库 + 歌单成员的跨平台 identity；不喜欢按歌手计数
     * 以可枚举歌曲（收藏/曲库/歌单/历史/统计事实）对 DislikeStore 的 key 集与 identity 集判定
     *（DislikeStore 只存哈希，无歌手名存量）。同一 identity 只计一次，避免多来源重复计数误伤降权。
     */
    private fun buildTasteProfileV2(
        decayed: ListenStatsDailyStore.DecayedFacts,
        likedSongs: List<Song>,
        librarySongs: List<Song>,
        favoritePlaylists: List<Playlist>,
        favoriteAlbums: List<Playlist>,
        importedPlaylists: List<Playlist>,
        history: List<Song>,
        dislikedSongKeys: Set<String>,
        dislikedIdentities: Set<String>
    ): TasteProfileV2 {
        val playlists = favoritePlaylists + favoriteAlbums + importedPlaylists
        // ACCEPT6-sortfreeze：单遍构建 owned 集（原 distinctBy + mapTo 对每首歌算两遍 identity）；
        // 集合内容与迭代顺序（首次出现序）不变。
        val owned = linkedSetOf<String>()
        (librarySongs.asSequence() + playlists.asSequence().flatMap { it.songs.asSequence() })
            .forEach { song -> owned.add(songIdentity(song)) }
        val pseudoSongs = decayed.songs.map { fact ->
            Song(
                id = fact.key.substringAfter(':', ""),
                name = fact.name,
                singer = fact.singer,
                source = fact.key.substringBefore(':', "")
            )
        }
        val dislikeCounts = linkedMapOf<String, Int>()
        if (dislikedSongKeys.isNotEmpty() || dislikedIdentities.isNotEmpty()) {
            (likedSongs + librarySongs + playlists.flatMap { it.songs } + history + pseudoSongs)
                .distinctBy(::songIdentity)
                .forEach { song ->
                    if (song.key() in dislikedSongKeys || songIdentity(song) in dislikedIdentities) {
                        TasteProfileV2.artistKeys(song.singer).forEach { key ->
                            dislikeCounts[key] = (dislikeCounts[key] ?: 0) + 1
                        }
                    }
                }
        }
        return TasteProfileV2.build(
            TasteProfileV2.Inputs(
                favorites = likedSongs,
                librarySongs = librarySongs,
                playlists = playlists,
                history = history,
                listened = decayed.songs.map { TasteProfileV2.ListenedSong(it.key, it.name, it.singer, it.seconds, it.count) },
                favoriteAlbumNames = favoriteAlbums.map { it.name },
                ownedIdentities = owned,
                dislikeCountByArtist = dislikeCounts,
                totalPlays = decayed.totalPlays.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        )
    }

    fun loadSeen(context: Context, kind: String, platform: String, signature: String): Set<String> = synchronized(lock) {
        val raw = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(seenPreferenceKey(kind, platform, signature), null)
        }.getOrNull() ?: return@synchronized emptySet()
        runCatching {
            val array = JSONArray(raw)
            linkedSetOf<String>().apply { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        }.getOrDefault(emptySet())
    }

    fun rememberSeen(context: Context, kind: String, platform: String, signature: String, keys: Collection<String>): Set<String> = synchronized(lock) {
        val old = loadSeen(context, kind, platform, signature)
        val merged = mergeSeenKeys(old, keys)
        runCatching {
            val array = JSONArray().apply { merged.forEach { value: String -> put(value) } }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(seenPreferenceKey(kind, platform, signature), array.toString())
                .apply()
        }
        merged
    }

    /** Ordered unique ID window: re-exposure moves an ID to the newest edge and the preference remains bounded. */
    internal fun mergeSeenKeys(existing: Collection<String>, added: Collection<String>, limit: Int = MAX_SEEN_KEYS): LinkedHashSet<String> {
        if (limit <= 0) return linkedSetOf()
        val merged = linkedSetOf<String>()
        existing.filter(String::isNotBlank).forEach(merged::add)
        added.filter(String::isNotBlank).forEach { key -> merged.remove(key); merged.add(key) }
        while (merged.size > limit) merged.remove(merged.first())
        return merged
    }

    private fun seenPreferenceKey(kind: String, platform: String, signature: String): String =
        "seen_${token("$kind|${platform.lowercase(Locale.ROOT)}|$signature")}"

    internal fun selectQueries(queries: List<TasteQuery>, limit: Int): List<TasteQuery> {
        if (limit <= 0) return emptyList()
        val quotas = when {
            limit <= 6 -> listOf(QueryKind.ARTIST to 2, QueryKind.ALBUM to 2, QueryKind.TRACK to 2)
            limit <= 8 -> listOf(QueryKind.ARTIST to 4, QueryKind.ALBUM to 2, QueryKind.TRACK to 2)
            else -> listOf(QueryKind.ARTIST to 5, QueryKind.ALBUM to 3, QueryKind.TRACK to 4)
        }
        val selected = ArrayList<TasteQuery>(limit)
        quotas.forEach { (kind, count) -> selected.addAll(queries.filter { it.kind == kind }.take(count)) }
        if (selected.size < limit) {
            val selectedKeys = selected.mapTo(mutableSetOf()) { it.kind to normalized(it.term) }
            selected.addAll(queries.filterNot { (it.kind to normalized(it.term)) in selectedKeys }.take(limit - selected.size))
        }
        return selected.take(limit)
    }

    /** Query the chosen provider using real artist/album terms; no static or fabricated song lists. */
    suspend fun searchSongs(
        platform: String,
        profile: Profile,
        excludedKeys: Set<String>,
        limit: Int = 60
    ): List<Song> {
        val source = platform.lowercase(Locale.ROOT)
        if (source !in platforms || profile.queries.isEmpty()) return emptyList()
        val queries = selectQueries(profile.queries, 8)
        val provider = MusicSourceRegistry.platformSource(source)
        val batches = coroutineScope {
            queries.map { query ->
                async(Dispatchers.IO) {
                    query to recommendationAttempt { provider.search(query.term, 1, 30) }.orEmpty()
                }
            }.awaitAll()
        }
        val ranked = linkedMapOf<String, Pair<Song, Int>>()
        for ((query, songs) in batches) for (song in songs) {
            if (song.id.isBlank() || song.name.isBlank() || !song.source.equals(source, true)) continue
            val key = songIdentity(song)
            if (key in profile.excludedSongKeys || key in excludedKeys) continue
            val queryTerm = normalized(query.term)
            val songArtists = artistTerms(song.singer).map(::normalized).toSet()
            val artistMatch = queryTerm in songArtists
            val album = normalized(song.album)
            val albumMatch = album.isNotBlank() && album == queryTerm
            val title = normalized(song.name)
            val trackMatch = title == queryTerm && query.expectedArtists.isNotEmpty() && songArtists == query.expectedArtists.map(::normalized).toSet()
            // Search endpoints can be fuzzy; do not turn unrelated fallbacks into personalized results.
            val relevant = when (query.kind) {
                QueryKind.ARTIST -> artistMatch
                QueryKind.ALBUM -> albumMatch
                QueryKind.TRACK -> trackMatch
            }
            if (!relevant) continue
            val score = query.weight * 3 +
                (if (query.kind == QueryKind.ARTIST && artistMatch) 24 else 0) +
                (if (query.kind == QueryKind.ALBUM && albumMatch) 20 else 0) +
                (if (query.kind == QueryKind.TRACK && trackMatch) 22 else 0) +
                (if (song.source.equals(source, ignoreCase = true)) 2 else 0)
            val previous = ranked[key]
            if (previous == null || score > previous.second) ranked[key] = song to score
        }
        val ordered = ranked.values.sortedWith(
            compareByDescending<Pair<Song, Int>> { it.second }
                .thenBy { normalized(it.first.name) }
                .thenBy { normalized(it.first.singer) }
        ).map { it.first }
        return diversifyArtists(ordered, limit)
    }

    /** Rank recalled category tracks using the same observed artists/albums/qualified track identities. */
    internal fun rankCandidates(songs: List<Song>, profile: Profile?): List<Song> {
        if (profile == null) return songs
        fun score(song: Song): Int {
            val artists = artistTerms(song.singer).map(::normalized).toSet()
            return profile.queries.sumOf { q ->
                val term = normalized(q.term)
                val match = when (q.kind) {
                    QueryKind.ARTIST -> term in artists
                    QueryKind.ALBUM -> term == normalized(song.album)
                    QueryKind.TRACK -> term == normalized(song.name) && q.expectedArtists.isNotEmpty() && artists == q.expectedArtists.map(::normalized).toSet()
                }
                if (match) q.weight else 0
            }
        }
        // Stable sorting keeps seeded exploration order only for equally supported candidates.
        return songs.sortedByDescending(::score)
    }

    /** Keep up to two tracks per artist in the first pass, then fill remaining slots without losing relevance. */
    internal fun diversifyArtists(songs: List<Song>, limit: Int): List<Song> {
        if (limit <= 0) return emptyList()
        val selected = ArrayList<Song>(limit)
        val selectedKeys = HashSet<String>()
        val artistCounts = HashMap<String, Int>()
        for (song in songs) {
            if (selected.size >= limit) break
            val key = songIdentity(song)
            if (key in selectedKeys) continue
            val artists = artistTerms(song.singer).map(::normalized)
            if (artists.any { (artistCounts[it] ?: 0) >= 2 }) continue
            selected.add(song)
            selectedKeys.add(key)
            artists.forEach { artistCounts[it] = (artistCounts[it] ?: 0) + 1 }
        }
        if (selected.size < limit) for (song in songs) {
            if (selected.size >= limit) break
            if (selectedKeys.add(songIdentity(song))) selected.add(song)
        }
        return selected
    }

    /** Query the selected platform's real playlist search endpoint using the same user-derived profile. */
    suspend fun searchPlaylists(
        platform: String,
        profile: Profile,
        excludedKeys: Set<String>,
        limit: Int = 60,
        queryLimit: Int = 12
    ): List<Playlist> {
        val source = platform.lowercase(Locale.ROOT)
        if (source !in platforms || profile.queries.isEmpty()) return emptyList()
        val queries = selectQueries(profile.queries, queryLimit.coerceIn(0, 16))
        val provider = MusicSourceRegistry.platformSource(source)
        val batches = coroutineScope {
            queries.map { query ->
                async(Dispatchers.IO) {
                    query to recommendationAttempt { provider.searchPlaylists(query.term, source, 30) }.orEmpty()
                }
            }.awaitAll()
        }
        val ranked = linkedMapOf<String, Pair<Playlist, Int>>()
        for ((query, playlists) in batches) for (playlist in playlists) {
            if (playlist.id.isBlank() || !playlist.source.equals(source, true)) continue
            val key = playlistIdentity(playlist, source)
            if (key in excludedKeys) continue
            // If a provider returns a populated track list, skip a playlist only when every track is recent/liked.
            if (playlist.songs.isNotEmpty() && playlist.songs.all { songIdentity(it) in profile.excludedSongKeys }) continue
            val term = normalized(query.term)
            val metadata = (listOf(playlist.name, playlist.author, playlist.description) +
                playlist.songs.flatMap { listOf(it.name, it.singer, it.album) }).joinToString(" ").let(::normalized)
            val match = metadata.contains(term)
            // Keep real provider metadata as the relevance gate; no synthetic genre/popularity labels.
            if (!match) continue
            val score = query.weight * 3 + if (match) 20 else 0
            val previous = ranked[key]
            if (previous == null || score > previous.second) ranked[key] = playlist to score
        }
        return ranked.values.sortedWith(
            compareByDescending<Pair<Playlist, Int>> { it.second }
                .thenBy { normalized(it.first.name) }
                .thenBy { playlistIdentity(it.first, source) }
        ).take(limit).map { it.first }
    }
}
