package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Parsed, bounded in-memory preview. Constructing this value never touches persistent storage. */
internal data class PreparedLocalProfileBackup(
    val bundle: LocalStore.UserBundle,
    val preview: LocalProfileBackupPreview
)

internal data class LocalProfileBackupPreview(
    val favoriteCount: Int,
    val playlistCount: Int,
    val playlistSongCount: Int,
    val historyCount: Int,
    val playlistNames: List<String>,
    val songNames: List<String>
)

/** Strict reader for exactly the JSON emitted by InactiveProfileBackupSerializer at version 1. */
internal object LocalProfileBackupImporter {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024

    private const val MAX_FAVORITES = 5_000
    private const val MAX_PLAYLISTS = 500
    private const val MAX_HISTORY = 500
    private const val MAX_TOTAL_PLAYLIST_SONGS = 100_000
    private const val MAX_TOMBSTONE_ENTRIES = 50_000
    private const val MAX_STRING_CHARS = 4_096
    private const val MAX_ID_CHARS = 512
    private val safeProvider = Regex("[A-Za-z0-9_-]{1,32}")
    private val domainLike = Regex("(?i)(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::[0-9]{1,5})?")
    private val privateHostSuffixes = setOf("localhost", "local", "internal", "lan", "home", "test", "example", "invalid", "onion")
    private val songKeys = setOf("id", "name", "singer", "src", "cover", "album", "albumId", "duration", "playedAt", "_swAddedAt")
    private val historySongKeys = songKeys - "_swAddedAt"
    private val playlistKeys = setOf(
        "id", "songs", "nm", "cv", "local", "src", "hid", "remoteId", "favorited",
        "_swCreatedAt", "_swLibOrder", "_swLibOrderAt", "_swFavOrder", "_swFavOrderAt"
    )
    private val deletedFavoriteKey = "_swDeletedFavorites"
    private val todayStylePreferencesKey = "_swTodayStylePreferences"
    private val deletedPlaylistKey = "_swDeletedPlaylists"
    private val deletedPlaylistSongsKey = "_swDeletedPlaylistSongs"

    fun read(input: InputStream): PreparedLocalProfileBackup = parse(readBounded(input))

    fun parse(bytes: ByteArray): PreparedLocalProfileBackup {
        require(bytes.isNotEmpty()) { "所选文件为空" }
        require(bytes.size <= MAX_INPUT_BYTES) { "文件超过 32 MiB 导入上限" }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: Exception) {
            throw IllegalArgumentException("文件不是有效 UTF-8 JSON", failure)
        }
        StrictJsonDuplicateKeyScanner.validate(text, MAX_STRING_CHARS)
        val root = try {
            JSONObject(text)
        } catch (failure: Exception) {
            throw IllegalArgumentException("文件 JSON 结构无效", failure)
        }
        require(keys(root) == setOf("schema", "version", "data")) { "文件含有非支持字段" }
        require(requiredString(root, "schema", 64) == InactiveProfileBackupSerializer.SCHEMA) {
            "这不是 SoundWave 本机资料备份文件"
        }
        val version = root.opt("version")
        require((version is Int || version is Long) && version.toLong() == InactiveProfileBackupSerializer.VERSION.toLong()) {
            "本机资料备份版本不受支持"
        }
        val data = root.optJSONObject("data") ?: throw IllegalArgumentException("本机资料数据缺失或无效")
        require(keys(data) == setOf("favs", "pls", "history")) { "本机资料数据含有非支持字段" }
        val favorites = requiredArray(data, "favs")
        val playlists = requiredArray(data, "pls")
        val history = requiredArray(data, "history")
        require(favorites.length() <= MAX_FAVORITES + 2) { "收藏数量超过导入上限" }
        require(playlists.length() <= MAX_PLAYLISTS + 1) { "歌单数量超过导入上限" }
        require(history.length() <= MAX_HISTORY) { "播放历史数量超过导入上限" }

