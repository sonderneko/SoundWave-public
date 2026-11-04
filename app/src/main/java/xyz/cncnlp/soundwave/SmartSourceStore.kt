package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sollin 风格智能换源配置：
 * - 跨平台顺序 / 平台绑脚本链路（r656 起取链顺序只由平台链路推导；激活/备用仅为旧数据兼容存储，不参与取链）
 * - 阶段顺序：原平台重试 / 音质降级 / 跨平台 / 跨脚本
 * - 128k 兜底、记住跨平台结果
 */
object SmartSourceStore {
    private const val PREF = "smart_source_v1"
    private const val PROFILE_PREF_PREFIX = "smart_source_profile_"
    private const val K_PLAT_ORDER = "plat_order"
    private const val K_PLAT_SCRIPT = "plat_script"
    private const val K_ACTIVE = "active_script"
    private const val K_BACKUP = "backup_order"
    private const val K_STAGES = "stage_order"
    private const val K_FALLBACK_128 = "fallback_128k"
    private const val K_REMEMBER = "remember_cross"
    private const val K_REMEMBERED = "remembered_map"
    private const val K_ENABLED = "smart_enabled"
    private const val K_PLAT_PLAY_Q = "plat_play_quality"
    private const val K_PLAT_DOWN_Q = "plat_down_quality"
    private const val K_STAGES_VERSION = "stage_order_version"
    /** 跨平台记忆直链的复用窗口：平台直链时效极短，过期即视为未命中并在写入时清理。 */
    private const val REMEMBER_TTL_MS = 20 * 60 * 1000L

    const val STAGE_ORIGIN = "origin"
    const val STAGE_QUALITY = "quality"
    const val STAGE_CROSS_PLAT = "cross_plat"
    const val STAGE_CROSS_SCRIPT = "cross_script"

    // 默认：先在同平台换外部脚本，再进行跨平台搜索/候选匹配；避免某个脚本慢链耗尽全局预算。
    val DEFAULT_STAGES = listOf(STAGE_ORIGIN, STAGE_QUALITY, STAGE_CROSS_SCRIPT, STAGE_CROSS_PLAT)
    val STAGE_LABELS = mapOf(
        STAGE_ORIGIN to "原平台重试",
        STAGE_QUALITY to "按音质依次降级",
        STAGE_CROSS_PLAT to "跨平台换源",
        STAGE_CROSS_SCRIPT to "跨脚本换源"
    )

    private val DEFAULT_PLATS = listOf("wy", "kw", "kg", "tx", "mg")

    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = xyz.cncnlp.soundwave.ContextPreferences.open(ctx.applicationContext, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        }
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("SmartSourceStore not init")

    /** Resolve each explicitly addressed profile directly; never reuse the legacy singleton prefs. */
    private fun profilePrefs(ctx: Context, profileId: ProfileId): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("$PROFILE_PREF_PREFIX${profileId.value}", 0)

    data class PlatItem(val code: String, var enabled: Boolean = true)
    data class BackupItem(val id: String, var enabled: Boolean = true)
    data class Remembered(
        val id: String,
        val source: String,
        val url: String,
        val quality: String,
        val name: String = "",
        val singer: String = ""
    )

    fun platformOrder(): MutableList<PlatItem> = platformOrder(p())

    fun platformOrder(ctx: Context, profileId: ProfileId): MutableList<PlatItem> =
        platformOrder(profilePrefs(ctx, profileId))

