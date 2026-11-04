package xyz.cncnlp.soundwave

import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Dated, per-song listening facts. Each device component is monotonic and merges by max. */
internal object ListenStatsDailyStore {
    const val LOCAL_RETENTION_DAYS = 365
    const val CLOUD_RETENTION_DAYS = 30
    const val LEGACY_DEVICE_ID = "__legacy_undated__"
    /** r658c D#2：从播放历史回补的估算值，只留本机（与 legacy 一样不上云），可随时整体重算。 */
    const val HISTORY_BACKFILL_DEVICE_ID = "__history_backfill__"
    /** 时长未知的历史条目按 3 分钟估算；单曲估算上限 10 分钟。 */
    const val BACKFILL_UNKNOWN_SECONDS = 180L
    const val BACKFILL_MAX_SECONDS = 600L

    private fun isLocalOnlyDevice(device: String): Boolean =
        device == LEGACY_DEVICE_ID || device == HISTORY_BACKFILL_DEVICE_ID

    fun backfillSecondsOf(song: Song): Long {
        val sec = song.duration.coerceAtLeast(0L) / 1000L
        return if (sec <= 0L) BACKFILL_UNKNOWN_SECONDS else sec.coerceAtMost(BACKFILL_MAX_SECONDS)
    }

    /**
     * 用播放历史回补「有播放历史、但当天没有任何真实计时」的日期。幂等：每次先清掉旧回补分量再按历史重算；
     * 当天一旦有真实计时（本机或其他设备）就不再回补，真实数据永远优先。
     */
    fun applyHistoryBackfill(rawJson: String, history: List<Song>, nowMs: Long): String {
        val root = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        root.keys().asSequence().toList().forEach { dayKey ->
            val day = root.optJSONObject(dayKey) ?: return@forEach
            day.optJSONObject("totalByDevice")?.remove(HISTORY_BACKFILL_DEVICE_ID)
            listOf("artists", "platforms").forEach { field ->
                day.optJSONObject(field)?.let { names -> names.keys().asSequence().toList().forEach { names.optJSONObject(it)?.remove(HISTORY_BACKFILL_DEVICE_ID) } }
            }
            day.optJSONObject("songs")?.let { songs -> songs.keys().asSequence().toList().forEach { songs.optJSONObject(it)?.optJSONObject("devices")?.remove(HISTORY_BACKFILL_DEVICE_ID) } }
        }
        val todayKey = ListeningCalendar.dayKey(nowMs)
        val byDay = history.filter { it.playedAt > 0L && it.id.isNotBlank() }
            .groupBy { ListeningCalendar.dayKey(it.playedAt) }
        byDay.forEach { (dayKey, plays) ->
            // 今天仍在实时计时，只回补已过去的日期，避免估算与真实计时叠加。
            if (dayKey == todayKey || !isRetainedDay(dayKey, nowMs, LOCAL_RETENTION_DAYS)) return@forEach
            val day = root.optJSONObject(dayKey) ?: JSONObject()
            if (sumDeviceMap(day.optJSONObject("totalByDevice")) > 0L) return@forEach
            plays.forEach { song ->
                val seconds = backfillSecondsOf(song)
                putDeviceTotal(day, HISTORY_BACKFILL_DEVICE_ID, seconds)
                if (song.singer.isNotBlank()) addNamedDeviceSeconds(day, "artists", song.singer, HISTORY_BACKFILL_DEVICE_ID, seconds)
                if (song.source.isNotBlank()) addNamedDeviceSeconds(day, "platforms", song.source, HISTORY_BACKFILL_DEVICE_ID, seconds)
                val songs = day.optJSONObject("songs") ?: JSONObject()
                val songKey = "${song.source}:${song.id}"
                val item = songs.optJSONObject(songKey) ?: JSONObject()
                item.put("name", item.optString("name").ifBlank { song.name })
                item.put("singer", item.optString("singer").ifBlank { song.singer })
                if (item.optString("cover").isBlank() && song.cover.isNotBlank() && !isDeviceLocalReference(song.cover)) item.put("cover", song.cover)
                val devices = item.optJSONObject("devices") ?: JSONObject()
                val c = devices.optJSONObject(HISTORY_BACKFILL_DEVICE_ID) ?: JSONObject()
                c.put("sec", safeAdd(c.optLong("sec", 0L), seconds))
                c.put("cnt", safeAdd(c.optLong("cnt", 0L), 1L))
                devices.put(HISTORY_BACKFILL_DEVICE_ID, c)
                item.put("devices", devices)
                songs.put(songKey, item)
                day.put("songs", songs)
            }
            root.put(dayKey, day)
        }
        return normalize(root, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true).toString()
    }

