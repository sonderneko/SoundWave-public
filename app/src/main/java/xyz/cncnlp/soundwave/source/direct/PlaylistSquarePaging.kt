package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal data class PlaylistCategoryRef(val group: String, val name: String, val id: String, val kind: String = "")
private val squareTaxonomies = ConcurrentHashMap<String, List<PlaylistCategoryRef>>()
internal fun rememberSquareTaxonomy(source: String, raw: String) {
    parseSquareCategoryRefs(source, raw)?.let { squareTaxonomies[source] = it }
}
internal fun parseSquareCategoryRefs(source: String, raw: String): List<PlaylistCategoryRef>? = try {
    val root = JSONObject(raw)
    val out = mutableListOf<PlaylistCategoryRef>()
    when (source) {
        "tx" -> {
            if (root.optInt("code", -1) != 0 || root.optJSONObject("tags")?.optInt("code", -1) != 0) null
            else {
                val rows = root.getJSONObject("tags").getJSONObject("data").getJSONArray("v_group")
                for (i in 0 until rows.length()) {
                    val group = rows.getJSONObject(i)
                    val items = group.getJSONArray("v_item")
                    for (j in 0 until items.length()) { val item = items.getJSONObject(j)
                        if (item.optInt("usable", 1) != 0) out += PlaylistCategoryRef(group.getString("group_name"), item.getString("name"), item.get("id").toString())
                    }
                }; out
            }
        }
        "kw" -> {
            if (root.optInt("code", -1) != 200) null else {
                val rows = root.getJSONArray("data")
                for (i in 0 until rows.length()) { val group = rows.getJSONObject(i); val items = group.getJSONArray("data")
                    for (j in 0 until items.length()) { val item = items.getJSONObject(j)
                        out += PlaylistCategoryRef(group.getString("name"), item.getString("name"), item.get("id").toString(), item.getString("digest"))
                    }
                }; out
            }
        }
        "mg" -> {
            if (root.optString("code") != "000000") null else {
                val rows = root.getJSONArray("data")
                for (i in 0 until rows.length()) {
                    val group = rows.getJSONObject(i)
                    val title = group.getJSONObject("header").getString("title")
                    val items = group.getJSONArray("content")
                    for (j in 0 until items.length()) {
                        val texts = items.getJSONObject(j).getJSONArray("texts")
                        out += PlaylistCategoryRef(title, texts.getString(0), texts.getString(1))
                    }
                }; out
            }
        }
        else -> null
    }
} catch (_: Exception) { null }

/** Category names are labels, not search terms. Keep provider IDs and Kuwo digest type. */
internal suspend fun fetchNativePlaylistSquare(source: String, group: String, name: String, page: Int): PlaylistSearchOutcome? {
    if (source !in setOf("wy", "tx", "kw", "mg")) return null
    val number = page.coerceAtLeast(1)
    if (source == "wy") {
        val raw = platformHttpGet("https://music.163.com/api/playlist/list?cat=${URLEncoder.encode(name, "UTF-8")}&order=hot&limit=30&offset=${(number - 1L) * 30}", "https://music.163.com/", 8000L)
        return parseOtherNativePlaylistSquare(source, raw)
    }
    if (name != "全部" && squareTaxonomies[source] == null) fetchPlaylistCategoryGroupsDirect(source)
    val ref = if (name == "全部") PlaylistCategoryRef("", name, if (source == "tx") "10000000" else "", "10000")
    else squareTaxonomies[source].orEmpty().filter { it.name == name && (group == "全部" || it.group == group) }
        .distinctBy { it.id to it.kind }.singleOrNull() ?: return PlaylistSearchOutcome.Failure
    val pn = number
    if (source == "mg") {
        val url = if (name == "全部") "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=$pn"
            else "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-listbytag/release?pageNumber=$pn&templateVersion=2&tagId=${URLEncoder.encode(ref.id, "UTF-8")}"
        return parseOtherNativePlaylistSquare(source, platformHttpGet(url, "https://music.migu.cn/", 8000L))
    }
    val url: String
    val size = 30
    if (source == "tx") {
        val params = if (name == "全部") JSONObject().put("id", 10000000).put("sin", (pn - 1) * size).put("size", size).put("order", 5).put("cur_page", pn)
            else JSONObject().put("titleid", ref.id.toLongOrNull() ?: return PlaylistSearchOutcome.Failure)
                .put("caller", "0").put("category_id", ref.id.toLong()).put("size", size).put("page", pn - 1).put("use_page", 1)
        val args = JSONObject().put("playlist", JSONObject().put("module", if (name == "全部") "playlist.PlayListPlazaServer" else "playlist.PlayListCategoryServer")
            .put("method", if (name == "全部") "get_playlist_by_tag" else "get_category_content").put("param", params))
        url = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" + URLEncoder.encode(args.toString(), "UTF-8")
    } else if (ref.kind == "43") {
        if (pn > 1) return PlaylistSearchOutcome.Success(emptyList(), false)
        url = "https://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web&id=${ref.id}&prod=pc"
    } else if (ref.kind == "10000") {
        url = "https://wapi.kuwo.cn/api/pc/classify/playlist/" +
            (if (ref.id.isBlank()) "getRcmPlayList?order=hot" else "getTagPlayList?id=${ref.id}") +
            "&loginUid=0&loginSid=0&appUid=76039576&pn=$pn&rn=$size"
    } else return PlaylistSearchOutcome.Failure
    val raw = platformHttpGet(url, if (source == "tx") "https://y.qq.com/" else "https://www.kuwo.cn/", 8000L)
    return parseNativePlaylistSquare(source, raw, pn, size, ref.kind)
}

