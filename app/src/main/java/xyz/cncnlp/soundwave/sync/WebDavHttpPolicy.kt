package xyz.cncnlp.soundwave.sync

import android.content.Context
import java.net.URI

/**
 * WebDAV http 例外策略（用户 2026-11-03 20:48 拍板的新方案）。
 *
 * 默认仍为 HTTPS-only，**绝不全局放开** http：用户填写 http:// 地址时，经 DesignSheet
 * 风险确认后，按 (scheme + host + port) 为**单个服务器**记住例外；传输校验
 * （[normalizeWebDavBaseUrl]）查例外表放行，其余 http 地址一律拒绝。例外可撤销；
 * 更换服务器地址（host/port 不同）不会命中旧例外，必须重新确认。
 *
 * 例外键格式：`http|<host 小写>|<有效端口>`，存放在与 WebDAV 凭据相同的
 * 共享配置（prefs `soundwave_webdav`）中，随「忘记登录信息」之外的配置独立存续。
 */
internal object WebDavHttpPolicy {

    enum class HostClass {
        /** 公网主机：明文将经过互联网，警告加重一级。 */
        PUBLIC,

        /** 局域网 / 私有地址段 / .local / 环回：常规版风险文案。 */
        PRIVATE_OR_LOCAL
    }

    /** 解析端点三元组；无法解析（无 scheme/host、带 userinfo/query/fragment）返回 null。 */
    private fun parseEndpoint(raw: String): Triple<String, String, Int>? {
        try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            if (uri.isOpaque || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port >= 0) uri.port else when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> return null
            }
            return Triple(scheme, host, port)
        } catch (_: Exception) {
            return null
        }
    }

    /** http 地址的例外键；非 http 或不可解析时为 null（https 永不需要例外）。 */
    fun exceptionKey(rawUrl: String): String? {
        val (scheme, host, port) = parseEndpoint(rawUrl) ?: return null
        if (scheme != "http") return null
        return "http|$host|$port"
    }

    /** 该 http 地址是否已被用户按服务器确认例外。 */
    fun exceptionApplies(rawUrl: String, exceptions: Set<String>): Boolean {
        val key = exceptionKey(rawUrl) ?: return false
        return key in exceptions
    }

    /** URL 的主机名（小写）；不可解析时为 null。 */
    fun hostOf(rawUrl: String): String? = parseEndpoint(rawUrl)?.second

    /** 风险分级：公网主机警告加重；局域网/私有/.local/环回为常规版。 */
    fun hostClass(rawUrl: String): HostClass {
        val host = hostOf(rawUrl) ?: return HostClass.PUBLIC
        return hostClassOfHost(host)
    }

    fun hostClassOfHost(rawHost: String): HostClass {
        var host = rawHost.trim().lowercase().trimEnd('.')
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        if (host == "localhost" || host.endsWith(".local")) return HostClass.PRIVATE_OR_LOCAL
        // IPv4 映射的 IPv6（::ffff:a.b.c.d）按内嵌的 v4 地址分类。
        if (host.startsWith("::ffff:")) {
            return ipv4Class(host.removePrefix("::ffff:")) ?: HostClass.PUBLIC
        }
        ipv4Class(host)?.let { return it }
        if (host.contains(':')) return ipv6Class(host)
        return HostClass.PUBLIC
    }

    private fun ipv4Class(host: String): HostClass? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
        val (a, b) = octets
        return when {
            a == 10 -> HostClass.PRIVATE_OR_LOCAL // 10.0.0.0/8
            a == 172 && b in 16..31 -> HostClass.PRIVATE_OR_LOCAL // 172.16.0.0/12
            a == 192 && b == 168 -> HostClass.PRIVATE_OR_LOCAL // 192.168.0.0/16
            a == 127 -> HostClass.PRIVATE_OR_LOCAL // 127.0.0.0/8 环回
            a == 169 && b == 254 -> HostClass.PRIVATE_OR_LOCAL // 169.254.0.0/16 链路本地
            a == 100 && b in 64..127 -> HostClass.PRIVATE_OR_LOCAL // 100.64.0.0/10 CGNAT/overlay（如 Tailscale）
            else -> HostClass.PUBLIC
        }
    }

    private fun ipv6Class(host: String): HostClass {
        val normalized = host.lowercase()
        return when {
            normalized == "::1" || normalized == "::" -> HostClass.PRIVATE_OR_LOCAL // 环回/未指定
            // fe80::/10 链路本地：fe80–febf
            normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
                normalized.startsWith("fea") || normalized.startsWith("feb") -> HostClass.PRIVATE_OR_LOCAL
            // fc00::/7 唯一本地地址：fc00–fdff
            normalized.startsWith("fc") || normalized.startsWith("fd") -> HostClass.PRIVATE_OR_LOCAL
            else -> HostClass.PUBLIC
        }
    }

    /**
     * 该端点是否已处于规范形态（供自动同步绑定校验：https 走 [normalizeWebDavBaseUrl]
     * 等式；http 例外端点在此做结构校验——是否真有例外由绑定时与每次请求时另行把关）。
     */
    fun isCanonicalHttpEndpoint(endpoint: String): Boolean {
        val (scheme, _, _) = parseEndpoint(endpoint) ?: return false
        if (scheme != "http") return false
        return try {
            URI(endpoint).toASCIIString().trimEnd('/') == endpoint
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * http 例外的持久化（prefs `soundwave_webdav`，与 [WebDavCredentialStore] 同一共享配置）。
 * 只存例外键集合，不存任何凭据；撤销即时生效于之后的所有校验（含自动同步请求时复检）。
 */
internal object WebDavHttpExceptionStore {
    private const val PREFS = "soundwave_webdav"
    private const val KEY_EXCEPTIONS = "http_exceptions"

    fun load(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCEPTIONS, emptySet())?.toSet() ?: emptySet()

    /** 为该 http 地址记下例外；非 http/不可解析时返回 false。 */
    fun grant(context: Context, rawUrl: String): Boolean {
        val key = WebDavHttpPolicy.exceptionKey(rawUrl) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = load(context) + key
        return prefs.edit().putStringSet(KEY_EXCEPTIONS, updated).commit()
    }

    /** 撤销该 http 地址的例外（地址已改写/不可解析时可用 [revokeKey] 按键撤销）。 */
    fun revoke(context: Context, rawUrl: String): Boolean {
        val key = WebDavHttpPolicy.exceptionKey(rawUrl) ?: return false
        return revokeKey(context, key)
    }

    fun revokeKey(context: Context, key: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = load(context) - key
        return prefs.edit().putStringSet(KEY_EXCEPTIONS, updated).commit()
    }
}
