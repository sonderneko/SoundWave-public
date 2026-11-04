package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import java.net.URLEncoder

/** Public LX/Sollin album routes, using the existing anonymous HTTP policy.
 * KG's mobilecdn v3 alternative is already used by the repository's cover/detail path.
 * Never construct a KG cover from album ID. MG regular and digital albums use distinct public resource endpoints.
 */
internal suspend fun fetchAdditionalAlbumDetailDirect(source: String, id: String): Playlist? {
    val aid = URLEncoder.encode(id, "UTF-8")
    val detail = when (source) {
        "kw", "kuwo" -> parseKwAlbumDetail(platformHttpGet(
            "https://search.kuwo.cn/r.s?pn=0&rn=1000&stype=albuminfo&albumid=$aid&show_copyright_off=0&encoding=utf&vipver=MUSIC_9.1.0",
            "https://www.kuwo.cn/", 10000L), id)
        "kg", "kugou" -> {
            val meta = platformHttpGet("http://mobilecdn.kugou.com/api/v3/album/info?albumid=$aid", "https://m.kugou.com/", 8000L)
            val songs = ArrayList<Song>()
            // Same bounded 30-row paging as the existing public v3 server implementation.
            val seen = HashSet<String>()
            for (page in 1..4) {
                val raw = platformHttpGet("http://mobilecdn.kugou.com/api/v3/album/song?albumid=$aid&page=$page&pagesize=30", "https://m.kugou.com/", 8000L)
                val batch = parseKgAlbumDetail(meta, raw, id)
                val count = try { JSONObject(raw).optJSONObject("data")?.optJSONArray("info")?.length() ?: 0 } catch (_: Exception) { 0 }
                for (song in batch.songs) if (seen.add(song.id)) songs.add(song)
                if (count < 30) break
            }
            parseKgAlbumDetail(meta, "", id).copy(songs = songs)
        }
        "mg", "migu" -> {
            val songs = ArrayList<Song>()
            for (page in 1..20) {
                val raw = platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/resource/album/song/v2.0?albumId=$aid&pageNo=$page", "https://music.migu.cn/", 10000L)
                val batch = parseMgPlaylistDetail(raw)
                val known = songs.map { it.id }.toHashSet()
                songs.addAll(batch.filter { it.id !in known })
                if (batch.isEmpty() || !JSONObject(raw).optJSONObject("data").let { it?.optBoolean("hasNext", false) == true }) break
            }
            val meta = platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/resource/album/v2.0?albumId=$aid", "https://music.migu.cn/", 10000L)
            parseMgAlbumDetail(meta, "").copy(songs = songs)
        }
        else -> return null
    }
    if (detail.songs.isEmpty()) return null
    val src = when (source) { "kuwo" -> "kw"; "kugou" -> "kg"; "migu" -> "mg"; else -> source }
    return Playlist(id = id, name = detail.name.ifBlank { "专辑" }, cover = detail.cover,
        songs = detail.songs.toMutableList(), source = src, remoteId = id, count = detail.songs.size)
}

internal data class AdditionalAlbumDetail(val name: String, val cover: String, val songs: List<Song>)

/** LX kw/album.js stype=albuminfo envelope; malformed response stays unavailable. */
internal fun parseKwAlbumDetail(raw: String, albumId: String): AdditionalAlbumDetail {
    return try {
        val root = parseKwJson(raw)
        val name = cleanSpace(root.optString("name"))
        val cover = shrinkCover(firstStr(root, "img", "hts_img"))
        val rows = root.optJSONArray("musiclist")
        val songs = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            val item = rows?.optJSONObject(i) ?: return@mapNotNull null
            val id = kuwoRid(firstStr(item, "id", "rid", "musicrid"))
            val title = cleanSpace(item.optString("name"))
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            Song(id = id, name = title, singer = cleanSpace(item.optString("artist")), source = "kw",
                album = name, albumId = albumId, cover = shrinkCover(item.optString("pic")), duration = secondsToDurationMs(item.optLong("duration")))
        }.distinctBy { it.id }
        AdditionalAlbumDetail(name, cover, songs)
    } catch (_: Exception) { AdditionalAlbumDetail("", "", emptyList()) }
}

