package xyz.cncnlp.soundwave.source.direct

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.decodeProviderText
import xyz.cncnlp.soundwave.normalizeCoverUrl

/**
 * QQ anonymous search gateway. The SearchCgiService module now requires a zzc-signed
 * request posted to musics.fcg: unsigned posts to the old musicu.fcg are silently
 * emptied upstream (module code 2001, or code 0 with meta.sum 0) — live-probed
 * 2026-11-04, see WAVE-ACCEPT6-TXSEARCH. The old client_search_cp endpoint likewise
 * returns HTTP 200 with totalnum 0. Other musicu modules (singer list etc.) are not
 * behind the sign gate; only this search module is.
 */
private const val TX_MUSICU_SEARCH_URL = "https://u.y.qq.com/cgi-bin/musics.fcg"
private const val TX_COVER_PREFIX = "https://y.qq.com/music/photo_new/T002R800x800M000"
private const val TX_REFERER = "https://y.qq.com/"
private const val TX_SEARCH_TIMEOUT_MS = 8000L

internal data class TxTypedSearchResult(val songs: List<Song>, val available: Boolean)

internal suspend fun searchTx(keyword: String, page: Int, limit: Int): List<Song> {
    val songs = searchTxTyped(keyword, "song", page, limit)
    return songs.sortedByDescending { scoreSong(keyword, it.name, it.singer) }.take(limit.coerceAtLeast(0))
}

/**
 * QQ search tabs use the same real upstream module with search_type 0/1/2 for songs/artists/albums.
 * The request block key must match the module name and must not include a top-level `comm`; live
 * probes found the latter shape returns empty/error responses. Playlist mode uses its separately
 * verified client_music_search_songlist endpoint in PlaylistDirectSearch.
 */
internal suspend fun searchTxTyped(keyword: String, type: String, page: Int = 1, limit: Int = 30): List<Song> =
    searchTxTypedResult(keyword, type, page, limit).songs

internal suspend fun searchTxTypedResult(keyword: String, type: String, page: Int = 1, limit: Int = 30): TxTypedSearchResult {
    val query = keyword.trim()
    if (query.isEmpty()) return TxTypedSearchResult(emptyList(), available = true)
    val bodyKey = "music.search.SearchCgiService"
    val searchType = when (type.lowercase()) {
        "artist", "singer" -> 1
        "album" -> 2
        else -> 0
    }
    return try {
        val param = JSONObject()
            .put("search_type", searchType)
            .put("query", query)
            .put("page_num", page.coerceAtLeast(1))
            .put("num_per_page", limit.coerceIn(1, 50))
            .put("grp", 1)
        val request = JSONObject().put(
            bodyKey,
            JSONObject()
                .put("module", bodyKey)
                .put("method", "DoSearchForQQMusicDesktop")
                .put("param", param),
        )
        val body = request.toString()
        val raw = platformHttpPostJson("$TX_MUSICU_SEARCH_URL?sign=${txZzcSign(body)}", body, TX_REFERER, TX_SEARCH_TIMEOUT_MS)
        parseTxMusicuSearchStatus(raw, query, type, limit)
    } catch (_: Exception) {
        TxTypedSearchResult(emptyList(), available = false)
    }
}

private val TX_ZZC_PART1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
private val TX_ZZC_PART2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
private val TX_ZZC_SCRAMBLE = intArrayOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179)

/**
 * zzc request signature for the QQ search gateway, ported from the public LX Music
 * implementation (tx/utils/crypto.js): SHA-1 of the exact request body string, hex
 * chars picked by fixed indexes (an out-of-range index contributes nothing, matching
 * the JS original where index 40 reads past the 40-char hex), the 20 digest bytes
 * XORed with a fixed scramble table and base64-encoded with `/+=` stripped.
 * Pure and deterministic so unit tests can pin vectors.
 */
