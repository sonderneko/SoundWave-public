package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.Song
import java.net.URLEncoder

/**
 * 酷狗搜索客户端直连（Local-first 批4）。
 * 逐行移植服务端 kugou_search（/opt/soundwave/music_server.py:773）：
 * - 主源 URL/参数：https://songsearch.kugou.com/song_search_v2?keyword=<quote>&page=1&pagesize=<limit>
 *   &platform=WebFilter&userid=-1&iscorrection=1&privilege_filter=0&filter=2
 *   （quote 同 mg/wy/kw：URLEncoder + 空格→%20；与 urllib.parse.quote 的 / 编码差异在服务端解码等价）
 * - 主源请求头：服务端同款桌面 Chrome UA（PlatformHttp 共享）+ Referer=https://www.kugou.com/；
 *   无 Cookie（匿名直连，隐私规则）
 * - 兜底（主源结果 <3 条）：http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=<quote>
 *   &page=1&pagesize=<limit>&showtype=1 —— **明文 http 兜底属服务端既有行为，原样保留，安全整改另行处理**；
 *   请求头仅 UA、无 Referer（platformHttpGet 传空 Referer，同 kw 先例，附加 Accept 与共享通道一致）
 * - 总超时：主源/兜底均 6s（服务端 timeout=6 原值，platformHttpGet 传 timeoutMs=6000）
 * - 主源解析：data.lists[] → SongName/SingerName、FileHash/HQFileHash（id 与 hash 同值）、
 *   AlbumID/AlbumId、封面 AlbumImage/Image/AlbumImg/img → shrinkCover(1080)、AlbumName；
 *   name 且 sid 非空才保留；scoreSong 计分
 * - 兜底解析：data.info[] → songname/songname_original、singername、hash/320hash/sqhash 转大写、
 *   album_id、封面 album_img/imgUrl/image + trans_param.union_cover/img → shrinkCover(1080)、album_name；
 *   对主源已有 id 去重（seen 集合，兜底新条目加入 seen，同服务端）
 * - Song 字段：hash=sid、albumId=album_id（服务端同名键 1:1）；服务端未给 duration → 恒 0
 * - 排序：scoreSong 降序稳定重排（Kotlin sortedByDescending/TimSort 与 Python sort 同为稳定排序），截前 limit 条
 * - 与服务端差异：page 参数保留签名但忽略（服务端两接口 page 恒 1）；
 *   网络/解析失败返回空列表（服务端 except-pass）
 */

private const val KG_SEARCH_URL = "https://songsearch.kugou.com/song_search_v2"

/** 服务端明文 http 兜底（music_server.py:797），原样保留。 */
private const val KG_MOBILE_FALLBACK_URL = "http://mobilecdn.kugou.com/api/v3/search/song"

private const val KG_REFERER = "https://www.kugou.com/"

/** 服务端 timeout=6 的原值（其余批次直连统一 8s，本批按服务端原值移植）。 */
private const val KG_TIMEOUT_MS = 6000L