        var tombstoneEntries = 0
        for (index in 0 until favorites.length()) {
            val item = requiredObject(favorites, index, "收藏条目无效")
            if (item.has(deletedFavoriteKey)) {
                require(keys(item) == setOf(deletedFavoriteKey)) { "收藏删除标记含有非支持字段" }
                tombstoneEntries += validateLongMap(item.optJSONObject(deletedFavoriteKey), "收藏删除标记无效")
                require(tombstoneEntries <= MAX_TOMBSTONE_ENTRIES) { "删除标记数量超过导入上限" }
            } else if (item.has(todayStylePreferencesKey)) {
                require(keys(item) == setOf(todayStylePreferencesKey)) { "每日风格偏好标记含有非支持字段" }
                val preferences = item.optJSONObject(todayStylePreferencesKey)
                    ?: throw IllegalArgumentException("每日风格偏好标记无效")
                require(preferences.length() in 1..64) { "每日风格偏好数量无效" }
                val platforms = preferences.keys()
                while (platforms.hasNext()) {
                    val platform = platforms.next()
                    require(platform == xyz.cncnlp.soundwave.recommend.StylePreferenceSync.platformKey(platform) &&
                        platform.matches(Regex("[a-z0-9_-]{1,32}"))) { "音乐平台偏好身份无效" }
                    val selection = preferences.optJSONObject(platform)
                        ?: throw IllegalArgumentException("每日风格偏好条目无效")
                    require(keys(selection) == setOf("tags", "enabled", "modifiedAt", "version")) {
                        "每日风格偏好字段含有非支持内容"
                    }
                    require(selection.opt("enabled") is Boolean) { "每日风格偏好启用状态无效" }
                    val tags = requiredArray(selection, "tags")
                    require(tags.length() <= 64) { "每日风格标签数量超过上限" }
                    for (tagIndex in 0 until tags.length()) {
                        val tag = tags.opt(tagIndex) as? String ?: throw IllegalArgumentException("每日风格标签无效")
                        require(tag.length <= 128) { "每日风格标签长度超过上限" }
                    }
                    requiredNonNegativeLong(selection, "modifiedAt")
                    requiredNonNegativeLong(selection, "version")
                }
            } else {
                validateSong(item, songKeys, allowSyncAddedAt = true)
            }
        }

        var totalPlaylistSongs = 0
        for (index in 0 until playlists.length()) {
            val item = requiredObject(playlists, index, "歌单条目无效")
            val hasDeletedPlaylists = item.has(deletedPlaylistKey)
            val hasDeletedSongs = item.has(deletedPlaylistSongsKey)
            if (hasDeletedPlaylists || hasDeletedSongs) {
                require(keys(item).all { it == deletedPlaylistKey || it == deletedPlaylistSongsKey }) {
                    "歌单删除标记含有非支持字段"
                }
                require(hasDeletedPlaylists || hasDeletedSongs) { "歌单删除标记无效" }
                if (hasDeletedPlaylists) {
                    tombstoneEntries += validateLongMap(item.optJSONObject(deletedPlaylistKey), "歌单删除标记无效")
                }
                if (hasDeletedSongs) {
                    val nested = item.optJSONObject(deletedPlaylistSongsKey)
                        ?: throw IllegalArgumentException("歌单歌曲删除标记无效")
                    require(nested.length() <= MAX_TOMBSTONE_ENTRIES) { "删除标记数量超过导入上限" }
                    val playlistIds = nested.keys()
                    while (playlistIds.hasNext()) {
                        val playlistId = playlistIds.next()
                        validateIdentifier(playlistId, 512, "歌单删除标记身份无效")
                        val songMap = nested.optJSONObject(playlistId)
                            ?: throw IllegalArgumentException("歌曲删除标记无效")
                        tombstoneEntries += validateLongMap(songMap, "歌曲删除标记无效")
                        require(tombstoneEntries <= MAX_TOMBSTONE_ENTRIES) { "删除标记数量超过导入上限" }
                    }
                }
                require(tombstoneEntries <= MAX_TOMBSTONE_ENTRIES) { "删除标记数量超过导入上限" }
                continue
            }

            require(keys(item).all { it in playlistKeys } && item.has("id") && item.has("songs")) {
                "歌单字段无效"
            }
            validateIdentifier(requiredString(item, "id", MAX_ID_CHARS), MAX_ID_CHARS, "歌单身份无效")
            optionalString(item, "nm")
            if (item.has("cv")) item.put("cv", cleanPublicCover(requiredString(item, "cv", MAX_STRING_CHARS)))
            optionalBoolean(item, "local")
            optionalProvider(item, "src")
            optionalBoolean(item, "hid")
            if (item.has("remoteId")) validateIdentifier(requiredString(item, "remoteId", MAX_ID_CHARS), MAX_ID_CHARS, "远程歌单身份无效")
            optionalBoolean(item, "favorited")
            optionalNonNegativeLong(item, "_swCreatedAt")
            val hasOrder = item.has("_swLibOrder")
            val hasOrderAt = item.has("_swLibOrderAt")
            require(hasOrder == hasOrderAt) { "歌单排序标记不完整" }
            if (hasOrder) {
                val order = requiredNonNegativeLong(item, "_swLibOrder")
                require(order <= Int.MAX_VALUE) { "歌单排序标记无效" }
                require(requiredNonNegativeLong(item, "_swLibOrderAt") > 0L) { "歌单排序时间无效" }
            }
            val hasFavOrder = item.has("_swFavOrder")
            val hasFavOrderAt = item.has("_swFavOrderAt")
            require(hasFavOrder == hasFavOrderAt) { "收藏排序标记不完整" }
            if (hasFavOrder) {
                val favOrder = requiredNonNegativeLong(item, "_swFavOrder")
                require(favOrder <= Int.MAX_VALUE) { "收藏排序标记无效" }
                require(requiredNonNegativeLong(item, "_swFavOrderAt") > 0L) { "收藏排序时间无效" }
            }
            val songs = requiredArray(item, "songs")
            totalPlaylistSongs += songs.length()
            require(totalPlaylistSongs <= MAX_TOTAL_PLAYLIST_SONGS) { "歌单歌曲总数超过导入上限" }
            for (songIndex in 0 until songs.length()) {
                validateSong(requiredObject(songs, songIndex, "歌单歌曲无效"), songKeys, allowSyncAddedAt = true)
            }
        }
        require(tombstoneEntries <= MAX_TOMBSTONE_ENTRIES) { "删除标记数量超过导入上限" }
        for (index in 0 until history.length()) {
            validateSong(requiredObject(history, index, "播放历史条目无效"), historySongKeys, allowSyncAddedAt = false)
        }

