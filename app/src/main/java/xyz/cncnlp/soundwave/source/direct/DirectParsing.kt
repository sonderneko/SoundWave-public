package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import xyz.cncnlp.soundwave.normalizeCoverUrl

/**
 * 直连搜索共享解析工具（Local-first 批2，自 MgDirectSearch.kt 原样抽取）。
 * 均为 /opt/soundwave/music_server.py 对应函数的 1:1 移植，供各平台直连源复用
 * （同包顶层 internal 声明，直接引用无需 import）：
 * - cleanSpace/unescapeHtml/WS_ALL/CJK_GAP：服务端 clean_space 与 html.unescape 等价实现
 * - shrinkCover：服务端 shrink_cover(c, size=1080)
 * - scoreSong 与 COVER_BAD/KNOWN_ORIGINALS/PAREN_ANY/PAREN_LAZY：服务端 score_song
 */

/** 服务端 clean_space：HTML 实体还原 → 折叠空白 → 去掉汉字之间空格。 */
internal fun cleanSpace(value: String): String {
    val s = decodeProviderEntities(value).trim()
    val collapsed = WS_ALL.matcher(s).replaceAll(" ")
    return CJK_GAP.matcher(collapsed).replaceAll("")
}

/**
 * Provider 文本有时把实体再包进一层 `&amp;`，简介还会带真实 `<br>` 标记。
 * 最多解三层，避免无限解释用户数据；换行与普通文本保留，NBSP 统一成可见普通空格。
 */
internal fun decodeProviderMetadata(value: String): String {
    return decodeProviderEntities(value, stripMarkup = true)
}

/** Decode provider entities in ordinary song text without interpreting literal markup as tags. */
internal fun decodeProviderEntities(value: String, stripMarkup: Boolean = false): String {
    if (value.isEmpty()) return value
    var decoded = decodeJsonUnicodeEscapes(value)
    // Existing song/query text helpers intentionally decode one HTML layer; only provider metadata
    // opts into bounded recursive decoding so playlist titles/descriptions can clear double escapes.
    repeat(if (stripMarkup) 3 else 1) {
        val input = if (stripMarkup) stripProviderMarkup(decoded) else decoded
        val next = unescapeHtml(input)
        if (next == decoded) return input.replace('\u00A0', ' ')
        decoded = next
    }
    val result = if (stripMarkup) stripProviderMarkup(decoded) else decoded
    return result.replace('\u00A0', ' ')
}

private fun stripProviderMarkup(value: String): String = value
    .replace(Regex("(?i)<br\\s*/?>|</(?:p|div|li)\\s*>"), "\n")
    .replace(Regex("(?is)<[^>]+>"), "")

/** JSON \\uXXXX 字面转义匹配（批23a #B）。批23c：前导反斜杠 1 个或多个全部吃掉——上游双层/多层
 * JSON 序列化时 `\\\\u0026`（文本 2 反斜杠）只解一层会残留 `\\` 前缀（用户实测看到 `\\&`）。 */
private val JS_UNICODE_ESCAPE = Regex("\\\\+u([0-9a-fA-F]{4})")

/**
 * 还原 \uXXXX 字面转义（批23a #B）：上游对已解码文本二次 JSON 序列化时，org.json 只解一层，
 * 歌名/歌手会露出 “\u0026” 字面。仅处理反斜杠变体（“/uXXXX” 仍由 decodeProviderText 兼容），
 * 无反斜杠的正常文本原样返回；\uD83D\uDE00 之类代理对两次转义自然拼回。
 */
internal fun decodeJsonUnicodeEscapes(value: String): String {
    if (!value.contains('\\')) return value
    return JS_UNICODE_ESCAPE.replace(value) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }
}

/**
 * python3 `str` 的 `\s` 等价字符类。
 * ⚠️ Android 不支持 `Pattern.UNICODE_CHARACTER_CLASS`（java.util.regex 私有 flag，ART 的
 * Pattern 实现直接抛 IllegalArgumentException），必须展开成显式字符类，否则类初始化即崩
 * （1.0.329 真机 P0：全平台解析不可用 + 搜索页崩溃）。
 * 覆盖 python3 unicode `\s`：空格/制表/换行/垂直制表/换页/回车/\x1c-\x1f/\x85/\xa0/
 * \u1680/\u2000-\u200a/\u2028/\u2029/\u202f/\u205f/\u3000。
 */
