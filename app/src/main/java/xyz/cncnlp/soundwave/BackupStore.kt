package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手动备份：用户数据、安全播放/外观/下载偏好、已有脚本的顺序/启用配置、下载记录。
 * 不导出账号凭据、诊断上传授权、脚本代码/自由文本元数据，也不复制音频文件。
 */
object BackupStore {
    private const val VERSION = 1
    private const val PREF_SOUNDWAVE = "soundwave"
    private const val PREF_SCRIPTS = "soundwave_scripts"
    private const val PREF_USER = "soundwave_userdata"
    private const val MAX_RESTORED_HISTORY = Int.MAX_VALUE

    data class Preview(
        val favoriteCount: Int,
        val playlistCount: Int,
        val historyCount: Int,
        val downloadCount: Int,
        val hasAvatar: Boolean,
        val hasScripts: Boolean
    )

    fun export(ctx: Context): JSONObject = JSONObject().apply {
        put("schema", "soundwave-backup")
        put("version", VERSION)
        put("user", LocalStore.load(ctx).toCloudJson())
        put("history", JSONArray().apply { LocalStore.loadHistory(ctx).forEach { put(it.toJson()) } })
        put("dailyListenStats", JSONObject(ListenStats.localDailyListenStats(ctx)))
        put("downloads", JSONArray().apply {
            LocalStore.loadDownloads(ctx).forEach { d ->
                put(JSONObject().apply {
                    put("song", d.song.toJson()); put("path", d.path); put("size", d.size)
                    put("time", d.time); put("status", d.status); put("progress", d.progress); put("error", d.error)
                    put("quality", d.quality); put("platform", d.platform); put("scriptId", d.scriptId)
                    put("resolveFailure", d.resolveFailure); put("taskId", d.taskId)
                })
            }
        })
        put("settings", BackupPreferenceProjection.settings(JSONObject(ctx.getSharedPreferences(PREF_SOUNDWAVE, 0).all), strict = false))
        put("scripts", BackupPreferenceProjection.scriptConfiguration(JSONObject(ctx.getSharedPreferences(PREF_SCRIPTS, 0).all), strict = false))
    }

    /** Parse only in memory; this must not read or mutate any local app state. */
    fun preview(root: JSONObject): Preview {
        val bundle = validatedUserBundle(root)
        BackupPreferenceProjection.settings(root.optJSONObject("settings"), strict = true)
        val scripts = BackupPreferenceProjection.scriptConfiguration(root.optJSONObject("scripts"), strict = true)
        return Preview(
            favoriteCount = bundle.favs.size,
            playlistCount = bundle.playlists.count { it.id != "liked" && it.id != "local" },
            historyCount = countRestorableSongs(root.optJSONArray("history"), downloads = false),
            downloadCount = countRestorableSongs(root.optJSONArray("downloads"), downloads = true),
            hasAvatar = bundle.avatarB64.isNotBlank(),
            hasScripts = scripts.has("script_meta") && JSONArray(scripts.getString("script_meta")).length() > 0
        )
    }

    fun restore(ctx: Context, root: JSONObject): LocalStore.UserBundle = synchronized(BackupRestoreRecovery.lock) {
        // Projection strips unaccepted fields (including credentials) before persistence.
        val input = projectedInput(root)
        val id = BackupRestoreRecovery.begin(ctx, input)
        executeRecoverable(ctx, input, id)
    }

    /** The UI obtains review before its explicit replacement confirmation; check again at write time. */
    fun resume(ctx: Context, review: BackupRestoreRecovery.Review): LocalStore.UserBundle =
        synchronized(BackupRestoreRecovery.lock) {
            val input = BackupRestoreRecovery.inputForConfirmedReplay(ctx, review)
            executeRecoverable(ctx, input, review.id)
        }

