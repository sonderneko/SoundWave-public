package xyz.cncnlp.soundwave.recommend

import android.content.Context
import org.json.JSONArray
import xyz.cncnlp.soundwave.AccountDataIdentity
import xyz.cncnlp.soundwave.HomePageStateStore
import xyz.cncnlp.soundwave.ListenStats
import xyz.cncnlp.soundwave.TodayRecommendationSlot
import java.time.Instant
import java.util.Locale

/**
 * r655 今日推荐「风格推荐」与「换一批」的本地状态：
 *  - 每个平台最多 [MAX_TAGS] 个风格标签（按平台持久化，标签名来自该平台真实歌单分类接口）；
 *  - 「换一批」每个北京时间自然日（Asia/Shanghai，00:00 切日）只能用一次，持久化最近使用的日期。
 */
internal object StylePreferenceStore {
    const val MAX_TAGS = 3
    private const val KEY_TAGS = "today-style-tags:"
    private const val KEY_ENABLED = "today-style-enabled:"
    private const val KEY_REFRESH_DAY = "today-refresh-batch-day"
    private const val KEY_BATCH_SEQ = "today-refresh-batch-seq"
    private const val KEY_SYNC = "today-style-sync-v1"
    private const val ACCOUNT_PREFS_PREFIX = "today_style_preferences_v2_"

    enum class SaveResult { Changed, Unchanged, Failed }

    /** Include only explicitly saved platforms, so default empty state cannot erase a remote selection. */
    @Synchronized
    fun snapshot(context: Context, bundleJson: String = "{}"): String {
        val values = styleStrings(context)
        val selections = StylePreferenceSync.decode(values[KEY_SYNC].orEmpty()).toMutableMap()
        values.keys.filter { it.startsWith(KEY_TAGS) || it.startsWith(KEY_ENABLED) }.forEach { key ->
            val platform = key.substringAfter(':')
            if (platform !in selections) selections[platform] = StylePreferenceSync.Selection(
                decodeTags(values[KEY_TAGS + platform].orEmpty()), values[KEY_ENABLED + platform] != "false"
            )
        }
        return StylePreferenceSync.merge(StylePreferenceSync.encode(selections), bundleJson)
    }

    /** Apply remote values without minting a new edit/time/version or importing any unrelated home state. */
    @Synchronized
    fun applySynced(context: Context, incoming: String): String {
        val current = snapshot(context)
        val merged = StylePreferenceSync.merge(current, incoming)
        if (merged != current) check(writeSnapshot(context, merged)) { "风格设置写入未确认" }
        return merged
    }

    private fun writeSnapshot(context: Context, raw: String): Boolean {
        val values = linkedMapOf(KEY_SYNC to raw)
        StylePreferenceSync.decode(raw).forEach { (platform, selection) ->
            values[KEY_TAGS + platform] = encodeTags(selection.tags)
            values[KEY_ENABLED + platform] = selection.enabled.toString()
        }
        val owner = accountOwner(context)
        if (owner == null) return HomePageStateStore.putStringsConfirmed(context, values)
        val preferences = context.applicationContext.getSharedPreferences(accountPrefsName(owner), Context.MODE_PRIVATE)
        val editor = preferences.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        return editor.commit()
    }

    /** No daily edit quota: a confirmed clear/disable is an ordinary last-write-wins edit. */
    @Synchronized
    fun saveSelection(context: Context, platform: String, tags: List<String>, enabled: Boolean,
                      nowMillis: Long = System.currentTimeMillis()): SaveResult = try {
        val key = StylePreferenceSync.platformKey(platform)
        require(key.matches(Regex("[a-z0-9_-]{1,32}")))
        val selections = StylePreferenceSync.decode(snapshot(context)).toMutableMap()
        val old = selections[key]
        val next = StylePreferenceSync.edited(old, tags, enabled, nowMillis)
        if (next == (old ?: StylePreferenceSync.Selection())) SaveResult.Unchanged else {
            selections[key] = next
            if (writeSnapshot(context, StylePreferenceSync.encode(selections))) SaveResult.Changed else SaveResult.Failed
        }
    } catch (_: Exception) { SaveResult.Failed }