internal const val PY_WS = "[ \\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x85\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

/** python3 unicode `\d` 等价(数字 + 全角数字,够用于歌单名尾数字清洗)。 */
internal const val PY_DIGIT = "[0-9\\uFF10-\\uFF19]"

internal val WS_ALL = java.util.regex.Pattern.compile("$PY_WS+")
internal val CJK_GAP = java.util.regex.Pattern.compile("(?<=[\\u4e00-\\u9fa5])$PY_WS+(?=[\\u4e00-\\u9fa5])")

/** Decode one HTML/XML entity layer only; unknown entities and literal ampersands remain untouched. */
internal fun unescapeHtml(value: String): String {
    val named = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "nbsp" to "\u00A0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
        "copy" to "©", "reg" to "®", "trade" to "™", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "bull" to "•", "middot" to "·", "laquo" to "«", "raquo" to "»", "euro" to "€",
        "pound" to "£", "yen" to "¥", "cent" to "¢", "times" to "×", "divide" to "÷",
        "para" to "¶", "sect" to "§", "deg" to "°", "plusmn" to "±"
    )
    val entity = Regex("&(?:#x([0-9a-fA-F]+)|#(\\d+)|([A-Za-z][A-Za-z0-9]+));", RegexOption.IGNORE_CASE)
    return entity.replace(value) { match ->
        val replacement = when {
            match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty() -> {
                val code = if (match.groupValues[1].isNotEmpty()) match.groupValues[1].toIntOrNull(16)
                    else match.groupValues[2].toIntOrNull()
                if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) null
                else runCatching { String(Character.toChars(code)) }.getOrNull()
            }
            else -> named[match.groupValues[3].lowercase(java.util.Locale.ROOT)]
        }
        replacement ?: match.value
    }
}

/**
 * 批29-4：封面归一客户端单真源——shrinkCover 改为薄包装，全部规则并入
 * Models.normalizeCoverUrl（吸收点：空串保护 / // 协议 / Kugou 显式尺寸占位 / migu 截断过滤）。
 * J 波（验收三轮）：size 不再是摆设——透传为 normalizeCoverUrl 的 targetSizePx，
 * 列表面传小尺寸（如 300）避免每张列表图都整幅下载 1080 大图（封面不加载根因之一）；
 * 详情/大图面不传或传 1080，行为与此前一致。
 */
internal fun shrinkCover(cover: String, size: Int = 1080): String = normalizeCoverUrl(cover, targetSizePx = size)

/** 服务端 COVER_BAD（music_server.py:492）：疑似翻唱/DJ/伴奏等低质关键词。 */
internal val COVER_BAD = Regex(
    "(DJ|翻唱|降调|氛围|网友改编|伴奏|铃声|Live|现场|抖音|加速|喊麦|消音|KrixLee|片段|cover|改编|慢摇|阿lo|阿若|阿树|小桑)",
    RegexOption.IGNORE_CASE,
)

/** 服务端 KNOWN_ORIGINALS（music_server.py:1469）：歌名 → 原唱。 */
internal val KNOWN_ORIGINALS: Map<String, String> = mapOf("剥离之茧" to "添儿呗")

internal val PAREN_ANY = Regex("[\\(（].+[\\)）]")
internal val PAREN_LAZY = Regex("[\\(（].*?[\\)）]")

