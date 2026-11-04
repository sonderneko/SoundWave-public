package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.decodePlaylistMetadata
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import java.net.URLEncoder
import java.util.concurrent.CancellationException

/**
 * 歌单搜索客户端直连（Local-first 批13，批11 Phase2-① 最大缺口）。
 * 逐行移植服务端 /music/playlist?action=search（/opt/soundwave/music_server.py:3367）分派的
 * 五个平台歌单搜索函数与处理器层 add() 归一（:3379-3392）：
 * - wy netease_search_playlist（:2056）：分类广场 + 关键词搜索双段，关键词结果置前去重，
 *   合并结果不足 lim*0.6 时补查一次关键词端点
 * - tx QQ 歌单独立 client_music_search_songlist（client_search_cp t=2 已失效），匿名请求实测可用
 * - kg kugou_search_playlist（:2159）：mobilecdnbj search/special（明文 http，HttpPolicy 白名单域）
 * - kw kuwo_search_playlist（:2180）：search.kuwo.cn r.s ft=playlist（服务端 http://，客户端沿用
 *   KwDirectSearch 先例改 https —— HttpPolicy 明文白名单仅豁免 mobilecdn 双域）
 * - mg migu_search_playlist（:2201）：search_all.do searchSwitch={song:0,songlist:1}
 * - 分页：网易云用 offset，QQ/酷我用零基页号，酷狗/咪咕用一基页号；每页 limit 保持有界
 * - limit 闸门（:3373）：max(50, min(limit, 200))；空关键词回退「推荐」（同 ApiClient.searchPlaylists）
 * - add() 归一（normalizePlaylists 1:1）：名字尾数字清洗 re.sub(r'\s*\d+$') + strip、空则回原名、
 *   (id,src) 去重、空 id 丢弃；count/creator 归一对本批解析输出为恒等（解析器已按平台字段映射）
 * - cookie 策略：五平台函数服务端均不带 Cookie（匿名可搜，qq_search_playlist 无 cookie 入参），
 *   客户端一律匿名直连、零 cookie 注入（P0-1 红线无涉及）
 * - 与歌曲搜索 direct 目录（各平台文件）差异：歌单保持上游顺序、无 scoreSong 重排；封面统一 shrink_cover(1080)
 *   （tx 除外：服务端对 cover.imgurl 原样保留）；单平台路径与处理器 add(单平台函数) 语义一致
 * - 失败语义：任一环节异常/空结果返回空列表（服务端 except-pass），由 PlatformSourceAdapter
 *   回落 ApiClient.searchPlaylists 服务端路径，行为零回归
 */

/** 服务端 qq_search_playlist 的 JSONP 剥壳（music_server.py:2143，贪婪首 { 至尾 }）。 */

private const val WY_SQUARE_URL = "https://music.163.com/api/playlist/list"
private const val WY_CATALOG_URL = "https://music.163.com/api/playlist/catalogue"
private const val WY_KEYWORD_URL = "https://music.163.com/api/search/get"
private const val WY_REFERER = "https://music.163.com/"
// 批24a：QQ 歌单搜索独立接口（client_search_cp t=2 已失效，洛雪口径 client_music_search_songlist）。
private const val TX_PLAYLIST_URL = "https://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist"
private const val TX_REFERER = "https://y.qq.com/"

internal data class TxPlaylistSearchResult(val playlists: List<Playlist>, val available: Boolean)
private const val TX_TIMEOUT_MS = 4000L
private const val KG_SPECIAL_URL = "http://mobilecdnbj.kugou.com/api/v3/search/special"
private const val KG_REFERER = "https://m.kugou.com/"
private const val KW_PLAYLIST_URL = "https://search.kuwo.cn/r.s"
private const val KW_REFERER = "http://www.kuwo.cn/"
private const val MG_SEARCH_URL = "https://app.c.nf.migu.cn/MIGUM3.0/v1.0/content/search_all.do"
private const val MG_REFERER = "https://music.migu.cn/"
private const val MG_TIMEOUT_MS = 4000L