internal fun txZzcSign(body: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(body.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    val part1 = buildString { for (i in TX_ZZC_PART1_INDEXES) hex.getOrNull(i)?.let { append(it) } }
    val part2 = buildString { for (i in TX_ZZC_PART2_INDEXES) hex.getOrNull(i)?.let { append(it) } }
    val scrambled = ByteArray(20) { i -> (TX_ZZC_SCRAMBLE[i] xor (digest[i].toInt() and 0xFF)).toByte() }
    val b64 = Base64.getEncoder().encodeToString(scrambled).replace("/", "").replace("+", "").replace("=", "")
    return "zzc$part1$b64$part2".lowercase()
}

/** Pure parser for the live musics.fcg envelope; non-zero per-module codes are errors, not results. */
internal fun parseTxMusicuSearch(raw: String, keyword: String, type: String, limit: Int = 30): List<Song> =
    parseTxMusicuSearchStatus(raw, keyword, type, limit).songs

internal fun parseTxMusicuSearchStatus(raw: String, keyword: String, type: String, limit: Int = 30): TxTypedSearchResult {
    if (raw.isBlank()) return TxTypedSearchResult(emptyList(), available = false)
    if (limit <= 0) return TxTypedSearchResult(emptyList(), available = true)
    return try {
        val key = "music.search.SearchCgiService"
        val block = JSONObject(raw).optJSONObject(key) ?: return TxTypedSearchResult(emptyList(), available = false)
        if (block.optInt("code", -1) != 0) return TxTypedSearchResult(emptyList(), available = false)
        val data = block.optJSONObject("data") ?: return TxTypedSearchResult(emptyList(), available = false)
        if (data.has("code") && data.optInt("code", -1) != 0) return TxTypedSearchResult(emptyList(), available = false)
        val responseBody = data.optJSONObject("body") ?: return TxTypedSearchResult(emptyList(), available = false)
        val kind = when (type.lowercase()) {
            "artist", "singer" -> "singer"
            "album" -> "album"
            else -> "song"
        }
        val list = responseBody.optJSONObject(kind)?.optJSONArray("list") ?: return TxTypedSearchResult(emptyList(), available = false)
        val out = ArrayList<Song>(minOf(limit, list.length()))
        for (index in 0 until minOf(limit, list.length())) {
            val item = list.optJSONObject(index) ?: continue
            val mapped = when (kind) {
                "singer" -> mapTxMusicuSinger(item)
                "album" -> mapTxMusicuAlbum(item)
                else -> mapTxMusicuSong(item)
            }
            if (mapped != null) out += mapped
        }
        val songs = if (kind == "song") out.sortedByDescending { scoreSong(keyword, it.name, it.singer) } else out
        TxTypedSearchResult(songs, available = true)
    } catch (_: Exception) {
        TxTypedSearchResult(emptyList(), available = false)
    }
}

private fun mapTxMusicuSong(item: JSONObject): Song? {
    val album = item.optJSONObject("album") ?: JSONObject()
    val file = item.optJSONObject("file") ?: JSONObject()
    val id = txOrChain(item, "mid", "songmid", "song_mid", "id", "songid")
    val name = decodeProviderText(txOrChain(item, "name", "title", "songname"))
    if (id.isBlank() || name.isBlank()) return null
    val singer = item.optJSONArray("singer")?.let { artists ->
        (0 until artists.length()).mapNotNull { i ->
            artists.optJSONObject(i)?.let { decodeProviderText(txOrChain(it, "name", "title", "singerName")) }
                ?.takeIf(String::isNotBlank)
        }.joinToString(",")
    }.orEmpty().ifBlank { decodeProviderText(txOrChain(item, "singerName", "singername", "artist")) }
    // 主歌手 = singer 数组第一位，其 mid 即歌手页所需的 singerMid（此前只取 name、mid 被丢）。
    val singerId = item.optJSONArray("singer")?.optJSONObject(0)
        ?.let { txOrChain(it, "mid", "singerMid", "singerMID") }.orEmpty()
    val albumMid = txOrChain(album, "mid", "pmid", "albumMid").ifBlank { txOrChain(item, "albummid", "albumMid") }
    val albumName = decodeProviderText(txOrChain(album, "name", "title").ifBlank { txOrChain(item, "albumname", "albumName") })
    val cover = if (albumMid.isNotBlank()) "$TX_COVER_PREFIX$albumMid.jpg" else txOrChain(item, "cover", "albumPic", "album_pic")
    val durationSeconds = item.optLong("interval", item.optLong("duration", 0L))
    return Song(
        maxQuality = inferMaxQuality(item),
        id = id,
        name = cleanSpace(name),
        singer = cleanSpace(singer),
        singerId = singerId,
        source = "tx",
        cover = normalizeCoverUrl(cover),
        album = cleanSpace(albumName),
        albumId = albumMid.ifBlank { txOrChain(item, "albumid", "albumID") },
        duration = durationSeconds.coerceAtLeast(0L) * 1000L,
        mediaMid = txOrChain(file, "media_mid", "mediaMid").ifBlank { txOrChain(item, "media_mid", "mediaMid") },
    )
}

private fun mapTxMusicuSinger(item: JSONObject): Song? {
    val id = txOrChain(item, "singerMID", "singerMid", "singermid", "mid", "singerID", "singerId")
    val name = decodeProviderText(txOrChain(item, "singerName", "name", "title"))
    if (id.isBlank() || name.isBlank()) return null
    return Song(
        id = id,
        name = name,
        singer = name,
        source = "tx",
        cover = normalizeCoverUrl(txOrChain(item, "singerPic", "pic", "cover")),
    )
}

private fun mapTxMusicuAlbum(item: JSONObject): Song? {
    val id = txOrChain(item, "albumMid", "albumMID", "albummid", "mid", "albumID", "albumId", "id")
    val name = decodeProviderText(txOrChain(item, "albumName", "albumname", "name", "title"))
    if (id.isBlank() || name.isBlank()) return null
    val singer = decodeProviderText(txOrChain(item, "singerName", "singername", "artist"))
    val returnedCover = txOrChain(item, "albumPic", "album_pic", "pic", "cover")
    val cover = returnedCover.ifBlank { "$TX_COVER_PREFIX$id.jpg" }
    return Song(
        id = id,
        name = name,
        singer = singer,
        source = "tx",
        cover = normalizeCoverUrl(cover),
        album = name,
        albumId = id,
    )
}

/**
 * 纯解析函数（不触网，仅依赖 org.json，便于单测喂样例）：client_search_cp 原始 JSON → Song 列表（解析序）。
 * 服务端等价：((d['data'] or {})['song'] or {})['list'] or [] 的空对象/空数组视为缺失（or 语义），
 * 仅遍历前 limit 条（[:limit] 切片在过滤之前）；排序不在此处，统一由 searchTx 补封面后执行。
 */
internal fun parseTxSearch(raw: String, keyword: String, limit: Int = 12): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val data = JSONObject(raw).optJSONObject("data")?.takeIf { it.length() > 0 } ?: return emptyList()
        val song = data.optJSONObject("song")?.takeIf { it.length() > 0 } ?: return emptyList()
        val list = song.optJSONArray("list")?.takeIf { it.length() > 0 } ?: return emptyList()
        val out = ArrayList<Song>(minOf(limit, list.length()))
        for (i in 0 until minOf(limit, list.length())) {
            val item = list.optJSONObject(i) ?: continue
            val name = cleanSpace(txOrChain(item, "songname", "name"))
            val singerArr = item.optJSONArray("singer")
            val singers = cleanSpace(
                singerArr?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        arr.optJSONObject(idx)?.optString("name")
                    }.joinToString(",")
                } ?: ""
            )
            val songId = txOrChain(item, "songmid", "media_mid", "mid", "songid")
            // 主歌手 = singer 数组第一位的 mid（singerMid），与歌手名同源。
            val singerId = singerArr?.optJSONObject(0)?.let { txOrChain(it, "mid", "singerMid", "singerMID") }.orEmpty()
            val mediaMid = item.optString("media_mid")
            val albumMid = item.optString("albummid")
            val cover = if (albumMid.isNotEmpty()) "$TX_COVER_PREFIX$albumMid.jpg" else ""
            if (name.isEmpty() || songId.isEmpty()) continue
            out.add(
                Song(
                    maxQuality = inferMaxQuality(item),
                    id = songId,
                    name = name,
                    singer = singers,
                    singerId = singerId,
                    source = "tx",
                    cover = cover,
                    album = cleanSpace(item.optString("albumname")),
                    albumId = item.optString("albumid").ifEmpty { albumMid },
                    duration = 0L,
                    mediaMid = mediaMid,
                )
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/** 服务端 or 链语义：JSON null / 空串视为缺失并尝试下一个 key。 */
private fun txOrChain(obj: JSONObject, vararg keys: String): String {
    for (key in keys) {
        if (obj.isNull(key)) continue
        val value = obj.optString(key)
        if (value.isNotEmpty()) return value
    }
    return ""
}
