package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.decodePlaylistMetadata
import xyz.cncnlp.soundwave.normalizeCoverUrl

/** Actual provider metadata only: KW pl.svc; KG mobilecdn special/info;
 * MG LX songList.getListDetailInfo. Missing fields remain empty, never first-song cover.
 */
internal fun parseAdditionalPlaylistMetadata(source: String, raw: String): DirectPlaylistDetail = try {
    val root = JSONObject(raw)
    val data = if (source == "kw") root else root.optJSONObject("data") ?: JSONObject()
    val name = when (source) { "kw" -> firstStr(data, "title", "name"); "kg" -> data.optString("specialname"); "mg" -> data.optString("title"); else -> "" }
    val cover = when (source) { "kw" -> firstStr(data, "pic", "img"); "kg" -> data.optString("imgurl").replace("{size}", "1080"); "mg" -> data.optJSONObject("imgItem")?.optString("img").orEmpty(); else -> "" }
    val author = when (source) { "kw" -> firstStr(data, "uname", "userName"); "kg" -> data.optString("nickname"); "mg" -> data.optString("ownerName"); else -> "" }
    val description = when (source) { "kw" -> firstStr(data, "info", "desc"); "kg" -> data.optString("intro"); "mg" -> data.optString("summary"); else -> "" }
    val count = when (source) { "kw" -> data.optInt("total"); "kg" -> data.optInt("songcount"); else -> 0 }
    DirectPlaylistDetail(emptyList(), decodePlaylistMetadata(name), normalizeCoverUrl(cover),
        decodePlaylistMetadata(author), decodePlaylistMetadata(description), count.coerceAtLeast(0))
} catch (_: Exception) { DirectPlaylistDetail(emptyList()) }
