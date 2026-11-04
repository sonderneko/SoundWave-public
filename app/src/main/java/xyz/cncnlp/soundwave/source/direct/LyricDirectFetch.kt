package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.LyricTextBundle
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.encodeLyricTextBundle
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

/**
 * 在线歌词五平台客户端直连（Local-first 批14，批11 Phase2-③）。
 * 逐平台移植服务端 /music/lyric（/opt/soundwave/music_server.py:3222 handler）→
 * fetch_lyric（:1192）按 source 分派的主链（关键词兜底链除外，见下）：
 * - wy（:1200-1224）：music.163.com/api/song/lyric 两级——先 yv=1&ytv=1 取词级 yrc
 *   （须含 [ 与 (，客户端可真逐字），失败再单独取普通 lrc；同响应可选 tlyric/romalrc
 * - kw（:1225-1258）：wapi.kuwo.cn openapi/v1/www/lyric（lrclist 数组→[mm:ss.ss] 行，
 *   否则 lyric/lrc 纯文本），备用 m.kuwo.cn newh5/singles/songinfoandlrc（仅 lrclist）；
 *   song_id 先经 _kuwo_rid 清洗（:1519，去 MUSIC_ 前缀与非数字）
 * - kg（:1263-1290）：lyrics.kugou.com search（keyword=name + hash=song_id）→
 *   download（content=base64→LRC）两步，取 candidates[0] 的 id/accesskey
 * - mg（:1292-1320）：music.migu.cn getLyric?copyrightId={song.id}（handler :3225-3232
 *   未传 copyrightId 参数，服务端等价 song_id 直取），data/lyric/lrc 三路（串须含 [ 且 strip 后 >20 字）
 * - tx（:1322-1337）：c.y.qq.com fcg_query_lyric_new.fcg，JSONP 剥壳（贪婪首 { 至尾 }）+ base64 lyric
 * - Cookie 策略：服务端 fetch_lyric 与客户端直连均匿名取词，不依赖平台登录态。
 * - WY tlyric 与 QQ trans 仅从同一次主歌词响应读取；无严格时间戳配对时由统一解析层留空。
 *   不请求额外来源，不替换用户纠错，也不生成或上传歌词
 * - 兜底链差异：服务端另有酷我关键词搜索（:1345-1380）与网易云关键词搜索兜底（:1382-1425），
 *   直连版不移植（需二段搜索往返，超时风险高）——失败回落 ApiClient.lyric 服务端路径，由服务端补齐
 * - 失败语义：任一环节异常/空词返回 null，由 LyricSourceLoader 回落 ApiClient.lyric（服务端），
 *   再回落本地 .lrc/ID3，播放/歌词闭环零回归
 */

private const val WY_LYRIC_URL = "https://music.163.com/api/song/lyric"
private const val WY_REFERER = "https://music.163.com/"
private const val WY_TIMEOUT_MS = 6000L
private const val KW_LYRIC_URL = "https://wapi.kuwo.cn/openapi/v1/www/lyric"
private const val KW_MOBILE_LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"
private const val KW_REFERER = "https://www.kuwo.cn/"
private const val KW_TIMEOUT_MS = 6000L
private const val KG_SEARCH_URL = "https://lyrics.kugou.com/search"
private const val KG_DOWNLOAD_URL = "https://lyrics.kugou.com/download"
private const val KG_REFERER = "https://www.kugou.com/"
private const val KG_TIMEOUT_MS = 7000L
private const val MG_LYRIC_URL = "https://music.migu.cn/v3/api/music/audioPlayer/getLyric"
private const val MG_REFERER = "https://music.migu.cn/"
private const val MG_TIMEOUT_MS = 7000L
private const val TX_LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
private const val TX_REFERER = "https://y.qq.com/"
private const val TX_TIMEOUT_MS = 7000L

internal fun wyLyricUrl(songId: String, includeYrc: Boolean): String =
    "$WY_LYRIC_URL?id=$songId&lv=1&kv=1&tv=-1&rv=-1" +
        if (includeYrc) "&yv=1&ytv=1" else ""

