package xyz.cncnlp.soundwave.recommend

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Portable style selections only: never catalogs, recommendation caches or refresh quotas. */
internal object StylePreferenceSync {
    data class Selection(
        val tags: List<String> = emptyList(),
        val enabled: Boolean = true,
        val modifiedAt: Long = 0L,
        val version: Long = 0L
    )

    fun platformKey(platform: String): String = platform.trim().lowercase(Locale.ROOT)
    private fun validPlatform(key: String): Boolean = key.matches(Regex("[a-z0-9_-]{1,32}"))

    fun decode(raw: String): Map<String, Selection> = runCatching {
        val root = JSONObject(raw)
        buildMap {
            root.keys().asSequence().take(64).forEach { rawKey ->
                val key = platformKey(rawKey)
                if (!validPlatform(key)) return@forEach
                val item = root.optJSONObject(rawKey) ?: return@forEach
                val tags = item.optJSONArray("tags") ?: return@forEach
                if (item.has("enabled") && item.opt("enabled") !is Boolean) return@forEach
                val names = (0 until minOf(tags.length(), 64)).mapNotNull { tags.opt(it) as? String }
                put(key, Selection(
                    tags = StylePreferenceStore.normalize(names.filter { it.length <= 128 }),
                    enabled = item.optBoolean("enabled", true),
                    modifiedAt = (item.opt("modifiedAt") as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L,
                    version = (item.opt("version") as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
                ))
            }
        }
    }.getOrDefault(emptyMap())

    fun encode(selections: Map<String, Selection>): String = JSONObject().apply {
        selections.toSortedMap().forEach { (key, value) ->
            if (validPlatform(key)) put(key, JSONObject()
                .put("tags", JSONArray(StylePreferenceStore.normalize(value.tags)))
                .put("enabled", value.enabled)
                .put("modifiedAt", value.modifiedAt.coerceAtLeast(0L))
                .put("version", value.version.coerceAtLeast(0L)))
        }
    }.toString()

    fun canonical(raw: String): String = encode(decode(raw))

    /** local is the initiating device. Missing timestamps and equal timestamps deliberately favor it. */
    fun merge(local: String, remote: String): String {
        val left = decode(local)
        val right = decode(remote)
        return encode((left.keys + right.keys).associateWith { key ->
            val a = left[key]
            val b = right[key]
            when {
                a == null -> requireNotNull(b)
                b == null -> a
                a.modifiedAt == 0L || b.modifiedAt == 0L -> a
                b.modifiedAt > a.modifiedAt -> b
                else -> a
            }
        })
    }

    /** One confirmed save is one revision, including an explicit clear or enabled-state change. */
    fun edited(previous: Selection?, tags: List<String>, enabled: Boolean, nowMillis: Long): Selection {
        val old = previous ?: Selection()
        val normalized = StylePreferenceStore.normalize(tags)
        if (normalized == old.tags && enabled == old.enabled) return old
        // Stay monotonic on this device, including clock rollback and multiple edits in one millisecond.
        val nextTime = if (old.modifiedAt == Long.MAX_VALUE) Long.MAX_VALUE else old.modifiedAt + 1L
        val nextVersion = if (old.version == Long.MAX_VALUE) Long.MAX_VALUE else old.version + 1L
        return Selection(normalized, enabled, maxOf(1L, nowMillis, nextTime), nextVersion)
    }
}
