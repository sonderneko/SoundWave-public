package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileOwner
import xyz.cncnlp.soundwave.download.DownloadTaskIdentity
import java.time.Instant
import java.security.MessageDigest

internal const val MAX_CLOUD_INLINE_IMAGE_CHARS = 512 * 1024

internal class CloudInlineImageTooLargeException : IllegalArgumentException(
    "自定义封面 data:image 超过云同步上限（512 KiB），图片仍保留在本机；请压缩图片或改用公开图片链接后重试。"
)

internal fun cloudSafeCover(raw: String, includeLocalUris: Boolean): String {
    val filtered = filterDeviceLocalReference(raw, includeLocalUris)
    if (!includeLocalUris && filtered.startsWith("data:image/", ignoreCase = true) &&
        filtered.length > MAX_CLOUD_INLINE_IMAGE_CHARS
    ) throw CloudInlineImageTooLargeException()
    return filtered
}

/**
 * 本地用户数据：喜欢/歌单/历史/下载记录。登录后与云端 pls/favs 同步。
 */
object LocalStore {
    private const val PREF = "soundwave_userdata"
    private const val KEY_DATA = "user_data_v1"
    private const val KEY_HISTORY = "play_history_v1"
    private const val KEY_DOWNLOADS = "downloads_v1"
    private const val KEY_IMPORTED_SNAPSHOT_STATUS = "snapshot_import_status_v1"
    private const val KEY_IMPORTED_SNAPSHOT_AT = "snapshot_imported_at_v1"
    private const val KEY_IMPORTED_SNAPSHOT_SOURCE = "snapshot_import_source_v1"
    private const val KEY_IMPORTED_LOCAL_BACKUP_STATUS = "local_backup_import_status_v1"
    private const val KEY_IMPORTED_LOCAL_BACKUP_AT = "local_backup_imported_at_v1"
    private const val KEY_IMPORTED_LOCAL_BACKUP_SCHEMA = "local_backup_import_schema_v1"
    private const val KEY_IMPORTED_LOCAL_BACKUP_VERSION = "local_backup_import_version_v1"
    private const val KEY_ACCOUNT_DATA_OWNER_V1 = "account_data_owner_v1"
    private const val KEY_ACCOUNT_DATA_OWNER_V2 = "account_data_owner_v2"
    private const val ACCOUNT_LOCAL_SNAPSHOTS_PREF = "soundwave_account_local_snapshots_v1"
    private const val KEY_LEGACY_ACCOUNT_QUARANTINE = "legacy_unassigned_quarantine_v1"
    private const val PREF_LOCAL_BACKUP_PROFILE_INDEX = "soundwave_local_backup_profile_index_v1"
    private const val KEY_LOCAL_BACKUP_PROFILE_IDS = "profile_ids_v1"
    private const val MAX_LOCAL_BACKUP_PROFILES = 2_000
    private const val CLOUD_DELETED_FAVORITES = "_swDeletedFavorites"
    private const val CLOUD_DELETED_PLAYLISTS = "_swDeletedPlaylists"
    private const val CLOUD_DELETED_PLAYLIST_SONGS = "_swDeletedPlaylistSongs"
    private const val CLOUD_DELETED_HISTORY = "_swDeletedHistoryEvents"
    private const val CLOUD_TODAY_STYLE_PREFERENCES = "_swTodayStylePreferences"

    // r656-G：所有读取/其它写入入口先落下待写的整包快照，保证进程内写后读一致、写入顺序不变。
    fun prefs(ctx: Context): SharedPreferences =
        rawPrefs(ctx).also { deferredWriter.flush(it) }

    private fun profilePrefs(ctx: Context, profileId: ProfileId): SharedPreferences =
        rawProfilePrefs(ctx, profileId).also { deferredWriter.flush(it) }