/** 服务端 urllib.parse.quote('{song:0,songlist:1}') 的结果。 */
private const val MG_PLAYLIST_SWITCH = "%7Bsong%3A0%2Csonglist%3A1%7D"

/** Bounded page coordinates shared by each platform's anonymous playlist endpoint. */
internal data class PlaylistSearchPage(val page: Int, val limit: Int, val offset: Int)

/** Keep provider offsets finite even if a stale UI state or caller supplies an extreme page number. */
internal fun playlistSearchPage(page: Int, limit: Int): PlaylistSearchPage {
    val safePage = page.coerceIn(1, 10_000)
    val safeLimit = limit.coerceIn(1, 200)
    val offset = ((safePage - 1L) * safeLimit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return PlaylistSearchPage(safePage, safeLimit, offset)
}

private inline fun parsePlaylistResponseOutcome(
    raw: String,
    hasPayload: (JSONObject) -> Boolean,
    parse: () -> List<Playlist>
): PlaylistSearchOutcome {
    if (raw.isBlank()) return PlaylistSearchOutcome.Failure
    return try {
        val root = JSONObject(raw)
        if ((root.has("ok") && !root.optBoolean("ok")) ||
            (root.has("success") && !root.optBoolean("success")) || !hasPayload(root)) {
            PlaylistSearchOutcome.Failure
        } else {
            PlaylistSearchOutcome.Success(parse())
        }
    } catch (_: Exception) {
        PlaylistSearchOutcome.Failure
    }
}

internal fun parseWyPlaylistSquareOutcome(raw: String): PlaylistSearchOutcome =
    parsePlaylistResponseOutcome(raw, { root ->
        (!root.has("code") || root.optInt("code", -1) == 200) && root.optJSONArray("playlists") != null
    }) { parseWyPlaylistSquare(raw) }

internal fun parseWyPlaylistKeywordOutcome(raw: String): PlaylistSearchOutcome =
    parsePlaylistResponseOutcome(raw, { root ->
        (!root.has("code") || root.optInt("code", -1) == 200) &&
            root.optJSONObject("result")?.optJSONArray("playlists") != null
    }) { parseWyPlaylistKeyword(raw) }

internal fun parseKgPlaylistSearchOutcome(raw: String): PlaylistSearchOutcome =
    parsePlaylistResponseOutcome(raw, { it.optJSONObject("data")?.optJSONArray("info") != null }) {
        parseKgPlaylistSearch(raw)
    }

internal fun parseKwPlaylistSearchOutcome(raw: String): PlaylistSearchOutcome =
    parsePlaylistResponseOutcome(raw, { it.optJSONArray("abslist") != null }) { parseKwPlaylistSearch(raw) }

internal fun parseMgPlaylistSearchOutcome(raw: String): PlaylistSearchOutcome =
    parsePlaylistResponseOutcome(raw, { it.optJSONObject("songListResultData")?.optJSONArray("result") != null }) {
        parseMgPlaylistSearch(raw)
    }

private suspend fun requestPlaylistOutcome(request: suspend () -> PlaylistSearchOutcome): PlaylistSearchOutcome = try {
    request()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    PlaylistSearchOutcome.Failure
}

/** Dispatch one anonymous playlist-search page; unknown platforms return empty for the adapter's VPS fallback. */
internal suspend fun searchPlaylistDirect(source: String, keyword: String, limit: Int, page: Int = 1): List<Playlist> =
    when (val outcome = searchPlaylistDirectOutcome(source, keyword, limit, page)) {
        is PlaylistSearchOutcome.Success -> outcome.playlists
        PlaylistSearchOutcome.Failure -> emptyList()
    }

/** Category-page direct route that preserves success-empty versus failed-provider status. */
internal suspend fun searchPlaylistDirectOutcome(
    source: String,
    keyword: String,
    limit: Int,
    page: Int = 1
): PlaylistSearchOutcome {
    val keywordOrFallback = keyword.ifBlank { "推荐" }
    val lim = maxOf(50, minOf(limit, 200))
    val request = playlistSearchPage(page, lim)
    val result = when (source.lowercase()) {
        "wy", "netease" -> searchWyPlaylistOutcome(keywordOrFallback, request.limit, request.page)
        "tx", "qq" -> searchTxPlaylistOutcome(keywordOrFallback, request.limit, request.page)
        "kg", "kugou" -> searchKgPlaylistOutcome(keywordOrFallback, request.limit, request.page)
        "kw", "kuwo" -> searchKwPlaylistOutcome(keywordOrFallback, request.limit, request.page)
        "mg", "migu" -> searchMgPlaylistOutcome(keywordOrFallback, request.limit, request.page)
        else -> PlaylistSearchOutcome.Failure
    }
    return when (result) {
        is PlaylistSearchOutcome.Success -> PlaylistSearchOutcome.Success(normalizePlaylists(result.playlists))
        PlaylistSearchOutcome.Failure -> PlaylistSearchOutcome.Failure
    }
}

/**
 * 处理器层 add()（music_server.py:3379-3392）的 1:1 移植：空 id 丢弃、名字尾数字清洗
 * （清洗后空串回原名，等价 python `or p.get('name')`）、(id,src) 去重。
 * count/creator 归一（:3386-3387）为恒等：解析器已按平台字段完成 int/creator 映射。
 */
internal fun normalizePlaylists(lists: List<Playlist>): List<Playlist> {
    val seen = HashSet<Pair<String, String>>()
    val out = ArrayList<Playlist>(lists.size)
    for (playlist in lists) {
        if (playlist.id.isEmpty()) continue
        if (!seen.add(playlist.id to playlist.source)) continue
        val cleanedName = PL_NAME_TRAILING_DIGITS.matcher(playlist.name).replaceAll("").trim().ifEmpty { playlist.name }
        out.add(playlist.copy(name = cleanedName))
    }
    return out
}

/**
 * wy 直连（netease_search_playlist，music_server.py:2056-2135）：
 * 1) 分类广场 api/playlist/list?cat=<quote>&order=hot&limit=<max(50,lim)>&offset=0；
 * 2) 关键词搜索 api/search/get?type=1000&s=<quote>&limit=<max(50,lim)>（分类接口对任意词返回
 *    热门歌单的根因修复：关键词结果永远执行并置前）；
 * 3) 合并结果 < lim*0.6 时补查一次关键词端点（seen 增量去重）。
 * 两端点均 Referer=https://music.163.com/、timeout 8s、无 Cookie（同歌曲搜索 wy 直连）。
 */
internal suspend fun searchWyPlaylist(keyword: String, limit: Int, page: Int = 1): List<Playlist> {
    return when (val result = searchWyPlaylistOutcome(keyword, limit, page)) {
        is PlaylistSearchOutcome.Success -> result.playlists
        PlaylistSearchOutcome.Failure -> emptyList()
    }
}

internal suspend fun searchWyPlaylistOutcome(keyword: String, limit: Int, page: Int = 1): PlaylistSearchOutcome {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val request = playlistSearchPage(page, maxOf(50, limit))
        val lim = request.limit
        val lists = ArrayList<Playlist>()
        var successfulResponse = false
        when (val square = requestPlaylistOutcome {
            parseWyPlaylistSquareOutcome(
                platformHttpGet("$WY_SQUARE_URL?cat=$text&order=hot&limit=$lim&offset=${request.offset}", WY_REFERER)
            )
        }) {
            is PlaylistSearchOutcome.Success -> {
                successfulResponse = true
                lists.addAll(square.playlists)
            }
            PlaylistSearchOutcome.Failure -> Unit
        }
        when (val keywordResult = requestPlaylistOutcome {
            parseWyPlaylistKeywordOutcome(
                platformHttpGet("$WY_KEYWORD_URL?type=1000&s=$text&limit=$lim&offset=${request.offset}", WY_REFERER)
            )
        }) {
            is PlaylistSearchOutcome.Success -> {
                successfulResponse = true
                val keywordHits = keywordResult.playlists
                if (keywordHits.isNotEmpty()) {
                // 服务端：关键词结果优先，分类结果按 id 去重追加（:2089-2093）
                    val keywordIds = keywordHits.mapTo(HashSet()) { it.id }
                    val merged = ArrayList<Playlist>(keywordHits.size + lists.size)
                    merged.addAll(keywordHits)
                    for (playlist in lists) if (playlist.id !in keywordIds) merged.add(playlist)
                    lists.clear()
                    lists.addAll(merged)
                }
            }
            PlaylistSearchOutcome.Failure -> Unit
        }
        if (lists.size < lim * 0.6) {
            when (val refill = requestPlaylistOutcome {
                parseWyPlaylistKeywordOutcome(
                    platformHttpGet("$WY_KEYWORD_URL?type=1000&s=$text&limit=$lim&offset=${request.offset}", WY_REFERER)
                )
            }) {
                is PlaylistSearchOutcome.Success -> {
                    successfulResponse = true
                    val seen = lists.mapTo(HashSet()) { it.id }
                    for (playlist in refill.playlists) {
                        if (seen.add(playlist.id)) lists.add(playlist)
                    }
                }
                PlaylistSearchOutcome.Failure -> Unit
            }
        }
        if (successfulResponse) PlaylistSearchOutcome.Success(lists) else PlaylistSearchOutcome.Failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaylistSearchOutcome.Failure
    }
}

