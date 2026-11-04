package xyz.cncnlp.soundwave.source.direct

import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Request
import xyz.cncnlp.soundwave.AppHttp
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song

/**
 * r658c D#3：平台 App「分享」给出的几乎都是短链（163cn.tv / c6.y.qq.com / url.cn / t1.kugou.com …），
 * 旧实现离线解析直接拒绝 → 用户粘贴分享文本「导入失败」。这里是独立、有界、白名单的短链展开器：
 * 只对白名单短链主机发请求，不跟随自动重定向，逐跳读取 Location（≤5 跳），每一跳都必须仍在音乐平台域内，
 * 一旦某跳能被 [parseOnlineImportReference] 严格解析就返回它；不读正文、不猜 ID。
 */
internal object OnlineImportShortLink {
    private val shortHosts = setOf(
        "163cn.tv", "163cn.link", "url.cn", "c6.y.qq.com", "c.y.qq.com",
        "t1.kugou.com", "t.kugou.com", "m.kuwo.cn", "kuwo.cn", "www.kuwo.cn", "c.migu.cn", "music.migu.cn"
    )
    private val allowedSuffixes = listOf("163.com", "163cn.tv", "163cn.link", "qq.com", "url.cn", "kugou.com", "kuwo.cn", "migu.cn")

    internal fun shortLinkOf(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2000) return null
        val links = Regex("""https?://[^\s<>"“”'（）()《》「」，。！]+""", RegexOption.IGNORE_CASE).findAll(text).toList()
        if (links.size != 1) return null
        val link = links.single().value.trimEnd('.', ',', ';', ':', '!', '?', ')', '）')
        val host = runCatching { URI(link).host?.lowercase() }.getOrNull() ?: return null
        return link.takeIf { host in shortHosts }
    }

    internal fun allowedHop(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        (uri.scheme == "https" || uri.scheme == "http") && uri.rawUserInfo == null && uri.port in listOf(-1, 80, 443) &&
            allowedSuffixes.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    /** 返回可被严格解析的长链；无法展开返回 null。阻塞网络调用，须在 IO 线程。 */
    fun expand(raw: String): String? {
        if (parseOnlineImportReference(raw) != null) return null
        var current = shortLinkOf(raw) ?: return null
        val client = AppHttp.shared.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS).build()
        repeat(5) {
            if (!allowedHop(current)) return null
            val location = try {
                client.newCall(Request.Builder().url(current)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile Safari/537.36")
                    .get().build()).execute().use { response ->
                    if (response.code !in 300..399) return null
                    response.header("Location")
                }
            } catch (_: Exception) { null } ?: return null
            val next = runCatching { URI(current).resolve(location.trim()).toString() }.getOrNull() ?: return null
            if (parseOnlineImportReference(next) != null && allowedHop(next)) return next
            current = next
        }
        return null
    }
}

/** 平台别名统一到五个标准来源 ID；空来源按导入引用补齐（详情接口常省略或返回 qq/kugou 等别名）。 */
internal fun canonicalImportSource(raw: String, fallback: String): String = when (raw.trim().lowercase()) {
    "" -> fallback
    "qq", "tx", "tencent", "qqmusic" -> "tx"
    "wy", "netease", "163", "wangyi", "neteasecloud" -> "wy"
    "kg", "kugou" -> "kg"
    "kw", "kuwo" -> "kw"
    "mg", "migu" -> "mg"
    else -> raw.trim().lowercase()
}

/** 导入详情来源规范化：别名/缺省来源不再被当成跨平台结果整单拒绝。 */
internal fun canonicalizeImportDetail(detail: Playlist, source: String): Playlist {
    val songs = detail.songs.map { song: Song ->
        val canonical = canonicalImportSource(song.source, source)
        if (canonical == song.source) song else song.copy(source = canonical).also {
            it.resolvedUrl = song.resolvedUrl; it.actualQuality = song.actualQuality
        }
    }
    return detail.copy(source = canonicalImportSource(detail.source, source), songs = songs.toMutableList())
}
