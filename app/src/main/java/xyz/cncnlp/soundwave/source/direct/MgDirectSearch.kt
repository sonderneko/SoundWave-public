package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.mgImgOk
import xyz.cncnlp.soundwave.normalizeCoverUrl
import java.net.URLEncoder

/**
 * 咪咕搜索客户端直连（Local-first 批1试点）。
 * 逐行移植服务端 migu_search（/opt/soundwave/music_server.py）：
 * - URL/参数：text（quote 编码）+ pageNo + pageSize + searchSwitch=quote('{song:1}')
* - 请求头：Referer=https://music.migu.cn/、服务端同款桌面 Chrome UA、Accept 与服务端一致（json + text/plain + 通配）；总超时 8s
 * - 解析：songResultData.result 截取前 limit 条 → name/singers 拼接/contentId→copyrightId→id 回退、
 *   albums[0].name、imgItems 尾优先取 img；cleanSpace 清洗、shrink_cover(1080)、score_song 降序稳定重排
 * - 与服务端差异：pageNo 由调用方传页码（服务端恒 1）；网络/解析失败返回空列表（服务端 except-pass）
 * - 批2：cleanSpace/unescapeHtml/WS_ALL/CJK_GAP/shrinkCover/scoreSong 等共享解析工具已抽取至 DirectParsing.kt（同包直接引用）
 */

private const val MG_SEARCH_URL = "https://app.c.nf.migu.cn/MIGUM3.0/v1.0/content/search_all.do"

/** 服务端 urllib.parse.quote('{song:1}') 的结果。 */
private const val MG_SEARCH_SWITCH = "%7Bsong%3A1%7D"
private const val MG_REFERER = "https://music.migu.cn/"

internal suspend fun searchMg(keyword: String, page: Int, limit: Int): List<Song> {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val url = "$MG_SEARCH_URL?text=$text&pageNo=$page&pageSize=$limit&searchSwitch=$MG_SEARCH_SWITCH"
        parseMgSearch(platformHttpGet(url, MG_REFERER), keyword, limit)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数（不触网，仅依赖 org.json，便于单测喂样例）：migu 原始 JSON → Song 列表。
 * 服务端等价：先截取 result[:limit] 再逐条解析（name 且 id 非空才保留），最后按 score_song 降序稳定排序。
 */
internal fun parseMgSearch(raw: String, keyword: String, limit: Int = 12): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        if (root.has("code") && root.optString("code") != "000000") return emptyList()
        val result = root.optJSONObject("songResultData")?.optJSONArray("result")
            ?: return emptyList()
        val bound = minOf(result.length(), limit)
        val songs = ArrayList<Song>(bound)
        for (i in 0 until bound) {
            val item = result.optJSONObject(i) ?: continue
            val name = cleanSpace(text(item, "name"))
            val singers = cleanSpace(
                item.optJSONArray("singers")?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        arr.optJSONObject(idx)?.let { text(it, "name") }
                    }.joinToString(",")
                } ?: ""
            )
            val songId = orChain(item, "contentId", "copyrightId", "id")
            // 主歌手 = singers 第一位，其 id 与歌手名同源（此前只取 name、ID 被丢）。
            val singerId = item.optJSONArray("singers")?.optJSONObject(0)?.let { text(it, "id") }.orEmpty()
            val copyrightId = orChain(item, "copyrightId", "")
            val albums = item.optJSONArray("albums")
            val album = if (albums != null && albums.length() > 0) cleanSpace(text(albums.optJSONObject(0), "name")) else ""
            // imgItems 带 imgSizeType 时取最高档有效原图；每个变体先试 img，再试同一变体 webpImg。
            // 无 imgSizeType 保持服务端「尾优先」语义；imgItems 缺失时回退 img/pic/cover 直传字段。
            // 高清归一统一走 normalizeCoverUrl。
            val imgs = item.optJSONArray("imgItems")
            val cover = if (imgs != null && imgs.length() > 0) {
                var bestCover = ""
                var bestSize = -1
                for (i in 0 until imgs.length()) {
                    val variant = imgs.optJSONObject(i) ?: continue
                    // 批29-6：坏的/截断 img 不能遮蔽同一变体中仍可用的 webpImg。
                    val candidate = text(variant, "img").takeIf { mgImgOk(it) }
                        ?: text(variant, "webpImg").takeIf { mgImgOk(it) }
                        ?: continue
                    val size = variant.optString("imgSizeType").trimStart('0').toIntOrNull() ?: -1
                    if (size >= 0 && (bestSize < 0 || size > bestSize)) {
                        bestSize = size
                        bestCover = candidate
                    } else if (size < 0 && bestSize < 0) {
                        // 未标尺寸的数据沿用原服务端行为：有效候选取数组尾项。
                        bestCover = candidate
                    }
                }
                bestCover
            } else orChain(item, "img", "pic", "cover")
            if (name.isNotEmpty() && songId.isNotEmpty()) {
                songs.add(
                    Song(
                        maxQuality = inferMaxQuality(item),
                        id = songId,
                        name = name,
                        singer = singers,
                        singerId = singerId,
                        source = "mg",
                        cover = normalizeCoverUrl(cover),
                        album = album,
                        copyrightId = copyrightId,
                        // Legacy search `id` is the resource songId, not contentId/copyrightId.
                        miguSongId = orChain(item, "songId", "id"),
                        albumId = albums?.optJSONObject(0)?.let { text(it, "id") }.orEmpty(),
                    )
                )
            }
        }
        songs.sortedByDescending { scoreSong(keyword, it.name, it.singer) }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun text(obj: JSONObject?, key: String): String {
    if (obj == null) return ""
    val value = obj.optString(key)
    return if (value.isEmpty() || value == "null") "" else value
}
