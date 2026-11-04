package xyz.cncnlp.soundwave

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * 今日推荐「推送时段」：每天北京时间 18:00 刷新一次。
 * slot 日期 = 最近一次已过去的 18:00（Asia/Shanghai）边界所在日期；18:00 前仍属于昨天的 slot。
 * 推荐列表只在 slot 变化时重新生成，其余时间展示该 slot 的缓存。
 */
object TodayRecommendationSlot {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    const val REFRESH_HOUR = 18

    fun slotDate(nowMillis: Long): LocalDate {
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZONE)
        return if (now.hour >= REFRESH_HOUR) now.toLocalDate() else now.toLocalDate().minusDays(1)
    }

    fun slotKey(nowMillis: Long): String = slotDate(nowMillis).toString()

    /** 下一个 18:00（Asia/Shanghai）边界的毫秒时间戳（严格晚于 now）。 */
    fun nextBoundaryMillis(nowMillis: Long): Long {
        val next: ZonedDateTime = slotDate(nowMillis).plusDays(1).atTime(REFRESH_HOUR, 0).atZone(ZONE)
        return next.toInstant().toEpochMilli()
    }

    fun cacheId(platform: String): String = "today_recommendation_slot:${platform.trim().lowercase(Locale.ROOT)}"
    fun slotStateKey(platform: String): String = "today-recommendation-slot:${platform.trim().lowercase(Locale.ROOT)}"
    fun fingerprintStateKey(platform: String): String = "today-recommendation-fingerprint:${platform.trim().lowercase(Locale.ROOT)}"

    /** 时段快照校验键（见 StyleWeightedRecommendation.fingerprint）：不含实时变化的口味画像。 */
    internal fun snapshotFingerprint(platform: String, exposureScope: String, tags: List<String>, batch: Int = 0, enabled: Boolean = true): String =
        PersonalRecommendations.token(org.json.JSONArray().apply {
            put("today-snapshot-v2"); put(platform.trim().lowercase(Locale.ROOT)); put(enabled); put(batch)
            put(org.json.JSONArray(tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()))
            put(exposureScope)
        }.toString())

    /** Include all inputs affecting ranking/gates; old slot-only caches must not validate. */
    internal fun fingerprint(
        platform: String,
        profile: PersonalRecommendations.Profile?,
        tags: List<String>,
        batch: Int = 0,
        enabled: Boolean = true
    ): String = PersonalRecommendations.token(org.json.JSONArray().apply {
        put("strict-track-evidence-v1"); put(platform.trim().lowercase(Locale.ROOT)); put(enabled); put(batch)
        put(org.json.JSONArray(tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()))
        put(profile?.signature.orEmpty()); put(profile?.activitySignature.orEmpty()); put(profile?.exposureScope.orEmpty())
        put(org.json.JSONArray(profile?.excludedSongKeys.orEmpty().sorted()))
        put(org.json.JSONArray(profile?.queries.orEmpty().map { q ->
            org.json.JSONArray().put(q.kind.name).put(q.term).put(q.weight).put(org.json.JSONArray(q.expectedArtists.sorted())).toString()
        }.sorted()))
    }.toString())
}
