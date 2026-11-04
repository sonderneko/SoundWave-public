package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.Song
import java.net.URLEncoder

/**
 * 酷我搜索客户端直连（Local-first 批3）。
 * 逐行移植服务端 kuwo_search（/opt/soundwave/music_server.py:1542）：
 * - 主源 URL/参数：https://search.kuwo.cn/r.s?all=<quote(q)>&ft=music&client=kt&cluster=0&pn=0
 *   &rn=max(limit,30)&rformat=json&encoding=utf8&vipver=1（quote 同 mg/wy：URLEncoder + 空格→%20）
 * - 请求头：仅服务端同款桌面 Chrome UA；服务端 http_get(headers=None) 不带 Referer/Cookie（匿名可搜，
 *   按隐私规则保持无 cookie 直连，platformHttpGet 传空 Referer，附加 Accept 头与共享通道一致）。
 *   总超时 8s（服务端主源 10s / oiapi 8s）
 * - 无分页：服务端 pn 恒 0（/api/search 的 kw 分支同样不传页码），page 参数保留签名但忽略
 * - 解析：abslist[] → SONGNAME/NAME、ARTIST、DC_TARGETID/MUSICRID/rid（MUSIC_ 前缀剥离 + 仅留数字）、
 *   ALBUM、DURATION；封面只取该曲真实专辑图 web_albumpic_short；不把歌手图、MV 横幅、PICPATH 或
 *   ALBUMID 猜测 URL 当成歌曲专辑封面。http URL 原样保留，/ 开头补 https://img1.kuwo.cn/star/albumcover 前缀；
 *   服务端对 kw 搜索封面不做 shrink_cover，客户端同样原样保留
 * - 「剥离之茧」强制再查「剥离之茧 添儿呗」；KNOWN_ORIGINALS 硬插原唱（rid=KW_ORIGINAL_RID、cover 置空、
 *   album=歌名、duration=210、score=999）或对命中 rid 的既有条目改写 name/singer 并置 score=999
 *   （promoted 集合对应服务端 _score=999 覆写；scoreSong 共享实现已含 KNOWN_ORIGINALS 加权）
 * - 兜底：主源结果 <3 条时 oiapi.net/api/Kuwo/search?msg=<quote(keyword)>&pn=1&rn=<limit>（服务端注明
 *   勿解析 message 文本否则 id 为空，仅取 data.list/abslist 结构化列表，字段见 parseKwOiapiSearch）
 * - 排序：rid 全局去重后按 scoreSong 降序、duration 降序，截前 limit 条（服务端对合并列表单次 sort；
 *   parseKwSearch 保持解析序，排序统一在 searchKw 合并后执行）
 * - 与服务端差异：总超时统一 8s；DURATION/time 解析失败记 0（服务端该异常会丢弃整查询结果）；
 *   网络/解析失败返回空列表（服务端 except-pass）
 */

private const val KW_SEARCH_URL = "https://search.kuwo.cn/r.s"
private const val KW_OIAPI_URL = "https://oiapi.net/api/Kuwo/search"

/** 服务端 KNOWN_ORIGINALS（music_server.py:1469）硬插条目的酷我 rid。 */
private const val KW_ORIGINAL_RID = "159989657"

/** 服务端 _score=999 的置顶语义（KNOWN_ORIGINALS 硬插 / 命中 rid 改写）。 */
private const val KW_PROMOTED_SCORE = 999