/** Fetch one NetEase native category square without mixing keyword-search results. */
internal suspend fun fetchWyPlaylistCategoryDirect(category: String, limit: Int, page: Int = 1): List<Playlist> {
    val value = category.trim()
    if (value.isEmpty()) return emptyList()
    return runCatching {
        val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val request = playlistSearchPage(page, limit)
        normalizePlaylists(
            parseWyPlaylistSquare(
                platformHttpGet("$WY_SQUARE_URL?cat=$encoded&order=hot&limit=${request.limit}&offset=${request.offset}", WY_REFERER)
            )
        )
    }.getOrDefault(emptyList())
}

/** Fetch the server-defined NetEase taxonomy from its live catalogue endpoint. */
internal suspend fun fetchWyPlaylistCategoryGroupsDirect(): List<Pair<String, List<String>>> {
    val raw = runCatching { platformHttpGet(WY_CATALOG_URL, WY_REFERER) }.getOrDefault("")
    return parseWyPlaylistCategoryGroups(raw)
}

/**
 * tx 直连：QQ 专用 client_music_search_songlist 关键词接口（与歌曲 client_search_cp 分离），
 * Referer=https://y.qq.com/、timeout 4s、无 Cookie；仅返回上游真实列表。
 */
internal suspend fun searchTxPlaylist(keyword: String, limit: Int, page: Int = 1): List<Playlist> =
    searchTxPlaylistResult(keyword, limit, page).playlists

