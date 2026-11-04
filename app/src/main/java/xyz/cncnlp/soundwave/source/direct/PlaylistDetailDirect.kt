package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.decodePlaylistMetadata
import xyz.cncnlp.soundwave.mgImgOk
import xyz.cncnlp.soundwave.normalizeCoverUrl
import java.net.URLEncoder

/**
 * 歌单详情客户端直连（Local-first 批17，Phase2 收尾最后一块；additive，失败回落服务端）。
 * 逐行移植服务端 /music/playlist action=detail 处理器（/opt/soundwave/music_server.py:3432-3450）
 * 分派的五个平台歌单详情函数；wy 直接复用批15 wyPlaylistDetail（RankDirectFetch.kt:255）——
 * 服务端 netease_playlist_detail(:1995) 与榜单详情是同一函数（/music/rank 处理器 :3357 同样派发到它），
 * 含 wyCookie(os=pc + 登录 Cookie 仅 header) 与 trackIds 兜底，不做重复实现：
 * - kw kuwo_playlist_detail(:1901-1927)：nplserver.kuwo.cn pl.svc getlistinfo 单页 rn=limit；
 *   服务端 http(:1903)，客户端改 https（实测 200 且 musiclist 完整；HttpPolicy 白名单不扩面，无需豁免）
 * - kg kugou_playlist_detail(:1928-1956)：mobilecdn special/song（曲目）+ special/info（名称/封面）；
 *   详情模型只需曲目，special/info 请求省略（服务端 :1948-1954 仅产出 name/cover 元信息，由歌单卡片提供）；
 *   双域均在 HttpPolicy 明文白名单内（HttpPolicy.kt:12-17），http 直连放行
 * - tx qq_playlist_detail(:1867-1900)：qzone fcg_ucc_getcdinfo_byids_cp JSONP（贪婪首 { 至尾 } 剥壳
 *   :1877-1878）；singer ' / ' 连接(:1879，空名占位不过滤——与榜单 txDetail 的拼接规则不同，勿混用)
 * - mg migu_playlist_detail(:1957-1994)：playlist/song v2.0（songId 优先 :1967、img1/2/3 相对路径补
 *   https://d.musicapp.migu.cn :1973-1974）；服务端 iPhone UA(:1963)，实测桌面 UA（platformHttpGet
 *   固定 PLATFORM_HTTP_UA）响应逐字段一致，沿用现有通道
 * - 归一（照批13 normalizePlaylists 语义，PlaylistDirectSearch.kt:78-89）：(id,src) 去重 + 名字尾
 *   数字清洗（PY_WS/PY_DIGIT 显式字符类——Android 不支持 UNICODE_CHARACTER_CLASS）+ 空 id 丢弃；
 *   服务端 detail 处理器不套 add()（仅 search，:3379-3392），此为本批规格的客户端侧归一，回落路径不受影响
 * - 过滤语义：parseSongList 只留 id/name 非空（ApiClient.kt:415-417），各解析器同规则
 *   （kg 另需 ' - ' 分隔、kw 另需 sid+sname 非空，均照服务端原实现）
 * - 失败语义：网络（platformHttpGet 空串）/解析异常/平台空结果 → 空列表；source 未知或为空 → null
 *   （调用方回落原服务端路径），行为零回归
 */

/** wy 详情上限：客户端服务端路径 MAX_DETAIL_SONGS=1100（ApiClient.kt:1064），服务端处理器硬顶 1200（:3434-3438）。 */
private const val WY_DETAIL_LIMIT = 1100

/** 服务端各平台详情默认 limit=1000（:3434-3438）；kw/kg/mg 的 lim=min(int(limit),1000)（:1904/:1931/:1960）。 */
private const val KW_PL_DETAIL_LIMIT = 1000
private const val KG_PL_DETAIL_LIMIT = 1000
private const val MG_PL_DETAIL_LIMIT = 1000

