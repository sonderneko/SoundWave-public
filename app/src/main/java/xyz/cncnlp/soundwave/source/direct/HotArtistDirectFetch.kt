package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject

/** 平台热门歌手（带真实头像）。仅支持已实测匿名可用的平台；其余返回空列表由调用方回落。 */
data class HotArtist(
    val name: String,
    val avatar: String = "",
    /** 平台歌手 id（wy artist id / kg singerid）；空则调用方按歌手名检索。 */
    val id: String = ""
)

internal const val HOT_ARTIST_LIMIT = 12

/**
 * 当前平台热门歌手：wy 网易云 /api/artist/top 匿名可用（含 picUrl）；
 * kg 酷狗 /api/v3/singer/list 匿名可用（含 imgurl，按 heat 降序取前 N）。
 * tx QQ 音乐 Music.SingerListServer.get_singer_list 匿名可用（singer_mid + singer_pic）；
 * kw 酷我 artistlistinfo mb.slist order=hot 匿名可用（pic 为 starheads 相对路径）。
 * mg 从当前平台热歌榜歌曲的官方 resourceinfo.artists + singerImg 获取真实歌手头像。
 */
internal suspend fun fetchHotArtists(source: String, limit: Int = HOT_ARTIST_LIMIT, chartSongs: List<xyz.cncnlp.soundwave.Song> = emptyList()): List<HotArtist> = try {
    when (source.lowercase()) {
        "wy" -> parseWyHotArtists(platformHttpGet("https://music.163.com/api/artist/top?limit=$limit&offset=0", "https://music.163.com/"), limit)
        "tx" -> parseTxHotArtists(platformHttpGet(TX_HOT_ARTIST_URL, "https://y.qq.com/"), limit)
        "kw" -> parseKwHotArtists(platformHttpGet(kwHotArtistUrl(limit), KW_HOT_ARTIST_REFERER), limit)
        "kg" -> parseKgHotArtists(platformHttpGet("http://mobilecdn.kugou.com/api/v3/singer/list?page=1&pagesize=$limit&classid=0", "https://m.kugou.com/"), limit)
        "mg" -> fetchMgChartArtists(chartSongs, limit)
        else -> emptyList()
    }
} catch (cancelled: java.util.concurrent.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

internal fun parseWyHotArtists(raw: String, limit: Int): List<HotArtist> = try {
    if (raw.isBlank()) emptyList() else {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) emptyList() else {
            val arr = root.optJSONArray("artists") ?: org.json.JSONArray()
            val out = ArrayList<HotArtist>(limit)
            val bound = minOf(limit, arr.length())
            for (i in 0 until bound) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                out.add(HotArtist(name = name, avatar = shrinkCover(item.optString("picUrl"), 300), id = item.optString("id").takeIf { it.isNotBlank() && it != "0" }.orEmpty()))
            }
            out
        }
    }
} catch (_: Exception) {
    emptyList()
}

internal fun parseKgHotArtists(raw: String, limit: Int): List<HotArtist> = try {
    if (raw.isBlank()) emptyList() else {
        val root = JSONObject(raw)
        val info = root.optJSONObject("data")?.optJSONArray("info") ?: org.json.JSONArray()
        val out = ArrayList<HotArtist>(limit)
        val bound = minOf(limit, info.length())
        for (i in 0 until bound) {
            val item = info.optJSONObject(i) ?: continue
            val name = item.optString("singername").trim()
            if (name.isEmpty()) continue
            out.add(HotArtist(name = name, avatar = item.optString("imgurl").replace("{size}", "240").replace("http://", "https://"), id = item.optString("singerid").takeIf { it.isNotBlank() && it != "0" }.orEmpty()))
        }
        // 酷狗按热度降序返回，直接取前 N 即平台热门。
        out
    }
} catch (_: Exception) {
    emptyList()
}

private const val TX_HOT_ARTIST_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg?data=%7B%22comm%22%3A%7B%22ct%22%3A24%2C%22cv%22%3A0%7D%2C%22singerList%22%3A%7B%22module%22%3A%22Music.SingerListServer%22%2C%22method%22%3A%22get_singer_list%22%2C%22param%22%3A%7B%22area%22%3A-100%2C%22sex%22%3A-100%2C%22genre%22%3A-100%2C%22index%22%3A-100%2C%22sin%22%3A0%2C%22cur_page%22%3A1%7D%7D%7D"