/** Reuse the repository's actual v3 filename/hash parser; album metadata is not a song image guess. */
internal fun parseKgAlbumDetail(metadata: String, raw: String, albumId: String): AdditionalAlbumDetail {
    val meta = try { JSONObject(metadata).optJSONObject("data") ?: JSONObject() } catch (_: Exception) { JSONObject() }
    val name = cleanSpace(meta.optString("albumname"))
    val cover = shrinkCover(meta.optString("imgurl").replace("{size}", "1080"))
    val root = try { raw.takeIf { it.length <= 1_048_576 }?.let { JSONObject(it) } } catch (_: Exception) { null }
    val rows = root?.takeIf { it.optInt("status") == 1 }?.optJSONObject("data")?.optJSONArray("info")
    val songs = parseKgPlaylistDetail(raw).map { song ->
        val matches = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
            .filter { row -> row.optString("album_id") == albumId &&
                firstStr(row, "hash", "320hash", "sqhash").equals(song.hash, true) }
        song.copy(album = name, cover = cover,
            duration = matches.singleOrNull()?.optLong("duration")?.takeIf { it > 0 }?.let(::secondsToDurationMs) ?: song.duration,
            albumId = song.albumId.takeIf { value -> value.isNotBlank() && value != "0" } ?: albumId
        ).let { parsed -> parsed.copy(kgAlbumMetadata = matches.singleOrNull()?.let { row ->
            xyz.cncnlp.soundwave.KgAlbumMetadata.fromCatalog(parsed, row)
        }) }
    }
    return AdditionalAlbumDetail(name, cover, songs)
}

/** MG uses the same songList envelope as its playlist route. Empty upstream is NOT support evidence. */
internal fun parseMgAlbumDetail(metadata: String, raw: String): AdditionalAlbumDetail {
    val meta = try { JSONObject(metadata).optJSONObject("data") ?: JSONObject() } catch (_: Exception) { JSONObject() }
    val images = meta.optJSONArray("imgItems") ?: meta.optJSONArray("imgItem")
    // r658c D#5：imgItems 按 imgSizeType 取最大档（旧实现取第一张=最小档，专辑封面模糊）；无尺寸标注时取最后一张。
    val cover = (0 until (images?.length() ?: 0)).mapNotNull { images?.optJSONObject(it) }
        .map { it.optString("img") to (it.optString("imgSizeType").trimStart('0').toIntOrNull() ?: -1) }
        .withIndex().filter { xyz.cncnlp.soundwave.mgImgOk(it.value.first) }
        .maxWithOrNull(compareBy({ it.value.second }, { it.index }))?.value?.first.orEmpty()
    return AdditionalAlbumDetail(cleanSpace(meta.optString("title")), shrinkCover(cover), parseMgPlaylistDetail(raw))
}

/** Digital resource IDs cannot be sent to the ordinary album API. No paid access is attempted. */
internal suspend fun fetchMgDigitalAlbumDetailDirect(id: String): Playlist? {
    val aid = URLEncoder.encode(id, "UTF-8")
    val raw = platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/resource/dalbum/song/v2.0?dAlbumId=$aid", "https://music.migu.cn/", 10000L)
    val songs = parseMgPlaylistDetail(raw)
    if (songs.isEmpty()) return null
    val meta = platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/resource/dalbum/v2.0?dAlbumId=$aid", "https://music.migu.cn/", 10000L)
    val parsed = parseMgAlbumDetail(meta, raw)
    return Playlist(id, parsed.name.ifBlank { songs.first().album }, parsed.cover, songs.toMutableList(),
        source = "mg", remoteId = id, count = songs.size, detailContentKind = "digital")
}
