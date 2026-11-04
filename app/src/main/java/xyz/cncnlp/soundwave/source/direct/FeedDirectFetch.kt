package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.Platforms
import xyz.cncnlp.soundwave.Song

/**
 * 首页三大内容块客户端直连（Local-first 批16，批11 Phase2-② 收尾：发现/热门/热词）。
 * 逐平台移植服务端 /music/discover（/opt/soundwave/music_server.py:3041-3087）、/music/hot
 * （:2945-2989）、/music/hotword（:2929-2943 + hot_search_words :1104-1171）；失败/空结果由
 * ApiClient.discover/hot/hotKeywords 回落原服务端路径（原实现改名 discoverServer/hotServer/
 * hotKeywordsServer 原样保留），调用方（HomeFeedController/MainActivity）零改动，首页零回归。
 *
 * - discover（:3041）：wy netease_discover_cards(:2290) 三段——数字专辑 vipmall/albumproduct/list、
 *   新碟首发 api/album/new、新歌速递 api/personalized/newsong（每段 max(4,min(limit,30))、独立
 *   except-pass :2300/:2324/:2344、按服务端顺序合并）；tx qq_discover_cards(:2266)——新歌首发
 *   fcg_v8_toplist_cp?topid=26&newsong=1（要求 songname+albummid，封面 T002R800x800M000{mid}.jpg
 *   不 shrink）；kg/kw 搜「新歌」max(limit,18)；mg migu_rank('27553319', max(limit,18))
 *   （mgDetail 裸 columnId 直传，照 txDetail 裸 topid 先例）。处理器归一（:3068-3080）由
 *   normalizeDiscoverCards 1:1：先截前 limit 再过滤无 id/name，stamp source，setdefault
 *   kind=new_song/title=新歌速递/songId=id（artist 已由解析器按 singer 填好）
 * - hot（:2945）：服务端 /music/hot 返回**歌曲列表**（payload.list 为 Song 形状），客户端首页
 *   「热门歌曲」区消费 List<Song>，故直连返回 List<Song>。all 聚合（:2955-2987）：wy 热歌榜歌单
 *   3778678(:1995,30) → kw 热歌榜 bang 17(:727,30) → [合计不足10首补 kw 搜「热歌」25] →
 *   kg 搜「热歌」18 → tx 榜26(:1075,18) → mg 榜27186466(:573,18)；(src,id,name) 去重、单源上限30
 *   （PER_SRC）、总截80。单平台仅取己源：wy 空态兜底 kw 搜「流行」30（:2979-2984）、kw <10 补搜
 *   「热歌」25（:2957-2961）、kg/mg 无兜底（服务端 :2986 明确禁止跨平台冒充）。source 归一同
 *   hotServer：非五平台一律 all
 * - hotword（:2929 → hot_search_words :1104-1171）：kw searchKey(:1112) → tx gethotkey(:1118，
 *   不足补 tx 搜「热歌」) → kg mobilecdn search/hot(:1129，count=max(lim,10)) → wy 热歌榜歌单歌名
 *   (:1135，公开热搜接口多变，复用 wyPlaylistDetail) → mg 搜「热歌」(:1142，max(lim,12))；词
 *   clean_space+全链去重、lim=max(1,min(limit,20))（:2934-2936）；最终回退（:1157-1175）：
 *   all→wy 歌单、kg/mg/tx/kw→各搜「热歌」
 * - 复用：RankDirectFetch 的 wyPlaylistDetail/kwDetail/txDetail/mgDetail（批15 改 internal）与
 *   各平台歌曲搜索直连 searchKg/searchKw/searchTx/searchMg（批2/3，source 已按平台打标；kg 搜索
 *   封面不 shrink 同服务端 kugou_search、kw 搜索封面原样同 kuwo_search、wy 卡片封面 shrink 1080）
 * - 账号策略：本批上游均为匿名公开接口；网易云榜单/歌单仅使用固定 os=pc 协议标记，不含用户会话。
 * - 失败语义：任一环节异常/空结果返回空（服务端 except-pass 同构），由 ApiClient 回落服务端路径
 */

