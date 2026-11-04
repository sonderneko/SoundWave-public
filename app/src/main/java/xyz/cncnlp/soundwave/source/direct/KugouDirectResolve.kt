package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗官方取链客户端直连（批8b 试点）。
 * 1:1 移植服务端 kg 内联取链（/opt/soundwave/music_server.py:2863-2872）：
 * - GET https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash={song_id}，总超时 4s（ACCEPT6 由 8s 收紧，见函数内注）
 * - 解析 url / backup_url（list 取首个）；出链经 http→https 归一（等价服务端
 *   normalize_media_url，music_server.py:1455-1464）
 * - 与服务端差异：仅返回播放 URL，不回写 cover（imgUrl{size}→240 属搜索/详情链路，取链闭环用不到）
 * - 不注入 cookie（kg 官方取链无需登录态）
 * 失败语义：网络/解析/无 URL 一律 null，由 UrlResolver 回落脚本链（/music/url 服务端兜底已整条删除，全链在本机收尾）。
 * 返回的 http 明文 CDN 属媒体播放路径（ApiClient.playableUrl 豁免），不经 HttpPolicy API 闸门。
 */
internal suspend fun kugouDirectResolveUrl(songId: String): String? {
    if (songId.isBlank()) return null
    // ACCEPT6（qqslow）：超时 8s→4s——该端点是小 JSON 接口，要么秒回要么
    // 卡死；卡死时不该让脚本链在本机 26s 预算里先白等 8s（mg 直连 ACCEPT5
    // 已按同一理由定 4s）。服务端 8s 是数据中心口径，不照搬到真机链路。
    val raw = platformHttpGet(
        url = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$songId",
        referer = "https://m.kugou.com/",
        timeoutMs = 4000L
    )
    return parseKgResolve(raw)
}

/** 纯解析（与网络分离，可测）：url|backup_url 首项 + http→https 归一；无出链返回 null。 */
internal fun parseKgResolve(raw: String): String? {
    val d = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return null
    }
    var url = kgUrlOrListFirst(d, "url")
    if (url.isEmpty()) url = kgUrlOrListFirst(d, "backup_url")
    if (url.isEmpty()) return null
    return normalizeKgMediaUrl(url).ifEmpty { null }
}

/** python `d.get(key)`：字符串原样；JSON 数组取首个元素（空数组为 ''）；缺失/null/其余类型为 ''。 */
private fun kgUrlOrListFirst(d: JSONObject, key: String): String {
    if (d.isNull(key)) return ""
    return when (val value = d.opt(key)) {
        is String -> value
        is JSONArray -> if (value.length() > 0) value.optString(0) else ""
        else -> ""
    }
}

/** music_server.py normalize_media_url 的 1:1 移植：// 补协议头，http 明文提升为 https。 */
internal fun normalizeKgMediaUrl(url: String): String {
    var u = url.trim().trim('"', ',')
    if (u.isEmpty()) return ""
    if (u.startsWith("//")) u = "https:$u"
    if (u.startsWith("http://")) u = "https://" + u.substring(7)
    return u
}
