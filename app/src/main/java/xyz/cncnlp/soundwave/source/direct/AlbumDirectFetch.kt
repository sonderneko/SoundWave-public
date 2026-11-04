package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import java.net.URLEncoder

/**
 * 专辑详情客户端直连（Local-first 批18，最后一块未直连能力；additive，失败回落服务端）。
 * 逐行移植服务端 /music/album 处理器（/opt/soundwave/music_server.py:3091-3102）实际分派的两个
 * 平台专辑函数；kw/kg/mg 服务端本就未实现（handler else 分支 detail=None → 404 :3098-3100），
 * 客户端同语义返回 null 回落 ApiClient.albumDetailServer（原服务端路径同样 404 → null，零回归）：
 * - tx qq_album_detail(:2360-2382)：fcg_v8_album_info_cp.fcg?albummid=&format=json（Referer
 *   https://y.qq.com/、timeout=10 :2363-2365）；非 '{' 开头贪婪剥壳（:2366）；曲目 songmid/songid
 *   or 链、空 id 丢弃（:2368-2370）、singer 数组 ' / ' 连接（:2372-2373）、song 封面
 *   T002R800x800M000{albummid}.jpg（:2374，albummid 缺省回落专辑 album_id）、
 *   album=albumname or data.name（:2376）；专辑封面 T002R800x800M000{album_id}.jpg（:2378）、
 *   name=clean_space(data.name)（:2377）；limit=1200（:2360）
 * - wy netease_album_detail(:2387-2420)：/api/v1/album/{id}（Referer https://music.163.com/、
 *   timeout=10 :2392-2395）；j.songs 逐首（:2399）、仅 id 非空过滤（:2400，name 不过滤照原实现）、
 *   ar/artists ' / ' 连接（:2403）、al 非 dict 回落专辑对象（:2404）、song 封面
 *   shrink_cover(al.picUrl or album.picUrl)（:2405，缺失即空串不补造，避免色裂占位）、
 *   album=al.name or album.name（:2407）、duration=int(dt)（:2408）；专辑 name=album.name or
 *   '专辑'（:2414，空名兜底在调用方）、封面 shrink_cover（:2415）
 * - 账号：服务端两函数均匿名请求（无 cookie 参数，:2363-2365/:2393-2395），客户端同样匿名；
 *   客户端不提供第三方账号凭证注入路径。
 * - 失败语义：网络空串/JSON 异常/空曲目 → null（调用方回落 albumDetailServer 多路径探测）；
 *   tx 服务端 except 返回空 dict（:2381-2382）仍下发，客户端等价空结果同样回落
 * - 归一：tx 曲目 cover 走 shrinkCover(1080) 对 gtimg 800x800 直链为 no-op（与批17 txDetail 一致）；
 *   wy album 级封面/曲目封面均 shrink 1080（:2405/:2415）；author 服务端未提供，保持 Playlist 默认空串
 * - 调用方 MainActivity:1618（首页专辑卡）/ :1795（albumId 专辑跳转）已在协程内，suspend 化零改动
 */

/** 服务端 qq_album_detail 默认 limit=1200（:2360）。 */
private const val TX_ALBUM_LIMIT = 1200

/** 服务端两平台专辑详情 timeout=10（:2365/:2395）。 */
private const val ALBUM_TIMEOUT_MS = 10000L

private const val TX_ALBUM_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg"
private const val TX_ALBUM_REFERER = "https://y.qq.com/"
private const val TX_ALBUM_COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R800x800M000"
private const val WY_ALBUM_URL = "https://music.163.com/api/v1/album/"
private const val WY_ALBUM_REFERER = "https://music.163.com/"

/**
 * 专辑详情直连统一入口：按 source 分派两平台实现。
 * 返回 null=未知平台/空 id/平台空结果或异常（调用方回落服务端）。
 * 空 source 服务端按 wy 处理（:3094 `or ['wy']` 链），客户端同语义。
 */
internal suspend fun fetchAlbumDetailDirect(source: String, id: String): Playlist? {
    val aid = id.trim()
    if (aid.isEmpty()) return null
    return when (source.trim().lowercase()) {
        "", "wy", "netease" -> wyAlbumDetail(aid)
        "tx", "qq" -> txAlbumDetail(aid)
        else -> fetchAdditionalAlbumDetailDirect(source.trim().lowercase(), aid)
    }
}

private suspend fun wyAlbumDetail(aid: String): Playlist? {
    val detail = parseWyAlbumDetail(
        platformHttpGet(WY_ALBUM_URL + URLEncoder.encode(aid, "UTF-8"), WY_ALBUM_REFERER, ALBUM_TIMEOUT_MS),
    )
    if (detail.songs.isEmpty()) return null
    return Playlist(
        id = aid,
        name = detail.name.ifBlank { "专辑" },
        cover = detail.cover,
        songs = detail.songs.toMutableList(),
        source = "wy",
        count = detail.songs.size,
        remoteId = aid,
    )
}

