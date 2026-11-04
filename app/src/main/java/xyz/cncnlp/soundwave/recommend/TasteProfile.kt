package xyz.cncnlp.soundwave.recommend

import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * R 波一期统一口味画像（TasteProfileV2）：今日推荐与心动模式共用同一个本机打分器。
 *
 * 纯函数层：不引用 Android / Context / JSON / 服务端。身份敏感输入（已拥有 identity 集、
 * 按歌手统计的不喜欢计数、日粒度衰减后的收听事实）由调用方以普通数据喂入；本机计算，
 * 不臆造 genre（Song 无可信流派字段），身份口径与调用方保持一致。
 *
 * 证据量级与 ISSUE-RECO §4.1 对齐：收藏 +8/首（单歌手封顶 40）、曲库 +3/首（封顶 24）、
 * 历史近因 8→2、统计带 45 天半衰期衰减、歌单共现 ×0.4、歌手级软降权 ×0.2、已拥有 ×0.35。
 */
data class TasteProfileV2(
    /** artistKey（归一化歌手名）→ 亲和度（已应用软降权）。 */
    val artistAffinity: Map<String, Double>,
    /** artistKey → 展示名（首见原文）。 */
    val artistNames: Map<String, String>,
    /** 归一化专辑名 → 专辑亲和。 */
    val albumAffinity: Map<String, Double>,
    /** 歌曲键（source:id）→ 衰减后的单曲统计分（min(cnt',12) + min(sec'/1800,6)）。 */
    val trackScores: Map<String, Double>,
    /** 已拥有歌曲的跨平台 identity 集（曲库/歌单成员，非收藏口径由调用方剔除重叠无妨：收藏在上游已硬排除）。 */
    val ownedIdentities: Set<String>,
    /** 被软降权的 artistKey 集。 */
    val demotedArtists: Set<String>,
    /** artistKey → 各平台 singerId（歌手扩展池召回用，来自用户自有歌曲的真实元数据）。 */
    val artistCatalog: Map<String, List<ArtistSourceId>>,
    /** 未衰减总播放数（冷启动分层：≥5 启用统计画像）。 */
    val totalPlays: Int
) {
    data class ArtistSourceId(val source: String, val singerId: String, val displayName: String)

    /** 衰减后的单曲收听事实（由 ListenStatsDailyStore.decayedFacts 映射而来）。 */
    data class ListenedSong(val key: String, val name: String, val singer: String, val seconds: Double, val count: Double)

    data class Inputs(
        val favorites: List<Song>,
        val librarySongs: List<Song>,
        /** 用户自建/收藏/导入歌单（共现与已拥有的来源）。 */
        val playlists: List<Playlist>,
        /** 近因序（最新在前）的播放历史。 */
        val history: List<Song>,
        val listened: List<ListenedSong>,
        val favoriteAlbumNames: List<String> = emptyList(),
        val ownedIdentities: Set<String> = emptySet(),
        /** artistKey → 被不喜欢的歌曲数（调用方以 DislikeStore 的 key/identity 集判定）。 */
        val dislikeCountByArtist: Map<String, Int> = emptyMap(),
        val totalPlays: Int = 0
    )

    /** 某歌手字段（可含多位歌手）的亲和：取各分量最大值——合作曲只要一位是高亲和即算数。 */
    fun affinityOf(singerField: String): Double =
        artistKeys(singerField).maxOfOrNull { artistAffinity[it] ?: 0.0 } ?: 0.0

    /** 亲和 Top 歌手（artistKey 降序，同分按 key 稳定）。 */
    fun topArtists(limit: Int): List<String> =
        artistAffinity.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit.coerceAtLeast(0)).map { it.key }

    /** 统一歌曲打分：Σ歌手亲和 + 专辑亲和×0.5 + 单曲统计分×0.5。 */
    fun songScore(song: Song): Double {
        val artist = artistKeys(song.singer).distinct().sumOf { artistAffinity[it] ?: 0.0 }
        val album = if (song.album.isBlank()) 0.0 else albumAffinity[normalized(song.album)] ?: 0.0
        val track = trackScores[trackKey(song)] ?: 0.0
        return artist + album * ALBUM_SCORE_FACTOR + track * TRACK_SCORE_FACTOR
    }

    companion object {
        const val OWNED_FACTOR = 0.35
        const val DEMOTE_FACTOR = 0.2
        const val CO_OCCURRENCE_FACTOR = 0.4
        private const val ALBUM_SCORE_FACTOR = 0.5
        private const val TRACK_SCORE_FACTOR = 0.5
        private const val FAVORITE_PER_SONG = 8.0
        private const val FAVORITE_CAP = 40.0
        private const val LIBRARY_PER_SONG = 3.0
        private const val LIBRARY_CAP = 24.0
        private const val HISTORY_CAP = 24.0
        private const val STATS_CAP = 36.0
        private const val ALBUM_CAP = 20.0
        private const val CO_OCCURRENCE_CAP = 16.0
        private const val CO_OCCURRENCE_MIN_COMPANION = 8.0
        private const val SEED_BASE_WEIGHT = 4.0
        private const val EXPLORE_FLOOR_RATIO = 0.05

        // ACCEPT6-sortfreeze：normalized/artistKeys 为纯函数，但单次含正则切分 + NFKC 归一化；
        // build() 对全库逐首、多遍调用（曲库遍/歌单共现遍/歌手 id 遍），是画像重建在主线程的
        // 主要残余开销。歌手字段在曲库中高度重复，备忘后绝大多数调用只剩查表；输出不变。
        // 缓存按条目封顶，超限整表清空（仅丢失备忘，不影响正确性）。
        private const val NORMALIZED_CACHE_LIMIT = 8_192
        private const val ARTIST_KEYS_CACHE_LIMIT = 8_192
        private val normalizedCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val artistKeysCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

        /** 与 HeartRecommendationPolicy 同口径的歌手切分（feat./ft. 与分隔符），保证两处画像可比。 */
        fun artistParts(value: String): List<String> = value.split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+|[/、;:,&＆]+"))
            .map { it.trim() }.filter { normalized(it).isNotEmpty() }

        fun artistKey(part: String): String = normalized(part)

        fun artistKeys(singerField: String): List<String> {
            artistKeysCache[singerField]?.let { return it }
            val out = artistParts(singerField).map(::normalized).distinct()
            if (artistKeysCache.size >= ARTIST_KEYS_CACHE_LIMIT) artistKeysCache.clear()
            artistKeysCache[singerField] = out
            return out
        }

        fun normalized(value: String): String {
            normalizedCache[value]?.let { return it }
            val out = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            if (normalizedCache.size >= NORMALIZED_CACHE_LIMIT) normalizedCache.clear()
            normalizedCache[value] = out
            return out
        }

        private fun trackKey(song: Song): String =
            if (song.id.isBlank()) "" else "${song.source.lowercase(Locale.ROOT)}:${song.id}"

        fun build(inputs: Inputs): TasteProfileV2 {
            val names = linkedMapOf<String, String>()
            fun registerNames(singerField: String) {
                artistParts(singerField).forEach { part -> names.putIfAbsent(normalized(part), part) }
            }
            val favorite = linkedMapOf<String, Double>()
            val library = linkedMapOf<String, Double>()
            val historyScore = linkedMapOf<String, Double>()
            val stats = linkedMapOf<String, Double>()
            val statSeconds = linkedMapOf<String, Double>()
            val albums = linkedMapOf<String, Double>()
            fun bump(map: MutableMap<String, Double>, key: String, delta: Double, cap: Double) {
                if (key.isEmpty() || delta <= 0.0) return
                map[key] = minOf((map[key] ?: 0.0) + delta, cap)
            }
            inputs.favorites.forEach { song ->
                registerNames(song.singer)
                artistKeys(song.singer).forEach { bump(favorite, it, FAVORITE_PER_SONG, FAVORITE_CAP) }
                if (song.album.isNotBlank()) bump(albums, normalized(song.album), 4.0, ALBUM_CAP)
            }
            val libraryUniverse = (inputs.librarySongs + inputs.playlists.flatMap { it.songs })
            libraryUniverse.forEach { song ->
                registerNames(song.singer)
                artistKeys(song.singer).forEach { bump(library, it, LIBRARY_PER_SONG, LIBRARY_CAP) }
                if (song.album.isNotBlank()) bump(albums, normalized(song.album), 2.0, ALBUM_CAP)
            }
            inputs.favoriteAlbumNames.forEach { name ->
                if (name.isNotBlank()) bump(albums, normalized(name), 5.0, ALBUM_CAP)
            }
            inputs.history.forEachIndexed { index, song ->
                registerNames(song.singer)
                val weight = (8 - index / 12).coerceAtLeast(2).toDouble()
                artistKeys(song.singer).forEach { bump(historyScore, it, weight, HISTORY_CAP) }
            }
            val trackScores = linkedMapOf<String, Double>()
            inputs.listened.forEach { fact ->
                registerNames(fact.singer)
                val statScore = minOf(fact.count, 12.0) + minOf(fact.seconds / 1800.0, 6.0)
                if (statScore > 0.0) trackScores[fact.key] = statScore
                artistKeys(fact.singer).forEach { key ->
                    bump(stats, key, statScore, STATS_CAP)
                    statSeconds[key] = (statSeconds[key] ?: 0.0) + fact.seconds
                }
            }
            val base = linkedMapOf<String, Double>()
            (favorite.keys + library.keys + historyScore.keys + stats.keys).forEach { key ->
                base[key] = (favorite[key] ?: 0.0) + (library[key] ?: 0.0) + (historyScore[key] ?: 0.0) + (stats[key] ?: 0.0)
            }
            // 歌单共现：与高亲和歌手同单的其他歌手得 0.4 × 最高亲和（封顶 16），取多歌单最大值。
            val bonus = linkedMapOf<String, Double>()
            inputs.playlists.forEach { playlist ->
                val present = playlist.songs.flatMap { artistKeys(it.singer) }.distinct()
                if (present.size < 2) return@forEach
                present.forEach { key ->
                    val companion = present.filter { it != key }.maxOfOrNull { base[it] ?: 0.0 } ?: 0.0
                    if (companion >= CO_OCCURRENCE_MIN_COMPANION) {
                        bonus[key] = maxOf(bonus[key] ?: 0.0, minOf(companion * CO_OCCURRENCE_FACTOR, CO_OCCURRENCE_CAP))
                    }
                }
            }
            val favoriteArtists = favorite.keys
            val demoted = sortedSetOf<String>()
            val affinity = linkedMapOf<String, Double>()
            (base.keys + bonus.keys).forEach { key ->
                var value = (base[key] ?: 0.0) + (bonus[key] ?: 0.0)
                val dislikes = inputs.dislikeCountByArtist[key] ?: 0
                // 软降权：被不喜欢 ≥2 首、从未收藏、且无任何收听证据（「播满」在一期无完播数据，以零收听为代理）。
                if (dislikes >= 2 && key !in favoriteArtists && (statSeconds[key] ?: 0.0) <= 0.0) {
                    value *= DEMOTE_FACTOR
                    demoted += key
                }
                if (value > 0.0) affinity[key] = value
            }
            val catalog = linkedMapOf<String, MutableMap<String, ArtistSourceId>>()
            (inputs.favorites + libraryUniverse + inputs.history).forEach { song ->
                if (song.singerId.isBlank()) return@forEach
                val parts = artistParts(song.singer)
                val primary = parts.firstOrNull() ?: return@forEach
                val key = normalized(primary)
                if (key.isEmpty()) return@forEach
                val bySource = catalog.getOrPut(key) { linkedMapOf() }
                val source = song.source.lowercase(Locale.ROOT)
                bySource.putIfAbsent(source, ArtistSourceId(source, song.singerId, primary))
            }
            return TasteProfileV2(
                artistAffinity = affinity,
                artistNames = names.filterKeys { it in affinity.keys || it in catalog.keys },
                albumAffinity = albums,
                trackScores = trackScores,
                ownedIdentities = inputs.ownedIdentities,
                demotedArtists = demoted,
                artistCatalog = catalog.mapValues { it.value.values.toList() },
                totalPlays = inputs.totalPlays
            )
        }

        /** 今日推荐排序：统一打分 × 已拥有降权（0.35），稳定排序保持召回序处理同分。 */
        fun rankForToday(candidates: List<Song>, taste: TasteProfileV2, identityOf: (Song) -> String): List<Song> =
            candidates.sortedByDescending { song ->
                taste.songScore(song) * if (identityOf(song) in taste.ownedIdentities) OWNED_FACTOR else 1.0
            }

        /**
         * 同专辑 ≤3 整形：按入序保留每专辑前 3 首，其余（含被挤出的）按原序尾随，
         * 调用方取 limit 时先用整形内名额、不足再由尾随回填。空专辑不设限（无专辑信息 ≠ 同一张专辑）。
         */
        fun enforceAlbumCap(ranked: List<Song>, maxPerAlbum: Int = 3): List<Song> {
            if (maxPerAlbum <= 0) return ranked
            val counts = mutableMapOf<String, Int>()
            val kept = ArrayList<Song>(ranked.size)
            val overflow = ArrayList<Song>()
            ranked.forEach { song ->
                val album = normalized(song.album)
                if (album.isEmpty()) {
                    kept += song
                } else {
                    val count = counts.getOrDefault(album, 0)
                    if (count < maxPerAlbum) {
                        counts[album] = count + 1
                        kept += song
                    } else overflow += song
                }
            }
            return kept + overflow
        }

        /** 首抽概率：∝ weight + 探索地板（0.05 × 最大权重），长尾不清零。 */
        fun samplingProbabilities(weights: List<Double>): List<Double> {
            if (weights.isEmpty()) return emptyList()
            val max = weights.maxOrNull() ?: 0.0
            val floor = max * EXPLORE_FLOOR_RATIO
            val effective = weights.map { (it.coerceAtLeast(0.0)) + floor }
            val total = effective.sum()
            if (total <= 0.0) return weights.map { 1.0 / weights.size }
            return effective.map { it / total }
        }

        /** 按权重无放回抽样（概率同 [samplingProbabilities]，每抽走一个后重归一）。 */
        fun weightedSample(items: List<String>, weights: List<Double>, count: Int, random: Random): List<String> {
            if (items.isEmpty() || count <= 0) return emptyList()
            val pool = items.indices.toMutableList()
            val out = ArrayList<String>(minOf(count, items.size))
            while (out.size < count && pool.isNotEmpty()) {
                val probabilities = samplingProbabilities(pool.map { weights.getOrElse(it) { 0.0 } })
                var draw = random.nextDouble()
                var picked = pool.last()
                for (i in pool.indices) {
                    draw -= probabilities[i]
                    if (draw <= 0.0) { picked = pool[i]; break }
                }
                pool.remove(picked)
                out += items[picked]
            }
            return out
        }

        /**
         * 心动查询计划：种子歌手按亲和加权抽至多 2 个；当前歌歌手以「会话锚」加入候选，
         * 锚权重 = (其亲和 + 种子 Top1 权重) / 2（已是种子时不降权，取 max）。
         */
        fun heartQueryPlan(seedArtists: List<String>, taste: TasteProfileV2?, currentSong: Song?, random: Random): List<String> {
            val displays = linkedMapOf<String, String>()
            val weights = linkedMapOf<String, Double>()
            seedArtists.forEach { display ->
                val key = artistKey(display)
                if (key.isEmpty() || key in displays) return@forEach
                displays[key] = display
                val affinity = taste?.artistAffinity?.get(key) ?: 0.0
                weights[key] = if (affinity > 0.0) affinity else SEED_BASE_WEIGHT
            }
            if (displays.isEmpty()) return emptyList()
            if (currentSong != null) {
                val topSeed = weights.values.maxOrNull() ?: SEED_BASE_WEIGHT
                artistParts(currentSong.singer).forEach { part ->
                    val key = artistKey(part)
                    if (key.isEmpty()) return@forEach
                    val anchor = ((taste?.artistAffinity?.get(key) ?: 0.0) + topSeed) / 2.0
                    if (key in displays) {
                        weights[key] = maxOf(weights.getValue(key), anchor)
                    } else {
                        displays[key] = part
                        weights[key] = anchor
                    }
                }
            }
            val keys = displays.keys.toList()
            return weightedSample(keys, keys.map { weights.getValue(it) }, 2, random).map { displays.getValue(it) }
        }

        /** 与原曲的相似身份（标题 + 歌手集归一），与 HeartRecommendationPolicy.identity 同语义。 */
        fun identityOf(song: Song): String? {
            val title = normalized(song.name)
            val artists = artistKeys(song.singer).sorted()
            return if (title.isEmpty() || artists.isEmpty()) null else "$title|${artists.joinToString("+")}"
        }

        /**
         * 「相似歌曲」真选歌：与原曲共享至少一位歌手、身份不同、不在排除集，
         * 按统一打分 + 主歌手命中加成取最高分；同分保持候选池原序（稳定）。
         */
        fun pickSimilar(item: Song, pool: List<Song>, excludedKeys: Set<String>, taste: TasteProfileV2?): Song? {
            val itemIdentity = identityOf(item) ?: return null
            val itemArtists = artistKeys(item.singer).toSet()
            val itemPrimary = artistParts(item.singer).firstOrNull()?.let(::artistKey)
            return pool.asSequence()
                .filter { candidate ->
                    val id = identityOf(candidate)
                    id != null && id != itemIdentity && candidate.key() != item.key() &&
                        candidate.key() !in excludedKeys && artistKeys(candidate.singer).any { it in itemArtists }
                }
                .maxByOrNull { candidate ->
                    (taste?.songScore(candidate) ?: 0.0) +
                        if (itemPrimary != null && artistParts(candidate.singer).firstOrNull()?.let(::artistKey) == itemPrimary) 1.0 else 0.0
                }
        }
    }
}