internal fun parseNativePlaylistSquare(source: String, raw: String, page: Int, size: Int, kind: String = ""): PlaylistSearchOutcome { return try {
    val out = mutableListOf<Playlist>()
    var total = -1L
    fun add(item: JSONObject, tx: Boolean) {
        val id = if (tx) firstStr(item, "tid", "uin") else item.optString("id")
        val title = if (tx) item.optString("title") else item.optString("name")
        if (id.isBlank() || title.isBlank()) return
        out += Playlist(id = id, remoteId = id, name = cleanSpace(title), source = source,
            cover = if (tx) item.optJSONObject("cover")?.let { firstStr(it, "big_url", "medium_url", "default_url") }.orEmpty().ifEmpty { firstStr(item, "cover_url_big", "cover_url_medium") } else item.optString("img"),
            author = if (tx) (item.optJSONObject("creator") ?: item.optJSONObject("creator_info"))?.optString("nick").orEmpty() else item.optString("uname"),
            count = if (tx) item.optInt("song_cnt", item.optInt("song_num")) else item.optInt("total"),
            playCount = if (tx) item.optLong("play_cnt", item.optLong("access_num")) else item.optLong("listencnt"))
    }
    if (source == "kw" && kind == "43") {
        // Live get_pc_qz_data is an array of sections with `list` children, not
        // the paged tag endpoint's object envelope. Retain legacy wrappers too.
        val rows = if (raw.trimStart().startsWith("[")) JSONArray(raw) else {
            val zone = JSONObject(raw)
            zone.optJSONArray("data") ?: zone.optJSONArray("child") ?: return PlaylistSearchOutcome.Failure
        }
        fun visit(items: JSONArray, depth: Int) {
            require(depth <= 12)
            for (i in 0 until items.length()) {
                val row = items.getJSONObject(i)
                // Albums (13), songs and navigation nodes are not playlists.
                if (row.optString("digest") == "8") add(row, false)
                for (key in listOf("list", "child", "data")) {
                    row.optJSONArray(key)?.let { visit(it, depth + 1) }
                }
            }
        }
        visit(rows, 0)
        return PlaylistSearchOutcome.Success(out.distinctBy { it.id }, false)
    }
    val root = JSONObject(raw)
    if (source == "tx") {
        if (root.optInt("code", -1) != 0 || root.optJSONObject("playlist")?.optInt("code", -1) != 0) return PlaylistSearchOutcome.Failure
        val payload = root.getJSONObject("playlist").getJSONObject("data")
        val data = payload.optJSONObject("content") ?: payload
        total = data.optLong("total_cnt", data.optLong("total", -1L))
        val rows = data.optJSONArray("v_item") ?: data.getJSONArray("v_playlist")
        for (i in 0 until rows.length()) { val item = rows.getJSONObject(i); add(item.optJSONObject("basic") ?: item, true) }
    } else {
        if (root.optInt("code", -1) != 200) return PlaylistSearchOutcome.Failure
        val data = root.getJSONObject("data"); total = data.optLong("total", -1L)
        val rows = data.getJSONArray("data")
        for (i in 0 until rows.length()) add(rows.getJSONObject(i), false)
    }
    PlaylistSearchOutcome.Success(out.distinctBy { it.id }, if (kind == "43") false else if (total >= 0L) page.toLong() * size < total else out.isNotEmpty())
} catch (_: Exception) { PlaylistSearchOutcome.Failure }
}

/** NetEase exposes more; Migu tag pages expose nextPageUrl, recommendation pages end on an empty page. */
internal fun parseOtherNativePlaylistSquare(source: String, raw: String): PlaylistSearchOutcome = try {
    val root = JSONObject(raw)
    // An explicitly empty Migu page is a valid end, not an absent/malformed payload.
    // Nonempty contentItemList still goes through strict recommendation item validation.
    val emptyMiguPage = source == "mg" && root.optString("code") == "000000" &&
        root.optJSONObject("data")?.optJSONArray("contentItemList")?.length() == 0
    val rows = if (emptyMiguPage) emptyList() else parseRecommendPlaylistSquare(source, raw)
    if (rows == null) PlaylistSearchOutcome.Failure else {
        val more = if (source == "wy") {
            if (root.has("more")) root.optBoolean("more") else rows.isNotEmpty()
        } else if (emptyMiguPage) false
        else root.optJSONObject("data")?.optString("nextPageUrl").orEmpty().isNotBlank() || rows.isNotEmpty()
        PlaylistSearchOutcome.Success(rows, more)
    }
} catch (_: Exception) { PlaylistSearchOutcome.Failure }
