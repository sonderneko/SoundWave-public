package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.mgImgOk
import xyz.cncnlp.soundwave.normalizeCoverUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

/** Anonymous artist detail routes. LX tx/singer.js GetSingerSongList; KG v3 public
 * alternative used by existing server (LX v5 equivalent); WY public v1 artist.
 * KW: kuwo www artist endpoints (music-get proven shape). MG: bmw singer/song +
 * resourceinfo type 2002 on the already direct-connected app.c.nf.migu.cn.
 */
internal suspend fun fetchArtistDetailDirect(source: String, artistId: String, nameHint: String): Playlist? {
    if (artistId.isBlank()) return null
    val id = URLEncoder.encode(artistId, "UTF-8")
    val detail = when (source) {
        "wy" -> parseWyArtistDetail(platformHttpGet("https://music.163.com/api/v1/artist/$id", "https://music.163.com/"), artistId, nameHint)
        "tx" -> {
            val request = JSONObject().put("req_0", JSONObject().put("module", "musichall.song_list_server")
                .put("method", "GetSingerSongList").put("param", JSONObject()
                    .put("singerMid", artistId).put("begin", 0).put("num", 100).put("order", 1)))
            parseTxArtistDetail(platformHttpGet("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${URLEncoder.encode(request.toString(), "UTF-8")}",
                "https://y.qq.com/", 10000L), artistId, nameHint)
        }
        "kg" -> {
            // info 与 songs 相互独立：并发取，墙钟由两轮串行降到一轮（专辑封面回填的并行化在 kgFillAlbumCovers）。
            val (meta, raw) = coroutineScope {
                val metaD = async { platformHttpGet("http://mobilecdn.kugou.com/api/v3/singer/info?singerid=$id", "https://m.kugou.com/") }
                val rawD = async { platformHttpGet("http://mobilecdn.kugou.com/api/v3/singer/song?singerid=$id&page=1&pagesize=100", "https://m.kugou.com/", 10000L) }
                metaD.await() to rawD.await()
            }
            parseKgArtistDetail(meta, raw, artistId, nameHint)?.let { it.copy(songs = kgFillAlbumCovers(it.songs).toMutableList()) }
        }
        "kw" -> {
            // 播种（fetchKwArtistJson 内的 kw_token 30 分钟缓存）之后两请求相互独立：并发取，少一轮串行往返。
            val (meta, raw) = coroutineScope {
                val metaD = async { fetchKwArtistJson("https://www.kuwo.cn/api/www/artist/artist?artistid=$id") }
                val rawD = async { fetchKwArtistJson("https://www.kuwo.cn/api/www/artist/artistMusic?artistid=$id&pn=1&rn=100") }
                metaD.await() to rawD.await()
            }
            parseKwArtistDetail(meta, raw, artistId, nameHint)
        }
        "mg" -> {
            // 信息 + 两页歌曲相互独立：并发取，墙钟由三轮串行降到一轮（合并/去重口径在 parseMgArtistDetail 不变）。
            val (meta, page1, page2) = coroutineScope {
                val metaD = async { platformHttpGet("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?needSimple=01&resourceType=2002&resourceId=$id", "https://music.migu.cn/", 10000L) }
                val page1D = async { platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/bmw/singer/song/v1.0?singerId=$id&pageNo=1", "https://music.migu.cn/", 10000L) }
                val page2D = async { platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/bmw/singer/song/v1.0?singerId=$id&pageNo=2", "https://music.migu.cn/", 10000L) }
                Triple(metaD.await(), page1D.await(), page2D.await())
            }
            parseMgArtistDetail(meta, page1, page2, artistId, nameHint)
        }
        else -> null
    }
    return detail?.takeIf { it.songs.isNotEmpty() }
}

internal fun parseWyArtistDetail(raw: String, artistId: String, nameHint: String): Playlist? = try {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) null else {
        val artist = root.optJSONObject("artist") ?: JSONObject()
        val rows = root.optJSONArray("hotSongs") ?: artist.optJSONArray("hotSongs") ?: JSONArray()
        val more = root.optJSONArray("moreSongs")
        val pool = JSONArray()
        for (i in 0 until rows.length()) pool.put(rows.optJSONObject(i))
        if (more != null) {
            for (i in 0 until more.length()) pool.put(more.optJSONObject(i))
        }
        val songs = parseWyAlbumDetail(JSONObject().put("songs", pool).toString()).songs
            .filter { it.name.isNotBlank() }.distinctBy { it.id }.take(100)
        val name = cleanSpace(artist.optString("name")).ifBlank { nameHint }
        Playlist(id = "singer_wy_$artistId", name = name, source = "wy", remoteId = artistId,
            cover = shrinkCover(firstStr(artist, "picUrl", "img1v1Url")), description = cleanSpace(artist.optString("briefDesc")).take(5000),
            author = name, songs = songs.toMutableList(), count = songs.size)
    }
} catch (_: Exception) { null }

internal fun parseTxArtistDetail(raw: String, artistId: String, nameHint: String): Playlist? = try {
    val root = JSONObject(raw)
    val block = root.optJSONObject("req_0")
    if (root.optInt("code", -1) != 0 || block?.optInt("code", -1) != 0) null else {
        val data = block?.optJSONObject("data") ?: JSONObject()
        val rows = data.optJSONArray("songList") ?: JSONArray()
        val songsJson = JSONArray()
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.optJSONObject("songInfo")?.let { songsJson.put(it) }
        val songs = parseTxPlaylistDetail(JSONObject().put("detail", JSONObject().put("data", JSONObject().put("songlist", songsJson))).toString())
            .distinctBy { it.id }.take(100)
        val name = cleanSpace(firstStr(data, "singerName", "name")).ifBlank { nameHint }
        // No anonymous SingerInfoInter biography: don't substitute the first track's album image.
        Playlist(id = "singer_tx_$artistId", name = name, source = "tx", remoteId = artistId,
            author = name, songs = songs.toMutableList(), count = songs.size)
    }
} catch (_: Exception) { null }

internal fun parseKgArtistDetail(metadata: String, raw: String, artistId: String, nameHint: String): Playlist? = try {
    val root = JSONObject(raw)
    if (root.optInt("errcode", 0) != 0 || root.optJSONObject("data")?.optJSONArray("info") == null) null else {
        val meta = try { JSONObject(metadata).optJSONObject("data") ?: JSONObject() } catch (_: Exception) { JSONObject() }
        val name = cleanSpace(meta.optString("singername")).ifBlank { nameHint }
        val songs = parseKgPlaylistDetail(raw).distinctBy { it.id }.take(100)
        Playlist(id = "singer_kg_$artistId", name = name, source = "kg", remoteId = artistId,
            author = name, cover = shrinkCover(meta.optString("imgurl").replace("{size}", "1080")),
            description = cleanSpace(meta.optString("profile")).take(5000), songs = songs.toMutableList(), count = songs.size)
    }
} catch (_: Exception) { null }

@Volatile private var kwTokenValue: String? = null
@Volatile private var kwTokenFetchedAtMs: Long = 0L

/**
 * KW www artist endpoints demand the browser token pair in 2026 (anonymous calls
 * are refused with a non-200 envelope from every egress tested): seed `kw_token`
 * from the www homepage's Set-Cookie once (30-min in-process cache) and replay it
 * as Cookie + `csrf` header — the Meting pattern, scoped to this kw artist fetch
 * only. No token (or a refused call) degrades to the anonymous request and then
 * to the /music/singer fallback via the parser's envelope gate.
 */
private suspend fun fetchKwArtistJson(url: String): String {
    val token = kwSeededToken()
    return if (token != null) {
        platformHttpGetWithHeaders(url, "https://www.kuwo.cn/", mapOf("Cookie" to "kw_token=$token", "csrf" to token), 10000L)
    } else {
        platformHttpGet(url, "https://www.kuwo.cn/", 10000L)
    }
}

private suspend fun kwSeededToken(): String? {
    val cached = kwTokenValue
    if (cached != null && System.currentTimeMillis() - kwTokenFetchedAtMs < 30L * 60L * 1000L) return cached
    val cookies = platformHttpGetSetCookies("https://www.kuwo.cn/", "https://www.kuwo.cn/")
    val token = cookies.firstNotNullOfOrNull { line ->
        val pair = line.substringBefore(';').trim()
        if (pair.startsWith("kw_token=")) pair.removePrefix("kw_token=").takeIf { it.isNotBlank() } else null
    }
    if (token != null) {
        kwTokenValue = token
        kwTokenFetchedAtMs = System.currentTimeMillis()
    }
    return token
}

/** KW www artist endpoints (music-get proven envelope): info {code:200, data{id,name,pic300}},
 * songs {code:200, data{list[]}} with rid/name/artist/album/albumpic on each row. Any
 * non-200 envelope (incl. the gateway's illegal-request body) parses to null so the
 * caller keeps the /music/singer fallback. */
internal fun parseKwArtistDetail(metadata: String, raw: String, artistId: String, nameHint: String): Playlist? = try {
    val root = parseKwJson(raw)
    val rows = root.takeIf { it.optInt("code", -1) == 200 }?.optJSONObject("data")?.optJSONArray("list")
    if (rows == null) null else {
        val meta = try { parseKwJson(metadata).optJSONObject("data") ?: JSONObject() } catch (_: Exception) { JSONObject() }
        val seen = HashSet<String>()
        val songs = ArrayList<Song>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val name = cleanSpace(item.optString("name"))
            val songId = kuwoRid(orChain(item, "rid", "musicrid", "id"))
            if (name.isEmpty() || songId.isEmpty() || !seen.add(songId)) continue
            val pic = firstStr(item, "albumpic", "pic120", "pic")
            songs.add(
                Song(
                    maxQuality = inferMaxQuality(item),
                    id = songId,
                    name = name,
                    singer = cleanSpace(item.optString("artist")),
                    singerId = item.optString("artistid").takeUnless { it == "0" }.orEmpty(),
                    source = "kw",
                    cover = when {
                        pic.startsWith("http") -> pic
                        pic.startsWith("/") -> "https://img1.kuwo.cn$pic"
                        pic.isNotEmpty() -> "https://img1.kuwo.cn/$pic"
                        else -> ""
                    },
                    album = cleanSpace(item.optString("album")),
                    albumId = item.optString("albumid").takeUnless { it == "0" }.orEmpty(),
                    duration = kwArtistDurationMs(item),
                )
            )
        }
        val name = cleanSpace(meta.optString("name")).ifBlank { nameHint }
        val finalSongs = songs.take(100)
        Playlist(id = "singer_kw_$artistId", name = name, source = "kw", remoteId = artistId,
            author = name, cover = shrinkCover(firstStr(meta, "pic500", "pic300", "pic120", "pic")),
            description = cleanSpace(meta.optString("desc")).take(5000),
            songs = finalSongs.toMutableList(), count = finalSongs.size)
    }
} catch (_: Exception) { null }