/** 服务端四平台详情 timeout=10（:1876/:1906/:1933/:1965）。 */
private const val PL_DETAIL_TIMEOUT_MS = 10000L
private const val TX_METADATA_TIMEOUT_MS = 4000L

private const val KW_PL_SVC_URL = "https://nplserver.kuwo.cn/pl.svc"
private const val KW_PL_REFERER = "http://www.kuwo.cn/"
private const val KG_SPECIAL_SONG_URL = "http://mobilecdn.kugou.com/api/v3/special/song"
private const val KG_REFERER = "https://m.kugou.com/"
private const val TX_CDINFO_URL = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg"
private const val TX_REFERER = "https://y.qq.com/"
private const val MG_PLAYLIST_SONG_URL = "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0"
private const val MG_PL_REFERER = "https://m.music.migu.cn/"
private const val MG_PL_PREFIX = "https://d.musicapp.migu.cn"

/**
 * 歌单详情直连统一入口：按 source 分派各平台实现，再做批17规格归一。
 * 返回 null=未知/空 source（调用方回落服务端）；空列表=平台已尝试但空/异常（同样回落）。
 */
internal suspend fun fetchPlaylistDetailDirect(source: String, rid: String): List<Song>? {
    val src = normalizeRankSource(source) ?: return null
    val pid = rid.trim()
    if (pid.isEmpty()) return emptyList()
    return try {
        normalizeDetailSongs(
            when (src) {
                // wy 裸数字闸门：非数字 pid（script: 等导入残缺 id，MainActivity 可达性判断放行）必败，
                // 直接空回落服务端，省一次必败请求
                "wy" -> if (pid.all { it in '0'..'9' }) wyPlaylistDetail(pid, WY_DETAIL_LIMIT).songs else emptyList()
                "kw" -> parseKwPlaylistDetail(
                    platformHttpGet(
                        "$KW_PL_SVC_URL?op=getlistinfo&pid=${URLEncoder.encode(pid, "UTF-8")}&pn=0&rn=$KW_PL_DETAIL_LIMIT" +
                            "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1",
                        KW_PL_REFERER,
                        PL_DETAIL_TIMEOUT_MS,
                    )
                )
                "kg" -> {
                    val raw = platformHttpGet(
                        "$KG_SPECIAL_SONG_URL?specialid=${URLEncoder.encode(pid, "UTF-8")}&page=1&pagesize=$KG_PL_DETAIL_LIMIT",
                        KG_REFERER,
                        PL_DETAIL_TIMEOUT_MS,
                    )
                    kgFillAlbumCovers(parseKgPlaylistDetail(raw))
                }
                "tx" -> parseTxPlaylistDetail(
                    platformHttpGet(
                        txPlaylistDetailUrl(pid),
                        TX_REFERER,
                        PL_DETAIL_TIMEOUT_MS,
                    )
                )
                else -> parseMgPlaylistDetail(
                    platformHttpGet(
                        "$MG_PLAYLIST_SONG_URL?pageNo=1&pageSize=$MG_PL_DETAIL_LIMIT&playlistId=${URLEncoder.encode(pid, "UTF-8")}",
                        MG_PL_REFERER,
                        PL_DETAIL_TIMEOUT_MS,
                    )
                )
            }
        )
    } catch (_: Exception) {
        emptyList()
    }
}

/** Same-provider playlist fields used to hydrate the detail page; songs remain from their existing endpoint. */
internal data class DirectPlaylistDetail(
    val songs: List<Song>,
    val name: String = "",
    val cover: String = "",
    val author: String = "",
    val description: String = "",
    val count: Int = 0,
)

