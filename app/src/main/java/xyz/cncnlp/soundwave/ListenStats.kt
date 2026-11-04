package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.sync.SongPlayCountCounterMerge

/**
 * 听歌统计：今日 / 本周 / 累计听歌时长，最常听歌曲 / 歌手（含播放时长）。
 * 基于 SharedPreferences，写入频率低（每曲结束时或定期）。
 */
object ListenStats {
    private const val PREF = "listen_stats_v1"
    private const val K_TOTAL_SEC = "total_sec"          // 累计秒数（Long）
    private const val K_TODAY_DATE = "today_date"        // yyyyMMdd
    private const val K_TODAY_SEC = "today_sec"
    private const val K_WEEK_LABEL = "week_label"        // yyyyWW
    private const val K_WEEK_SEC = "week_sec"
    private const val K_SONG_MAP = "song_map_v1"         // id -> {name,singer,sec}
    private const val K_ARTIST_MAP = "artist_map_v1"     // singer -> sec
    private const val K_DAILY_LISTEN_STATS = "daily_listen_stats_v1"
    private const val K_ACCOUNT_DAILY_LISTEN_STATS_V1 = "account_daily_listen_stats_v1"
    private const val K_ACCOUNT_DAILY_LISTEN_STATS_V2 = "account_daily_listen_stats_v2"
    private const val K_V1_ARCHIVE_COMPLETE = "listen_stats_v1_archive_complete"
    private const val V1_ARCHIVE_PREFIX = "listen_stats_v1_archive__"
    private const val K_PLAY_COUNT_DEVICE_ID = "play_count_device_id_v1"
    private const val K_PLAY_COUNT_BY_DEVICE = "playCountByDevice"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var prefsOwner: ProfileContext? = null
    @Volatile private var prefsContext: Context? = null
    private val statsWriteLock = Any()