internal suspend fun searchTxPlaylistResult(keyword: String, limit: Int, page: Int = 1): TxPlaylistSearchResult {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val request = playlistSearchPage(page, limit)
        val url = "$TX_PLAYLIST_URL?page_no=${request.page - 1}&num_per_page=${request.limit}&format=json&query=$text&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8"
        parseTxPlaylistSearchStatus(platformHttpGet(url, TX_REFERER, TX_TIMEOUT_MS))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TxPlaylistSearchResult(emptyList(), available = false)
    }
}

private suspend fun searchTxPlaylistOutcome(keyword: String, limit: Int, page: Int): PlaylistSearchOutcome =
    searchTxPlaylistResult(keyword, limit, page).let { result ->
        if (result.available) PlaylistSearchOutcome.Success(result.playlists) else PlaylistSearchOutcome.Failure
    }

/**
 * kg 直连（kugou_search_playlist，music_server.py:2159-2178）：mobilecdnbj search/special，
 * Referer=https://m.kugou.com/、timeout 8s、无 Cookie；明文 http 属服务端既有行为且
 * mobilecdnbj.kugou.com 已在 HttpPolicy 白名单。
 */
internal suspend fun searchKgPlaylist(keyword: String, limit: Int, page: Int = 1): List<Playlist> {
    return when (val result = searchKgPlaylistOutcome(keyword, limit, page)) {
        is PlaylistSearchOutcome.Success -> result.playlists
        PlaylistSearchOutcome.Failure -> emptyList()
    }
}

