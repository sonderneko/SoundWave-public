package xyz.cncnlp.soundwave.recommend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.HomePageStateStore
import org.json.JSONArray
import xyz.cncnlp.soundwave.PersonalRecommendations
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.TodayRecommendationSlot
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random

/**
 * 平台分类是硬门槛：歌单仅用于召回，不作为每首曲目的分类证据。
 * 当前 Song 无官方 genre/instrumental 字段，只接受曲目自身明确文本，未知宁缺毋滥。
 * 这是可解释的本地文本筛选，不是官方标签或私有推荐模型。
 */
internal object StyleWeightedRecommendation {
    const val TAG_SHARE = 0.55
    private const val EXPANSION_ARTISTS = 3
    private const val EXPANSION_PER_ARTIST = 6
    private const val PLAYLISTS_PER_TAG = 2
    private const val PLAYLIST_CANDIDATES = 8
    private const val EXPOSURE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    private fun exposureKey(platform: String, profile: PersonalRecommendations.Profile?) =
        "today-exposure:${platform.trim().lowercase(Locale.ROOT)}:${profile?.exposureScope.orEmpty()}"

    private fun exposures(context: Context, platform: String, profile: PersonalRecommendations.Profile?, now: Long): List<Pair<String, Long>> = runCatching {
        val array = JSONArray(HomePageStateStore.getString(context, exposureKey(platform, profile)).ifBlank { "[]" })
        (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONArray(index) ?: return@mapNotNull null
            val time = row.optLong(1)
            row.optString(0).takeIf { it.isNotBlank() && now - time in 0..EXPOSURE_WINDOW_MS }?.let { it to time }
        }
    }.getOrDefault(emptyList())

    fun rememberPublished(context: Context, platform: String, profile: PersonalRecommendations.Profile?, songs: List<Song>, nowMillis: Long = System.currentTimeMillis()) {
        val rows = linkedMapOf<String, Long>()
        exposures(context, platform, profile, nowMillis).forEach { (key, time) -> rows[key] = time }
        songs.forEach { rows[PersonalRecommendations.songIdentity(it)] = nowMillis }
        val array = JSONArray()
        rows.entries.sortedByDescending { it.value }.take(360).forEach { array.put(JSONArray().put(it.key).put(it.value)) }
        HomePageStateStore.putString(context, exposureKey(platform, profile), array.toString())
    }

    /**
     * r657 第三轮 b（用户：今日推荐没有歌曲且很卡）：今日推荐是「时段快照」（每天 18:00 一次），校验键只含
     * 平台 / 风格开关 / 标签 / 换一批序号 / 账号作用域。旧实现把口味画像的 activitySignature（含实时累加的收听秒数、
     * 最近播放）也算进来——播放中画像每次重建都换键：首页卡片清空并重新联网生成、已打开的详情被置空。
     * 画像变化在下一个时段、换一批或改风格时生效。
     */
    fun fingerprint(context: Context, platform: String, profile: PersonalRecommendations.Profile?, batch: Int = StylePreferenceStore.batchSeq(context)): String =
        TodayRecommendationSlot.snapshotFingerprint(platform, profile?.exposureScope.orEmpty(), StylePreferenceStore.loadTags(context, platform), batch, StylePreferenceStore.loadEnabled(context, platform))

