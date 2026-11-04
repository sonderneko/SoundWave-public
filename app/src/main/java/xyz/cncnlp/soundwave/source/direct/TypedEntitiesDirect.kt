package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.Song
import java.net.URLEncoder

/** Official entity buckets, using the existing anonymous routes (WY cloudsearch,
 * LX/Sollin KW ft=album, public KG v3 and MG search_all used by existing search).
 * Do not turn song IDs into artist/album IDs or borrow another provider's entities.
 */
internal suspend fun searchOfficialEntitiesDirect(source: String, keyword: String, type: String): TxTypedSearchResult {
    val artist = type in listOf("artist", "singer")
    if (!artist && type != "album") return TxTypedSearchResult(emptyList(), false)
    val text = URLEncoder.encode(keyword, "UTF-8")
    val (url, referer) = when (source) {
        "wy" -> "https://music.163.com/api/cloudsearch/pc?s=$text&type=${if (artist) 100 else 10}&limit=30&offset=0" to "https://music.163.com/"
        "kw" -> "https://search.kuwo.cn/r.s?all=$text&ft=${if (artist) "artist" else "album"}&itemset=web_2013&client=kt&cluster=0&pn=0&rn=30&rformat=json&encoding=utf8&vipver=1" to "https://www.kuwo.cn/"
        "kg" -> if (artist) "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$text&page=1&pagesize=30&showtype=14" to "https://m.kugou.com/"
            else "http://mobilecdn.kugou.com/api/v3/search/album?keyword=$text&page=1&pagesize=30&showtype=10&plat=2&version=7910&sver=5" to "https://m.kugou.com/"
        "mg" -> "https://app.c.nf.migu.cn/MIGUM3.0/v1.0/content/search_all.do?text=$text&pageNo=1&pageSize=30&searchSwitch=${URLEncoder.encode(if (artist) "{singer:1}" else "{album:1}", "UTF-8")}" to "https://music.migu.cn/"
        else -> return TxTypedSearchResult(emptyList(), false)
    }
    return parseOfficialEntities(source, platformHttpGet(url, referer), keyword, type)
}

internal fun parseOfficialEntities(source: String, raw: String, keyword: String, type: String): TxTypedSearchResult {
    val artist = type in listOf("artist", "singer")
    if (!artist && type != "album") return TxTypedSearchResult(emptyList(), false)
    return try {
        val root = if (source == "kw") parseKwJson(raw) else JSONObject(raw)
        val rows: JSONArray = when (source) {
            "wy" -> {
                if (root.optInt("code", -1) != 200) return TxTypedSearchResult(emptyList(), false)
                root.optJSONObject("result")?.optJSONArray(if (artist) "artists" else "albums")
            }
            "kw" -> root.optJSONArray(if (artist) "abslist" else "albumlist")
            "kg" -> {
                if (root.optInt("errcode", 0) != 0) return TxTypedSearchResult(emptyList(), false)
                if (artist) root.optJSONObject("relative")?.optJSONArray("singer")
                else root.optJSONObject("data")?.let { it.optJSONArray("albuminfo") ?: it.optJSONArray("info") }
            }
            "mg" -> {
                if (root.has("code") && root.optString("code") !in listOf("000000", "0", "200")) return TxTypedSearchResult(emptyList(), false)
                root.optJSONObject(if (artist) "singerResultData" else "albumResultData")?.optJSONArray("result")
            }
            else -> null
        } ?: return TxTypedSearchResult(emptyList(), false)
        val songs = (0 until minOf(rows.length(), 30)).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = when (source) {
                "kw" -> if (artist) firstStr(item, "ARTISTID", "DC_TARGETID") else firstStr(item, "albumid", "DC_TARGETID")
                "kg" -> if (artist) firstStr(item, "singerid") else firstStr(item, "albumid", "album_id")
                else -> firstStr(item, "id")
            }
            val name = cleanSpace(when (source) {
                "kw" -> if (artist) firstStr(item, "ARTIST", "artist") else firstStr(item, "name", "title")
                "kg" -> if (artist) item.optString("singername") else item.optString("albumname")
                else -> item.optString("name")
            })
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            var cover = when (source) {
                "wy" -> firstStr(item, "picUrl", "img1v1Url", "blurPicUrl")
                "kw" -> if (artist) firstStr(item, "hts_PICPATH", "PICPATH") else firstStr(item, "hts_img", "img", "pic")
                "kg" -> item.optString("imgurl").replace("{size}", "1080")
                else -> {
                    // Preserve imgItems when supplied by an MG singer entity too.
                    // Read only the selected entity; never borrow song/album artwork.
                    val images = item.optJSONArray("imgItems")
                    (0 until (images?.length() ?: 0)).mapNotNull { images?.optJSONObject(it)?.optString("img") }
                        .filter { xyz.cncnlp.soundwave.mgImgOk(it) }.lastOrNull().orEmpty()
                }
            }
            if (source == "kw" && cover.isNotBlank() && !cover.startsWith("http") && !cover.startsWith("//")) {
                cover = "https://img1.kuwo.cn/star/${if (artist) "starheads" else "albumcover"}/${cover.trimStart('/')}"
            }
            val singer = if (artist) name else cleanSpace(if (item.opt("artist") is JSONObject) {
                item.optJSONObject("artist")?.optString("name").orEmpty()
            } else firstStr(item, "artist", "aartist", "singername", "singer"))
            Song(id = id, name = name, singer = singer, source = source, cover = shrinkCover(cover),
                album = if (artist) "" else name, albumId = if (artist) "" else id)
        }.distinctBy { it.id }
        val query = keyword.trim().lowercase()
        TxTypedSearchResult(songs.sortedBy { val name = it.name.lowercase(); if (name == query) 0 else if (name.contains(query)) 1 else 2 }, true)
    } catch (_: Exception) { TxTypedSearchResult(emptyList(), false) }
}
