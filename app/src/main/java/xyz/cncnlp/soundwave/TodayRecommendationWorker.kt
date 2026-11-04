package xyz.cncnlp.soundwave

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.recommend.StylePreferenceStore
import xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation
import java.util.concurrent.TimeUnit

/**
 * r654：今日推荐后台刷新。每天北京时间 18:00（[TodayRecommendationSlot.REFRESH_HOUR]，Asia/Shanghai）由 WorkManager
 * 在后台按最近一次前台保存的口味画像生成新一期 18 首并写入 slot 缓存，用户打开首页即可直接看到，不必等前台现算。
 * 前台每次进入新 slot 时调用 [schedule] 记录当前平台与画像并把一次性任务排到下一个 18:00；任务执行后自动排下一天。
 */
internal object TodayRecommendationScheduler {
    private const val UNIQUE_WORK = "today-recommendation-18"
    private const val KEY_PLATFORM = "today-recommendation-bg-platform"
    private const val KEY_PROFILE = "today-recommendation-bg-profile"
    private const val KEY_REVISION = "today-recommendation-bg-revision"

    private fun profileKey(platform: String) = "$KEY_PROFILE:${platform.trim().lowercase(java.util.Locale.ROOT)}"

    private fun register(context: Context, platform: String, profile: PersonalRecommendations.Profile?) {
        val source = platform.trim().lowercase(java.util.Locale.ROOT)
        val encoded = profile?.let(::encode) ?: HomePageStateStore.getString(context, profileKey(source))
        if (savedPlatform(context) != source || HomePageStateStore.getString(context, profileKey(source)) != encoded) {
            HomePageStateStore.putString(context, KEY_REVISION, java.util.UUID.randomUUID().toString())
        }
        HomePageStateStore.putStrings(context, mapOf(KEY_PLATFORM to source, profileKey(source) to encoded))
    }

    fun schedule(context: Context, platform: String, profile: PersonalRecommendations.Profile) {
        val app = context.applicationContext
        register(app, platform, profile)
        enqueueNext(app)
    }

    /** r655：用户选了风格标签（即使暂无口味画像）也登记平台并排 18:00 后台刷新。 */
    fun scheduleForStyle(context: Context, platform: String) {
        val app = context.applicationContext
        register(app, platform, null)
        enqueueNext(app)
    }