private const val WY_DISCOVER_DIGITAL_URL = "https://music.163.com/api/vipmall/albumproduct/list"
private const val WY_DISCOVER_NEW_ALBUM_URL = "https://music.163.com/api/album/new"
private const val WY_DISCOVER_NEW_SONG_URL = "https://music.163.com/api/personalized/newsong"
private const val WY_REFERER = "https://music.163.com/"
private const val TX_DISCOVER_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=26&format=json&newsong=1&g_tk=5381"
private const val TX_REFERER = "https://y.qq.com/"
private const val KW_HOTWORD_URL = "https://wapi.kuwo.cn/api/www/search/searchKey?key="
private const val TX_HOTWORD_URL = "https://c.y.qq.com/splcloud/fcgi-bin/gethotkey.fcg"
// kg 热搜端点仅 http 可用（mobilecdn 域属 HttpPolicy 明文白名单，服务端 :1130 同为 http）。
private const val KG_HOTWORD_URL = "http://mobilecdn.kugou.com/api/v3/search/hot?format=json&plat=0&count="
private const val WY_HOT_PLAYLIST_ID = "3778678"
private const val KW_HOT_BANG_ID = "17"
private const val TX_HOT_TOPID = "26"
private const val MG_HOT_COLUMN_ID = "27186466"
private const val MG_DISCOVER_COLUMN_ID = "27553319"
private const val DISCOVER_KEYWORD = "新歌"
private const val DISCOVER_MIN_LIMIT = 18
private const val HOT_KEYWORD = "热歌"
private const val KW_POP_FALLBACK_KEYWORD = "流行"
private const val HOT_PER_SOURCE = 30
private const val HOT_ALL_CAP = 80
private const val HOT_KG_LIMIT = 18
private const val HOT_TX_LIMIT = 18
private const val HOT_MG_LIMIT = 18
private const val HOT_KW_FALLBACK_BELOW = 10
private const val KW_HOT_FALLBACK_LIMIT = 25
private const val KW_POP_FALLBACK_LIMIT = 30
private const val TX_DISCOVER_TIMEOUT_MS = 10000L
private const val WY_DISCOVER_TIMEOUT_MS = 10000L
private const val HOTWORD_TIMEOUT_MS = 8000L

/**
 * 发现位直连统一入口：按 source 分派各平台实现，再做处理器层归一（:3068-3080）。
 * 未知平台返回空列表（调用方回落服务端）；别名归一复用 normalizeRankSource
 * （wy/netease、tx/qq、kg/kugou、kw/kuwo、mg/migu，与服务端 :3048 别名表一致）。
 */
