package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
 *
 * PB-F1（第二批后第 3 波）双端点热备——前置复核结论（2026-11-05 实测）：
 * - 参照 guohuiyuan/music-lib 当前 main 的 kugou 包：fetchSongInfo（移动端
 *   getSongInfo，errcode 1002 = 操作太频繁/风控）在 GetDownloadURL 主路并无
 *   1002 专属备路，fetchTrackerSongInfo（注释自述「原接口被风控 (1002) 时使用
 *   PC 网页端 API Fallback」）只挂在 VIP 路径上；本波把该备路接到匿名主路的
 *   1002 分支，口径与 E-1 原意一致。
 * - 线上实测：移动端 getSongInfo 正常应答（url 为字符串、bitRate 单位 kbps、
 *   timeLength 单位秒）；备路三址中 v2 cmd21 / bj cmd23 对实测 hash 均回
 *   errcode 20028（匿名不可用），仅 cmd4 可用，且其 https 形态同址同参实测
 *   成功（url 字符串、bitRate 单位 bps、timeLength 秒）——故备路只取 cmd4 的
 *   https 形态：不扩明文白名单（HttpPolicy / network_security_config 不动），
 *   v2 两址不接线（证据如上，避免无效往返）。
 * - 字段/单位差异在适配层抹平：[parseKgTrackerResolve] 按 music-lib 的
 *   normalizeKugouBitrate / normalizeKugouDuration 口径（>1000 才除 1000）
 *   把 bps→kbps、毫秒→秒，产出与移动端同型的 [KgResolvedTrack]。
 * - 1002 不记死刑：限频是平台级瞬时状态，不是脚本/歌曲的终局错误——本路
 *   从不调用 ScriptVerdictStore.recordTerminal/recordShortVerdict（那是脚本
 *   裁决面），也不写任何持久化负记录；只记进程内短冷却
 *   [KgMobileRateLimitCooldown]（60s，与脚本负缓存同档），冷却期内直达备路。
 *   PlatformTransportCooldown 只门控投机流量、主动播放恒放行，承载不了
 *   本语义，故另立此短记忆。备路再失败仍按既有口径返回 null 回落脚本链。
 */

/** kg 移动端风控错误码：操作太频繁（music-lib fetchSongInfo 同口径）。 */
internal const val KG_ERRCODE_RATE_LIMITED = 1002

/** 1002 短冷却时长：与脚本负缓存 60s 同档，远短于 ScriptVerdictStore 的软裁决 30min。 */
internal const val KG_MOBILE_COOLDOWN_MS = 60_000L

/**
 * kg 移动端限频短冷却（进程内，不落盘）：记录 1002 时刻，冷却期内
 * [kugouDirectResolveUrl] 跳过移动端直达 tracker 备路。时钟可注入以便单测。
 */
internal object KgMobileRateLimitCooldown {
    @Volatile private var untilMs: Long = 0L

    fun record(nowMs: Long = System.currentTimeMillis()) {
        untilMs = nowMs + KG_MOBILE_COOLDOWN_MS
    }

