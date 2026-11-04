package xyz.cncnlp.soundwave

import org.json.JSONObject
import xyz.cncnlp.soundwave.source.direct.decodeJsonUnicodeEscapes
import xyz.cncnlp.soundwave.source.direct.decodeProviderMetadata
import xyz.cncnlp.soundwave.source.direct.decodeProviderEntities
import xyz.cncnlp.soundwave.source.direct.orChain

/** 图片接口常返回 http 或缩略图参数；仅对已按同一资源实测的 provider 变体做白名单改写。
 * 批29-4：客户端封面归一单真源——吸收 DirectParsing.shrinkCover 全部规则
 * （空串保护 / // 协议相对 / Kugou 显式尺寸占位 / migu 截断过滤），
 * shrinkCover 改为薄包装调用本函数。规则顺序：输入清洗 → 平台改写 → 终态校验。 */
fun normalizeCoverUrl(raw: String, targetSizePx: Int? = null): String {
    var url = raw.trim()
    // 空串保护（吸收 shrinkCover）
    if (url.isEmpty()) return ""
    // 协议相对与 http→https（// 规则吸收自 shrinkCover）。酷我 img1.kwcdn 主机实测
    // HTTP 可读、HTTPS 证书主机名不匹配；也修复已落盘旧 https URL，避免 Coil 永远裂图。
    if (url.startsWith("//")) url = "https:$url"
    val kuwoHttpOnlyHost = url.startsWith("http://img1.kwcdn.kuwo.cn/", ignoreCase = true) ||
        url.startsWith("https://img1.kwcdn.kuwo.cn/", ignoreCase = true)
    if (kuwoHttpOnlyHost) {
        url = "http://${url.substringAfter("://")}"
    } else if (url.startsWith("http://", ignoreCase = true)) {
        url = "https://${url.substring(7)}"
    }
    // 酷我 r.s 接口的 img 字段用 imgN.sycdn.kuwo.cn 主机：该主机 HTTPS 实测 TLS
    // 失败（curl 000），同路径在 imgN.kuwo.cn 上 HTTPS 实测 200（响应里的
    // hts_img 字段就是这面镜像）。归一到 kuwo.cn 主机，后续 img1/img4
    // albumcover 升档规则随之生效；已落盘的旧 sycdn URL 同样被修复。
    url = url.replace(Regex("(?i)^https?://(img\\d+)\\.sycdn\\.kuwo\\.cn/"), "https://$1.kuwo.cn/")
    // 网易云 coverImgUrl 会带多段 imageView/watermark 处理链，末段常锁死 thumbnail=140y140；
    // 仅追加 param=1080y1080 不会覆盖前面的缩略处理（实测仍返回 140×140），所以丢弃变换 query，
    // 保留真实图片路径后再请求标准 param 尺寸。
    val neteaseImage = url.contains("music.126.net", true) || url.contains("music.163.com", true)
    if (neteaseImage) {
        val imageQuery = url.substringAfter('?', "")
        val hasLegacyTransform = imageQuery.contains("thumbnail=", true) ||
            imageQuery.contains("imageView=", true) || imageQuery.contains("%7c", true) || imageQuery.contains('|')
        if (hasLegacyTransform) url = url.substringBefore('?')
    }
    // 不因 query 中存在 param 提前返回；大小写和 x/y 分隔均按同一规格处理。
    // J 波：param 尺寸跟随 targetSizePx（未指定时仍为 1080，与历史行为一致）；
    // 列表面传 300/400 时只下小图，详情面以大 target 重新归一即可升回大图（正则覆盖任意既有尺寸）。
    val coverTargetPx = targetSizePx?.takeIf { it > 0 } ?: 1080
    url = url.replace(Regex("(?i)([?&]param=)\\d+[xy]\\d+")) { match ->
        "${match.groupValues[1]}${coverTargetPx}y${coverTargetPx}"
    }
    // 网易封面没有 param 时服务端不会自动给高清图；显式请求目标尺寸，且缓存键随之更新，
    // 避免继续命中旧版低清缓存。data/file/content 资源不能追加查询参数。
    if ((url.contains("music.126.net", true) || url.contains("music.163.com", true)) &&
        !url.contains(Regex("(?i)[?&]param="))) {
        url += if ('?' in url) "&param=${coverTargetPx}y${coverTargetPx}" else "?param=${coverTargetPx}y${coverTargetPx}"
    }
    // 仅 Kugou API 明确返回的 imgurl{size} 模板使用已验证的 stdmusic 档（尺寸跟随 target）；
    // 其他域不推测模板含义。
    if (url.contains("kugou.com", true) || url.contains("kgimg.com", true)) {
        url = url.replace("{size}", coverTargetPx.toString()).replace("{SIZE}", coverTargetPx.toString())
    }
    // 咪咕按 API 同一对象内真实 imgSizeType 选最大候选；不拼通用 1080 参数或把文件后缀猜成 _00。
    // QQ 新歌/新碟接口有时返回 R300；R800 才是清晰封面，避免首页卡片沿用低清图。
    // 批23b：扩到全部 QQ 封面域（y.gtimg.cn 之外还有 imgcache.qq.com 等直传形态）。
    // 批24a：T001 歌手头像同规格升级（歌手搜索/歌手页封面，否则 150/500 小图模糊）；
    // T001 必须替换为 T001 前缀（误用 T002 前缀会裂图）。
    if (url.contains("gtimg", true) || url.contains("qq.com", true)) {
        url = url.replace(Regex("T002R\\d+x\\d+M"), "T002R800x800M")
            .replace(Regex("T001R\\d+x\\d+M"), "T001R800x800M")
    }
    // QQ 歌单接口实际会返回 qpic music_cover/<id>/300；同一路径 /600 已实测同内容且分辨率翻倍。
    // 只改写这两个已验证 CDN 的尺寸段；music-file.y.qq.com 等真实高分图按 API 原 URL 保留。
    if (url.contains("p.qpic.cn/music_cover/", true) || url.contains("qpic.y.qq.com/music_cover/", true)) {
        url = url.replace(Regex("(?i)(/music_cover/[^/?#]+/)300(?=([?#]|$))")) { match ->
            "${match.groupValues[1]}600"
        }
    }
    // 仅酷我已实测的 img1.kwcdn 主机上同资源 _500.jpg 返回 500×500；其它图片主机保留原尺寸。
    if (Regex("(?i)^https?://img1\\.kwcdn\\.kuwo\\.cn/star/userpl2015/").containsMatchIn(url)) {
        url = url.replace(Regex("(?i)_(?:150|240|300)(?=\\.jpg(?:[?#]|$))"), "_500")
    }
    // 酷我歌单接口还会返回 HTTP-only img1.kwcdn.kuwo.cn/star/albumcover/320/s...；同一真实
    // playlist ID 的 /500 与 /1080 路径已分别 GET 验证为 500×500 / 960×960。仅升级现代 /s... 路径；
    // 旧式数字-only /95/... 路径未验证且有 404 样本，必须保持原样。
    // r658c D#5：酷我搜索/歌手接口常给 img2/img3/img4.kuwo.cn 的 /120/ 小图（旧正则只认 img1 → 一直 120px 模糊）。
    // 实测 img2/img3 主机连接失败、img1/img4 同路径 /500 与 /1080 分别返回 500×500 / 1080×1080，故 img2/img3 迁到 img1，img1/img4 升档。
    url = url.replace(Regex("(?i)^(https?://)img[23]\\.kuwo\\.cn/star/(albumcover|starheads)/(?=\\d+/s)"), "$1img1.kuwo.cn/star/$2/")
    // 歌手头像 starheads /120/ 现代 /s... 路径实测 /500 返回 500×500。
    url = url.replace(Regex("(?i)^(https?://img1\\.kuwo\\.cn/star/starheads/)(?:120|150|240|300)(/s[^/?#]+/[^?#]+\\.(?:jpg|jpeg|png|webp))")) { m ->
        "${m.groupValues[1]}500${m.groupValues[2]}"
    }
    val kuwoAlbumPath = Regex("(?i)^(https?://(?:img1\\.kuwo\\.cn|img4\\.kuwo\\.cn|img1\\.kwcdn\\.kuwo\\.cn)/star/albumcover/)\\d+(/s[^/?#]+/[^?#]+\\.(?:jpg|jpeg|png|webp))([?#].*)?$")
    val kuwoAlbumSize = if (targetSizePx == null || targetSizePx >= 500) 1080 else 500
    url = kuwoAlbumPath.replace(url) { match ->
        "${match.groupValues[1]}$kuwoAlbumSize${match.groupValues[2]}${match.groupValues[3]}"
    }
    // Verified numeric albumcover family on img1/img4: same-object 120/500/1080 GETs retain artwork.
    // KwDirectSearch emits numeric img1 paths; upgrading only img4 left those foreground covers at 120px.
    // Only this CDN/path family is upgraded; unrelated legacy 95px routes stay unchanged.
    url = url.replace(Regex("(?i)^(https?://(?:img1|img4)\\.kuwo\\.cn/star/albumcover/)(?:120|150|240|300|500)(/\\d+/\\d+/\\d+\\.jpg)([?#].*)?$")) { m ->
        "${m.groupValues[1]}1080${m.groupValues[2]}${m.groupValues[3]}"
    }
    if (url.contains("kugou.com", true) || url.contains("kgimg.com", true)) {
        url = url.replace(Regex("/stdmusic/\\d+/"), "/stdmusic/1080/")
    }
    // 批28→批30-2：咪咕 CDN 间歇性返回截断 URL（.web 缺尾 p / .jp 截断）加载必裂图；
    // 但无扩展名 OSS 路径（/data/oss/resource/...）是合法图，不得误伤。用 mgImgOk 终态校验。
    if (url.contains("migu.cn", ignoreCase = true) && !mgImgOk(url)) {
        return ""
    }
    return url
}

