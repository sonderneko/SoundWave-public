package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryDirect

/** 网易云使用其真实分类词；其他平台的 live app category catalog 用作搜索词，结果仍由各平台真实歌单搜索接口返回。 */
internal object PlaylistCategoryCatalog {
    /** Interleave groups so a rotating window spans language/style/theme/mood/scene rather than one category. */
    fun queryTerms(source: String, groups: List<Pair<String, List<String>>>): List<String> {
        val normalizedGroups = groups
        val terms = linkedSetOf<String>()
        val maxDepth = normalizedGroups.maxOfOrNull { it.second.size } ?: 0
        for (depth in 0 until maxDepth) {
            for ((_, items) in normalizedGroups) {
                items.getOrNull(depth)?.trim()?.takeIf(String::isNotEmpty)?.let(terms::add)
            }
        }
        return terms.toList()
    }

    /** Advance by one window per successful refresh; wraps through the complete real taxonomy. */
    internal fun rotatingWindow(terms: List<String>, cycle: Int, limit: Int = 8): List<String> {
        if (terms.isEmpty() || limit <= 0) return emptyList()
        val count = minOf(limit, terms.size)
        val start = Math.floorMod(cycle.toLong() * count, terms.size.toLong()).toInt()
        return List(count) { offset -> terms[(start + offset) % terms.size] }
    }
}

/** Merge pages by the originating platform and remote playlist identity; same IDs on different sources remain distinct. */
internal fun mergeCategoryPlaylistPages(
    existing: List<Playlist>,
    incoming: List<Playlist>,
    fallbackPlatform: String
): List<Playlist> {
    val seen = HashSet<String>()
    val merged = ArrayList<Playlist>(existing.size + incoming.size)
    for (playlist in existing + incoming) {
        val id = playlist.remoteId.ifBlank { playlist.id }.trim()
        if (id.isEmpty()) continue
        val source = canonicalSourceId(playlist.source.ifBlank { fallbackPlatform })
        if (seen.add("$source:$id")) merged.add(playlist)
    }
    return merged
}

internal data class CategoryPlaylistPageState(
    val page: Int,
    val playlists: List<Playlist>,
    val hasMore: Boolean,
    val loadingMore: Boolean
)

internal data class CategoryPlaylistRefreshResult(
    val displayedPlaylists: List<Playlist>,
    /** null means the request failed and the cache must not be touched; emptyList is a valid negative snapshot. */
    val cacheSnapshot: List<Playlist>?,
    val failed: Boolean
)

internal fun resolveCategoryPlaylistRefresh(
    outcome: PlaylistSearchOutcome,
    cachedPlaylists: List<Playlist>?,
    titleMatches: List<Playlist>,
    platform: String
): CategoryPlaylistRefreshResult {
    val fallback = cachedPlaylists.orEmpty().ifEmpty { titleMatches }
    return when (outcome) {
        is PlaylistSearchOutcome.Success -> {
            val fetched = mergeCategoryPlaylistPages(emptyList(), outcome.playlists, platform)
            CategoryPlaylistRefreshResult(
                displayedPlaylists = fetched.ifEmpty { fallback },
                cacheSnapshot = fetched,
                failed = false
            )
        }
        PlaylistSearchOutcome.Failure -> CategoryPlaylistRefreshResult(
            displayedPlaylists = fallback,
            cacheSnapshot = null,
            failed = true
        )
    }
}

/** Empty responses and failures (null) keep the retry cursor and list while releasing the load-more lock. */
internal fun finishCategoryPlaylistPageLoad(
    current: CategoryPlaylistPageState,
    incoming: List<Playlist>?,
    platform: String,
    nextPage: Int
): CategoryPlaylistPageState {
    if (incoming.isNullOrEmpty()) return current.copy(loadingMore = false)

    val merged = mergeCategoryPlaylistPages(current.playlists, incoming, platform)
    return current.copy(
        page = nextPage,
        playlists = merged,
        // Providers expose a page list, not an authoritative hasMore flag; duplicates must not hide later pages.
        hasMore = incoming.isNotEmpty(),
        loadingMore = false
    )
}

internal fun playlistCategoryGroupsRegistrySourceOrNull(platform: String): String? =
    platform.takeIf { it in MusicSourceRegistry.platformIds }

/** Fetch the true two-level NetEase taxonomy; other platforms keep the existing VPS catalog route. */
internal suspend fun fetchPlaylistCategoryGroups(platform: String): ApiClient.CatGroups? {
    val registrySource = playlistCategoryGroupsRegistrySourceOrNull(platform)
    return if (registrySource != null) {
        MusicSourceRegistry.platformSource(registrySource).playlistCategoryGroups()
    } else {
        ApiClient.plCatGroups(canonicalSourceId(platform))
    }
}