    suspend fun generate(
        context: Context,
        platform: String,
        profile: PersonalRecommendations.Profile?,
        limit: Int = 18,
        excludedKeys: Set<String> = emptySet(),
        batch: Int = StylePreferenceStore.batchSeq(context),
        nowMillis: Long = System.currentTimeMillis()
    ): List<Song> = withContext(Dispatchers.IO) {
        val source = platform.trim().lowercase(Locale.ROOT)
        if (source !in MusicSourceRegistry.platformIds || limit <= 0) return@withContext emptyList()
        val tags = StylePreferenceStore.activeTags(context, source)
        val exclusions = excludedKeys + xyz.cncnlp.soundwave.DislikeStore.identities(context) + exposures(context, source, profile, nowMillis).map { it.first }
        val hasTaste = profile != null && profile.queries.isNotEmpty()
        val tasteV2 = profile?.taste
        if (tags.isEmpty()) {
            val recalled = if (hasTaste) PersonalRecommendations.searchSongs(
                source, profile!!, exclusions, limit = if (tasteV2 != null) limit * 2 else limit
            ) else emptyList()
            // R 波一期：有统一画像时，口味搜索召回 + 歌手扩展池合并后按 songScore × 已拥有降权重排，
            // 再做同专辑 ≤3 整形与同歌手 ≤2 多样化；无 V2（旧画像）保持旧口径原样。
            val taste = if (tasteV2 != null) {
                val expansion = if (hasTaste) fetchArtistExpansion(source, tasteV2, exclusions + profile!!.excludedSongKeys) else emptyList()
                val combined = (recalled + expansion).distinctBy { PersonalRecommendations.songIdentity(it) }
                PersonalRecommendations.diversifyArtists(
                    TasteProfileV2.enforceAlbumCap(TasteProfileV2.rankForToday(combined, tasteV2, PersonalRecommendations::songIdentity)),
                    limit
                )
            } else recalled
            if (taste.size >= limit / 2) return@withContext taste
            // r657 第三轮 b（用户：今日推荐没有歌曲）：新用户 / 口味画像为空且未选风格时，旧实现直接返回空列表。
            // 冷启动改为从平台「流行 / 华语」分类歌单召回补齐（不是热榜，也不冒充风格筛选）。
            val coldSeed = "${TodayRecommendationSlot.slotKey(nowMillis)}|$source|$batch|cold".hashCode().toLong()
            val cold = fetchTagPools(source, COLD_START_TAGS, coldSeed, perTag = limit)
                .map { pool -> PersonalRecommendations.rankCandidates(pool.filter { acceptsFromCategory(it, source, emptyList()) }, profile) }
            val mixedCold = mix(taste, cold, limit * 3, excluded = exclusions + profile?.excludedSongKeys.orEmpty())
            return@withContext PersonalRecommendations.diversifyArtists(
                if (tasteV2 != null) TasteProfileV2.enforceAlbumCap(mixedCold) else mixedCold, limit)
        }
        val seed = "${TodayRecommendationSlot.slotKey(nowMillis)}|$source|$batch|${tags.joinToString(",")}".hashCode().toLong()
        val (taste, pools) = coroutineScope {
            val t = async { if (hasTaste) PersonalRecommendations.searchSongs(source, profile!!, exclusions, limit = limit * 2) else emptyList() }
            val p = async { fetchTagPools(source, tags, seed, perTag = limit) }
            t.await() to p.await()
        }
        val eligibleTaste = taste.filter { accepts(it, source, tags) }
        // r657 第三轮 b：分类歌单召回的曲目以「平台分类歌单成员」为风格证据（旧实现要求曲名/专辑名包含风格词，
        // 「摇滚」「民谣」等几乎全部被筛空 → 今日推荐为空）；纯音乐仍拒绝明确的人声 / 动漫冲突。
        val eligiblePools = pools.map { pool -> PersonalRecommendations.rankCandidates(pool.filter { acceptsFromCategory(it, source, tags) }, profile) }
        val mixed = mix(eligibleTaste, eligiblePools, limit * 3, excluded = exclusions + profile?.excludedSongKeys.orEmpty())
        val rankedMixed = PersonalRecommendations.rankCandidates(mixed, profile)
        // R 波一期：55% 混排比例不动；有统一画像时，已拥有歌曲稳定下沉（仍可回填凑数）并做同专辑 ≤3 整形。
        val shapedMixed = if (tasteV2 != null) {
            TasteProfileV2.enforceAlbumCap(
                rankedMixed.sortedBy { song -> if (PersonalRecommendations.songIdentity(song) in tasteV2.ownedIdentities) 1 else 0 }
            )
        } else rankedMixed
        PersonalRecommendations.diversifyArtists(shapedMixed, limit)
    }

    /**
     * R 波一期歌手扩展池：Top 亲和歌手按目标平台的真实 singerId 拉歌手作品，
     * 只收未排除（含近 30 天听过/收藏/不喜欢/已曝光）且歌手身份吻合的曲目——补搜索召回
     * 每词仅 30 条、同名混杂漏掉的同歌手纵深。无该平台 singerId 的歌手跳过，不臆造。
     */
    private suspend fun fetchArtistExpansion(
        platform: String,
        taste: TasteProfileV2,
        excluded: Set<String>
    ): List<Song> = coroutineScope {
        val provider = MusicSourceRegistry.platformSource(platform)
        taste.topArtists(EXPANSION_ARTISTS).mapNotNull { artistKey ->
            val entry = taste.artistCatalog[artistKey].orEmpty()
                .firstOrNull { it.source.equals(platform, ignoreCase = true) && it.singerId.isNotBlank() }
                ?: return@mapNotNull null
            async(Dispatchers.IO) {
                runCatching {
                    val detail = recommendationAttempt { provider.artistDetail(entry.singerId, entry.displayName) }
                    detail?.songs.orEmpty().filter { song ->
                        song.source.equals(platform, ignoreCase = true) && song.id.isNotBlank() && song.name.isNotBlank() &&
                            PersonalRecommendations.songIdentity(song) !in excluded &&
                            TasteProfileV2.artistKeys(song.singer).contains(artistKey)
                    }.sortedByDescending { taste.songScore(it) }.take(EXPANSION_PER_ARTIST)
                }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            }
        }.awaitAll().flatten()
    }