    private fun platformOrder(preferences: SharedPreferences): MutableList<PlatItem> {
        val raw = preferences.getString(K_PLAT_ORDER, null)
        if (raw.isNullOrBlank()) {
            return DEFAULT_PLATS.map { PlatItem(it, true) }.toMutableList()
        }
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<PlatItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optString("c").ifEmpty { o.optString("code") }
                if (c.isNotEmpty()) out.add(PlatItem(c, o.optBoolean("e", true)))
            }
            for (d in DEFAULT_PLATS) {
                if (out.none { it.code == d }) out.add(PlatItem(d, true))
            }
            if (out.isEmpty()) DEFAULT_PLATS.map { PlatItem(it, true) }.toMutableList() else out
        } catch (_: Exception) {
            DEFAULT_PLATS.map { PlatItem(it, true) }.toMutableList()
        }
    }

    fun savePlatformOrder(list: List<PlatItem>) = savePlatformOrder(p(), list)

    fun savePlatformOrder(ctx: Context, profileId: ProfileId, list: List<PlatItem>) =
        savePlatformOrder(profilePrefs(ctx, profileId), list)

    private fun savePlatformOrder(preferences: SharedPreferences, list: List<PlatItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("c", it.code).put("e", it.enabled)) }
        preferences.edit().putString(K_PLAT_ORDER, arr.toString()).apply()
    }

    fun resetPlatformOrder() {
        savePlatformOrder(DEFAULT_PLATS.map { PlatItem(it, true) })
    }

    fun resetPlatformOrder(ctx: Context, profileId: ProfileId) {
        savePlatformOrder(ctx, profileId, DEFAULT_PLATS.map { PlatItem(it, true) })
    }

    fun platformScripts(): MutableMap<String, String> = platformScripts(p())

    fun platformScripts(ctx: Context, profileId: ProfileId): MutableMap<String, String> =
        platformScripts(profilePrefs(ctx, profileId))

    private fun platformScripts(preferences: SharedPreferences): MutableMap<String, String> {
        val raw = preferences.getString(K_PLAT_SCRIPT, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k) }
            m
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun setPlatformScript(code: String, scriptId: String) = setPlatformScript(p(), code, scriptId)

    fun setPlatformScript(ctx: Context, profileId: ProfileId, code: String, scriptId: String) =
        setPlatformScript(profilePrefs(ctx, profileId), code, scriptId)

    private fun setPlatformScript(preferences: SharedPreferences, code: String, scriptId: String) {
        val m = platformScripts(preferences)
        if (scriptId.isEmpty()) m.remove(code) else m[code] = scriptId
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        preferences.edit().putString(K_PLAT_SCRIPT, o.toString()).apply()
    }

    fun platformScript(code: String): String = platformScript(p(), code)

    fun platformScript(ctx: Context, profileId: ProfileId, code: String): String =
        platformScript(profilePrefs(ctx, profileId), code)

    private fun platformScript(preferences: SharedPreferences, code: String): String =
        platformScripts(preferences)[code].orEmpty()

    /** 每个平台独立脚本播放顺序；旧版单脚本绑定作为链路第一项兼容。 */
    fun platformScriptOrder(code: String): MutableList<String> = platformScriptOrder(p(), code)

    fun platformScriptOrder(ctx: Context, profileId: ProfileId, code: String): MutableList<String> =
        platformScriptOrder(profilePrefs(ctx, profileId), code)

    private fun platformScriptOrder(preferences: SharedPreferences, code: String): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val root = JSONObject(preferences.getString("platform_script_order_v2", "{}") ?: "{}")
            val arr = root.optJSONArray(code)
            if (arr != null) for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank() && id !in out) out.add(id)
            }
        } catch (_: Exception) {}
        val legacy = platformScript(preferences, code)
        if (legacy.isNotBlank() && legacy !in out) out.add(0, legacy)
        return out
    }

    fun platformEligibleScripts(scripts: List<Script>): List<Script> =
        scripts.filter { it.enabled && it.code.isNotBlank() }

    fun filterPlatformScriptOrder(ids: List<String>, scripts: List<Script>): List<String> {
        val eligibleIds = platformEligibleScripts(scripts).mapTo(mutableSetOf()) { it.id }
        return ids.filter { it in eligibleIds }.distinct()
    }

    fun savePlatformScriptOrder(code: String, ids: List<String>) = savePlatformScriptOrder(p(), code, ids)

    fun savePlatformScriptOrder(ctx: Context, profileId: ProfileId, code: String, ids: List<String>) =
        savePlatformScriptOrder(profilePrefs(ctx, profileId), code, ids)

    private fun savePlatformScriptOrder(preferences: SharedPreferences, code: String, ids: List<String>) {
        val root = try { JSONObject(preferences.getString("platform_script_order_v2", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val clean = ids.filter { it.isNotBlank() }.distinct()
        root.put(code, JSONArray(clean))
        preferences.edit().putString("platform_script_order_v2", root.toString()).apply()
        setPlatformScript(preferences, code, clean.firstOrNull().orEmpty())
    }

    fun activeScriptId(): String = activeScriptId(p())

    fun activeScriptId(ctx: Context, profileId: ProfileId): String =
        activeScriptId(profilePrefs(ctx, profileId))

    private fun activeScriptId(preferences: SharedPreferences): String =
        preferences.getString(K_ACTIVE, "").orEmpty()

    fun setActiveScriptId(id: String) = setActiveScriptId(p(), id)

    fun setActiveScriptId(ctx: Context, profileId: ProfileId, id: String) =
        setActiveScriptId(profilePrefs(ctx, profileId), id)

    private fun setActiveScriptId(preferences: SharedPreferences, id: String) {
        preferences.edit().putString(K_ACTIVE, id).apply()
        if (id.isNotEmpty()) {
            val backs = backupOrder(preferences).filterNot { it.id == id }
            saveBackupOrder(preferences, backs)
        }
    }

    fun backupOrder(): MutableList<BackupItem> = backupOrder(p())

    fun backupOrder(ctx: Context, profileId: ProfileId): MutableList<BackupItem> =
        backupOrder(profilePrefs(ctx, profileId))

    private fun backupOrder(preferences: SharedPreferences): MutableList<BackupItem> {
        val raw = preferences.getString(K_BACKUP, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<BackupItem>()
            val active = activeScriptId(preferences)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty() || id == active) continue
                out.add(BackupItem(id, o.optBoolean("e", true)))
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveBackupOrder(list: List<BackupItem>) = saveBackupOrder(p(), list)

    fun saveBackupOrder(ctx: Context, profileId: ProfileId, list: List<BackupItem>) =
        saveBackupOrder(profilePrefs(ctx, profileId), list)

    private fun saveBackupOrder(preferences: SharedPreferences, list: List<BackupItem>) {
        val active = activeScriptId(preferences)
        val arr = JSONArray()
        list.filter { it.id.isNotEmpty() && it.id != active }.forEach {
            arr.put(JSONObject().put("id", it.id).put("e", it.enabled))
        }
        preferences.edit().putString(K_BACKUP, arr.toString()).apply()
    }

    fun stageOrder(): MutableList<String> = stageOrder(p())

    fun stageOrder(ctx: Context, profileId: ProfileId): MutableList<String> =
        stageOrder(profilePrefs(ctx, profileId))

    private fun stageOrder(preferences: SharedPreferences): MutableList<String> {
        val raw = preferences.getString(K_STAGES, null)
        if (raw.isNullOrBlank()) return DEFAULT_STAGES.toMutableList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s in STAGE_LABELS && s !in out) out.add(s)
            }
            // 只迁移旧版精确默认顺序，不覆盖用户手动保存的其它排列。
            val oldDefault = listOf(STAGE_ORIGIN, STAGE_QUALITY, STAGE_CROSS_PLAT, STAGE_CROSS_SCRIPT)
            if (preferences.getInt(K_STAGES_VERSION, 0) < 2 && out == oldDefault) {
                saveStageOrder(preferences, DEFAULT_STAGES)
                return DEFAULT_STAGES.toMutableList()
            }
            for (d in DEFAULT_STAGES) if (d !in out) out.add(d)
            out
        } catch (_: Exception) {
            DEFAULT_STAGES.toMutableList()
        }
    }

    fun saveStageOrder(list: List<String>) = saveStageOrder(p(), list)

    fun saveStageOrder(ctx: Context, profileId: ProfileId, list: List<String>) =
        saveStageOrder(profilePrefs(ctx, profileId), list)

    private fun saveStageOrder(preferences: SharedPreferences, list: List<String>) {
        val arr = JSONArray()
        list.filter { it in STAGE_LABELS }.distinct().forEach { arr.put(it) }
        // 补齐
        for (d in DEFAULT_STAGES) if (!(0 until arr.length()).any { arr.optString(it) == d }) arr.put(d)
        preferences.edit().putString(K_STAGES, arr.toString()).putInt(K_STAGES_VERSION, 2).apply()
    }

    fun moveStage(id: String, delta: Int) = moveStage(p(), id, delta)

    fun moveStage(ctx: Context, profileId: ProfileId, id: String, delta: Int) =
        moveStage(profilePrefs(ctx, profileId), id, delta)

    private fun moveStage(preferences: SharedPreferences, id: String, delta: Int) {
        val list = stageOrder(preferences)
        val i = list.indexOf(id)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i == j) return
        val v = list.removeAt(i)
        list.add(j, v)
        saveStageOrder(preferences, list)
    }

    fun smartEnabled(): Boolean = smartEnabled(p())
    fun smartEnabled(ctx: Context, profileId: ProfileId): Boolean = smartEnabled(profilePrefs(ctx, profileId))
    private fun smartEnabled(preferences: SharedPreferences): Boolean = preferences.getBoolean(K_ENABLED, true)

    fun setSmartEnabled(v: Boolean) = setSmartEnabled(p(), v)
    fun setSmartEnabled(ctx: Context, profileId: ProfileId, v: Boolean) =
        setSmartEnabled(profilePrefs(ctx, profileId), v)
    private fun setSmartEnabled(preferences: SharedPreferences, v: Boolean) =
        preferences.edit().putBoolean(K_ENABLED, v).apply()

    fun fallback128k(): Boolean = fallback128k(p())
    fun fallback128k(ctx: Context, profileId: ProfileId): Boolean = fallback128k(profilePrefs(ctx, profileId))
    private fun fallback128k(preferences: SharedPreferences): Boolean = preferences.getBoolean(K_FALLBACK_128, true)

    fun setFallback128k(v: Boolean) = setFallback128k(p(), v)
    fun setFallback128k(ctx: Context, profileId: ProfileId, v: Boolean) =
        setFallback128k(profilePrefs(ctx, profileId), v)
    private fun setFallback128k(preferences: SharedPreferences, v: Boolean) =
        preferences.edit().putBoolean(K_FALLBACK_128, v).apply()

    fun rememberCrossPlat(): Boolean = rememberCrossPlat(p())
    fun rememberCrossPlat(ctx: Context, profileId: ProfileId): Boolean = rememberCrossPlat(profilePrefs(ctx, profileId))
    private fun rememberCrossPlat(preferences: SharedPreferences): Boolean = preferences.getBoolean(K_REMEMBER, true)

    fun setRememberCrossPlat(v: Boolean) = setRememberCrossPlat(p(), v)
    fun setRememberCrossPlat(ctx: Context, profileId: ProfileId, v: Boolean) =
        setRememberCrossPlat(profilePrefs(ctx, profileId), v)
    private fun setRememberCrossPlat(preferences: SharedPreferences, v: Boolean) =
        preferences.edit().putBoolean(K_REMEMBER, v).apply()

    /** URL-bearing cache APIs intentionally remain legacy/global and have no ProfileId overloads. */
    fun remember(songKey: String, rem: Remembered) {
        if (songKey.isEmpty() || !rem.url.startsWith("http")) return
        try {
            val root = try {
                JSONObject(p().getString(K_REMEMBERED, "{}") ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            // 写入前先清掉已过期的条目，避免死链长期堆积；直链时效极短，不能无期限复用。
            val now = System.currentTimeMillis()
            root.keys().forEach { k ->
                val ts = root.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
                if (ts <= 0L || now - ts > REMEMBER_TTL_MS) root.remove(k)
            }
            root.put(
                songKey,
                JSONObject()
                    .put("id", rem.id)
                    .put("source", rem.source)
                    .put("url", rem.url)
                    .put("quality", rem.quality)
                    .put("name", rem.name)
                    .put("singer", rem.singer)
                    .put("ts", now)
            )
            // 限 200 条
            if (root.length() > 200) {
                val keys = mutableListOf<Pair<String, Long>>()
                root.keys().forEach { k ->
                    keys.add(k to (root.optJSONObject(k)?.optLong("ts", 0L) ?: 0L))
                }
                keys.sortedBy { it.second }.take(keys.size - 200).forEach { root.remove(it.first) }
            }
            p().edit().putString(K_REMEMBERED, root.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun getRemembered(songKey: String): Remembered? {
        if (songKey.isEmpty()) return null
        return try {
            val root = JSONObject(p().getString(K_REMEMBERED, "{}") ?: "{}")
            val o = root.optJSONObject(songKey) ?: return null
            val ts = o.optLong("ts", 0L)
            // 直链过期即视为未命中：既不复用死链，也不把失效 URL 冒充可播捷径。
            if (ts <= 0L || System.currentTimeMillis() - ts > REMEMBER_TTL_MS) return null
            val url = o.optString("url")
            if (!url.startsWith("http")) return null
            Remembered(
                id = o.optString("id"),
                source = o.optString("source"),
                url = url,
                quality = o.optString("quality"),
                name = o.optString("name"),
                singer = o.optString("singer")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearRemembered() {
        p().edit().remove(K_REMEMBERED).apply()
    }

    /** ACCEPT4（智能换源页按稿重做）：换源记忆统计（已记住歌曲数 / 占用字节），只读展示用，不改记忆语义。 */
    fun rememberedStats(): Pair<Int, Int> = try {
        val raw = p().getString(K_REMEMBERED, "{}") ?: "{}"
        JSONObject(raw).length() to raw.toByteArray(Charsets.UTF_8).size
    } catch (_: Exception) {
        0 to 0
    }

    /** 播放失败时按歌曲移除跨源记忆；死链不得在 TTL 内反复复用。 */
    fun forget(songKey: String) {
        if (songKey.isEmpty()) return
        try {
            val root = JSONObject(p().getString(K_REMEMBERED, "{}") ?: "{}")
            if (root.has(songKey)) {
                root.remove(songKey)
                p().edit().putString(K_REMEMBERED, root.toString()).apply()
            }
        } catch (_: Exception) {
        }
    }

    fun syncScripts(scripts: List<Script>) = syncScripts(p(), scripts)

    /** Reconcile only stored script IDs in the explicitly selected profile; script source is not persisted here. */
    fun syncScripts(ctx: Context, profileId: ProfileId, scripts: List<Script>) =
        syncScripts(profilePrefs(ctx, profileId), scripts)

    private fun syncScripts(preferences: SharedPreferences, scripts: List<Script>) {
        // r658c D：脚本尚未加载/重载中（空列表）时绝不改写任何持久化选择。
        if (scripts.isEmpty()) return
        val ids = scripts.map { it.id }.toSet()
        val active = activeScriptId(preferences).let { if (it in ids) it else "" }
        if (active != activeScriptId(preferences)) setActiveScriptId(preferences, active)
        if (active.isEmpty() && scripts.isNotEmpty()) {
            val first = scripts.firstOrNull { it.enabled && it.code.isNotBlank() } ?: scripts.first()
            setActiveScriptId(preferences, first.id)
        }
        val cur = backupOrder(preferences).filter { it.id in ids && it.id != activeScriptId(preferences) }.toMutableList()
        val known = cur.map { it.id }.toSet()
        for (sc in scripts) {
            if (sc.id !in known && sc.id != activeScriptId(preferences)) {
                cur.add(BackupItem(sc.id, sc.enabled))
            }
        }
        saveBackupOrder(preferences, cur)
        // r658c D「智能换源链路自己被清除」根因：这里曾把每个平台链路按「本次传入的 scripts 中启用且有代码」过滤后
        // 覆盖写回。而 syncScripts 被大量路径以不完整列表调用——启动时 scripts 状态尚为空、PlayerService 只传已启用脚本、
        // 每次取链 orderedScripts、脚本重载/导入中途、用户临时停用某脚本——任何一次都会把用户选好的链路永久删掉。
        // 现在持久化链路只由用户在智能换源页显式修改；不可用脚本在读取时（orderScriptsByPlatformChains）跳过，
        // 脚本重新启用/重载完成后原链路自动恢复生效。
    }

    /**
     * 兼容旧调用：不再按名称猜测、绑定或启用任何脚本。
     * 音源只能由用户在脚本管理/智能换源中明确选择。
     */
    fun ensureDefaultPlatformScripts(scripts: List<Script>) {
        syncScripts(scripts)
    }

    /**
     * r656：取链脚本顺序只由「按平台脚本链路」推导，不再读取「激活脚本」与「跨脚本备用顺序」（页面已移除）：
     * 1. 歌曲所属平台的脚本链路；2. 其余已开启平台的链路，按平台顺序（= 跨平台顺序）依次接力；
     * 3. 仍未出现的已启用脚本按音源脚本页的排列顺序兜底。
     */
    fun orderedScripts(songSource: String, scripts: List<Script>): List<Script> {
        if (scripts.isEmpty()) return emptyList()
        syncScripts(scripts)
        val src = songSource.ifEmpty { "wy" }
        val otherChains = enabledPlatformsInOrder().filter { it != src }.map { platformScriptOrder(it) }
        return orderScriptsByPlatformChains(platformScriptOrder(src), otherChains, scripts)
    }

    /** 纯函数（JVM 单测）：同上规则；结果只含启用且有代码的脚本、去重。 */
    internal fun orderScriptsByPlatformChains(
        sourceChain: List<String>,
        otherPlatformChains: List<List<String>>,
        scripts: List<Script>
    ): List<Script> {
        val byId = scripts.associateBy { it.id }
        val out = mutableListOf<Script>()
        val seen = mutableSetOf<String>()
        fun add(id: String) {
            if (id.isEmpty() || id in seen) return
            val sc = byId[id] ?: return
            if (!sc.enabled || sc.code.isBlank()) return
            out.add(sc); seen.add(id)
        }
        sourceChain.forEach(::add)
        otherPlatformChains.forEach { chain -> chain.forEach(::add) }
        scripts.forEach { add(it.id) }
        return out
    }

    /** 页面展示用：某平台链路失败后实际的接力顺序（不含本平台链路），供「按平台脚本链路」卡显示。 */
    fun derivedFallbackChain(platform: String, scripts: List<Script>): List<Script> {
        val own = platformScriptOrder(platform)
        val otherChains = enabledPlatformsInOrder().filter { it != platform }.map { platformScriptOrder(it) }
        return orderScriptsByPlatformChains(own, otherChains, scripts).filter { it.id !in own }
    }


    private fun qualityMap(preferences: SharedPreferences, key: String): MutableMap<String, String> {
        val raw = preferences.getString(key, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k ->
                val v = o.optString(k)
                if (v.isNotBlank()) m[k] = v
            }
            m
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveQualityMap(preferences: SharedPreferences, key: String, map: Map<String, String>) {
        val o = JSONObject()
        map.filter { it.key.isNotBlank() && it.value.isNotBlank() }.forEach { (k, v) -> o.put(k, v) }
        preferences.edit().putString(key, o.toString()).apply()
    }

    fun platformPlayQuality(code: String, global: String = "320k"): String =
        normalizeUserSelectableQuality(qualityMap(p(), K_PLAT_PLAY_Q)[code].orEmpty().ifEmpty { global.ifEmpty { "320k" } })

    fun platformPlayQuality(ctx: Context, profileId: ProfileId, code: String, global: String = "320k"): String =
        normalizeUserSelectableQuality(qualityMap(profilePrefs(ctx, profileId), K_PLAT_PLAY_Q)[code].orEmpty().ifEmpty { global.ifEmpty { "320k" } })

    fun platformDownloadQuality(code: String, global: String = "320k"): String =
        normalizeUserSelectableQuality(qualityMap(p(), K_PLAT_DOWN_Q)[code].orEmpty().ifEmpty { global.ifEmpty { "320k" } })

    fun platformDownloadQuality(ctx: Context, profileId: ProfileId, code: String, global: String = "320k"): String =
        normalizeUserSelectableQuality(qualityMap(profilePrefs(ctx, profileId), K_PLAT_DOWN_Q)[code].orEmpty().ifEmpty { global.ifEmpty { "320k" } })

    fun setPlatformPlayQuality(code: String, q: String) {
        setPlatformPlayQuality(p(), code, q)
    }

    fun setPlatformPlayQuality(ctx: Context, profileId: ProfileId, code: String, q: String) =
        setPlatformPlayQuality(profilePrefs(ctx, profileId), code, q)

    private fun setPlatformPlayQuality(preferences: SharedPreferences, code: String, q: String) {
        val m = qualityMap(preferences, K_PLAT_PLAY_Q)
        if (q.isBlank()) m.remove(code) else m[code] = normalizeUserSelectableQuality(q)
        saveQualityMap(preferences, K_PLAT_PLAY_Q, m)
    }

    fun setPlatformDownloadQuality(code: String, q: String) {
        setPlatformDownloadQuality(p(), code, q)
    }

    fun setPlatformDownloadQuality(ctx: Context, profileId: ProfileId, code: String, q: String) =
        setPlatformDownloadQuality(profilePrefs(ctx, profileId), code, q)

    private fun setPlatformDownloadQuality(preferences: SharedPreferences, code: String, q: String) {
        val m = qualityMap(preferences, K_PLAT_DOWN_Q)
        if (q.isBlank()) m.remove(code) else m[code] = normalizeUserSelectableQuality(q)
        saveQualityMap(preferences, K_PLAT_DOWN_Q, m)
    }

    /**
     * ACCEPT5（裁定二）：内置默认平台序的唯一出处——与用户未自定义顺序时
     * [enabledPlatformsInOrder] 返回的顺序同一份。解析层在存储不可读的异常
     * 兜底里只能引用这里，禁止在调用点另写一份写死平台表。
     */
    fun defaultPlatformOrder(): List<String> = DEFAULT_PLATS

    fun enabledPlatformsInOrder(): List<String> =
        platformOrder().filter { it.enabled }.map { it.code }.ifEmpty { DEFAULT_PLATS }

    fun enabledPlatformsInOrder(ctx: Context, profileId: ProfileId): List<String> =
        platformOrder(ctx, profileId).filter { it.enabled }.map { it.code }.ifEmpty { DEFAULT_PLATS }
}
