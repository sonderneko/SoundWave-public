package xyz.cncnlp.soundwave

/**
 * r657 P0-1：崩溃报告脱敏。写盘与上传前都会经过这里，纯函数便于 JVM 单测。
 *
 * 处理范围：
 * - URL：去掉 userinfo、query 与 fragment（保留 scheme/host/path，路径里的用户目录另行替换）。
 * - 凭据：Authorization / Cookie / Set-Cookie 头整行值，token= / sign= / key= / cookie= 等参数值，
 *   Bearer 令牌，JWT 形态串。
 * - 歌曲 key：`source:id` 形态（wy/tx/kg/kw/mg/local/script:*）里的 id 部分。
 * - 邮箱、中国大陆手机号、Android ID（16 位 hex，及显式 androidId/deviceId 字段）。
 * - 本机路径：/data/user/N/<pkg>、/data/data/<pkg> 前缀；/storage/emulated/N、/sdcard 下整段路径；/home/<user>、/Users/<user>；
 *   content:// 与 file:// URI。
 */
object CrashReportRedactor {
    private const val MASK = "<redacted>"

    private val headerLine = Regex(
        "(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-token|x-request-key|x-card-key)\\s*[:=]\\s*).+$"
    )
    private val bearer = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val networkUrl = Regex("""(?i)\b((?:https?|wss?|ftp)://)([^\s/?#"'<>]+)([^\s?#"'<>]*)(?:[?#][^\s"'<>]*)?""")
    private val contentUri = Regex("(?i)\\b(content|file)://[^\\s\"'<>]+")
    private val secretParam = Regex(
        """(?i)(?<![\w])(["']?)(access_token|refresh_token|token|sign|signature|key|apikey|api_key|secret|password|pass|pwd|cookie|session|sessionid|auth|authorization|proxy-authorization|set-cookie|x-token|x-request-key|x-card-key|uin|qqmusic_key|musicu|vkey|guid|deviceid|device_id|androidid|android_id|imei|oaid)\1(\s*[=:]\s*)"""
    )
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phone = Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])")
    private val androidIdHex = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{16}(?![0-9a-fA-F])")
    private val songKey = Regex("(?i)\\b(wy|tx|kg|kw|mg|local|cloud|script:[A-Za-z0-9_.-]{1,64}):([A-Za-z0-9_.%-]{3,})")
    private val appDataPath = Regex("/data/(?:user(?:_de)?/[0-9]+|data)/[A-Za-z0-9_.]+")
    // 外部存储路径整段替换：用户自建目录/文件名可能含真实姓名。
    private val externalPath = Regex("/(?:storage/emulated/[0-9]+|storage/self/primary|sdcard|mnt/sdcard|storage/[0-9A-F]{4}-[0-9A-F]{4})(?:/[^\\s:\"'()<>]*)?")
    private val homePath = Regex("/(?:home|Users)/[^/\\s]+")

    // Scan quoted values instead of recursively matching escapes with a regex (large values stay linear).
    private fun redactParameters(text: String): String = buildString(text.length) {
        var cursor = 0
        while (cursor < text.length) {
            val match = secretParam.find(text, cursor) ?: break
            append(text, cursor, match.range.last + 1)
            var end = match.range.last + 1
            val quote = text.getOrNull(end)?.takeIf { it == '\"' || it == '\'' }
            if (quote != null) {
                append(quote)
                end++
                var closed = false
                while (end < text.length) {
                    val char = text[end++]
                    if (char == '\\' && end < text.length) end++
                    else if (char == quote) {
                        closed = true
                        break
                    }
                }
                append(MASK)
                if (closed) append(quote)
            } else {
                while (end < text.length && !text[end].isWhitespace() && text[end] !in "&,;\"'}]") end++
                append(MASK)
            }
            cursor = end
        }
        append(text, cursor, text.length)
    }

    fun redact(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = networkUrl.replace(raw) {
            it.groupValues[1] + it.groupValues[2].substringAfterLast('@') + it.groupValues[3]
        }
        s = headerLine.replace(s) { it.groupValues[1] + MASK }
        s = bearer.replace(s) { it.groupValues[1] + " " + MASK }
        s = jwt.replace(s, MASK)
        s = contentUri.replace(s) { it.groupValues[1].lowercase() + "://" + MASK }
        s = redactParameters(s)
        s = email.replace(s, "<email>")
        s = phone.replace(s, "<phone>")
        s = appDataPath.replace(s, "<app-data>")
        s = externalPath.replace(s, "<external-path>")
        s = homePath.replace(s, "<home>")
        s = songKey.replace(s) { it.groupValues[1] + ":" + MASK }
        s = androidIdHex.replace(s, "<hex16>")
        return s
    }
}
