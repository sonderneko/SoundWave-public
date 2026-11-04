package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.Song
import java.net.URLEncoder

/**
 * 网易云搜索客户端直连（Local-first 批2）。
 * 逐行移植服务端搜索端点的 _wy()（/opt/soundwave/music_server.py /api/search 分支，~L2722）：
 * - URL/参数：cloudsearch/pc?s=<quote(keyword)>&type=1&limit=<limit>&offset=<(page-1)*limit>
 *   （quote 同 mg：URLEncoder + 空格→%20；与 urllib.parse.quote 的 / 编码差异在服务端解码等价）
 * - 请求头：Referer=https://music.163.com/ + 服务端同款桌面 Chrome UA；服务端 wy 搜索不带任何
 *   Cookie（对比 toplist/playlist 分支的 Cookie: os=pc），客户端保持无 cookie 匿名直连
 * - 解析：result.songs → ar/artists[].name 以「,」拼接、al/album 的 picUrl/blurPicUrl 封面、
 *   album.name；cleanSpace 清洗、shrinkCover(1080)（内含 http→https 升级，与服务端两步等价）、
 *   scoreSong 降序稳定重排（服务端合并各源 all_list 后按 _score 降序排，wy 单源同序）
 * - 补封面：无封面 id（截取前 limit 个）批量请求 song/detail/?ids=[id,id]，失败不影响已解析结果
 * - 与服务端差异：总超时 8s（服务端 6s）；网络/解析失败返回空列表（服务端 except-pass）
 */

private const val WY_SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
private const val WY_DETAIL_URL = "https://music.163.com/api/song/detail/"
private const val WY_REFERER = "https://music.163.com/"

internal suspend fun searchWy(keyword: String, page: Int, limit: Int): List<Song> {
    return try {
        val text = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val url = "$WY_SEARCH_URL?s=$text&type=1&limit=$limit&offset=${(page - 1) * limit}"
        val songs = parseWySearch(platformHttpGet(url, WY_REFERER), keyword, limit)
        if (songs.isEmpty()) return songs
        val needDetail = songs.filter { it.cover.isEmpty() }.map { it.id }.take(limit)
        if (needDetail.isEmpty()) return songs
        val covers = try {
            parseWyDetailCovers(
                platformHttpGet("$WY_DETAIL_URL?ids=[${needDetail.joinToString(",")}]", WY_REFERER),
            )
        } catch (e: Exception) {
            emptyMap()
        }
        if (covers.isEmpty()) songs else songs.map { song ->
            if (song.cover.isEmpty()) covers[song.id]?.let { song.copy(cover = it) } ?: song else song
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数（不触网，仅依赖 org.json，便于单测喂样例）：网易云 cloudsearch 原始 JSON → Song 列表。
 * 服务端等价：URL 已带 limit，服务端不另截取（limit 保留入参以对齐签名）；遍历 result.songs 全量，
 * name+id 非空才保留；al/al.name 按 Python or 语义（空对象/空串视为缺失）回退。
 */
internal fun parseWySearch(raw: String, keyword: String, limit: Int = 12): List<Song> {
    if (raw.isBlank()) return emptyList()
    return try {
        val result = JSONObject(raw).optJSONObject("result")?.takeIf { it.length() > 0 }
            ?: return emptyList()
        val songs = result.optJSONArray("songs")
        if (songs == null || songs.length() == 0) return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val name = cleanSpace(item.optString("name"))
            val ar = item.optJSONArray("ar")
            val arts = if (ar != null && ar.length() > 0) ar else item.optJSONArray("artists")
            val singers = cleanSpace(
                arts?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        arr.optJSONObject(idx)?.optString("name")
                    }.joinToString(",")
                } ?: ""
            )
            val songId = item.optString("id")
            // 主歌手 = ar/artists 第一位，其 id 与歌手名同源（此前只取 name、ID 被丢，「查看歌手」只能按名猜）。
            val singerId = arts?.optJSONObject(0)?.optString("id")?.takeUnless { it == "null" }.orEmpty()
            val al = item.optJSONObject("al")?.takeIf { it.length() > 0 }
                ?: item.optJSONObject("album")?.takeIf { it.length() > 0 }
            val rawCover = if (al != null) al.optString("picUrl").ifEmpty { al.optString("blurPicUrl") } else ""
            val cover = if (rawCover.isNotEmpty()) shrinkCover(rawCover) else ""
            if (name.isNotEmpty() && songId.isNotEmpty()) {
                out.add(
                    Song(
                        maxQuality = inferMaxQuality(item),
                        id = songId,
                        name = name,
                        singer = singers,
                        singerId = singerId,
                        source = "wy",
                        cover = cover,
                        album = if (al != null) cleanSpace(al.optString("name")) else "",
                        // ACCEPT5（C）：dt（毫秒）为 cloudsearch 每条结果的真实字段
                        // （2026-11-04 对 music.163.com/api/cloudsearch/pc 黑盒探针
                        // 实证，免费/受限曲目均带）——跨源严配的时长证据自此在 wy
                        // 腿成立；不读 dt 时 wy 候选时长恒 0，SongMatcher 一律以
                        // duration_missing 拒绝，跨源 wy 腿形同虚设。
                        duration = item.optLong("dt").coerceAtLeast(0L),
                    )
                )
            }
        }
        out.sortedByDescending { scoreSong(keyword, it.name, it.singer) }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 纯解析函数：song/detail 批量接口原始 JSON → id → 封面（shrinkCover 后，含 http→https 升级）。
 * 服务端等价：songs[].album/al 的 picUrl/blurPicUrl，取到封面才写入映射（无封面不占位）。
 */
internal fun parseWyDetailCovers(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return try {
        val songs = JSONObject(raw).optJSONArray("songs") ?: return emptyMap()
        val covers = HashMap<String, String>(songs.length())
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val al = song.optJSONObject("album")?.takeIf { it.length() > 0 }
                ?: song.optJSONObject("al")?.takeIf { it.length() > 0 }
            if (al == null) continue
            val pic = al.optString("picUrl").ifEmpty { al.optString("blurPicUrl") }
            if (pic.isNotEmpty()) covers[song.optString("id")] = shrinkCover(pic)
        }
        covers
    } catch (e: Exception) {
        emptyMap()
    }
}
