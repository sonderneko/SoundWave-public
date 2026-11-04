package xyz.cncnlp.soundwave

import org.json.JSONObject
import xyz.cncnlp.soundwave.source.direct.orChain
import xyz.cncnlp.soundwave.source.direct.PlatformTransportOutcome
import xyz.cncnlp.soundwave.source.direct.PlatformTransportError
import xyz.cncnlp.soundwave.source.direct.PlatformTransportErrorKind

/** Only managed playlist-search/recommend envelopes; not song/album/platform signing. */
internal object PlaylistCatalogPayloads {
    fun legacy(raw: String, source: String): List<Playlist> {
        if (raw.isEmpty()) return emptyList()
        return try {
            val d = JSONObject(raw)
            val data = d.optJSONObject("data") ?: d.optJSONObject("result")
            val arr = d.optJSONArray("list") ?: d.optJSONArray("playlists") ?: d.optJSONArray("result") ?: d.optJSONArray("records")
                ?: data?.optJSONArray("list") ?: data?.optJSONArray("playlists") ?: data?.optJSONArray("result") ?: data?.optJSONArray("records") ?: return emptyList()
            val out = ArrayList<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = orChain(o, "id", "pid", "playlistId", "playlist_id", "mid", "rid")
                if (id.isEmpty()) continue
                val src = canonicalSourceId(orChain(o, "src", "source", "platform").ifEmpty { source })
                val requestedSource = canonicalSourceId(source)
                // 指定平台时只接受同源实体；空来源仅在请求本身已明确平台时补位。
                if (requestedSource.isNotEmpty() && src != requestedSource) continue
                val playlistText: (String) -> String = ::decodePlaylistMetadata
                out.add(
                    Playlist(
                        id = id,
                        name = playlistText(orChain(o, "name", "nm", "title", "playlistName")).ifEmpty { "歌单" },
                        cover = normalizeCoverUrl(orChain(o, "cover", "pic", "cv", "picUrl", "img")),
                        songs = mutableListOf(),
                        source = src,
                        author = playlistText(orChain(o, "creator", "author")),
                        description = playlistText(orChain(o, "description", "desc")),
                        count = o.optInt("count", o.optInt("trackCount", 0)),
                        playCount = o.optLong("playCount", o.optLong("playcount", o.optLong("play_count", o.optLong("playNum", 0L))))
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }


    fun validated(raw: String, source: String, checkNestedFailure: Boolean): PlatformTransportOutcome<List<Playlist>> {
        if (raw.isBlank()) return failure(PlatformTransportErrorKind.PARSE)
        return try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root.optJSONObject("result")
            val checked = if (checkNestedFailure) listOfNotNull(root, data) else listOf(root)
            if (checked.any { (it.has("ok") && !it.optBoolean("ok")) ||
                    (it.has("success") && !it.optBoolean("success")) }) {
                return failure(PlatformTransportErrorKind.REMOTE_FAILURE)
            }
            val hasArray = listOfNotNull(root, data).any { obj ->
                listOf("list", "playlists", "result", "records").any { obj.optJSONArray(it) != null }
            }
            if (!hasArray) failure(PlatformTransportErrorKind.PARSE)
            else PlatformTransportOutcome.Success(legacy(raw, source))
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(PlatformTransportErrorKind.PARSE)
        }
    }

    private fun failure(kind: PlatformTransportErrorKind) =
        PlatformTransportOutcome.Failure(PlatformTransportError(kind))
}