/**
 * 歌词直连统一入口：按 source 分派各平台，返回 LRC/YRC 文本；WY/QQ 同响应译文存在时，
 * 用版本化内部包保留两份原文供缓存与严格时间戳配对（旧纯歌词缓存仍兼容）。
 * 未知平台/缺 id/任何失败返回 null（调用方回落服务端）；别名集合与 fetch_lyric 一致
 * （:1200/:1226/:1263/:1292/:1322：wy|netease、kw|kuwo、kg|kugou、mg|migu、tx|qq|tencent）。
 */
internal suspend fun fetchLyricDirect(source: String, song: Song): String? {
    if (song.id.isBlank()) return null
    return when (source.lowercase()) {
        "wy", "netease" -> if (song.id.all { it.isDigit() }) fetchWyLyric(song.id) else null
        "kw", "kuwo" -> fetchKwLyric(song.id)
        "kg", "kugou" -> fetchKgLyric(song.name, song.id)
        "mg", "migu" -> fetchMgLyric(song.id)
        "tx", "qq", "tencent" -> fetchTxLyric(song.id)
        else -> null
    }
}

/** wy 两级链（服务端 :1200-1224）：先 yrc 后 lrc，两级独立请求。 */
private suspend fun fetchWyLyric(songId: String): String? {
    val withYrc = platformHttpGet(
        wyLyricUrl(songId, includeYrc = true), WY_REFERER, WY_TIMEOUT_MS)
    parseWyLyricPayload(withYrc, allowYrc = true)?.let { return encodeLyricTextBundle(it) }
    val lrcOnly = platformHttpGet(wyLyricUrl(songId, includeYrc = false), WY_REFERER, WY_TIMEOUT_MS)
    return parseWyLyricPayload(lrcOnly, allowYrc = false)?.let { encodeLyricTextBundle(it) }
}

/** 纯解析：wy /api/song/lyric 原始 JSON → LRC/yrc 文本（yrc 须含 [ 与 (，服务端 :1203-1206）。 */
internal fun parseWyLyric(raw: String, allowYrc: Boolean): String? =
    parseWyLyricPayload(raw, allowYrc)?.main

/** Read tlyric only from the same response that supplied the selected main lyric. */
internal fun parseWyLyricPayload(raw: String, allowYrc: Boolean): LyricTextBundle? {
    if (raw.isBlank()) return null
    return try {
        val d = JSONObject(raw)
        val main = if (allowYrc) {
            val yrc = (d.optJSONObject("yrc")?.opt("lyric") as? String).orEmpty()
            if (yrc.isNotBlank() && '[' in yrc && '(' in yrc) yrc else null
        } else null
        val selectedMain = main ?: (d.optJSONObject("lrc")?.opt("lyric") as? String).orEmpty()
        if (selectedMain.isBlank()) return null
        val translation = (d.optJSONObject("tlyric")?.opt("lyric") as? String).orEmpty()
        val romanization = (d.optJSONObject("romalrc")?.opt("lyric") as? String).orEmpty()
        LyricTextBundle(selectedMain, translation, romanization)
    } catch (_: Exception) {
        null
    }
}

/** kw 主+备两级链（服务端 :1226-1258）：wapi 失败回落 m.kuwo newh5。 */
private suspend fun fetchKwLyric(songId: String): String? {
    // 批29-1 #B-5：rid 清洗并入 DirectParsing.kuwoRid，但本路径原实现用 Char.isDigit()
    // （含全角等 Unicode Nd 数字，另两处为 ASCII '0'..'9'）；按「行为不变」差异保留在调用方。
    val rid = songId.trim().removePrefix("MUSIC_").filter { it.isDigit() }
    if (rid.isEmpty()) return null
    parseKwLyric(platformHttpGet("$KW_LYRIC_URL?musicId=$rid", KW_REFERER, KW_TIMEOUT_MS))?.let { return it }
    return parseKwMobileLyric(platformHttpGet("$KW_MOBILE_LYRIC_URL?musicId=$rid", KW_REFERER, KW_TIMEOUT_MS))
}