    data class SongSummary(
        val name: String,
        val singer: String,
        val cover: String,
        val seconds: Long,
        val count: Long,
        val deviceCounts: Map<String, Long>
    )

    data class Summary(
        val totalSeconds: Long,
        val songs: Map<String, SongSummary>,
        val artists: Map<String, Long>,
        val platforms: Map<String, Long>,
        val dailySeconds: Map<String, Long>
    )

    fun emptyJson(): String = JSONObject().toString()

    /** Migrate date-free legacy song/artist/platform totals to a local-only bucket at migration time. */
    fun migrateLegacy(
        songMapJson: String,
        artistMapJson: String,
        platformMapJson: String,
        datedDailySeconds: Map<String, Long>,
        todayDate: String,
        todaySeconds: Long,
        nowMs: Long
    ): String {
        val root = JSONObject()
        datedDailySeconds.forEach { (dayKey, seconds) ->
            if (seconds <= 0L || !isRetainedDay(dayKey, nowMs, LOCAL_RETENTION_DAYS)) return@forEach
            val day = root.optJSONObject(dayKey) ?: JSONObject()
            putDeviceTotal(day, LEGACY_DEVICE_ID, seconds)
            root.put(dayKey, day)
        }
        if (todaySeconds > 0L && isRetainedDay(todayDate, nowMs, LOCAL_RETENTION_DAYS)) {
            val day = root.optJSONObject(todayDate) ?: JSONObject()
            val totals = day.optJSONObject("totalByDevice") ?: JSONObject()
            if (!totals.has(LEGACY_DEVICE_ID)) totals.put(LEGACY_DEVICE_ID, todaySeconds)
            day.put("totalByDevice", totals)
            root.put(todayDate, day)
        }
        val today = ListeningCalendar.dayKey(nowMs)
        if (isRetainedDay(today, nowMs, LOCAL_RETENTION_DAYS)) {
            val day = root.optJSONObject(today) ?: JSONObject()
            val songs = day.optJSONObject("songs") ?: JSONObject()
            JSONObject(songMapJson).keys().forEach { songKey ->
                val old = JSONObject(songMapJson).optJSONObject(songKey) ?: return@forEach
                val sec = old.optLong("sec", 0L).coerceAtLeast(0L)
                val count = old.optLong("cnt", 0L).coerceAtLeast(0L)
                if (sec == 0L && count == 0L) return@forEach
                val item = songs.optJSONObject(songKey) ?: JSONObject()
                item.put("name", old.optString("name"))
                item.put("singer", old.optString("singer"))
                item.put("cover", old.optString("cover"))
                val devices = item.optJSONObject("devices") ?: JSONObject()
                devices.put(LEGACY_DEVICE_ID, JSONObject().put("sec", sec).put("cnt", count))
                item.put("devices", devices)
                songs.put(songKey, item)
            }
            day.put("songs", songs)
            putLegacyNamedTotals(day, "artists", JSONObject(artistMapJson))
            putLegacyNamedTotals(day, "platforms", JSONObject(platformMapJson))
            root.put(today, day)
        }
        return normalize(root, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true).toString()
    }

