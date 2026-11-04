package xyz.cncnlp.soundwave.recommend

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import xyz.cncnlp.soundwave.HomeFeedCache
import xyz.cncnlp.soundwave.HomePageStateStore
import xyz.cncnlp.soundwave.PersonalRecommendations
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.TodayRecommendationScheduler
import xyz.cncnlp.soundwave.TodayRecommendationSlot

/**
 * r655 今日推荐即时重算：「换一批」（每天一次）与风格标签变更（不占换一批次数）都走这里，
 * 结果写入当前 slot 缓存并通过 [updates] 通知首页卡片与已打开的详情页。
 */
internal object TodayRecommendationRefresher {
    const val TODAY_DETAIL_ID = "today_recommendation"

    data class Update(val platform: String, val songs: List<Song>, val fingerprint: String = "")
    private val refreshMutex = Mutex()

    private val _updates = MutableSharedFlow<Update>(extraBufferCapacity = 4)
    val updates: SharedFlow<Update> = _updates.asSharedFlow()

    sealed interface Outcome {
        data class Done(val songs: List<Song>) : Outcome
        object QuotaUsed : Outcome
        object Empty : Outcome
    }

    /** Consume one daily replacement only for a still-visible song; publish removal before networking. */
    suspend fun dismiss(context: Context, platform: String, current: List<Song>, song: Song,
        nowMillis: Long = System.currentTimeMillis()): Outcome = refreshMutex.withLock {
        val app = context.applicationContext
        val request = TodayRecommendationScheduler.capture(app, platform)
        val identity = PersonalRecommendations.songIdentity(song)
        if (current.none { PersonalRecommendations.songIdentity(it) == identity }) return@withLock Outcome.Empty
        // A queued second tap may carry an older UI list. Do not resurrect a prior dismissal.
        val remaining = xyz.cncnlp.soundwave.DislikeStore.filter(app,
            current.filter { PersonalRecommendations.songIdentity(it) != identity })
        val day = java.time.Instant.ofEpochMilli(nowMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(app, "soundwave", 0,
            xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val key = "today-dismiss-v1:${request.profile?.exposureScope.orEmpty()}"
        val old = prefs.getString(key, "").orEmpty()
        val rejected = TodayDislikeQuota.rejected(old, day)
        val reserved = TodayDislikeQuota.reserve(old, day, identity) ?: return@withLock Outcome.QuotaUsed
        // Durable quota reservation precedes emitting state; failure never pretends removal happened.
        if (!prefs.edit().putString(key, reserved).commit()) return@withLock Outcome.Empty
        xyz.cncnlp.soundwave.DislikeStore.mark(app, song)
        publish(app, platform, remaining, nowMillis, request.fingerprint)
        val replacements = try {
            StyleWeightedRecommendation.generate(app, platform, request.profile, limit = 6,
                excludedKeys = current.map(PersonalRecommendations::songIdentity).toSet() + rejected + identity,
                nowMillis = nowMillis)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { emptyList() }
        currentCoroutineContext().ensureActive()
        if (!TodayRecommendationScheduler.isCurrent(app, request)) return@withLock Outcome.Empty
        val replacement = xyz.cncnlp.soundwave.DislikeStore.filter(app, replacements).firstOrNull { candidate ->
            remaining.none { PersonalRecommendations.songIdentity(it) == PersonalRecommendations.songIdentity(candidate) }
        }
        val result = if (replacement == null) remaining else remaining + replacement
        publish(app, platform, result, nowMillis, request.fingerprint)
        Outcome.Done(result)
    }

    /** 换一批：今日已用则直接返回 [Outcome.QuotaUsed]；生成失败不消耗次数。 */
    suspend fun refreshBatch(context: Context, platform: String, current: List<Song>, nowMillis: Long = System.currentTimeMillis()): Outcome = refreshMutex.withLock {
        val app = context.applicationContext
        if (!StylePreferenceStore.refreshAvailable(app, nowMillis)) return@withLock Outcome.QuotaUsed
        val request = TodayRecommendationScheduler.capture(app, platform, StylePreferenceStore.batchSeq(app) + 1)
        val exclude = current.map(PersonalRecommendations::songIdentity).toSet()
        val songs = StyleWeightedRecommendation.generate(
            app, platform, request.profile, excludedKeys = exclude,
            batch = request.batch, nowMillis = nowMillis
        )
        currentCoroutineContext().ensureActive()
        if (!TodayRecommendationScheduler.isCurrent(app, request) || songs.isEmpty()) return@withLock Outcome.Empty
        StylePreferenceStore.markRefreshUsed(app, nowMillis)
        publish(app, platform, songs, nowMillis, request.fingerprint)
        Outcome.Done(songs)
    }

    /** 风格标签变更后重算今日列表（不消耗换一批次数）。 */
    suspend fun regenerateForStyle(context: Context, platform: String, nowMillis: Long = System.currentTimeMillis()): Outcome {
        val app = context.applicationContext
        val request = TodayRecommendationScheduler.capture(app, platform)
        val songs = StyleWeightedRecommendation.generate(app, platform, request.profile, nowMillis = nowMillis)
        currentCoroutineContext().ensureActive()
        if (!TodayRecommendationScheduler.isCurrent(app, request)) return Outcome.Empty
        // Empty is authoritative for the new strict filter: clear old-class songs and notify the UI.
        publish(app, platform, songs, nowMillis, request.fingerprint)
        if (songs.isEmpty()) return Outcome.Empty
        return Outcome.Done(songs)
    }

    /** 已打开的今日推荐详情（同平台）换成新列表；其它详情返回 null 不动。 */
    fun applyToDetail(detail: Playlist?, update: Update): Playlist? = detail
        ?.takeIf { it.id == TODAY_DETAIL_ID && it.source.equals(update.platform, ignoreCase = true) }
        ?.copy(songs = update.songs.toMutableList(), count = update.songs.size, cover = xyz.cncnlp.soundwave.todayRecommendationCover(update.songs))

    private fun publish(context: Context, platform: String, songs: List<Song>, nowMillis: Long, fingerprint: String) {
        HomeFeedCache.saveDetail(context, TodayRecommendationSlot.cacheId(platform), platform, songs)
        StyleWeightedRecommendation.rememberPublished(context, platform, TodayRecommendationScheduler.savedProfile(context, platform), songs, nowMillis)
        // HomeFeedCache.saveDetail ignores empty lists. Never relabel its old raw songs as the new filter.
        HomePageStateStore.putStrings(context, mapOf(
            TodayRecommendationSlot.slotStateKey(platform) to if (songs.isEmpty()) "" else TodayRecommendationSlot.slotKey(nowMillis),
            TodayRecommendationSlot.fingerprintStateKey(platform) to if (songs.isEmpty()) "" else fingerprint
        ))
        _updates.tryEmit(Update(platform.trim().lowercase(Locale.ROOT), songs, fingerprint))
    }
}