    fun enqueueNext(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val delay = (TodayRecommendationSlot.nextBoundaryMillis(nowMillis) - nowMillis).coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<TodayRecommendationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request) }
    }

    internal fun savedPlatform(context: Context): String = HomePageStateStore.getString(context, KEY_PLATFORM)
    internal fun savedProfile(context: Context, platform: String = savedPlatform(context)): PersonalRecommendations.Profile? =
        decode(HomePageStateStore.getString(context, profileKey(platform)))

    internal data class Request(val platform: String, val profile: PersonalRecommendations.Profile?, val fingerprint: String, val revision: String, val batch: Int, val stateBatch: Int)

    internal fun capture(context: Context, platform: String, batch: Int = StylePreferenceStore.batchSeq(context)): Request {
        val source = platform.trim().lowercase(java.util.Locale.ROOT)
        val profile = savedProfile(context, source)
        return Request(source, profile, StyleWeightedRecommendation.fingerprint(context, source, profile, batch), HomePageStateStore.getString(context, KEY_REVISION), batch, StylePreferenceStore.batchSeq(context))
    }

    internal fun isCurrent(context: Context, request: Request): Boolean =
        savedPlatform(context) == request.platform && StylePreferenceStore.batchSeq(context) == request.stateBatch && HomePageStateStore.getString(context, KEY_REVISION) == request.revision &&
            StyleWeightedRecommendation.fingerprint(context, request.platform, savedProfile(context, request.platform), request.batch) == request.fingerprint

    internal fun encode(profile: PersonalRecommendations.Profile): String = JSONObject()
        .put("signature", profile.signature)
        .put("activity", profile.activitySignature)
        .put("scope", profile.exposureScope)
        .put("excluded", JSONArray(profile.excludedSongKeys.sorted()))
        .put("queries", JSONArray().apply {
            profile.queries.forEach { put(JSONObject().put("term", it.term).put("weight", it.weight).put("kind", it.kind.name).put("artists", JSONArray(it.expectedArtists.sorted()))) }
        })
        .apply {
            // R 波一期：统一画像随画像一同序列化，后台 worker 与前台共用同一份 TasteProfileV2。
            profile.taste?.let { taste -> put("v2", encodeTaste(taste)) }
        }
        .toString()

    private fun encodeTaste(taste: xyz.cncnlp.soundwave.recommend.TasteProfileV2): JSONObject = JSONObject()
        .put("artists", JSONArray().apply {
            taste.artistAffinity.forEach { (key, score) ->
                put(JSONObject().put("k", key).put("n", taste.artistNames[key] ?: key).put("s", score))
            }
        })
        .put("albums", JSONArray().apply {
            taste.albumAffinity.forEach { (key, score) -> put(JSONObject().put("k", key).put("s", score)) }
        })
        .put("tracks", JSONArray().apply {
            taste.trackScores.entries.take(80).forEach { (key, score) -> put(JSONObject().put("k", key).put("s", score)) }
        })
        .put("owned", JSONArray(taste.ownedIdentities.take(800)))
        .put("demoted", JSONArray(taste.demotedArtists.toList()))
        .put("catalog", JSONArray().apply {
            taste.artistCatalog.forEach { (key, entries) ->
                entries.forEach { entry ->
                    put(JSONObject().put("k", key).put("src", entry.source).put("id", entry.singerId).put("n", entry.displayName))
                }
            }
        })
        .put("plays", taste.totalPlays)

    private fun decodeTaste(raw: JSONObject?): xyz.cncnlp.soundwave.recommend.TasteProfileV2? {
        val v2 = raw ?: return null
        val affinity = linkedMapOf<String, Double>()
        val names = linkedMapOf<String, String>()
        val artists = v2.optJSONArray("artists") ?: JSONArray()
        for (i in 0 until artists.length()) {
            val row = artists.optJSONObject(i) ?: continue
            val key = row.optString("k")
            if (key.isBlank()) continue
            affinity[key] = row.optDouble("s", 0.0)
            names[key] = row.optString("n").ifBlank { key }
        }
        val albums = linkedMapOf<String, Double>()
        val albumRows = v2.optJSONArray("albums") ?: JSONArray()
        for (i in 0 until albumRows.length()) {
            val row = albumRows.optJSONObject(i) ?: continue
            if (row.optString("k").isNotBlank()) albums[row.optString("k")] = row.optDouble("s", 0.0)
        }
        val tracks = linkedMapOf<String, Double>()
        val trackRows = v2.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until trackRows.length()) {
            val row = trackRows.optJSONObject(i) ?: continue
            if (row.optString("k").isNotBlank()) tracks[row.optString("k")] = row.optDouble("s", 0.0)
        }
        fun stringSet(array: JSONArray?): Set<String> {
            val rows = array ?: return emptySet()
            return (0 until rows.length()).map { rows.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
        val catalog = linkedMapOf<String, MutableList<xyz.cncnlp.soundwave.recommend.TasteProfileV2.ArtistSourceId>>()
        val catalogRows = v2.optJSONArray("catalog") ?: JSONArray()
        for (i in 0 until catalogRows.length()) {
            val row = catalogRows.optJSONObject(i) ?: continue
            val key = row.optString("k")
            val id = row.optString("id")
            if (key.isBlank() || id.isBlank()) continue
            catalog.getOrPut(key) { mutableListOf() } += xyz.cncnlp.soundwave.recommend.TasteProfileV2.ArtistSourceId(
                row.optString("src"), id, row.optString("n")
            )
        }
        return xyz.cncnlp.soundwave.recommend.TasteProfileV2(
            artistAffinity = affinity,
            artistNames = names,
            albumAffinity = albums,
            trackScores = tracks,
            ownedIdentities = stringSet(v2.optJSONArray("owned")),
            demotedArtists = stringSet(v2.optJSONArray("demoted")),
            artistCatalog = catalog.mapValues { it.value.toList() },
            totalPlays = v2.optInt("plays", 0)
        )
    }

    internal fun decode(raw: String): PersonalRecommendations.Profile? = runCatching {
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        val qs = o.optJSONArray("queries") ?: JSONArray()
        val queries = (0 until qs.length()).mapNotNull { i ->
            val q = qs.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { PersonalRecommendations.QueryKind.valueOf(q.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val artists = q.optJSONArray("artists") ?: JSONArray()
            PersonalRecommendations.TasteQuery(q.optString("term"), q.optInt("weight"), kind, (0 until artists.length()).map { artists.optString(it) }.filter { it.isNotBlank() }.toSet())
        }.filter { it.term.isNotBlank() }
        val ex = o.optJSONArray("excluded") ?: JSONArray()
        PersonalRecommendations.Profile(
            queries = queries,
            excludedSongKeys = (0 until ex.length()).map { ex.optString(it) }.filter { it.isNotBlank() }.toSet(),
            signature = o.optString("signature"),
            activitySignature = o.optString("activity"),
            exposureScope = o.optString("scope"),
            taste = decodeTaste(o.optJSONObject("v2"))
        )
    }.getOrNull()
}

class TodayRecommendationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        try {
            val platform = TodayRecommendationScheduler.savedPlatform(ctx)
            val request = TodayRecommendationScheduler.capture(ctx, platform)
            val profile = request.profile
            val hasStyle = platform.isNotBlank() && StylePreferenceStore.activeTags(ctx, platform).isNotEmpty()
            if (platform.isBlank() || ((profile == null || profile.queries.isEmpty()) && !hasStyle)) return@withContext Result.success()
            val slot = TodayRecommendationSlot.slotKey(System.currentTimeMillis())
            // 已由前台生成过本 slot 就不重复请求。
            if (HomePageStateStore.getString(ctx, TodayRecommendationSlot.slotStateKey(platform)) == slot &&
                HomePageStateStore.getString(ctx, TodayRecommendationSlot.fingerprintStateKey(platform)) == request.fingerprint) return@withContext Result.success()
            ApiClient.setBase(DownloadScheduler.restoreServiceBase(ctx))
            // r655：风格标签加权（未选标签时等同原口味画像生成）。
            val generated = StyleWeightedRecommendation.generate(ctx, platform, profile, limit = 18)
            currentCoroutineContext().ensureActive()
            if (!TodayRecommendationScheduler.isCurrent(ctx, request)) return@withContext Result.success()
            if (generated.isEmpty()) return@withContext if (runAttemptCount < 3) Result.retry() else Result.success()
            HomeFeedCache.saveDetail(ctx, TodayRecommendationSlot.cacheId(platform), platform, generated)
            StyleWeightedRecommendation.rememberPublished(ctx, platform, profile, generated)
            HomePageStateStore.putStrings(ctx, mapOf(TodayRecommendationSlot.slotStateKey(platform) to slot, TodayRecommendationSlot.fingerprintStateKey(platform) to request.fingerprint))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } finally {
            if (!isStopped) TodayRecommendationScheduler.enqueueNext(ctx)
        }
    }
}