/** 服务端 score_song 的 1:1 移植（含 KNOWN_ORIGINALS 加权与「剥离之茧」专项加分）。 */
internal fun scoreSong(query: String, songName: String, singer: String): Int {
    val q = query.trim()
    val name = songName.trim()
    val artist = singer.trim()
    var score = 0
    if (name == q) {
        score += 120
    } else if (q.isNotEmpty() && name.contains(q)) {
        score += 30
    }
    if (COVER_BAD.containsMatchIn(name)) score -= 120
    if (COVER_BAD.containsMatchIn(artist)) score -= 40
    if (PAREN_ANY.containsMatchIn(name)) score -= 50
    val base = PAREN_LAZY.replace(name, "").trim()
    if (base == q) score += 40
    if (q.isNotEmpty() && artist.isNotEmpty() && q.contains(artist)) score += 15
    for ((song, originalArtist) in KNOWN_ORIGINALS) {
        if (q == song || base == song) {
            if (artist.contains(originalArtist) && !COVER_BAD.containsMatchIn(name) && base == song) {
                score += 200
            }
        }
    }
    if (q == "剥离之茧" && artist.contains("添儿呗") && base == "剥离之茧" && !COVER_BAD.containsMatchIn(name)) {
        score += 80
    }
    return score
}

/**
 * 服务端 _kuwo_rid（music_server.py:1519-1524）：trim → 剥 MUSIC_ 前缀 → 仅保留 ASCII 数字。
 * 批29-1 #B-5：KwDirectSearch.kwRid 与 KuwoDirectResolve.normalizeKuwoRid 两份 1:1 拷贝合并于此
 * （startsWith+substring ≡ removePrefix，filter '0'..'9' 相同，逐字等价）。LyricDirectFetch.kuwoRid
 * 用 Char.isDigit()（含全角等 Unicode Nd 数字）而非 ASCII 范围，口径有细微差异——按「行为不变」
 * 原则，该差异保留在 LyricDirectFetch 调用方，不并入本共享函数。
 */
internal fun kuwoRid(value: String): String =
    value.trim().removePrefix("MUSIC_").filter { it in '0'..'9' }

/** 服务端 JSONP 剥壳（贪婪，首 { 至尾 }）。批29-1 #B-9：LyricDirectFetch.TX_JSONP_BODY /
 * AlbumDirectFetch.TX_ALBUM_JSONP / PlaylistDetailDirect.TX_CDINFO_JSONP 三份同正则合并于此。 */
internal val TX_JSONP_BODY = Regex("\\{[\\s\\S]*\\}")

/**
 * 服务端名字尾数字清洗（music_server.py add() :3383，re.sub(r'\s*\d+$')）：去掉名字尾部的空白+数字。
 * ⚠️ 不可用 Pattern.UNICODE_CHARACTER_CLASS（Android 不支持，类初始化崩溃，1.0.329 P0），
 * 改用 PY_WS/PY_DIGIT 显式字符类等价 python3 unicode 语义。批29-1 #B-10：
 * PlaylistDirectSearch.PL_NAME_TRAILING_DIGITS 与 PlaylistDetailDirect.DETAIL_NAME_TRAILING_DIGITS
 * 模式串逐字相同，合并于此。
 */
internal val PL_NAME_TRAILING_DIGITS = java.util.regex.Pattern.compile("$PY_WS*$PY_DIGIT+$")

// ── 批29-2：or-chain 助手收敛（共享区，以下两函数自各平台直连源合并）────────────────────

/**
 * 服务端 `a or b` 的等价取值（空串视为 falsy，与 python 语义一致）：仅「非空」语义，不排除 "null"/"0"。
 * 批29-2 #A：AlbumDirectFetch.kt:47 与 PlaylistDetailDirect.kt:55 两份 private 实现逐字相同
 *（仅注释措辞不同），合并于此。需要排除字符串 "null"/"0" 的场景请用 orChain。
 */
internal fun firstStr(o: JSONObject, vararg keys: String): String {
    for (key in keys) {
        val value = o.optString(key)
        if (value.isNotEmpty()) return value
    }
    return ""
}

/**
 * 服务端 `a.get(k1) or a.get(k2) or ...` 的回退语义：空串 / "null" / "0" 视为缺失（0 与 python falsy 一致）。
 * 批29-2 #B：PlaylistDirectSearch.orChain(:384) / RankDirectFetch.rankOrChain(:642) /
 * MgDirectSearch.firstNonEmpty(:116) 三份实现逐字等价（JSONObject.NULL 经 optString 得 "null" 被排除、
 * 字符串 "null"/"0" 被排除、空串被排除，三者处理完全一致），合并于此；参数名沿用 obj。
 */