private suspend fun txAlbumDetail(aid: String): Playlist? {
    val detail = parseTxAlbumDetail(
        platformHttpGet(
            "$TX_ALBUM_URL?albummid=${URLEncoder.encode(aid, "UTF-8")}&format=json",
            TX_ALBUM_REFERER,
            ALBUM_TIMEOUT_MS,
        ),
        aid,
    )
    if (detail.songs.isEmpty()) return null
    return Playlist(
        id = aid,
        name = detail.name.ifBlank { "专辑" },
        cover = detail.cover,
        songs = detail.songs.toMutableList(),
        source = "tx",
        count = detail.songs.size,
        remoteId = aid,
    )
}

internal data class WyAlbumDetail(val name: String, val cover: String, val songs: List<Song>)

/** 纯解析：/api/v1/album 原始 JSON → 专辑元信息 + 曲目（netease_album_detail :2396-2419 1:1）。 */
internal fun parseWyAlbumDetail(raw: String): WyAlbumDetail {
    if (raw.isBlank()) return WyAlbumDetail("", "", emptyList())
    return try {
        val j = JSONObject(raw)
        val album = j.optJSONObject("album") ?: JSONObject()
        val arr = j.optJSONArray("songs")
        val out = ArrayList<Song>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val sid = s.optString("id")
                if (sid.isEmpty()) continue
                val ar = s.optJSONArray("ar")?.takeIf { it.length() > 0 } ?: s.optJSONArray("artists")
                val singer = if (ar != null) {
                    (0 until ar.length()).mapNotNull { idx ->
                        when (val a = ar.opt(idx)) {
                            is JSONObject -> a.optString("name")
                            null, JSONObject.NULL -> null
                            else -> a.toString()
                        }
                    }.joinToString(" / ")
                } else ""
                val al = s.optJSONObject("al") ?: album
                out.add(
                    Song(
                        maxQuality = inferMaxQuality(s),
                        id = sid,
                        name = s.optString("name"),
                        singer = singer,
                        source = "wy",
                        cover = shrinkCover(firstStr(al, "picUrl").ifEmpty { album.optString("picUrl") }),
                        album = firstStr(al, "name").ifEmpty { album.optString("name") },
                        albumId = firstStr(al, "id").ifEmpty { firstStr(album, "id") },
                        duration = s.optLong("dt"),
                    ),
                )
            }
        }
        WyAlbumDetail(
            name = album.optString("name"),
            cover = shrinkCover(album.optString("picUrl")),
            songs = out,
        )
    } catch (_: Exception) {
        WyAlbumDetail("", "", emptyList())
    }
}

internal data class TxAlbumDetail(val name: String, val cover: String, val songs: List<Song>)

/** 纯解析：fcg_v8_album_info JSON → 专辑元信息 + 曲目（qq_album_detail :2366-2379 1:1）。 */
internal fun parseTxAlbumDetail(raw: String, albumMid: String): TxAlbumDetail {
    if (raw.isBlank()) return TxAlbumDetail("", "", emptyList())
    return try {
        val json = if (raw.trimStart().startsWith("{")) raw else TX_JSONP_BODY.find(raw)?.value
            ?: return TxAlbumDetail("", "", emptyList())
        val d = JSONObject(json)
        val data = d.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list")
        val out = ArrayList<Song>(list?.length() ?: 0)
        if (list != null) {
            for (i in 0 until minOf(list.length(), TX_ALBUM_LIMIT)) {
                val item = list.optJSONObject(i) ?: continue
                val mid = firstStr(item, "songmid", "songid")
                if (mid.isEmpty()) continue
                val mediaMid = firstStr(item, "strMediaMid", "media_mid")
                val singers = item.optJSONArray("singer")
                val singer = if (singers != null) {
                    (0 until singers.length()).mapNotNull { idx -> singers.optJSONObject(idx) }
                        .joinToString(" / ") { cleanSpace(it.optString("name")) }
                } else ""
                out.add(
                    Song(
                        maxQuality = inferMaxQuality(item),
                        id = mid,
                        name = cleanSpace(item.optString("songname")),
                        singer = singer,
                        source = "tx",
                        cover = shrinkCover(TX_ALBUM_COVER_PREFIX + firstStr(item, "albummid").ifEmpty { albumMid } + ".jpg"),
                        album = cleanSpace(firstStr(item, "albumname").ifEmpty { data.optString("name") }),
                        albumId = firstStr(item, "albummid").ifEmpty { albumMid },
                        duration = secondsToDurationMs(item.optLong("interval")),
                        mediaMid = mediaMid,
                    ),
                )
            }
        }
        TxAlbumDetail(
            name = cleanSpace(data.optString("name")),
            cover = TX_ALBUM_COVER_PREFIX + albumMid + ".jpg",
            songs = out,
        )
    } catch (_: Exception) {
        TxAlbumDetail("", "", emptyList())
    }
}
