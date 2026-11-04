package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.decodePlaylistMetadata
import xyz.cncnlp.soundwave.explicitRankChange
import xyz.cncnlp.soundwave.mgImgOk
import xyz.cncnlp.soundwave.providerUpdateTime
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 五平台榜单客户端直连；具体 URL、解析字段、缓存键与错误边界见各平台函数。
 * - wy：网易云官方榜单目录与榜单详情相结合，详情 trackIds 用于歌曲身份，封面取真实曲目/官方歌单字段。
 * - kw：榜单目录优先请求官方 bangMenu，使用 musicList.bangId/sourceid（不是菜单记录 id）；只预取
 *   首页常用六榜，其余显示官方目录名称/封面并在打开时加载详情。目录不可用时仅回退经 live menu 核实的榜单。
 * - tx：目录和详情均来自 QQ 官方 toplist API；MV/有声类仅在详情含非零标准音频 song ID 时展示，避免空点播。
 * - kg：官方 mobilecdn rank/list 提供目录，详情按 rankid/volid 分页，歌曲 hash 来自真实榜单记录。
 * - mg：目录优先读取咪咕官网公开 rank-index，再用 querycontentbyId 按 rankId 取真实详情；
 *   官网目录外的风向/内地/港台栏目仅作为已实测可用补充，保留接口标题、曲数及最大 albumImgs 封面。
 * - 榜单目录与详情均为匿名公开请求；网易云仅携带固定 os=pc 协议标记，不含用户身份凭证。
 *   单榜失败不计入结果；调用方可在整个平台直连失败时按既有语义回落服务端。
 * - 歌曲 id 必须来自 provider 可播放曲目字段；榜卡主图只用榜一曲或官方榜图，不能把榜二/三封面挪作榜一。
 */

/** 服务端榜单目录镜像（1:1，music_server.py:494-571）。cover 仅 wy 官方合并新榜使用。 */
internal data class RankCatalogEntry(
    val id: String,
    val name: String,
    val cover: String = "",
    val bangId: String = "",
    val topid: String = "",
    val columnId: String = "",
    val keyword: String = "",
)

/** NETEASE_TOPS（music_server.py:494-537）。 */
internal val WY_STATIC_TOPS: List<RankCatalogEntry> = listOf(
    RankCatalogEntry("19723756", "飙升榜"),
    RankCatalogEntry("3779629", "新歌榜"),
    RankCatalogEntry("2884035", "原创榜"),
    RankCatalogEntry("3778678", "热歌榜"),
    RankCatalogEntry("991319590", "说唱榜"),
    RankCatalogEntry("71384707", "古典榜"),
    RankCatalogEntry("1978921795", "电音榜"),
    RankCatalogEntry("5453912201", "黑胶VIP爱听榜"),
    RankCatalogEntry("71385702", "ACG榜"),
    RankCatalogEntry("745956260", "韩语榜"),
    RankCatalogEntry("10520166", "国电榜"),
    RankCatalogEntry("180106", "UK排行榜周榜"),
    RankCatalogEntry("60198", "美国Billboard榜"),
    RankCatalogEntry("3812895", "Beatport全球电子舞曲榜"),
    RankCatalogEntry("21845217", "KTV唛榜"),
    RankCatalogEntry("60131", "日本Oricon榜"),
    RankCatalogEntry("2809513713", "欧美热歌榜"),
    RankCatalogEntry("2809577409", "欧美新歌榜"),
    RankCatalogEntry("27135204", "法国 NRJ Vos Hits 周榜"),
    RankCatalogEntry("3001835560", "ACG动画榜"),
    RankCatalogEntry("3001795926", "ACG游戏榜"),
    RankCatalogEntry("3001890046", "ACG VOCALOID榜"),
    RankCatalogEntry("3112516681", "中国新乡村音乐排行榜"),
    RankCatalogEntry("5059644681", "日语榜"),
    RankCatalogEntry("5059633707", "摇滚榜"),
    RankCatalogEntry("5059642708", "国风榜"),
    RankCatalogEntry("5338990334", "潜力爆款榜"),
    RankCatalogEntry("5059661515", "民谣榜"),
    RankCatalogEntry("6688069460", "听歌识曲榜"),
    RankCatalogEntry("6723173524", "网络热歌榜"),
    RankCatalogEntry("6732051320", "俄语榜"),
    RankCatalogEntry("6732014811", "越南语榜"),
    RankCatalogEntry("6886768100", "中文DJ榜"),
    RankCatalogEntry("6939992364", "俄罗斯top hit流行音乐榜"),
    RankCatalogEntry("7095271308", "泰语榜"),
    RankCatalogEntry("7356827205", "BEAT排行榜"),
    RankCatalogEntry("7325478166", "编辑推荐榜"),
    RankCatalogEntry("7603212484", "赏音榜"),
    RankCatalogEntry("7775163417", "黑胶VIP新歌榜"),
    RankCatalogEntry("7785123708", "黑胶VIP热歌榜"),
    RankCatalogEntry("7785066739", "黑胶VIP爱搜榜"),
)

/** 官方 bangMenu 不可用时的有限回退；ID/name 已按 live menu 核实，运行时优先动态完整目录。 */
internal val KW_BANGS: List<RankCatalogEntry> = listOf(
    RankCatalogEntry("kw_16", "酷我热歌榜", bangId = "16"),
    RankCatalogEntry("kw_17", "酷我新歌榜", bangId = "17"),
    RankCatalogEntry("kw_93", "酷我飙升榜", bangId = "93"),
    RankCatalogEntry("kw_104", "酷我华语榜", bangId = "104"),
    RankCatalogEntry("kw_22", "酷我欧美榜", bangId = "22"),
    RankCatalogEntry("kw_182", "酷我粤语榜", bangId = "182"),
    RankCatalogEntry("kw_184", "酷我韩语榜", bangId = "184"),
    RankCatalogEntry("kw_183", "酷我日语榜", bangId = "183"),
    RankCatalogEntry("kw_158", "短视频酷我排行榜", bangId = "158"),
    RankCatalogEntry("kw_145", "会员畅听榜", bangId = "145"),
    RankCatalogEntry("kw_12", "美国The Billboard", bangId = "12"),
    RankCatalogEntry("kw_64", "影视金曲榜", bangId = "64"),
    RankCatalogEntry("kw_26", "经典怀旧榜", bangId = "26"),
)