/** Multi-category platform playlist feed. Categories and playlist objects come from real provider-backed data. */
internal object PlaylistFeedRepository {
    private const val GROUP_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val EMPTY_GROUP_CACHE_TTL_MS = 10L * 60L * 1000L
    private const val CATEGORY_QUERIES_PER_REFRESH = 8
    private const val SEEN_HISTORY_KIND = "featured_playlists"

    suspend fun fetchFeatured(
        context: Context,
        source: String,
        cycle: Int,
        limit: Int = 10,
        tasteProfile: PersonalRecommendations.Profile = PersonalRecommendations.Profile(emptyList(), emptySet(), "", ""),
        forceNetwork: Boolean = false
    ): List<Playlist> {
        val platform = source.trim().lowercase(Locale.ROOT)
        if (platform !in MusicSourceRegistry.platformIds) return emptyList()
        return when (
            val result = fetchFeaturedResult(context, source, cycle, limit, tasteProfile, forceNetwork)
        ) {
            is HomeFeedRequestResult.Success -> result.value
            HomeFeedRequestResult.Failure -> emptyList()
        }
    }

    suspend fun fetchFeaturedResult(
        context: Context,
        source: String,
        cycle: Int,
        limit: Int = 10,
        tasteProfile: PersonalRecommendations.Profile = PersonalRecommendations.Profile(emptyList(), emptySet(), "", ""),
        forceNetwork: Boolean = false
    ): HomeFeedRequestResult<List<Playlist>> {
        val platform = source.trim().lowercase(Locale.ROOT)
        if (platform !in MusicSourceRegistry.platformIds) return HomeFeedRequestResult.Failure
        var hadRequestFailure = false
        val groupSnapshot = withContext(Dispatchers.IO) {
            HomePageStateStore.loadCategoryGroups(context, platform)
        }
        var groups = groupSnapshot?.groups
        val groupTtl = if (groups?.groups.orEmpty().isEmpty()) EMPTY_GROUP_CACHE_TTL_MS else GROUP_CACHE_TTL_MS
        val groupsFresh = groupSnapshot != null && System.currentTimeMillis() - groupSnapshot.savedAt in 0..groupTtl
        if (forceNetwork || !groupsFresh) {
            val fetchedGroupsResult = withContext(Dispatchers.IO) {
                runCatching { fetchPlaylistCategoryGroups(platform) }
            }
            val fetchedGroups = fetchedGroupsResult.getOrNull()
            if (fetchedGroups != null) {
                groups = fetchedGroups
                withContext(Dispatchers.IO) { HomePageStateStore.saveCategoryGroups(context, platform, fetchedGroups) }
            } else {
                hadRequestFailure = true
                if (groupSnapshot == null) {
                    withContext(Dispatchers.IO) { HomePageStateStore.saveCategoryGroups(context, platform, null) }
                }
            }
        }

        val allTerms = PlaylistCategoryCatalog.queryTerms(platform, groups?.groups.orEmpty())
        val queries = PlaylistCategoryCatalog.rotatingWindow(allTerms, cycle, CATEGORY_QUERIES_PER_REFRESH)
        val seenHistorySignature = tasteProfile.exposureScope.ifBlank {
            PersonalRecommendations.token("featured-playlists|local")
        }
        val recentlyRecommended = withContext(Dispatchers.IO) {
            PersonalRecommendations.loadSeen(context, SEEN_HISTORY_KIND, platform, seenHistorySignature)
        }
        val provider = MusicSourceRegistry.platformSource(platform)
        val (categoryResults, personalizedResult, officialResult) = coroutineScope {
            val categoryRequests = queries.map { term ->
                async(Dispatchers.IO) {
                    runCatching {
                        if (platform == "wy") fetchWyPlaylistCategoryDirect(term, 40)
                        else provider.searchPlaylists(term, platform, 40)
                    }.fold(
                        onSuccess = { HomeFeedRequestResult.Success(it) },
                        onFailure = { HomeFeedRequestResult.Failure }
                    )
                }
            }
            val personalizedRequest = async(Dispatchers.IO) {
                runCatching {
                    PersonalRecommendations.searchPlaylists(
                        platform = platform,
                        profile = tasteProfile,
                        excludedKeys = recentlyRecommended,
                        limit = 40,
                        queryLimit = 6
                    )
                }.fold(
                    onSuccess = { HomeFeedRequestResult.Success(it) },
                    onFailure = { HomeFeedRequestResult.Failure }
                )
            }
            val officialRequest = async(Dispatchers.IO) {
                runCatching { provider.recommendPlaylists(limit = 50) }.fold(
                    onSuccess = { HomeFeedRequestResult.Success(it) },
                    onFailure = { HomeFeedRequestResult.Failure }
                )
            }
            Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())
        }
        val allBatchResults = categoryResults + listOf(personalizedResult, officialResult)
        if (allBatchResults.any { it === HomeFeedRequestResult.Failure }) hadRequestFailure = true
        val categoryBatches = categoryResults.map { it.homeFeedListOrEmpty() }
        val personalizedBatch = personalizedResult.homeFeedListOrEmpty()
        val officialBatch = officialResult.homeFeedListOrEmpty()

