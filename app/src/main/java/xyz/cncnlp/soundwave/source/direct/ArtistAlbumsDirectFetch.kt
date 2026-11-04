package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.ArtistAlbum
import java.net.URLEncoder

/**
 * 歌手专辑列表直连（匿名公开接口）：
 * - wy：/api/artist/albums/{id}（hotAlbums：id / name / picUrl / publishTime ms / size）
 * - tx：musicu music.musichallAlbum.AlbumListServer.GetAlbumList（singerMid → albumMid，封面 T002R300x300M000{mid}）
 * - kg：mobilecdn v3 singer/album（albumid / albumname / imgurl {size} / publishtime / songcount）
 * kw：公开 stype=albumlist；mg：singer/album，保留其普通/数字专辑类型以分派正确详情。
 * page 从1开始；UI主动续页，末页/重复页停止，不假定其他平台不支持。
 */
internal suspend fun fetchArtistAlbumsDirect(source: String, artistId: String, limit: Int = 30, page: Int = 1): List<ArtistAlbum> {
    if (artistId.isBlank()) return emptyList()
    require(page >= 1 && limit in 1..100)
    val offset = (page - 1) * limit
    val id = URLEncoder.encode(artistId, "UTF-8")
    return when (source) {
        "wy" -> parseWyArtistAlbums(artistCatalogGet("https://music.163.com/api/artist/albums/$id?limit=$limit&offset=$offset", "https://music.163.com/"))
        "tx" -> {
            val req = JSONObject().put("req_0", JSONObject().put("module", "music.musichallAlbum.AlbumListServer")
                .put("method", "GetAlbumList").put("param", JSONObject()
                    .put("singerMid", artistId).put("order", 0).put("begin", offset).put("num", limit).put("songNumTag", 0).put("singerID", 0)))
            parseTxArtistAlbums(artistCatalogGet("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${URLEncoder.encode(req.toString(), "UTF-8")}", "https://y.qq.com/", 10000L))
        }
        "kg" -> parseKgArtistAlbums(artistCatalogGet("http://mobilecdn.kugou.com/api/v3/singer/album?singerid=$id&page=$page&pagesize=$limit", "https://m.kugou.com/", 10000L))
        "kw" -> parseKwArtistAlbums(artistCatalogGet("https://search.kuwo.cn/r.s?artistid=$id&stype=albumlist&pn=${page - 1}&rn=$limit&sortby=1&encoding=utf8&rformat=json&pcjson=1", "https://www.kuwo.cn/", 10000L))
        "mg" -> parseMgArtistAlbums(artistCatalogGet("https://app.c.nf.migu.cn/bmw/singer/album/v1.0?singerId=$id&pageNo=$page", "https://music.migu.cn/", 10000L))
        else -> emptyList()
    }
}

private fun dateOf(ms: Long): String =
    if (ms <= 0L) "" else java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).format(java.util.Date(ms))

private fun dotDate(raw: String): String = raw.trim().take(10).replace('-', '.')

internal fun parseWyArtistAlbums(raw: String): List<ArtistAlbum> = try {
    val arr = JSONObject(raw).optJSONArray("hotAlbums")
    (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val aid = o.optString("id").takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
        ArtistAlbum(aid, cleanSpace(o.optString("name")), shrinkCover(o.optString("picUrl")), dateOf(o.optLong("publishTime")), o.optInt("size"), "wy")
    }
} catch (_: Exception) { emptyList() }

internal fun parseTxArtistAlbums(raw: String): List<ArtistAlbum> = try {
    val data = JSONObject(raw).optJSONObject("req_0")?.optJSONObject("data")
    val arr = data?.optJSONArray("albumList")
    (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val mid = o.optString("albumMid").ifBlank { return@mapNotNull null }
        ArtistAlbum(mid, cleanSpace(o.optString("albumName")), "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg",
            dotDate(o.optString("publishDate")), o.optInt("totalNum"), "tx")
    }
} catch (_: Exception) { emptyList() }

internal fun parseKgArtistAlbums(raw: String): List<ArtistAlbum> = try {
    val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("info")
    (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val aid = o.optString("albumid").takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
        ArtistAlbum(aid, cleanSpace(o.optString("albumname")), shrinkCover(o.optString("imgurl").replace("{size}", "400")),
            dotDate(o.optString("publishtime")), o.optInt("songcount"), "kg")
    }
} catch (_: Exception) { emptyList() }

/** Field names and page envelopes checked against live public artist catalog responses. */
internal fun parseKwArtistAlbums(raw: String): List<ArtistAlbum> = try {
    val rows = parseKwJson(raw).optJSONArray("albumlist")
    (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        val item = rows!!.optJSONObject(i) ?: return@mapNotNull null
        val id = item.optString("albumid").takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
        // 封面优先 hts_img（imgN.kuwo.cn HTTPS 镜像）；img 是 sycdn 主机，
        // HTTPS 实测 TLS 失败（normalizeCoverUrl 亦有主机归一兜底）。
        // 曲目数上游字段是 musiccnt（无 musicNum）。
        ArtistAlbum(id, cleanSpace(item.optString("name")), shrinkCover(firstStr(item, "hts_img", "img")),
            dotDate(item.optString("pub")), item.optInt("musicNum").takeIf { it > 0 } ?: item.optInt("musiccnt"), "kw")
    }.distinctBy { it.id }
} catch (_: Exception) { emptyList() }

internal fun parseMgArtistAlbums(raw: String): List<ArtistAlbum> = try {
    val root = JSONObject(raw)
    val rows = root.takeIf { it.optString("code") == "000000" }?.optJSONObject("data")?.optJSONArray("contents")
    (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        val item = rows!!.optJSONObject(i) ?: return@mapNotNull null
        val action = item.optString("action")
        val kind = when {
            action.startsWith("mgmusic://digital-album-info?") -> "digital"
            action.startsWith("mgmusic://album-info?") -> "album"
            else -> return@mapNotNull null
        }
        val id = item.optString("resId").takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
        ArtistAlbum(id, cleanSpace(item.optString("txt")), shrinkCover(item.optString("img")),
            dotDate(item.optString("txt3")), 0, "mg", kind)
    }.distinctBy { "${it.kind}:${it.id}" }
} catch (_: Exception) { emptyList() }

private suspend fun artistCatalogGet(url: String, referer: String, timeoutMs: Long = 10000L): String =
    when (val result = platformHttpGetOutcome(url, referer, timeoutMs)) {
        is PlatformTransportOutcome.Success -> result.value.takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("Empty artist album response")
        is PlatformTransportOutcome.Failure -> throw java.io.IOException("Artist album request failed: ${result.error.kind}")
    }