internal suspend fun fetchDiscoverDirect(source: String, limit: Int): List<DiscoverCard> {
    val src = normalizeRankSource(source) ?: return emptyList()
    return try {
        val cards = when (src) {
            "wy" -> discoverWy(limit)
            "tx" -> fetchDiscoverSectionsConcurrently(listOf(
                { parseTxDigitalAlbums(platformHttpGet("https://c.y.qq.com/v8/fcg-bin/musicmall.fcg?format=json&outCharset=utf-8&cmd=pc_index_new", TX_REFERER, TX_DISCOVER_TIMEOUT_MS), limit) },
                { parseTxNewAlbums(platformHttpGet(txNewAlbumCatalogUrl(limit), TX_REFERER, TX_DISCOVER_TIMEOUT_MS), limit) },
                { parseTxDiscoverCards(platformHttpGet(TX_DISCOVER_URL, TX_REFERER, TX_DISCOVER_TIMEOUT_MS), limit) }
            )).flatten()
            "kg" -> fetchDiscoverSectionsConcurrently(listOf(
                { parseKgDigitalAlbums(platformHttpGet("https://ep.kugou.com/v3/album_shop/get_album_list?page=1&limit=${limit.coerceIn(1, 30)}", "https://ep.kugou.com/", 10000L), limit) },
                { parseKgNewAlbums(platformHttpGet("https://www.kugou.com/yy/album/index/1-1-1.html", "https://www.kugou.com/", 10000L), limit) },
                { searchKg(DISCOVER_KEYWORD, 1, maxOf(limit, DISCOVER_MIN_LIMIT)).map { it.toDiscoverCard() } }
            )).flatten()
            // accept6-kwfix（用户 2026-11-04 预授权摘除）：kw 的新碟来源 disc/shelves
            // 货架实测长期只有 1 张特辑（2026-10-23 与 2026-11-04 两录皆为同一张，
            // pn≥2 皆空，无替代匿名端点），不是新碟目录——kw 不再参与新碟首发，
            // 只保留新歌速递段。证据见 WAVE-ACCEPT6-KWFIX 台账。
            "kw" -> fetchDiscoverSectionsConcurrently(listOf(
                { searchKw(DISCOVER_KEYWORD, 1, maxOf(limit, DISCOVER_MIN_LIMIT)).map { it.toDiscoverCard() } }
            )).flatten()
            else -> fetchDiscoverSectionsConcurrently(listOf(
                { parseMgDigitalAlbums(platformHttpGet("https://app.c.nf.migu.cn/column/column-info/h5/v2.0?columnId=28625302", "https://music.migu.cn/", 10000L), limit) },
                { parseMgNewAlbums(platformHttpGet("https://app.c.nf.migu.cn/MIGUM3.0/v1.0/template/get-new-cd-list-data?templateVersion=1&columnId=15279065&start=1&count=${limit.coerceIn(1, 30)}", "https://music.migu.cn/", 10000L), limit) },
                { mgDetail(MG_DISCOVER_COLUMN_ID, maxOf(limit, DISCOVER_MIN_LIMIT)).map { it.toDiscoverCard() } }
            )).flatten()
        }
        normalizeDiscoverCards(cards, src, limit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

/** The three independent discovery endpoints run concurrently, one in-flight request per section. */
internal suspend fun fetchDiscoverSectionsConcurrently(
    requests: List<suspend () -> List<DiscoverCard>>,
): List<List<DiscoverCard>> = coroutineScope {
    val sourceGate = Semaphore(3)
    requests.map { request ->
        async(Dispatchers.IO) {
            sourceGate.withPermit {
                try {
                    request()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }.awaitAll()
}

/**
 * wy 发现位（netease_discover_cards，:2290-2345）：数字专辑→新碟→新歌三段按序合并，
 * 每段独立 except-pass、limit=max(4,min(limit,30))（:2294）。匿名直连（服务端 _j 仅 UA+Referer，
 * :2295），零 cookie，超时对齐服务端 10s。
 */
private suspend fun discoverWy(limit: Int): List<DiscoverCard> {
    val lim = maxOf(4, minOf(limit, 30))
    val requests: List<suspend () -> List<DiscoverCard>> = listOf(
        {
            parseWyDigitalAlbums(
                platformHttpGet("$WY_DISCOVER_DIGITAL_URL?limit=$lim&offset=0&total=true&area=ALL", WY_REFERER, WY_DISCOVER_TIMEOUT_MS),
                lim,
            )
        },
        {
            parseWyNewAlbums(
                platformHttpGet("$WY_DISCOVER_NEW_ALBUM_URL?area=ALL&limit=$lim&offset=0", WY_REFERER, WY_DISCOVER_TIMEOUT_MS),
                lim,
            )
        },
        {
            parseWyNewSongs(
                platformHttpGet("$WY_DISCOVER_NEW_SONG_URL?limit=$lim", WY_REFERER, WY_DISCOVER_TIMEOUT_MS),
                lim,
            )
        },
    )
    return fetchDiscoverSectionsConcurrently(requests).flatten()
}

/** 纯解析：vipmall 数字专辑（:2301-2315）：products 或 records → kind=digital，封面 shrink_cover(1080)。 */
internal fun parseWyDigitalAlbums(raw: String, limit: Int): List<DiscoverCard> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("products")?.takeIf { it.length() > 0 } ?: root.optJSONArray("records") ?: return emptyList()
        val out = ArrayList<DiscoverCard>()
        val bound = minOf(limit, arr.length())
        for (i in 0 until bound) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("albumId").ifEmpty { item.optString("id") }
            val name = item.optString("albumName").ifEmpty { item.optString("name") }.trim()
            if (id.isEmpty() || name.isEmpty()) continue
            out.add(
                DiscoverCard(
                    id = id,
                    name = name,
                    cover = shrinkCover(item.optString("coverUrl").ifEmpty { item.optString("picUrl") }.ifEmpty { item.optString("cover") }, 400),
                    artist = wyArtistNames(item.opt("artistName") ?: item.opt("artists")),
                    kind = "digital",
                    title = "数字专辑",
                    count = item.optInt("songCount", item.optInt("trackCount", 0)),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：api/album/new 新碟首发（:2317-2331）：albums → kind=new_album，封面 shrink_cover(1080)。 */
internal fun parseWyNewAlbums(raw: String, limit: Int): List<DiscoverCard> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("albums") ?: return emptyList()
        val out = ArrayList<DiscoverCard>()
        val bound = minOf(limit, arr.length())
        for (i in 0 until bound) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val name = item.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            out.add(
                DiscoverCard(
                    id = id,
                    name = name,
                    cover = shrinkCover(item.optString("picUrl").ifEmpty { item.optString("blurPicUrl") }, 400),
                    artist = wyArtistNames(item.opt("artists")),
                    kind = "new_album",
                    title = "新碟首发",
                    count = item.optInt("size", 0),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：api/personalized/newsong 新歌速递（:2333-2345）：卡片 id=专辑 id、songId=歌曲 id。 */
internal fun parseWyNewSongs(raw: String, limit: Int): List<DiscoverCard> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("result") ?: return emptyList()
        val out = ArrayList<DiscoverCard>()
        val bound = minOf(limit, arr.length())
        for (i in 0 until bound) {
            val item = arr.optJSONObject(i) ?: continue
            val song = item.optJSONObject("song") ?: item
            val album = song.optJSONObject("album")
            val songId = song.optString("id").ifEmpty { item.optString("id") }
            val albumId = album?.optString("id").orEmpty().ifEmpty { songId }
            val name = song.optString("name").ifEmpty { item.optString("name") }.trim()
            if (albumId.isEmpty() || name.isEmpty()) continue
            out.add(
                DiscoverCard(
                    id = albumId,
                    name = name,
                    cover = shrinkCover(album?.optString("picUrl").orEmpty().ifEmpty { song.optString("picUrl") }.ifEmpty { item.optString("picUrl") }, 400),
                    artist = wyArtistNames(song.opt("artists")),
                    kind = "new_song",
                    title = "新歌速递",
                    album = album?.optString("name").orEmpty().trim(),
                    songId = songId,
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 服务端 wy 卡片 artist 归一（:2307-2310 等）：list → ' / '.join(name)，字符串原样，其余空。 */
private fun wyArtistNames(value: Any?): String = when (value) {
    is JSONArray -> {
        val names = ArrayList<String>(value.length())
        for (i in 0 until value.length()) {
            val name = when (val element = value.opt(i)) {
                is JSONObject -> element.optString("name")
                is String -> element
                else -> ""
            }
            if (name.isNotEmpty()) names.add(name)
        }
        names.joinToString(" / ")
    }
    is String -> value
    else -> ""
}

/** 纯解析：qq_discover_cards（:2266-2288）：songlist[].data，要求 songname+albummid；封面固定 800 图。 */
internal fun parseTxDiscoverCards(raw: String, limit: Int): List<DiscoverCard> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<DiscoverCard>()
        val bound = minOf(limit, arr.length())
        for (i in 0 until bound) {
            val item = arr.optJSONObject(i) ?: continue
            val data = item.optJSONObject("data") ?: continue
            val name = data.optString("name").ifEmpty { data.optString("title") }.trim()
            val album = data.optJSONObject("album")
            val albumMid = album?.optString("mid").orEmpty().trim()
            if (name.isEmpty() || albumMid.isEmpty()) continue
            val singer = data.optJSONArray("singer")?.let { singers ->
                val names = ArrayList<String>(singers.length())
                for (k in 0 until singers.length()) {
                    val singerName = singers.optJSONObject(k)?.optString("name").orEmpty()
                    if (singerName.isNotEmpty()) names.add(singerName)
                }
                names.joinToString(" / ")
            }.orEmpty()
            out.add(
                DiscoverCard(
                    id = data.optString("id").ifEmpty { data.optString("mid") },
                    name = name,
                    cover = "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg",
                    artist = singer,
                    kind = "new_song",
                    title = "新歌首发",
                    album = album?.optString("name").orEmpty().trim(),
                ),
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** Song → 发现卡片：kind/title/songId/source 由 normalizeDiscoverCards 按处理器 setdefault 补全。 */
private fun Song.toDiscoverCard(): DiscoverCard = DiscoverCard(
    id = id,
    name = name,
    cover = cover,
    artist = singer,
    album = album,
)

/**
 * 处理器归一（:3068-3080 思路 + 批31 按 kind 分组修复）：按 kind 分组各保留 limit 个
 * （digital 数字专辑 / new_album 新碟首发 / new_song 新歌速递），按 digital→new_album→new_song
 * 顺序拼接——原全局 take(limit) 会让 digital 占满名额，new_album（新碟首发）被切掉
 * （服务端 music_server.py:3990-4008 已修，此处同步，双端规则对齐）。
 */
internal fun normalizeDiscoverCards(cards: List<DiscoverCard>, source: String, limit: Int): List<DiscoverCard> {
    val byKind = LinkedHashMap<String, MutableList<DiscoverCard>>()
    for (card in cards) {
        if (card.id.isBlank() || card.name.isBlank()) continue
        val k = card.kind.ifBlank { "new_song" }
        val bucket = byKind.getOrPut(k) { ArrayList() }
        if (bucket.size < limit) bucket.add(card)
    }
    return buildList {
        for (k in listOf("digital", "new_album", "new_song")) {
            for (card in byKind[k].orEmpty()) {
                add(
                    card.copy(
                        source = source,
                        songId = card.songId.ifBlank { card.id },
                        kind = card.kind.ifBlank { "new_song" },
                        title = card.title.ifBlank { "新歌速递" },
                    )
                )
            }
        }
    }
}

/**
 * 热门直连统一入口（/music/hot，:2945-2989）。服务端该接口返回歌曲列表，故返回 List<Song>；
 * source 归一同 ApiClient.hotServer（非五平台一律 all 聚合）。空/异常返回空列表（调用方回落）。
 */
internal suspend fun fetchHotDirect(source: String): List<Song> {
    val requested = source.trim().lowercase()
    val src = if (requested in Platforms.ALL) requested else "all"
    return try {
        when (src) {
            "wy" -> hotWy()
            "kw" -> hotKw()
            "kg" -> searchKg(HOT_KEYWORD, 1, HOT_KG_LIMIT)
            "tx" -> txDetail(TX_HOT_TOPID, HOT_TX_LIMIT)
            "mg" -> mgDetail(MG_HOT_COLUMN_ID, HOT_MG_LIMIT)
            else -> hotAll()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** wy 热门（:2955-2960 + :2979-2984）：热歌榜歌单 3778678 前30；空态兜底 kw 搜「流行」30。 */
private suspend fun hotWy(): List<Song> {
    val songs = runCatching { wyPlaylistDetail(WY_HOT_PLAYLIST_ID, HOT_PER_SOURCE) }
        .getOrDefault(WyPlaylistDetail("", emptyList(), emptyList()))
        .songs
    if (songs.isNotEmpty()) return songs
    return runCatching { searchKw(KW_POP_FALLBACK_KEYWORD, 1, KW_POP_FALLBACK_LIMIT) }.getOrDefault(emptyList())
}

/** kw 热门（:2961-2969）：热歌榜 bang 17 前30；合计 <10 补 kw 搜「热歌」25（PER_SRC=30 封顶）。 */
private suspend fun hotKw(): List<Song> {
    val songs = runCatching { kwDetail(KW_HOT_BANG_ID, HOT_PER_SOURCE) }
        .getOrDefault(KwRankDetail("", "", 0, emptyList()))
        .songs
    if (songs.size >= HOT_KW_FALLBACK_BELOW) return songs
    val extra = runCatching { searchKw(HOT_KEYWORD, 1, KW_HOT_FALLBACK_LIMIT) }.getOrDefault(emptyList())
    return mergeHotSongs(songs, extra, HOT_PER_SOURCE)
}

/**
 * all 聚合（:2955-2987）：wy 歌单 → kw 榜（<10 补搜）→ kg 搜 → tx 榜26 → mg 榜27186466，
 * 服务端顺序 except-pass；(src,id,name) 去重、单源上限30、总截80。
 */
private suspend fun hotAll(): List<Song> {
    val merged = ArrayList<Song>()
    val seen = HashSet<Triple<String, String, String>>()
    val perSource = HashMap<String, Int>()
    fun addAll(list: List<Song>) {
        for (song in list) {
            val key = Triple(song.source, song.id, song.name)
            if (key in seen) continue
            if ((perSource[song.source] ?: 0) >= HOT_PER_SOURCE) continue
            seen.add(key)
            perSource[song.source] = (perSource[song.source] ?: 0) + 1
            merged.add(song)
        }
    }
    addAll(runCatching { wyPlaylistDetail(WY_HOT_PLAYLIST_ID, HOT_PER_SOURCE) }.getOrDefault(WyPlaylistDetail("", emptyList(), emptyList())).songs)
    addAll(runCatching { kwDetail(KW_HOT_BANG_ID, HOT_PER_SOURCE) }.getOrDefault(KwRankDetail("", "", 0, emptyList())).songs)
    if (merged.size < HOT_KW_FALLBACK_BELOW) {
        addAll(runCatching { searchKw(HOT_KEYWORD, 1, KW_HOT_FALLBACK_LIMIT) }.getOrDefault(emptyList()))
    }
    addAll(runCatching { searchKg(HOT_KEYWORD, 1, HOT_KG_LIMIT) }.getOrDefault(emptyList()))
    addAll(runCatching { txDetail(TX_HOT_TOPID, HOT_TX_LIMIT) }.getOrDefault(emptyList()))
    addAll(runCatching { mgDetail(MG_HOT_COLUMN_ID, HOT_MG_LIMIT) }.getOrDefault(emptyList()))
    return merged.take(HOT_ALL_CAP)
}

/** (src,id,name) 去重合并（服务端 add_list 的 seen 键 f"{src}|{id}|{name}"），总量封顶 cap。 */
private fun mergeHotSongs(base: List<Song>, extra: List<Song>, cap: Int): List<Song> {
    val seen = HashSet<Triple<String, String, String>>()
    val out = ArrayList<Song>(base.size + extra.size)
    for (song in base + extra) {
        if (out.size >= cap) break
        if (seen.add(Triple(song.source, song.id, song.name))) out.add(song)
    }
    return out
}

/**
 * 热搜词直连统一入口（hot_search_words，:1104-1171）。五平台主链 + 最终回退，词
 * clean_space + 全链去重、lim=max(1,min(limit,20))（:2934-2936）；全部匿名直连（服务端
 * http_get_text 默认仅 UA），空/异常返回空列表（调用方回落）。
 */
internal suspend fun fetchHotWordsDirect(source: String, limit: Int): List<String> {
    val lim = maxOf(1, minOf(limit, 20))
    val src = source.trim().lowercase()
    val words = ArrayList<String>()
    fun add(values: List<String>) {
        for (value in values) {
            val word = cleanSpace(value)
            if (word.isNotEmpty() && word !in words) words.add(word)
        }
    }
    try {
        if (src == "kw" || src == "all") {
            add(parseKwHotWords(platformHttpGet(KW_HOTWORD_URL, "", HOTWORD_TIMEOUT_MS)))
        }
        if ((src == "tx" || src == "qq" || src == "all") && words.size < lim) {
            add(parseTxHotKeys(platformHttpGet(TX_HOTWORD_URL, "", HOTWORD_TIMEOUT_MS)))
            if (words.size < lim) add(runCatching { searchTx(HOT_KEYWORD, 1, lim) }.getOrDefault(emptyList()).map { it.name })
        }
        if ((src == "kg" || src == "all") && words.size < lim) {
            add(parseKgHotWords(platformHttpGet("$KG_HOTWORD_URL${maxOf(lim, 10)}", "", HOTWORD_TIMEOUT_MS)))
        }
        if ((src == "wy" || src == "netease" || src == "all") && words.size < lim) {
            // 网易公开热搜接口多变，用热歌榜歌名作实时热词（服务端 :1135-1141 同构）
            add(runCatching { wyPlaylistDetail(WY_HOT_PLAYLIST_ID, maxOf(lim, 12)) }.getOrDefault(WyPlaylistDetail("", emptyList(), emptyList())).songs.map { it.name })
        }
        if ((src == "mg" || src == "migu" || src == "all") && words.size < lim) {
            add(runCatching { searchMg(HOT_KEYWORD, 1, maxOf(lim, 12)) }.getOrDefault(emptyList()).map { it.name })
        }
    } catch (_: Exception) {
        // 服务端 :1146-1148 整体 except-pass：主链异常后仍走下方最终回退
    }
    if (words.size < lim && src == "all") {
        add(runCatching { wyPlaylistDetail(WY_HOT_PLAYLIST_ID, lim) }.getOrDefault(WyPlaylistDetail("", emptyList(), emptyList())).songs.map { it.name })
    }
    if (words.size < lim) {
        when (src) {
            "kg" -> add(runCatching { searchKg(HOT_KEYWORD, 1, lim) }.getOrDefault(emptyList()).map { it.name })
            "mg", "migu" -> add(runCatching { searchMg(HOT_KEYWORD, 1, lim) }.getOrDefault(emptyList()).map { it.name })
            "tx", "qq" -> add(runCatching { searchTx(HOT_KEYWORD, 1, lim) }.getOrDefault(emptyList()).map { it.name })
            "kw" -> add(runCatching { searchKw(HOT_KEYWORD, 1, lim) }.getOrDefault(emptyList()).map { it.name })
        }
    }
    return words.take(lim)
}

/** 纯解析：kw searchKey（:1112-1117）：data 为字符串数组（非字符串元素防御性跳过）。 */
internal fun parseKwHotWords(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return try {
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        (0 until data.length()).mapNotNull { i -> (data.opt(i) as? String)?.takeIf { it.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：tx gethotkey（:1119-1127）：data.hotkey（空则顶层 hotkey）的 k/query/word。 */
internal fun parseTxHotKeys(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        val hot = root.optJSONObject("data")?.optJSONArray("hotkey")?.takeIf { it.length() > 0 }
            ?: root.optJSONArray("hotkey")
            ?: return emptyList()
        (0 until hot.length()).mapNotNull { i ->
            val item = hot.optJSONObject(i) ?: return@mapNotNull null
            item.optString("k").ifEmpty { item.optString("query") }.ifEmpty { item.optString("word") }.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 纯解析：kg search/hot（:1129-1135）：data.info 的 keyword/word。 */
internal fun parseKgHotWords(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return try {
        val info = JSONObject(raw).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        (0 until info.length()).mapNotNull { i ->
            val item = info.optJSONObject(i) ?: return@mapNotNull null
            item.optString("keyword").ifEmpty { item.optString("word") }.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** QQ's actual digital-sales catalog; keep album MID for the album-detail route. */
internal fun parseTxDigitalAlbums(raw: String, limit: Int): List<DiscoverCard> = try {
    val content = JSONObject(raw).takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("data")?.optJSONArray("content")
    buildList {
        for (i in 0 until (content?.length() ?: 0)) {
            val rows = content!!.optJSONObject(i)?.optJSONArray("albumlist") ?: continue
            for (j in 0 until rows.length()) {
                val item = rows.optJSONObject(j) ?: continue
                val id = item.optString("album_mid")
                val name = cleanSpace(item.optString("album_name"))
                if (id.isBlank() || name.isBlank()) continue
                add(DiscoverCard(id, name, "https://y.gtimg.cn/music/photo_new/T002R800x800M000$id.jpg",
                    item.optString("singer_name"), kind = "digital", title = "数字专辑", source = "tx"))
            }
        }
    }.distinctBy { it.id }.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }

/** MG new-CD catalog uses album-info actions, not song IDs or a digital-sales label. */
internal fun parseMgNewAlbums(raw: String, limit: Int): List<DiscoverCard> = try {
    val rows = JSONObject(raw).takeIf { it.optString("code") == "000000" }?.optJSONObject("data")?.optJSONArray("contentItemList")
    val out = ArrayList<DiscoverCard>()
    fun visit(value: Any?) {
        when (value) {
            is JSONObject -> {
                val action = value.optString("actionUrl")
                if (action.startsWith("mgmusic://album-info?id=")) {
                    val id = action.substringAfter("?id=").substringBefore('&')
                    val name = cleanSpace(value.optString("title"))
                    if (id.isNotBlank() && name.isNotBlank()) out.add(DiscoverCard(id, name,
                        shrinkCover(value.optString("imageUrl"), 400), cleanSpace(value.optString("subTitle")),
                        kind = "new_album", title = "新碟首发", source = "mg"))
                }
                for (key in listOf("itemList", "contentItemList")) visit(value.optJSONArray(key))
            }
            is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
        }
    }
    visit(rows)
    out.distinctBy { it.id }.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }

/** Official digital-album shop catalogue; media_id is the real album API identifier. */
internal fun parseKgDigitalAlbums(raw: String, limit: Int): List<DiscoverCard> = try {
    val root = JSONObject(raw)
    val rows = if(root.optInt("error_code", -1) == 0) root.optJSONObject("data")?.optJSONArray("list") else null
    (0 until (rows?.length() ?: 0)).mapNotNull { index ->
        val item = rows?.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("media_id").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return@mapNotNull null
        val name = item.optString("album_name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DiscoverCard(id=id,name=name,cover=item.optString("pic"),artist=item.optString("singer_name"),source="kg",kind="digital",title="数字专辑")
    }.distinctBy { it.id }.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }

/** Official QQ new-release RPC, separate from digital sales and new-song charts. */
internal fun txNewAlbumCatalogUrl(limit: Int): String {
    val payload = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0))
        .put("req_1", JSONObject().put("module", "newalbum.NewAlbumServer").put("method", "get_new_album_info")
            .put("param", JSONObject().put("area", 1).put("sin", 0).put("num", limit.coerceIn(1, 30))))
    return "https://u.y.qq.com/cgi-bin/musicu.fcg?data=" + java.net.URLEncoder.encode(payload.toString(), "UTF-8")
}
internal fun parseTxNewAlbums(raw: String, limit: Int): List<DiscoverCard> = try {
    val root = JSONObject(raw)
    val req = root.takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("req_1")
    val rows = req?.takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("data")?.optJSONArray("albums")
    (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        val a = rows!!.optJSONObject(i) ?: return@mapNotNull null
        val id = a.optString("mid"); val name = cleanSpace(a.optString("name"))
        if (id.isBlank() || name.isBlank()) return@mapNotNull null
        val singers = a.optJSONArray("singers")
        DiscoverCard(id, name, "https://y.gtimg.cn/music/photo_new/T002R800x800M000$id.jpg",
            (0 until (singers?.length() ?: 0)).mapNotNull { singers!!.optJSONObject(it)?.optString("name") }.joinToString(" / "),
            source="tx",kind="new_album",title="新碟首发")
    }.distinctBy { it.id }.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }

/** Public official newest-album page; consume its real album IDs, never search-song IDs. */
internal fun parseKgNewAlbums(raw: String, limit: Int): List<DiscoverCard> = try {
    val json = Regex("var data\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)
    val rows = json?.let { JSONArray(it) }
    (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        val a = rows!!.optJSONObject(i) ?: return@mapNotNull null
        val id = a.optString("albumid"); val name = cleanSpace(a.optString("albumname"))
        if (id.toLongOrNull()?.let { it > 0 } != true || name.isBlank() || a.optInt("is_publish") != 1) return@mapNotNull null
        DiscoverCard(id, name, shrinkCover(a.optString("img"), 400), cleanSpace(a.optString("singername")),
            source="kg",kind="new_album",title="新碟首发")
    }.distinctBy { it.id }.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }

/** Official digital-album column: only resourceType=5, using digital contentId for dAlbumId. */
internal fun parseMgDigitalAlbums(raw: String, limit: Int): List<DiscoverCard> = try {
    val root = JSONObject(raw).takeIf { it.optString("code") == "000000" }
    val out = ArrayList<DiscoverCard>()
    fun visit(value: Any?) {
        when(value) {
            is JSONObject -> {
                if (value.optString("resourceType") == "5") {
                    val id = value.optString("contentId"); val name = cleanSpace(value.optString("title"))
                    val imgs = value.optJSONArray("imgItems")
                    val cover = (0 until (imgs?.length() ?: 0)).mapNotNull { imgs!!.optJSONObject(it) }
                        .sortedByDescending { it.optString("imgSizeType").toIntOrNull() ?: 0 }
                        .map { it.optString("img") }.firstOrNull { it.isNotBlank() }.orEmpty()
                    if (id.isNotBlank() && name.isNotBlank()) out.add(DiscoverCard(id, name, shrinkCover(cover, 400),
                        cleanSpace(value.optString("singer")), source="mg",kind="digital",title="数字专辑"))
                }
                val keys = value.keys()
                while(keys.hasNext()) visit(value.opt(keys.next()))
            }
            is JSONArray -> for(i in 0 until value.length()) visit(value.opt(i))
        }
    }
    visit(root)
    out.distinctBy { it.id }.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }
