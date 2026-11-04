package xyz.cncnlp.soundwave.source.direct

import java.net.URI

internal enum class OnlineImportKind { PLAYLIST, ALBUM }
internal data class OnlineImportReference(val source: String, val id: String, val kind: OnlineImportKind)

/** Offline, typed public-link parsing only. Never follow a supplied URL, decode an ID into
 * validity, guess digits from prose, or confuse QQ albumid with albummid. Unknown/short links
 * deliberately fail; expanding redirects needs a separate bounded, allowlisted resolver.
 */
internal fun parseOnlineImportReference(raw: String): OnlineImportReference? {
    val text = raw.trim()
    if (text.isEmpty() || text.length > 2000) return null
    val numeric = Regex("[0-9]{1,20}")
    if (numeric.matches(text)) return OnlineImportReference("wy", text, OnlineImportKind.PLAYLIST)
    val links = Regex("""https?://[^\s<>"“”'（）()《》「」，。！]+""", RegexOption.IGNORE_CASE)
        .findAll(text).toList()
    if (links.size != 1) return null
    return try {
        val uri = URI(links.single().value.trimEnd('.', ',', ';', ':', '!', '?', ')', '）'))
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 80, 443)) return null
        val host = uri.host?.lowercase() ?: return null
        fun domain(name: String) = host == name || host.endsWith(".$name")
        val source = when {
            domain("music.163.com") -> "wy"
            domain("y.qq.com") -> "tx"
            domain("kugou.com") -> "kg"
            domain("kuwo.cn") -> "kw"
            domain("migu.cn") -> "mg"
            else -> return null
        }
        val route = uri.rawFragment?.takeIf { it.startsWith("/") }
            ?: ((uri.rawPath ?: "") + "?" + (uri.rawQuery ?: ""))
        // r658c D#3：移动端分享页路径归一：网易 /m/playlist、/m/album；酷我 /newh5app/… 与 h5app 同构。
        val path = route.substringBefore('?').let { p ->
            when (source) {
                "wy" -> if (p == "/m/playlist" || p == "/m/album") p.removePrefix("/m") else p
                "kw" -> p.replaceFirst("/newh5app/", "/h5app/")
                else -> p
            }
        }
        val query = route.substringAfter('?', "")
        fun param(key: String): String? {
            val values = query.split('&').filter { it.substringBefore('=') == key }
            if (values.size != 1) return null
            return values.single().substringAfter('=', "")
        }
        // Reject conflicting aliases rather than silently picking one.
        fun either(first: String, second: String): String? {
            if (query.split('&').any { it.substringBefore('=') == first }) {
                if (query.split('&').any { it.substringBefore('=') == second }) return null
                return param(first)
            }
            return param(second)
        }
        fun group(pattern: String) = Regex(pattern).matchEntire(path)?.groupValues?.get(1)
        val playlistId = when (source) {
            "wy" -> if (path == "/playlist") param("id") else null
            "tx" -> group("""/(?:n/(?:yqq|ryqq)/)?playlist/([0-9]+)(?:\.html)?/?""")
                ?: if (path.endsWith("/taoge.html")) param("id") else null
            // /songlist/... is a user collection link, NOT a specialid (LX uses a separate resolver).
            "kg" -> group("""/(?:yy/)?special/single/([0-9]+)\.html""")
            "kw" -> group("""/(?:h5app/)?playlist(?:_detail)?/([0-9]+)/?""")
            else -> group("""/(?:v3/music/)?playlist/([0-9]+)/?""")
                ?: if (path.endsWith("/playlist/index.html") || path == "/playlist") either("playlistId", "id") else null
        }
        if (playlistId != null) return playlistId.takeIf(numeric::matches)
            ?.let { OnlineImportReference(source, it, OnlineImportKind.PLAYLIST) }
        val albumId = when (source) {
            "wy" -> if (path == "/album") param("id") else null
            "tx" -> group("""/n/(?:yqq|ryqq)/(?:album|albumDetail)/([A-Za-z0-9]+)(?:\.html)?/?""")
                ?: if (path.endsWith("/album.html")) param("albummid") else null
            "kg" -> group("""/(?:yy/)?album/(?:single/)?([0-9]+)(?:\.html)?/?""")
            "kw" -> group("""/(?:h5app/)?album(?:_detail)?/([0-9]+)/?""")
            else -> group("""/(?:v3/music/)?album/([0-9]+)/?""")
                ?: if (path == "/album" || path.endsWith("/album/index.html")) either("albumId", "id") else null
        }
        val valid = if (source == "tx") albumId?.matches(Regex("[A-Za-z0-9]{14}")) == true && albumId.any { it.isLetter() }
            else albumId?.matches(numeric) == true
        if (valid) OnlineImportReference(source, albumId!!, OnlineImportKind.ALBUM) else null
    } catch (_: Exception) { null }
}