/** QQ musicu currently returns songs but empty directory metadata; fetch legacy cdinfo metadata in parallel. */
internal suspend fun fetchPlaylistDetailPayloadDirect(source: String, rid: String): DirectPlaylistDetail? {
    val src = normalizeRankSource(source) ?: return null
    val pid = rid.trim()
    if (pid.isEmpty()) return DirectPlaylistDetail(emptyList())
    if (src == "wy") {
        if (pid.any { it !in '0'..'9' }) return DirectPlaylistDetail(emptyList())
        val detail = try {
            wyPlaylistDetail(pid, WY_DETAIL_LIMIT)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DirectPlaylistDetail(emptyList())
        }
        return DirectPlaylistDetail(
            songs = normalizeDetailSongs(detail.songs),
            name = detail.name,
            cover = normalizeCoverUrl(detail.cover),
            author = detail.author,
            description = detail.description,
            count = detail.count,
        )
    }
    if (src != "tx") {
        val songs = fetchPlaylistDetailDirect(src, pid) ?: return null
        val encoded = URLEncoder.encode(pid, "UTF-8")
        val raw = when (src) {
            "kw" -> platformHttpGet("$KW_PL_SVC_URL?op=getlistinfo&pid=$encoded&pn=0&rn=1&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1", KW_PL_REFERER, PL_DETAIL_TIMEOUT_MS)
            "kg" -> platformHttpGet("http://mobilecdn.kugou.com/api/v3/special/info?specialid=$encoded", KG_REFERER, PL_DETAIL_TIMEOUT_MS)
            else -> platformHttpGet("https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0?playlistId=$encoded", MG_PL_REFERER, PL_DETAIL_TIMEOUT_MS)
        }
        return parseAdditionalPlaylistMetadata(src, raw).copy(songs = songs)
    }
    return try {
        coroutineScope {
            val songsTask = async(Dispatchers.IO) { fetchPlaylistDetailDirect(src, pid).orEmpty() }
            val metadataTask = async(Dispatchers.IO) {
                runCatching {
                    parseTxPlaylistMetadata(
                        platformHttpGet(txPlaylistMetadataUrl(pid), TX_REFERER, TX_METADATA_TIMEOUT_MS),
                        pid,
                    )
                }.getOrDefault(DirectPlaylistDetail(emptyList()))
            }
            val metadata = metadataTask.await()
            metadata.copy(songs = songsTask.await())
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DirectPlaylistDetail(emptyList())
    }
}

/** The legacy cdinfo response is metadata-only; the app keeps musicu aiDissInfo as the QQ song source. */
internal fun txPlaylistMetadataUrl(pid: String): String =
    "$TX_CDINFO_URL?type=1&json=1&utf8=1&onlysonglist=0&disstid=${URLEncoder.encode(pid, "UTF-8")}" +
        "&song_begin=0&song_num=1&format=json&platform=yqq&hostUin=0&needNewCode=0"

internal fun parseTxPlaylistMetadata(raw: String, requestedId: String): DirectPlaylistDetail {
    if (raw.isBlank()) return DirectPlaylistDetail(emptyList())
    return runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", 0) != 0) return@runCatching DirectPlaylistDetail(emptyList())
        val item = root.optJSONArray("cdlist")?.optJSONObject(0)
            ?: return@runCatching DirectPlaylistDetail(emptyList())
        val responseId = item.optString("disstid").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        if (responseId.isNotEmpty() && responseId != requestedId) return@runCatching DirectPlaylistDetail(emptyList())
        val rawCover = item.optString("logo").takeIf { it.isNotBlank() && it != "null" }
            ?: item.optString("dir_pic_url2").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        DirectPlaylistDetail(
            songs = emptyList(),
            name = decodePlaylistMetadata(item.optString("dissname")),
            cover = rawCover.takeIf { it.isNotBlank() }?.let(::normalizeCoverUrl).orEmpty(),
            author = decodePlaylistMetadata(item.optString("nick").ifBlank { item.optString("nickname") }),
            description = decodePlaylistMetadata(item.optString("desc").ifBlank { item.optString("description") }),
            count = item.optInt("songnum", item.optInt("total_song_num", 0)).coerceAtLeast(0),
        )
    }.getOrDefault(DirectPlaylistDetail(emptyList()))
}