private suspend fun searchKgPlaylistOutcome(keyword: String, limit: Int, page: Int): PlaylistSearchOutcome {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val request = playlistSearchPage(page, limit)
        val url = "$KG_SPECIAL_URL?keyword=$text&page=${request.page}&pagesize=${request.limit}"
        requestPlaylistOutcome { parseKgPlaylistSearchOutcome(platformHttpGet(url, KG_REFERER)) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaylistSearchOutcome.Failure
    }
}

/**
 * kw 直连（kuwo_search_playlist，music_server.py:2180-2199）：r.s ft=playlist 全参数移植
 * （含 uid=794762570），Referer=http://www.kuwo.cn/、timeout 8s、无 Cookie；URL 方案 https 见类头。
 */
internal suspend fun searchKwPlaylist(keyword: String, limit: Int, page: Int = 1): List<Playlist> {
    return when (val result = searchKwPlaylistOutcome(keyword, limit, page)) {
        is PlaylistSearchOutcome.Success -> result.playlists
        PlaylistSearchOutcome.Failure -> emptyList()
    }
}

private suspend fun searchKwPlaylistOutcome(keyword: String, limit: Int, page: Int): PlaylistSearchOutcome {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val request = playlistSearchPage(page, limit)
        val url = "$KW_PLAYLIST_URL?client=kt&all=$text&pn=${request.page - 1}&rn=${request.limit}&uid=794762570&ver=kwplayer_ar_9.2.2.1" +
            "&vipver=1&show_copyright_off=1&newver=1&ft=playlist&cluster=0&strategy=2012&encoding=utf8" +
            "&rformat=json&vermerge=1&mobi=1"
        requestPlaylistOutcome { parseKwPlaylistSearchOutcome(platformHttpGet(url, KW_REFERER)) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaylistSearchOutcome.Failure
    }
}

/**
 * mg 直连（migu_search_playlist，music_server.py:2201-2219）：search_all.do 歌单开关
 * searchSwitch={song:0,songlist:1}，Referer=https://music.migu.cn/、timeout 4s（服务端原值）、无 Cookie。
 */
internal suspend fun searchMgPlaylist(keyword: String, limit: Int, page: Int = 1): List<Playlist> {
    return when (val result = searchMgPlaylistOutcome(keyword, limit, page)) {
        is PlaylistSearchOutcome.Success -> result.playlists
        PlaylistSearchOutcome.Failure -> emptyList()
    }
}

private suspend fun searchMgPlaylistOutcome(keyword: String, limit: Int, page: Int): PlaylistSearchOutcome {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val request = playlistSearchPage(page, limit)
        val url = "$MG_SEARCH_URL?text=$text&pageNo=${request.page}&pageSize=${request.limit}&searchSwitch=$MG_PLAYLIST_SWITCH"
        requestPlaylistOutcome { parseMgPlaylistSearchOutcome(platformHttpGet(url, MG_REFERER, MG_TIMEOUT_MS)) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaylistSearchOutcome.Failure
    }
}