internal suspend fun searchKw(keyword: String, page: Int, limit: Int): List<Song> {
    return try {
        val seen = HashSet<String>()
        val promoted = HashSet<String>()
        val items = ArrayList<Song>()
        val queries = ArrayList<String>()
        queries.add(keyword)
        // 服务端：剥离之茧强制再查原唱歌手，避免 remix 霸榜
        if (keyword.trim() == "剥离之茧") queries.add("剥离之茧 添儿呗")
        for (query in queries) {
            try {
                val text = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                val url = "$KW_SEARCH_URL?all=$text&ft=music&client=kt&cluster=0&pn=0&rn=${maxOf(limit, 30)}" +
                    "&rformat=json&encoding=utf8&vipver=1"
                for (song in parseKwSearch(platformHttpGet(url, ""), keyword, limit)) {
                    if (seen.add(song.id)) items.add(song)
                }
            } catch (e: Exception) {
                // 服务端 except-pass：单次查询失败不影响其余查询
            }
        }
        // 服务端：硬插已知原唱到最前；命中 rid 的既有条目改写 name/singer 并置 _score=999
        for ((originalSong, originalArtist) in KNOWN_ORIGINALS) {
            if (keyword.trim() == originalSong && KW_ORIGINAL_RID !in seen) {
                seen.add(KW_ORIGINAL_RID)
                promoted.add(KW_ORIGINAL_RID)
                items.add(
                    Song(
                        id = KW_ORIGINAL_RID,
                        name = originalSong,
                        singer = originalArtist,
                        source = "kw",
                        cover = "",
                        album = originalSong,
                        duration = 210000L,
                    )
                )
            } else {
                for (index in items.indices) {
                    if (items[index].id == KW_ORIGINAL_RID) {
                        promoted.add(KW_ORIGINAL_RID)
                        items[index] = items[index].copy(name = originalSong, singer = originalArtist)
                    }
                }
            }
        }
        // 服务端：主源不足 3 条时 oiapi 结构化 search 兜底（勿解析 message 文本，否则 id 为空）
        if (items.size < 3) {
            try {
                val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
                val url = "$KW_OIAPI_URL?msg=$text&pn=1&rn=$limit"
                for (song in parseKwOiapiSearch(platformHttpGet(url, ""), keyword, limit)) {
                    if (seen.add(song.id)) items.add(song)
                }
            } catch (e: Exception) {
                // 服务端 except-pass
            }
        }
        items.sortedWith(
            compareByDescending<Song> { if (it.id in promoted) KW_PROMOTED_SCORE else scoreSong(keyword, it.name, it.singer) }
                .thenByDescending { it.duration }
        ).take(limit)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数（不触网，仅依赖 org.json，便于单测喂样例）：酷我 r.s 原始 JSON → Song 列表（解析序）。
 * 服务端等价：abslist[] 逐条解析，name 且 rid 非空才保留、rid 去重；_score 重排在 searchKw 合并后
 * 统一执行（服务端对合并列表单次 sort），本函数不排序不截取（limit 入参对齐签名，rn 由 searchKw 拼接）。
 */
internal fun parseKwSearch(raw: String, keyword: String, limit: Int = 20): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val list = parseKwJson(raw).optJSONArray("abslist") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = cleanSpace(kwOrChain(item, "SONGNAME", "NAME"))
            val singer = cleanSpace(item.optString("ARTIST"))
            // ARTISTID 与 ARTIST 并列下发（此前只取名字、ID 被丢）；"0" 是无歌手占位，按缺失处理走按名兜底。
            val singerId = kwOrChain(item, "ARTISTID").takeUnless { it == "0" }.orEmpty()
            val songId = kuwoRid(kwOrChain(item, "DC_TARGETID", "MUSICRID", "rid"))
            val album = cleanSpace(item.optString("ALBUM"))
            val cover = kwCoverFromItem(item)
            val duration = secondsToDurationMs(item.optString("DURATION").trim().toLongOrNull() ?: 0L)
            if (name.isEmpty() || songId.isEmpty() || !seen.add(songId)) continue
            out.add(
                Song(
                    maxQuality = inferMaxQuality(item),
                    id = songId,
                    name = name,
                    singer = singer,
                    singerId = singerId,
                    source = "kw",
                    cover = cover,
                    album = album,
                    duration = duration,
                )
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数：oiapi Kuwo/search 原始 JSON → Song 列表（解析序）。
 * 服务端等价：data 为 dict 时取 list（空则回退 abslist，等价 Python or 链）、为数组时直接遍历；
 * 字段 song/name/SONGNAME、singer/artist/ARTIST、rid/id/DC_TARGETID、picture/cover（原样，不补
 * kuwo 前缀、不 shrink）、album（原样不清洗）、time/duration。
 */
internal fun parseKwOiapiSearch(raw: String, keyword: String, limit: Int = 20): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val data = parseKwJson(raw).opt("data")
        val list = when (data) {
            is JSONObject -> data.optJSONArray("list")?.takeIf { it.length() > 0 } ?: data.optJSONArray("abslist")
            is JSONArray -> data
            else -> null
        } ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = cleanSpace(kwOrChain(item, "song", "name", "SONGNAME"))
            val singer = cleanSpace(kwOrChain(item, "singer", "artist", "ARTIST"))
            val singerId = kwOrChain(item, "ARTISTID", "artistid", "artistId").takeUnless { it == "0" }.orEmpty()
            val songId = kuwoRid(kwOrChain(item, "rid", "id", "DC_TARGETID"))
            val cover = kwOrChain(item, "picture", "cover")
            val album = item.optString("album")
            val duration = secondsToDurationMs(kwOrChain(item, "time", "duration").trim().toLongOrNull() ?: 0L)
            if (name.isEmpty() || songId.isEmpty() || !seen.add(songId)) continue
            out.add(
                Song(
                    maxQuality = inferMaxQuality(item),
                    id = songId,
                    name = name,
                    singer = singer,
                    singerId = singerId,
                    source = "kw",
                    cover = cover,
                    album = album,
                    duration = duration,
                )
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/** 服务端 _parse_kuwo_json：直接解析失败后按单引号→双引号重试，仍失败返回空对象。 */
internal fun parseKwJson(raw: String): JSONObject {
    val text = raw.trim()
    if (text.isEmpty()) return JSONObject()
    return try {
        JSONObject(text)
    } catch (e: Exception) {
        try {
            JSONObject(text.replace("'", "\""))
        } catch (e: Exception) {
            JSONObject()
        }
    }
}

/** 服务端 or 链语义：JSON null / 空串视为缺失并尝试下一个 key。 */
private fun kwOrChain(obj: JSONObject, vararg keys: String): String {
    for (key in keys) {
        if (obj.isNull(key)) continue
        val value = obj.optString(key)
        if (value.isNotEmpty()) return value
    }
    return ""
}

/** 歌曲封面只用该歌曲真实专辑封面 web_albumpic_short；歌手头像、视频横幅及猜测 albumid 的 URL 均不是同一封面。 */
private fun kwCoverFromItem(item: JSONObject): String {
    if (!item.isNull("web_albumpic_short")) {
        val pic = item.optString("web_albumpic_short")
        if (pic.isNotEmpty()) {
            if (pic.startsWith("http")) return pic
            if (pic.startsWith("/")) return "https://img1.kuwo.cn/star/albumcover$pic"
            return "https://img1.kuwo.cn/star/albumcover/${pic.trimStart('/')}"
        }
    }
    return ""
}
