package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.source.CatGroups

/** Restores home browse choices when Crossfade removes and later recreates HomePage. */
internal object HomePageStateStore {
    private val homeOwner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
    private const val PREFS = "home_page_view_state_v1"
    private const val KEY_CATEGORY_GROUPS = "playlist_category_groups_v3_"
    internal const val CATEGORY_GROUP_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    internal const val EMPTY_CATEGORY_GROUP_CACHE_TTL_MS = 10L * 60L * 1000L

    data class CategoryGroupsSnapshot(val groups: CatGroups?, val savedAt: Long)

    internal fun categoryGroupCacheTtl(groups: CatGroups?): Long =
        if (groups?.groups.orEmpty().isEmpty()) EMPTY_CATEGORY_GROUP_CACHE_TTL_MS else CATEGORY_GROUP_CACHE_TTL_MS

    /** Restricted caller projection; other home state must never enter account/WebDAV payloads. */
    internal fun stringsWithPrefixes(context: Context, prefixes: Set<String>): Map<String, String> =
        ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner).all
            .mapNotNull { (key, value) ->
                if (value is String && prefixes.any { key.startsWith(it) }) key to value else null
            }.toMap()

    internal fun putStringsConfirmed(context: Context, values: Map<String, String>): Boolean {
        val editor = ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner).edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        return editor.commit()
    }

    fun getString(context: Context, key: String, defaultValue: String = ""): String =
        runCatching {
            ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner)
                .getString(key, defaultValue) ?: defaultValue
        }.getOrDefault(defaultValue)

    fun putString(context: Context, key: String, value: String) {
        runCatching {
            ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner)
                .edit().putString(key, value).apply()
        }
    }

    /** Persist related view-state values in one editor transaction (e.g. a one-time schema migration). */
    fun putStrings(context: Context, values: Map<String, String>) {
        if (values.isEmpty()) return
        runCatching {
            val editor = ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner).edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }
    }

    fun loadCategoryGroups(context: Context, platform: String): CategoryGroupsSnapshot? {
        val raw = runCatching {
            ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner)
                .getString(KEY_CATEGORY_GROUPS + platform.lowercase(), null)
        }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("groups") ?: JSONArray()
            val groups = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val values = item.optJSONArray("items") ?: JSONArray()
                    val categories = buildList {
                        for (j in 0 until values.length()) values.optString(j).trim().takeIf(String::isNotEmpty)?.let(::add)
                    }
                    if (name.isNotEmpty() && categories.isNotEmpty()) add(name to categories)
                }
            }
            val catalog = groups.takeIf { it.isNotEmpty() }?.let { CatGroups(it) }
            CategoryGroupsSnapshot(groups = catalog, savedAt = root.optLong("ts"))
        }.getOrNull()
    }

    fun saveCategoryGroups(context: Context, platform: String, groups: CatGroups?) {
        runCatching {
            val array = JSONArray()
            groups?.groups.orEmpty().forEach { (name, values) ->
                val items = JSONArray()
                values.forEach(items::put)
                array.put(JSONObject().put("name", name).put("items", items))
            }
            val raw = JSONObject().put("groups", array).put("ts", System.currentTimeMillis()).toString()
            ContextPreferences.open(context, PREFS, Context.MODE_PRIVATE, homeOwner)
                .edit().putString(KEY_CATEGORY_GROUPS + platform.lowercase(), raw).apply()
        }
    }
}