    fun record(
        rawJson: String,
        song: Song,
        deviceId: String,
        seconds: Long,
        incrementCount: Boolean,
        nowMs: Long
    ): String {
        require(isValidDeviceId(deviceId))
        val root = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        val dayKey = ListeningCalendar.dayKey(nowMs)
        val day = root.optJSONObject(dayKey) ?: JSONObject()
        if (seconds > 0L) {
            putDeviceTotal(day, deviceId, seconds)
            if (song.singer.isNotBlank()) addNamedDeviceSeconds(day, "artists", song.singer, deviceId, seconds)
            if (song.source.isNotBlank()) addNamedDeviceSeconds(day, "platforms", song.source, deviceId, seconds)
        }
        if (song.id.isNotBlank() && (seconds > 0L || incrementCount)) {
            val songKey = "${song.source}:${song.id}"
            val songs = day.optJSONObject("songs") ?: JSONObject()
            val item = songs.optJSONObject(songKey) ?: JSONObject()
            item.put("name", song.name)
            item.put("singer", song.singer)
            if (song.cover.isNotBlank()) item.put("cover", song.cover)
            val devices = item.optJSONObject("devices") ?: JSONObject()
            val contribution = devices.optJSONObject(deviceId) ?: JSONObject()
            if (seconds > 0L) contribution.put("sec", safeAdd(contribution.optLong("sec", 0L), seconds))
            if (incrementCount) contribution.put("cnt", safeAdd(contribution.optLong("cnt", 0L), 1L))
            devices.put(deviceId, contribution)
            item.put("devices", devices)
            songs.put(songKey, item)
            day.put("songs", songs)
        }
        root.put(dayKey, day)
        return normalize(root, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true).toString()
    }