        val bundle = try {
            LocalStore.UserBundle.fromCloud(data).also {
                it.reconcileSyncTombstones()
                it.ensureDefaults()
            }
        } catch (failure: Exception) {
            throw IllegalArgumentException("本机资料无法读取", failure)
        }
        require(bundle.enabledScripts.isEmpty() && bundle.scriptOrder.isEmpty() && bundle.avatarB64.isBlank()) {
            "本机资料含有非支持内容"
        }
        val allSongs = bundle.favs + bundle.playlists.flatMap { it.songs } + bundle.history
        require(allSongs.all { it.resolvedUrl.isBlank() }) { "本机资料含有本机播放路径" }

        val visiblePlaylists = bundle.playlists.filterNot { it.id in setOf("liked", "local") }
        val sampledSongs = bundle.favs.take(5)
            .ifEmpty { visiblePlaylists.flatMap { it.songs }.take(5) }
            .ifEmpty { bundle.history.take(5) }
        val preview = LocalProfileBackupPreview(
            favoriteCount = bundle.favs.size,
            playlistCount = visiblePlaylists.size,
            playlistSongCount = visiblePlaylists.sumOf { it.songs.size },
            historyCount = bundle.history.size,
            playlistNames = visiblePlaylists.take(4).map { safeDisplayText(it.name) },
            songNames = sampledSongs.map { song ->
                val name = safeDisplayText(song.name)
                val artist = safeDisplayText(song.singer)
                if (artist.isBlank()) name else "$name — $artist"
            }
        )
        return PreparedLocalProfileBackup(bundle = bundle, preview = preview)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                require(total < MAX_INPUT_BYTES) { "文件超过 32 MiB 导入上限" }
                output.write(one)
                total++
                continue
            }
            require(count <= MAX_INPUT_BYTES - total) { "文件超过 32 MiB 导入上限" }
            output.write(buffer, 0, count)
            total += count
        }
        require(total > 0) { "所选文件为空" }
        return output.toByteArray()
    }

    private fun validateSong(item: JSONObject, allowedKeys: Set<String>, allowSyncAddedAt: Boolean) {
        require(keys(item).all { it in allowedKeys } && item.has("id")) { "歌曲字段含有非支持内容" }
        validateIdentifier(requiredString(item, "id", MAX_ID_CHARS), MAX_ID_CHARS, "歌曲身份无效")
        optionalString(item, "name")
        optionalString(item, "singer")
        optionalProvider(item, "src")
        if (item.has("cover")) item.put("cover", cleanPublicCover(requiredString(item, "cover", MAX_STRING_CHARS)))
        optionalString(item, "album")
        if (item.has("albumId")) validateIdentifier(requiredString(item, "albumId", MAX_ID_CHARS), MAX_ID_CHARS, "专辑身份无效")
        optionalNonNegativeLong(item, "duration")
        optionalNonNegativeLong(item, "playedAt")
        if (allowSyncAddedAt) optionalNonNegativeLong(item, "_swAddedAt")
        else require(!item.has("_swAddedAt")) { "播放历史含有非支持字段" }
    }

    private fun validateLongMap(input: JSONObject?, label: String): Int {
        val map = input ?: throw IllegalArgumentException(label)
        require(map.length() <= MAX_TOMBSTONE_ENTRIES) { "删除标记数量超过导入上限" }
        val keys = map.keys()
        var count = 0
        while (keys.hasNext()) {
            val key = keys.next()
            validateIdentifier(key, MAX_STRING_CHARS, label)
            requiredNonNegativeLong(map, key)
            count++
        }
        return count
    }

    private fun validateIdentifier(value: String, maxChars: Int, message: String) {
        require(value.isNotBlank() && value == value.trim() && value.length <= maxChars) { message }
        require(value.none { it.isISOControl() } && '@' !in value && !looksLikePathOrUrl(value) && !domainLike.matches(value)) { message }
    }

    private fun looksLikePathOrUrl(value: String): Boolean =
        value.contains("://") || value.startsWith('/') || value.contains('\\') || value.contains('?') ||
            value.contains('#') || Regex("(?i)^(https?|file|content|ftp|saf):").containsMatchIn(value)

    private fun optionalProvider(obj: JSONObject, key: String) {
        if (!obj.has(key)) return
        val value = requiredString(obj, key, 32)
        require(safeProvider.matches(value)) { "音乐来源字段无效" }
    }

    private fun optionalString(obj: JSONObject, key: String): String? =
        if (obj.has(key)) requiredString(obj, key, MAX_STRING_CHARS) else null

    private fun optionalBoolean(obj: JSONObject, key: String) {
        if (obj.has(key)) require(obj.opt(key) is Boolean) { "本机资料布尔字段无效" }
    }

    private fun optionalNonNegativeLong(obj: JSONObject, key: String): Long? =
        if (obj.has(key)) requiredNonNegativeLong(obj, key) else null

    private fun requiredNonNegativeLong(obj: JSONObject, key: String): Long {
        val value = obj.opt(key)
        require((value is Int || value is Long) && value.toLong() >= 0L) { "本机资料数字字段无效" }
        return (value as Number).toLong()
    }

    private fun requiredString(obj: JSONObject, key: String, maxChars: Int): String {
        val value = obj.opt(key) as? String ?: throw IllegalArgumentException("本机资料文本字段无效")
        require(value.length <= maxChars) { "本机资料文本长度超过上限" }
        return value
    }

    private fun requiredArray(obj: JSONObject, key: String): JSONArray =
        obj.optJSONArray(key) ?: throw IllegalArgumentException("本机资料列表字段无效")

    private fun requiredObject(array: JSONArray, index: Int, label: String): JSONObject =
        array.optJSONObject(index) ?: throw IllegalArgumentException(label)

    private fun keys(obj: JSONObject): Set<String> = obj.keys().asSequence().toSet()

    /** Return a canonical URL only when it is a public, unsigned HTTP(S) cover URL. */
    private fun cleanPublicCover(raw: String): String {
        require(raw.isNotBlank() && raw == raw.trim()) { "封面地址无效" }
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw IllegalArgumentException("封面地址无效")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in setOf("http", "https") && !uri.isOpaque && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "封面地址必须是不含凭据、查询参数或片段的公开 HTTP(S) 地址" }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("封面地址主机无效")
        require(isPublicHost(host)) { "封面地址主机不是公开主机" }
        return runCatching { uri.normalize().toASCIIString() }.getOrElse { throw IllegalArgumentException("封面地址无效") }
    }

    private fun isPublicHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.contains(':')) return false
        if (host.all { it.isDigit() || it == '.' }) {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { it.length > 1 && it.startsWith('0') }) return false
            val address = octets.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return false }
            val (a, b, c, d) = address
            if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) ||
                (a == 198 && b in 18..19) || (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) || (a == 192 && b == 88 && c == 99) ||
                (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113) ||
                (a == 255 && b == 255 && c == 255 && d == 255)
            ) return false
            return true
        }
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isBlank() || it.startsWith('-') || it.endsWith('-') || !it.matches(Regex("[a-z0-9-]+")) }) return false
        val suffix = labels.last()
        return suffix.length >= 2 && suffix !in privateHostSuffixes
    }

    private fun safeDisplayText(value: String): String =
        value.filterNot { it.isISOControl() }.trim().take(100).ifBlank { "未命名" }

}