        // Keep the user's weighted taste-search order, then interleave real platform category/search batches.
        val batches = listOf(personalizedBatch) + categoryBatches + listOf(officialBatch)
        val preserveBatchOrder = setOf(0)
        val fresh = selectDiverseFeaturedPlaylists(
            batches = batches,
            fallbackPlatform = platform,
            limit = limit,
            excludedPlaylistKeys = recentlyRecommended,
            excludedSongKeys = tasteProfile.excludedSongKeys,
            preserveBatchOrder = preserveBatchOrder
        )
        // Recent-exposure exclusion is best-effort: only backfill with already-seen real results if too few fresh IDs exist.
        val selected = if (fresh.size >= limit || limit <= 0) fresh else {
            val freshKeys = fresh.mapTo(mutableSetOf()) { PersonalRecommendations.playlistIdentity(it, platform) }
            val backfill = selectDiverseFeaturedPlaylists(
                batches = batches,
                fallbackPlatform = platform,
                limit = limit,
                excludedSongKeys = tasteProfile.excludedSongKeys,
                preserveBatchOrder = preserveBatchOrder
            ).filterNot { PersonalRecommendations.playlistIdentity(it, platform) in freshKeys }
            (fresh + backfill).take(limit)
        }
        if (selected.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                PersonalRecommendations.rememberSeen(
                    context,
                    SEEN_HISTORY_KIND,
                    platform,
                    seenHistorySignature,
                    selected.map { PersonalRecommendations.playlistIdentity(it, platform) }
                )
            }
        }
        return if (selected.isEmpty() && hadRequestFailure) {
            HomeFeedRequestResult.Failure
        } else {
            HomeFeedRequestResult.Success(selected)
        }
    }
}

/** Round-robin across real provider query batches, ranking each batch only by actual provider play counts. */
internal fun selectDiverseFeaturedPlaylists(
    batches: List<List<Playlist>>,
    fallbackPlatform: String,
    limit: Int = 10,
    excludedPlaylistKeys: Set<String> = emptySet(),
    excludedSongKeys: Set<String> = emptySet(),
    preserveBatchOrder: Set<Int> = emptySet()
): List<Playlist> {
    if (limit <= 0) return emptyList()
    val rankedBatches = batches.mapIndexed { batchIndex, batch ->
        val indexed = batch.withIndex()
        val ordered = if (batchIndex in preserveBatchOrder) indexed else indexed.sortedWith(
            compareByDescending<IndexedValue<Playlist>> { it.value.playCount > 0L }
                .thenByDescending { it.value.playCount }
                .thenBy { it.index }
        )
        ordered.map { it.value }
    }
    val candidates = ArrayList<Playlist>()
    val seenIds = HashSet<String>()
    val seenMetadata = HashSet<String>()
    var depth = 0
    while (depth < (rankedBatches.maxOfOrNull { it.size } ?: 0)) {
        for (batch in rankedBatches) {
            val playlist = batch.getOrNull(depth) ?: continue
            if (playlist.id.isBlank()) continue
            val identity = PersonalRecommendations.playlistIdentity(playlist, fallbackPlatform)
            if (identity in excludedPlaylistKeys) continue
            if (playlist.songs.isNotEmpty() && playlist.songs.all { PersonalRecommendations.songIdentity(it) in excludedSongKeys }) continue
            val metadataIdentity = "${playlist.name.trim().lowercase(Locale.ROOT)}|${playlist.author.trim().lowercase(Locale.ROOT)}"
            if (!seenIds.add(identity) || !seenMetadata.add(metadataIdentity)) continue
            candidates.add(playlist)
        }
        depth++
    }
    val out = ArrayList<Playlist>(limit)
    val selectedIds = HashSet<String>()
    val authorCounts = HashMap<String, Int>()
    // First pass limits any one creator to two curated slots when alternatives exist.
    for (playlist in candidates) {
        if (out.size >= limit) break
        val author = playlist.author.trim().lowercase(Locale.ROOT)
        if (author.isNotBlank() && (authorCounts[author] ?: 0) >= 2) continue
        out.add(playlist)
        selectedIds.add(PersonalRecommendations.playlistIdentity(playlist, fallbackPlatform))
        if (author.isNotBlank()) authorCounts[author] = (authorCounts[author] ?: 0) + 1
    }
    // If the catalog has too few creators, fill remaining slots with distinct real provider results.
    if (out.size < limit) for (playlist in candidates) {
        if (out.size >= limit) break
        val identity = PersonalRecommendations.playlistIdentity(playlist, fallbackPlatform)
        if (selectedIds.add(identity)) out.add(playlist)
    }
    return out
}