    @Synchronized
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val owner = ProfileWorkIdentity.runtime()
        if (prefs == null || prefsOwner?.matches(owner) != true || prefsContext !== app) {
            prefs = ContextPreferences.open(app, PREF, 0, owner)
            prefsOwner = owner
            prefsContext = app
            ensureDatedStats(prefs!!, System.currentTimeMillis())
        }
    }

    private fun p(): SharedPreferences = prefs ?: throw IllegalStateException("ListenStats not init")

    /** Bind the runtime preference handle without migrating or rewriting the shared guest stream. */
    @Synchronized
    private fun initAccountBackfill(ctx: Context) {
        val app = ctx.applicationContext
        val owner = ProfileWorkIdentity.runtime()
        if (prefs == null || prefsOwner?.matches(owner) != true || prefsContext !== app) {
            prefs = ContextPreferences.open(app, PREF, 0, owner)
            prefsOwner = owner
            prefsContext = app
        }
    }

    internal fun activeAccountUser(ctx: Context): String? {
        // 账号系统去除后：统计归属身份 = 本机资料名（prefs `user`，原账号名），不再要求登录态。
        val auth = ctx.applicationContext.getSharedPreferences("soundwave", 0)
        return auth.getString("user", null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun accountStatsKey(user: String): String = AccountDataIdentity.key(user)

    /** Explicit profile storage shares the profile's existing private preference namespace. */
    private fun profilePrefs(ctx: Context, profileId: ProfileId): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(profileId.preferencesName, 0).also {
            ensureDatedStats(it, System.currentTimeMillis())
        }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun todayKey(): String = ListeningCalendar.dayKey(System.currentTimeMillis())

    private fun weekKey(): String = ListeningCalendar.weekKey(System.currentTimeMillis())

    // ── write ─────────────────────────────────────────────────────────────────

    /** 仅累计时长（不增加次数）。进度 ticker 调用。 */
    fun addDuration(ctx: Context, song: Song, sec: Long): Boolean =
        addDuration(ctx, song, sec, activeAccountUser(ctx))

    internal fun addDuration(ctx: Context, song: Song, sec: Long, accountUser: String?): Boolean {
        if (sec <= 0) return true
        init(ctx)
        return updateStats(p(), song, sec, incrementCount = false, dropSensitiveCoverQuery = false, accountUser = accountUser)
    }

    /** 仅累计指定本机资料的时长（不增加次数）；不会选择或关联活动资料。 */
    fun addDuration(ctx: Context, profileId: ProfileId, song: Song, sec: Long): Boolean {
        if (sec <= 0) return true
        return updateStats(profilePrefs(ctx, profileId), song, sec, incrementCount = false, dropSensitiveCoverQuery = true)
    }

    /** Assemble every stats surface before one synchronous atomic commit. */
    internal fun updateStats(
        preferences: SharedPreferences,
        song: Song,
        sec: Long,
        incrementCount: Boolean,
        dropSensitiveCoverQuery: Boolean,
        accountUser: String? = null,
    ): Boolean = synchronized(statsWriteLock) {
        updateStatsLocked(preferences, song, sec, incrementCount, dropSensitiveCoverQuery, accountUser)
    }

    private fun updateStatsLocked(
        preferences: SharedPreferences,
        song: Song,
        sec: Long,
        incrementCount: Boolean,
        dropSensitiveCoverQuery: Boolean,
        accountUser: String?,
    ): Boolean {
        if (sec <= 0L && !incrementCount) return true
        return try {
            val nowMs = System.currentTimeMillis()
            if (!ensureDatedStats(preferences, nowMs)) return false
            val deviceId = getOrCreatePlayCountDeviceId(preferences) ?: return false
            val cover = if (dropSensitiveCoverQuery && hasSensitiveCoverQuery(song.cover)) "" else song.cover
            // The shared v1 stream is guest/legacy-device data only. Authenticated events
            // belong exclusively to their captured account's v2 bucket.
            val currentDaily = preferences.getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}"
            val dated = if (accountUser.isNullOrEmpty()) ListenStatsDailyStore.record(
                currentDaily, song.copy(cover = cover), deviceId, sec.coerceAtLeast(0L), incrementCount, nowMs
            ) else currentDaily
            val accountStats = accountUser?.takeIf { it.isNotEmpty() }?.let { account ->
                val allAccounts = JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
                val key = accountStatsKey(account)
                val current = allAccounts.optString(key, "{}")
                val accountSong = song.copy(cover = song.cover.takeUnless(::hasSensitiveCoverQuery).orEmpty())
                allAccounts.put(key, ListenStatsDailyStore.record(
                    current, accountSong, deviceId, sec.coerceAtLeast(0L), incrementCount, nowMs
                ))
                allAccounts.toString()
            }
            persistDatedStats(preferences, dated, nowMs, accountStats)
        } catch (_: Exception) {
            false
        }
    }

    /** Upgrade undated lifetime aggregates as local-only legacy data, then keep only dated 365-day facts. */
    private fun ensureDatedStats(preferences: SharedPreferences, nowMs: Long): Boolean = synchronized(statsWriteLock) {
        try {
            if (!archiveOriginalV1Stats(preferences)) return@synchronized false
            val raw = if (preferences.contains(K_DAILY_LISTEN_STATS)) {
                preferences.getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}"
            } else {
                val datedSeconds = preferences.all.entries.mapNotNull { (key, value) ->
                    if (!key.startsWith("day_sec_")) return@mapNotNull null
                    val dayKey = key.removePrefix("day_sec_")
                    val seconds = (value as? Long)?.coerceAtLeast(0L) ?: 0L
                    dayKey to seconds
                }.toMap()
                val nowDate = ListeningCalendar.dayKey(nowMs)
                ListenStatsDailyStore.migrateLegacy(
                    songMapJson = preferences.getString(K_SONG_MAP, "{}") ?: "{}",
                    artistMapJson = preferences.getString(K_ARTIST_MAP, "{}") ?: "{}",
                    platformMapJson = preferences.getString("platform_map_v1", "{}") ?: "{}",
                    datedDailySeconds = datedSeconds,
                    todayDate = preferences.getString(K_TODAY_DATE, nowDate) ?: nowDate,
                    todaySeconds = preferences.getLong(K_TODAY_SEC, 0L).coerceAtLeast(0L),
                    nowMs = nowMs
                )
            }
            val local = ListenStatsDailyStore.localProjection(raw, nowMs)
            persistDatedStats(preferences, local, nowMs)
        } catch (_: Exception) {
            false
        }
    }

    /** Preserve original device-local bytes before the first normal retention/canonicalization rewrite. */
    private fun archiveOriginalV1Stats(preferences: SharedPreferences): Boolean {
        if (preferences.getBoolean(K_V1_ARCHIVE_COMPLETE, false)) return true
        val knownKeys = setOf(
            K_TOTAL_SEC, K_TODAY_DATE, K_TODAY_SEC, K_WEEK_LABEL, K_WEEK_SEC,
            K_SONG_MAP, K_ARTIST_MAP, "platform_map_v1", K_DAILY_LISTEN_STATS,
            K_ACCOUNT_DAILY_LISTEN_STATS_V1, K_PLAY_COUNT_DEVICE_ID, K_PLAY_COUNT_BY_DEVICE
        )
        val original = preferences.all.filterKeys { it in knownKeys || it.startsWith("day_sec_") }
        val editor = preferences.edit()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(V1_ARCHIVE_PREFIX + key, value)
                is Long -> editor.putLong(V1_ARCHIVE_PREFIX + key, value)
                is Int -> editor.putInt(V1_ARCHIVE_PREFIX + key, value)
                is Boolean -> editor.putBoolean(V1_ARCHIVE_PREFIX + key, value)
                is Float -> editor.putFloat(V1_ARCHIVE_PREFIX + key, value)
                is Set<*> -> editor.putStringSet(V1_ARCHIVE_PREFIX + key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.putBoolean(K_V1_ARCHIVE_COMPLETE, true)
        if (!editor.commit() || !preferences.getBoolean(K_V1_ARCHIVE_COMPLETE, false)) return false
        return original.all { (key, value) -> preferences.all[V1_ARCHIVE_PREFIX + key] == value }
    }

    /** Commit the daily facts and every local stats summary atomically; retry only already-applied values. */
    private fun persistDatedStats(
        preferences: SharedPreferences,
        rawJson: String,
        nowMs: Long,
        accountDailyStatsJson: String? = null
    ): Boolean {
        val localJson = ListenStatsDailyStore.localProjection(rawJson, nowMs)
        val summary = ListenStatsDailyStore.summary(localJson, nowMs)
        val songs = JSONObject()
        summary.songs.forEach { (key, stat) ->
            val devices = JSONObject().apply { stat.deviceCounts.forEach { (device, count) -> if (count > 0L) put(device, count) } }
            val songJson = JSONObject()
                .put("name", stat.name)
                .put("singer", stat.singer)
                .put("sec", stat.seconds)
                .put("cnt", stat.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .put(K_PLAY_COUNT_BY_DEVICE, devices)
            if (stat.cover.isNotBlank()) songJson.put("cover", stat.cover)
            songs.put(key, songJson)
        }
        val artists = JSONObject().apply { summary.artists.forEach { (name, sec) -> if (sec > 0L) put(name, sec) } }
        val platforms = JSONObject().apply { summary.platforms.forEach { (name, sec) -> if (sec > 0L) put(name, sec) } }
        val nowDate = ListeningCalendar.dayKey(nowMs)
        val currentWeek = ListeningCalendar.weekKey(nowMs)
        val currentWeekSeconds = summary.dailySeconds.entries.sumOf { (dayKey, seconds) ->
            val day = runCatching { java.time.LocalDate.parse(dayKey, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                ?: return@sumOf 0L
            val millis = day.atStartOfDay(ListeningCalendar.zoneId).toInstant().toEpochMilli()
            if (ListeningCalendar.weekKey(millis) == currentWeek) seconds else 0L
        }
        val targetDailyKeys = summary.dailySeconds.keys.mapTo(linkedSetOf()) { "day_sec_$it" }
        val existingDailyKeys = preferences.all.keys.filter { it.startsWith("day_sec_") }.toSet()
        val editor = preferences.edit()
            .putString(K_DAILY_LISTEN_STATS, localJson)
            .putLong(K_TOTAL_SEC, summary.totalSeconds)
            .putString(K_TODAY_DATE, nowDate)
            .putLong(K_TODAY_SEC, summary.dailySeconds[nowDate] ?: 0L)
            .putString(K_WEEK_LABEL, currentWeek)
            .putLong(K_WEEK_SEC, currentWeekSeconds)
            .putString(K_SONG_MAP, songs.toString())
            .putString(K_ARTIST_MAP, artists.toString())
            .putString("platform_map_v1", platforms.toString())
        accountDailyStatsJson?.let { editor.putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, it) }
        existingDailyKeys.filterNot { it in targetDailyKeys }.forEach(editor::remove)
        summary.dailySeconds.forEach { (dayKey, seconds) -> editor.putLong("day_sec_$dayKey", seconds) }
        val values = linkedMapOf<String, Any>(
            K_DAILY_LISTEN_STATS to localJson,
            K_TOTAL_SEC to summary.totalSeconds,
            K_TODAY_DATE to nowDate,
            K_TODAY_SEC to (summary.dailySeconds[nowDate] ?: 0L),
            K_WEEK_LABEL to currentWeek,
            K_WEEK_SEC to currentWeekSeconds,
            K_SONG_MAP to songs.toString(),
            K_ARTIST_MAP to artists.toString(),
            "platform_map_v1" to platforms.toString()
        )
        accountDailyStatsJson?.let { values[K_ACCOUNT_DAILY_LISTEN_STATS_V2] = it }
        fun matches(): Boolean = values.all { (key, value) ->
            when (value) {
                is String -> preferences.getString(key, null) == value
                is Long -> preferences.getLong(key, Long.MIN_VALUE) == value
                else -> false
            }
        } && preferences.all.keys.filter { it.startsWith("day_sec_") }.toSet() == targetDailyKeys &&
            summary.dailySeconds.all { (dayKey, seconds) -> preferences.getLong("day_sec_$dayKey", Long.MIN_VALUE) == seconds }
        // commit() mutates memory even on disk failure. Always ask the synchronous writer:
        // Android retries a pending disk generation even when these values are unchanged,
        // and skips the physical write when the generation is already durable.
        if (editor.commit() && matches()) return true
        if (!matches()) return false
        val retry = preferences.edit()
            .putString(K_DAILY_LISTEN_STATS, localJson)
            .putLong(K_TOTAL_SEC, summary.totalSeconds)
            .putString(K_TODAY_DATE, nowDate)
            .putLong(K_TODAY_SEC, summary.dailySeconds[nowDate] ?: 0L)
            .putString(K_WEEK_LABEL, currentWeek)
            .putLong(K_WEEK_SEC, currentWeekSeconds)
            .putString(K_SONG_MAP, songs.toString())
            .putString(K_ARTIST_MAP, artists.toString())
            .putString("platform_map_v1", platforms.toString())
        accountDailyStatsJson?.let { retry.putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, it) }
        existingDailyKeys.filterNot { it in targetDailyKeys }.forEach(retry::remove)
        summary.dailySeconds.forEach { (dayKey, seconds) -> retry.putLong("day_sec_$dayKey", seconds) }
        val committed = retry.commit()
        if (!committed) android.util.Log.w("ListenStats", "daily stats disk commit failed after in-memory apply")
        return committed && matches()
    }

    /** Cached init and a new owner generation must not turn failed in-memory writes into a snapshot. */
    private fun confirmedSnapshotPreferences(): SharedPreferences = p().also {
        if (!ensureDatedStats(it, System.currentTimeMillis())) throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.UNCONFIRMED)
    }

    /** Account cloud projection: only dated facts within 30 days; undated legacy totals never leave this device. */
    internal fun accountDailyListenStats(ctx: Context, nowMs: Long = System.currentTimeMillis()): String {
        init(ctx)
        return synchronized(statsWriteLock) {
            val account = activeAccountUser(ctx) ?: return@synchronized "{}"
            val perAccount = JSONObject(confirmedSnapshotPreferences()
                .getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
            ListenStatsDailyStore.cloudProjection(perAccount.optString(accountStatsKey(account), "{}"), nowMs)
        }
    }

    /** Build one account's cloud projection from its remote baseline plus only this device's local contribution. */
    internal fun accountDailyListenStatsForAccount(
        ctx: Context,
        accountUser: String,
        remoteBaselineJson: String,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        init(ctx)
        return synchronized(statsWriteLock) {
            if (accountUser.isEmpty() || activeAccountUser(ctx) != accountUser) return@synchronized "{}"
            val preferences = confirmedSnapshotPreferences()
            val perAccount = JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
            val localAccountFacts = perAccount.optString(accountStatsKey(accountUser), "{}")
            val combined = ListenStatsDailyStore.mergeLocalAndCloud(localAccountFacts, remoteBaselineJson, nowMs)
            ListenStatsDailyStore.cloudProjection(combined, nowMs)
        }
    }

    /** Only the already authenticated, server-confirmed rename flow may move a v2 namespace. */
    internal fun renameAccountDailyStats(ctx: Context, oldUser: String, newUser: String): Boolean {
        if (oldUser.isBlank() || newUser.isBlank() || activeAccountUser(ctx) != oldUser) return false
        init(ctx)
        return synchronized(statsWriteLock) {
            runCatching {
                val preferences = confirmedSnapshotPreferences()
                val accounts = JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
                val oldKey = accountStatsKey(oldUser)
                val newKey = accountStatsKey(newUser)
                if (oldKey == newKey) return@synchronized true
                val original = accounts.optString(oldKey, "{}")
                val destination = accounts.optString(newKey, "{}")
                if (destination != "{}" && destination != original) return@synchronized false
                if (accounts.has(oldKey)) accounts.put(newKey, original)
                accounts.remove(oldKey)
                val raw = accounts.toString()
                preferences.edit().putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, raw).commit() &&
                    preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) == raw
            }.getOrDefault(false)
        }
    }

    internal fun localDailyListenStats(ctx: Context, nowMs: Long = System.currentTimeMillis()): String {
        init(ctx)
        return synchronized(statsWriteLock) {
            val preferences = confirmedSnapshotPreferences()
            val account = activeAccountUser(ctx)
            val raw = if (account == null) preferences.getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}"
                else accountFacts(preferences, account)
            ListenStatsDailyStore.localProjection(raw, nowMs)
        }
    }

    /** A private, hashed-owner summary cache keeps generic UI reads scoped to the selected account. */
    private fun selectedSummaryPreferences(ctx: Context, nowMs: Long = System.currentTimeMillis()): SharedPreferences {
        init(ctx)
        val account = activeAccountUser(ctx) ?: return p()
        return synchronized(statsWriteLock) {
            val source = confirmedSnapshotPreferences()
            val facts = accountFacts(source, account)
            val cache = ctx.applicationContext.getSharedPreferences(
                "listen_stats_account_summary_${accountStatsKey(account)}", Context.MODE_PRIVATE
            )
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(facts.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val day = ListeningCalendar.dayKey(nowMs)
            if (cache.getString("source_fingerprint", null) != fingerprint || cache.getString("summary_day", null) != day) {
                if (!persistDatedStats(cache, facts, nowMs)) return@synchronized cache
                val marker = cache.edit().putString("source_fingerprint", fingerprint).putString("summary_day", day)
                if (!marker.commit()) android.util.Log.w("ListenStats", "account summary cache marker commit failed")
            }
            cache
        }
    }

    private fun accountFacts(preferences: SharedPreferences, account: String): String = try {
        JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
            .optString(accountStatsKey(account), "{}")
    } catch (_: Exception) { "{}" }

    /** Merge authorized remote 30-day facts without removing any local data inside the 365-day window. */
    internal fun mergeAccountDailyListenStats(ctx: Context, cloudJson: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        init(ctx)
        return synchronized(statsWriteLock) {
            runCatching {
                val merged = ListenStatsDailyStore.mergeLocalAndCloud(
                    p().getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}", cloudJson, nowMs
                )
                persistDatedStats(p(), merged, nowMs)
            }.getOrDefault(false)
        }
    }

    /** Merge authenticated remote facts into only the selected account's v2 slot. */
    internal fun mergeAccountDailyListenStatsForAccount(
        ctx: Context,
        accountUser: String,
        cloudJson: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (accountUser.isEmpty() || activeAccountUser(ctx) != accountUser) return false
        init(ctx)
        return synchronized(statsWriteLock) {
            if (activeAccountUser(ctx) != accountUser) return@synchronized false
            runCatching {
                val preferences = p()
                val allAccounts = JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
                val key = accountStatsKey(accountUser)
                val local = allAccounts.optString(key, "{}")
                val merged = ListenStatsDailyStore.localProjection(
                    ListenStatsDailyStore.mergeLocalAndCloud(local, cloudJson, nowMs), nowMs
                )
                allAccounts.put(key, merged)
                val raw = allAccounts.toString()
                val committed = preferences.edit().putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, raw).commit()
                if (committed && preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) == raw) {
                    true
                } else if (preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) != raw) {
                    false
                } else {
                    preferences.edit().putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, raw).commit() &&
                        preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) == raw
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Backfill only when the active account and LocalStore's v2 owner both prove
     * the supplied history's owner. Unassigned/legacy histories are deliberately
     * not guessed to be guest or account data.
     */
    fun backfillFromHistory(ctx: Context, history: List<Song>, nowMs: Long = System.currentTimeMillis()): Boolean {
        val account = activeAccountUser(ctx) ?: return false
        val owner = AccountDataIdentity.key(account)
        if (LocalStore.accountDataOwner(ctx) != owner || LocalStore.loadHistory(ctx) != history ||
            activeAccountUser(ctx) != account || LocalStore.accountDataOwner(ctx) != owner
        ) return false
        return backfillAccountHistory(ctx, account, owner, history, nowMs)
    }

    /** Account estimates are committed only into that exact account's v2 bucket. */
    internal fun backfillAccountHistory(
        ctx: Context,
        accountUser: String,
        expectedOwner: String,
        history: List<Song>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val app = ctx.applicationContext
        if (accountUser.isBlank() || expectedOwner != AccountDataIdentity.key(accountUser) ||
            activeAccountUser(app) != accountUser || LocalStore.accountDataOwner(app) != expectedOwner
        ) return false
        val storedHistory = LocalStore.loadHistory(app)
        if (storedHistory != history || activeAccountUser(app) != accountUser ||
            LocalStore.accountDataOwner(app) != expectedOwner
        ) return false
        if (history.isEmpty()) return true
        initAccountBackfill(app)
        return synchronized(statsWriteLock) {
            runCatching {
                if (activeAccountUser(app) != accountUser || LocalStore.accountDataOwner(app) != expectedOwner) {
                    return@synchronized false
                }
                val preferences = p()
                val allAccounts = JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
                val key = accountStatsKey(accountUser)
                val current = allAccounts.optString(key, "{}")
                val filled = ListenStatsDailyStore.applyHistoryBackfill(current, history, nowMs)
                allAccounts.put(key, ListenStatsDailyStore.localProjection(filled, nowMs))
                val raw = allAccounts.toString()
                if (activeAccountUser(app) != accountUser || LocalStore.accountDataOwner(app) != expectedOwner) {
                    return@synchronized false
                }
                val committed = preferences.edit().putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, raw).commit()
                if (committed && preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) == raw) true
                else if (preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) != raw) false
                else preferences.edit().putString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, raw).commit() &&
                    preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, null) == raw
            }.getOrDefault(false)
        }
    }

    /** Explicit profile history remains in its own preference namespace. */
    internal fun backfillFromHistory(
        ctx: Context,
        profileId: ProfileId,
        history: List<Song>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (history.isEmpty()) return true
        val preferences = profilePrefs(ctx, profileId)
        return synchronized(statsWriteLock) {
            runCatching {
                val current = preferences.getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}"
                persistDatedStats(preferences, ListenStatsDailyStore.applyHistoryBackfill(current, history, nowMs), nowMs)
            }.getOrDefault(false)
        }
    }

    /** Restore a validated local backup projection (365 days, unlike the 30-day cloud projection). */
    internal fun restoreLocalDailyListenStats(ctx: Context, localJson: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        init(ctx)
        return synchronized(statsWriteLock) {
            runCatching { persistDatedStats(p(), ListenStatsDailyStore.localProjection(localJson, nowMs), nowMs) }
                .getOrDefault(false)
        }
    }

    /** Export only personal play counts for an already authorized account snapshot. */
    internal fun accountPlayCountCounters(ctx: Context): MutableMap<String, MutableMap<String, Long>> {
        init(ctx)
        return synchronized(statsWriteLock) { exportPlayCountCounters(p()) }
    }

    /** Merge authenticated account counters locally; callers must enforce account consent and identity. */
    internal fun mergeAccountPlayCountCounters(
        ctx: Context,
        remote: Map<String, Map<String, Long>>
    ): Boolean {
        if (remote.isEmpty()) return true
        init(ctx)
        return synchronized(statsWriteLock) { mergeAccountPlayCountCounters(p(), remote) }
    }

    private fun exportPlayCountCounters(preferences: SharedPreferences): MutableMap<String, MutableMap<String, Long>> {
        return try {
            val songs = JSONObject(preferences.getString(K_SONG_MAP, "{}") ?: "{}")
            var deviceId = preferences.getString(K_PLAY_COUNT_DEVICE_ID, null)?.takeIf(::isValidDeviceId)
            var changed = false
            val exported = linkedMapOf<String, MutableMap<String, Long>>()
            val keys = songs.keys()
            while (keys.hasNext()) {
                val songKey = keys.next()
                if (songKey.isBlank() || songKey.length > 512) continue
                val item = songs.optJSONObject(songKey) ?: continue
                val devices = item.optJSONObject(K_PLAY_COUNT_BY_DEVICE) ?: JSONObject()
                if (devices.length() == 0) {
                    val legacyCount = item.optLong("cnt", 0L).coerceAtLeast(0L)
                    if (legacyCount > 0L) {
                        if (deviceId == null) {
                            deviceId = createAndPersistDeviceId(preferences)
                                ?: throw IllegalStateException("play-count installation identity was not persisted")
                        }
                        devices.put(deviceId, legacyCount)
                        item.put(K_PLAY_COUNT_BY_DEVICE, devices)
                        item.put("cnt", SongPlayCountCounterMerge.total(deviceCounts(devices)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        songs.put(songKey, item)
                        changed = true
                    }
                }
                deviceCounts(devices).takeIf { it.isNotEmpty() }?.let { exported[songKey] = it }
            }
            if (changed) {
                val raw = songs.toString()
                if (!preferences.edit().putString(K_SONG_MAP, raw).commit() || preferences.getString(K_SONG_MAP, null) != raw) {
                    throw IllegalStateException("legacy play-count migration was not persisted")
                }
            }
            SongPlayCountCounterMerge.trim(exported)
        } catch (failure: Exception) {
            throw IllegalStateException("歌曲播放次数读取或旧格式迁移未确认", failure)
        }
    }

    private fun mergeAccountPlayCountCounters(
        preferences: SharedPreferences,
        remote: Map<String, Map<String, Long>>
    ): Boolean = try {
        val local = exportPlayCountCounters(preferences)
        val merged = SongPlayCountCounterMerge.merge(local, remote)
        val songs = JSONObject(preferences.getString(K_SONG_MAP, "{}") ?: "{}")
        merged.forEach { (songKey, counts) ->
            val item = songs.optJSONObject(songKey) ?: JSONObject().apply {
                val separator = songKey.indexOf(':')
                put("name", if (separator >= 0) songKey.substring(separator + 1) else songKey)
                put("singer", "")
                put("cover", "")
                put("sec", 0L)
            }
            item.put(K_PLAY_COUNT_BY_DEVICE, JSONObject().apply {
                counts.forEach { (deviceId, count) -> if (count > 0L) put(deviceId, count) }
            })
            item.put("cnt", SongPlayCountCounterMerge.total(counts).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            songs.put(songKey, item)
        }
        trimSongStatsByCount(songs, SongPlayCountCounterMerge.MAX_TRACKS)
        val raw = songs.toString()
        preferences.edit().putString(K_SONG_MAP, raw).commit() && preferences.getString(K_SONG_MAP, null) == raw
    } catch (_: Exception) {
        false
    }

    private fun getOrCreatePlayCountDeviceId(preferences: SharedPreferences): String? {
        preferences.getString(K_PLAY_COUNT_DEVICE_ID, null)?.takeIf(::isValidDeviceId)?.let { return it }
        return createAndPersistDeviceId(preferences)
    }

    private fun createAndPersistDeviceId(preferences: SharedPreferences): String? {
        preferences.getString(K_PLAY_COUNT_DEVICE_ID, null)?.takeIf(::isValidDeviceId)?.let { return it }
        val generated = UUID.randomUUID().toString()
        val committed = preferences.edit().putString(K_PLAY_COUNT_DEVICE_ID, generated).commit()
        return generated.takeIf { committed && preferences.getString(K_PLAY_COUNT_DEVICE_ID, null) == it }
    }

    private fun isValidDeviceId(value: String): Boolean =
        value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun deviceCounts(devices: JSONObject): MutableMap<String, Long> {
        val result = linkedMapOf<String, Long>()
        val keys = devices.keys()
        while (keys.hasNext()) {
            val deviceId = keys.next()
            // r658c D#2：历史回补是本机估算值，不作为播放次数上云。
            if (!isValidDeviceId(deviceId) || deviceId == ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID) continue
            val count = devices.optLong(deviceId, 0L).coerceAtLeast(0L)
            if (count > 0L) result[deviceId] = count
        }
        return result
    }

    private fun trimSongStatsByCount(root: JSONObject, limit: Int) {
        val keep = root.keys().asSequence()
            .mapNotNull { key -> root.optJSONObject(key)?.let { key to it } }
            .sortedWith(compareByDescending<Pair<String, JSONObject>> { it.second.optLong("cnt", 0L) }
                .thenByDescending { it.second.optLong("sec", 0L) }.thenBy { it.first })
            .take(limit.coerceAtLeast(0))
            .map { it.first }
            .toSet()
        root.keys().asSequence().toList().filterNot { it in keep }.forEach(root::remove)
    }

    /** 切到新曲时记 1 次播放。 */
    fun markPlay(ctx: Context, song: Song): Boolean = markPlay(ctx, song, activeAccountUser(ctx))

    internal fun markPlay(ctx: Context, song: Song, accountUser: String?): Boolean {
        if (song.id.isEmpty()) return true
        init(ctx)
        return updateStats(p(), song, 0L, incrementCount = true, dropSensitiveCoverQuery = false, accountUser = accountUser)
    }

    /** 切到新曲时只为指定本机资料记 1 次播放。 */
    fun markPlay(ctx: Context, profileId: ProfileId, song: Song): Boolean {
        if (song.id.isEmpty()) return true
        return updateStats(
            profilePrefs(ctx, profileId),
            song,
            0L,
            incrementCount = true,
            dropSensitiveCoverQuery = true,
        )
    }

    /** 兼容旧调用。 */
    fun addPlay(ctx: Context, song: Song, sec: Long): Boolean {
        init(ctx)
        return updateStats(p(), song, sec.coerceAtLeast(0L), incrementCount = true, dropSensitiveCoverQuery = false, accountUser = activeAccountUser(ctx))
    }

    /** 兼容组合写入的 profile-scoped API。 */
    fun addPlay(ctx: Context, profileId: ProfileId, song: Song, sec: Long): Boolean =
        updateStats(profilePrefs(ctx, profileId), song, sec.coerceAtLeast(0L), incrementCount = true, dropSensitiveCoverQuery = true)

    private val sensitiveCoverQueryKeys = setOf(
        "accesskey", "accesskeyid", "accesstoken", "apikey", "auth", "authcode", "authkey",
        "authorization", "authorizationcode", "awsaccesskeyid", "clientsecret", "code",
        "credential", "credentials", "expiration", "expires", "expiry", "googleaccessid",
        "key", "keypairid", "ossaccesskeyid", "policy", "secret", "sessiontoken", "sig",
        "sign", "signature", "token", "xamzcredential", "xamzsecuritytoken", "xamzsignature",
        "xgoogsignature",
    )

    private fun hasSensitiveCoverQuery(cover: String): Boolean {
        val uri = runCatching { Uri.parse(cover) }.getOrNull() ?: return '?' in cover
        if (uri.encodedQuery.isNullOrBlank()) return false
        val parameterNames = runCatching { uri.queryParameterNames }.getOrElse { return true }
        return parameterNames.any { rawName ->
            val name = rawName.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            name in sensitiveCoverQueryKeys ||
                name.startsWith("auth") ||
                name.endsWith("token") ||
                name.contains("signature") ||
                name.contains("credential") ||
                name.endsWith("accesskeyid") ||
                name.contains("clientsecret")
        }
    }

    private fun trimMap(root: JSONObject, limit: Int) {
        val list = mutableListOf<Pair<String, Long>>()
        root.keys().forEach { k -> list.add(k to root.optJSONObject(k)?.optLong("sec", 0L).orZero()) }
        list.sortByDescending { it.second }
        val keep = list.take(limit).map { it.first }.toSet()
        val remove = list.map { it.first }.filterNot { it in keep }
        remove.forEach { root.remove(it) }
    }

    private fun trimMapSimple(root: JSONObject, limit: Int) {
        val list = mutableListOf<Pair<String, Long>>()
        root.keys().forEach { k -> list.add(k to root.optLong(k, 0L)) }
        list.sortByDescending { it.second }
        val keep = list.take(limit).map { it.first }.toSet()
        list.map { it.first }.filterNot { it in keep }.forEach { root.remove(it) }
    }

    private fun Long?.orZero() = this ?: 0L

    // ── read ──────────────────────────────────────────────────────────────────

    fun totalSec(ctx: Context): Long { init(ctx); return selectedSummaryPreferences(ctx).getLong(K_TOTAL_SEC, 0L) }

    fun totalSec(ctx: Context, profileId: ProfileId): Long =
        profilePrefs(ctx, profileId).getLong(K_TOTAL_SEC, 0L)

    fun todaySec(ctx: Context): Long {
        init(ctx)
        val today = todayKey()
        val preferences = selectedSummaryPreferences(ctx)
        return if (preferences.getString(K_TODAY_DATE, "") == today) preferences.getLong(K_TODAY_SEC, 0L) else 0L
    }

    fun todaySec(ctx: Context, profileId: ProfileId): Long {
        val preferences = profilePrefs(ctx, profileId)
        val today = todayKey()
        return if (preferences.getString(K_TODAY_DATE, "") == today) preferences.getLong(K_TODAY_SEC, 0L) else 0L
    }

    fun weekSec(ctx: Context): Long {
        init(ctx)
        val week = weekKey()
        val preferences = selectedSummaryPreferences(ctx)
        return if (preferences.getString(K_WEEK_LABEL, "") == week) preferences.getLong(K_WEEK_SEC, 0L) else 0L
    }

    fun weekSec(ctx: Context, profileId: ProfileId): Long {
        val preferences = profilePrefs(ctx, profileId)
        val week = weekKey()
        return if (preferences.getString(K_WEEK_LABEL, "") == week) preferences.getLong(K_WEEK_SEC, 0L) else 0L
    }

    data class SongStat(val key: String, val name: String, val singer: String, val cover: String, val sec: Long, val cnt: Int = 0)
    data class ArtistStat(val singer: String, val sec: Long, val cnt: Int = 0, val source: String = "")

    /** ACCEPT5-STATSMONTH：一个自然月的只读聚合（听歌统计页翻月用），全部来自日统计表。 */
    data class MonthStats(
        val month: java.time.YearMonth,
        val totalSec: Long,
        val totalPlays: Long,
        val activeDays: Int,
        val dailySeconds: List<Long>,
        val topSongs: List<SongStat>,
        val topArtists: List<ArtistStat>,
        val topPlatforms: List<Pair<String, Long>>
    )

    fun topSongs(ctx: Context, limit: Int = 10): List<SongStat> {
        init(ctx)
        return topSongsFrom(selectedSummaryPreferences(ctx), limit)
    }

    fun topSongs(ctx: Context, profileId: ProfileId, limit: Int = 10): List<SongStat> =
        topSongsFrom(profilePrefs(ctx, profileId), limit)

    private fun topSongsFrom(preferences: SharedPreferences, limit: Int): List<SongStat> {
        return try {
            val root = JSONObject(preferences.getString(K_SONG_MAP, "{}") ?: "{}")
            val list = mutableListOf<SongStat>()
            root.keys().forEach { k ->
                val o = root.optJSONObject(k) ?: return@forEach
                list.add(SongStat(k, o.optString("name"), o.optString("singer"), o.optString("cover"), o.optLong("sec", 0L), o.optInt("cnt", 0)))
            }
            list.sortedWith(compareByDescending<SongStat> { it.cnt }.thenByDescending { it.sec }).take(limit)
        } catch (_: Exception) { emptyList() }
    }

    fun topArtists(ctx: Context, limit: Int = 10): List<ArtistStat> {
        init(ctx)
        return topArtistsFrom(selectedSummaryPreferences(ctx), limit)
    }

    fun topArtists(ctx: Context, profileId: ProfileId, limit: Int = 10): List<ArtistStat> =
        topArtistsFrom(profilePrefs(ctx, profileId), limit)

    private fun topArtistsFrom(preferences: SharedPreferences, limit: Int): List<ArtistStat> {
        return try {
            val root = JSONObject(preferences.getString(K_SONG_MAP, "{}") ?: "{}")
            val totals = linkedMapOf<String, ArtistStat>()
            val sourceWeights = linkedMapOf<String, MutableMap<String, Long>>()
            root.keys().forEach { key ->
                val song = root.optJSONObject(key) ?: return@forEach
                val singer = song.optString("singer").trim()
                if (singer.isBlank()) return@forEach
                val identity = singer.lowercase(java.util.Locale.ROOT)
                val source = key.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
                if (source in xyz.cncnlp.soundwave.source.MusicSourceRegistry.platformIds) {
                    val weights = sourceWeights.getOrPut(identity) { linkedMapOf() }
                    weights[source] = (weights[source] ?: 0) + song.optInt("cnt", 0).coerceAtLeast(1)
                }
                val old = totals[identity] ?: ArtistStat(singer, 0L, 0)
                totals[identity] = old.copy(sec = old.sec + song.optLong("sec", 0L).coerceAtLeast(0L),
                    cnt = old.cnt + song.optInt("cnt", 0).coerceAtLeast(0))
            }
            totals.map { (identity, stat) -> stat.copy(source = sourceWeights[identity]?.maxByOrNull { it.value }?.key.orEmpty()) }
                .sortedWith(compareByDescending<ArtistStat> { it.cnt }.thenByDescending { it.sec }).take(limit)
        } catch (_: Exception) { emptyList() }
    }

    /** Daily seconds for every actual day in the requested China-time month; missing days stay empty. */
    fun monthDailySeconds(ctx: Context, nowMillis: Long = System.currentTimeMillis()): List<Long> {
        init(ctx)
        return monthDailySecondsFrom(selectedSummaryPreferences(ctx, nowMillis), nowMillis)
    }

    fun monthDailySeconds(
        ctx: Context,
        profileId: ProfileId,
        nowMillis: Long = System.currentTimeMillis()
    ): List<Long> = monthDailySecondsFrom(profilePrefs(ctx, profileId), nowMillis)

    private fun monthDailySecondsFrom(preferences: SharedPreferences, nowMillis: Long): List<Long> {
        val month = ListeningCalendar.monthAt(nowMillis)
        return (1..month.lengthOfMonth()).map { day ->
            preferences.getLong("day_sec_${ListeningCalendar.dayKey(month.atDay(day))}", 0L).coerceAtLeast(0L)
        }
    }

    /** ACCEPT5-STATSMONTH：所选自然月的整月聚合（只读，不改采集与存储）。 */
    fun monthStats(ctx: Context, month: java.time.YearMonth, nowMs: Long = System.currentTimeMillis()): MonthStats {
        init(ctx)
        return monthStatsFrom(selectedSummaryPreferences(ctx, nowMs), month, nowMs)
    }

    fun monthStats(ctx: Context, profileId: ProfileId, month: java.time.YearMonth, nowMs: Long = System.currentTimeMillis()): MonthStats =
        monthStatsFrom(profilePrefs(ctx, profileId), month, nowMs)

    private fun monthStatsFrom(preferences: SharedPreferences, month: java.time.YearMonth, nowMs: Long): MonthStats {
        val raw = preferences.getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}"
        val summary = ListenStatsDailyStore.summaryForMonth(raw, month, nowMs)
        val daily = (1..month.lengthOfMonth()).map { day ->
            summary.dailySeconds[ListeningCalendar.dayKey(month.atDay(day))] ?: 0L
        }
        val topSongs = summary.songs.entries.map { (key, stat) ->
            SongStat(key, stat.name, stat.singer, stat.cover, stat.seconds,
                stat.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }.sortedWith(compareByDescending<SongStat> { it.cnt }.thenByDescending { it.sec }).take(10)
        val topPlatforms = summary.platforms.entries.map { it.key to it.value }
            .sortedByDescending { it.second }.take(3)
        return MonthStats(
            month = month,
            totalSec = summary.totalSeconds,
            totalPlays = summary.songs.values.sumOf { it.count },
            activeDays = daily.count { it > 0L },
            dailySeconds = daily,
            topSongs = topSongs,
            topArtists = monthArtistsFrom(summary.songs, 10),
            topPlatforms = topPlatforms
        )
    }

    /** 与 [topArtistsFrom] 同口径，只是聚合对象换成当月日事实里的歌曲表。 */
    private fun monthArtistsFrom(
        songs: Map<String, ListenStatsDailyStore.SongSummary>,
        limit: Int
    ): List<ArtistStat> {
        val totals = linkedMapOf<String, ArtistStat>()
        val sourceWeights = linkedMapOf<String, MutableMap<String, Long>>()
        songs.forEach { (key, stat) ->
            val singer = stat.singer.trim()
            if (singer.isBlank()) return@forEach
            val identity = singer.lowercase(java.util.Locale.ROOT)
            val source = key.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
            if (source in xyz.cncnlp.soundwave.source.MusicSourceRegistry.platformIds) {
                val weights = sourceWeights.getOrPut(identity) { linkedMapOf() }
                weights[source] = (weights[source] ?: 0L) + stat.count.coerceAtLeast(1L)
            }
            val old = totals[identity] ?: ArtistStat(singer, 0L, 0)
            totals[identity] = old.copy(
                sec = old.sec + stat.seconds.coerceAtLeast(0L),
                cnt = old.cnt + stat.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }
        return totals.map { (identity, stat) ->
            stat.copy(source = sourceWeights[identity]?.maxByOrNull { it.value }?.key.orEmpty())
        }.sortedWith(compareByDescending<ArtistStat> { it.cnt }.thenByDescending { it.sec }).take(limit)
    }

    fun fmtDuration(sec: Long): String {
        if (sec <= 0L) return "0 分钟"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    // ── 增强：播放天数（最近7天活跃天数）与最常听平台 ──────────────────────────

    /** 最近 7 天有播放的天数；跨天自然失效，不依赖精确清理。 */
    fun activeDaysLast7(ctx: Context): Int {
        init(ctx)
        return activeDaysLast7From(selectedSummaryPreferences(ctx))
    }

    /** Daily listening seconds for the trailing seven calendar days, oldest first. */
    /** r654：上一自然周（周一至周日）累计聆听秒数，用于设置页「比上周多/少」胶囊。 */
    fun previousWeekSec(ctx: Context): Long {
        init(ctx)
        val preferences = selectedSummaryPreferences(ctx)
        val today = ListeningCalendar.localDate(System.currentTimeMillis())
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        return (1..7).sumOf { back ->
            preferences.getLong("day_sec_${ListeningCalendar.dayKey(monday.minusDays(back.toLong()))}", 0L).coerceAtLeast(0L)
        }
    }

    fun last7DaySeconds(ctx: Context): List<Long> {
        init(ctx)
        val preferences = selectedSummaryPreferences(ctx)
        val today = ListeningCalendar.localDate(System.currentTimeMillis())
        return (6 downTo 0).map { offset ->
            val key = ListeningCalendar.dayKey(today.minusDays(offset.toLong()))
            preferences.getLong("day_sec_$key", 0L).coerceAtLeast(0L)
        }
    }

    fun activeDaysLast7(ctx: Context, profileId: ProfileId): Int =
        activeDaysLast7From(profilePrefs(ctx, profileId))

    private fun activeDaysLast7From(preferences: SharedPreferences): Int {
        return try {
            val today = ListeningCalendar.localDate(System.currentTimeMillis())
            val days = mutableSetOf<String>()
            for (i in 0 until 7) {
                val key = ListeningCalendar.dayKey(today.minusDays(i.toLong()))
                if (preferences.getLong("day_sec_$key", 0L) > 0L) days.add(key)
            }
            days.size
        } catch (_: Exception) { 0 }
    }

    /** 最常听的平台（按累计秒数），返回 平台代码->秒 前3。 */
    fun topPlatforms(ctx: Context, limit: Int = 3): List<Pair<String, Long>> {
        init(ctx)
        return topPlatformsFrom(selectedSummaryPreferences(ctx), limit)
    }

    fun topPlatforms(ctx: Context, profileId: ProfileId, limit: Int = 3): List<Pair<String, Long>> =
        topPlatformsFrom(profilePrefs(ctx, profileId), limit)

    private fun topPlatformsFrom(preferences: SharedPreferences, limit: Int): List<Pair<String, Long>> {
        return try {
            val root = JSONObject(preferences.getString("platform_map_v1", "{}") ?: "{}")
            val list = mutableListOf<Pair<String, Long>>()
            root.keys().forEach { k -> list.add(k to root.optLong(k, 0L)) }
            list.sortedByDescending { it.second }.take(limit)
        } catch (_: Exception) { emptyList() }
    }

    fun recordPlatform(ctx: Context, source: String, sec: Long): Boolean {
        if (sec <= 0L || source.isBlank()) return true
        init(ctx)
        return recordPlatformIn(p(), source, sec, activeAccountUser(ctx))
    }

    fun recordPlatform(ctx: Context, profileId: ProfileId, source: String, sec: Long): Boolean {
        if (sec <= 0L || source.isBlank()) return true
        return recordPlatformIn(profilePrefs(ctx, profileId), source, sec)
    }

    private fun recordPlatformIn(preferences: SharedPreferences, source: String, sec: Long, accountUser: String? = null): Boolean {
        return try {
            val nowMs = System.currentTimeMillis()
            if (!ensureDatedStats(preferences, nowMs)) return false
            val deviceId = getOrCreatePlayCountDeviceId(preferences) ?: return false
            val currentDaily = preferences.getString(K_DAILY_LISTEN_STATS, "{}") ?: "{}"
            val dated = if (accountUser.isNullOrEmpty())
                ListenStatsDailyStore.recordPlatform(currentDaily, source, deviceId, sec, nowMs)
            else currentDaily
            val accountStats = accountUser?.takeIf { it.isNotEmpty() }?.let { account ->
                val allAccounts = JSONObject(preferences.getString(K_ACCOUNT_DAILY_LISTEN_STATS_V2, "{}") ?: "{}")
                val key = accountStatsKey(account)
                val current = allAccounts.optString(key, "{}")
                allAccounts.put(key, ListenStatsDailyStore.recordPlatform(current, source, deviceId, sec, nowMs))
                allAccounts.toString()
            }
            persistDatedStats(preferences, dated, nowMs, accountStats)
        } catch (_: Exception) { false }
    }
}