    fun isCooling(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < untilMs

    fun clearForTests() {
        untilMs = 0L
    }
}

/** 双端点归一后的取链事实：bitrate 恒 kbps、duration 恒秒（两路单位差异已抹平）。 */
internal data class KgResolvedTrack(
    val url: String,
    val bitrateKbps: Int,
    val durationSec: Int,
    val extName: String,
)

/** kg 取链解析三态：命中 / 被限频（1002）/ 无链（其余失败事实不细分，维持既有回落）。 */
internal sealed interface KgResolveParse {
    data class Found(val track: KgResolvedTrack) : KgResolveParse
    object RateLimited : KgResolveParse
    object NoUrl : KgResolveParse
}

/** 备路选择（纯函数，可测）：移动端冷却中、或移动端刚回 1002 时才走备路；其余无链维持原回落。 */
internal fun kgShouldTryBackup(mobile: KgResolveParse?, cooling: Boolean): Boolean =
    cooling || mobile is KgResolveParse.RateLimited

internal suspend fun kugouDirectResolveUrl(songId: String): String? {
    if (songId.isBlank()) return null
    // ACCEPT6（qqslow）：超时 8s→4s——该端点是小 JSON 接口，要么秒回要么
    // 卡死；卡死时不该让脚本链在本机 26s 预算里先白等 8s（mg 直连 ACCEPT5
    // 已按同一理由定 4s）。服务端 8s 是数据中心口径，不照搬到真机链路。
    val cooling = KgMobileRateLimitCooldown.isCooling()
    var mobile: KgResolveParse? = null
    if (!cooling) {
        val raw = platformHttpGet(
            url = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$songId",
            referer = "https://m.kugou.com/",
            timeoutMs = 4000L
        )
        mobile = parseKgMobileResolve(raw)
        when (mobile) {
            is KgResolveParse.Found -> return mobile.track.url
            KgResolveParse.RateLimited -> KgMobileRateLimitCooldown.record()
            KgResolveParse.NoUrl, null -> Unit
        }
    }
    if (!kgShouldTryBackup(mobile, cooling)) return null
    // PB-F1 备路：tracker cmd4（music-lib fetchTrackerSongInfo 第三址的 https
    // 形态，复核证据见文件头）。key = MD5(小写 hash + "kgcloud")，与 Go 版同式。
    val backupRaw = platformHttpGet(
        url = kgTrackerBackupUrl(songId),
        referer = "https://www.kugou.com/",
        timeoutMs = 4000L
    )
    return (parseKgTrackerResolve(backupRaw) as? KgResolveParse.Found)?.track?.url
}

/** 纯解析（移动端）：errcode=1002 → RateLimited；url|backup_url 首项 + http→https 归一；无出链 NoUrl。 */
internal fun parseKgMobileResolve(raw: String): KgResolveParse {
    val d = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return KgResolveParse.NoUrl
    }
    if (d.optInt("errcode", 0) == KG_ERRCODE_RATE_LIMITED) return KgResolveParse.RateLimited
    var url = kgUrlOrListFirst(d, "url")
    if (url.isEmpty()) url = kgUrlOrListFirst(d, "backup_url")
    if (url.isEmpty()) return KgResolveParse.NoUrl
    val normalized = normalizeKgMediaUrl(url).ifEmpty { return KgResolveParse.NoUrl }
    // 移动端口径：bitRate 已是 kbps、timeLength 已是秒，原样带出。
    return KgResolveParse.Found(
        KgResolvedTrack(
            url = normalized,
            bitrateKbps = d.optInt("bitRate", 0),
            durationSec = d.optInt("timeLength", 0),
            extName = d.optString("extName", ""),
        )
    )
}

/**
 * 纯解析（tracker 备路）：信封同型（url/backup_url、errcode），但 bitRate 为
 * bps、timeLength 可能为毫秒——按 music-lib normalize 口径（>1000 才除 1000）
 * 归一到与移动端同型。errcode 非 0（如实测 20028）一律 NoUrl。
 */
internal fun parseKgTrackerResolve(raw: String): KgResolveParse {
    val d = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return KgResolveParse.NoUrl
    }
    if (d.optInt("errcode", 0) != 0) return KgResolveParse.NoUrl
    var url = kgUrlOrListFirst(d, "url")
    if (url.isEmpty()) url = kgUrlOrListFirst(d, "backup_url")
    if (url.isEmpty()) return KgResolveParse.NoUrl
    val normalized = normalizeKgMediaUrl(url).ifEmpty { return KgResolveParse.NoUrl }
    return KgResolveParse.Found(
        KgResolvedTrack(
            url = normalized,
            bitrateKbps = normalizeKgBitrate(d.optInt("bitRate", 0)),
            durationSec = normalizeKgDurationSec(d.optInt("timeLength", 0)),
            extName = d.optString("extName", ""),
        )
    )
}

/** music-lib normalizeKugouBitrate：>1000 视为 bps，除 1000 得 kbps；否则已是 kbps。 */
internal fun normalizeKgBitrate(value: Int): Int = if (value > 1000) value / 1000 else value

/** music-lib normalizeKugouDuration：>1000 视为毫秒，除 1000 得秒；否则已是秒。 */
internal fun normalizeKgDurationSec(value: Int): Int = if (value > 1000) value / 1000 else value

/** tracker cmd4 备路 URL（纯函数，可测）：hash 先小写，key = MD5(hash + "kgcloud")。 */
internal fun kgTrackerBackupUrl(songId: String): String {
    val hash = songId.trim().lowercase()
    val key = MessageDigest.getInstance("MD5")
        .digest((hash + "kgcloud").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "https://trackercdn.kugou.com/i/?cmd=4&pid=1&forceDown=0&vip=1&hash=$hash&key=$key"
}

/** 纯解析（与网络分离，可测）：url|backup_url 首项 + http→https 归一；无出链返回 null。 */
internal fun parseKgResolve(raw: String): String? =
    (parseKgMobileResolve(raw) as? KgResolveParse.Found)?.track?.url

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
