package xyz.cncnlp.soundwave

import android.content.Context
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 断点续播：队列 + 当前曲 + 进度 */
object PlaybackStore {
    private const val PREF = "soundwave_playback_v1"
    private const val PROFILE_PREF_PREFIX = "soundwave_playback_profile_"
    private const val KEY = "state"
    /** Service 被重建后，用户从通知栏明确点“播放”才置位；绝不因系统重启自行播放。 */
    private const val KEY_EXPLICIT_RESUME = "explicit_resume"
    /** 直链通常有时效；超过该窗口只恢复歌曲与队列，强制重新取链。 */
    private const val RESOLVED_URL_TTL_MS = 30 * 60 * 1000L

    /**
     * 从 Service 的稳定快照保存断点。
     *
     * 新代码应优先使用这个入口，避免在 UI/Controller 层分别读取 current、queue、position
     * 等多个可变 Service getter，从而在保存瞬间混合出不同时间点的状态。
     */
    /**
     * r658c F：断点可能由后台定期任务与主线程（onDestroy / 服务销毁）同时写入；按采样时间单调提交，
     * 迟到的旧快照不得覆盖更新的断点。
     */
    private val checkpointLock = Any()
    private var lastCheckpointCapturedAt = Long.MIN_VALUE
    /** 同一 owner + 同一首歌的 resolvedAt 缓存：免去每次保存都整串解析上一份断点 JSON。 */
    @Volatile private var lastResolvedAt: Triple<String, String, Long>? = null

    /** ACCEPT5：与 lastResolvedAt 同款缓存（ownerKey → 队列来源三键），非控制器保存保留来源时免整串解析。 */
    @Volatile private var lastQueueOrigin: Pair<String, Triple<String, String, String>?>? = null

    fun save(ctx: Context, snapshot: PlaybackSnapshot, capturedAtNanos: Long,
             queueOrigin: QueueOrigin? = null, queueOriginFlags: List<Boolean> = emptyList(), queueOriginKnown: Boolean = false) {
        synchronized(checkpointLock) {
            if (capturedAtNanos < lastCheckpointCapturedAt) return
            lastCheckpointCapturedAt = capturedAtNanos
            saveSnapshotLocked(ctx, snapshot, queueOrigin, queueOriginFlags, queueOriginKnown)
        }
    }

    fun save(ctx: Context, snapshot: PlaybackSnapshot,
             queueOrigin: QueueOrigin? = null, queueOriginFlags: List<Boolean> = emptyList(), queueOriginKnown: Boolean = false) = synchronized(checkpointLock) {
        lastCheckpointCapturedAt = maxOf(lastCheckpointCapturedAt, System.nanoTime())
        saveSnapshotLocked(ctx, snapshot, queueOrigin, queueOriginFlags, queueOriginKnown)
    }