    /** OR across selected categories; instrumental-only additionally rejects explicit vocal/anime conflicts. */
    internal fun accepts(song: Song, platform: String, tags: List<String>): Boolean {
        if (!song.source.equals(platform, ignoreCase = true) || song.id.isBlank() || song.name.isBlank()) return false
        if (tags.isEmpty()) return true
        val title = song.name.lowercase(Locale.ROOT)
        val metadata = "$title ${song.album.lowercase(Locale.ROOT)}"
        return tags.any { raw ->
            val tag = raw.trim().lowercase(Locale.ROOT)
            if (tag in INSTRUMENTAL_TAGS) {
                // Positive evidence must be in the TRACK title, never inherited from the playlist/album.
                val positive = Regex("纯音乐|器乐|轻音乐|钢琴曲|\\binstrumental\\b|\\bpiano\\b|\\bbgm\\b").containsMatchIn(title)
                val conflict = Regex("人声|演唱|伴奏|卡拉|日漫|动漫|动画|主题曲|片头|片尾|\\b(vocal|vocals|karaoke|anime|animation|opening|ending)\\b").containsMatchIn(metadata)
                positive && !conflict
            } else tag.isNotBlank() && metadata.contains(tag)
        }
    }

    /** 冷启动召回用的平台通用分类（各平台分类广场均有）。 */
    internal val COLD_START_TAGS = listOf("流行", "华语")

    /** 分类歌单召回的曲目：歌单成员即风格证据；仅「纯音乐」类标签额外排除明确的人声 / 动漫冲突。 */
    internal fun acceptsFromCategory(song: Song, platform: String, tags: List<String>): Boolean {
        if (!song.source.equals(platform, ignoreCase = true) || song.id.isBlank() || song.name.isBlank()) return false
        val instrumentalOnly = tags.isNotEmpty() && tags.all { it.trim().lowercase(Locale.ROOT) in INSTRUMENTAL_TAGS }
        if (!instrumentalOnly) return true
        val metadata = "${song.name} ${song.album}".lowercase(Locale.ROOT)
        return !Regex("人声|演唱|伴奏|卡拉|日漫|动漫|动画|主题曲|片头|片尾|\\b(vocal|vocals|karaoke|anime|animation|opening|ending)\\b").containsMatchIn(metadata)
    }

    /**
     * 每个标签：取该平台分类/搜索的前 [PLAYLIST_CANDIDATES] 个歌单，按 seed 轮换挑 [PLAYLISTS_PER_TAG] 个，展开曲目后按 seed 打散。
     *
     * r658c D#4：只有网易有「分类广场」接口；其余平台此前直接拿标签词去搜歌单，搜索结果是模糊匹配
     * （「纯音乐」会搜出「抖音流行热歌」等），歌单成员又被当作风格证据整单接受 → 选了纯音乐仍推流行。
     * 现在：① 按平台补标签别名（如 QQ/咪咕「纯音乐」≈「轻音乐」）；② 搜索得到的歌单必须在歌单名里出现
     * 标签/别名才算该风格；③ 仍不足时用标签词搜歌曲并要求曲名/专辑含风格证据兜底；绝不回落通用热门。
     */
    private suspend fun fetchTagPools(platform: String, tags: List<String>, seed: Long, perTag: Int): List<List<Song>> = coroutineScope {
        val provider = MusicSourceRegistry.platformSource(platform)
        tags.map { tag ->
            async(Dispatchers.IO) {
                runCatching {
                    val tagSeed = seed xor tag.hashCode().toLong()
                    val square = recommendationAttempt { provider.playlistCategorySquare(tag, 20) }.orEmpty()
                    val terms = tagSearchTerms(platform, tag)
                    val playlists = square.ifEmpty {
                        terms.flatMap { term -> recommendationAttempt { provider.searchPlaylists(term, platform, 20) }.orEmpty() }
                            .filter { playlistMatchesTag(it.name, platform, tag) }
                    }
                    val fromPlaylists = pickPlaylists(playlists.filter { it.source.equals(platform, true) }, tagSeed, PLAYLISTS_PER_TAG).flatMap { pl ->
                        pl.songs.ifEmpty { recommendationAttempt { provider.playlistDetail(pl.remoteId.ifBlank { pl.id }) }.orEmpty() }
                    }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    val pool = fromPlaylists.ifEmpty {
                        // 兜底：标签词搜歌，只收曲名/专辑带风格证据的曲目（纯音乐另需排除人声冲突）。
                        terms.flatMap { term -> recommendationAttempt { provider.search(term, 1, 60) }.orEmpty() }
                            .filter { song -> accepts(song, platform, terms) }
                    }
                    pool.shuffled(Random(tagSeed)).take(maxOf(perTag * 2, 120))
                }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            }
        }.awaitAll()
    }