internal suspend fun searchKg(keyword: String, page: Int, limit: Int): List<Song> {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val mainUrl = "$KG_SEARCH_URL?keyword=$text&page=1&pagesize=$limit" +
            "&platform=WebFilter&userid=-1&iscorrection=1&privilege_filter=0&filter=2"
        val items = ArrayList<Song>()
        items.addAll(parseKgSearch(platformHttpGet(mainUrl, KG_REFERER, KG_TIMEOUT_MS), keyword, limit))
        // 服务端：主源不足 3 条时 mobilecdn v3 兜底，兜底结果对主源已有 id 去重
        if (items.size < 3) {
            try {
                val fallbackUrl = "$KG_MOBILE_FALLBACK_URL?format=json&keyword=$text&page=1&pagesize=$limit&showtype=1"
                val seen = items.map { it.id }.toHashSet()
                for (song in parseKgMobileCdnFallback(platformHttpGet(fallbackUrl, "", KG_TIMEOUT_MS), keyword, limit)) {
                    if (seen.add(song.id)) items.add(song)
                }
            } catch (e: Exception) {
                // 服务端 except-pass：兜底失败不影响主源结果
            }
        }
        items.sortedByDescending { scoreSong(keyword, it.name, it.singer) }.take(limit)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数（不触网，仅依赖 org.json，便于单测喂样例）：酷狗 song_search_v2 原始 JSON → Song 列表（解析序）。
 * 服务端等价：data.lists 逐条解析（name 且 FileHash/HQFileHash 回退结果非空才保留），
 * 不在解析内排序/截断（排序统一在 searchKg 合并后执行；URL 的 pagesize 已限流）。
 */
internal fun parseKgSearch(raw: String, keyword: String, limit: Int = 12): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val lists = JSONObject(raw).optJSONObject("data")?.optJSONArray("lists")
            ?: return emptyList()
        val out = ArrayList<Song>(lists.length())
        for (i in 0 until lists.length()) {
            val item = lists.optJSONObject(i) ?: continue
            val name = cleanSpace(kgOrChain(item, "SongName"))
            val singer = cleanSpace(kgOrChain(item, "SingerName"))
            // v2 条目多数不带歌手 ID：尝试 SingerID/singerid，取不到留空走按名兜底（不猜、不借歌曲 id）。
            val singerId = kgOrChain(item, "SingerID", "SingerId", "singerid", "singerId")
            val songId = kgOrChain(item, "FileHash", "HQFileHash")
            val albumId = kgOrChain(item, "AlbumID", "AlbumId")
            val cover = shrinkCover(kgOrChain(item, "AlbumImage", "Image", "AlbumImg", "img"))
            val album = cleanSpace(kgOrChain(item, "AlbumName"))
            if (name.isNotEmpty() && songId.isNotEmpty()) {
                out.add(
                    Song(
                        maxQuality = inferMaxQuality(item),
                        id = songId,
                        name = name,
                        singer = singer,
                        singerId = singerId,
                        source = "kg",
                        cover = cover,
                        album = album,
                        albumId = albumId,
                        hash = songId,
                        // ACCEPT5（C）：Duration（秒）为 kg 搜索每条结果的真实
                        // 字段——跨源严配的时长证据自此在 kg 腿成立；不读时 kg
                        // 候选时长恒 0，SongMatcher 一律 duration_missing 拒绝。
                        duration = secondsToDurationMs(
                            kgOrChain(item, "Duration", "duration").trim().toLongOrNull() ?: 0L
                        ),
                    )
                )
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数：mobilecdn v3 search/song 兜底原始 JSON → Song 列表（解析序，不含主源去重）。
 * 服务端等价：data.info[]（data 缺失/非 dict 时等价空列表）；hash/320hash/sqhash 回退后转大写；
 * 封面 album_img/imgUrl/image 为空且 trans_param 为 dict 时回退 union_cover/img，再 shrinkCover(1080)。
 */
internal fun parseKgMobileCdnFallback(raw: String, keyword: String, limit: Int = 12): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val info = JSONObject(raw).optJSONObject("data")?.optJSONArray("info")
            ?: return emptyList()
        val out = ArrayList<Song>(info.length())
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val name = cleanSpace(kgOrChain(item, "songname", "songname_original"))
            val singer = cleanSpace(kgOrChain(item, "singername"))
            // v3 条目带 singerid（单歌手时）；缺失留空走按名兜底。
            val singerId = kgOrChain(item, "singerid", "singerId", "SingerID")
            val songId = kgOrChain(item, "hash", "320hash", "sqhash").uppercase()
            val albumId = kgOrChain(item, "album_id")
            var cover = kgOrChain(item, "album_img", "imgUrl", "image", "pic", "imgurl")
            if (cover.isEmpty()) {
                val transParam = item.optJSONObject("trans_param")
                if (transParam != null) cover = kgOrChain(transParam, "union_cover", "img")
            }
            val album = cleanSpace(kgOrChain(item, "album_name"))
            if (name.isNotEmpty() && songId.isNotEmpty()) {
                out.add(
                    Song(
                        maxQuality = inferMaxQuality(item),
                        id = songId,
                        name = name,
                        singer = singer,
                        singerId = singerId,
                        source = "kg",
                        cover = shrinkCover(cover),
                        album = album,
                        albumId = albumId,
                        hash = songId,
                        // ACCEPT5（C）：duration（秒）为 mobilecdn v3 每条结果的
                        // 真实字段（2026-11-04 黑盒探针实证）——kg 腿时长证据。
                        duration = secondsToDurationMs(
                            kgOrChain(item, "duration", "Duration").trim().toLongOrNull() ?: 0L
                        ),
                    )
                )
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/** 服务端 or 链语义：JSON null / 空串视为缺失并尝试下一个 key。 */
private fun kgOrChain(obj: JSONObject, vararg keys: String): String {
    for (key in keys) {
        if (obj.isNull(key)) continue
        val value = obj.optString(key)
        if (value.isNotEmpty()) return value
    }
    return ""
}