    private fun saveSnapshotLocked(ctx: Context, snapshot: PlaybackSnapshot,
                                   queueOrigin: QueueOrigin? = null, queueOriginFlags: List<Boolean> = emptyList(), queueOriginKnown: Boolean = false) {
        if (!ProfileWorkIdentity.isRuntime(snapshot.ownerContext)) return
        val song = snapshot.song ?: return
        val savedQueue = snapshot.queue.ifEmpty { listOf(song) }
        val savedIndex = snapshot.queueIndex
            .takeIf { it >= 0 }
            ?.coerceIn(0, savedQueue.lastIndex)
            ?: savedQueue.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0)
        save(
            ctx = ctx,
            song = song,
            queue = savedQueue,
            index = savedIndex,
            positionMs = snapshot.positionMs,
            playing = snapshot.isPlaying,
            ownerContext = snapshot.ownerContext,
            queueOrigin = queueOrigin,
            queueOriginFlags = queueOriginFlags,
            queueOriginKnown = queueOriginKnown
        )
    }

    /** Persist a profile-addressed queue/checkpoint snapshot without selecting or activating it. */
    fun save(ctx: Context, profileId: ProfileId, snapshot: PlaybackSnapshot) {
        val owner = snapshot.ownerContext.owner as? xyz.cncnlp.soundwave.migration.ProfileOwner.RecordedProfile ?: return
        if (owner.id != profileId) return
        val song = snapshot.song ?: return
        val savedQueue = snapshot.queue.ifEmpty { listOf(song) }
        val savedIndex = snapshot.queueIndex
            .takeIf { it >= 0 }
            ?.coerceIn(0, savedQueue.lastIndex)
            ?: savedQueue.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0)
        save(
            ctx = ctx,
            profileId = profileId,
            song = song,
            queue = savedQueue,
            index = savedIndex,
            positionMs = snapshot.positionMs,
            playing = snapshot.isPlaying,
            ownerContext = snapshot.ownerContext
        )
    }

    fun save(ctx: Context, song: Song?, queue: List<Song>, index: Int, positionMs: Long, playing: Boolean,
             ownerContext: xyz.cncnlp.soundwave.migration.ProfileContext = ProfileWorkIdentity.runtime(),
             queueOrigin: QueueOrigin? = null, queueOriginFlags: List<Boolean> = emptyList(), queueOriginKnown: Boolean = false) {
        if (!ProfileWorkIdentity.isRuntime(ownerContext)) return
        if (song == null || queue.isEmpty()) return
        try {
            save(
                prefs = ContextPreferences.open(ctx, PREF, 0, ownerContext),
                ownerGeneration = ownerContext.generation,
                song = song,
                queue = queue,
                index = index,
                positionMs = positionMs,
                playing = playing,
                queueOrigin = queueOrigin,
                queueOriginFlags = queueOriginFlags,
                queueOriginKnown = queueOriginKnown
            )
        } catch (_: Exception) {}
    }

    fun save(
        ctx: Context,
        profileId: ProfileId,
        song: Song?,
        queue: List<Song>,
        index: Int,
        positionMs: Long,
        playing: Boolean,
        ownerContext: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileContext.recorded(profileId, 0L)
    ) {
        if (!ProfileWorkIdentity.isRuntime(ownerContext) || (ownerContext.owner as? xyz.cncnlp.soundwave.migration.ProfileOwner.RecordedProfile)?.id != profileId) return
        if (song == null || queue.isEmpty()) return
        try {
            save(
                prefs = profilePreferences(ctx, profileId),
                ownerId = "profile:${profileId.value}",
                ownerGeneration = ownerContext.generation,
                song = song,
                queue = queue,
                index = index,
                positionMs = positionMs,
                playing = playing
            )
        } catch (_: Exception) {}
    }

    private fun save(
        prefs: SharedPreferences,
        ownerId: String = ProfileWorkIdentity.LEGACY_OWNER,
        ownerGeneration: Long,
        song: Song,
        queue: List<Song>,
        index: Int,
        positionMs: Long,
        playing: Boolean,
        queueOrigin: QueueOrigin? = null,
        queueOriginFlags: List<Boolean> = emptyList(),
        queueOriginKnown: Boolean = false
    ) {
        try {
            // 只持久化本地文件路径；远程临时直链不落盘（时效性 + 账号/平台数据边界）。
            fun persistUrl(s: Song) = if (s.source == "local") s.resolvedUrl else ""
            val arr = JSONArray()
            queue.forEach { s ->
                arr.put(s.toJson().apply {
                    put("resolvedUrl", persistUrl(s))
                    put("actualQuality", s.actualQuality)
                })
            }
            val o = JSONObject().apply {
                put("ownerVersion", 1)
                put("owner", ownerId)
                put("ownerGeneration", ownerGeneration)
                put("song", song.toJson().apply {
                    put("resolvedUrl", persistUrl(song))
                    put("actualQuality", song.actualQuality)
                })
                put("queue", arr)
                put("index", index.coerceIn(0, queue.lastIndex))
                put("positionMs", positionMs.coerceAtLeast(0L))
                put("playing", playing)
                put("ts", System.currentTimeMillis())
                put("resolvedAt", System.currentTimeMillis())
            }
            val ownerKey = "$ownerId#$ownerGeneration"
            // 上一份断点 JSON 懒读一次：resolvedAt 继承与队列来源保留共用，避免重复整串解析。
            var previousJson: JSONObject? = null
            var previousRead = false
            fun previous(): JSONObject? {
                if (!previousRead) {
                    previousRead = true
                    previousJson = try { prefs.getString(KEY, null)?.let(::JSONObject) } catch (_: Exception) { null }
                }
                return previousJson
            }
            val cached = lastResolvedAt?.takeIf { it.first == ownerKey && it.second == song.key() }?.third
            val resolvedAt = cached ?: run {
                val previous = previous()
                val previousKey = previous?.optJSONObject("song")?.let { Song.fromJson(it).key() }
                if (previousKey == song.key()) {
                    previous?.optLong("resolvedAt", 0L)?.takeIf { it > 0L } ?: System.currentTimeMillis()
                } else {
                    System.currentTimeMillis()
                }
            }
            // ACCEPT5 队列来源三键：控制器保存是权威（known=true，按当前来源写，null 即明确无来源）；
            // Service/退出保存不携带来源（known=false）时保留上一份三键，避免把来源冲掉。
            val originFields: Triple<String, String, String>? = if (queueOriginKnown) {
                queueOrigin?.let {
                    Triple(it.playlistId, it.type.name, queueOriginFlags.joinToString("") { f -> if (f) "1" else "0" })
                }
            } else {
                // 缓存命中（含值为 null=已明确无来源）即权威，不再穿透读 JSON 复活旧来源。
                val cachedOrigin = lastQueueOrigin
                if (cachedOrigin != null && cachedOrigin.first == ownerKey) cachedOrigin.second
                else previous()?.let { prev ->
                    val id = prev.optString("queueOriginId")
                    if (id.isBlank()) null
                    else Triple(id, prev.optString("queueOriginType"), prev.optString("queueOriginFlags"))
                }
            }
            originFields?.let { (id, type, flags) ->
                o.put("queueOriginId", id)
                o.put("queueOriginType", type)
                o.put("queueOriginFlags", flags)
            }
            lastQueueOrigin = ownerKey to originFields
            prefs.edit().putString(KEY, o.apply { put("resolvedAt", resolvedAt) }.toString()).apply()
            lastResolvedAt = Triple(ownerKey, song.key(), resolvedAt)
        } catch (_: Exception) {}
    }

    data class State(
        val song: Song,
        val queue: List<Song>,
        val index: Int,
        val positionMs: Long,
        val wasPlaying: Boolean,
        /** ACCEPT5 队列来源同步：断点队列的来源歌单与条目来源标记（旧断点为 null/空，恢复端按无来源处理）。 */
        val queueOrigin: QueueOrigin? = null,
        val queueOriginFlags: List<Boolean> = emptyList()
    )

    fun setExplicitResume(ctx: Context, enabled: Boolean,
                          owner: xyz.cncnlp.soundwave.migration.ProfileContext = ProfileWorkIdentity.runtime()) {
        if (!ProfileWorkIdentity.isRuntime(owner)) return
        val prefs = ContextPreferences.open(ctx, PREF, 0, owner)
        // r658c F：每次暂停都会清标记；标记本来就是 false 时 consume 结果不变，跳过这次写入
        // （ContextPreferences 每次写入还会给大体积 userdata 文件 bump 版本号并整文件重写）。
        if (!enabled && !prefs.getBoolean(KEY_EXPLICIT_RESUME, false)) return
        prefs.edit().putBoolean(KEY_EXPLICIT_RESUME, enabled)
            .putString("resume_owner", ProfileWorkIdentity.encodeOwner(owner))
            .putLong("resume_generation", owner.generation).apply()
    }

    fun consumeExplicitResume(ctx: Context,
                              owner: xyz.cncnlp.soundwave.migration.ProfileContext = ProfileWorkIdentity.runtime()): Boolean {
        if (!ProfileWorkIdentity.isRuntime(owner)) return false
        val prefs = ContextPreferences.open(ctx, PREF, 0, owner)
        val requested = prefs.getBoolean(KEY_EXPLICIT_RESUME, false)
        val matches = !prefs.contains("resume_owner") ||
            (prefs.getString("resume_owner", null) == ProfileWorkIdentity.encodeOwner(owner) &&
                prefs.getLong("resume_generation", -1L) == owner.generation)
        if (requested) prefs.edit().remove(KEY_EXPLICIT_RESUME).remove("resume_owner").remove("resume_generation").apply()
        return requested && matches
    }

    /** Read a prior checkpoint only for the captured owner; generation is data, not authorization.
     * Explicit resume still requires a fresh service-owned user request.
     */
    fun load(ctx: Context, owner: xyz.cncnlp.soundwave.migration.ProfileContext): State? {
        if (!ProfileWorkIdentity.isRuntime(owner)) return null
        return when (val identity = owner.owner) {
            xyz.cncnlp.soundwave.migration.ProfileOwner.LegacyUnassigned -> load(ctx)
            is xyz.cncnlp.soundwave.migration.ProfileOwner.RecordedProfile -> load(ctx, identity.id)
        }
    }

    fun load(ctx: Context): State? {
        val raw = ctx.applicationContext.getSharedPreferences(PREF, 0).getString(KEY, null) ?: return null
        return load(raw)
    }

    /** Read a profile snapshot only; this does not trigger playback or consume resume intent. */
    fun load(ctx: Context, profileId: ProfileId): State? {
        val raw = profilePreferences(ctx, profileId).getString(KEY, null) ?: return null
        return load(raw, "profile:${profileId.value}")
    }

    private fun profilePreferences(ctx: Context, profileId: ProfileId): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("$PROFILE_PREF_PREFIX${profileId.value}", 0)

    private fun load(raw: String, expectedOwner: String = ProfileWorkIdentity.LEGACY_OWNER): State? {
        return try {
            val o = JSONObject(raw)
            if (o.has("ownerVersion")) {
                if (o.optInt("ownerVersion") != 1 || o.optString("owner") != expectedOwner ||
                    o.optLong("ownerGeneration", -1L) < 0L) return null
            }
            val songO = o.optJSONObject("song") ?: return null
            val song = Song.fromJson(songO).also {
                // 远程 URL 永不作为恢复捷径：恢复路径必须经 Resolver 重新取链；仅本地文件路径可直接播放。
                it.resolvedUrl = if (it.source == "local") songO.optString("resolvedUrl") else ""
                it.actualQuality = songO.optString("actualQuality")
            }
            val resolvedAt = o.optLong("resolvedAt", 0L)
            val urlFresh = resolvedAt <= 0L || System.currentTimeMillis() - resolvedAt <= RESOLVED_URL_TTL_MS
            // r658c D#1：TTL 只针对远程直链；本地文件 URI 永久有效，过期清空会让本地歌被迫去取链。
            if (!urlFresh && !LocalPlayback.isLocalSong(song)) {
                song.resolvedUrl = ""
                song.actualQuality = ""
            }
            val arr = o.optJSONArray("queue") ?: JSONArray()
            val q = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                q.add(Song.fromJson(so).also {
                    it.resolvedUrl = if (it.source == "local") so.optString("resolvedUrl") else ""
                    it.actualQuality = so.optString("actualQuality")
                })
            }
            if (q.isEmpty()) q.add(song)
            if (!urlFresh) q.filterNot(LocalPlayback::isLocalSong).forEach { it.resolvedUrl = ""; it.actualQuality = "" }
            // ACCEPT5：队列来源恢复；类型串损坏时按普通歌单处理，标记长度与队列不符时丢弃（恢复端回退全来源）。
            val restoredOriginId = o.optString("queueOriginId")
            val restoredOrigin = if (restoredOriginId.isBlank()) null else QueueOrigin(
                restoredOriginId,
                runCatching { QueueOrigin.Type.valueOf(o.optString("queueOriginType")) }
                    .getOrDefault(QueueOrigin.Type.PLAYLIST)
            )
            val restoredFlagsRaw = o.optString("queueOriginFlags")
            val restoredFlags = if (restoredOrigin != null && restoredFlagsRaw.length == q.size) {
                restoredFlagsRaw.map { it == '1' }
            } else emptyList()
            State(
                song = song,
                queue = q,
                index = o.optInt("index", 0).coerceIn(0, q.lastIndex),
                positionMs = o.optLong("positionMs", 0L),
                wasPlaying = o.optBoolean("playing", false),
                queueOrigin = restoredOrigin,
                queueOriginFlags = restoredFlags
            )
        } catch (_: Exception) { null }
    }
}