/** 旧版关键词榜缓存 ID 兼容读取；新的用户可见榜单目录只来自官方 rank/list。 */
internal val KG_RANKS: List<RankCatalogEntry> = listOf(
    RankCatalogEntry("kg_hot", "酷狗热歌榜", keyword = "酷狗热歌"),
    RankCatalogEntry("kg_new", "酷狗新歌榜", keyword = "新歌"),
    RankCatalogEntry("kg_douyin", "酷狗抖音热歌", keyword = "抖音热歌"),
)

/** QQ_RANKS（music_server.py:556-560）。 */
internal val TX_RANKS: List<RankCatalogEntry> = listOf(
    RankCatalogEntry("tx_hot", "QQ热歌榜", topid = "26"),
    RankCatalogEntry("tx_new", "QQ新歌榜", topid = "27"),
    RankCatalogEntry("tx_douyin", "QQ流行指数榜", topid = "62"),
)

/** Migu 官网目录失败时的已验证回退/补充；显示时优先使用 querycontentbyId 的 columnTitle。 */
internal val MG_RANKS: List<RankCatalogEntry> = listOf(
    RankCatalogEntry("mg_83048887", "全网热歌榜", columnId = "83048887"),
    RankCatalogEntry("mg_83049014", "抖音热歌榜", columnId = "83049014"),
    RankCatalogEntry("mg_83048867", "免费热歌榜", columnId = "83048867"),
    RankCatalogEntry("mg_83049056", "收藏人气榜", columnId = "83049056"),
    RankCatalogEntry("mg_76557745", "会员臻爱榜", columnId = "76557745"),
    RankCatalogEntry("mg_new", "咪咕新歌榜", columnId = "27553319"),
    RankCatalogEntry("mg_hot", "咪咕热歌榜", columnId = "27186466"),
    RankCatalogEntry("mg_original", "咪咕原创榜", columnId = "27553408"),
    RankCatalogEntry("mg_trend", "咪咕音乐风向榜", columnId = "75959118"),
    RankCatalogEntry("mg_mainland", "咪咕内地榜", columnId = "23189399"),
    RankCatalogEntry("mg_hktw", "咪咕港台榜", columnId = "23189800"),
    RankCatalogEntry("mg_guofeng", "咪咕国风热歌榜", columnId = "83176390"),
)

/** 官网目录之外仍经详情 API 实测有效的风向、内地、港台栏目；只补充，不覆盖官网元数据。 */
private val MG_SUPPLEMENT_RANK_IDS = setOf("mg_trend", "mg_mainland", "mg_hktw")
/** 风向/国风详情曾出现慢响应；动态 rankId 与静态 alias 均以 columnId 识别。 */
private val MG_SLOW_COLUMN_IDS = setOf("75959118", "83176390")

private const val WY_TOPLIST_DETAIL_URL = "https://music.163.com/api/toplist/detail"
private const val WY_PLAYLIST_DETAIL_URL = "https://music.163.com/api/playlist/detail"
private const val WY_SONG_DETAIL_URL = "https://music.163.com/api/song/detail/"
private const val WY_REFERER = "https://music.163.com/"
private const val WY_DETAIL_DEFAULT_LIMIT = 1000
private const val WY_BOARD_CONCURRENCY = 6
private const val WY_TOPLIST_TIMEOUT_MS = 15000L

private const val KW_BANG_URL = "https://wapi.kuwo.cn/api/www/bang/bang/musicList"
private const val KW_BANG_MENU_URL = "https://wapi.kuwo.cn/api/www/bang/bang/bangMenu"
private const val KW_REFERER = "https://www.kuwo.cn/"
private const val KW_PAGE_SIZE = 30
private const val KW_DETAIL_LIMIT = 100
private const val KW_META_PAGE_LIMIT = 8
private const val KW_BOARD_CONCURRENCY = 6

private const val TX_TOPLIST_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg"
private const val TX_TOPLIST_CATALOG_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg"
private const val TX_REFERER = "https://y.qq.com/"
private const val TX_COVER_PREFIX = "https://y.qq.com/music/photo_new/T002R800x800M000"
private const val TX_TIMEOUT_MS = 4000L
private const val TX_DETAIL_LIMIT = 80
private const val TX_META_LIMIT = 8

private const val KG_DETAIL_LIMIT = 80
private const val KG_RANK_API_BASE_URL = "http://mobilecdn.kugou.com/api/v3/rank"
private const val KG_RANK_REFERER = "https://www.kugou.com/"
private const val KG_RANK_TIMEOUT_MS = 8000L

internal data class KgRankCatalogRow(
    val id: String,
    val name: String,
    val rankType: Int,
    val cover: String,
    val count: Int,
    val currentVolumeId: String,
    val hasVolumes: Boolean,
)

private val kgRankCatalogById = ConcurrentHashMap<String, KgRankCatalogRow>()

private const val MG_RANK_INDEX_URL = "https://c.musicapp.migu.cn/pc/bmw/rank/rank-index/v1.0"
private const val MG_CATALOG_REFERER = "https://music.migu.cn/v5/"
private const val MG_CATALOG_TIMEOUT_MS = 12000L
private const val MG_BOARD_URL = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do"
private const val MG_REFERER = "https://app.c.nf.migu.cn/"
private const val MG_TIMEOUT_MS = 20000L
private const val MG_LIMIT = 100
/** 首页榜单卡只需封面与少量预览，详情仍按实际接口取全量曲目。 */
private const val MG_META_PREVIEW = 8
/** 普通榜快速超时；两个已验证慢榜单独使用20秒，避免空列表/错误封面。 */
private const val MG_META_TIMEOUT_MS = 12000L
private const val MG_SLOW_META_TIMEOUT_MS = 20000L
/** 官网动态榜单与少量已验证补充同批并发；并发不超过七路。 */
private const val MG_BOARD_CONCURRENCY = 7

/** 咪咕图像只选 API 同一对象中最大且完整的 imgSizeType URL；文件后缀不做推测性升级。 */

/** source 别名归一（与 /music/rank 的 src 判断一致，:3259-3264/:3270-3311）；未知返回 null。 */
internal fun normalizeRankSource(source: String): String? = when (source.trim().lowercase()) {
    "wy", "netease" -> "wy"
    "kw", "kuwo" -> "kw"
    "kg", "kugou" -> "kg"
    "tx", "qq" -> "tx"
    "mg", "migu" -> "mg"
    else -> null
}

/**
 * 榜单元数据直连统一入口：按 source 分派各平台目录+封面+前三首预览。
 * 未知平台（含 src 空=跨平台聚合）返回空列表，由调用方回落服务端。
 */
