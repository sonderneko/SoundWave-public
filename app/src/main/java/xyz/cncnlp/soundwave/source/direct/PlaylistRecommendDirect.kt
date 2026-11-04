package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import java.net.URLEncoder

/** Official squares from local LX songList.js, not keyword search disguised as recommendations.
 * Returns null for unavailable/malformed; valid-empty remains separate. Public HTTPS variants
 * of historical KG/KW HTTP routes do not expand the transport's cleartext allowlist.
 */
internal suspend fun fetchRecommendPlaylistsDirect(source: String, limit: Int): List<Playlist>? {
    val src = MusicSourceRegistry.lyricPlatformId(source) ?: return null
    val size = limit.coerceIn(1, 50)
    if (src == "wy") {
        val raw = platformHttpGet("https://music.163.com/api/playlist/list?cat=${URLEncoder.encode("热门", "UTF-8")}&order=hot&limit=$size&offset=0", "https://music.163.com/")
        return parseRecommendPlaylistSquare(src, raw)?.take(size)
    }
    if (src == "kg") {
        val out = ArrayList<Playlist>()
        var valid = false
        for (page in 1..3) {
            val raw = platformHttpGet("https://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=5&c=&p=$page", "https://www.kugou.com/")
            val batch = parseRecommendPlaylistSquare(src, raw) ?: break
            valid = true
            out.addAll(batch)
            if (batch.isEmpty() || out.distinctBy { it.id }.size >= size) break
        }
        return if (valid) out.distinctBy { it.id }.take(size) else null
    }
    val (url, referer) = when (src) {
        "tx" -> {
            val request = JSONObject().put("comm", JSONObject().put("cv", 1602).put("ct", 20))
                .put("playlist", JSONObject().put("module", "playlist.PlayListPlazaServer").put("method", "get_playlist_by_tag")
                    .put("param", JSONObject().put("id", 10000000).put("sin", 0).put("size", size).put("order", 5).put("cur_page", 1)))
            "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${URLEncoder.encode(request.toString(), "UTF-8")}" to "https://y.qq.com/"
        }
        "kw" -> "https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=1&rn=$size&order=hot" to "https://www.kuwo.cn/"
        else -> "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=1" to "https://music.migu.cn/"
    }
    val rows = parseRecommendPlaylistSquare(src, platformHttpGet(url, referer))?.take(size) ?: return null
    // LX kw/songList.js digest 5 IDs are nodes, not pl.svc playlist IDs.
    return if (src == "kw") resolveKwRecommendationNodes(rows) { node ->
        parseKwRecommendationSourceId(platformHttpGet(
            "https://qukudata.kuwo.cn/q.k?op=query&cont=ninfo&node=${URLEncoder.encode(node, "UTF-8")}&pn=0&rn=1&fmt=json&src=mbox&level=2",
            "https://www.kuwo.cn/"))
    } else rows
}

/** Resolve before publishing cards, so both direct detail and retained server fallback receive sourceid. */
internal suspend fun resolveKwRecommendationNodes(
    rows: List<Playlist>,
    resolve: suspend (String) -> String?,
): List<Playlist>? {
    val out = ArrayList<Playlist>()
    for (row in rows) {
        val detailId = resolve(row.remoteId)?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        out.add(row.copy(id = detailId, remoteId = detailId))
    }
    return out.distinctBy { it.id }
}

/** LX reads child[0].sourceid only; absent/empty/malformed child is unavailable, never the node itself. */
internal fun parseKwRecommendationSourceId(raw: String): String? = try {
    val child = JSONObject(raw).optJSONArray("child")?.optJSONObject(0)
    child?.let { recommendationField(it, "sourceid") }?.takeIf { it.isNotBlank() }
} catch (_: Exception) { null }

private fun recommendationField(item: JSONObject, key: String): String = when (val value = item.opt(key)) {
    is String -> value.takeUnless { it == "null" }.orEmpty()
    is Number -> value.toString()
    else -> ""
}

internal fun parseRecommendPlaylistSquare(source: String, raw: String): List<Playlist>? {
    return try {
    val root = JSONObject(raw)
    val out = ArrayList<Playlist>()
    fun add(id: String, name: String, cover: String, author: String = "", count: Int = 0, description: String = "") {
        require(id.isNotBlank() && id != "null" && cleanSpace(name).isNotBlank() && name != "null")
        out.add(Playlist(id = id, remoteId = id, source = source, name = cleanSpace(name),
            cover = shrinkCover(cover, 300), author = cleanSpace(author), count = count.coerceAtLeast(0), description = cleanSpace(description)))
    }
    // A non-object/missing required field is malformed, not a successful-empty square.
    fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
    when (source) {
        "wy" -> {
            if (root.optInt("code", -1) != 200) return null
            val rows = root.optJSONArray("playlists") ?: return null
            for (item in objects(rows)) {
                require(recommendationField(item, "id").isNotBlank() &&
                    cleanSpace(recommendationField(item, "name")).isNotBlank())
            }
            return parseWyPlaylistSquare(raw).take(50)
        }
        "tx" -> {
            val block = root.optJSONObject("playlist")
            if (root.optInt("code", -1) != 0 || block?.optInt("code", -1) != 0) return null
            val rows = block?.optJSONObject("data")?.optJSONArray("v_playlist") ?: return null
            for (item in objects(rows)) add(recommendationField(item, "tid"), recommendationField(item, "title"), firstStr(item, "cover_url_big", "cover_url_medium"),
                item.optJSONObject("creator")?.optString("name").orEmpty().ifBlank { item.optString("creator_name") }, item.optInt("song_cnt"))
        }
        "kg" -> {
            if (root.optInt("status", -1) != 1) return null
            val rows = root.optJSONArray("special_db") ?: return null
            for (item in objects(rows)) add(recommendationField(item, "specialid"), recommendationField(item, "specialname"), item.optString("img"), item.optString("nickname"), item.optInt("song_count"))
        }
        "kw" -> {
            if (root.optInt("code", -1) != 200) return null
            val rows = root.optJSONObject("data")?.optJSONArray("data") ?: return null
            // Non-playlist digests need separate album/rank handlers; never send them to playlist detail.
            for (item in objects(rows)) {
                val digest = recommendationField(item, "digest")
                require(digest.isNotBlank())
                if (digest == "5") add(recommendationField(item, "id"), recommendationField(item, "name"), item.optString("img"), item.optString("uname"))
            }
        }
        "mg" -> {
            if (root.optString("code") != "000000") return null
            val data = root.optJSONObject("data") ?: return null
            fun visit(rows: JSONArray, depth: Int) {
                require(depth <= 8)
                for (item in objects(rows)) {
                    val children = item.optJSONArray("contents")
                    if (children != null) visit(children, depth + 1)
                    else {
                        val type = recommendationField(item, "resType")
                        require(type.isNotBlank())
                        if (type == "2021") add(recommendationField(item, "resId"), recommendationField(item, "txt"), item.optString("img"), description = item.optString("txt2"))
                    }
                }
            }
            val rows = data.optJSONArray("contents")
            if (rows != null) visit(rows, 0) else {
                val items = data.optJSONArray("contentItemList")?.optJSONObject(1)?.optJSONArray("itemList") ?: return null
                for (item in objects(items)) add(item.optJSONObject("logEvent")?.let { recommendationField(it, "contentId") }.orEmpty(), recommendationField(item, "title"), item.optString("imageUrl"))
            }
        }
        else -> return null
    }
    out.distinctBy { it.id }.take(50)
    } catch (_: Exception) { null }
}