    private val INSTRUMENTAL_TAGS = setOf("纯音乐", "器乐", "instrumental", "轻音乐")

    /** 平台标签 → 实际可检索词。各平台分类名不同：QQ/咪咕常用「轻音乐」，酷狗/酷我「纯音乐」。 */
    internal fun tagSearchTerms(platform: String, tag: String): List<String> {
        val t = tag.trim()
        val aliases = when (t.lowercase(Locale.ROOT)) {
            "纯音乐", "器乐", "instrumental" -> when (platform.lowercase(Locale.ROOT)) {
                "tx", "mg" -> listOf("轻音乐", "纯音乐")
                else -> listOf("纯音乐", "轻音乐")
            }
            "轻音乐" -> listOf("轻音乐", "纯音乐")
            "说唱" -> listOf("说唱", "嘻哈")
            "嘻哈" -> listOf("嘻哈", "说唱")
            "电子" -> listOf("电子", "电音")
            "acg", "二次元" -> listOf(t, "动漫")
            else -> emptyList()
        }
        return (listOf(t) + aliases).filter { it.isNotBlank() }.distinct()
    }

    /** 搜索得到的歌单必须在歌单名里出现标签或其别名，才把成员当作该风格证据。 */
    internal fun playlistMatchesTag(name: String, platform: String, tag: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        val terms = tagSearchTerms(platform, tag).map { it.lowercase(Locale.ROOT) }
        val instrumental = terms.any { it in INSTRUMENTAL_TAGS }
        val extra = if (instrumental) listOf("钢琴", "piano", "bgm", "吉他", "小提琴", "古筝", "大提琴", "八音盒", "白噪音") else emptyList()
        if (terms.none { n.contains(it) } && extra.none { n.contains(it) }) return false
        // 纯音乐歌单名里明确是人声/流行合集的不算。
        if (instrumental && Regex("热歌|流行|翻唱|情歌|抖音|网红|dj|伤感|说唱|唱|歌手").containsMatchIn(n)) return false
        return true
    }

    internal fun pickPlaylists(playlists: List<Playlist>, seed: Long, count: Int): List<Playlist> {
        val candidates = playlists.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(PLAYLIST_CANDIDATES)
        if (candidates.size <= count) return candidates
        return candidates.shuffled(Random(seed)).take(count)
    }

    /** 标签曲目轮询合并：每个标签轮流出一首，保证 3 个标签都有代表。 */
    internal fun roundRobin(pools: List<List<Song>>): List<Song> {
        val out = ArrayList<Song>()
        val max = pools.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (pool in pools) if (i < pool.size) out.add(pool[i])
        return out
    }

    /**
     * 纯函数混合：标签曲目目标 ceil(limit × share)（18 首 → 10 首，约 56%），其余为口味画像；
     * 任一侧不足由另一侧补齐；按跨平台身份去重并剔除 [excluded]；结果按比例均匀穿插。
     */
    internal fun mix(
        taste: List<Song>,
        tagPools: List<List<Song>>,
        limit: Int,
        share: Double = TAG_SHARE,
        excluded: Set<String> = emptySet(),
        identity: (Song) -> String = PersonalRecommendations::songIdentity
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val seen = HashSet(excluded)
        val tagQueue = roundRobin(tagPools).filter { seen.add(identity(it)) }
        val tasteQueue = taste.filter { seen.add(identity(it)) }
        val tagTarget = if (tasteQueue.isEmpty()) limit else ceil(limit * share).toInt().coerceIn(0, limit)
        val tagPicked = tagQueue.take(tagTarget).toMutableList()
        val tastePicked = tasteQueue.take(limit - tagPicked.size).toMutableList()
        if (tagPicked.size + tastePicked.size < limit) {
            tagPicked += tagQueue.drop(tagPicked.size).take(limit - tagPicked.size - tastePicked.size)
        }
        val total = tagPicked.size + tastePicked.size
        val out = ArrayList<Song>(total)
        var ti = 0
        var pi = 0
        for (i in 0 until total) {
            // 让标签曲目在列表里按比例分布，而不是扎堆在前或后。
            val wantTag = pi >= tastePicked.size ||
                (ti < tagPicked.size && (ti + 1).toDouble() / tagPicked.size <= (i + 1).toDouble() / total + 1e-9)
            if (wantTag && ti < tagPicked.size) out.add(tagPicked[ti++]) else out.add(tastePicked[pi++])
        }
        return out
    }
}
