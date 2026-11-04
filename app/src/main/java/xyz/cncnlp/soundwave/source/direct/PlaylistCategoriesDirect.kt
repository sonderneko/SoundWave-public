package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.source.MusicSourceRegistry

/** Anonymous provider taxonomies, never borrowed from another platform.
 * References: LX musicSdk/{tx,kg,kw,mg}/songList.js; existing provider_playlist_categories.
 * HTTPS is used for LX's historical HTTP taxonomy URLs; no cleartext policy expansion.
 * null means unavailable/malformed; an empty list is a structurally valid empty taxonomy.
 */
internal suspend fun fetchPlaylistCategoryGroupsDirect(source: String): List<Pair<String, List<String>>>? {
    val src = MusicSourceRegistry.lyricPlatformId(source) ?: return null
    if (src == "wy") return fetchWyPlaylistCategoryGroupsDirect()
    val (url, referer) = when (src) {
        "tx" -> "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" +
            java.net.URLEncoder.encode(JSONObject().put("tags", JSONObject()
                .put("module", "playlist.PlaylistAllCategoriesServer")
                .put("method", "get_all_categories")
                .put("param", JSONObject().put("qq", ""))).toString(), "UTF-8") to "https://y.qq.com/"
        "kg" -> "https://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&cdn=cdn&t=5" to "https://www.kugou.com/"
        "kw" -> "https://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576" to "https://www.kuwo.cn/"
        else -> "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release" to "https://music.migu.cn/"
    }
    val raw = platformHttpGet(url, referer, 6000L)
    rememberSquareTaxonomy(src, raw)
    return parsePlaylistCategoryGroups(src, raw)
}

internal fun parsePlaylistCategoryGroups(source: String, raw: String): List<Pair<String, List<String>>>? {
    if (raw.isBlank()) return null
    return try {
        val root = JSONObject(raw)
        val groups = ArrayList<Pair<String, List<String>>>()
        fun add(name: String, items: List<String>) {
            val title = cleanSpace(name)
            val names = items.map(::cleanSpace).filter { it.isNotBlank() }.distinct()
            if (title.isNotBlank() && names.isNotEmpty()) groups.add(title to names)
        }
        fun objects(array: JSONArray?): List<JSONObject> = array?.let { values ->
            (0 until values.length()).mapNotNull { values.optJSONObject(it) }
        }.orEmpty()
        when (source) {
            "tx" -> {
                if (root.optInt("code", -1) != 0) return null
                val tags = root.optJSONObject("tags")
                if (tags != null && tags.optInt("code", -1) != 0) return null
                val data = tags?.optJSONObject("data") ?: root.optJSONObject("data") ?: return null
                val rows = data.optJSONArray("v_group") ?: data.optJSONArray("categories") ?: return null
                for (group in objects(rows)) {
                    val items = group.optJSONArray("v_item") ?: group.optJSONArray("items")
                    add(firstStr(group, "group_name", "categoryGroupName"), objects(items)
                        .filter { it.optInt("usable", 1) != 0 && firstStr(it, "id", "categoryId") != "10000000" }
                        .map { firstStr(it, "name", "categoryName") })
                }
            }
            "kg" -> {
                if (root.optInt("status", -1) != 1) return null
                val tags = root.optJSONObject("data")?.optJSONObject("tagids") ?: return null
                val keys = tags.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    add(key, objects(tags.optJSONObject(key)?.optJSONArray("data")).map { it.optString("name") })
                }
            }
            "kw" -> {
                if (root.has("code") && root.optInt("code", -1) != 200 && root.optInt("code", -1) != 0) return null
                val rows = root.optJSONObject("data")?.optJSONArray("list")
                    ?: root.optJSONArray("data") ?: root.optJSONArray("list") ?: return null
                for (group in objects(rows)) add(group.optString("name"), objects(group.optJSONArray("data")).map { it.optString("name") })
            }
            "mg" -> {
                if (root.optString("code") != "000000") return null
                val rows = root.optJSONArray("data") ?: return null
                for (group in objects(rows)) add(firstStr(group.optJSONObject("header") ?: JSONObject(), "title", "text", "name"),
                    objects(group.optJSONArray("content")).map { it.optJSONArray("texts")?.optString(0).orEmpty() })
            }
            else -> return null
        }
        groups
    } catch (_: Exception) { null }
}