/** wy 单条 → Playlist（服务端两段共用的字段映射：trackCount/playCount/coverImgUrl/creator.nickname）。 */
private fun wyPlaylist(item: JSONObject): Playlist? {
    val id = item.optString("id")
    if (id.isEmpty()) return null
    val rawCover = item.optString("coverImgUrl")
    return Playlist(
        id = id,
        name = decodePlaylistMetadata(item.optString("name")),
        cover = if (rawCover.isEmpty()) "" else shrinkCover(rawCover, 300),
        songs = mutableListOf(),
        source = "wy",
        author = decodePlaylistMetadata(item.optJSONObject("creator")?.optString("nickname").orEmpty()),
        description = decodePlaylistMetadata(orChain(item, "description", "desc", "briefDesc")),
        count = item.optInt("trackCount"),
        playCount = item.optLong("playCount").takeIf { it != 0L } ?: item.optLong("playcount"),
    )
}

/** Parse `api/playlist/catalogue` into the provider's real category groups and category terms. */
internal fun parseWyPlaylistCategoryGroups(raw: String): List<Pair<String, List<String>>> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) return@runCatching emptyList()
        val categoryNames = root.optJSONObject("categories") ?: return@runCatching emptyList()
        val categories = root.optJSONArray("sub") ?: return@runCatching emptyList()
        val groups = linkedMapOf<String, LinkedHashSet<String>>()
        for (i in 0 until categories.length()) {
            val category = categories.optJSONObject(i) ?: continue
            val name = category.optString("name").trim()
            val groupName = categoryNames.optString(category.optInt("category", -1).toString()).trim()
            if (name.isNotEmpty() && groupName.isNotEmpty()) {
                groups.getOrPut(groupName) { linkedSetOf() }.add(name)
            }
        }
        groups.mapNotNull { (name, terms) -> terms.toList().takeIf { it.isNotEmpty() }?.let { name to it } }
    }.getOrDefault(emptyList())
}

/**
 * 纯解析函数（不触网，仅依赖 org.json）：分类广场 api/playlist/list 原始 JSON → Playlist 列表。
 * 服务端等价：playlists[] 全量（:2063-2073），无 id 过滤（空 id 由 normalizePlaylists/add() 丢弃）。
 */
internal fun parseWyPlaylistSquare(raw: String): List<Playlist> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("playlists") ?: return emptyList()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            wyPlaylist(item)?.let { out.add(it) }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数：关键词搜索 api/search/get?type=1000 原始 JSON → Playlist 列表（id 去重）。
 * 服务端等价：result.playlists[] 按 pid seen 去重（:2077-2088）；空 id 跳过。
 */
internal fun parseWyPlaylistKeyword(raw: String): List<Playlist> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONObject("result")?.optJSONArray("playlists") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty() || !seen.add(id)) continue
            wyPlaylist(item)?.let { out.add(it) }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** Pure parser for QQ's verified playlist search response; accepts its JSON and older JSONP envelope. */
internal fun parseTxPlaylistSearch(raw: String): List<Playlist> = parseTxPlaylistSearchStatus(raw).playlists

internal fun parseTxPlaylistSearchStatus(raw: String): TxPlaylistSearchResult {
    if (raw.isBlank()) return TxPlaylistSearchResult(emptyList(), available = false)
    return try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return TxPlaylistSearchResult(emptyList(), available = false)
        val data = JSONObject(raw.substring(start, end + 1)).optJSONObject("data")
            ?: return TxPlaylistSearchResult(emptyList(), available = false)
        val list = data.optJSONArray("list")
            ?: data.optJSONObject("playlist")?.optJSONArray("list")
            ?: return TxPlaylistSearchResult(emptyList(), available = false)
        val out = ArrayList<Playlist>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = orChain(item, "dissid", "id", "playlist_id")
            if (id.isEmpty()) continue
            val cover = orChain(item, "imgurl", "playlist_cover")
                .ifBlank { item.optJSONObject("cover")?.optString("imgurl").orEmpty() }
            out.add(
                Playlist(
                    id = id,
                    name = decodePlaylistMetadata(orChain(item, "dissname", "name", "title", "playlist_name")),
                    cover = cover,
                    songs = mutableListOf(),
                    source = "tx",
                    author = decodePlaylistMetadata(item.optJSONObject("creator")?.optString("name").orEmpty()),
                    description = decodePlaylistMetadata(orChain(item, "desc", "description", "introduction")),
                    count = orInt(item, "song_count").takeIf { it > 0 } ?: orInt(item, "total_song_count"),
                ),
            )
        }
        TxPlaylistSearchResult(out, available = true)
    } catch (_: Exception) {
        TxPlaylistSearchResult(emptyList(), available = false)
    }
}