/**
 * 批17规格归一（照 normalizePlaylists，PlaylistDirectSearch.kt:78-89）：空 id 丢弃、
 * (id,src) 去重、名字尾数字清洗（清洗后空串回原名）。仅作用于直连路径。
 */
internal fun normalizeDetailSongs(songs: List<Song>): List<Song> {
    val seen = HashSet<Pair<String, String>>()
    val out = ArrayList<Song>(songs.size)
    for (song in songs) {
        if (song.id.isEmpty()) continue
        if (!seen.add(song.id to song.source)) continue
        val cleanedName = PL_NAME_TRAILING_DIGITS.matcher(song.name).replaceAll("").trim().ifEmpty { song.name }
        out.add(song.copy(name = cleanedName))
    }
    return out
}

/** 纯解析：pl.svc getlistinfo 原始 JSON → 歌曲列表（kuwo_playlist_detail :1907-1924 1:1）。 */
/** kw 歌单详情封面只接受该歌曲响应中的真实专辑图字段；不根据 albumId 猜 URL。 */
private fun kwPlaylistCoverFallback(s: JSONObject): String {
    val shortPic = firstStr(s, "web_albumpic_short")
    if (shortPic.isNotEmpty()) {
        return if (shortPic.startsWith("http")) shortPic else "https://img1.kuwo.cn/star/albumcover/${shortPic.trimStart('/')}"
    }
    return ""
}