internal fun isDeviceLocalReference(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return false
    if (value.startsWith("//")) return false
    return value.startsWith("file:", ignoreCase = true) ||
        value.startsWith("content:", ignoreCase = true) ||
        value.startsWith("/") ||
        value.startsWith("\\") ||
        (value.length >= 3 && value[0].isLetter() && value[1] == ':' && (value[2] == '/' || value[2] == '\\'))
}

internal fun filterDeviceLocalReference(value: String, includeLocalUris: Boolean): String =
    if (includeLocalUris || !isDeviceLocalReference(value)) value else ""

internal fun canonicalSourceId(source: String): String =
    if (source.trim().equals("qq", ignoreCase = true)) "tx" else source.trim().lowercase(java.util.Locale.ROOT)

/** Separate detail Hero decodes from thumbnail/background memory entries without changing the source URL or disk key. */
internal fun detailHeroCoverMemoryCacheKey(rawUrl: String, targetSizePx: Int): String {
    val size = targetSizePx.coerceAtLeast(1)
    val normalizedUrl = normalizeCoverUrl(rawUrl, size)
    val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
        .digest(normalizedUrl.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "detail-hero-v1:${size}x$size:$fingerprint"
}

/** 批30-2：咪咕封面 URL 终态校验——完整图片扩展名 或 无扩展名 OSS 路径均合法
 * （上游 /data/oss/resource/xx/xx/xx 本无扩展名，强制要求扩展名会把真封面误伤成空）；
 * 只拒截断后缀（.web 缺尾 p / .jp 截断，加载必裂图）。与服务端 music_server._mg_img_ok 同规则，
 * 双端单一真源。 */
internal fun mgImgOk(u: String): Boolean {
    var s = u.substringBefore('?').trimEnd('/')
    if (s.isEmpty()) return false
    if (Regex("\\.(webp|jpg|jpeg|png)$", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
    return '.' !in s.substringAfterLast('/')
}

internal fun decodeProviderText(value: String): String {
    if (value.isEmpty()) return value
    // 批23a #B：上游把已解码文本二次 JSON 序列化时 \uXXXX 会残留为字面（org.json 只解一层），
    // 详情/搜索页歌名/歌手/专辑出现 “\u0026”。先还原字面 unicode 转义，再保留旧版 “/uXXXX”
    // 变体与 HTML 实体还原；正常文本（无反斜杠）零改动。
    val decoded = decodeJsonUnicodeEscapes(value)
        .replace(Regex("(?:\\\\|/)u([0-9a-fA-F]{4})")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    return decodeProviderEntities(decoded)
}

/** Provider 元数据可能被 HTML/XML 二次编码；有限解码并规范 NBSP，防止标题出现字面的 `&#160;`。 */
internal fun decodePlaylistMetadata(value: String): String =
    if (value.isEmpty()) value else decodeProviderMetadata(value).replace('\u00A0', ' ').trim()

data class Song(
    val id: String,
    val name: String,
    val singer: String,
    /** 真实来源；空表示来源未经验证，禁止隐式标为网易云。 */
    val source: String = "",
    val cover: String = "",
    val album: String = "",
    /** 平台返回的真实专辑身份；不可再误用 song.id 请求专辑详情。 */
    val albumId: String = "",
    /** 平台返回的主歌手真实身份（歌手数组第一位，与 primaryArtist 语义一致）；与 albumId 对称，
     * 供「查看歌手」按 ID 直开歌手页。旧数据/本地歌曲缺省空，导航自动落入按名兜底。 */
    val singerId: String = "",
    val duration: Long = 0,
    val hash: String = "",
    /** 平台版权身份（咪咕取链必需；QQ 脚本源也靠它精确定位）。空则退化按歌名匹配，易张冠李戴。 */
    val copyrightId: String = "",
    /** QQ 文件 media_mid（vkey/脚本源取链真实凭据，≠ songmid）。空则脚本源可能按歌名猜错歌。 */
    val mediaMid: String = "",
    var resolvedUrl: String = "",
    var actualQuality: String = "",
    val playedAt: Long = 0L,
    /** User-data cloud LWW marker; only emitted by UserBundle serialization. */
    val syncAddedAt: Long = 0L,
    /**
     * r657 第二轮：播放历史「来自 <来源>」——开始播放时所在的歌单 / 专辑 / 页面名（如「我的喜欢」「今日推荐」「搜索」）。
     * 只在播放历史条目上写入（JSON 键 playSrc，非空才输出）；旧数据没有该键 → 默认空串，历史行不显示「来自」。
     */
    val playSource: String = "",
    /** 咪咕真实 songId；独立于稳定的 contentId/id 与 copyrightId。 */
    val miguSongId: String = "",
    /** Typed, identity-bound KG catalog facts; never URLs, account secrets or entitlement. */
    val kgAlbumMetadata: KgAlbumMetadata? = null,
    /** r657 第三轮第 22 项：平台列表返回的最高可用音质键（master / hires / flac / 320k；空 = 平台未给出）。不入 JSON。 */
    val maxQuality: String = "",
    /** r657 第三轮第 23 项：平台给出的发布时间（epoch ms；0 = 未知）。不入 JSON。 */
    val publishTime: Long = 0L,
    /** r657 第三轮第 23 项：平台榜单条目自带的「新歌」标记（如酷我 isnew）。不入 JSON。 */
    val platformNew: Boolean = false,
    /** 榜单上下文中的真实名次变化：正数上升、负数下降；null 表示平台未提供可信变化量。通用 Song JSON 不持久化。 */
    val rankChange: Int? = null
) {
    /**
     * 播放身份不能退化成 "平台:"：部分导入/搜索接口会缺 id，旧实现会让这些歌曲
     * 共用同一个缓存、队列索引和播放事务，直接造成点下一首仍播放上一首。
     */
    fun key(): String {
        val src = source.ifBlank { "wy" }.lowercase()
        if (id.isNotBlank()) return "$src:$id"
        fun norm(v: String) = v.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[|:#/\\\\]+"), "_")
        return "$src:meta:${norm(name)}:${norm(singer)}:$duration"
    }
    /**
     * compact=true（r658 云同步 413 修复）：省略空字符串/0 值的可选字段与重复别名 "source"；
     * fromJson 对缺失字段与空值等价处理（src 优先读取），因此语义无损，只是体积更小。
     */
    fun toJson(includeSyncMetadata: Boolean = false, includeLocalUri: Boolean = true, compact: Boolean = false): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("singer", singer)
        put("src", source)
        val safeCover = filterDeviceLocalReference(cover, includeLocalUri)
        if (!compact) {
            put("source", source); put("cover", safeCover)
            put("album", album); put("albumId", albumId); put("singerId", singerId); put("duration", duration); put("hash", hash)
            put("copyrightId", copyrightId)
            put("mediaMid", mediaMid)
            put("miguSongId", miguSongId)
        } else {
            if (safeCover.isNotEmpty()) put("cover", safeCover)
            if (album.isNotEmpty()) put("album", album)
            if (albumId.isNotEmpty()) put("albumId", albumId)
            if (singerId.isNotEmpty()) put("singerId", singerId)
            if (duration > 0L) put("duration", duration)
            if (hash.isNotEmpty()) put("hash", hash)
            if (copyrightId.isNotEmpty()) put("copyrightId", copyrightId)
            if (mediaMid.isNotEmpty()) put("mediaMid", mediaMid)
            if (miguSongId.isNotEmpty()) put("miguSongId", miguSongId)
        }
        kgAlbumMetadata?.validatedFor(this@Song)?.let { put("kgAlbumMetadata", it.toJson()) }
        // 本地歌单/喜欢中的删除文件依赖稳定的本地 URI；旧版遗漏它，重启后只能“从歌单删除”。
        if (includeLocalUri && isDeviceLocalReference(resolvedUrl)) put("resolvedUrl", resolvedUrl)
        if (playedAt > 0L) put("playedAt", playedAt)
        if (includeSyncMetadata && syncAddedAt > 0L) put("_swAddedAt", syncAddedAt)
        if (playSource.isNotBlank()) put("playSrc", playSource.take(PLAY_SOURCE_MAX))
        // r658b 第 2 项：本地缓存（首页 / 详情快照）保留平台最高音质键，冷启动后音质标识不丢；同步包不写（控制体积）。
        if (!includeSyncMetadata && maxQuality.isNotBlank()) put("mq", maxQuality)
    }
    companion object {
        /** fallbackSrc 是调用方显式声明的请求平台；响应缺来源时用它补位，绝不默认网易云。 */
        fun fromJson(o: JSONObject, fallbackSrc: String = ""): Song {
            val id = orChain(o, "id", "songmid", "songId", "song_id", "mid", "hash")
            val al = o.optJSONObject("al") ?: o.optJSONObject("albumInfo")
            val ar = o.optJSONArray("ar")
            val singerFromAr = if (ar != null) (0 until ar.length()).mapNotNull { decodeProviderText(ar.optJSONObject(it)?.optString("name").orEmpty()).takeIf { n -> n.isNotBlank() } }.joinToString("/") else ""
            // 与 singerFromAr 同源：ar 第一位即主歌手，其 id 与 primaryArtist 语义一致（wy 载荷）。
            val singerIdFromAr = ar?.optJSONObject(0)?.optString("id")?.takeUnless { it == "null" }.orEmpty()
            return Song(
                id = id,
                name = decodeProviderText(orChain(o, "name", "songname", "songName", "title")),
                singer = decodeProviderText(orChain(o, "singer", "artist", "artistName", "singername").ifEmpty { singerFromAr }),
                source = orChain(o, "src", "source").ifEmpty { fallbackSrc },
                cover = normalizeCoverUrl(
                    orChain(o, "cover", "img", "pic", "picUrl", "album_pic")
                        .ifEmpty { miguArrayCover(o) }
                        .ifEmpty { al?.optString("picUrl").orEmpty() }
                        .ifEmpty { al?.optString("pic").orEmpty() }
                        .ifEmpty { al?.optString("cover").orEmpty() }
                ),
                album = decodeProviderText(o.optString("album").ifEmpty { al?.optString("name").orEmpty() }),
                albumId = orChain(o, "albumId", "album_id", "albumMid", "albummid")
                    .ifEmpty { al?.optString("id").orEmpty() }.ifEmpty { al?.optString("albumId").orEmpty() },
                // 平台原始歌手行/歌曲行透传时的歌手 ID 键（kg singerid、kw ARTISTID、tx singerMid 等）；
                // 都没有时回落 wy 形态的 ar[0].id。缺省空 = 导航按名兜底，向后兼容旧存量。
                singerId = orChain(o, "singerId", "singerid", "singerMid", "singerMID", "ARTISTID", "artistId")
                    .takeUnless { it == "null" }.orEmpty().ifEmpty { singerIdFromAr },
                duration = if (o.has("duration") || o.has("dt")) o.optLong("duration", o.optLong("dt", 0L)).coerceAtLeast(0L)
                    else xyz.cncnlp.soundwave.source.direct.secondsToDurationMs(o.optLong("interval", 0L)),
                hash = o.optString("hash"),
                copyrightId = o.optString("copyrightId"),
                mediaMid = orChain(o, "mediaMid", "media_mid"),
                resolvedUrl = orChain(o, "resolvedUrl", "localUri"),
                playedAt = o.optLong("playedAt", 0L),
                syncAddedAt = o.optLong("_swAddedAt", 0L),
                playSource = o.optString("playSrc").trim().take(PLAY_SOURCE_MAX),
                miguSongId = o.optString("miguSongId").takeUnless { it == "null" }.orEmpty(),
                maxQuality = o.optString("mq").ifBlank { xyz.cncnlp.soundwave.source.direct.inferMaxQuality(o) }
            ).let { song -> song.copy(kgAlbumMetadata = KgAlbumMetadata.fromJson(song, o.opt("kgAlbumMetadata"))) }
        }
        const val PLAY_SOURCE_MAX = 40
    }
}

/** migu 原始条目兜底（服务端按歌手搜索可能直传 imgItems/albumImgs）：取最后一张非空 img。 */
private fun miguArrayCover(o: JSONObject): String {
    for (key in listOf("imgItems", "albumImgs")) {
        val arr = o.optJSONArray(key) ?: continue
        for (i in arr.length() - 1 downTo 0) {
            val img = arr.optJSONObject(i)?.optString("img").orEmpty()
            if (img.isNotEmpty() && img != "null") return img
        }
    }
    return ""
}

/** 榜卡叠层按榜单顺序保留前三首各自的封面槽（含空值）；调用方不得跨位冒充主封面。 */
@Suppress("UNUSED_PARAMETER")
internal fun rankCardCoverPool(songs: List<Song>, source: String, name: String): List<String> =
    songs.take(3).map { it.cover }

data class Playlist(
    val id: String,
    val name: String,
    val cover: String = "",
    val songs: MutableList<Song> = mutableListOf(),
    val local: Boolean = false,
    /** 真实来源；空表示未经验证，禁止隐式标为网易云。 */
    val source: String = "",
    val hidden: Boolean = false,
    val author: String = "",
    val description: String = "",
    val count: Int = 0,
    /** 网易云歌单真实播放量（用于封面角标）。 */
    val playCount: Long = 0L,
    /** 用户从首页收藏的远程歌单：保留原始远程 id，避免本地收藏 id 覆盖来源身份。 */
    val remoteId: String = "",
    val favorited: Boolean = false,
    /** User-data cloud LWW marker for explicit playlist creation/restoration. */
    val syncCreatedAt: Long = 0L,
    /** User-data cloud ordering rank; null means this playlist predates synced ordering. */
    val syncOrder: Int? = null,
    /** Shared revision for one completed playlist reorder; stored inside the pls element. */
    val syncOrderAt: Long = 0L,
    /** Portable cloud cover fallback while a device-local custom cover remains visible locally. */
    val syncCover: String = "",
    /** Ephemeral detail origin: digital/new_album; never inferred from a song or artist album. */
    val detailContentKind: String = ""
)

/** 远程平台歌单只能使用它自己的封面；缺失时不以某首歌曲的图冒充。榜单/本地列表可用真实曲目作代表图。 */
internal fun playlistDisplayCover(playlist: Playlist, songs: List<Song> = playlist.songs): String {
    normalizeCoverUrl(playlist.cover).takeIf { it.isNotBlank() }?.let { return it }
    // A missing source does not make a provider playlist local. Never relabel the first track's art as playlist art.
    val canUseRepresentative = playlist.local || playlist.id.startsWith("rank_") || playlist.id.startsWith("hot_")
    return if (canUseRepresentative) {
        songs.firstOrNull { it.cover.isNotBlank() }?.cover?.let(::normalizeCoverUrl).orEmpty()
    } else ""
}

/** Merge a same-provider full detail response without replacing user-facing local identity. */
internal fun mergePlaylistDetailSnapshot(
    base: Playlist,
    fallback: Playlist,
    songs: List<Song>,
    id: String,
    source: String,
    cover: String,
    local: Boolean = fallback.local,
    countFloor: Int = fallback.count,
    remoteId: String = id,
    favorited: Boolean = fallback.favorited,
    playCount: Long = fallback.playCount,
): Playlist = base.copy(
    id = id,
    name = base.name.ifBlank { fallback.name },
    cover = cover,
    songs = songs.toMutableList(),
    local = local,
    source = source,
    author = base.author.ifBlank { fallback.author },
    description = base.description.ifBlank { fallback.description },
    count = maxOf(countFloor, base.count, songs.size),
    remoteId = remoteId,
    favorited = favorited,
    playCount = playCount,
)

/** Store fetched provider metadata on a favorite while preserving its custom title and source. */
internal fun Playlist.withPlaylistDetailMetadata(
    metadata: Playlist,
    songs: List<Song>,
    cover: String,
    count: Int,
): Playlist = copy(
    songs = songs.toMutableList(),
    cover = cover,
    count = count,
    author = metadata.author.ifBlank { author },
    description = metadata.description.ifBlank { description },
)

data class DiscoverCard(
    val id: String,
    val name: String,
    val cover: String = "",
    val artist: String = "",
    val kind: String = "",
    val title: String = "",
    /** 新歌卡片的专辑名；不能把“新歌首发”标签当作专辑名。 */
    val album: String = "",
    /** 真实来源；空表示未经验证，禁止隐式标为网易云。 */
    val source: String = "",
    val songId: String = "",
    /** 歌曲/曲目数量（数字专辑/新碟首发卡片用）。 */
    val count: Int = 0
)

/**
 * 发现数据的可观察边界：请求平台、真实返回内容与本地缓存必须分开。
 * 不保存请求 URL、Cookie 或上游响应；无记录表示该平台没有可展示的真实数据。
 */
data class FeedSourceState(
    val source: String,
    val fetchedAt: Long = 0L,
    val refreshedAt: Long = 0L,
    val hotCount: Int = 0,
    val rankCount: Int = 0,
    val playlistCount: Int = 0,
    val lastError: String = "",
    /** Last successful featured-playlist response, including a valid empty response. */
    val featuredPlaylistFetchedAt: Long = 0L,
    /** Last successful non-empty hot-song response; zero/missing legacy timestamps are stale. */
    val hotSongsFetchedAt: Long = 0L,
    /** Last successful non-empty rank-catalog response; zero/missing legacy timestamps are stale. */
    val rankBoardsFetchedAt: Long = 0L,
    /** Last successful hot-keyword response, including a valid empty response. */
    val hotKeywordsFetchedAt: Long = 0L
) {
    val hasData: Boolean get() = hotCount > 0 || rankCount > 0 || playlistCount > 0
    val isCached: Boolean get() = fetchedAt > 0L && refreshedAt <= 0L
}

data class RankBoard(
    val name: String,
    /** 真实来源；空表示未经验证，禁止隐式标为网易云。 */
    val source: String = "",
    val songs: List<Song> = emptyList(),
    val id: String = "",
    val cover: String = "",
    /** 接口真实曲数；优先于 songs.size（入口列表常不带全量 songs） */
    val count: Int = 0,
    /** 平台提供的真实榜单更新时间（epoch ms）；null 表示未知，不能用抓取时间填充。 */
    val updatedAt: Long? = null
) {
    fun songCount(): Int = when {
        count > 0 -> count
        songs.isNotEmpty() -> songs.size
        else -> 0
    }
    val previewCover: String get() = songs.firstOrNull()?.cover?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty { cover }
}

/** Only the provider's explicitly signed numeric rankChange is meaningful; zero is commonly an unknown placeholder. */
internal fun explicitRankChange(o: JSONObject): Int? {
    val raw = o.opt("rankChange") as? Number ?: return null
    val value = raw.toDouble()
    if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
    return value.toInt().takeIf { it != 0 }
}

/** Parse an actual provider updateTime only; numeric strings must be exact epoch seconds/ms, local dates are Beijing time. */
internal fun providerUpdateTime(o: JSONObject, key: String = "updateTime"): Long? {
    val value = when (val raw = o.opt(key)) {
        is Number -> {
            val number = raw.toDouble()
            if (!number.isFinite() || number % 1.0 != 0.0 || number <= 0.0 || number > Long.MAX_VALUE.toDouble()) return null
            number.toLong()
        }
        is String -> {
            val text = raw.trim()
            if (text.matches(Regex("\\d{10}")) || text.matches(Regex("\\d{13}"))) {
                text.toLongOrNull() ?: return null
            } else {
                val patterns = listOf("yyyy-MM-dd HH:mm:ss")
                var parsed: Long? = null
                for (pattern in patterns) {
                    val format = java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT).apply {
                        isLenient = false
                        timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                    }
                    val position = java.text.ParsePosition(0)
                    val date = format.parse(text, position)
                    if (date != null && position.index == text.length) { parsed = date.time; break }
                }
                parsed ?: return null
            }
        }
        else -> return null
    }
    return when {
        value in 1_000_000_000L..9_999_999_999L -> value * 1000L
        value in 1_000_000_000_000L..9_999_999_999_999L -> value
        else -> null
    }
}

/** 榜单右侧只显示来源明确的更新时间；未知时返回空，不猜周期、抓取时间或“刚刚更新”。 */
fun RankBoard.officialUpdateLabel(): String {
    val timestamp = updatedAt?.takeIf { it > 0L } ?: return ""
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).apply {
        // Provider update timestamps use platform time; render consistently in China Standard Time.
        timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    }
    return "更新于 ${formatter.format(java.util.Date(timestamp))}（北京时间）"
}

data class Script(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val builtin: Boolean = false,
    val code: String = "",
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("version", version); put("author", author); put("builtin", builtin); put("enabled", enabled)
    }
    companion object {
        fun fromJson(o: JSONObject) = Script(
            o.optString("id"), o.optString("name"), o.optString("description"),
            o.optString("version"), o.optString("author"), o.optBoolean("builtin", false),
            "", o.optBoolean("enabled", true)
        )
    }
}

data class DownloadItem(
    val song: Song,
    val path: String,
    val size: Long,
    val time: Long,
    val status: String = "done", // downloading | done | failed
    val progress: Float = 1f,
    val error: String = "",
    /** 解析真实来源，下载记录可解释音质/脚本/候选，不保存 URL 或凭据。 */
    val quality: String = "",
    val platform: String = "",
    val scriptId: String = "",
    val resolveFailure: String = "",
    val taskId: String = ""
)

enum class PlayMode { SEQUENCE, LOOP_ONE, SHUFFLE, HEART }

internal fun nextPlayMode(mode: PlayMode): PlayMode = when (mode) {
    PlayMode.SEQUENCE -> PlayMode.LOOP_ONE
    PlayMode.LOOP_ONE -> PlayMode.SHUFFLE
    PlayMode.SHUFFLE -> PlayMode.HEART
    PlayMode.HEART -> PlayMode.SEQUENCE
}

object Platforms {
    const val WY = "wy"; const val KG = "kg"; const val KW = "kw"; const val TX = "tx"; const val MG = "mg"
    val ALL = listOf(WY, KG, KW, TX, MG)
    fun label(src: String): String = when (src) {
        WY -> "网易云"; KG -> "酷狗"; KW -> "酷我"; TX -> "QQ音乐"; MG -> "咪咕"; else -> src
    }
}

object Qualities {
    fun cascade(start: String = "320k"): List<String> {
        val map = mapOf(
            "master" to listOf("master", "flac", "320k", "128k"),
            "dolby" to listOf("dolby", "flac", "320k", "128k"),
            "hires" to listOf("hires", "flac", "320k", "128k"),
            "flac24bit" to listOf("flac24bit", "flac", "320k", "128k"),
            "flac" to listOf("flac", "320k", "128k"),
            "320k" to listOf("320k", "128k", "flac"),
            "320" to listOf("320k", "128k", "flac"),
            "192k" to listOf("192k", "128k", "320k"),
            "128k" to listOf("128k", "320k", "192k"),
            "128" to listOf("128k", "320k", "192k")
        )
        return (map[start] ?: map["320k"]!!).take(4)
    }
}

/** 歌手详情「专辑」横排条目：id 与对应平台专辑详情接口一致；date 形如 2026.08.05（可空）。 */
data class ArtistAlbum(
    val id: String,
    val name: String,
    val cover: String = "",
    val date: String = "",
    val count: Int = 0,
    val source: String = "",
    val kind: String = "album"
)
