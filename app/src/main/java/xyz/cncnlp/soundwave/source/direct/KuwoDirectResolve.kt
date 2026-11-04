package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.cncnlp.soundwave.ApiClient
import xyz.cncnlp.soundwave.download.AudioQualityEvidence

/**
 * 酷我官方取链客户端直连（批8d，模式照抄批8b/8c kg/mg 范本）。
 * 1:1 移植服务端 kuwo_url_by_rid 官方 anti 段（/opt/soundwave/music_server.py:1793-1847）：
 * - rid 归一 _kuwo_rid（music_server.py:1519-1524）：剥 MUSIC_ 前缀 + 仅保留数字
 * - br 级联（L1811-1820）：(br, 'flac', '320', '192', '128') 逐个请求
 *   https://antiserver.kuwo.cn/anti.s?type=convert_url3&rid={rid}&format=mp3&response=url&br={br_try}，
 *   Referer https://www.kuwo.cn/，timeout 8s（platformHttpGet 默认，与服务端 http_get_text timeout=8 一致）
 * - 每候选 _extract_play_url 等价解析（music_server.py:1702-1734）：裸 URL / JSON data.url|music_url /
 *   “音乐链接：xxx” 文案 / 任意 http(s) 正则；末尾 rstrip('\"',',') 等价
 * - probe 闸门（kuwo_url_by_rid 内 probe_audio_url(url, min_bytes=500000)，约 L1834-1843）：
 *   复用 ApiClient.headContentLength（HEAD 拒绝时以 Range bytes=0-0 的 206 回退，取 Content-Range/
 *   Content-Length 总长，语义与服务端 probe_audio_url 一致），<500000 判试听碎片失败，继续下一个 br
 * - 服务端明文变体 http://antiserver.kuwo.cn（L1822-1827）默认不移植：HttpPolicy fail-closed
 *   （platformHttpGet 明文白名单仅 kg mobilecdn，新增明文 API 面须明示理由，此处无需）
 * - 不注入 cookie（kw 官方 anti 取链无需登录态）
 * 失败语义：网络/解析/probe 全败一律 null，由 UrlResolver 回落脚本链（/music/url 服务端兜底已整条删除，全链在本机收尾）。
 * - 出链归一（ACCEPT4 补齐，与 kg/mg/wy 先例对齐）：anti.s 返回的媒体 URL 常为
 *   http 明文，原实现原样返回——SW-03 播放器汇点明文门会把非例外宿主的 http
 *   媒体链直接拒死。现与其它直连一致经 normalizeKgMediaUrl 提升 https 后再
 *   probe/返回（probe 量的就是将实际播放的那条链）。
 */
internal suspend fun kuwoDirectResolveUrl(songId: String, br: String = "320", expectedDurationMs: Long = 0L): String? {
    val rid = kuwoRid(songId)
    if (rid.isEmpty()) return null
    // ACCEPT6（qqslow）：级联改「边取边验」——旧形态先打完全部 5 个 br 收集
    // 候选才开始 probe，常见情形（首档即出可用链）白付 4 次 API 往返；现按
    // 级联序逐个取链+probe，首个通过即返回，胜者与旧形态一致（同序、同闸门）。
    // probe 闸门同时加时长完整性（expectedDurationMs>0 时）：45~60s 的 128k
    // 试听有 360~960KB，旧 500KB 下限整段漏过。
    val seen = HashSet<String>()
    for (brTry in listOf(br, "flac", "320", "192", "128")) {
        val raw = platformHttpGet(
            url = "https://antiserver.kuwo.cn/anti.s?type=convert_url3&rid=$rid&format=mp3&response=url&br=$brTry",
            referer = "https://www.kuwo.cn/"
        )
        // 出链归一与旧形态一致：先 http→https 提升，再 probe/返回。
        val url = normalizeKgMediaUrl(extractKuwoPlayUrl(raw))
        if (url.isEmpty() || !seen.add(url)) continue
        val totalBytes = withContext(Dispatchers.IO) { ApiClient.headContentLength(url) }
        if (totalBytes >= KUWO_PROBE_MIN_BYTES &&
            !AudioQualityEvidence.isPreviewFragment(totalBytes, expectedDurationMs, url)
        ) return url
    }
    return null
}

/** 服务端 probe 调用点 min_bytes=500000（music_server.py:1838）：试听碎片 <200KB，完整歌 >1MB。 */
private const val KUWO_PROBE_MIN_BYTES = 500_000L

/** music_server.py _extract_play_url（L1702-1734）的 url 通道等价移植（cover 不属取链闭环，不移植）。 */
internal fun extractKuwoPlayUrl(payload: String): String {
    val raw = payload.trim()
    if (raw.isEmpty()) return ""
    if (raw.startsWith("http")) return raw
    val json = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return genericHttpUrl.find(raw)?.value ?: ""
    }
    val data = json.optJSONObject("data") ?: json
    var url = data.optString("url")
    if (url.isBlank()) url = data.optString("music_url")
    if (url.isBlank()) {
        // oiapi message 文案（服务端兼容历史载荷，保留等价）：音乐链接：xxx
        val msg = json.optString("message")
        msgPlayUrl.find(msg)?.let { url = it.groupValues[1] }
    }
    if (url.isBlank()) url = json.optString("url")
    return url.trim().trimEnd('"', ',')
}

private val genericHttpUrl = Regex("https?://[^\\s\"'<>]+")
private val msgPlayUrl = Regex("音乐链接[：:](https?://\\S+)")