/** 纯解析：wapi lyric 原始 JSON → LRC（lrclist 数组优先，否则 lyric/lrc 文本；服务端 :1231-1246）。 */
internal fun parseKwLyric(raw: String): String? {
    if (raw.isBlank()) return null
    return try {
        val d = JSONObject(raw)
        val data = d.optJSONObject("data") ?: d
        val lrclist = data.optJSONArray("lrclist")
        if (lrclist != null && lrclist.length() > 0) {
            kwLrclistToLrc(lrclist, includeLineFallback = true).ifBlank { null }
        } else {
            data.optString("lyric").ifEmpty { data.optString("lrc") }.ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }
}

/** 纯解析：m.kuwo newh5 原始 JSON → LRC（仅 lrclist 数组，lineLyric 单字段；服务端 :1249-1257）。 */
internal fun parseKwMobileLyric(raw: String): String? {
    if (raw.isBlank()) return null
    return try {
        val data = JSONObject(raw).optJSONObject("data") ?: return null
        val lrclist = data.optJSONArray("lrclist") ?: return null
        if (lrclist.length() == 0) return null
        kwLrclistToLrc(lrclist, includeLineFallback = false).ifBlank { null }
    } catch (_: Exception) {
        null
    }
}

/** 服务端 lrclist→[mm:ss.ss] 行拼接（:1237-1243）：time 取秒，lineLyric 优先、主接口回退 line。 */
internal fun kwLrclistToLrc(lrclist: JSONArray, includeLineFallback: Boolean): String {
    val out = ArrayList<String>(lrclist.length())
    for (i in 0 until lrclist.length()) {
        val item = lrclist.optJSONObject(i) ?: continue
        if (item.length() == 0) continue
        val t = item.optDouble("time", 0.0)
        val minutes = (t / 60).toInt()
        val seconds = t - minutes * 60
        val text = if (includeLineFallback) {
            item.optString("lineLyric").ifEmpty { item.optString("line") }
        } else {
            item.optString("lineLyric")
        }
        out.add(String.format(Locale.US, "[%02d:%05.2f]%s", minutes, seconds, text))
    }
    return out.joinToString("\n")
}

/** kg 两步链（服务端 :1263-1290）：search 拿候选 → download 拿 base64 LRC。 */
private suspend fun fetchKgLyric(name: String, songId: String): String? {
    val search = platformHttpGet(
        "$KG_SEARCH_URL?ver=1&man=yes&client=pc&keyword=${URLEncoder.encode(name, "UTF-8")}" +
            "&hash=${URLEncoder.encode(songId, "UTF-8")}",
        KG_REFERER, KG_TIMEOUT_MS)
    val (candidateId, accessKey) = parseKgSearchCandidate(search) ?: return null
    val download = platformHttpGet(
        "$KG_DOWNLOAD_URL?ver=1&client=pc&id=${URLEncoder.encode(candidateId, "UTF-8")}" +
            "&accesskey=${URLEncoder.encode(accessKey, "UTF-8")}&fmt=lrc",
        KG_REFERER, KG_TIMEOUT_MS)
    return parseKgDownload(download)
}

/** 纯解析：kugou search 原始 JSON → candidates[0] 的 (id, accesskey)（服务端 :1269-1276）。 */
internal fun parseKgSearchCandidate(raw: String): Pair<String, String>? {
    if (raw.isBlank()) return null
    return try {
        val cands = JSONObject(raw).optJSONArray("candidates") ?: return null
        val best = cands.optJSONObject(0) ?: return null
        val candidateId = best.optString("id")
        val accessKey = best.optString("accesskey")
        if (candidateId.isNotEmpty() && accessKey.isNotEmpty()) candidateId to accessKey else null
    } catch (_: Exception) {
        null
    }
}

/** 纯解析：kugou download 原始 JSON → content(base64) 解码出的 LRC（服务端 :1277-1285）。 */
internal fun parseKgDownload(raw: String): String? {
    if (raw.isBlank()) return null
    return try {
        val contentB64 = JSONObject(raw).optString("content")
        if (contentB64.isEmpty()) return null
        decodeBase64Text(contentB64)?.takeIf { it.isNotBlank() && '[' in it }
    } catch (_: Exception) {
        null
    }
}

/** mg 单级（服务端 :1294-1320）：copyrightId 直取，data/lyric/lrc 三路串值。 */
private suspend fun fetchMgLyric(songId: String): String? = fetchMiguLyricByCopyrightId(songId)

/** 歌词纠错只允许传 Migu 原生 copyrightId；搜索结果的 contentId/Song.id 不等价。 */
internal suspend fun fetchMiguLyricByCopyrightId(copyrightId: String): String? {
    if (copyrightId.isBlank()) return null
    val raw = platformHttpGet(
        "$MG_LYRIC_URL?copyrightId=${URLEncoder.encode(copyrightId, "UTF-8")}", MG_REFERER, MG_TIMEOUT_MS)
    return parseMgLyric(raw)
}

/** 纯解析：migu getLyric 原始 JSON → LRC（data/lyric/lrc 串含 [ 且 strip 后 >20 字；服务端 :1304-1313）。 */
internal fun parseMgLyric(raw: String): String? {
    if (raw.isBlank()) return null
    return try {
        val d = JSONObject(raw)
        for (value in sequenceOf(d.opt("data"), d.opt("lyric"), d.opt("lrc"))) {
            when (value) {
                is String -> if ('[' in value && value.trim().length > 20) return value
                is JSONObject -> {
                    val text = sequenceOf("lyric", "lrc", "content")
                        .map { value.optString(it) }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                    if ('[' in text && text.trim().length > 20) return text
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

/** tx 单级（服务端 :1322-1337）：fcg 接口，JSONP 剥壳 + base64 lyric。 */
private suspend fun fetchTxLyric(songMid: String): String? {
    val raw = platformHttpGet(
        "$TX_LYRIC_URL?songmid=${URLEncoder.encode(songMid, "UTF-8")}&format=json&nobase64=0&g_tk=5381",
        TX_REFERER, TX_TIMEOUT_MS)
    return parseTxLyricPayload(raw)?.let { encodeLyricTextBundle(it) }
}

/** 纯解析：QQ fcg 原始响应（JSONP 或裸 JSON）→ base64 lyric 解码出的 LRC（服务端 :1325-1335）。 */
internal fun parseTxLyric(raw: String): String? = parseTxLyricPayload(raw)?.main

/** Decode lyric and optional trans from the same QQ response; invalid translation never fails main lyrics. */
internal fun parseTxLyricPayload(raw: String): LyricTextBundle? {
    if (raw.isBlank()) return null
    return try {
        val json = TX_JSONP_BODY.find(raw)?.value ?: raw
        val response = JSONObject(json)
        val lyricB64 = response.optString("lyric")
        if (lyricB64.isEmpty()) return null
        val main = decodeBase64Text(lyricB64)?.takeIf { it.isNotBlank() && '[' in it } ?: return null
        val translation = decodeTranslationBase64(response.optString("trans"))
        LyricTextBundle(main, translation.orEmpty())
    } catch (_: Exception) {
        null
    }
}

/** QQ trans is optional and must be valid UTF-8 Base64; failure leaves the primary lyric intact. */
private fun decodeTranslationBase64(value: String): String? {
    val compact = value.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
    if (compact.isEmpty() || compact.length % 4 == 1 ||
        !Regex("^[A-Za-z0-9+/]*={0,2}$").matches(compact)
    ) return null
    return try {
        val bytes = Base64.getDecoder().decode(compact)
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }
}

/** base64→UTF-8 文本；MimeDecoder 与服务端 b64decode(validate=False) 同为宽松语义，失败返回 null。 */
internal fun decodeBase64Text(contentB64: String): String? = try {
    val bytes = Base64.getMimeDecoder().decode(contentB64)
    if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
} catch (_: Exception) {
    null
}