/** artistMusic rows carry `duration` in seconds on some rows and `songTimeMinutes`
 * ("m:ss") on others; accept whichever is present. */
private fun kwArtistDurationMs(item: JSONObject): Long {
    item.optLong("duration", 0L).takeIf { it > 0 }?.let { return secondsToDurationMs(it) }
    val parts = item.optString("songTimeMinutes").trim().split(":")
    if (parts.size == 2) {
        val totalSeconds = (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
        if (totalSeconds > 0) return secondsToDurationMs(totalSeconds)
    }
    return 0L
}

/** MG singer songs: bmw view-tree (same family as the shipped bmw/singer/album) —
 * data.contents[] blocks whose items wrap a full `songItem` (contentId/copyrightId/
 * songId/singerList/albumId/duration/audioFormats). Two pages of 50 merge to the
 * same 100-row cap as the kg branch. Info: resourceinfo type 2002 resource[0]. */
internal fun parseMgArtistDetail(metadata: String, rawPage1: String, rawPage2: String, artistId: String, nameHint: String): Playlist? = try {
    val songs = (parseMgBmwSingerSongs(rawPage1) + parseMgBmwSingerSongs(rawPage2))
        .distinctBy { it.id }.take(100)
    if (songs.isEmpty()) null else {
        val meta = try { JSONObject(metadata).optJSONArray("resource")?.optJSONObject(0) ?: JSONObject() } catch (_: Exception) { JSONObject() }
        val name = cleanSpace(meta.optString("singer")).ifBlank { nameHint }
        val images = meta.optJSONArray("imgs")
        val cover = (0 until (images?.length() ?: 0)).mapNotNull { images?.optJSONObject(it) }
            .map { it.optString("img") to (it.optString("imgSizeType").trimStart('0').toIntOrNull() ?: -1) }
            .withIndex().filter { mgImgOk(it.value.first) }
            .maxWithOrNull(compareBy({ it.value.second }, { it.index }))?.value?.first.orEmpty()
        Playlist(id = "singer_mg_$artistId", name = name, source = "mg", remoteId = artistId,
            author = name, cover = normalizeCoverUrl(cover),
            description = cleanSpace(meta.optString("summary")).take(5000),
            songs = songs.toMutableList(), count = songs.size)
    }
} catch (_: Exception) { null }

internal fun parseMgBmwSingerSongs(raw: String): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        if (root.optString("code") != "000000") return emptyList()
        val blocks = root.optJSONObject("data")?.optJSONArray("contents") ?: return emptyList()
        val out = ArrayList<Song>()
        for (b in 0 until blocks.length()) {
            val items = blocks.optJSONObject(b)?.optJSONArray("contents") ?: continue
            for (i in 0 until items.length()) {
                val si = items.optJSONObject(i)?.optJSONObject("songItem") ?: continue
                val name = cleanSpace(si.optString("songName"))
                val songId = orChain(si, "contentId", "copyrightId")
                if (name.isEmpty() || songId.isEmpty()) continue
                val singers = si.optJSONArray("singerList")
                val singerNames = (0 until (singers?.length() ?: 0)).mapNotNull {
                    singers?.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
                }
                val img = si.optString("img1")
                out.add(
                    Song(
                        maxQuality = inferMaxQuality(si),
                        id = songId,
                        name = name,
                        singer = cleanSpace(singerNames.joinToString(",")),
                        singerId = singers?.optJSONObject(0)?.optString("id").orEmpty(),
                        source = "mg",
                        cover = normalizeCoverUrl(
                            when {
                                img.startsWith("http") -> img
                                img.startsWith("/") -> "https://d.musicapp.migu.cn$img"
                                else -> img
                            }
                        ),
                        album = cleanSpace(si.optString("album")),
                        albumId = si.optString("albumId").takeUnless { it == "0" }.orEmpty(),
                        copyrightId = si.optString("copyrightId"),
                        miguSongId = si.optString("songId"),
                        duration = secondsToDurationMs(si.optLong("duration", 0L)),
                    )
                )
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}