    /** 规范化：去空白、去重、最多 [max] 个，保持用户点选顺序。 */
    fun normalize(tags: List<String>, max: Int = MAX_TAGS): List<String> =
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(max)

    /** 点选 chip：已选则取消；未选且未满则追加；已满（[max] 个）时保持不变。 */
    fun toggle(current: List<String>, tag: String, max: Int = MAX_TAGS): List<String> {
        val clean = tag.trim()
        val base = normalize(current, max)
        if (clean.isEmpty()) return base
        return when {
            clean in base -> base - clean
            base.size >= max -> base
            else -> base + clean
        }
    }

    fun isAtCap(current: List<String>, max: Int = MAX_TAGS): Boolean = normalize(current, max).size >= max

    fun loadTags(context: Context, platform: String): List<String> =
        decodeTags(styleString(context, tagsKey(platform)))

    fun loadEnabled(context: Context, platform: String): Boolean =
        styleString(context, KEY_ENABLED + platform.trim().lowercase(Locale.ROOT)) != "false"

    fun saveEnabled(context: Context, platform: String, enabled: Boolean) {
        check(saveSelection(context, platform, loadTags(context, platform), enabled) != SaveResult.Failed)
    }

    fun activeTags(context: Context, platform: String): List<String> =
        if (loadEnabled(context, platform)) loadTags(context, platform) else emptyList()

    fun saveTags(context: Context, platform: String, tags: List<String>) {
        check(saveSelection(context, platform, tags, loadEnabled(context, platform)) != SaveResult.Failed)
    }

    internal fun tagsKey(platform: String): String = KEY_TAGS + platform.trim().lowercase(Locale.ROOT)

    /** Account style preferences are private, v2-owned data; unowned legacy state stays device-only. */
    private fun accountOwner(context: Context): String? =
        ListenStats.activeAccountUser(context)?.let(AccountDataIdentity::key)

    private fun accountPrefsName(owner: String): String = ACCOUNT_PREFS_PREFIX + owner

    private fun styleStrings(context: Context): Map<String, String> {
        val prefixes = setOf(KEY_TAGS, KEY_ENABLED, KEY_SYNC)
        val owner = accountOwner(context) ?: return HomePageStateStore.stringsWithPrefixes(context, prefixes)
        return context.applicationContext.getSharedPreferences(accountPrefsName(owner), Context.MODE_PRIVATE)
            .all.mapNotNull { (key, value) ->
                if (value is String && prefixes.any { key.startsWith(it) }) key to value else null
            }.toMap()
    }

    private fun styleString(context: Context, key: String): String {
        val owner = accountOwner(context) ?: return HomePageStateStore.getString(context, key)
        return context.applicationContext.getSharedPreferences(accountPrefsName(owner), Context.MODE_PRIVATE)
            .getString(key, "") ?: ""
    }

    internal fun encodeTags(tags: List<String>): String = JSONArray(normalize(tags)).toString()

    internal fun decodeTags(raw: String): List<String> = runCatching {
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        normalize((0 until array.length()).map { array.optString(it) })
    }.getOrDefault(emptyList())

    /** 北京时间自然日（yyyy-MM-dd）。 */
    fun shanghaiDay(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(TodayRecommendationSlot.ZONE).toLocalDate().toString()

    /** 一天一次门槛：最近使用日期不是今天（北京时间）即可再用。 */
    fun canRefresh(lastUsedDay: String, nowMillis: Long): Boolean = lastUsedDay != shanghaiDay(nowMillis)

    fun refreshAvailable(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        canRefresh(HomePageStateStore.getString(context, KEY_REFRESH_DAY), nowMillis)

    /** 记录今天已换过一批，并返回新的批次序号（用于打散标签歌单的选取）。 */
    fun markRefreshUsed(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val next = (HomePageStateStore.getString(context, KEY_BATCH_SEQ).toIntOrNull() ?: 0) + 1
        HomePageStateStore.putStrings(context, mapOf(KEY_REFRESH_DAY to shanghaiDay(nowMillis), KEY_BATCH_SEQ to next.toString()))
        return next
    }

    fun batchSeq(context: Context): Int = HomePageStateStore.getString(context, KEY_BATCH_SEQ).toIntOrNull() ?: 0
}