    fun recordPlatform(rawJson: String, source: String, deviceId: String, seconds: Long, nowMs: Long): String {
        if (source.isBlank() || seconds <= 0L) return normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, true).toString()
        require(isValidDeviceId(deviceId))
        val root = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        val dayKey = ListeningCalendar.dayKey(nowMs)
        val day = root.optJSONObject(dayKey) ?: JSONObject()
        addNamedDeviceSeconds(day, "platforms", source, deviceId, seconds)
        root.put(dayKey, day)
        return normalize(root, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true).toString()
    }

    /** Keep local 365-day data and cloud recent-30-day data, merging shared device components by max. */
    fun mergeLocalAndCloud(localJson: String, cloudJson: String, nowMs: Long): String {
        val local = normalizedRoot(localJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        val cloud = normalizedRoot(cloudJson, nowMs, CLOUD_RETENTION_DAYS, includeLegacy = false)
        val merged = JSONObject()
        val days = (local.keys().asSequence().toList() + cloud.keys().asSequence().toList()).distinct().sorted()
        days.forEach { dayKey ->
            val a = local.optJSONObject(dayKey) ?: JSONObject()
            val b = cloud.optJSONObject(dayKey) ?: JSONObject()
            val out = JSONObject()
            // Totals are device -> seconds, unlike artists/platforms (name -> device -> seconds).
            mergeDeviceMaps(out, "totalByDevice", a.optJSONObject("totalByDevice"), b.optJSONObject("totalByDevice"))
            mergeNamedDeviceMaps(out, "artists", a.optJSONObject("artists"), b.optJSONObject("artists"))
            mergeNamedDeviceMaps(out, "platforms", a.optJSONObject("platforms"), b.optJSONObject("platforms"))
            val songs = JSONObject()
            val songKeys = (a.optJSONObject("songs")?.keys()?.asSequence()?.toList().orEmpty() +
                b.optJSONObject("songs")?.keys()?.asSequence()?.toList().orEmpty()).distinct().sorted()
            songKeys.forEach { songKey ->
                val left = a.optJSONObject("songs")?.optJSONObject(songKey) ?: JSONObject()
                val right = b.optJSONObject("songs")?.optJSONObject(songKey) ?: JSONObject()
                val item = JSONObject()
                item.put("name", left.optString("name").ifBlank { right.optString("name") })
                item.put("singer", left.optString("singer").ifBlank { right.optString("singer") })
                item.put("cover", left.optString("cover").ifBlank { right.optString("cover") })
                val devices = JSONObject()
                val deviceIds = (left.optJSONObject("devices")?.keys()?.asSequence()?.toList().orEmpty() +
                    right.optJSONObject("devices")?.keys()?.asSequence()?.toList().orEmpty()).distinct().sorted()
                deviceIds.forEach { deviceId ->
                    if (!isValidDeviceId(deviceId)) return@forEach
                    if (isLocalOnlyDevice(deviceId)) {
                        val legacy = left.optJSONObject("devices")?.optJSONObject(deviceId)
                        if (legacy != null) devices.put(deviceId, JSONObject(legacy.toString()))
                    } else {
                        val l = left.optJSONObject("devices")?.optJSONObject(deviceId) ?: JSONObject()
                        val r = right.optJSONObject("devices")?.optJSONObject(deviceId) ?: JSONObject()
                        val contribution = JSONObject()
                        val sec = maxOf(l.optLong("sec", 0L), r.optLong("sec", 0L)).coerceAtLeast(0L)
                        val count = maxOf(l.optLong("cnt", 0L), r.optLong("cnt", 0L)).coerceAtLeast(0L)
                        if (sec > 0L || count > 0L) contribution.put("sec", sec).put("cnt", count)
                        if (contribution.length() > 0) devices.put(deviceId, contribution)
                    }
                }
                if (devices.length() > 0) {
                    item.put("devices", devices)
                    songs.put(songKey, item)
                }
            }
            if (songs.length() > 0) out.put("songs", songs)
            if (out.length() > 0) merged.put(dayKey, out)
        }
        return normalize(merged, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true).toString()
    }

    fun localProjection(rawJson: String, nowMs: Long): String =
        normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true).toString()

    /** Keep only this installation's local facts before combining them with one account's cloud baseline. */
    fun localDeviceProjection(rawJson: String, deviceId: String, nowMs: Long): String {
        require(isValidDeviceId(deviceId) && !isLocalOnlyDevice(deviceId))
        val source = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        val output = JSONObject()
        source.keys().asSequence().toList().sorted().forEach { dayKey ->
            if (!isRetainedDay(dayKey, nowMs, CLOUD_RETENTION_DAYS)) return@forEach
            val day = source.optJSONObject(dayKey) ?: return@forEach
            val projected = JSONObject()
            day.optJSONObject("totalByDevice")?.optLong(deviceId, 0L)?.takeIf { it > 0L }?.let {
                projected.put("totalByDevice", JSONObject().put(deviceId, it))
            }
            listOf("artists", "platforms").forEach { field ->
                val names = day.optJSONObject(field) ?: return@forEach
                val projectedNames = JSONObject()
                names.keys().asSequence().toList().sorted().forEach { name ->
                    val seconds = names.optJSONObject(name)?.optLong(deviceId, 0L) ?: 0L
                    if (name.isNotBlank() && seconds > 0L) projectedNames.put(name, JSONObject().put(deviceId, seconds))
                }
                if (projectedNames.length() > 0) projected.put(field, projectedNames)
            }
            val sourceSongs = day.optJSONObject("songs") ?: JSONObject()
            val projectedSongs = JSONObject()
            sourceSongs.keys().asSequence().toList().sorted().forEach { songKey ->
                val item = sourceSongs.optJSONObject(songKey) ?: return@forEach
                val contribution = item.optJSONObject("devices")?.optJSONObject(deviceId) ?: return@forEach
                val sec = contribution.optLong("sec", 0L).coerceAtLeast(0L)
                val count = contribution.optLong("cnt", 0L).coerceAtLeast(0L)
                if (sec <= 0L && count <= 0L) return@forEach
                projectedSongs.put(songKey, JSONObject()
                    .put("name", item.optString("name"))
                    .put("singer", item.optString("singer"))
                    .put("cover", item.optString("cover"))
                    .put("devices", JSONObject().put(deviceId, JSONObject().put("sec", sec).put("cnt", count))))
            }
            if (projectedSongs.length() > 0) projected.put("songs", projectedSongs)
            if (projected.length() > 0) output.put(dayKey, projected)
        }
        return normalize(output, nowMs, CLOUD_RETENTION_DAYS, includeLegacy = false).toString()
    }

    fun cloudProjection(rawJson: String, nowMs: Long): String =
        normalizedRoot(rawJson, nowMs, CLOUD_RETENTION_DAYS, includeLegacy = false).toString()

    fun summary(rawJson: String, nowMs: Long): Summary {
        val root = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        var total = 0L
        val dailyTotals = linkedMapOf<String, Long>()
        val songs = linkedMapOf<String, SongSummary>()
        val artistTotals = linkedMapOf<String, Long>()
        val platformTotals = linkedMapOf<String, Long>()
        root.keys().asSequence().toList().sorted().forEach { dayKey ->
            val day = root.optJSONObject(dayKey) ?: return@forEach
            val dayTotal = sumDeviceMap(day.optJSONObject("totalByDevice"))
            dailyTotals[dayKey] = dayTotal
            total = safeAdd(total, dayTotal)
            val songObj = day.optJSONObject("songs") ?: JSONObject()
            songObj.keys().asSequence().toList().forEach { key ->
                val item = songObj.optJSONObject(key) ?: return@forEach
                val devices = item.optJSONObject("devices") ?: JSONObject()
                val deviceCounts = linkedMapOf<String, Long>()
                var seconds = 0L
                var count = 0L
                devices.keys().asSequence().toList().forEach { device ->
                    val record = devices.optJSONObject(device) ?: return@forEach
                    val sec = record.optLong("sec", 0L).coerceAtLeast(0L)
                    val cnt = record.optLong("cnt", 0L).coerceAtLeast(0L)
                    seconds = safeAdd(seconds, sec)
                    count = safeAdd(count, cnt)
                    if (cnt > 0L) deviceCounts[device] = safeAdd(deviceCounts[device] ?: 0L, cnt)
                }
                val previous = songs[key]
                songs[key] = SongSummary(
                    name = item.optString("name").ifBlank { previous?.name.orEmpty() },
                    singer = item.optString("singer").ifBlank { previous?.singer.orEmpty() },
                    cover = item.optString("cover").ifBlank { previous?.cover.orEmpty() },
                    seconds = safeAdd(previous?.seconds ?: 0L, seconds),
                    count = safeAdd(previous?.count ?: 0L, count),
                    deviceCounts = mergeSums(previous?.deviceCounts.orEmpty(), deviceCounts)
                )
            }
            aggregateNamedDeviceMap(artistTotals, day.optJSONObject("artists"))
            aggregateNamedDeviceMap(platformTotals, day.optJSONObject("platforms"))
        }
        return Summary(total, songs, artistTotals, platformTotals, dailyTotals)
    }

    /**
     * ACCEPT5-STATSMONTH：只读按月汇总——把日事实先过滤到目标自然月，再走与 [summary]
     * 完全相同的聚合（归一化幂等，总量/歌曲/歌手/平台/逐日秒数同口径）。不写任何数据。
     */
    fun summaryForMonth(rawJson: String, month: YearMonth, nowMs: Long): Summary {
        val root = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        val filtered = JSONObject()
        root.keys().asSequence().toList().forEach { dayKey ->
            val day = runCatching { LocalDate.parse(dayKey, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                ?: return@forEach
            if (YearMonth.from(day) == month) root.optJSONObject(dayKey)?.let { filtered.put(dayKey, it) }
        }
        return summary(filtered.toString(), nowMs)
    }

    data class DecayedSongFact(
        val key: String,
        val name: String,
        val singer: String,
        /** Half-life-decayed listening seconds/count for this song (sums across devices and days). */
        val seconds: Double,
        val count: Double
    )

    data class DecayedFacts(
        val songs: List<DecayedSongFact>,
        /** Undecayed total play count across all retained days; drives cold-start tiering. */
        val totalPlays: Long,
        /** Decayed total listening seconds across all retained days. */
        val totalSeconds: Double
    )

    /**
     * R-wave read-side aggregation: the same daily facts as [summary], but each day's
     * contribution is weighted by `0.5^(ageDays / halfLifeDays)` so recent taste outweighs
     * lifetime accumulation. Pure read; the recording surface is untouched. Device scope
     * matches [summary] (every device component, including local-only legacy/backfill).
     */
    fun decayedFacts(rawJson: String, nowMs: Long, halfLifeDays: Double = 45.0): DecayedFacts {
        require(halfLifeDays > 0.0)
        val root = normalizedRoot(rawJson, nowMs, LOCAL_RETENTION_DAYS, includeLegacy = true)
        val today = ListeningCalendar.localDate(nowMs)
        val byKey = linkedMapOf<String, DecayedSongFact>()
        var totalPlays = 0L
        var totalSeconds = 0.0
        root.keys().asSequence().toList().sorted().forEach { dayKey ->
            val day = runCatching { LocalDate.parse(dayKey, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return@forEach
            val ageDays = java.time.temporal.ChronoUnit.DAYS.between(day, today)
            if (ageDays < 0L) return@forEach
            val factor = Math.pow(0.5, ageDays.toDouble() / halfLifeDays)
            val dayObj = root.optJSONObject(dayKey) ?: return@forEach
            val songObj = dayObj.optJSONObject("songs") ?: JSONObject()
            songObj.keys().asSequence().toList().forEach { key ->
                val item = songObj.optJSONObject(key) ?: return@forEach
                val devices = item.optJSONObject("devices") ?: JSONObject()
                var sec = 0L
                var cnt = 0L
                devices.keys().asSequence().toList().forEach { device ->
                    val record = devices.optJSONObject(device) ?: return@forEach
                    sec += record.optLong("sec", 0L).coerceAtLeast(0L)
                    cnt += record.optLong("cnt", 0L).coerceAtLeast(0L)
                }
                if (sec <= 0L && cnt <= 0L) return@forEach
                totalPlays += cnt
                totalSeconds += sec * factor
                val previous = byKey[key]
                byKey[key] = DecayedSongFact(
                    key = key,
                    name = item.optString("name").ifBlank { previous?.name.orEmpty() },
                    singer = item.optString("singer").ifBlank { previous?.singer.orEmpty() },
                    seconds = (previous?.seconds ?: 0.0) + sec * factor,
                    count = (previous?.count ?: 0.0) + cnt * factor
                )
            }
        }
        return DecayedFacts(byKey.values.toList(), totalPlays, totalSeconds)
    }

    fun isRetainedDay(dayKey: String, nowMs: Long, days: Int): Boolean {
        if (days <= 0 || !dayKey.matches(Regex("\\d{8}"))) return false
        val day = runCatching { LocalDate.parse(dayKey, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return false
        val today = ListeningCalendar.localDate(nowMs)
        return !day.isAfter(today) && !day.isBefore(today.minusDays((days - 1).toLong()))
    }

    private fun normalize(input: JSONObject, nowMs: Long, days: Int, includeLegacy: Boolean): JSONObject {
        val output = JSONObject()
        input.keys().asSequence().toList().sorted().forEach { dayKey ->
            if (!isRetainedDay(dayKey, nowMs, days)) return@forEach
            val day = input.optJSONObject(dayKey) ?: return@forEach
            val sanitized = JSONObject()
            sanitizeDeviceMap(sanitized, "totalByDevice", day.optJSONObject("totalByDevice"), includeLegacy)
            sanitizeNamedDeviceMap(sanitized, "artists", day.optJSONObject("artists"), includeLegacy)
            sanitizeNamedDeviceMap(sanitized, "platforms", day.optJSONObject("platforms"), includeLegacy)
            val songsIn = day.optJSONObject("songs") ?: JSONObject()
            val songsOut = JSONObject()
            songsIn.keys().asSequence().toList().sorted().forEach { songKey ->
                if (songKey.isBlank() || songKey.length > 512) return@forEach
                val itemIn = songsIn.optJSONObject(songKey) ?: return@forEach
                val itemOut = JSONObject()
                    .put("name", itemIn.optString("name").take(512))
                    .put("singer", itemIn.optString("singer").take(512))
                    // Only local normalization includes legacy facts; cloud projections must not expose device paths.
                    .put("cover", filterDeviceLocalReference(itemIn.optString("cover"), includeLocalUris = includeLegacy).take(4096))
                val devicesIn = itemIn.optJSONObject("devices") ?: JSONObject()
                val devicesOut = JSONObject()
                devicesIn.keys().asSequence().toList().sorted().forEach { device ->
                    if (!isValidDeviceId(device) || (!includeLegacy && isLocalOnlyDevice(device))) return@forEach
                    val record = devicesIn.optJSONObject(device) ?: return@forEach
                    val sec = record.optLong("sec", 0L).coerceAtLeast(0L)
                    val cnt = record.optLong("cnt", 0L).coerceAtLeast(0L)
                    if (sec > 0L || cnt > 0L) devicesOut.put(device, JSONObject().put("sec", sec).put("cnt", cnt))
                }
                if (devicesOut.length() > 0) {
                    itemOut.put("devices", devicesOut)
                    songsOut.put(songKey, itemOut)
                }
            }
            if (songsOut.length() > 0) sanitized.put("songs", songsOut)
            if (sanitized.length() > 0) output.put(dayKey, sanitized)
        }
        return output
    }

    private fun normalizedRoot(raw: String, nowMs: Long, days: Int, includeLegacy: Boolean): JSONObject =
        normalize(JSONObject(raw.ifBlank { "{}" }), nowMs, days, includeLegacy)

    private fun putDeviceTotal(day: JSONObject, device: String, seconds: Long) {
        val total = day.optJSONObject("totalByDevice") ?: JSONObject()
        total.put(device, safeAdd(total.optLong(device, 0L), seconds))
        day.put("totalByDevice", total)
    }

    private fun addNamedDeviceSeconds(day: JSONObject, field: String, name: String, device: String, seconds: Long) {
        val names = day.optJSONObject(field) ?: JSONObject()
        val devices = names.optJSONObject(name) ?: JSONObject()
        devices.put(device, safeAdd(devices.optLong(device, 0L), seconds))
        names.put(name, devices)
        day.put(field, names)
    }

    private fun putLegacyNamedTotals(day: JSONObject, field: String, old: JSONObject) {
        val names = day.optJSONObject(field) ?: JSONObject()
        old.keys().asSequence().toList().forEach { name ->
            val seconds = old.optLong(name, 0L).coerceAtLeast(0L)
            if (name.isNotBlank() && seconds > 0L) names.put(name, JSONObject().put(LEGACY_DEVICE_ID, seconds))
        }
        if (names.length() > 0) day.put(field, names)
    }

    private fun sanitizeDeviceMap(target: JSONObject, field: String, source: JSONObject?, includeLegacy: Boolean) {
        if (source == null) return
        val result = JSONObject()
        source.keys().asSequence().toList().sorted().forEach { device ->
            if (!isValidDeviceId(device) || (!includeLegacy && isLocalOnlyDevice(device))) return@forEach
            val value = source.optLong(device, 0L).coerceAtLeast(0L)
            if (value > 0L) result.put(device, value)
        }
        if (result.length() > 0) target.put(field, result)
    }

    private fun sanitizeNamedDeviceMap(target: JSONObject, field: String, source: JSONObject?, includeLegacy: Boolean) {
        if (source == null) return
        val result = JSONObject()
        source.keys().asSequence().toList().sorted().forEach { name ->
            if (name.isBlank() || name.length > 512) return@forEach
            val devicesIn = source.optJSONObject(name) ?: return@forEach
            val devices = JSONObject()
            devicesIn.keys().asSequence().toList().sorted().forEach { device ->
                if (!isValidDeviceId(device) || (!includeLegacy && isLocalOnlyDevice(device))) return@forEach
                val seconds = devicesIn.optLong(device, 0L).coerceAtLeast(0L)
                if (seconds > 0L) devices.put(device, seconds)
            }
            if (devices.length() > 0) result.put(name, devices)
        }
        if (result.length() > 0) target.put(field, result)
    }

    /** Merge flat per-device counters; both inputs have already passed retention/privacy projection. */
    private fun mergeDeviceMaps(target: JSONObject, field: String, first: JSONObject?, second: JSONObject?) {
        val devices = JSONObject()
        val keys = (first?.keys()?.asSequence()?.toList().orEmpty() + second?.keys()?.asSequence()?.toList().orEmpty())
            .distinct().sorted()
        keys.forEach { device ->
            if (!isValidDeviceId(device)) return@forEach
            val value = maxOf(first?.optLong(device, 0L) ?: 0L, second?.optLong(device, 0L) ?: 0L).coerceAtLeast(0L)
            if (value > 0L) devices.put(device, value)
        }
        if (devices.length() > 0) target.put(field, devices)
    }

    private fun mergeNamedDeviceMaps(target: JSONObject, field: String, first: JSONObject?, second: JSONObject?) {
        val names = JSONObject()
        val keys = (first?.keys()?.asSequence()?.toList().orEmpty() + second?.keys()?.asSequence()?.toList().orEmpty())
            .distinct().sorted()
        keys.forEach { name ->
            val a = first?.optJSONObject(name) ?: JSONObject()
            val b = second?.optJSONObject(name) ?: JSONObject()
            val devices = JSONObject()
            (a.keys().asSequence().toList() + b.keys().asSequence().toList()).distinct().sorted().forEach { device ->
                if (!isValidDeviceId(device)) return@forEach
                val value = maxOf(a.optLong(device, 0L), b.optLong(device, 0L)).coerceAtLeast(0L)
                if (value > 0L) devices.put(device, value)
            }
            if (devices.length() > 0) names.put(name, devices)
        }
        if (names.length() > 0) target.put(field, names)
    }

    private fun sumDeviceMap(devices: JSONObject?): Long = devices?.let { obj ->
        obj.keys().asSequence().fold(0L) { total, device -> safeAdd(total, obj.optLong(device, 0L).coerceAtLeast(0L)) }
    } ?: 0L

    private fun aggregateNamedDeviceMap(target: MutableMap<String, Long>, names: JSONObject?) {
        if (names == null) return
        names.keys().asSequence().toList().forEach { name ->
            val seconds = sumDeviceMap(names.optJSONObject(name))
            if (seconds > 0L) target[name] = safeAdd(target[name] ?: 0L, seconds)
        }
    }

    private fun mergeSums(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
        linkedMapOf<String, Long>().apply {
            (first.keys + second.keys).forEach { key -> put(key, safeAdd(first[key] ?: 0L, second[key] ?: 0L)) }
        }

    private fun isValidDeviceId(deviceId: String): Boolean =
        isLocalOnlyDevice(deviceId) || (deviceId.length in 1..128 && deviceId.all { it.isLetterOrDigit() || it == '-' || it == '_' })

    private fun safeAdd(first: Long, second: Long): Long {
        val a = first.coerceAtLeast(0L)
        val b = second.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