    private fun rawPrefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, 0)

    private fun rawProfilePrefs(ctx: Context, profileId: ProfileId): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(profileId.preferencesName, 0)

    /**
     * r656-G：主界面运行时开启。开启后主线程上的 [save] 只拷贝快照，JSON 序列化与写入
     * 在后台单线程执行并做 250ms 合并；关闭时（Worker、单测、非主线程）保持原同步语义。
     */
    @Volatile internal var deferMainThreadSaves: Boolean = false
    /** 可替换的“是否主线程”判断（单测里 Robolectric 的测试线程未必是主 Looper 线程）。 */
    @Volatile internal var isMainThread: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
    /** 单测可替换为长合并窗口的实例，避免慢机/模拟器上 250ms 窗口内的计时抖动。 */
    @Volatile internal var deferredWriter = xyz.cncnlp.soundwave.perf.DeferredPrefsWriter()

    /** 立即落下所有待写快照（Activity 退到后台、备份/迁移读取前调用）。 */
    fun flushPendingWrites() = deferredWriter.flushAll()

    private val profileSnapshotWriteLock = Any()

    internal data class ProfileSnapshotImportMetadata(
        val status: String,
        val importedAt: String,
        val sourceLabel: String
    )

    internal data class LocalProfileBackupImportMetadata(
        val status: String,
        val importedAt: String,
        val schema: String,
        val version: Int
    )

    internal data class UnactivatedProfilePreview(
        val favoriteCount: Int,
        val playlistCount: Int,
        val playlistSongCount: Int,
        val historyCount: Int,
        val playlistNames: List<String>,
        val songNames: List<String>
    )

    data class UserBundle(
        val favs: MutableList<Song> = mutableListOf(),
        val playlists: MutableList<Playlist> = mutableListOf(),
        val enabledScripts: MutableList<String> = mutableListOf(),
        val scriptOrder: MutableList<String> = mutableListOf(),
        var avatarB64: String = "",
        val history: MutableList<Song> = mutableListOf(),
        /** Stable song key -> deletion time. Persisted inside an element of the favs array. */
        val deletedFavoriteKeys: MutableMap<String, Long> = linkedMapOf(),
        /** Playlist id -> deletion time. Persisted inside an element of the pls array. */
        val deletedPlaylistIds: MutableMap<String, Long> = linkedMapOf(),
        /** Playlist id -> (stable song key -> deletion time), also stored inside the pls array. */
        val deletedPlaylistSongKeys: MutableMap<String, MutableMap<String, Long>> = linkedMapOf(),
        /** Stable song key -> per-installation monotonic play counters; only account save emits this field. */
        val songPlayCountCounters: MutableMap<String, MutableMap<String, Long>> = linkedMapOf(),
        /** Dated per-song listening facts; local snapshot retains 365 days, cloud projection only 30. */
        var dailyListenStatsJson: String = "{}",
        /** Only v2-proven facts may enter an account-owned payload. */
        var dailyListenStatsOwnerV2: String? = null,
        /** Original ownerless input retained only for non-destructive local quarantine. */
        var legacyDailyListenStatsJson: String? = null,
        /** Opaque single-play event key -> deletion time, propagated to account cloud and WebDAV. */
        val historyDeletionTombstones: MutableMap<String, Long> = linkedMapOf(),
        /** Platform-keyed daily recommendation preferences; stored as a legacy-safe favs marker. */
        var todayStylePreferencesJson: String = "{}"
    ) {
        fun ensureDefaults() {
            // 收藏歌单是 favs 的同步视图；必须保留，不能因歌单页的文案需求删除收藏入口或数据。
            if (playlists.none { it.id == "liked" }) {
                playlists.add(0, Playlist("liked", "喜欢", local = true, songs = favs.toMutableList()))
            }
            if (playlists.none { it.id == "local" }) {
                playlists.add(Playlist("local", "本地歌单", local = true))
            }
            // liked 是 favs 的同步视图：先同步歌曲，再补默认封面，避免新增喜欢后 cover 仍为空。
            val likedIndex = playlists.indexOfFirst { it.id == "liked" }
            if (likedIndex >= 0) {
                val liked = playlists[likedIndex]
                playlists[likedIndex] = liked.copy(songs = favs.toMutableList())
            }
            // 仅空封面使用首曲兜底；非空值视为用户自定义封面，绝不覆盖。
            playlists.forEachIndexed { i, pl ->
                if (pl.cover.isBlank()) {
                    val fallback = pl.songs.firstOrNull()?.cover.orEmpty()
                    if (fallback.isNotBlank()) playlists[i] = pl.copy(cover = normalizeCoverUrl(fallback))
                }
            }
        }

        // r657 第二轮性能：「我的喜欢」等长列表每行都会问一次 isLiked；原实现每次线性扫描 favs 并为每首重建 key() 字符串
        // （600 首 × 可见 12 行 = 每次重组 7200 次字符串拼接）。改为按 favs 指纹（实例 + 长度 + 首尾元素）缓存的 key 集合。
        private var likedKeyCache: Set<String> = emptySet()
        private var likedKeyList: List<Song>? = null
        private var likedKeySize = -1
        private var likedKeyFirst: Song? = null
        private var likedKeyLast: Song? = null

        fun isLiked(song: Song): Boolean {
            val f = favs
            if (f.size <= 16) return f.any { it.key() == song.key() }
            val first = f.firstOrNull(); val last = f.lastOrNull()
            if (likedKeyList !== f || likedKeySize != f.size || likedKeyFirst !== first || likedKeyLast !== last) {
                likedKeyCache = f.mapTo(HashSet(f.size * 2)) { it.key() }
                likedKeyList = f; likedKeySize = f.size; likedKeyFirst = first; likedKeyLast = last
            }
            return song.key() in likedKeyCache
        }

        fun toggleLike(song: Song): Boolean {
            val i = favs.indexOfFirst { it.key() == song.key() }
            return if (i >= 0) {
                removeFavoriteKeys(setOf(song.key()))
                false
            } else {
                addFavorite(song)
                true
            }.also { ensureDefaults() }
        }

        fun addFavorite(song: Song, nowMs: Long = System.currentTimeMillis()) {
            val key = song.key()
            val deletedAt = deletedFavoriteKeys.remove(key)
            val index = favs.indexOfFirst { it.key() == key }
            if (index >= 0 && deletedAt == null) return
            val addedAt = maxOf(nowMs, (deletedAt ?: 0L) + 1L, song.syncAddedAt)
            favs.removeAll { it.key() == key }
            favs.add(0, song.copy(syncAddedAt = addedAt))
            ensureDefaults()
        }

        fun removeFavoriteKeys(keys: Set<String>, nowMs: Long = System.currentTimeMillis()) {
            keys.forEach { key -> deletedFavoriteKeys[key] = nextSyncTime(nowMs, deletedFavoriteKeys[key]) }
            favs.removeAll { it.key() in keys }
            ensureDefaults()
        }

        fun clearFavorites(nowMs: Long = System.currentTimeMillis()) {
            removeFavoriteKeys(favs.mapTo(linkedSetOf()) { it.key() }, nowMs)
        }

        fun deleteHistoryEvent(song: Song, nowMs: Long = System.currentTimeMillis()) {
            if (song.playedAt <= 0L) return
            val key = PlaybackHistoryPolicy.historyEventKey(song)
            historyDeletionTombstones[key] = maxOf(nowMs, (historyDeletionTombstones[key] ?: 0L) + 1L)
            history.removeAll { PlaybackHistoryPolicy.historyEventKey(it) == key }
        }

        fun addSongsToPlaylist(playlistId: String, songs: List<Song>, nowMs: Long = System.currentTimeMillis()) {
            val index = playlists.indexOfFirst { it.id == playlistId }
            if (index < 0) return
            val playlist = playlists[index]
            val deletedAt = deletedPlaylistIds.remove(playlistId)
            val songDeletes = deletedPlaylistSongKeys.getOrPut(playlistId) { linkedMapOf() }
            val nextSongs = playlist.songs.toMutableList()
            songs.forEach { song ->
                val key = song.key()
                val removedAt = songDeletes.remove(key)
                val songIndex = nextSongs.indexOfFirst { it.key() == key }
                if (songIndex >= 0 && removedAt == null) return@forEach
                val addedAt = maxOf(nowMs, (removedAt ?: 0L) + 1L, song.syncAddedAt)
                val stamped = song.copy(syncAddedAt = addedAt)
                if (songIndex >= 0) nextSongs[songIndex] = stamped else nextSongs.add(0, stamped)
            }
            playlists[index] = playlist.copy(
                songs = nextSongs,
                count = nextSongs.size,
                syncCreatedAt = maxOf(playlist.syncCreatedAt, deletedAt?.plus(1L) ?: 0L)
            )
            if (songDeletes.isEmpty()) deletedPlaylistSongKeys.remove(playlistId)
            ensureDefaults()
        }

        fun removeSongsFromPlaylist(playlistId: String, keys: Set<String>, nowMs: Long = System.currentTimeMillis()) {
            val index = playlists.indexOfFirst { it.id == playlistId }
            if (index < 0) return
            val playlist = playlists[index]
            val songDeletes = deletedPlaylistSongKeys.getOrPut(playlistId) { linkedMapOf() }
            keys.forEach { key -> songDeletes[key] = nextSyncTime(nowMs, songDeletes[key]) }
            val songs = playlist.songs.filterNot { it.key() in keys }.toMutableList()
            playlists[index] = playlist.copy(songs = songs, count = songs.size)
        }

        fun replacePlaylistSongs(playlistId: String, songs: List<Song>, nowMs: Long = System.currentTimeMillis()) {
            val index = playlists.indexOfFirst { it.id == playlistId }
            if (index < 0) return
            val playlist = playlists[index]
            val incoming = songs.distinctBy { it.key() }
            val incomingKeys = incoming.mapTo(hashSetOf()) { it.key() }
            val previousByKey = playlist.songs.associateBy { it.key() }
            val songDeletes = deletedPlaylistSongKeys.getOrPut(playlistId) { linkedMapOf() }
            (previousByKey.keys - incomingKeys).forEach { key ->
                songDeletes[key] = nextSyncTime(nowMs, songDeletes[key])
            }
            val restored = incoming.map { song ->
                val key = song.key()
                val deletedAt = songDeletes.remove(key)
                val previous = previousByKey[key]
                when {
                    deletedAt != null -> song.copy(syncAddedAt = maxOf(nowMs, deletedAt + 1L))
                    previous != null -> song.copy(syncAddedAt = previous.syncAddedAt)
                    else -> song.copy(syncAddedAt = nowMs)
                }
            }.toMutableList()
            if (songDeletes.isEmpty()) deletedPlaylistSongKeys.remove(playlistId)
            playlists[index] = playlist.copy(songs = restored, count = restored.size)
        }

        fun addOrReplacePlaylist(playlist: Playlist, nowMs: Long = System.currentTimeMillis()) {
            val deletedAt = deletedPlaylistIds.remove(playlist.id)
            val songDeletes = deletedPlaylistSongKeys[playlist.id]
            val songs = playlist.songs.map { song ->
                val removedAt = songDeletes?.remove(song.key())
                when {
                    removedAt != null -> song.copy(syncAddedAt = maxOf(nowMs, removedAt + 1L))
                    song.syncAddedAt == 0L -> song.copy(syncAddedAt = nowMs)
                    else -> song
                }
            }.toMutableList()
            if (songDeletes?.isEmpty() == true) deletedPlaylistSongKeys.remove(playlist.id)
            playlists.removeAll { it.id == playlist.id }
            playlists.add(0, playlist.copy(
                songs = songs,
                syncCreatedAt = maxOf(playlist.syncCreatedAt, nowMs, deletedAt?.plus(1L) ?: 0L)
            ))
            ensureDefaults()
        }

        fun deletePlaylist(playlistId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (playlistId in setOf("liked", "local")) return false
            deletedPlaylistIds[playlistId] = nextSyncTime(nowMs, deletedPlaylistIds[playlistId])
            val oldSize = playlists.size
            playlists.removeAll { it.id == playlistId }
            return playlists.size != oldSize
        }

        /** Deep snapshot used by asynchronous cloud saves so later edits cannot mutate the payload. */
        fun syncSnapshot(): UserBundle = copy(
            favs = favs.toMutableList(),
            playlists = playlists.map { it.copy(songs = it.songs.toMutableList()) }.toMutableList(),
            enabledScripts = enabledScripts.toMutableList(),
            scriptOrder = scriptOrder.toMutableList(),
            history = history.toMutableList(),
            deletedFavoriteKeys = deletedFavoriteKeys.toMutableMap(),
            deletedPlaylistIds = deletedPlaylistIds.toMutableMap(),
            deletedPlaylistSongKeys = deletedPlaylistSongKeys.mapValuesTo(linkedMapOf()) { it.value.toMutableMap() },
            songPlayCountCounters = songPlayCountCounters.mapValuesTo(linkedMapOf()) { it.value.toMutableMap() },
            historyDeletionTombstones = historyDeletionTombstones.toMutableMap(),
            todayStylePreferencesJson = todayStylePreferencesJson
        )

        /** Apply delete-wins for unchanged/legacy records, but let an explicit later re-add win. */
        fun reconcileSyncTombstones(nowMs: Long = System.currentTimeMillis()) {
            deletedFavoriteKeys.toMap().forEach { (key, deletedAt) ->
                if (favs.any { it.key() == key && it.syncAddedAt > deletedAt }) deletedFavoriteKeys.remove(key)
            }
            favs.removeAll { song -> (deletedFavoriteKeys[song.key()] ?: Long.MIN_VALUE) >= song.syncAddedAt }

            val keptPlaylists = playlists.filter { playlist ->
                val deletedAt = deletedPlaylistIds[playlist.id] ?: return@filter true
                if (playlist.syncCreatedAt > deletedAt) {
                    deletedPlaylistIds.remove(playlist.id)
                    true
                } else false
            }
            playlists.clear()
            playlists.addAll(keptPlaylists)

            playlists.indices.forEach { index ->
                val playlist = playlists[index]
                val songDeletes = deletedPlaylistSongKeys[playlist.id] ?: return@forEach
                songDeletes.toMap().forEach { (key, deletedAt) ->
                    if (playlist.songs.any { it.key() == key && it.syncAddedAt > deletedAt }) songDeletes.remove(key)
                }
                val songs = playlist.songs.filterNot { song ->
                    (songDeletes[song.key()] ?: Long.MIN_VALUE) >= song.syncAddedAt
                }.toMutableList()
                if (songs.size != playlist.songs.size) {
                    val count = if (playlist.local || playlist.id.startsWith("local_")) songs.size else playlist.count
                    playlists[index] = playlist.copy(songs = songs, count = count)
                }
                if (songDeletes.isEmpty()) deletedPlaylistSongKeys.remove(playlist.id)
            }
            val retainedHistoryDeletes = PlaybackHistoryPolicy.retainHistoryDeletionTombstones(
                historyDeletionTombstones,
                nowMs
            )
            historyDeletionTombstones.clear()
            historyDeletionTombstones.putAll(retainedHistoryDeletes)
            history.removeAll { PlaybackHistoryPolicy.historyEventKey(it) in historyDeletionTombstones }
        }

        private fun nextSyncTime(nowMs: Long, previous: Long?): Long = maxOf(nowMs, (previous ?: 0L) + 1L)

        private fun putTimeMap(target: JSONObject, key: String, values: Map<String, Long>) {
            if (values.isNotEmpty()) target.put(key, JSONObject().apply { values.forEach { (id, time) -> put(id, time) } })
        }

        fun toCloudJson(
            includeAvatar: Boolean = true,
            includeHistory: Boolean = true,
            historyLimit: Int = Int.MAX_VALUE,
            includePlayCountCounters: Boolean = false,
            includeDailyListenStats: Boolean = false,
            includeLocalUris: Boolean = true,
            /** r658：云端上传用紧凑格式（无重复别名键、无空字段），读取端 fromCloud 兼容新旧两种格式。 */
            compact: Boolean = false
        ): JSONObject {
            reconcileSyncTombstones()
            ensureDefaults()
            val favArr = JSONArray()
            favs.forEach { song ->
                val cover = cloudSafeCover(song.cover, includeLocalUris)
                favArr.put(song.copy(cover = cover).toJson(includeSyncMetadata = true, includeLocalUri = includeLocalUris, compact = compact))
            }
            if (deletedFavoriteKeys.isNotEmpty()) {
                favArr.put(JSONObject().apply { putTimeMap(this, CLOUD_DELETED_FAVORITES, deletedFavoriteKeys) })
            }
            val canonicalTodayStylePreferences = xyz.cncnlp.soundwave.recommend.StylePreferenceSync
                .canonical(todayStylePreferencesJson)
            if (JSONObject(canonicalTodayStylePreferences).length() > 0) {
                // Old cloud services pass unknown favs entries through unchanged.
                favArr.put(JSONObject().put(CLOUD_TODAY_STYLE_PREFERENCES, JSONObject(canonicalTodayStylePreferences)))
            }
            // Legacy account servers preserve unknown favs markers even when they whitelist root fields.
            val ownedDailyStats = dailyListenStatsOwnerV2?.takeIf(AccountDataIdentity::isKey)?.let { owner ->
                JSONObject().put("owner", owner).put("days", JSONObject(
                    if (includeLocalUris) ListenStatsDailyStore.localProjection(dailyListenStatsJson, System.currentTimeMillis())
                    else ListenStatsDailyStore.cloudProjection(dailyListenStatsJson, System.currentTimeMillis())
                ))
            }
            if (ownedDailyStats != null) favArr.put(JSONObject().put("_swDailyListenStatsV2", ownedDailyStats))
            val plsArr = JSONArray()
            playlists.forEach { pl ->
                val songsToWrite = if (pl.id == "liked" || pl.id == "local") emptyList() else pl.songs
                val songs = JSONArray()
                songsToWrite.forEach { song ->
                    val cover = cloudSafeCover(song.cover, includeLocalUris)
                    songs.put(song.copy(cover = cover).toJson(includeSyncMetadata = true, includeLocalUri = includeLocalUris, compact = compact))
                }
                val coverSource = if (includeLocalUris) pl.cover else {
                    cloudSafeCover(pl.cover, includeLocalUris = false).ifBlank { pl.syncCover }
                }
                val cover = cloudSafeCover(coverSource, includeLocalUris)
                plsArr.put(JSONObject().apply {
                    put("id", pl.id)
                    put("nm", pl.name)
                    // r658 413 根因之一：自定义封面可为内联 data:image（≤512KB），旧格式在 cv/cover 各写一份，
                    // 体积翻倍。fromCloud 先读 cv/nm/src/hid，紧凑格式只写一份。
                    put("cv", cover)
                    if (!compact) { put("name", pl.name); put("cover", cover) }
                    // Keep a portable fallback across local saves/restarts; never accept a device path here.
                    if (includeLocalUris) {
                        cloudSafeCover(pl.syncCover, includeLocalUris = false).takeIf { it.isNotBlank() }
                            ?.let { put("_swSyncCover", it) }
                    }
                    put("local", pl.local)
                    put("src", pl.source)
                    if (!compact) put("source", pl.source)
                    if (!compact || pl.author.isNotEmpty()) put("author", pl.author)
                    put("count", if (pl.id == "local") 0 else pl.songCount().takeIf { it > 0 } ?: pl.count)
                    if (!compact || pl.playCount != 0L) put("playCount", pl.playCount)
                    put("hid", pl.hidden)
                    if (!compact) put("hidden", pl.hidden)
                    put("songs", songs)
                    if (pl.syncCreatedAt > 0L) put("_swCreatedAt", pl.syncCreatedAt)
                    if (pl.syncOrder != null && pl.syncOrderAt > 0L) {
                        put("_swLibOrder", pl.syncOrder)
                        put("_swLibOrderAt", pl.syncOrderAt)
                    }
                    if (pl.favSyncOrder != null && pl.favSyncOrderAt > 0L) {
                        put("_swFavOrder", pl.favSyncOrder)
                        put("_swFavOrderAt", pl.favSyncOrderAt)
                    }
                    if (pl.remoteId.isNotEmpty()) put("remoteId", pl.remoteId)
                    if (pl.favorited) put("favorited", true)
                })
            }
            if (deletedPlaylistIds.isNotEmpty() || deletedPlaylistSongKeys.isNotEmpty()) {
                plsArr.put(JSONObject().apply {
                    putTimeMap(this, CLOUD_DELETED_PLAYLISTS, deletedPlaylistIds)
                    if (deletedPlaylistSongKeys.isNotEmpty()) {
                        put(CLOUD_DELETED_PLAYLIST_SONGS, JSONObject().apply {
                            deletedPlaylistSongKeys.forEach { (playlistId, keys) ->
                                if (keys.isNotEmpty()) put(playlistId, JSONObject().apply {
                                    keys.forEach { (songKey, time) -> put(songKey, time) }
                                })
                            }
                        })
                    }
                })
            }
            val en = JSONArray()
            enabledScripts.forEach { en.put(it) }
            val so = JSONArray()
            scriptOrder.forEach { so.put(it) }
            return JSONObject().apply {
                put("favs", favArr)
                put("pls", plsArr)
                put("enabledScripts", en)
                put("scriptOrder", so)
                if (includeAvatar && avatarB64.isNotEmpty()) put("avatar", avatarB64)
                if (includeHistory) {
                    val hist = JSONArray()
                    PlaybackHistoryPolicy.retainForCloudSync(history, limit = historyLimit)
                        .forEach { song ->
                            val cover = cloudSafeCover(song.cover, includeLocalUris)
                            hist.put(song.copy(cover = cover).toJson(includeLocalUri = includeLocalUris, compact = compact))
                        }
                    put("history", hist)
                    putTimeMap(this, CLOUD_DELETED_HISTORY, historyDeletionTombstones)
                }
                if (includePlayCountCounters && includeLocalUris) {
                    put("playCountCounters", JSONObject().apply {
                        xyz.cncnlp.soundwave.sync.SongPlayCountCounterMerge
                            .trim(songPlayCountCounters)
                            .forEach { (songKey, devices) ->
                                put(songKey, JSONObject().apply {
                                    devices.forEach { (deviceId, count) ->
                                        if (count > 0L) put(deviceId, count)
                                    }
                                })
                            }
                    })
                }
                if (ownedDailyStats != null) {
                    put("dailyListenStatsV2", ownedDailyStats)
                } else if (includeDailyListenStats) {
                    put("dailyListenStats", JSONObject(
                        ListenStatsDailyStore.cloudProjection(dailyListenStatsJson, System.currentTimeMillis())
                    ))
                }
            }
        }

        companion object {
            private fun readOwnedDailyStats(bundle: UserBundle, value: JSONObject?, allowLocalUris: Boolean) {
                val owner = value?.optString("owner")?.takeIf(AccountDataIdentity::isKey) ?: return
                val days = value.optJSONObject("days") ?: return
                bundle.dailyListenStatsOwnerV2 = owner
                bundle.dailyListenStatsJson = if (allowLocalUris) {
                    ListenStatsDailyStore.localProjection(days.toString(), System.currentTimeMillis())
                } else ListenStatsDailyStore.cloudProjection(days.toString(), System.currentTimeMillis())
            }

            private fun bundleSong(data: JSONObject, allowLocalUris: Boolean): Song {
                val song = Song.fromJson(data)
                return song.copy(
                    cover = cloudSafeCover(song.cover, allowLocalUris),
                    resolvedUrl = if (allowLocalUris) song.resolvedUrl else ""
                )
            }

            fun fromCloud(data: JSONObject?, allowLocalUris: Boolean = true): UserBundle {
                val b = UserBundle()
                if (data == null) {
                    b.ensureDefaults(); return b
                }
                val favs = data.optJSONArray("favs")
                if (favs != null) {
                    for (i in 0 until favs.length()) {
                        val o = favs.optJSONObject(i) ?: continue
                        if (o.has("_swDailyListenStatsV2")) {
                            readOwnedDailyStats(b, o.optJSONObject("_swDailyListenStatsV2"), allowLocalUris)
                            continue
                        }
                        if (o.has(CLOUD_TODAY_STYLE_PREFERENCES)) {
                            val preferences = o.optJSONObject(CLOUD_TODAY_STYLE_PREFERENCES)
                            if (preferences != null) {
                                b.todayStylePreferencesJson = xyz.cncnlp.soundwave.recommend.StylePreferenceSync
                                    .canonical(preferences.toString())
                            }
                            continue
                        }
                        val marker = o.optJSONObject(CLOUD_DELETED_FAVORITES)
                        if (marker != null) {
                            val keys = marker.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                b.deletedFavoriteKeys[key] = marker.optLong(key, 0L)
                            }
                            continue
                        }
                        val s = bundleSong(o, allowLocalUris)
                        if (s.id.isNotEmpty()) b.favs.add(s)
                    }
                }
                val pls = data.optJSONArray("pls") ?: data.optJSONArray("playlists")
                if (pls != null) {
                    for (i in 0 until pls.length()) {
                        val o = pls.optJSONObject(i) ?: continue
                        val deletedPlaylists = o.optJSONObject(CLOUD_DELETED_PLAYLISTS)
                        val deletedSongs = o.optJSONObject(CLOUD_DELETED_PLAYLIST_SONGS)
                        if (deletedPlaylists != null || deletedSongs != null) {
                            if (deletedPlaylists != null) {
                                val keys = deletedPlaylists.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    b.deletedPlaylistIds[key] = deletedPlaylists.optLong(key, 0L)
                                }
                            }
                            if (deletedSongs != null) {
                                val playlistKeys = deletedSongs.keys()
                                while (playlistKeys.hasNext()) {
                                    val playlistId = playlistKeys.next()
                                    val songMap = deletedSongs.optJSONObject(playlistId) ?: continue
                                    val songKeys = songMap.keys()
                                    val entries = b.deletedPlaylistSongKeys.getOrPut(playlistId) { linkedMapOf() }
                                    while (songKeys.hasNext()) {
                                        val key = songKeys.next()
                                        entries[key] = songMap.optLong(key, 0L)
                                    }
                                }
                            }
                            continue
                        }
                        val playlistId = o.optString("id").ifEmpty { "pl_$i" }
                        val isDeviceLocalLibrary = !allowLocalUris && playlistId == "local"
                        val songsArr = o.optJSONArray("songs") ?: o.optJSONArray("list")
                        val songs = mutableListOf<Song>()
                        if (!isDeviceLocalLibrary && songsArr != null) {
                            for (j in 0 until songsArr.length()) {
                                val so = songsArr.optJSONObject(j) ?: continue
                                val s = bundleSong(so, allowLocalUris)
                                if (s.id.isNotEmpty()) songs.add(s)
                            }
                        }
                        b.playlists.add(
                            Playlist(
                                id = playlistId,
                                name = o.optString("nm").ifEmpty { o.optString("name") }.ifEmpty { "歌单${i + 1}" },
                                cover = cloudSafeCover(
                                    normalizeCoverUrl(o.optString("cv").ifEmpty { o.optString("cover") }),
                                    allowLocalUris
                                ),
                                songs = songs,
                                local = o.optBoolean("local", false) || o.optString("id") in listOf("liked", "local"),
                                source = o.optString("src").ifEmpty { o.optString("source") },
                                author = o.optString("author").ifEmpty { o.optString("creator") },
                                count = if (isDeviceLocalLibrary) 0 else o.optInt("count", o.optInt("trackCount", songs.size)),
                                playCount = o.optLong("playCount", 0L),
                                hidden = o.optBoolean("hid", false) || o.optBoolean("hidden", false),
                                remoteId = o.optString("remoteId"),
                                favorited = o.optBoolean("favorited", false),
                                syncCreatedAt = o.optLong("_swCreatedAt", 0L),
                                syncOrder = o.optInt("_swLibOrder", -1).takeIf { o.has("_swLibOrder") && it >= 0 },
                                syncOrderAt = o.optLong("_swLibOrderAt", 0L).coerceAtLeast(0L),
                                favSyncOrder = o.optInt("_swFavOrder", -1).takeIf { o.has("_swFavOrder") && it >= 0 },
                                favSyncOrderAt = o.optLong("_swFavOrderAt", 0L).coerceAtLeast(0L),
                                syncCover = if (allowLocalUris) cloudSafeCover(
                                    normalizeCoverUrl(o.optString("_swSyncCover")), includeLocalUris = false
                                ) else ""
                            )
                        )
                    }
                }
                val en = data.optJSONArray("enabledScripts")
                if (en != null) {
                    for (i in 0 until en.length()) b.enabledScripts.add(en.optString(i))
                }
                val so = data.optJSONArray("scriptOrder")
                if (so != null) {
                    for (i in 0 until so.length()) b.scriptOrder.add(so.optString(i))
                }
                b.avatarB64 = data.optString("avatar")
                val hist = data.optJSONArray("history")
                if (hist != null) for (i in 0 until hist.length()) {
                    val o = hist.optJSONObject(i) ?: continue
                    val sng = bundleSong(o, allowLocalUris)
                    if (sng.id.isNotEmpty()) b.history.add(sng)
                }
                val deletedHistory = data.optJSONObject(CLOUD_DELETED_HISTORY)
                if (deletedHistory != null) {
                    val eventKeys = deletedHistory.keys()
                    while (eventKeys.hasNext()) {
                        val eventKey = eventKeys.next()
                        b.historyDeletionTombstones[eventKey] = deletedHistory.optLong(eventKey, 0L)
                    }
                }
                val dailyStats = data.optJSONObject("dailyListenStats")
                b.legacyDailyListenStatsJson = dailyStats?.toString()
                if (dailyStats != null && b.dailyListenStatsOwnerV2 == null) {
                    b.dailyListenStatsJson = if (allowLocalUris) {
                        ListenStatsDailyStore.localProjection(dailyStats.toString(), System.currentTimeMillis())
                    } else {
                        ListenStatsDailyStore.cloudProjection(dailyStats.toString(), System.currentTimeMillis())
                    }
                }
                if (data.has("dailyListenStatsV2")) {
                    readOwnedDailyStats(b, data.optJSONObject("dailyListenStatsV2"), allowLocalUris)
                }
                val playCounts = data.optJSONObject("playCountCounters")
                if (allowLocalUris && playCounts != null) {
                    val songKeys = playCounts.keys()
                    while (songKeys.hasNext()) {
                        val songKey = songKeys.next().takeIf { it.isNotBlank() && it.length <= 512 } ?: continue
                        val devicesObject = playCounts.optJSONObject(songKey) ?: continue
                        val devices = linkedMapOf<String, Long>()
                        val deviceKeys = devicesObject.keys()
                        while (deviceKeys.hasNext()) {
                            val deviceId = deviceKeys.next()
                            if (deviceId.length !in 1..128 || !deviceId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) continue
                            val count = devicesObject.optLong(deviceId, 0L).coerceAtLeast(0L)
                            if (count > 0L) devices[deviceId] = count
                        }
                        if (devices.isNotEmpty()) b.songPlayCountCounters[songKey] = devices
                    }
                    val trimmed = xyz.cncnlp.soundwave.sync.SongPlayCountCounterMerge.trim(b.songPlayCountCounters)
                    b.songPlayCountCounters.clear()
                    b.songPlayCountCounters.putAll(trimmed)
                }
                val retainedHistory = if (allowLocalUris) {
                    PlaybackHistoryPolicy.retainRecent(b.history)
                } else {
                    PlaybackHistoryPolicy.retainForCloudSync(b.history)
                }
                b.history.clear()
                b.history.addAll(retainedHistory)
                // Legacy bundles may store likes only in the liked playlist; tombstones are reconciled below.
                if (b.favs.isEmpty()) {
                    b.playlists.find { it.id == "liked" }?.songs?.let { b.favs.addAll(it) }
                }
                b.reconcileSyncTombstones()
                b.ensureDefaults()
                return b
            }
        }
    }

    internal data class AccountLocalScope(
        val bundle: UserBundle,
        val history: List<Song>,
        val switchedFromAnotherAccount: Boolean
    )

    /** Account ownership is local metadata; only v2 identity keys can establish ownership. */
    internal fun accountDataOwner(ctx: Context): String =
        prefs(ctx).getString(KEY_ACCOUNT_DATA_OWNER_V2, null)?.takeIf(AccountDataIdentity::isKey).orEmpty()

    /** Preserve the old account's local copy and restore only a matching account's own copy. */
    internal fun prepareAccountLocalScope(
        ctx: Context,
        targetAccount: String,
        currentBundle: UserBundle,
        currentHistory: List<Song>
    ): AccountLocalScope? = synchronized(profileSnapshotWriteLock) {
        if (targetAccount.isBlank()) return@synchronized null
        val target = AccountDataIdentity.key(targetAccount)
        val activePreferences = prefs(ctx)
        val currentOwner = activePreferences.getString(KEY_ACCOUNT_DATA_OWNER_V2, null)
            ?.takeIf(AccountDataIdentity::isKey).orEmpty()
        val sourceIsHidden = currentOwner.isNotBlank() && !canReadActiveAccountData(ctx)
        val sourceBundle = if (sourceIsHidden) loadFrom(activePreferences) else currentBundle
        val sourceHistory = if (sourceIsHidden) loadHistoryFrom(activePreferences) else currentHistory
        if (currentOwner == target) {
            return@synchronized AccountLocalScope(
                sourceBundle.syncSnapshot(),
                PlaybackHistoryPolicy.retainRecent(sourceHistory),
                switchedFromAnotherAccount = false
            )
        }

        if (currentOwner.isBlank()) {
            // v1 owner values and v1 snapshot slots are unproven legacy metadata. Preserve the
            // live local data without associating it with the newly authenticated identity.
            val quarantine = JSONObject()
                .put("bundle", sourceBundle.syncSnapshot().toCloudJson(
                    includeAvatar = true, includeHistory = true, includeDailyListenStats = true,
                    includeLocalUris = true
                ))
                .put("history", JSONArray().apply {
                    PlaybackHistoryPolicy.retainRecent(sourceHistory).forEach { put(it.toJson()) }
                })
                .put("legacyRawSource", JSONObject().apply {
                    listOf(KEY_DATA, KEY_HISTORY, KEY_ACCOUNT_DATA_OWNER_V1).forEach { key ->
                        activePreferences.getString(key, null)?.let { put(key, it) }
                    }
                })
                .toString()
            val quarantinePreferences = ctx.applicationContext.getSharedPreferences(
                ACCOUNT_LOCAL_SNAPSHOTS_PREF, Context.MODE_PRIVATE
            )
            val quarantineHash = MessageDigest.getInstance("SHA-256").digest(quarantine.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val contentHashKey = "legacy_unassigned_quarantine_sha256_$quarantineHash"
            val editor = quarantinePreferences.edit()
            if (!quarantinePreferences.contains(KEY_LEGACY_ACCOUNT_QUARANTINE)) {
                editor.putString(KEY_LEGACY_ACCOUNT_QUARANTINE, quarantine)
            }
            if (!quarantinePreferences.contains(contentHashKey)) editor.putString(contentHashKey, quarantine)
            val quarantined = editor.commit() &&
                quarantinePreferences.getString(KEY_LEGACY_ACCOUNT_QUARANTINE, null) != null &&
                quarantinePreferences.getString(contentHashKey, null) == quarantine
            if (!quarantined) return@synchronized null
            val snapshots = ctx.applicationContext.getSharedPreferences(ACCOUNT_LOCAL_SNAPSHOTS_PREF, Context.MODE_PRIVATE)
            val targetRaw = snapshots.getString(accountSnapshotKeyForIdentityKey(target), null)
            val restored = if (targetRaw == null) {
                UserBundle().also { it.ensureDefaults() } to emptyList()
            } else {
                decodeAccountLocalSnapshot(targetRaw, target) ?: return@synchronized null
            }
            return@synchronized AccountLocalScope(restored.first, restored.second, switchedFromAnotherAccount = true)
        }

        val snapshots = ctx.applicationContext.getSharedPreferences(ACCOUNT_LOCAL_SNAPSHOTS_PREF, Context.MODE_PRIVATE)
        val oldKey = accountSnapshotKeyForIdentityKey(currentOwner)
        val oldRaw = encodeAccountLocalSnapshot(currentOwner, sourceBundle, sourceHistory)
        if (!snapshots.edit().putString(oldKey, oldRaw).commit() || snapshots.getString(oldKey, null) != oldRaw) {
            return@synchronized null
        }

        val targetRaw = snapshots.getString(accountSnapshotKeyForIdentityKey(target), null)
        val restored = if (targetRaw == null) {
            UserBundle().also { it.ensureDefaults() } to emptyList()
        } else {
            try {
                decodeAccountLocalSnapshot(targetRaw, target) ?: return@synchronized null
            } catch (_: Exception) {
                return@synchronized null
            }
        }
        AccountLocalScope(restored.first, restored.second, switchedFromAnotherAccount = true)
    }

    /** Move, rather than copy, a confirmed renamed account's local namespace. */
    internal fun renameAccountLocalScope(
        ctx: Context,
        oldAccount: String,
        newAccount: String,
        bundle: UserBundle,
        history: List<Song>
    ): Boolean = synchronized(profileSnapshotWriteLock) {
        if (oldAccount.isBlank() || newAccount.isBlank()) return@synchronized false
        val oldOwner = AccountDataIdentity.key(oldAccount)
        val newOwner = AccountDataIdentity.key(newAccount)
        if (oldOwner == newOwner) return@synchronized true
        val snapshots = ctx.applicationContext.getSharedPreferences(ACCOUNT_LOCAL_SNAPSHOTS_PREF, Context.MODE_PRIVATE)
        val oldKey = accountSnapshotKeyForIdentityKey(oldOwner)
        val newKey = accountSnapshotKeyForIdentityKey(newOwner)
        val newRaw = encodeAccountLocalSnapshot(newOwner, bundle, history)
        val committed = snapshots.edit().putString(newKey, newRaw).remove(oldKey).commit()
        committed && snapshots.getString(newKey, null) == newRaw && snapshots.getString(oldKey, null) == null
    }

    private fun encodeAccountLocalSnapshot(account: String, bundle: UserBundle, history: List<Song>): String =
        JSONObject()
            .put("owner", account)
            .put("bundle", bundle.syncSnapshot().toCloudJson(
                includeAvatar = true, includeHistory = true, includeDailyListenStats = true,
                includeLocalUris = true
            ))
            .put("history", JSONArray().apply { PlaybackHistoryPolicy.retainRecent(history).forEach { put(it.toJson()) } })
            .toString()

    private fun accountSnapshotKeyForIdentityKey(identityKey: String): String = "account_$identityKey"

    private fun decodeAccountLocalSnapshot(raw: String, expectedOwner: String): Pair<UserBundle, List<Song>>? = try {
        val root = JSONObject(raw)
        require(root.optString("owner") == expectedOwner) { "账号本机快照归属无效" }
        val bundleJson = root.optJSONObject("bundle") ?: error("账号本机快照缺少 bundle")
        val bundle = UserBundle.fromCloud(bundleJson, allowLocalUris = true)
        val historyJson = root.optJSONArray("history") ?: JSONArray()
        val history = (0 until historyJson.length()).mapNotNull { index ->
            historyJson.optJSONObject(index)?.let(Song::fromJson)
        }
        bundle.history.clear()
        bundle.history.addAll(PlaybackHistoryPolicy.retainRecent(history))
        bundle.ensureDefaults()
        bundle to PlaybackHistoryPolicy.retainRecent(history)
    } catch (_: Exception) {
        null
    }

    /** Park an active account's local state before logout, leaving the guest namespace empty. */
    internal fun parkAccountLocalScope(ctx: Context, currentBundle: UserBundle, currentHistory: List<Song>): Boolean =
        synchronized(profileSnapshotWriteLock) {
            synchronized(historyLock) history@{
                val preferences = prefs(ctx)
                val owner = preferences.getString(KEY_ACCOUNT_DATA_OWNER_V2, null)
                    ?.takeIf(AccountDataIdentity::isKey) ?: return@history true
                val snapshots = ctx.applicationContext.getSharedPreferences(ACCOUNT_LOCAL_SNAPSHOTS_PREF, Context.MODE_PRIVATE)
                val key = accountSnapshotKeyForIdentityKey(owner)
                val raw = encodeAccountLocalSnapshot(owner, currentBundle, currentHistory)
                if (!snapshots.edit().putString(key, raw).commit() || snapshots.getString(key, null) != raw) {
                    return@history false
                }
                val committed = preferences.edit()
                    .remove(KEY_DATA)
                    .remove(KEY_HISTORY)
                    .remove(KEY_ACCOUNT_DATA_OWNER_V2)
                    .commit()
                committed && !preferences.contains(KEY_DATA) && !preferences.contains(KEY_HISTORY) &&
                    !preferences.contains(KEY_ACCOUNT_DATA_OWNER_V2)
            }
        }

    /** Park an account hidden by current auth before startup or guest writes can replace it. */
    internal fun detachInactiveAccountLocalScope(ctx: Context): Boolean = synchronized(profileSnapshotWriteLock) {
        // owner 键只由同步 commit 路径写入（从不经延迟写队列），先用 rawPrefs 只读判定：
        // owner 缺失时直接通过，避免每次 save 都经 prefs() 同步 flush 待写快照——
        // 那会击穿 r656-G 主线程合并写（每存一次序列化一次且回到主线程）。
        val owner = rawPrefs(ctx).getString(KEY_ACCOUNT_DATA_OWNER_V2, null)
            ?.takeIf(AccountDataIdentity::isKey) ?: return@synchronized true
        val preferences = prefs(ctx)
        if (canReadActiveAccountData(ctx)) return@synchronized true
        parkAccountLocalScope(ctx, loadFrom(preferences), loadHistoryFrom(preferences))
    }

    fun load(ctx: Context): UserBundle = if (canReadActiveAccountData(ctx)) {
        loadFrom(prefs(ctx))
    } else UserBundle().also { it.ensureDefaults() }

    /** Load only the explicitly named profile; this does not discover or activate a profile. */
    fun load(ctx: Context, profileId: ProfileId): UserBundle = loadFrom(profilePrefs(ctx, profileId))

    private fun loadFrom(preferences: SharedPreferences): UserBundle = synchronized(profileSnapshotWriteLock) {
        val raw = preferences.getString(KEY_DATA, null)
            ?: return@synchronized UserBundle().also { it.ensureDefaults() }
        try {
            val json = JSONObject(raw)
            val bundle = UserBundle.fromCloud(json).also { it.ensureDefaults() }
            val retainedHistory = PlaybackHistoryPolicy.retainRecent(bundle.history)
            val historyWasPruned = retainedHistory != bundle.history
            if (historyWasPruned) {
                bundle.history.clear()
                bundle.history.addAll(retainedHistory)
            }
            val storedHistory = json.optJSONArray("history")
            if (storedHistory != null && (historyWasPruned || bundle.history.size != storedHistory.length())) {
                val cleaned = JSONObject(raw).put("history", JSONArray().apply {
                    bundle.history.forEach { put(it.toJson()) }
                }).toString()
                runCatching { preferences.edit().putString(KEY_DATA, cleaned).commit() }
            }
            bundle
        } catch (_: Exception) {
            UserBundle().also { it.ensureDefaults() }
        }
    }

    fun save(ctx: Context, bundle: UserBundle) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；数据未写入" }
        saveTo(rawPrefs(ctx), bundle)
    }

    /** Save an account-sync merge and its independent runtime history in one preferences edit. */
    internal fun saveWithIndependentHistory(
        ctx: Context,
        bundle: UserBundle,
        history: List<Song>,
        accountOwner: String? = null
    ) {
        if (accountOwner == null) {
            check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；数据未写入" }
        }
        val retainedHistory = PlaybackHistoryPolicy.retainRecent(history)
        val snapshot = bundle.syncSnapshot().copy(history = retainedHistory.toMutableList())
        snapshot.ensureDefaults()
        val bundleRaw = snapshot.toCloudJson().toString()
        val historyRaw = JSONArray().apply { snapshot.history.forEach { put(it.toJson()) } }.toString()
        check(persistAccountSyncSnapshot(prefs(ctx), bundleRaw, historyRaw, accountOwner)) { "本机合并快照持久化未确认" }
    }

    /** Return false unless both account-merge keys were durably committed and read back. */
    internal fun persistAccountSyncSnapshot(
        preferences: SharedPreferences,
        bundleRaw: String,
        historyRaw: String,
        accountOwner: String? = null
    ): Boolean = synchronized(profileSnapshotWriteLock) {
        val canonicalOwner = accountOwner?.takeIf { it.isNotBlank() }?.let(AccountDataIdentity::key)
        val editor = preferences.edit()
            .putString(KEY_DATA, bundleRaw)
            .putString(KEY_HISTORY, historyRaw)
        canonicalOwner?.let { editor.putString(KEY_ACCOUNT_DATA_OWNER_V2, it) }
        val committed = editor.commit()
        committed && preferences.getString(KEY_DATA, null) == bundleRaw &&
            preferences.getString(KEY_HISTORY, null) == historyRaw &&
            (canonicalOwner == null || preferences.getString(KEY_ACCOUNT_DATA_OWNER_V2, null) == canonicalOwner)
    }

    /** Atomically persist the global backup bundle with provenance that keeps account sync closed. */
    internal fun saveForGenericBackupRestore(ctx: Context, bundle: UserBundle) {
        check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；数据未写入" }
        val localSnapshot = bundle.syncSnapshot().copy(
            history = PlaybackHistoryPolicy.retainRecent(bundle.history).toMutableList()
        )
        localSnapshot.ensureDefaults()
        synchronized(profileSnapshotWriteLock) {
            val preferences = prefs(ctx)
            val epoch = preferences.getLong(RuntimeProvenanceInventory.EPOCH_KEY, 0L)
            check(epoch >= 0L) { "本机来源版本无效" }
            val nextEpoch = Math.addExact(epoch, 1L)
            val committed = preferences.edit()
                .putString(KEY_DATA, localSnapshot.toCloudJson().toString())
                .putBoolean(LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY, true)
                .putLong(RuntimeProvenanceInventory.EPOCH_KEY, nextEpoch)
                .commit()
            check(committed) { "本机备份数据写入未确认" }
        }
    }

    /** Save only to the explicitly named profile's core bundle key. */
    fun save(ctx: Context, profileId: ProfileId, bundle: UserBundle) {
        synchronized(profileSnapshotWriteLock) { saveTo(rawProfilePrefs(ctx, profileId), bundle) }
    }

    /** Persist an already validated local backup to one new, explicitly addressed inactive profile. */
    internal fun importLocalBackupToUnactivatedProfile(
        ctx: Context,
        profileId: ProfileId,
        prepared: PreparedLocalProfileBackup,
        importedAt: String = Instant.now().toString(),
        beforeRegistryWrite: () -> Unit = {},
        afterPayloadVerified: () -> Unit = {},
        auxiliaryDomains: Map<LocalCopyDomain, Map<String, Any>> = emptyMap()
    ): LocalProfileBackupImportMetadata {
        require(runCatching { Instant.parse(importedAt) }.isSuccess) { "导入时间无效" }
        val schema = InactiveProfileBackupSerializer.SCHEMA
        val version = InactiveProfileBackupSerializer.VERSION
        val snapshot = prepared.bundle.syncSnapshot().copy(
            history = PlaybackHistoryPolicy.retainRecent(prepared.bundle.history).toMutableList()
        )
        require(snapshot.enabledScripts.isEmpty() && snapshot.scriptOrder.isEmpty()) { "本机备份不得包含脚本配置" }
        require(snapshot.avatarB64.isBlank()) { "本机备份不得包含头像数据" }
        require((snapshot.favs + snapshot.playlists.flatMap { it.songs } + snapshot.history).all { it.resolvedUrl.isBlank() }) {
            "本机备份不得包含本机路径或播放 URI"
        }
        snapshot.reconcileSyncTombstones()
        snapshot.ensureDefaults()
        val encodedBundle = snapshot.toCloudJson(
            includeAvatar = false,
            includeHistory = true,
            historyLimit = maxOf(200, snapshot.history.size)
        ).toString()
        val encodedHistory = JSONArray().apply { snapshot.history.forEach { put(it.toJson()) } }.toString()
        val metadata = LocalProfileBackupImportMetadata(
            status = "IMPORTED_LOCAL_NOT_ACTIVE",
            importedAt = importedAt,
            schema = schema,
            version = version
        )

        return synchronized(profileSnapshotWriteLock) {
            val appContext = ctx.applicationContext
            val registry = appContext.getSharedPreferences(PREF_LOCAL_BACKUP_PROFILE_INDEX, 0)
            val registeredIds = readLocalBackupProfileIds(registry)
            check(profileId.value !in registeredIds) { "此本机资料 ID 已登记；未覆盖任何内容" }
            val nextIds = registeredIds + profileId.value
            require(nextIds.size <= MAX_LOCAL_BACKUP_PROFILES) { "本机未启用资料数量超过上限" }
            val nextRegistryRaw = JSONArray(nextIds).toString()

            // Address only the supplied random ProfileId. An occupied namespace is never overwritten.
            val preferences = profilePrefs(appContext, profileId)
            check(preferences.all.isEmpty()) { "此本机资料 ID 已有数据；未覆盖任何内容" }
            SelectedDomainCopy.preflight(appContext, profileId, LocalCopyDomain.values().toSet())
            check(!InactiveRollbackJournal.contains(appContext, profileId)) { "Occupied rollback journal" }
            val auxiliaryOperation = java.util.UUID.randomUUID().toString()
            check(InactiveImportJournal.prepare(appContext, profileId)) { "导入日志未确认；未写入资料" }
            val previousRegistryRaw = registry.getString(KEY_LOCAL_BACKUP_PROFILE_IDS, null)
            var profileWriteStarted = false
            var registryWriteStarted = false
            try {
                profileWriteStarted = true
                val committed = preferences.edit()
                    .putString(KEY_DATA, encodedBundle)
                    .putString(KEY_HISTORY, encodedHistory)
                    .putString(KEY_IMPORTED_LOCAL_BACKUP_STATUS, metadata.status)
                    .putString(KEY_IMPORTED_LOCAL_BACKUP_AT, metadata.importedAt)
                    .putString(KEY_IMPORTED_LOCAL_BACKUP_SCHEMA, metadata.schema)
                    .putInt(KEY_IMPORTED_LOCAL_BACKUP_VERSION, metadata.version)
                    .commit()
                check(committed) { "本机资料写入未确认" }
                check(preferences.getString(KEY_DATA, null) == encodedBundle) { "本机资料 bundle 回读不一致" }
                check(preferences.getString(KEY_HISTORY, null) == encodedHistory) { "本机历史回读不一致" }
                check(preferences.all.keys == localBackupProfilePreferenceKeys()) { "本机资料出现非预期存储键" }

                beforeRegistryWrite()
                registryWriteStarted = true
                check(registry.edit().putString(KEY_LOCAL_BACKUP_PROFILE_IDS, nextRegistryRaw).commit()) {
                    "本机资料索引写入未确认"
                }
                check(registry.getString(KEY_LOCAL_BACKUP_PROFILE_IDS, null) == nextRegistryRaw) {
                    "本机资料索引回读不一致"
                }
                check(registry.all.keys == setOf(KEY_LOCAL_BACKUP_PROFILE_IDS)) { "本机资料索引出现非预期字段" }

                check(importedLocalBackupMetadata(appContext, profileId) == metadata) {
                    "本机备份来源记录回读不一致"
                }
                val previewAfterWrite = readUnactivatedProfilePreview(appContext, profileId)
                check(previewAfterWrite.favoriteCount == prepared.preview.favoriteCount) { "收藏回读校验失败" }
                check(previewAfterWrite.playlistCount == prepared.preview.playlistCount) { "歌单回读校验失败" }
                check(previewAfterWrite.playlistSongCount == prepared.preview.playlistSongCount) { "歌单歌曲回读校验失败" }
                check(previewAfterWrite.historyCount == prepared.preview.historyCount) { "播放历史回读校验失败" }
                check(previewAfterWrite.playlistNames == prepared.preview.playlistNames) { "歌单预览回读校验失败" }
                check(previewAfterWrite.songNames == prepared.preview.songNames) { "歌曲预览回读校验失败" }
                val readBack = load(appContext, profileId)
                check(readBack.favs.map { it.key() } == snapshot.favs.map { it.key() }) { "收藏回读校验失败" }
                check(readBack.playlists.map { it.id } == snapshot.playlists.map { it.id }) { "歌单回读校验失败" }
                check(readBack.history.map { it.key() } == snapshot.history.map { it.key() }) { "资料历史回读校验失败" }
                check(loadHistory(appContext, profileId).map { it.key() } == snapshot.history.map { it.key() }) {
                    "独立历史回读校验失败"
                }
                SelectedDomainCopy.copy(appContext, profileId, auxiliaryDomains, auxiliaryOperation)
                afterPayloadVerified()
                check(InactiveImportJournal.complete(appContext, profileId)) { "导入完成记录未确认" }
                metadata
            } catch (failure: Exception) {
                val rollbackProblems = mutableListOf<String>()
                // Auxiliary payload ownership is journaled separately; never erase legacy files.
                if (!SelectedDomainCopy.rollback(appContext, profileId, auxiliaryOperation)) rollbackProblems += "附加数据域"
                if (registryWriteStarted) {
                    val restored = runCatching {
                        val editor = registry.edit().clear()
                        previousRegistryRaw?.let { editor.putString(KEY_LOCAL_BACKUP_PROFILE_IDS, it) }
                        val expectedKeys = if (previousRegistryRaw == null) emptySet() else setOf(KEY_LOCAL_BACKUP_PROFILE_IDS)
                        editor.commit() && registry.all.keys == expectedKeys &&
                            registry.getString(KEY_LOCAL_BACKUP_PROFILE_IDS, null) == previousRegistryRaw
                    }.getOrDefault(false)
                    if (!restored) rollbackProblems += "资料索引"
                }
                if (profileWriteStarted) {
                    val cleared = runCatching { preferences.edit().clear().commit() && preferences.all.isEmpty() }.getOrDefault(false)
                    if (!cleared) rollbackProblems += "新建资料"
                }
                if (rollbackProblems.isEmpty() && !InactiveImportJournal.rolledBack(appContext, profileId)) rollbackProblems += "导入日志"
                if (rollbackProblems.isNotEmpty()) {
                    throw IllegalStateException("本机资料写入失败，且${rollbackProblems.joinToString("与")}回滚未能确认；资料仍未启用", failure)
                }
                if (failure is IllegalStateException) throw failure
                throw IllegalStateException("本机备份导入失败；未保留部分资料", failure)
            }
        }
    }

    /** List only IDs explicitly registered by the local-backup importer; never scan or guess profile IDs. */
    internal fun importedLocalBackupProfiles(ctx: Context): Map<ProfileId, LocalProfileBackupImportMetadata> =
        synchronized(profileSnapshotWriteLock) {
            val registry = ctx.applicationContext.getSharedPreferences(PREF_LOCAL_BACKUP_PROFILE_INDEX, 0)
            readLocalBackupProfileIds(registry).mapNotNull { rawId ->
                val profileId = ProfileId.of(rawId)
                importedLocalBackupMetadata(ctx, profileId)?.let { profileId to it }
            }.toMap()
        }

    internal fun importedLocalBackupMetadata(
        ctx: Context,
        profileId: ProfileId
    ): LocalProfileBackupImportMetadata? = synchronized(profileSnapshotWriteLock) {
        val preferences = profilePrefs(ctx, profileId)
        if (preferences.all.keys != localBackupProfilePreferenceKeys()) return@synchronized null
        val values = preferences.all
        val status = values[KEY_IMPORTED_LOCAL_BACKUP_STATUS] as? String ?: return@synchronized null
        val importedAt = values[KEY_IMPORTED_LOCAL_BACKUP_AT] as? String ?: return@synchronized null
        val schema = values[KEY_IMPORTED_LOCAL_BACKUP_SCHEMA] as? String ?: return@synchronized null
        val version = values[KEY_IMPORTED_LOCAL_BACKUP_VERSION] as? Int ?: return@synchronized null
        if (status != "IMPORTED_LOCAL_NOT_ACTIVE" || schema != InactiveProfileBackupSerializer.SCHEMA ||
            version != InactiveProfileBackupSerializer.VERSION || runCatching { Instant.parse(importedAt) }.isFailure
        ) return@synchronized null
        LocalProfileBackupImportMetadata(status, importedAt, schema, version)
    }

    private fun readLocalBackupProfileIds(registry: SharedPreferences): List<String> {
        if (registry.all.isEmpty()) return emptyList()
        require(registry.all.keys == setOf(KEY_LOCAL_BACKUP_PROFILE_IDS)) { "本机资料索引无效" }
        val raw = registry.all[KEY_LOCAL_BACKUP_PROFILE_IDS] as? String ?: throw IllegalArgumentException("本机资料索引无效")
        val array = try { JSONArray(raw) } catch (failure: Exception) {
            throw IllegalArgumentException("本机资料索引无法读取", failure)
        }
        require(array.length() <= MAX_LOCAL_BACKUP_PROFILES) { "本机资料索引超过上限" }
        val seen = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val id = array.opt(index) as? String ?: throw IllegalArgumentException("本机资料索引条目无效")
            ProfileId.of(id)
            require(seen.add(id)) { "本机资料索引含有重复 ID" }
        }
        return seen.toList()
    }

    private fun localBackupProfilePreferenceKeys(): Set<String> = setOf(
        KEY_DATA, KEY_HISTORY, KEY_IMPORTED_LOCAL_BACKUP_STATUS, KEY_IMPORTED_LOCAL_BACKUP_AT,
        KEY_IMPORTED_LOCAL_BACKUP_SCHEMA, KEY_IMPORTED_LOCAL_BACKUP_VERSION
    )

    internal fun importedSnapshotMetadata(
        ctx: Context,
        profileId: ProfileId
    ): ProfileSnapshotImportMetadata? = synchronized(profileSnapshotWriteLock) {
        val preferences = profilePrefs(ctx, profileId)
        if (!preferences.contains(KEY_DATA) || !preferences.contains(KEY_HISTORY)) return@synchronized null
        val status = preferences.getString(KEY_IMPORTED_SNAPSHOT_STATUS, null) ?: return@synchronized null
        val importedAt = preferences.getString(KEY_IMPORTED_SNAPSHOT_AT, null) ?: return@synchronized null
        val sourceLabel = preferences.getString(KEY_IMPORTED_SNAPSHOT_SOURCE, null) ?: return@synchronized null
        if (status != "IMPORTED_LOCAL_NOT_ACTIVE" || sourceLabel.isBlank() ||
            runCatching { Instant.parse(importedAt) }.isFailure
        ) return@synchronized null
        ProfileSnapshotImportMetadata(status, importedAt, sourceLabel)
    }

    private fun validatedUnactivatedProfileKind(
        ctx: Context,
        profileId: ProfileId,
        preferences: SharedPreferences
    ): String {
        val keys = preferences.all.keys
        val snapshotKeys = setOf(
            KEY_DATA, KEY_HISTORY, KEY_IMPORTED_SNAPSHOT_STATUS, KEY_IMPORTED_SNAPSHOT_AT,
            KEY_IMPORTED_SNAPSHOT_SOURCE
        )
        val localBackupKeys = localBackupProfilePreferenceKeys()
        return when (keys) {
            snapshotKeys -> {
                require(importedSnapshotMetadata(ctx, profileId) != null) { "本机快照导入记录缺失或无效" }
                "server_snapshot"
            }
            localBackupKeys -> {
                require(importedLocalBackupMetadata(ctx, profileId) != null) { "本机备份导入记录缺失或无效" }
                "local_profile_backup"
            }
            else -> throw IllegalArgumentException("本机资料文件缺失或包含非预期数据")
        }
    }

    internal fun applyInactiveProfileSync(ctx: Context, token: ProfileContext, bundle: UserBundle): Boolean = synchronized(profileSnapshotWriteLock) {
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return@synchronized false
        if (!xyz.cncnlp.soundwave.migration.ProfileCatalog.isCurrent(ctx, token)) return@synchronized false
        val preferences = profilePrefs(ctx, owner.id)
        val current = load(ctx, owner.id)
        val merged = xyz.cncnlp.soundwave.sync.CloudUserMerge.merge(current, bundle)
        val historyNow = xyz.cncnlp.soundwave.sync.WebDavHistoryReconciler.reconcile(loadHistory(ctx, owner.id), current.history, merged.history)
        val snapshot = merged.copy(history = historyNow.toMutableList()).syncSnapshot().apply { enabledScripts.clear(); scriptOrder.clear(); avatarB64 = "" }
        val projected = try {
            LocalProfileBackupImporter.parse(InactiveProfileBackupSerializer.serialize(
                snapshot.toCloudJson(includeAvatar = false, includeHistory = true),
                JSONArray().apply { snapshot.history.forEach { put(it.toJson()) } }))
        } catch (_: Exception) { return@synchronized false }
        val raw = projected.bundle.toCloudJson(includeAvatar = false, includeHistory = true).toString()
        val history = JSONArray().apply { projected.bundle.history.forEach { put(it.toJson()) } }.toString()
        if (!RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) return@synchronized false
        preferences.edit().putString(KEY_DATA, raw).putString(KEY_HISTORY, history).commit() &&
            preferences.getString(KEY_DATA, null) == raw && preferences.getString(KEY_HISTORY, null) == history
    }

    /** Exact pending receipt authorizes cleanup only; it never authorizes preview or activation. */
    internal fun rollbackInactiveLocalCopy(ctx: Context, token: ProfileContext,
        afterPhase: (InactiveRollbackJournal.Phase) -> Unit = {}): Boolean = synchronized(profileSnapshotWriteLock) {
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return@synchronized false
        try {
            var receipt = InactiveRollbackJournal.read(ctx, owner.id)
            if (receipt == null) {
                if (InactiveRollbackJournal.contains(ctx, owner.id) ||
                    !xyz.cncnlp.soundwave.migration.ProfileCatalog.isCurrent(ctx, token)) return@synchronized false
                receipt = InactiveRollbackJournal.prepare(ctx, token) ?: return@synchronized false
            }
            if (!receipt.token().matches(token)) return@synchronized false
            // Exact journaled owner only: clear encrypted binding locally without credential reads.
            val binding = ctx.applicationContext.getSharedPreferences("soundwave_profile_webdav_${owner.id.value}", 0)
            if (!binding.edit().clear().commit() || binding.all.isNotEmpty()) return@synchronized false
            val prefs = profilePrefs(ctx, owner.id)
            val registry = ctx.applicationContext.getSharedPreferences(PREF_LOCAL_BACKUP_PROFILE_INDEX, 0)
            // Replay all operations: crash may occur after mutation but before phase publication.
            val ids = readLocalBackupProfileIds(registry).filterNot { it == owner.id.value }
            val editor = registry.edit().clear()
            if (ids.isNotEmpty()) editor.putString(KEY_LOCAL_BACKUP_PROFILE_IDS, JSONArray(ids).toString())
            if (!editor.commit() || owner.id.value in readLocalBackupProfileIds(registry)) return@synchronized false
            receipt = InactiveRollbackJournal.advance(ctx, receipt, InactiveRollbackJournal.Phase.REGISTRY_REMOVED)
                ?: return@synchronized false
            afterPhase(receipt.phase)
            if (!SelectedDomainCopy.rollback(ctx, owner.id)) return@synchronized false
            receipt = InactiveRollbackJournal.advance(ctx, receipt, InactiveRollbackJournal.Phase.AUXILIARY_CLEARED)
                ?: return@synchronized false
            afterPhase(receipt.phase)
            if (!prefs.edit().clear().commit() || prefs.all.isNotEmpty()) return@synchronized false
            receipt = InactiveRollbackJournal.advance(ctx, receipt, InactiveRollbackJournal.Phase.CORE_CLEARED)
                ?: return@synchronized false
            afterPhase(receipt.phase)
            if (!InactiveImportJournal.rolledBack(ctx.applicationContext, owner.id)) return@synchronized false
            InactiveRollbackJournal.finish(ctx, receipt)
        } catch (_: Exception) { false }
    }

    /** Explicit immutable identity for readonly inactive preview; unknown/legacy never falls back. */
    internal fun readUnactivatedProfilePreview(ctx: Context, owner: ProfileContext): UnactivatedProfilePreview {
        val profile = owner.owner as? ProfileOwner.RecordedProfile ?: error("未明确指定资料")
        return readUnactivatedProfilePreview(ctx, profile.id)
    }

    /** Read a validated imported profile only; malformed/missing data is an error, never a legacy fallback. */
    internal fun readUnactivatedProfilePreview(
        ctx: Context,
        profileId: ProfileId
    ): UnactivatedProfilePreview = synchronized(profileSnapshotWriteLock) {
        val preferences = profilePrefs(ctx, profileId)
        val profileKind = validatedUnactivatedProfileKind(ctx, profileId, preferences)

        val rawBundle = preferences.getString(KEY_DATA, null)
            ?: throw IllegalArgumentException("本机资料内容缺失")
        val rawHistory = preferences.getString(KEY_HISTORY, null)
            ?: throw IllegalArgumentException("本机资料历史缺失")
        val root = try {
            JSONObject(rawBundle)
        } catch (failure: Exception) {
            throw IllegalArgumentException("本机资料内容无法解析", failure)
        }
        val expectedBundleKeys = setOf("favs", "pls", "enabledScripts", "scriptOrder", "history")
        require(root.keys().asSequence().toSet() == expectedBundleKeys) { "本机资料字段无效" }
        val favorites = root.optJSONArray("favs") ?: throw IllegalArgumentException("本机收藏数据无效")
        val playlists = root.optJSONArray("pls") ?: throw IllegalArgumentException("本机歌单数据无效")
        val enabledScripts = root.optJSONArray("enabledScripts") ?: throw IllegalArgumentException("本机资料脚本字段无效")
        val scriptOrder = root.optJSONArray("scriptOrder") ?: throw IllegalArgumentException("本机资料脚本顺序字段无效")
        val bundledHistory = root.optJSONArray("history") ?: throw IllegalArgumentException("本机播放历史无效")
        require(enabledScripts.length() == 0 && scriptOrder.length() == 0) { "本机资料包含非预期脚本配置" }

        for (index in 0 until favorites.length()) {
            val item = favorites.optJSONObject(index) ?: throw IllegalArgumentException("本机收藏条目无效")
            if (item.has(CLOUD_TODAY_STYLE_PREFERENCES)) {
                require(item.optJSONObject(CLOUD_TODAY_STYLE_PREFERENCES) != null) { "本机每日风格偏好标记无效" }
            }
            if (item.has(CLOUD_DELETED_FAVORITES)) {
                require(item.optJSONObject(CLOUD_DELETED_FAVORITES) != null) { "本机收藏删除标记无效" }
            } else if (item.has(CLOUD_TODAY_STYLE_PREFERENCES)) {
                // This reserved entry carries bundle metadata, never a favorite song.
            } else {
                require(item.optString("id").isNotBlank()) { "本机收藏歌曲无效" }
            }
        }
        for (index in 0 until playlists.length()) {
            val item = playlists.optJSONObject(index) ?: throw IllegalArgumentException("本机歌单条目无效")
            if (item.has(CLOUD_DELETED_PLAYLISTS) || item.has(CLOUD_DELETED_PLAYLIST_SONGS)) {
                if (item.has(CLOUD_DELETED_PLAYLISTS)) {
                    require(item.optJSONObject(CLOUD_DELETED_PLAYLISTS) != null) { "本机歌单删除标记无效" }
                }
                if (item.has(CLOUD_DELETED_PLAYLIST_SONGS)) {
                    require(item.optJSONObject(CLOUD_DELETED_PLAYLIST_SONGS) != null) { "本机歌单歌曲删除标记无效" }
                }
            } else {
                require(item.optString("id").isNotBlank()) { "本机歌单身份无效" }
                val songs = item.optJSONArray("songs") ?: throw IllegalArgumentException("本机歌单歌曲数据无效")
                for (songIndex in 0 until songs.length()) {
                    val song = songs.optJSONObject(songIndex) ?: throw IllegalArgumentException("本机歌单歌曲无效")
                    require(song.optString("id").isNotBlank()) { "本机歌单歌曲身份无效" }
                }
            }
        }
        for (index in 0 until bundledHistory.length()) {
            val song = bundledHistory.optJSONObject(index) ?: throw IllegalArgumentException("本机播放历史条目无效")
            require(song.optString("id").isNotBlank()) { "本机播放历史歌曲无效" }
        }

        val bundle = try {
            UserBundle.fromCloud(root)
        } catch (failure: Exception) {
            throw IllegalArgumentException("本机资料内容无法读取", failure)
        }
        require(bundle.enabledScripts.isEmpty() && bundle.scriptOrder.isEmpty() && bundle.avatarB64.isBlank()) {
            "本机资料包含非预期的脚本或头像数据"
        }
        val separateHistory = try {
            JSONArray(rawHistory)
        } catch (failure: Exception) {
            throw IllegalArgumentException("本机独立历史无法解析", failure)
        }
        val historySongs = buildList {
            for (index in 0 until separateHistory.length()) {
                val song = separateHistory.optJSONObject(index) ?: throw IllegalArgumentException("本机独立历史条目无效")
                val parsed = Song.fromJson(song)
                require(parsed.id.isNotBlank()) { "本机独立历史歌曲无效" }
                add(parsed)
            }
        }
        require(historySongs.map { it.key() } == bundle.history.map { it.key() }) { "本机独立历史与资料摘要不一致" }
        val allSongs = bundle.favs + bundle.playlists.flatMap { it.songs } + bundle.history + historySongs
        require(allSongs.all { it.resolvedUrl.isBlank() }) { "本机资料含有非预期的本机路径" }

        fun safeName(value: String): String = value.filterNot { it.isISOControl() }.trim().take(100).ifBlank { "未命名" }
        val visiblePlaylists = bundle.playlists.filterNot { it.id in setOf("liked", "local") }
        val sampledSongs = bundle.favs.take(5)
            .ifEmpty { visiblePlaylists.flatMap { it.songs }.take(5) }
            .ifEmpty { if (profileKind == "local_profile_backup") historySongs.take(5) else emptyList() }
        UnactivatedProfilePreview(
            favoriteCount = bundle.favs.size,
            playlistCount = visiblePlaylists.size,
            playlistSongCount = visiblePlaylists.sumOf { it.songs.size },
            historyCount = historySongs.size,
            playlistNames = visiblePlaylists.take(4).map { safeName(it.name) },
            songNames = sampledSongs.map { song ->
                val name = safeName(song.name)
                val artist = song.singer.filterNot { it.isISOControl() }.trim().take(100)
                if (artist.isBlank()) name else "$name — $artist"
            }
        )
    }

    /** Revalidate the same inactive profile at the user's export action, then emit only its local allowlist. */
    internal fun exportUnactivatedProfileBackup(ctx: Context, profileId: ProfileId): ByteArray =
        synchronized(profileSnapshotWriteLock) {
            readUnactivatedProfilePreview(ctx, profileId)
            val preferences = profilePrefs(ctx, profileId)
            validatedUnactivatedProfileKind(ctx, profileId, preferences)
            val rawBundle = preferences.getString(KEY_DATA, null)
                ?: throw IllegalArgumentException("本机资料内容缺失")
            val rawHistory = preferences.getString(KEY_HISTORY, null)
                ?: throw IllegalArgumentException("本机资料历史缺失")
            InactiveProfileBackupSerializer.serialize(JSONObject(rawBundle), JSONArray(rawHistory))
        }

    private fun saveTo(preferences: SharedPreferences, bundle: UserBundle) = synchronized(profileSnapshotWriteLock) {
        val localSnapshot = bundle.syncSnapshot().copy(
            history = PlaybackHistoryPolicy.retainRecent(bundle.history).toMutableList()
        )
        if (deferMainThreadSaves && isMainThread()) {
            // 快照已是独立拷贝（列表/映射均新建），后台序列化不会与主线程后续修改竞争。
            deferredWriter.enqueue(preferences, KEY_DATA) {
                localSnapshot.ensureDefaults()
                localSnapshot.toCloudJson().toString()
            }
        } else {
            deferredWriter.flush(preferences)
            localSnapshot.ensureDefaults()
            preferences.edit().putString(KEY_DATA, localSnapshot.toCloudJson().toString()).apply()
        }
    }

    fun loadHistory(ctx: Context): List<Song> = if (canReadActiveAccountData(ctx)) {
        loadHistoryFrom(prefs(ctx))
    } else emptyList()

    private fun canReadActiveAccountData(ctx: Context): Boolean {
        val owner = accountDataOwner(ctx)
        if (owner.isBlank()) return true
        // 账号系统去除后：归属键与本机资料名（prefs `user`，原账号名）匹配即放行，不再要求登录态。
        val auth = ctx.applicationContext.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
        val user = auth.getString("user", null)?.takeIf { it.isNotBlank() } ?: return false
        return AccountDataIdentity.key(user) == owner
    }

    /**
     * 账号去除升级迁移：升级时处于登出态的资料，其数据已封存在快照区、主命名空间为空。
     * 若封存快照归属本机资料名，将其恢复进主命名空间作为本地资料数据（归属键保持不变），
     * 并移除封存条目。主命名空间已有归属数据时不做任何事。
     */
    internal fun restoreParkedScopeAsLocalProfile(ctx: Context, localUser: String): Boolean =
        synchronized(profileSnapshotWriteLock) {
            if (localUser.isBlank()) return@synchronized false
            if (accountDataOwner(ctx).isNotBlank()) return@synchronized false
            val identityKey = AccountDataIdentity.key(localUser)
            val snapshots = ctx.applicationContext.getSharedPreferences(ACCOUNT_LOCAL_SNAPSHOTS_PREF, Context.MODE_PRIVATE)
            val snapshotKey = accountSnapshotKeyForIdentityKey(identityKey)
            val raw = snapshots.getString(snapshotKey, null) ?: return@synchronized false
            val (bundle, history) = decodeAccountLocalSnapshot(raw, identityKey) ?: return@synchronized false
            val restored = runCatching {
                saveWithIndependentHistory(ctx, bundle, history, localUser)
                accountDataOwner(ctx) == identityKey
            }.getOrDefault(false)
            if (!restored) return@synchronized false
            snapshots.edit().remove(snapshotKey).commit()
            true
        }

    /** Load the explicitly named profile's independent play-history key. */
    fun loadHistory(ctx: Context, profileId: ProfileId): List<Song> = synchronized(profileSnapshotWriteLock) {
        loadHistoryFrom(profilePrefs(ctx, profileId))
    }

    private val historyLock = Any()

    private fun loadHistoryFrom(preferences: SharedPreferences): List<Song> = synchronized(historyLock) {
        val raw = preferences.getString(KEY_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            val out = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    val song = Song.fromJson(o)
                    if (song.id.isNotEmpty()) out.add(song)
                }
            }
            val retained = PlaybackHistoryPolicy.retainRecent(out)
            if (retained != out) {
                val cleaned = JSONArray().apply { retained.forEach { put(it.toJson()) } }.toString()
                runCatching { preferences.edit().putString(KEY_HISTORY, cleaned).commit() }
            }
            retained
        } catch (_: Exception) { emptyList() }
    }

    fun pushHistory(ctx: Context, song: Song, limit: Int = Int.MAX_VALUE) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；历史未写入" }
        pushHistoryTo(prefs(ctx), song, limit)
    }

    /** Push only to the explicitly named profile's independent play-history key. */
    fun pushHistory(ctx: Context, profileId: ProfileId, song: Song, limit: Int = Int.MAX_VALUE) {
        synchronized(profileSnapshotWriteLock) { pushHistoryTo(profilePrefs(ctx, profileId), song, limit) }
    }

    private fun pushHistoryTo(preferences: SharedPreferences, song: Song, limit: Int) {
        synchronized(historyLock) {
            val list = loadHistoryFrom(preferences).toMutableList()
            list.removeAll { it.key() == song.key() }
            list.add(0, song.copy(playedAt = System.currentTimeMillis()))
            val retained = PlaybackHistoryPolicy.retainRecent(list, limit = limit)
            val arr = JSONArray()
            retained.forEach { arr.put(it.toJson()) }
            preferences.edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    /** Replace only the independent history key, preserving timestamps while applying retention. */
    internal fun replaceHistory(ctx: Context, songs: List<Song>, limit: Int = Int.MAX_VALUE) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；历史未写入" }
        val retained = PlaybackHistoryPolicy.retainRecent(songs, limit = limit)
        val raw = JSONArray().apply { retained.forEach { put(it.toJson()) } }.toString()
        val preferences = prefs(ctx)
        synchronized(historyLock) {
            check(preferences.edit().putString(KEY_HISTORY, raw).commit()) { "本机历史写入未确认" }
            check(preferences.getString(KEY_HISTORY, null) == raw) { "本机历史回读不一致" }
        }
    }

    fun removeHistory(ctx: Context, song: Song) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；历史未更新" }
        synchronized(historyLock) {
            val list = loadHistory(ctx).filterNot { it.key() == song.key() }
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    fun clearHistory(ctx: Context) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        check(detachInactiveAccountLocalScope(ctx)) { "账号本机数据隔离失败；历史未更新" }
        synchronized(historyLock) { prefs(ctx).edit().putString(KEY_HISTORY, "[]").apply() }
    }

    /** Remove one history entry only from the explicitly named profile. */
    fun removeHistory(ctx: Context, profileId: ProfileId, song: Song) {
        synchronized(profileSnapshotWriteLock) {
            synchronized(historyLock) {
                val preferences = profilePrefs(ctx, profileId)
                val list = loadHistoryFrom(preferences).filterNot { it.key() == song.key() }
                val arr = JSONArray()
                list.forEach { arr.put(it.toJson()) }
                preferences.edit().putString(KEY_HISTORY, arr.toString()).apply()
            }
        }
    }

    /** Clear only the explicitly named profile's independent play-history key. */
    fun clearHistory(ctx: Context, profileId: ProfileId) {
        synchronized(profileSnapshotWriteLock) {
            synchronized(historyLock) {
                profilePrefs(ctx, profileId).edit().putString(KEY_HISTORY, "[]").apply()
            }
        }
    }

    /** 下载 Worker、Compose 刷新、暂停/删除可能并发读改写；必须在进程内串行化。 */
    private val downloadLock = Any()

    fun loadDownloads(ctx: Context): List<DownloadItem> {
        synchronized(downloadLock) {
            val raw = prefs(ctx).getString(KEY_DOWNLOADS, "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<DownloadItem>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toDownloadItem()?.let { out.add(it) }
                }
                out
            } catch (_: Exception) { emptyList() }
        }
    }

    /** Read only download records stored in the explicitly named profile namespace. */
    fun loadDownloads(ctx: Context, profileId: ProfileId): List<DownloadItem> {
        synchronized(downloadLock) {
            val raw = profilePrefs(ctx, profileId).getString(KEY_DOWNLOADS, "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<DownloadItem>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toDownloadItem()?.let { out.add(it) }
                }
                out
            } catch (_: Exception) { emptyList() }
        }
    }

    /**
     * 本地播放唯一入口：只认同平台同官方 ID（或同平台 hash）的完成下载记录，且必须可读。
     * 禁止按歌名猜文件，避免同名/live/翻唱被静默替换成错误音频。
     */
    fun findReadableDownload(ctx: Context, song: Song): DownloadItem? {
        fun readable(path: String): Boolean = try {
            when {
                path.isBlank() || path.startsWith("pending:") -> false
                path.startsWith("content://") -> ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(path), "r")?.use { true } ?: false
                else -> java.io.File(path.removePrefix("file://")).isFile
            }
        } catch (_: Exception) { false }
        return loadDownloads(ctx).firstOrNull { item ->
            if (item.status != "done" || !readable(item.path)) return@firstOrNull false
            val sameOfficialId = song.id.isNotBlank() && item.song.id == song.id && item.song.source.equals(song.source, true)
            val sameHash = song.hash.isNotBlank() && item.song.hash == song.hash && item.song.source.equals(song.source, true)
            // 旧记录有时没有 source：只允许明确相同 key；不能放宽为标题匹配。
            val sameKey = DownloadTaskIdentity.songKey(item.song) == DownloadTaskIdentity.songKey(song)
            sameKey || sameOfficialId || sameHash
        }
    }

    internal fun addDownloads(ctx: Context, items: List<DownloadItem>) {
        if (items.isEmpty()) return
        synchronized(downloadLock) {
            val list = loadDownloads(ctx).toMutableList()
            items.forEach { upsertDownload(list, it) }
            saveDownloads(ctx, list)
        }
    }

    /** Generic restore replaces the records in one checked commit; never clears then queues N applies. */
    internal fun restoreDownloadsDurably(ctx: Context, items: List<DownloadItem>) = synchronized(downloadLock) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        val out = mutableListOf<DownloadItem>()
        items.forEach { upsertDownload(out, it) } // preserve existing restore's ordering/dedup semantics
        val raw = encodeDownloads(out)
        val preferences = prefs(ctx)
        check(preferences.edit().putString(KEY_DOWNLOADS, raw).commit()) { "下载记录恢复提交未确认" }
        check(preferences.getString(KEY_DOWNLOADS, null) == raw) { "下载记录恢复回读不一致" }
    }

    fun addDownload(ctx: Context, item: DownloadItem) {
        synchronized(downloadLock) {
            val list = loadDownloads(ctx).toMutableList()
            upsertDownload(list, item)
            saveDownloads(ctx, list)
        }
    }

    /** Add or update one download record only in the explicitly named profile namespace. */
    fun addDownload(ctx: Context, profileId: ProfileId, item: DownloadItem) {
        synchronized(downloadLock) {
            val list = loadDownloads(ctx, profileId).toMutableList()
            upsertDownload(list, item)
            saveDownloads(ctx, profileId, list)
        }
    }

    /**
     * Synchronously persists the completed output record. SAF replacement must not remove the
     * previous document until this returns true; false is treated as an ambiguous commit.
     */
    fun addDownloadDurably(ctx: Context, item: DownloadItem): Boolean = synchronized(downloadLock) {
        if (!RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) return@synchronized false
        val list = loadDownloads(ctx).toMutableList()
        upsertDownload(list, item)
        prefs(ctx).edit().putString(KEY_DOWNLOADS, encodeDownloads(list)).commit()
    }

    /** Synchronously persist a completed record only in the explicitly named profile namespace. */
    fun addDownloadDurably(ctx: Context, profileId: ProfileId, item: DownloadItem): Boolean =
        synchronized(downloadLock) {
            val list = loadDownloads(ctx, profileId).toMutableList()
            upsertDownload(list, item)
            profilePrefs(ctx, profileId).edit().putString(KEY_DOWNLOADS, encodeDownloads(list)).commit()
        }

    private fun upsertDownload(list: MutableList<DownloadItem>, item: DownloadItem) {
        // Worker 的进度回写必须保持用户队列位置，不能每次更新都把该项跳回第一位。
        val oldIndex = list.indexOfFirst {
            (item.taskId.isNotBlank() && it.taskId == item.taskId) ||
                (it.path == item.path && item.path.isNotBlank()) ||
                (DownloadTaskIdentity.songKey(it.song) == DownloadTaskIdentity.songKey(item.song) && it.quality == item.quality)
        }
        list.removeAll {
            (item.taskId.isNotBlank() && it.taskId == item.taskId) ||
                (it.path == item.path && item.path.isNotBlank()) ||
                (DownloadTaskIdentity.songKey(it.song) == DownloadTaskIdentity.songKey(item.song) && it.quality == item.quality)
        }
        list.add(if (oldIndex >= 0) oldIndex.coerceAtMost(list.size) else 0, item)
    }

    /** 用户显式调整的队列顺序；Worker 后续写进度时 addDownload 会保持该位置。 */
    fun moveDownload(ctx: Context, item: DownloadItem, delta: Int): Boolean {
        synchronized(downloadLock) {
            if (delta == 0) return false
            val list = loadDownloads(ctx).toMutableList()
            val from = list.indexOfFirst {
                (item.taskId.isNotBlank() && it.taskId == item.taskId) ||
                    (it.path == item.path && item.path.isNotBlank()) ||
                    (DownloadTaskIdentity.songKey(it.song) == DownloadTaskIdentity.songKey(item.song) && it.quality == item.quality)
            }
            if (from < 0) return false
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (to == from) return false
            val moved = list.removeAt(from)
            list.add(to, moved)
            saveDownloads(ctx, list)
            return true
        }
    }

    /** Move a record only within the explicitly named profile's download queue. */
    fun moveDownload(ctx: Context, profileId: ProfileId, item: DownloadItem, delta: Int): Boolean {
        synchronized(downloadLock) {
            if (delta == 0) return false
            val list = loadDownloads(ctx, profileId).toMutableList()
            val from = list.indexOfFirst {
                (item.taskId.isNotBlank() && it.taskId == item.taskId) ||
                    (it.path == item.path && item.path.isNotBlank()) ||
                    (DownloadTaskIdentity.songKey(it.song) == DownloadTaskIdentity.songKey(item.song) && it.quality == item.quality)
            }
            if (from < 0) return false
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (to == from) return false
            val moved = list.removeAt(from)
            list.add(to, moved)
            saveDownloads(ctx, profileId, list)
            return true
        }
    }

    /** 失败原因分组：按 DownloadItem 的 failure 维度分类，返回 原因 -> 条目 映射。 */
    fun groupDownloadFailures(items: List<DownloadItem>): Map<String, List<DownloadItem>> {
        return items.filter { it.status == "failed" }.groupBy { d ->
            when {
                d.resolveFailure.isNotBlank() -> when (d.resolveFailure) {
                    "NO_ENABLED_SCRIPT" -> "无可用脚本"
                    "NO_PLAYABLE_URL" -> "脚本无链接"
                    "TIMEOUT" -> "取链超时"
                    "STALE_REQUEST" -> "请求过期"
                    else -> "取链失败(${d.resolveFailure})"
                }
                d.error.contains("HTTP 401") || d.error.contains("HTTP 403") -> "地址被拒绝"
                d.error.contains("HTTP 404") || d.error.contains("410") -> "地址失效"
                d.error.contains("HTTP 5") -> "服务端异常"
                d.error.contains("timeout") || d.error.contains("timed out") -> "连接超时"
                d.error.contains("Range") || d.error.contains("范围") -> "分段下载异常"
                d.error.isNotBlank() -> "其他(${d.error.take(24)})"
                else -> "未知"
            }
        }
    }

    /** 与 groupDownloadFailures 相反语义：把原因分组名还原为可用于重试请求的实例列表。 */
    fun failuresInGroup(items: List<DownloadItem>, groupLabel: String): List<DownloadItem> =
        groupDownloadFailures(items)[groupLabel].orEmpty()

    private fun DownloadItem.toJson(): JSONObject = JSONObject().apply {
        put("song", song.toJson())
        put("path", path)
        put("size", size)
        put("time", time)
        put("status", status)
        put("progress", progress.toDouble())
        put("error", error)
        put("quality", quality)
        put("platform", platform)
        put("scriptId", scriptId)
        put("resolveFailure", resolveFailure)
        put("taskId", taskId)
    }

    private fun JSONObject.toDownloadItem(): DownloadItem? {
        val path = optString("path")
        if (path.isEmpty()) return null
        return DownloadItem(
            song = Song.fromJson(optJSONObject("song") ?: this),
            path = path,
            size = optLong("size"),
            time = optLong("time"),
            status = optString("status", "done"),
            progress = optDouble("progress", 1.0).toFloat(),
            error = optString("error"),
            quality = optString("quality"),
            platform = optString("platform"),
            scriptId = optString("scriptId"),
            resolveFailure = optString("resolveFailure"),
            taskId = optString("taskId")
        )
    }

    private fun saveDownloads(ctx: Context, list: List<DownloadItem>) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        prefs(ctx).edit().putString(KEY_DOWNLOADS, encodeDownloads(list)).apply()
    }

    private fun saveDownloads(ctx: Context, profileId: ProfileId, list: List<DownloadItem>) {
        profilePrefs(ctx, profileId).edit().putString(KEY_DOWNLOADS, encodeDownloads(list)).apply()
    }

    private fun encodeDownloads(list: List<DownloadItem>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun removeDownload(ctx: Context, item: DownloadItem) {
        synchronized(downloadLock) {
            val list = loadDownloads(ctx).filterNot { it.path == item.path }
            saveDownloads(ctx, list)
        }
    }

    /** Remove one record only from the explicitly named profile's download namespace. */
    fun removeDownload(ctx: Context, profileId: ProfileId, item: DownloadItem) {
        synchronized(downloadLock) {
            val list = loadDownloads(ctx, profileId).filterNot { it.path == item.path }
            saveDownloads(ctx, profileId, list)
        }
    }

    /** 批量删除下载记录：锁内一次读取+一次写入，避免逐条全量重写导致卡顿/并发写损坏。 */
    fun removeDownloads(ctx: Context, items: List<DownloadItem>) {
        if (items.isEmpty()) return
        synchronized(downloadLock) {
            val paths = items.mapTo(HashSet()) { it.path }
            val list = loadDownloads(ctx).filterNot { it.path in paths }
            saveDownloads(ctx, list)
        }
    }

    /** Remove a batch of records only from the explicitly named profile namespace. */
    fun removeDownloads(ctx: Context, profileId: ProfileId, items: List<DownloadItem>) {
        if (items.isEmpty()) return
        synchronized(downloadLock) {
            val paths = items.mapTo(HashSet()) { it.path }
            val list = loadDownloads(ctx, profileId).filterNot { it.path in paths }
            saveDownloads(ctx, profileId, list)
        }
    }

    fun clearDownloads(ctx: Context) {
        check(RuntimeProvenanceInventory.invalidateBeforeMutation(ctx)) { "来源版本更新失败；数据未写入" }
        synchronized(downloadLock) {
            prefs(ctx).edit().putString(KEY_DOWNLOADS, "[]").apply()
        }
    }

    /** Clear only the explicitly named profile's download records; audio files are untouched. */
    fun clearDownloads(ctx: Context, profileId: ProfileId) {
        synchronized(downloadLock) {
            profilePrefs(ctx, profileId).edit().putString(KEY_DOWNLOADS, "[]").apply()
        }
    }
}