    private fun executeRecoverable(ctx: Context, root: JSONObject, id: String): LocalStore.UserBundle {
        // Refusal here must not accept changed data as a new interrupted checkpoint.
        BackupRestoreRecovery.verifyBeforeWrite(ctx, id)
        val bundle = validatedUserBundle(root)
        val dailyStatsJson = validatedDailyListenStats(root)
        val settings = BackupPreferenceProjection.settings(root.optJSONObject("settings"), strict = true)
        val scripts = BackupPreferenceProjection.scriptConfiguration(root.optJSONObject("scripts"), strict = true)
        val scriptPrefs = ctx.getSharedPreferences(PREF_SCRIPTS, 0)
        val mergedScripts = BackupPreferenceProjection.mergeScriptConfiguration(
            scriptPrefs.getString("script_meta", "[]").orEmpty(), scripts
        )
        BackupRestoreRecovery.stage(ctx, id, "user")
        // Recheck after validation and checkpoint publication, immediately before business writes.
        // This refusal stays outside catch: never accept concurrent edits as our partial restore.
        BackupRestoreRecovery.verifyBeforeWrite(ctx, id)
        try {
            LocalStore.saveForGenericBackupRestore(ctx, bundle)
            BackupRestoreRecovery.stage(ctx, id, "history")
            restoreHistory(ctx, root.optJSONArray("history"))
            BackupRestoreRecovery.stage(ctx, id, "downloads")
            restoreDownloads(ctx, root.optJSONArray("downloads"))
            BackupRestoreRecovery.stage(ctx, id, "daily-statistics")
            if (dailyStatsJson != null) {
                require(ListenStats.restoreLocalDailyListenStats(ctx, dailyStatsJson)) { "本机每日统计恢复未确认" }
            }
            BackupRestoreRecovery.stage(ctx, id, "settings")
            jsonToPrefs(ctx.getSharedPreferences(PREF_SOUNDWAVE, 0), settings)
            BackupRestoreRecovery.stage(ctx, id, "scripts")
            if (mergedScripts != null) check(scriptPrefs.edit().putString("script_meta", mergedScripts).commit()) {
                "脚本配置恢复提交未确认"
            }
            BackupRestoreRecovery.stage(ctx, id, "avatar")
            if (bundle.avatarB64.isNotBlank()) {
                val currentUser = ctx.getSharedPreferences(PREF_SOUNDWAVE, 0).getString("user", "").orEmpty()
                // AvatarStore writes/syncs an immutable image before its prefs pointer is committed.
                // Empty return is a failure, not successful restoration. Previous bytes are in the journal.
                check(AvatarStore.saveFromB64(ctx, currentUser, bundle.avatarB64).isNotBlank()) { "头像恢复未确认" }
            }
            LocalStore.flushPendingWrites()
            BackupRestoreRecovery.complete(ctx, id)
            return bundle
        } catch (failure: Exception) {
            BackupRestoreRecovery.interrupted(ctx, id)
            throw failure // includes CancellationException; never mark partial writes successful
        }
    }