internal fun parseTxHotArtists(raw: String, limit: Int): List<HotArtist> = try {
    if (raw.isBlank()) emptyList() else {
        val list = JSONObject(raw).optJSONObject("singerList")?.optJSONObject("data")?.optJSONArray("singerlist")
        if (list == null) emptyList() else (0 until minOf(limit, list.length())).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("singer_name").trim()
            if (name.isEmpty()) null
            else HotArtist(name, item.optString("singer_pic").replace("http://", "https://"), item.optString("singer_mid"))
        }
    }
} catch (_: Exception) {
    emptyList()
}

/**
 * 酷我热门歌手：HttpPolicy 对白名单外 http:// fail-closed，故必须走 https（实测 artistlistinfo https 200）。
 */
internal fun kwHotArtistUrl(limit: Int): String =
    "https://artistlistinfo.kuwo.cn/mb.slist?stype=artistlist&category=0&order=hot&pn=0&rn=$limit"

internal const val KW_HOT_ARTIST_REFERER = "https://www.kuwo.cn/"

/** 酷我返回单引号伪 JSON；先严格解析，失败再只替换结构位置的引号。pic 前缀 img1.kuwo.cn/star/starheads/。 */
internal fun parseKwHotArtists(raw: String, limit: Int): List<HotArtist> = try {
    if (raw.isBlank()) emptyList() else {
        val root = try { JSONObject(raw) } catch (_: Exception) { JSONObject(kwPseudoJsonToJson(raw)) }
        val list = root.optJSONArray("artistlist")
        if (list == null) emptyList() else (0 until minOf(limit, list.length())).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val name = decodeKwEntities(item.optString("name")).trim()
            val pic = item.optString("pic").trim()
            if (name.isEmpty()) null
            else HotArtist(name, if (pic.isBlank()) "" else if (pic.startsWith("http")) pic else "https://img1.kuwo.cn/star/starheads/$pic", item.optString("id").takeIf { it != "0" }.orEmpty())
        }
    }
} catch (_: Exception) {
    emptyList()
}

/**
 * 把酷我单引号伪 JSON 转成合法 JSON：只在“结构位置”把 ' 当作字符串边界——
 * 字符串外的 ' 视为开引号；值内的 ' 只有后面紧跟（可有空白）, } ] : 或结尾时才视为闭引号，
 * 否则按普通字符保留（如 `Guns N' Roses`）。字符串内的 " 转义为 \"。已是双引号的字符串原样透传。
 */
internal fun kwPseudoJsonToJson(raw: String): String {
    val out = StringBuilder(raw.length + 16)
    var i = 0
    val n = raw.length
    fun nextSignificant(from: Int): Char? {
        var j = from
        while (j < n && raw[j].isWhitespace()) j++
        return if (j < n) raw[j] else null
    }
    while (i < n) {
        val c = raw[i]
        when (c) {
            '"' -> {
                // 已是合法双引号字符串：原样复制到闭合引号。
                out.append(c); i++
                while (i < n) {
                    val d = raw[i]
                    out.append(d); i++
                    if (d == '\\' && i < n) { out.append(raw[i]); i++; continue }
                    if (d == '"') break
                }
            }
            '\'' -> {
                out.append('"'); i++
                while (i < n) {
                    val d = raw[i]
                    if (d == '\\' && i + 1 < n) {
                        val e = raw[i + 1]
                        if (e == '\'') out.append('\'') else out.append(d).append(e)
                        i += 2; continue
                    }
                    if (d == '\'') {
                        val after = nextSignificant(i + 1)
                        if (after == null || after == ',' || after == '}' || after == ']' || after == ':') { i++; break }
                        out.append('\''); i++; continue
                    }
                    if (d == '"') out.append("\\\"") else out.append(d)
                    i++
                }
                out.append('"')
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

private fun decodeKwEntities(value: String): String =
    if ('&' !in value) value
    else value.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&")
