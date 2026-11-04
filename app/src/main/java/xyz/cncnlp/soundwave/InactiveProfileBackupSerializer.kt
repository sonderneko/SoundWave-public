package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure allowlist serializer for one validated, still-inactive profile. */
internal object InactiveProfileBackupSerializer {
    const val SCHEMA = "soundwave-local-profile-backup"
    const val VERSION = 1

    private const val DELETED_FAVORITES = "_swDeletedFavorites"
    private const val TODAY_STYLE_PREFERENCES = "_swTodayStylePreferences"
    private const val DELETED_PLAYLISTS = "_swDeletedPlaylists"
    private const val DELETED_PLAYLIST_SONGS = "_swDeletedPlaylistSongs"
    private const val MAX_FAVORITES = 5_000
    private const val MAX_PLAYLISTS = 500
    private const val MAX_HISTORY = 500
    private const val MAX_TOTAL_PLAYLIST_SONGS = 100_000
    private val safeProviderId = Regex("[A-Za-z0-9_-]{1,32}")
    private val domainLikeIdentifier = Regex("(?i)(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::[0-9]{1,5})?")
    private val privateHostSuffixes = setOf(
        "localhost", "local", "internal", "lan", "home", "test", "example", "invalid", "onion"
    )

    /**
     * Projects only the documented user-data fields. Unknown keys are intentionally ignored,
     * even when their values contain account credentials, local paths, scripts, or server URLs.
     */
    fun serialize(bundle: JSONObject, independentHistory: JSONArray): ByteArray {
        val favorites = bundle.optJSONArray("favs") ?: error("本机收藏数据无效")
        val playlists = bundle.optJSONArray("pls") ?: error("本机歌单数据无效")
        val enabledScripts = bundle.optJSONArray("enabledScripts") ?: error("本机脚本字段无效")
        val scriptOrder = bundle.optJSONArray("scriptOrder") ?: error("本机脚本顺序字段无效")
        require(enabledScripts.length() == 0 && scriptOrder.length() == 0) { "本机资料包含非预期脚本配置" }
        require(favorites.length() <= MAX_FAVORITES + 2) { "收藏数量超过导出上限" }
        require(playlists.length() <= MAX_PLAYLISTS + 1) { "歌单数量超过导出上限" }
        require(independentHistory.length() <= MAX_HISTORY) { "播放历史超过导出上限" }

        val safeFavorites = JSONArray()
        for (index in 0 until favorites.length()) {
            val item = favorites.optJSONObject(index) ?: error("本机收藏条目无效")
            if (item.has(DELETED_FAVORITES)) {
                safeFavorites.put(JSONObject().put(DELETED_FAVORITES, projectLongMap(item.optJSONObject(DELETED_FAVORITES))))
            } else if (item.has(TODAY_STYLE_PREFERENCES)) {
                require(item.keys().asSequence().toSet() == setOf(TODAY_STYLE_PREFERENCES)) { "每日风格偏好标记含有非支持字段" }
                val preferences = item.optJSONObject(TODAY_STYLE_PREFERENCES) ?: error("每日风格偏好标记无效")
                val canonical = xyz.cncnlp.soundwave.recommend.StylePreferenceSync.canonical(preferences.toString())
                require(JSONObject(canonical).length() > 0) { "每日风格偏好标记无效" }
                safeFavorites.put(JSONObject().put(TODAY_STYLE_PREFERENCES, JSONObject(canonical)))
            } else {
                safeFavorites.put(projectSong(item, includeSyncAddedAt = true))
            }
        }

        val safePlaylists = JSONArray()
        var totalPlaylistSongs = 0
        for (index in 0 until playlists.length()) {
            val item = playlists.optJSONObject(index) ?: error("本机歌单条目无效")
            val hasDeletedPlaylists = item.has(DELETED_PLAYLISTS)
            val hasDeletedSongs = item.has(DELETED_PLAYLIST_SONGS)
            if (hasDeletedPlaylists || hasDeletedSongs) {
                val tombstones = JSONObject()
                if (hasDeletedPlaylists) {
                    tombstones.put(DELETED_PLAYLISTS, projectLongMap(item.optJSONObject(DELETED_PLAYLISTS)))
                }
                if (hasDeletedSongs) {
                    tombstones.put(DELETED_PLAYLIST_SONGS, projectNestedLongMap(item.optJSONObject(DELETED_PLAYLIST_SONGS)))
                }
                safePlaylists.put(tombstones)
                continue
            }

            val songs = item.optJSONArray("songs") ?: error("本机歌单歌曲数据无效")
            totalPlaylistSongs += songs.length()
            require(totalPlaylistSongs <= MAX_TOTAL_PLAYLIST_SONGS) { "歌单歌曲总数超过导出上限" }
            val safeSongs = JSONArray()
            for (songIndex in 0 until songs.length()) {
                val song = songs.optJSONObject(songIndex) ?: error("本机歌单歌曲无效")
                safeSongs.put(projectSong(song, includeSyncAddedAt = true))
            }

            val playlistId = requiredSafeIdentifier(item, "id", "本机歌单身份无效")
            val safePlaylist = JSONObject()
                .put("id", playlistId)
                .put("songs", safeSongs)
            aliasString(item, "nm", "name")?.takeIf { it.isNotBlank() }?.let { safePlaylist.put("nm", it) }
            safeCover(aliasString(item, "cv", "cover"))?.let { safePlaylist.put("cv", it) }
            optionalBoolean(item, "local")?.let { safePlaylist.put("local", it) }
            safeProvider(aliasString(item, "src", "source"))?.let { safePlaylist.put("src", it) }
            aliasBoolean(item, "hid", "hidden")?.let { safePlaylist.put("hid", it) }
            optionalSafeIdentifier(item, "remoteId")?.let { safePlaylist.put("remoteId", it) }
            optionalBoolean(item, "favorited")?.let { safePlaylist.put("favorited", it) }

            nonNegativeLong(item, "_swCreatedAt")?.takeIf { it > 0L }?.let { safePlaylist.put("_swCreatedAt", it) }
            val order = nonNegativeInt(item, "_swLibOrder")
            val orderAt = nonNegativeLong(item, "_swLibOrderAt")
            if (order != null && orderAt != null && orderAt > 0L) {
                safePlaylist.put("_swLibOrder", order)
                safePlaylist.put("_swLibOrderAt", orderAt)
            }
            safePlaylists.put(safePlaylist)
        }

        val projectedHistory = (0 until independentHistory.length()).map { index ->
            val song = independentHistory.optJSONObject(index) ?: error("本机播放历史条目无效")
            val projected = projectSong(song, includeSyncAddedAt = false)
            projected to Song.fromJson(projected)
        }
        val retainedHistory = PlaybackHistoryPolicy.retainRecent(
            projectedHistory.map { it.second },
            nowMs = System.currentTimeMillis(),
            limit = MAX_HISTORY
        )
        val safeHistory = JSONArray().apply {
            retainedHistory.forEach { retained ->
                projectedHistory.firstOrNull { (json, song) ->
                    song.key() == retained.key() && song.playedAt == retained.playedAt &&
                        json.optString("name") == retained.name
                }?.first?.let(::put)
            }
        }

        val data = JSONObject()
            .put("favs", safeFavorites)
            .put("pls", safePlaylists)
            .put("history", safeHistory)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("data", data)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun projectSong(input: JSONObject, includeSyncAddedAt: Boolean): JSONObject {
        val id = requiredSafeIdentifier(input, "id", "本机歌曲身份无效")
        val output = JSONObject().put("id", id)
        optionalString(input, "name")?.let { output.put("name", it) }
        optionalString(input, "singer")?.let { output.put("singer", it) }
        safeProvider(aliasString(input, "src", "source"))?.let { output.put("src", it) }
        safeCover(optionalString(input, "cover"))?.let { output.put("cover", it) }
        optionalString(input, "album")?.let { output.put("album", it) }
        optionalSafeIdentifier(input, "albumId")?.let { output.put("albumId", it) }
        nonNegativeLong(input, "duration")?.let { output.put("duration", it) }
        nonNegativeLong(input, "playedAt")?.takeIf { it > 0L }?.let { output.put("playedAt", it) }
        if (includeSyncAddedAt) {
            nonNegativeLong(input, "_swAddedAt")?.takeIf { it > 0L }?.let { output.put("_swAddedAt", it) }
        }
        return output
    }

    private fun projectLongMap(input: JSONObject?): JSONObject {
        require(input != null) { "本机删除标记无效" }
        val output = JSONObject()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key.isNotBlank() && key.length <= 4_096) { "本机删除标记无效" }
            val value = input.opt(key) as? Number ?: error("本机删除时间无效")
            val timestamp = value.toLong()
            require(timestamp >= 0L && value.toDouble().isFinite() && value.toDouble() == timestamp.toDouble()) {
                "本机删除时间无效"
            }
            output.put(key, timestamp)
        }
        return output
    }

    private fun projectNestedLongMap(input: JSONObject?): JSONObject {
        require(input != null) { "本机歌单删除标记无效" }
        val output = JSONObject()
        val playlistIds = input.keys()
        while (playlistIds.hasNext()) {
            val playlistId = playlistIds.next()
            require(playlistId.isNotBlank() && playlistId.length <= 512 && !looksLikePathOrUrl(playlistId)) {
                "本机歌单删除标记无效"
            }
            val songs = input.optJSONObject(playlistId) ?: error("本机歌曲删除标记无效")
            output.put(playlistId, projectLongMap(songs))
        }
        return output
    }

    private fun requiredSafeIdentifier(input: JSONObject, key: String, errorMessage: String): String {
        val value = optionalString(input, key)?.trim().orEmpty()
        require(value.isNotBlank() && value.length <= 512 && !looksLikePathOrUrl(value)) { errorMessage }
        return value
    }

    private fun optionalSafeIdentifier(input: JSONObject, key: String): String? {
        val value = optionalString(input, key)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value.takeIf { it.length <= 512 && !looksLikePathOrUrl(it) }
    }

    private fun looksLikePathOrUrl(value: String): Boolean =
        value.contains("://") || value.startsWith('/') || value.contains('\\') || value.contains('?') ||
            value.contains('#') || domainLikeIdentifier.matches(value)

    private fun optionalString(input: JSONObject, key: String): String? {
        if (!input.has(key) || input.isNull(key)) return null
        return input.opt(key) as? String ?: error("本机资料字段无效")
    }

    private fun aliasString(input: JSONObject, vararg keys: String): String? {
        val values = keys.mapNotNull { key -> optionalString(input, key)?.takeIf { it.isNotBlank() } }.distinct()
        require(values.size <= 1) { "本机资料字段不一致" }
        return values.firstOrNull()
    }

    private fun optionalBoolean(input: JSONObject, key: String): Boolean? {
        if (!input.has(key) || input.isNull(key)) return null
        return input.opt(key) as? Boolean ?: error("本机资料字段无效")
    }

    private fun aliasBoolean(input: JSONObject, vararg keys: String): Boolean? {
        val values = keys.mapNotNull { key -> optionalBoolean(input, key) }.distinct()
        require(values.size <= 1) { "本机资料字段不一致" }
        return values.firstOrNull()
    }

    private fun nonNegativeLong(input: JSONObject, key: String): Long? {
        if (!input.has(key) || input.isNull(key)) return null
        val value = input.opt(key) as? Number ?: error("本机资料字段无效")
        val double = value.toDouble()
        require(double.isFinite() && double >= 0.0 && double <= Long.MAX_VALUE.toDouble()) { "本机资料字段无效" }
        val longValue = value.toLong()
        require(double == longValue.toDouble()) { "本机资料字段无效" }
        return longValue
    }

    private fun nonNegativeInt(input: JSONObject, key: String): Int? {
        val value = nonNegativeLong(input, key) ?: return null
        require(value <= Int.MAX_VALUE) { "本机排序字段无效" }
        return value.toInt()
    }

    private fun safeProvider(value: String?): String? = value?.takeIf { safeProviderId.matches(it) }

    /** Covers are only retained when they are unsigned, non-local, public HTTP(S) URI references. */
    private fun safeCover(value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() && it == it.trim() } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || uri.isOpaque || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null
        ) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
        if (!isPublicHost(host)) return null
        return runCatching { uri.normalize().toASCIIString() }.getOrNull()
    }

    private fun isPublicHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host.contains(':')) return false // IPv6 is conservatively omitted rather than misclassified.
        if (host.all { it.isDigit() || it == '.' }) {
            val octets = host.split('.')
            if (octets.size != 4) return false
            if (octets.any { it.length > 1 && it.startsWith('0') }) return false
            val address = octets.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return false }
            val (a, b, c, d) = address
            if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) || (a == 198 && b in 18..19) ||
                (a == 192 && b == 0 && c == 0) || (a == 192 && b == 0 && c == 2) ||
                (a == 192 && b == 88 && c == 99) || (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113) || (a == 255 && b == 255 && c == 255 && d == 255)
            ) return false
            return true
        }
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isBlank() || it.startsWith('-') || it.endsWith('-') || !it.matches(Regex("[a-z0-9-]+")) }) {
            return false
        }
        val suffix = labels.last()
        if (suffix.length < 2 || suffix in privateHostSuffixes) return false
        return true
    }
}