internal suspend fun fetchRankBoardsDirect(source: String): List<RankBoard> {
    val src = normalizeRankSource(source) ?: return emptyList()
    return try {
        when (src) {
            "wy" -> wyBoards()
            "kw" -> kwBoards()
            "kg" -> kgBoards()
            "tx" -> txBoards()
            else -> mgBoards()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 榜单详情直连统一入口：按 source 分派；空/失败返回空列表（调用方回落服务端）。
 * limit<=0 用服务端同位 limit（wy 1000/kw 100/tx 80/kg 80/mg 100）；page 保留签名但忽略
 * （服务端 /music/rank 详情不分页，同 PlaylistDirectSearch 的 page 先例）。
 */
internal suspend fun fetchRankDirect(source: String, rankId: String, page: Int = 1, limit: Int = 0): List<Song> {
    if (rankId.isBlank()) return emptyList()
    val src = normalizeRankSource(source) ?: return emptyList()
    return try {
        when (src) {
            "wy" -> wyPlaylistDetail(rankId, limit).songs
            "kw" -> kwDetail(rankId, limit).songs
            "tx" -> txDetail(rankId, limit)
            "kg" -> kgDetail(rankId, limit)
            else -> mgDetail(rankId, limit)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

// ---------------- wy ----------------

internal data class WyPlaylistDetail(
    val cover: String,
    val trackIds: List<String>,
    val songs: List<Song>,
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val count: Int = 0,
    val updatedAt: Long? = null,
)

/** wy 榜单元数据（netease_top_with_covers_wy_only，:645-692）：官方全量同步 + 6 路逐榜前三首。 */
private suspend fun wyBoards(): List<RankBoard> = coroutineScope {
    val tops = ArrayList(WY_STATIC_TOPS)
    val known = tops.mapTo(HashSet()) { it.id }
    val raw = platformHttpGet(WY_TOPLIST_DETAIL_URL, WY_REFERER, WY_TOPLIST_TIMEOUT_MS, includeWyAnonymousProtocolMarker = true)
    for (entry in parseWyToplistMerge(raw, known)) if (known.add(entry.id)) tops.add(entry)
    tops.chunked(WY_BOARD_CONCURRENCY).flatMap { chunk ->
        chunk.map { top ->
            async {
                val detail = runCatching { wyPlaylistDetail(top.id, 3) }.getOrDefault(WyPlaylistDetail("", emptyList(), emptyList()))
                val firstCover = detail.songs.firstOrNull()?.cover.orEmpty()
                val cover = if (firstCover.isNotEmpty()) firstCover else shrinkCover(detail.cover.ifEmpty { top.cover })
                RankBoard(name = top.name, source = "wy", songs = detail.songs, id = top.id, cover = cover, count = detail.songs.size, updatedAt = detail.updatedAt)
            }
        }
        .awaitAll()
    }
}

/** wy 榜单详情（netease_playlist_detail，:1995-2045）。 */
internal suspend fun wyPlaylistDetail(pid: String, limit: Int): WyPlaylistDetail {
    if (pid.isBlank()) return WyPlaylistDetail("", emptyList(), emptyList())
    val cap = if (limit > 0) limit else WY_DETAIL_DEFAULT_LIMIT
    var detail = parseWyPlaylistDetail(platformHttpGet("$WY_PLAYLIST_DETAIL_URL?id=$pid", WY_REFERER, includeWyAnonymousProtocolMarker = true), cap)
    if (detail.songs.isEmpty()) {
        val retry = parseWyPlaylistDetail(platformHttpGet("$WY_PLAYLIST_DETAIL_URL?id=$pid&n=$cap&s=0", WY_REFERER, includeWyAnonymousProtocolMarker = true), cap)
        if (retry.songs.isNotEmpty()) detail = retry
    }
    if (detail.trackIds.isNotEmpty() && detail.songs.size < minOf(detail.trackIds.size, cap)) {
        // trackIds 兜底：置换为 song/detail 分批(200/批)结果，保 trackIds 名次序（:2022-2040）
        val songs = ArrayList<Song>()
        for (chunk in detail.trackIds.take(cap).chunked(200)) {
            val body = URLEncoder.encode("[${chunk.joinToString(",")}]", "UTF-8")
            songs.addAll(parseWySongDetail(platformHttpGet("$WY_SONG_DETAIL_URL?ids=$body", WY_REFERER, includeWyAnonymousProtocolMarker = true)))
        }
        if (songs.isNotEmpty()) detail = detail.copy(songs = songs)
    }
    return detail
}

/** 纯解析：toplist/detail 原始 JSON → 官方目录里 id 不在 knownIds 的新榜（:651-667）。 */
internal fun parseWyToplistMerge(raw: String, knownIds: Set<String>): List<RankCatalogEntry> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("list") ?: root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<RankCatalogEntry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val id = item.optString("id")
            if (name.isEmpty() || id.isEmpty() || id in knownIds) continue
            out.add(RankCatalogEntry(id = id, name = name, cover = item.optString("coverImgUrl").ifEmpty { item.optString("cover") }))
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：playlist/detail 原始 JSON → 封面 + trackIds + 前 limit 首歌（:2004-2043）。 */
internal fun parseWyPlaylistDetail(raw: String, limit: Int): WyPlaylistDetail {
    if (raw.isBlank()) return WyPlaylistDetail("", emptyList(), emptyList())
    return try {
        val d = JSONObject(raw)
        val r = d.optJSONObject("result") ?: d.optJSONObject("playlist") ?: JSONObject()
        val cover = r.optString("coverImgUrl").ifEmpty { r.optString("picUrl") }
        val name = decodePlaylistMetadata(r.optString("name"))
        val author = decodePlaylistMetadata(r.optJSONObject("creator")?.optString("nickname").orEmpty())
        val description = decodePlaylistMetadata(r.optString("description"))
        val trackIds = r.optJSONArray("trackIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.optString("id")?.takeIf { it.isNotEmpty() } }
        } ?: emptyList()
        val tracks = r.optJSONArray("tracks")
        val songs = ArrayList<Song>()
        if (tracks != null) {
            val bound = minOf(limit, tracks.length())
            for (i in 0 until bound) {
                val t = tracks.optJSONObject(i) ?: continue
                wyTrackToSong(t)?.let { songs.add(it) }
            }
        }
        WyPlaylistDetail(
            cover = cover,
            trackIds = trackIds,
            songs = songs,
            name = name,
            author = author,
            description = description,
            count = r.optInt("trackCount", trackIds.size).coerceAtLeast(0),
            updatedAt = providerUpdateTime(r),
        )
    } catch (_: Exception) {
        WyPlaylistDetail("", emptyList(), emptyList())
    }
}

/** 纯解析：song/detail 原始 JSON → 歌曲列表（:2036-2039 同款字段映射）。 */
internal fun parseWySongDetail(raw: String): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("songs") ?: return emptyList()
        (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.let(::wyTrackToSong) }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 服务端 wy 曲目映射（:2036-2043）：artists/ar、album/al、picUrl 封面；id/name 过滤对齐 parseSongList。 */
private fun wyTrackToSong(t: JSONObject): Song? {
    val name = cleanSpace(t.optString("name"))
    val id = t.optString("id")
    if (name.isEmpty() || id.isEmpty()) return null
    val artists = t.optJSONArray("artists")?.takeIf { it.length() > 0 } ?: t.optJSONArray("ar")
    val singer = cleanSpace(
        artists?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.optString("name") }.joinToString(",")
        } ?: ""
    )
    val album = t.optJSONObject("album")?.takeIf { it.length() > 0 } ?: t.optJSONObject("al")?.takeIf { it.length() > 0 }
    return Song(
        id = id,
        name = name,
        singer = singer,
        source = "wy",
        cover = shrinkCover(album?.optString("picUrl").orEmpty()),
        album = if (album != null) cleanSpace(album.optString("name")) else "",
        // r657 第三轮第 22/23 项：wy 曲目自带 hr / sq / h 音质对象（为 null 表示该档不可用）与 publishTime（ms）。
        maxQuality = rankMaxQualityWy(t),
        publishTime = t.optLong("publishTime").takeIf { it > 0L } ?: (album?.optLong("publishTime") ?: 0L),
        rankChange = explicitRankChange(t),
    )
}

/** wy 曲目最高可用音质：hr → hires，sq → flac，h → 320k；否则看 privilege.maxBrLevel。 */
internal fun rankMaxQualityWy(t: JSONObject): String = when {
    t.optJSONObject("hr") != null -> "hires"
    t.optJSONObject("sq") != null -> "flac"
    t.optJSONObject("h") != null -> "320k"
    else -> when (t.optJSONObject("privilege")?.let { it.optString("maxBrLevel").ifEmpty { it.optString("maxbrlevel") } }.orEmpty().lowercase()) {
        "jymaster" -> "master"
        "hires" -> "hires"
        "lossless" -> "flac"
        "exhigh" -> "320k"
        else -> ""
    }
}

/** kw formats（如 "MP3H|ALFLAC|HIRFLAC"）→ 最高音质。 */
internal fun rankMaxQualityKw(formats: String): String {
    val f = formats.uppercase()
    return when {
        "HIRFLAC" in f || "HIRES" in f -> "hires"
        "ALFLAC" in f || "FLAC" in f -> "flac"
        "MP3H" in f || "320" in f -> "320k"
        else -> ""
    }
}

/** "yyyy-MM-dd[ HH:mm:ss]" / 秒 / 毫秒 → epoch ms；解析不了返回 0。 */
internal fun rankParseDate(raw: String): Long {
    val v = raw.trim()
    if (v.isEmpty() || v == "0") return 0L
    v.toLongOrNull()?.let { n -> return if (n in 1_000_000_000L..9_999_999_999L) n * 1000L else if (n > 9_999_999_999L) n else 0L }
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }.parse(v.take(10))?.time ?: 0L
    }.getOrDefault(0L)
}

// ---------------- kw ----------------

internal data class KwRankDetail(val name: String, val cover: String, val total: Int, val songs: List<Song>)

/** 官方 bangMenu 完整目录；仅预取常用/用户点名榜单的真实曲目，其他榜单点入时加载详情。 */
private suspend fun kwBoards(): List<RankBoard> {
    val liveCatalog = runCatching {
        parseKwRankCatalog(platformHttpGet("$KW_BANG_MENU_URL?httpsStatus=1", KW_REFERER, 10000L))
    }.getOrDefault(emptyList())
    val catalog = liveCatalog.ifEmpty { KW_BANGS }
    val previewIds = if (liveCatalog.isEmpty()) {
        catalog.mapTo(HashSet()) { it.bangId.ifBlank { it.id.removePrefix("kw_") } }
    } else {
        setOf("16", "93", "17", "104", "22", "158")
    }
    val previewCatalog = catalog.filter { it.bangId.ifBlank { it.id.removePrefix("kw_") } in previewIds }
    val previewsById = coroutineScope {
        previewCatalog.chunked(KW_BOARD_CONCURRENCY).flatMap { chunk ->
            chunk.map { bang ->
                async {
                    val bangId = bang.bangId.ifBlank { bang.id.removePrefix("kw_") }
                    bangId to runCatching { kwDetail(bangId, KW_META_PAGE_LIMIT) }
                        .getOrDefault(KwRankDetail("", "", 0, emptyList()))
                }
            }.awaitAll()
        }.toMap()
    }
    return coroutineScope {
        catalog.map { bang ->
            val bangId = bang.bangId.ifBlank { bang.id.removePrefix("kw_") }
            val detail = previewsById[bangId] ?: KwRankDetail(bang.name, bang.cover, 0, emptyList())
            val firstTrackCover = detail.songs.firstOrNull()?.cover?.takeIf { it.isNotBlank() }
            RankBoard(
                name = bang.name,
                source = "kw",
                songs = detail.songs,
                id = bang.id,
                // 不跨曲目借图：榜一曲真封面优先；缺失时用该榜单官方目录/详情图片。
                cover = firstTrackCover ?: detail.cover.ifBlank { bang.cover },
                count = detail.total.coerceAtLeast(detail.songs.size),
            )
        }
    }
}

/** Parse the live Kuwo official catalog (`data[] -> list[]`); sourceid is musicList.bangId. */
internal fun parseKwRankCatalog(raw: String): List<RankCatalogEntry> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) return emptyList()
        val groups = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<RankCatalogEntry>()
        val seen = HashSet<String>()
        for (groupIndex in 0 until groups.length()) {
            val list = groups.optJSONObject(groupIndex)?.optJSONArray("list") ?: continue
            for (itemIndex in 0 until list.length()) {
                val item = list.optJSONObject(itemIndex) ?: continue
                val bangId = item.optString("sourceid").trim()
                val name = cleanSpace(item.optString("name"))
                if (bangId.isBlank() || bangId.any { it !in '0'..'9' } || name.isBlank() || !seen.add(bangId)) continue
                out.add(
                    RankCatalogEntry(
                        id = "kw_$bangId",
                        name = name,
                        cover = shrinkCover(item.optString("pic")),
                        bangId = bangId,
                    ),
                )
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** kw 榜单详情（kuwo_bang_detail，:727-771）：每页 30 分页取满 cap，空页即停（:744-746）。 */
internal suspend fun kwDetail(rankId: String, limit: Int): KwRankDetail {
    val bangId = rankId.removePrefix("kw_")
    if (bangId.isEmpty() || bangId.any { it !in '0'..'9' }) return KwRankDetail("", "", 0, emptyList())
    val cap = if (limit > 0) limit else KW_DETAIL_LIMIT
    val pages = maxOf(1, (cap + KW_PAGE_SIZE - 1) / KW_PAGE_SIZE)
    // 批23c：多页并发拉取（wapi 单页 1.5-2s，串行 N 页拖垮 8s 总预算；并行后≈单页耗时），
    // 结果按页码顺序合并，空页即停语义保留。
    val results = coroutineScope {
        (1..pages).map { pn ->
            async {
                parseKwBangPage(
                    platformHttpGet(
                        "$KW_BANG_URL?bangId=$bangId&pn=$pn&rn=$KW_PAGE_SIZE&httpsStatus=1",
                        KW_REFERER,
                    ),
                )
            }
        }.awaitAll()
    }
    var name = ""
    var cover = ""
    var total = 0
    val songs = ArrayList<Song>()
    for (result in results) {
        if (songs.size >= cap) break
        if (name.isBlank()) { name = result.name; cover = result.cover; total = result.total }
        if (result.songs.isEmpty()) break
        for (song in result.songs) {
            if (songs.size >= cap) break
            songs.add(song)
        }
    }
    if (name.isBlank()) name = "酷我榜$bangId"
    return KwRankDetail(name, cover, total, songs)
}

/** 纯解析：bang/musicList 原始 JSON → 榜名/img/num + 歌曲页（:741-766；img/num 仅 pn=1 有效）。 */
internal fun parseKwBangPage(raw: String): KwRankDetail {
    if (raw.isBlank()) return KwRankDetail("", "", 0, emptyList())
    return try {
        val data = JSONObject(raw).optJSONObject("data") ?: return KwRankDetail("", "", 0, emptyList())
        val name = cleanSpace(data.optString("name").ifEmpty { data.optString("bang") })
        val cover = shrinkCover(data.optString("img").ifEmpty { data.optString("pic") })
        val total = data.optString("num").trim().toIntOrNull() ?: 0
        val musicList = data.optJSONArray("musicList")
        val songs = ArrayList<Song>()
        if (musicList != null) {
            for (i in 0 until musicList.length()) {
                val item = musicList.optJSONObject(i) ?: continue
                val songName = cleanSpace(item.optString("name"))
                val rid = kuwoRid(orChain(item, "rid", "musicrid", "id"))
                if (songName.isEmpty() || rid.isEmpty()) continue
                songs.add(
                    Song(
                        id = rid,
                        name = songName,
                        singer = cleanSpace(item.optString("artist").ifEmpty { item.optString("singer") }),
                        source = "kw",
                        cover = shrinkCover(item.optString("pic").ifEmpty { item.optString("albumpic") }),
                        album = cleanSpace(item.optString("album")),
                        // bang/musicList 条目带真实 albumid，此前未读：空封面条目够不到专辑封面回补资格。
                        albumId = item.optString("albumid").takeUnless { it == "0" }.orEmpty(),
                        duration = secondsToDurationMs(item.optString("duration").trim().toLongOrNull() ?: 0L),
                        maxQuality = rankMaxQualityKw(item.optString("formats")),
                        publishTime = rankParseDate(item.optString("releaseDate").ifEmpty { item.optString("releasetime") }),
                        platformNew = item.optString("isnew") == "1",
                        rankChange = explicitRankChange(item),
                    ),
                )
            }
        }
        KwRankDetail(name, cover, total, songs)
    } catch (_: Exception) {
        KwRankDetail("", "", 0, emptyList())
    }
}

// ---------------- tx ----------------

/** tx 榜单元数据：优先使用 QQ 真实 toplist 目录，动态获取当前平台所有可用榜单 ID/名称/封面。 */
private suspend fun txBoards(): List<RankBoard> {
    val rawCatalog = platformHttpGet(
        "$TX_TOPLIST_CATALOG_URL?g_tk=5381&uin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1&_=${System.currentTimeMillis()}",
        TX_REFERER,
        8000L,
    )
    val liveCatalog = parseTxToplistCatalog(rawCatalog)
    if (liveCatalog.isNotEmpty()) {
        // Keep known real IDs only as a fallback supplement; dynamic provider entries win name/cover conflicts.
        val catalog = (liveCatalog + TX_RANKS).distinctBy { entry ->
            entry.topid.ifBlank { entry.id.removePrefix("tx_") }
        }
        val audioOnlyCandidates = catalog.filter { entry ->
            val id = entry.topid.ifBlank { entry.id.removePrefix("tx_") }
            id == "201" || id == "75" || entry.name.contains("MV", ignoreCase = true) || entry.name.contains("有声")
        }
        val audioOnlyIds = audioOnlyCandidates.map { it.topid.ifBlank { it.id.removePrefix("tx_") } }.toSet()
        // QQ 目录会返回视频榜、无 standard Song ID 的有声榜；只有详情端确实有可播放曲目时才列出。
        val playableSpecialIds = coroutineScope {
            audioOnlyCandidates.map { entry ->
                async {
                    val id = entry.topid.ifBlank { entry.id.removePrefix("tx_") }
                    id to runCatching { txDetail(id, TX_META_LIMIT).isNotEmpty() }.getOrDefault(false)
                }
            }.awaitAll().filter { it.second }.map { it.first }.toSet()
        }
        return catalog.filter { entry ->
            val id = entry.topid.ifBlank { entry.id.removePrefix("tx_") }
            id !in audioOnlyIds || id in playableSpecialIds
        }.map { entry ->
            val topid = entry.topid.ifBlank { entry.id }
            RankBoard(
                name = entry.name,
                source = "tx",
                songs = emptyList(),
                id = topid,
                cover = entry.cover,
                // The directory embeds only a three-song preview; do not misreport it as total chart size.
                count = 0,
            )
        }
    }

    // Directory failure preserves the previous three verified charts and their real preview/detail path.
    return TX_RANKS.map { top ->
        val songs = runCatching { txDetail(top.id, TX_META_LIMIT) }.getOrDefault(emptyList())
        RankBoard(
            name = top.name,
            source = "tx",
            songs = songs,
            id = top.id,
            cover = top.cover.ifBlank { songs.firstOrNull()?.cover.orEmpty() },
            count = songs.size,
        )
    }
}

/** Pure parser for the provider's live `data.topList` directory response. */
internal fun parseTxToplistCatalog(raw: String): List<RankCatalogEntry> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("topList") ?: return emptyList()
        val out = ArrayList<RankCatalogEntry>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = orChain(item, "id", "topid", "topId")
            val name = cleanSpace(orChain(item, "topTitle", "title", "name"))
            if (id.isBlank() || !id.all { it in '0'..'9' } || name.isBlank() || !seen.add(id)) continue
            out.add(
                RankCatalogEntry(
                    id = id,
                    name = name,
                    cover = shrinkCover(orChain(item, "picUrl", "cover", "pic")),
                    topid = id,
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** tx 榜单详情（qq_toplist_detail，:1075-1097）：目录 id → topid；裸数字 topid 兼容（超集，容旧缓存）。 */
internal suspend fun txDetail(rankId: String, limit: Int): List<Song> {
    val topid = TX_RANKS.firstOrNull { it.id == rankId }?.topid?.ifEmpty { null } ?: rankId
    if (topid.isEmpty() || topid.any { it !in '0'..'9' }) return emptyList()
    val cap = if (limit > 0) limit else TX_DETAIL_LIMIT
    return parseTxToplistDetail(
        platformHttpGet(
            "$TX_TOPLIST_URL?topid=$topid&page=detail&type=top&tpl=3&format=json",
            TX_REFERER,
            TX_TIMEOUT_MS,
        ),
        cap,
    )
}

/** 纯解析：fcg_v8_toplist_cp 原始 JSON → 歌曲列表（:1082-1093；songmid/mid/songid 链、albummid 封面）。 */
internal fun parseTxToplistDetail(raw: String, limit: Int): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val songlist = JSONObject(raw).optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<Song>()
        val bound = minOf(limit, songlist.length())
        for (i in 0 until bound) {
            val data = songlist.optJSONObject(i)?.optJSONObject("data") ?: continue
            val name = cleanSpace(data.optString("songname"))
            val songId = orChain(data, "songmid", "mid", "songid")
            if (name.isEmpty() || songId.isEmpty() || songId == "0" || songId.equals("null", true)) continue
            val albumMid = data.optString("albummid")
            val mediaMid = data.optString("strMediaMid").ifEmpty { data.optString("media_mid") }
            out.add(
                Song(
                    id = songId,
                    name = name,
                    singer = cleanSpace(
                        data.optJSONArray("singer")?.let { arr ->
                            (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.optString("name") }.joinToString(",")
                        } ?: ""
                    ),
                    source = "tx",
                    cover = if (albumMid.isEmpty()) "" else shrinkCover("$TX_COVER_PREFIX$albumMid.jpg"),
                    album = cleanSpace(data.optString("albumname")),
                    albumId = data.optString("albumid").ifEmpty { albumMid },
                    mediaMid = mediaMid,
                    // r657 第三轮第 22/23 项：QQ 音乐 size* 字段 > 0 表示该档可用；pubtime 为秒。
                    maxQuality = when {
                        data.optLong("sizehires") > 0L || data.optLong("size_hires") > 0L -> "hires"
                        data.optLong("sizeflac") > 0L || data.optLong("size_flac") > 0L -> "flac"
                        data.optLong("size320") > 0L || data.optLong("size_320mp3") > 0L -> "320k"
                        else -> ""
                    },
                    publishTime = rankParseDate(data.optString("pubtime").ifEmpty { data.optString("time_public") }),
                    rankChange = explicitRankChange(data),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

// ---------------- kg ----------------

/** 酷狗真实榜单目录：rank/list 返回完整动态目录；不以搜索结果构造或冒充榜单。 */
private suspend fun kgBoards(): List<RankBoard> {
    val rows = fetchKgRankCatalog()
    return rows.map { row ->
        RankBoard(
            name = row.name,
            source = "kg",
            id = row.id,
            cover = row.cover,
            count = row.count,
        )
    }
}

private suspend fun fetchKgRankCatalog(): List<KgRankCatalogRow> {
    val raw = platformHttpGet(
        "$KG_RANK_API_BASE_URL/list?withsong=0",
        KG_RANK_REFERER,
        KG_RANK_TIMEOUT_MS,
    )
    val rows = parseKgRankCatalog(raw)
    if (rows.isNotEmpty()) {
        kgRankCatalogById.clear()
        rows.forEach { kgRankCatalogById[it.id] = it }
    }
    return rows
}

/** 酷狗 rank/vol 与 rank/song 以 HTML 注释包裹 JSON；移除传输包装后再做严格字段解析。 */
private fun kgResponseJson(raw: String): String {
    val startTag = "<!--KG_TAG_RES_START-->"
    val endTag = "<!--KG_TAG_RES_END-->"
    val start = raw.indexOf(startTag)
    val body = if (start >= 0) raw.substring(start + startTag.length) else raw
    val end = body.indexOf(endTag)
    return (if (end >= 0) body.substring(0, end) else body).trim()
}

/** 纯解析：酷狗 rank/list 原始 JSON → 官方可进入榜单目录，不采纳搜索词或静态伪造项。 */
internal fun parseKgRankCatalog(raw: String): List<KgRankCatalogRow> {
    if (raw.isBlank()) return emptyList()
    return try {
        val info = JSONObject(kgResponseJson(raw)).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<KgRankCatalogRow>(info.length())
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val id = orChain(item, "rankid")
            val name = orChain(item, "rankname").trim()
            val rankType = orChain(item, "ranktype").toIntOrNull() ?: 0
            if (id.isEmpty() || id.any { it !in '0'..'9' } || name.isEmpty() || rankType <= 0 || !seen.add(id)) continue
            val responseMeta = item.optJSONObject("extra")?.optJSONObject("resp")
            val count = responseMeta?.optInt("all_total", 0)?.coerceAtLeast(0) ?: 0
            val currentVolumeId = orChain(item, "rank_cid")
            out.add(
                KgRankCatalogRow(
                    id = id,
                    name = name,
                    rankType = rankType,
                    cover = shrinkCover(orChain(item, "imgurl", "img_cover", "img_9")),
                    count = count,
                    currentVolumeId = currentVolumeId.takeIf { value -> value.isNotEmpty() && value.all { it in '0'..'9' } }.orEmpty(),
                    hasVolumes = item.optInt("isvol", 0) == 1,
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 获取最近期号；rank/vol 原始顺序是年份降序、同年期号降序。 */
internal fun parseKgLatestVolumeId(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val info = JSONObject(kgResponseJson(raw)).optJSONObject("data")?.optJSONArray("info") ?: return ""
        for (i in 0 until info.length()) {
            val volumes = info.optJSONObject(i)?.optJSONArray("vols") ?: continue
            for (j in 0 until volumes.length()) {
                val id = orChain(volumes.optJSONObject(j) ?: continue, "volid")
                if (id.isNotEmpty() && id.all { it in '0'..'9' }) return id
            }
        }
        ""
    } catch (_: Exception) {
        ""
    }
}

/** 纯解析：酷狗 rank/song 当前榜期分页 → 使用官方 hash 构造可播放歌曲。 */
internal fun parseKgRankSongs(raw: String, limit: Int = KG_DETAIL_LIMIT): List<Song> {
    if (raw.isBlank() || limit <= 0) return emptyList()
    return try {
        val info = JSONObject(kgResponseJson(raw)).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val out = ArrayList<Song>(minOf(info.length(), limit))
        val seen = HashSet<String>()
        for (i in 0 until minOf(info.length(), limit)) {
            val item = info.optJSONObject(i) ?: continue
            val filename = orChain(item, "filename")
            val name = orChain(item, "songname", "name").ifEmpty { filename.substringAfter(" - ", filename) }.trim()
            val hash = orChain(item, "hash", "FileHash", "HQFileHash", "320hash", "sqhash").uppercase()
            if (name.isEmpty() || hash.isEmpty() || !seen.add(hash)) continue
            val artists = item.optJSONArray("authors")
            val singer = if (artists != null) {
                (0 until artists.length()).mapNotNull { index ->
                    artists.optJSONObject(index)?.let { orChain(it, "author_name", "name").trim().takeIf(String::isNotEmpty) }
                }.distinct().joinToString(",")
            } else ""
            val cover = orChain(item, "album_sizable_cover", "album_img", "img", "cover").ifEmpty {
                item.optJSONObject("trans_param")?.let { orChain(it, "union_cover", "img") }.orEmpty()
            }
            val duration = item.optLong("duration", 0L).takeIf { it > 0L } ?: item.optLong("duration_high", 0L)
            out.add(
                Song(
                    id = hash,
                    name = name,
                    singer = singer.ifEmpty { filename.substringBefore(" - ").takeIf { it != filename }.orEmpty() },
                    source = "kg",
                    cover = shrinkCover(cover),
                    album = orChain(item, "album_name", "albumName").trim(),
                    albumId = orChain(item, "album_id", "AlbumID"),
                    duration = secondsToDurationMs(duration),
                    hash = hash,
                    rankChange = explicitRankChange(item),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** kg 榜单详情：live rankid → 最新 volid → rank/song 分页，旧缓存的三条伪目录只保留兼容读取。 */
internal suspend fun kgDetail(rankId: String, limit: Int): List<Song> {
    val cap = if (limit > 0) limit else KG_DETAIL_LIMIT
    if (rankId.isEmpty() || rankId.any { it !in '0'..'9' }) {
        val legacyKeyword = KG_RANKS.firstOrNull { it.id == rankId }?.keyword ?: return emptyList()
        return searchKg(legacyKeyword, 1, cap)
    }
    val catalog = kgRankCatalogById[rankId] ?: fetchKgRankCatalog().firstOrNull { it.id == rankId } ?: return emptyList()
    val volumeRaw = platformHttpGet(
        "$KG_RANK_API_BASE_URL/vol?rankid=$rankId&ranktype=${catalog.rankType}&plat=0&with_res_tag=1",
        KG_RANK_REFERER,
        KG_RANK_TIMEOUT_MS,
    )
    val volumeId = parseKgLatestVolumeId(volumeRaw).ifEmpty {
        catalog.currentVolumeId.takeIf { catalog.hasVolumes }.orEmpty()
    }
    if (volumeId.isEmpty()) return emptyList()

    val pageSize = minOf(100, cap)
    val pageCount = (cap + pageSize - 1) / pageSize
    return coroutineScope {
        (1..pageCount).map { page ->
            async {
                val url = "$KG_RANK_API_BASE_URL/song?version=9108&ranktype=${catalog.rankType}" +
                    "&plat=0&pagesize=$pageSize&area_code=1&page=$page&volid=$volumeId" +
                    "&rankid=$rankId&with_res_tag=1"
                parseKgRankSongs(platformHttpGet(url, KG_RANK_REFERER, KG_RANK_TIMEOUT_MS), pageSize)
            }
        }.awaitAll().flatten().distinctBy { it.id }.take(cap)
    }
}

// ---------------- mg ----------------

internal data class MgRankDetailPayload(
    val title: String = "",
    val total: Int = 0,
    val cover: String = "",
    val songs: List<Song> = emptyList(),
)

/** 纯解析：官网 rank-index 分组 → 真实 rankId、官方标题和榜单封面。 */
internal fun parseMgRankCatalog(raw: String): List<RankCatalogEntry> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        val code = root.optString("code")
        if (code.isNotEmpty() && code != "000000") return emptyList()
        val groups = root.optJSONObject("data")?.optJSONArray("contents") ?: return emptyList()
        val out = ArrayList<RankCatalogEntry>()
        val seen = HashSet<String>()
        for (groupIndex in 0 until groups.length()) {
            val entries = groups.optJSONObject(groupIndex)?.optJSONArray("contents") ?: continue
            for (entryIndex in 0 until entries.length()) {
                val entry = entries.optJSONObject(entryIndex) ?: continue
                val columnId = orChain(entry, "rankId", "columnId").trim()
                val name = cleanSpace(orChain(entry, "rankName", "name"))
                if (columnId.isEmpty() || columnId.any { it !in '0'..'9' } || name.isEmpty() || !seen.add(columnId)) continue
                val stableId = MG_RANKS.firstOrNull { it.columnId == columnId }?.id ?: "mg_$columnId"
                out.add(
                    RankCatalogEntry(
                        id = stableId,
                        name = name,
                        cover = shrinkCover(orChain(entry, "imageUrl", "columnCover", "cover")),
                        columnId = columnId,
                    ),
                )
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 静态别名和官网动态 mg_<rankId> 均映射到详情 API 的真实数字 columnId。 */
internal fun miguColumnIdForRankId(rankId: String): String {
    val staticColumnId = MG_RANKS.firstOrNull { it.id == rankId }?.columnId
    if (!staticColumnId.isNullOrBlank()) return staticColumnId
    return if (rankId.startsWith("mg_")) rankId.removePrefix("mg_") else rankId
}

/** 目录优先官网动态数据，保留三项详情仍可用的官方栏目补充；仅返回真有歌曲的榜。 */
private suspend fun mgBoards(): List<RankBoard> = coroutineScope {
    val officialCatalog = runCatching {
        parseMgRankCatalog(platformHttpGet(MG_RANK_INDEX_URL, MG_CATALOG_REFERER, MG_CATALOG_TIMEOUT_MS))
    }.getOrDefault(emptyList())
    val catalog = (officialCatalog.ifEmpty { MG_RANKS } + MG_RANKS.filter { it.id in MG_SUPPLEMENT_RANK_IDS })
        .distinctBy { it.columnId.ifBlank { it.id } }
    catalog.chunked(MG_BOARD_CONCURRENCY).flatMap { chunk ->
        chunk.map { rank ->
            async {
                val timeout = if (rank.columnId in MG_SLOW_COLUMN_IDS) MG_SLOW_META_TIMEOUT_MS else MG_META_TIMEOUT_MS
                val detail = runCatching { mgRankDetail(rank.id, MG_META_PREVIEW, timeout) }
                    .getOrDefault(MgRankDetailPayload())
                rank to detail
            }
        }.awaitAll()
    }
        .filter { (_, detail) -> detail.songs.isNotEmpty() }
        .map { (rank, detail) ->
            val liveName = detail.title.ifBlank { rank.name.removePrefix("咪咕") }
            RankBoard(
                name = liveName,
                source = "mg",
                songs = detail.songs,
                id = rank.id,
                // Keep the official chart image separate from each song's own cover.
                cover = detail.cover.takeIf { it.isNotBlank() } ?: rank.cover,
                count = detail.total.coerceAtLeast(detail.songs.size),
            )
        }
}

/** columnId → querycontentbyId；详情用 needAll=1 拉满官方列表，首页预览用 needAll=0。 */
internal suspend fun mgRankDetail(rankId: String, limit: Int, timeoutMs: Long = MG_TIMEOUT_MS): MgRankDetailPayload {
    // 批16 复用（fetchHotDirect/fetchDiscoverDirect）：裸数字 columnId 直传，照 txDetail 裸 topid 先例。
    val columnId = miguColumnIdForRankId(rankId)
    if (columnId.isEmpty() || columnId.any { it !in '0'..'9' }) return MgRankDetailPayload()
    val cap = if (limit > 0) limit else MG_LIMIT
    val needAll = if (cap > MG_META_PREVIEW) 1 else 0
    return parseMgRankDetail(
        platformHttpGet(
            "$MG_BOARD_URL?columnId=$columnId&needAll=$needAll",
            MG_REFERER,
            timeoutMs,
        ),
        cap,
    )
}

internal suspend fun mgDetail(rankId: String, limit: Int, timeoutMs: Long = MG_TIMEOUT_MS): List<Song> =
    mgRankDetail(rankId, limit, timeoutMs).songs

/** 兼容调用入口：querycontentbyId 原始 JSON → 歌曲列表。 */
internal fun parseMgBoard(raw: String, limit: Int): List<Song> = parseMgRankDetail(raw, limit).songs

/** 保留 provider 榜单标题/曲数/封面，以及 contentId 优先的真实歌曲身份。 */
internal fun parseMgRankDetail(raw: String, limit: Int): MgRankDetailPayload {
    if (raw.isBlank()) return MgRankDetailPayload()
    return try {
        val d = JSONObject(raw)
        if (d.optString("code") != "000000") return MgRankDetailPayload()
        val columnInfo = d.optJSONObject("columnInfo") ?: return MgRankDetailPayload()
        val contents = columnInfo.optJSONArray("contents")
        val title = cleanSpace(columnInfo.optString("columnTitle"))
        val total = columnInfo.optInt("contentsCount", contents?.length() ?: 0)
        val boardCover = shrinkCover(orChain(columnInfo, "columnCover", "columnPic", "picUrl", "cover"))
        if (title.isEmpty() && (contents?.length() ?: 0) == 0) return MgRankDetailPayload()
        val out = ArrayList<Song>()
        if (contents != null) {
            val bound = minOf(limit, contents.length())
            for (i in 0 until bound) {
                val objInfo = contents.optJSONObject(i)?.optJSONObject("objectInfo") ?: continue
                val name = cleanSpace(orChain(objInfo, "songName", "name"))
                val songId = orChain(objInfo, "contentId", "songId")
                if (name.isEmpty() || songId.isEmpty()) continue
                if (COVER_BAD.containsMatchIn(name)) continue
                val mgCopyrightId = orChain(objInfo, "copyrightId", "copyright_id", "")
                out.add(
                    Song(
                        id = songId,
                        name = name,
                        singer = cleanSpace(objInfo.optString("singer")),
                        source = "mg",
                        cover = mgBoardCover(objInfo),
                        album = cleanSpace(objInfo.optString("album")),
                        albumId = orChain(objInfo, "albumId"),
                        miguSongId = orChain(objInfo, "songId"),
                        copyrightId = mgCopyrightId,
                        rankChange = explicitRankChange(objInfo),
                    ),
                )
            }
        }
        MgRankDetailPayload(title = title, total = total, cover = boardCover, songs = out)
    } catch (_: Exception) {
        MgRankDetailPayload()
    }
}

/** albumImgs 取最大 imgSizeType 档（先滤截断变体）；保留所选字段 URL，不推测更大文件后缀。 */
private fun mgBoardCover(objInfo: JSONObject): String {
    var cover = ""
    val imgs = objInfo.optJSONArray("albumImgs")
    if (imgs != null && imgs.length() > 0) {
        var best: JSONObject? = null
        var bestSize = -1
        for (i in 0 until imgs.length()) {
            val variant = imgs.optJSONObject(i) ?: continue
            // 批29-6 C：先滤掉截断变体（.web 缺尾 p / 无扩展名），对齐服务端 migu_rank——
            // 否则最大档恰是截断 URL 时 shrinkCover 归一回 ""，本可取到的次大档真封面被漏掉。
            val preview = variant.optString("img").ifEmpty { variant.optString("webpImg") }
            if (preview.isEmpty() || !mgImgOk(preview)) continue
            val size = variant.optString("imgSizeType").trimStart('0').ifEmpty { "0" }.toIntOrNull() ?: 0
            if (size > bestSize) {
                bestSize = size
                best = variant
            }
        }
        val pick = best
        if (pick != null) {
            val img = pick.optString("img")
            cover = if (img.isNotEmpty()) img else pick.optString("webpImg")
        }
    }
    // normalizeCoverUrl 只规范协议/截断值；不把 _02/_03 猜改为其他文件名。
    return shrinkCover(cover)
}
