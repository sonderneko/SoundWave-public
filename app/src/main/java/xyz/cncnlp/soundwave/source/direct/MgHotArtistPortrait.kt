package xyz.cncnlp.soundwave.source.direct

import java.net.URLEncoder
import org.json.JSONObject
import xyz.cncnlp.soundwave.Song

/** Only verified current-platform chart identities; never search by an ambiguous artist name. */
internal fun mgHotArtistResourceIds(songs: List<Song>, limit: Int): List<String> = songs.asSequence()
    .filter { it.source.equals("mg", true) && it.id.isNotBlank() }
    .map { it.id }.distinct().take(limit.coerceIn(0, HOT_ARTIST_LIMIT) * 2).toList()

internal suspend fun fetchMgChartArtists(songs: List<Song>, limit: Int): List<HotArtist> {
    val ids = mgHotArtistResourceIds(songs, limit)
    if (ids.isEmpty()) return emptyList()
    val url = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2&resourceId=" +
        URLEncoder.encode(ids.joinToString("|"), "UTF-8")
    return parseMgHotArtists(platformHttpGet(url, "https://music.migu.cn/"), ids.toSet(), limit)
}

/** singerImg is keyed by artist ID; albumImgs is deliberately never a portrait source. */
internal fun parseMgHotArtists(raw: String, requestedIds: Set<String>, limit: Int): List<HotArtist> = try {
    val root = JSONObject(raw)
    val out = LinkedHashMap<String, HotArtist>()
    if (root.optString("code") == "000000") {
        val resources = root.optJSONArray("resource")
        if (resources != null) for (i in 0 until resources.length()) {
            val item = resources.optJSONObject(i) ?: continue
            if (listOf("songId", "contentId", "copyrightId").none { item.optString(it) in requestedIds }) continue
            val artists = item.optJSONArray("artists") ?: continue
            val portraits = item.optJSONObject("singerImg")
            for (a in 0 until artists.length()) {
                val artist = artists.optJSONObject(a) ?: continue
                val id = artist.optString("id").trim()
                val name = artist.optString("name").trim()
                if (id.isBlank() || id == "0" || name.isBlank()) continue
                val portrait = portraits?.optJSONObject(id)
                // Matching ID is necessary; inconsistent labels fail closed rather than borrowing a namesake.
                val images = portrait?.takeIf { it.optString("singerName").trim() == name }?.optJSONArray("miguImgItems")
                var avatar = ""
                var size = ""
                if (images != null) for (n in 0 until images.length()) {
                    val image = images.optJSONObject(n) ?: continue
                    val candidate = image.optString("img").trim().replace("http://", "https://")
                    val path = candidate.substringBefore('?').substringAfterLast('/')
                    val valid = candidate.startsWith("https://") &&
                        (!path.contains('.') || path.substringAfterLast('.').lowercase() in setOf("webp", "jpg", "jpeg", "png"))
                    if (valid && image.optString("imgSizeType") >= size) {
                        avatar = candidate
                        size = image.optString("imgSizeType")
                    }
                }
                if (out[id]?.avatar.isNullOrBlank()) out[id] = HotArtist(name, avatar, id)
            }
        }
    }
    out.values.take(limit.coerceAtLeast(0))
} catch (_: Exception) { emptyList() }
