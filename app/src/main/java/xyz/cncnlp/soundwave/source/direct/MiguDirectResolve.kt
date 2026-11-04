package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史咪咕官方 resourceinfo 兼容解析（不是可播保证）。
 * 真实探测：晴天 contentId / songId 可返回元数据，但无 listenUrl / playUrl；
 * copyrightId GET/POST 返回空 resource。无 URL 必须返回 null，不从音质/VIP字段造链。
 * 1:1 移植服务端 migu_url_by_cid（/opt/soundwave/music_server.py:1024-1041）：
 * - GET https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceId={cid}&resourceType=2&channel=0&format=2，
 *   header Referer https://music.migu.cn/ + UA；timeout=4（ACCEPT5 起与服务端对齐，见函数内注）
 * - 解析 resource[0]（list 取首个）→ listenUrl → playUrl：python `or` 空值回落 + 列表解包顺序保真
 * - 出链经 http→https 归一：复用 normalizeKgMediaUrl（同包 internal，即服务端 normalize_media_url 的
 *   1:1 移植，kg/mg 语义相同；改名属额外改动，选最小方案直接复用）
 * - 服务端 /music/url 主流程无 mg 一等分支（仅跨源 fallback 调用 migu_url_by_cid），客户端直连把 mg
 *   升为一等源；该服务端端点已整条删除（ACCEPT5），直连失败回落脚本链
 * - 不注入 cookie（mg 官方取链无签名无 cookie）
 * 失败语义：网络/解析/无 URL 一律 null，由 UrlResolver 回落脚本链，全链在本机收尾。
 * 返回的 http 明文 CDN 属媒体播放路径（ApiClient.playableUrl 豁免），不经 HttpPolicy API 闸门。
 */
internal suspend fun miguDirectResolveUrl(cid: String): String? {
    if (cid.isBlank()) return null
    val raw = platformHttpGet(
        url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceId=$cid&resourceType=2&channel=0&format=2",
        referer = "https://music.migu.cn/",
        // ACCEPT5（D）：超时 4s——对齐服务端原实现（timeout=4），并按上方实证
        // 结论收紧：resourceinfo 对真实歌曲只回元数据、不给播放链接，这一段在
        // mg 链里结构性近死，mg 高档链串行时不该为它付满 8s。
        timeoutMs = 4000L
    )
    return parseMgResolve(raw)
}

/** 纯解析（与网络分离，可测）：resource 首项 listenUrl→playUrl（含 list 解包）+ http→https 归一；无出链返回 null。 */
internal fun parseMgResolve(raw: String): String? {
    val d = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return null
    }
    // python `d.get('resource') or []` + `res[0] if isinstance(res, list) and res else res`：
    // list 取首个元素，非 list 的 dict 原样，缺失/null/其余类型等价空。
    val first = when (val res = d.opt("resource")) {
        is JSONArray -> if (res.length() > 0) res.optJSONObject(0) else null
        is JSONObject -> res
        else -> null
    } ?: return null
    val url = mgUrlFrom(first)
    if (url.isEmpty()) return null
    return normalizeKgMediaUrl(url).ifEmpty { null }
}

/** python `r.get('listenUrl') or r.get('playUrl') or ''` + 末尾列表解包（`u[0] or ''`）的 1:1。 */
private fun mgUrlFrom(r: JSONObject): String {
    var u: Any? = r.opt("listenUrl")
    if (mgFalsy(u)) u = r.opt("playUrl")
    if (mgFalsy(u)) return ""
    if (u is JSONArray) u = if (u.length() > 0) u.opt(0) else null
    return if (u is String) u else ""
}

/** python 真值判断（JSON 可表示值全覆盖）：null/显式 null、空串、false、0、空数组、空对象为假，其余为真。
 *  非字符串终值（非空 dict/数字/true）解包后非 String → ''，等价服务端对非字符串做 normalize 抛错→'' 的净效果。 */
private fun mgFalsy(v: Any?): Boolean = when {
    v == null || v === JSONObject.NULL -> true
    v is String -> v.isEmpty()
    v is Boolean -> !v
    v is Number -> v.toDouble() == 0.0
    v is JSONArray -> v.length() == 0
    v is JSONObject -> v.length() == 0
    else -> false
}