/**
 * 纯解析函数：mobilecdnbj search/special 原始 JSON → Playlist 列表。
 * 服务端等价：data.info[] → specialname/specialid/songcount/imgurl→shrink_cover(1080)/nickname。
 */
internal fun parseKgPlaylistSearch(raw: String): List<Playlist> {
    if (raw.isBlank()) return emptyList()
    return try {
        val info = JSONObject(raw).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val out = ArrayList<Playlist>(info.length())
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val id = orChain(item, "specialid")
            if (id.isEmpty()) continue
            out.add(
                Playlist(
                    id = id,
                    name = cleanSpace(decodePlaylistMetadata(item.optString("specialname"))),
                    cover = shrinkCover(item.optString("imgurl"), 300),
                    songs = mutableListOf(),
                    source = "kg",
                    author = decodePlaylistMetadata(item.optString("nickname")),
                    description = decodePlaylistMetadata(orChain(item, "description", "intro", "desc")),
                    count = orInt(item, "songcount"),
                        playCount = item.optLong("playcount", 0L),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数：kuwo r.s ft=playlist 原始 JSON → Playlist 列表。
 * 同一响应 pic 实测约 240、hts_pic 约 150，优先用 pic；缺失才回退 hts_pic，保留上游实际 URL 尺寸。
 */
internal fun parseKwPlaylistSearch(raw: String): List<Playlist> {
    if (raw.isBlank()) return emptyList()
    return try {
        val abslist = JSONObject(raw).optJSONArray("abslist") ?: return emptyList()
        val out = ArrayList<Playlist>(abslist.length())
        for (i in 0 until abslist.length()) {
            val item = abslist.optJSONObject(i) ?: continue
            val id = orChain(item, "playlistid", "DC_TARGETID")
            if (id.isEmpty()) continue
            out.add(
                Playlist(
                    id = id,
                    name = decodePlaylistMetadata(item.optString("name")),
                    cover = shrinkCover(orChain(item, "pic", "hts_pic"), 300),
                    songs = mutableListOf(),
                    source = "kw",
                    author = decodePlaylistMetadata(item.optString("nickname")),
                    description = decodePlaylistMetadata(orChain(item, "description", "desc", "info")),
                    count = orInt(item, "songnum"),
                        playCount = item.optLong("playcnt", 0L),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数：migu search_all.do 歌单开关原始 JSON → Playlist 列表。
 * 服务端等价：songListResultData.result[] → name/id/int(musicNum or 0)/
 * musicListPicUrl→shrink_cover(1080)，creator 恒空（服务端未取 creator 字段）。
 */
internal fun parseMgPlaylistSearch(raw: String): List<Playlist> {
    if (raw.isBlank()) return emptyList()
    return try {
        val result = JSONObject(raw).optJSONObject("songListResultData")?.optJSONArray("result")
            ?: return emptyList()
        val out = ArrayList<Playlist>(result.length())
        for (i in 0 until result.length()) {
            val item = result.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            out.add(
                Playlist(
                    id = id,
                    name = decodePlaylistMetadata(item.optString("name")),
                    cover = shrinkCover(item.optString("musicListPicUrl"), 300),
                    songs = mutableListOf(),
                    source = "mg",
                    author = "",
                    description = decodePlaylistMetadata(orChain(item, "description", "desc")),
                    count = orInt(item, "musicNum"),
                        playCount = item.optLong("playNum", 0L),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 服务端 int(x or 0) 的宽容等价：字符串数字/整数直取，非数字记 0。 */
private fun orInt(obj: JSONObject, key: String): Int = obj.optString(key).toIntOrNull() ?: 0
