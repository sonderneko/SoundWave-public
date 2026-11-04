package xyz.cncnlp.soundwave

import java.net.URI
import java.net.URLDecoder
import xyz.cncnlp.soundwave.SchemeActionHolder.Action
import xyz.cncnlp.soundwave.source.direct.parseOnlineImportReference

/** Offline system-share routing: typed playlist/album -> existing import dialog;
 * explicit songs -> play, titled song shares -> search; all other URLs -> notice.
 * No redirect fetching and no legacy server text-parser fallback.
 */
internal object ShareLinkParser {
    const val MAX_SHARE_TEXT = 2000
    private const val PLATFORM_HINT = "目前支持网易云、QQ 音乐、酷狗、酷我、咪咕的歌单、专辑长链接或单曲分享链接"

    private val urlPattern = Regex("""https?://[^\s<>"“”'（）()《》「」，。！]+""", RegexOption.IGNORE_CASE)
    private val titlePattern = Regex("""《([^》\n]{1,100})》""")
    private val sharerSingerPattern = Regex("分享(.{1,40}?)的(?:单曲|歌曲)")

    fun platformName(source: String): String = when (source) {
        "wy" -> "网易云音乐"; "tx" -> "QQ 音乐"; "kg" -> "酷狗音乐"; "kw" -> "酷我音乐"; "mg" -> "咪咕音乐"; else -> source
    }

    fun parse(text: String?, subject: String? = null): Action {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Action.None
        if (raw.length > MAX_SHARE_TEXT) return Action.Notice("分享内容过长，无法识别。$PLATFORM_HINT")
        if (urlPattern.findAll(raw).count() > 1) return Action.Notice("请一次只分享一个歌单、专辑或单曲链接")
        val link = urlPattern.find(raw)?.value?.trimEnd('.', ',', ';', ':', '!', '?', '）', ')')
            ?: return Action.Notice("分享内容里没有找到链接。$PLATFORM_HINT")
        val uri = try { URI(link) } catch (_: Exception) { return Action.Notice("分享链接格式无效。$PLATFORM_HINT") }
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 80, 443)) return Action.Notice("分享链接格式无效。$PLATFORM_HINT")
        val host = uri.host?.lowercase() ?: return Action.Notice("分享链接格式无效。$PLATFORM_HINT")
        val source = platformOf(host) ?: return Action.Notice("暂不支持该链接（$host）。$PLATFORM_HINT")
        val context = listOfNotNull(subject, raw).joinToString(" ")

        parseOnlineImportReference(raw)?.let { return Action.ImportPlaylist(link, it.source) }

        val route = (uri.rawFragment?.takeIf { it.startsWith("/") } ?: ((uri.rawPath ?: "") + "?" + (uri.rawQuery ?: "")))
        val rawPath = route.substringBefore('?')
        val path = rawPath.lowercase()
        val query = route.substringAfter('?', "")
        fun param(key: String): String? = query.split('&')
            .firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.trim()?.takeIf { it.isNotEmpty() }

        val title = titlePattern.find(context)?.groupValues?.get(1)?.trim().orEmpty()
        val singer = sharerSingerPattern.find(context)?.groupValues?.get(1)?.trim().orEmpty()

        songIdOf(source, rawPath, ::param)?.let { id ->
            if (SchemeActionHolder.isValidSongId(id)) {
                return Action.Play(id, source, SchemeActionHolder.cleanText(title).orEmpty(), SchemeActionHolder.cleanText(singer).orEmpty())
            }
        }
        if (isAlbumOrArtist(path)) return Action.Notice("无法识别该${platformName(source)}专辑，或暂不支持歌手链接；请使用专辑或歌单长链接")

        val looksLikeSong = isSongPath(source, path) ||
            (("单曲" in context || "歌曲" in context) && "歌单" !in context)
        if (looksLikeSong) {
            val keyword = listOf(title, singer).filter { it.isNotBlank() }.joinToString(" ")
            val safe = SchemeActionHolder.cleanText(keyword)
            return if (!safe.isNullOrBlank()) Action.Search(safe)
            else Action.Notice("暂不支持直接打开该${platformName(source)}单曲链接，请在 SoundWave 中搜索歌名")
        }
        return Action.Notice("短链或未识别的链接暂不支持导入，请在${platformName(source)}中复制歌单或专辑完整长链接")
    }

    internal fun platformOf(host: String): String? {
        fun domain(name: String) = host == name || host.endsWith(".$name")
        return when {
            domain("music.163.com") || domain("163cn.tv") || domain("163.fm") || domain("163cn.link") -> "wy"
            domain("y.qq.com") || host == "url.cn" -> "tx"
            domain("kugou.com") -> "kg"
            domain("kuwo.cn") -> "kw"
            domain("migu.cn") -> "mg"
            else -> null
        }
    }

    /** [rawPath] 保留原始大小写（QQ songmid 大小写敏感）；匹配时忽略大小写。 */
    private fun songIdOf(source: String, rawPath: String, param: (String) -> String?): String? {
        val path = rawPath.lowercase()
        fun group(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).find(rawPath)?.groupValues?.get(1)
        return when (source) {
            "wy" -> if (path.endsWith("/song") || path.endsWith("/song/")) param("id") else null
            "tx" -> group("/songDetail/([A-Za-z0-9]{6,32})")
                ?: if (path.endsWith("/playsong.html") || path.endsWith("/songdetail")) param("songmid") else null
            "kw" -> group("/play_detail/([0-9]{1,20})")
                ?: if (path.contains("/songinfoandlrc") || path.contains("/yinyue")) param("musicId") ?: param("rid") else null
            else -> null
        }
    }

    private fun isSongPath(source: String, path: String): Boolean = when (source) {
        "kg" -> path.contains("/song.html") || path.contains("/mixsong") || path.contains("/share/song")
        "mg" -> path.contains("/song/") || path.endsWith("/song")
        "wy", "tx", "kw" -> path.contains("/song") || path.contains("play_detail") || path.contains("playsong")
        else -> false
    }

    private fun isAlbumOrArtist(path: String): Boolean =
        listOf("/album", "/artist", "/singer").any { path.contains(it) } && !path.contains("/playlist")
}