internal fun parseKwPlaylistDetail(raw: String): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val musiclist = JSONObject(raw).optJSONArray("musiclist") ?: return emptyList()
        val out = ArrayList<Song>(musiclist.length())
        for (i in 0 until musiclist.length()) {
            val s = musiclist.optJSONObject(i) ?: continue
            val sid = firstStr(s, "id", "rid", "musicrid")
            val sname = cleanSpace(firstStr(s, "name", "songname", "FSONGNAME"))
            if (sid.isEmpty() || sname.isEmpty()) continue
            out.add(
                Song(
                    maxQuality = inferMaxQuality(s),
                    id = sid,
                    name = sname,
                    singer = cleanSpace(firstStr(s, "artist", "FARTIST")),
                    // musiclist 条目带 artistid（与 artist 并列）；"0" 按缺失处理走按名兜底。
                    singerId = firstStr(s, "artistid", "ARTISTID", "artistId").takeUnless { it == "0" }.orEmpty(),
                    source = "kw",
                    duration = secondsToDurationMs(s.optLong("duration", s.optLong("DURATION", 0L))),
                    cover = shrinkCover(firstStr(s, "albumpic", "pic").ifEmpty { kwPlaylistCoverFallback(s) }, 1080),
                    album = cleanSpace(firstStr(s, "album", "FALBUM")),
                    // pl.svc 条目带真实 albumid，此前未读：空封面条目够不到专辑封面回补资格。
                    albumId = firstStr(s, "albumid", "ALBUMID").takeUnless { it == "0" }.orEmpty(),
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：special/song 原始 JSON → 歌曲列表（kugou_playlist_detail :1933-1946 1:1）。 */
/** 批25：酷狗歌单详情歌曲封面批量查（album/info imgurl 带 {size} 占位真实路径；兜底直拼）。 */
internal suspend fun kgFillAlbumCovers(songs: List<Song>): List<Song> {
    if (songs.isEmpty()) return songs
    val ids = songs.map { it.albumId }.filter { it.isNotBlank() && it != "0" }.distinct()
    if (ids.isEmpty()) return songs
    val covers = kgFetchAlbumCoverUrls(ids)
    return songs.map { s ->
        val found = covers[s.albumId]?.takeIf { it.isNotBlank() }
        if (s.cover.isBlank() && found != null) s.copy(cover = found) else s
    }
}

private const val KG_ALBUM_COVER_PARALLELISM = 6

/** 专辑封面批量查（accept6-singercover）：原实现 associateWith 逐专辑串行 await——
 * 一张歌手页/歌单几十张专辑就是几十轮串行往返，是 kg 详情最慢的一段。改有界并发（6）
 * 后墙钟降到约 1/6；单专辑查询口径与结果不变（见 kgFetchAlbumCoverUrl）。 */
private suspend fun kgFetchAlbumCoverUrls(ids: List<String>): Map<String, String> = coroutineScope {
    val gate = Semaphore(KG_ALBUM_COVER_PARALLELISM)
    ids.map { aid -> async { gate.withPermit { aid to kgFetchAlbumCoverUrl(aid) } } }.awaitAll().toMap()
}

/** 单专辑封面查询：album/info 的 imgurl（{size} 占位真实路径）。 */
private suspend fun kgFetchAlbumCoverUrl(albumId: String): String {
    val raw = platformHttpGet("http://mobilecdn.kugou.com/api/v3/album/info?albumid=$albumId", KG_REFERER, 5000L)
    val u = runCatching { JSONObject(raw).optJSONObject("data")?.optString("imgurl").orEmpty() }.getOrDefault("")
    // 批27：album/info 拿不到 imgurl 留空——直拼 imge.kugou.com/stdmusic/1080/{aid}.jpg 返回 200
    // 但是酷狗默认蓝占位图（非歌曲封面），宁缺毋滥。
    return if (u.isNotEmpty()) u.replace("{size}", "1080") else ""
}

internal fun parseKgPlaylistDetail(raw: String): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val info = JSONObject(raw).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val out = ArrayList<Song>(info.length())
        for (i in 0 until info.length()) {
            val s = info.optJSONObject(i) ?: continue
            val hashv = firstStr(s, "hash", "320hash", "sqhash").uppercase()
            val filename = cleanSpace(s.optString("filename"))
            if (hashv.isEmpty() || filename.isEmpty() || !filename.contains(" - ")) continue
            val parts = filename.split(" - ", limit = 2)
            // 批25：封面改 album/info imgurl 真实路径（批量查填）；直拼 album_id 对多数专辑无效。
            val albumId = s.optString("album_id").ifEmpty { "0" }
            out.add(
                Song(
                    maxQuality = inferMaxQuality(s),
                    id = hashv,
                    name = parts[1],
                    singer = parts[0],
                    // 歌名/歌手来自 filename 拆分；条目若另带 singerid 则顺手回填，取不到留空走按名兜底。
                    singerId = firstStr(s, "singerid", "singerId"),
                    source = "kg",
                    hash = hashv,
                    albumId = albumId,
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：musicu.fcg aiDissInfo 原始 JSON → 歌曲列表（批25：旧 cdlist.songlist 已改版缺失）。 */
internal fun parseTxPlaylistDetail(raw: String): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val detail = JSONObject(raw).optJSONObject("detail")?.optJSONObject("data") ?: return emptyList()
        val songlist = detail.optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<Song>(songlist.length())
        for (i in 0 until songlist.length()) {
            val s = songlist.optJSONObject(i) ?: continue
            val mid = orChain(s, "mid", "songmid")
            val name = orChain(s, "title", "name", "songname")
            if (mid.isEmpty() || name.isEmpty()) continue
            val singers = s.optJSONArray("singer")
            val singer = if (singers != null) {
                (0 until singers.length()).mapNotNull { singers.optJSONObject(it) }
                    .joinToString(" / ") { cleanSpace(it.optString("name")) }
            } else ""
            // 主歌手 = singer 数组第一位的 mid（singerMid），与歌手名同源（此前只取 name）。
            val singerId = singers?.optJSONObject(0)?.optString("mid")?.takeUnless { it == "null" }.orEmpty()
            val album = s.optJSONObject("album")
            val albummid = album?.optString("mid").orEmpty().ifEmpty { s.optString("albummid") }
            val albumName = album?.optString("name").orEmpty().ifEmpty { s.optString("albumname") }
            val mediaMid = s.optString("strMediaMid").ifEmpty {
                s.optString("media_mid").ifEmpty { s.optJSONObject("file")?.optString("media_mid").orEmpty() }
            }
            val sc = if (albummid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albummid.jpg" else ""
            out.add(
                Song(
                    maxQuality = inferMaxQuality(s),
                    id = mid,
                    name = cleanSpace(name),
                    singer = singer,
                    singerId = singerId,
                    source = "tx",
                    cover = shrinkCover(sc),
                    album = cleanSpace(albumName),
                    duration = secondsToDurationMs(s.optLong("interval", 0L)),
                    albumId = albummid,
                    mediaMid = mediaMid,
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 批25：QQ 歌单详情 musicu.fcg aiDissInfo URL（GET，data 为 URL 编码 JSON）。 */
internal fun txPlaylistDetailUrl(pid: String): String {
    val data = "{\"comm\":{\"ct\":24,\"cv\":0},\"detail\":{\"module\":\"music.srfDissInfo.aiDissInfo\"," +
        "\"method\":\"uniform_get_Dissinfo\",\"param\":{\"disstid\":$pid,\"onlysonglist\":1,\"song_begin\":0," +
        "\"song_num\":500,\"enc_host_uin\":\"\",\"tag\":1,\"userinfo\":1,\"songtype\":1}}}"
    return "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${URLEncoder.encode(data, "UTF-8")}"
}

/** 纯解析：playlist/song v2.0 原始 JSON → 歌曲列表（migu_playlist_detail :1965-1976 1:1）。 */
internal fun parseMgPlaylistDetail(raw: String): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        if (root.has("code") && root.optString("code") != "000000") return emptyList()
        val songList = root.optJSONObject("data")?.optJSONArray("songList") ?: return emptyList()
        val out = ArrayList<Song>(songList.length())
        for (i in 0 until songList.length()) {
            val s = songList.optJSONObject(i) ?: continue
            // 服务端 songId 优先（:1967）——与 MgDirectSearch 的 contentId 优先相反，此处照详情原实现
            val sid = firstStr(s, "songId", "contentId")
            val sname = cleanSpace(s.optString("songName"))
            if (sid.isEmpty() || sname.isEmpty()) continue
            val mgCopyrightId = firstStr(s, "copyrightId", "copyright_id")
            // 批27：img1/2/3 取第一个带图片扩展名的完整 URL——上游间歇性返回截断 URL（.web 缺尾 p）会裂图，
            // 截断/坏 URL 一律不采用（宁可空封面由客户端显示默认图）。
            var img = ""
            // r658c D#5：实测 img1/img2/img3 = 200/400/800 px（同一歌曲三档），旧顺序取 img1 → 封面模糊；改为大图优先。
            for (k in listOf("img3", "img2", "img1")) {
                val v = s.optString(k)
                if (v.isNotEmpty() && mgImgOk(v)) {
                    img = v
                    break
                }
            }
            if (img.isNotEmpty() && !img.startsWith("http")) img = MG_PL_PREFIX + img
            val singers = s.optJSONArray("singerList")
            val singer = if (singers != null) {
                (0 until singers.length())
                    .mapNotNull { j -> singers.optJSONObject(j)?.optString("name")?.takeIf { it.isNotEmpty() } }
                    .joinToString(",")
            } else ""
            // 主歌手 = singerList 第一位的 id，与歌手名同源（此前只取 name）。
            val singerId = singers?.optJSONObject(0)?.optString("id")?.takeUnless { it == "null" }.orEmpty()
            out.add(
                Song(
                    maxQuality = inferMaxQuality(s),
                    id = sid,
                    name = sname,
                    singer = cleanSpace(singer),
                    singerId = singerId,
                    source = "mg",
                    cover = shrinkCover(img),
                    duration = s.optLong("duration").coerceAtLeast(0L) * 1000L,
                    album = cleanSpace(s.optString("album")),
                    albumId = firstStr(s, "albumId"),
                    miguSongId = firstStr(s, "songId"),
                    copyrightId = mgCopyrightId,
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}