internal fun orChain(obj: JSONObject, vararg keys: String): String {
    for (key in keys) {
        val value = obj.optString(key)
        if (value.isNotEmpty() && value != "null" && value != "0") return value
    }
    return ""
}

// #C 不并入说明：KwDirectSearch.kwOrChain(:206) 与上面 orChain「不」等价——它只跳过 JSON null
//（obj.isNull）与空串，字符串 "null" 与 "0" 被当作合法值返回（例：kw 歌单 time/duration 值为 "0" 时
// kwOrChain 返回 "0"，orChain 则跳过续取下一 key）。按「行为不变」原则保留在 KwDirectSearch.kt 原位。

/**
 * r658b 第 2 项（用户 10-19：首页热门歌曲 / 详情 / 搜索歌曲行缺音质标识）：从各平台列表条目推断最高可用音质键
 * （master / hires / flac / 320k；推断不出返回空串 = 不显示标识）。只读平台已返回的档位字段，不造数据。
 *  - 网易云：hr / sq / h 对象、privilege.maxBrLevel / maxbr
 *  - 酷我：formats / N_MINFO / MINFO（HIRFLAC / ALFLAC / MP3H、bitrate:4000/2000/320）
 *  - 酷狗：ResFileHash / SQFileHash / HQFileHash、sqhash / 320hash、res_hash
 *  - QQ：file.size_hires / size_flac / size_320mp3（或扁平 sizehires / sizeflac / size320）
 *  - 咪咕：audioFormats / newRateFormats / rateFormats 的 formatType（ZQ24 / SQ / HQ）、toneControl
 */
internal fun inferMaxQuality(o: JSONObject?): String {
    if (o == null) return ""
    fun nonEmptyHash(k: String) = o.optString(k).let { it.isNotBlank() && it != "null" && it.trim('0').isNotEmpty() }
    fun size(obj: JSONObject?, vararg ks: String) = obj != null && ks.any { obj.optLong(it) > 0L }
    // 网易云
    if (o.optJSONObject("hr") != null) return "hires"
    o.optJSONObject("privilege")?.let { p ->
        when (p.optString("maxBrLevel").ifEmpty { p.optString("maxbrlevel") }.lowercase()) {
            "jymaster" -> return "master"; "hires" -> return "hires"; "lossless" -> return "flac"; "exhigh" -> return "320k"
        }
        val br = p.optInt("maxbr")
        if (br >= 999_000) return "flac"; if (br >= 320_000) return "320k"
    }
    if (o.optJSONObject("sq") != null) return "flac"
    if (o.optJSONObject("h") != null) return "320k"
    // 酷我
    val kw = (o.optString("formats") + "|" + o.optString("N_MINFO") + "|" + o.optString("MINFO")).uppercase()
    if ("HIRFLAC" in kw || "BITRATE:4000" in kw) return "hires"
    if ("ALFLAC" in kw || "BITRATE:2000" in kw) return "flac"
    if ("MP3H" in kw || "BITRATE:320" in kw) return "320k"
    // 酷狗
    if (nonEmptyHash("ResFileHash") || nonEmptyHash("res_hash")) return "hires"
    if (nonEmptyHash("SQFileHash") || nonEmptyHash("sqhash")) return "flac"
    if (nonEmptyHash("HQFileHash") || nonEmptyHash("320hash")) return "320k"
    // QQ
    val file = o.optJSONObject("file")
    if (size(file, "size_hires") || size(o, "sizehires", "size_hires")) return "hires"
    if (size(file, "size_flac") || size(o, "sizeflac", "size_flac")) return "flac"
    if (size(file, "size_320mp3", "size_320") || size(o, "size320", "size_320mp3")) return "320k"
    // 咪咕
    val types = HashSet<String>()
    for (k in listOf("audioFormats", "newRateFormats", "rateFormats")) {
        val arr = o.optJSONArray(k) ?: continue
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("formatType")?.uppercase()?.let(types::add)
    }
    o.optString("toneControl").uppercase().takeIf { it.isNotBlank() }?.let(types::add)
    if ("ZQ24" in types || "ZQ32" in types || "Z3D" in types) return "hires"
    if ("SQ" in types) return "flac"
    if ("HQ" in types) return "320k"
    return ""
}