    private fun projectedInput(root: JSONObject): JSONObject {
        val bundle = validatedUserBundle(root)
        // Preserve the existing ignore-malformed-avatar behavior; distinguish it from a valid
        // image whose publication subsequently fails, which must keep the recovery pending.
        if (bundle.avatarB64.isNotBlank() && !validAvatarPayload(bundle.avatarB64)) bundle.avatarB64 = ""
        val stats = validatedDailyListenStats(root)
        val result = JSONObject().put("schema", "soundwave-backup").put("version", VERSION)
            .put("user", bundle.toCloudJson())
            .put("settings", BackupPreferenceProjection.settings(root.optJSONObject("settings"), strict = true))
            .put("scripts", BackupPreferenceProjection.scriptConfiguration(root.optJSONObject("scripts"), strict = true))
        if (stats != null) result.put("dailyListenStats", JSONObject(stats))
        root.optJSONArray("history")?.let { array ->
            result.put("history", JSONArray().apply {
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { Song.fromJson(it) }
                    ?.takeIf { it.id.isNotBlank() }?.let { put(it.toJson()) }
            })
        }
        root.optJSONArray("downloads")?.let { array ->
            result.put("downloads", JSONArray().apply {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val song = Song.fromJson(item.optJSONObject("song") ?: item)
                    if (song.id.isBlank()) continue
                    put(JSONObject().put("song", song.toJson()).apply {
                        for (key in listOf("path", "error", "quality", "platform", "scriptId", "resolveFailure", "taskId"))
                            put(key, item.optString(key))
                        put("size", item.optLong("size")); put("time", item.optLong("time"))
                        put("status", item.optString("status", "done")); put("progress", item.optDouble("progress", 1.0))
                    })
                }
            })
        }
        return result
    }

    private fun validAvatarPayload(b64: String): Boolean = runCatching {
        val payload = if (b64.startsWith("data:")) b64.substringAfter(',') else b64
        val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    private fun validatedUserBundle(root: JSONObject): LocalStore.UserBundle {
        require(root.optString("schema") == "soundwave-backup") { "不是 SoundWave 备份文件" }
        require(root.optInt("version", 0) in 1..VERSION) { "备份版本不支持" }
        val user = root.opt("user")
        require(user is JSONObject) { "备份用户数据缺失或格式无效" }
        return LocalStore.UserBundle.fromCloud(user)
    }

    private fun validatedDailyListenStats(root: JSONObject): String? {
        if (!root.has("dailyListenStats")) return null
        val value = root.opt("dailyListenStats")
        require(value is JSONObject) { "备份每日统计缺失或格式无效" }
        val raw = value.toString()
        // Validate before any restore mutation; old backups without this field leave local stats untouched.
        ListenStatsDailyStore.localProjection(raw, System.currentTimeMillis())
        return raw
    }

    private fun countRestorableSongs(array: JSONArray?, downloads: Boolean): Int {
        if (array == null) return 0
        if (!downloads) {
            val songs = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Song.fromJson(it) }?.takeIf { it.id.isNotBlank() }
            }
            return PlaybackHistoryPolicy.retainRecent(songs, limit = MAX_RESTORED_HISTORY).size
        }
        return (0 until array.length()).count { index ->
            val item = array.optJSONObject(index) ?: return@count false
            val songJson = if (downloads) item.optJSONObject("song") ?: item else item
            Song.fromJson(songJson).id.isNotBlank()
        }
    }

    private fun jsonToPrefs(prefs: android.content.SharedPreferences, o: JSONObject?) {
        if (o == null) return
        val edit = prefs.edit()
        o.keys().forEach { k ->
            when (val v = o.opt(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Float -> edit.putFloat(k, v)
                is Double -> edit.putFloat(k, v.toFloat())
                is String -> edit.putString(k, v)
            }
        }
        check(edit.commit()) { "偏好设置恢复提交未确认" }
    }

    private fun restoreHistory(ctx: Context, arr: JSONArray?) {
        if (arr == null) return
        val out = mutableListOf<Song>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { Song.fromJson(it) }?.let { if (it.id.isNotBlank()) out.add(it) }
        LocalStore.replaceHistory(ctx, out, limit = MAX_RESTORED_HISTORY)
    }

    private fun restoreDownloads(ctx: Context, arr: JSONArray?) {
        if (arr == null) return
        val records = mutableListOf<DownloadItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val song = Song.fromJson(o.optJSONObject("song") ?: o)
            if (song.id.isBlank()) continue
            val restored = DownloadItem(
                song = song,
                path = o.optString("path"),
                size = o.optLong("size"),
                time = o.optLong("time"),
                status = o.optString("status", "done"),
                progress = o.optDouble("progress", 1.0).toFloat(),
                error = o.optString("error"),
                quality = o.optString("quality"),
                platform = o.optString("platform"),
                scriptId = o.optString("scriptId"),
                resolveFailure = o.optString("resolveFailure"),
                taskId = o.optString("taskId")
            )
            records.add(restored)
        }
        LocalStore.restoreDownloadsDurably(ctx, records)
    }
}
