package xyz.cncnlp.soundwave

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/** One hostname's exact public DNS answer, reused by OkHttp instead of resolving again. */
internal data class ValidatedScriptUrl(val host: String, val addresses: List<InetAddress>) {
    fun pinnedDns(): okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val normalized = hostname.lowercase().trimEnd('.')
            if (normalized != host) throw UnknownHostException("DNS policy is not bound to $hostname")
            return addresses.toList()
        }
    }
}

/** Direct target for the original URL; placeholder answers route via the system stack. */
internal data class ScriptUrlValidation(
    val validatedTarget: ValidatedScriptUrl?,
    /**
     * H3（已转默认）：DNS 应答为 fake-IP 占位集时，原生 OkHttp 用系统解析
     * 结果原样拨号（由 TUN 在网络层承接），不做分类、不做 pinning。
     * 只可能由占位分支置 true；真实公网应答仍走 pinned 直连，
     * 真实私网/混合应答仍拒绝——SSRF 边界不放松。服务端中转已退役，无其他出口。
     */
    val systemStackDirect: Boolean = false
)

/** Result of the optional script-only cleartext credential guard. */
internal data class ScriptCleartextCredentialDecision(
    val blocked: Boolean
)

/**
 * DNS-aware egress policy for untrusted LX scripts.
 * A syntactically public HTTPS hostname is insufficient: DNS rebinding can turn it
 * into a private/metadata address after validation, so every resolved address must
 * be public before OkHttp is allowed to connect.
 */
object ScriptNetworkPolicy {
    // LX 运行时保留脚本自带的鉴权/客户端标识头；旧版与洛雪会透传这些头。
    // Cookie、Host、代理控制等敏感 hop-by-hop 头一律过滤；宿主不再注入第三方平台账号凭证。
    private val allowedHeaders = setOf(
        "accept", "user-agent", "referer", "origin", "content-type",
        "x-requested-with", "x-request-key", "x-token", "x-client", "x-script-version", "x-card-key"
    )

    // P0 F-02: 会话 Cookie 不得回传脚本（防恶意脚本外发窃取）。
    fun isResponseHeaderAllowed(name: String): Boolean = name.trim().lowercase() in setOf(
        "location", "content-type", "content-length", "content-range", "accept-ranges",
        "etag", "last-modified", "cache-control"
    )

    fun filterHeaders(raw: Map<String, String>): Map<String, String> = raw.mapNotNull { (name, value) ->
        val normalized = name.trim().lowercase()
        if (normalized in allowedHeaders && value.isNotBlank()) name.trim() to value.trim() else null
    }.toMap(LinkedHashMap())

    /** Only token/key headers which are already part of LX's outgoing allowlist are sensitive here. */
    internal fun assessCleartextCredentialRequest(
        rawUrl: String,
        outgoingHeaderNames: Collection<String>,
        enabled: Boolean
    ): ScriptCleartextCredentialDecision {
        val isHttp = try {
            URI(rawUrl.trim()).scheme?.equals("http", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
        val hasSensitiveHeader = outgoingHeaderNames.any { name ->
            val normalized = name.trim().lowercase()
            normalized in allowedHeaders && (normalized.contains("token") || normalized.contains("key"))
        }
        val blocked = enabled && isHttp && hasSensitiveHeader
        return ScriptCleartextCredentialDecision(blocked)
    }

    /** Compare redirect origins using scheme, normalized host, and effective port. */
    internal fun isSameOrigin(fromUrl: String, toUrl: String): Boolean {
        val from = try { URI(fromUrl) } catch (_: Exception) { return false }
        val to = try { URI(toUrl) } catch (_: Exception) { return false }
        // URI retains input casing; HTTP(S) scheme matching is case-insensitive.
        val fromScheme = from.scheme?.lowercase() ?: return false
        val toScheme = to.scheme?.lowercase() ?: return false
        val fromHost = from.host?.lowercase()?.trimEnd('.') ?: return false
        val toHost = to.host?.lowercase()?.trimEnd('.') ?: return false
        fun effectivePort(uri: URI, scheme: String): Int? = when {
            uri.port >= 0 -> uri.port
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> null
        }
        return fromScheme == toScheme && fromHost == toHost &&
            effectivePort(from, fromScheme) != null &&
            effectivePort(from, fromScheme) == effectivePort(to, toScheme)
    }

    /** HTTPS requests may not follow a redirect to cleartext HTTP, even after header stripping. */
    internal fun isHttpsDowngrade(fromUrl: String, toUrl: String): Boolean {
        val from = try { URI(fromUrl) } catch (_: Exception) { return false }
        val to = try { URI(toUrl) } catch (_: Exception) { return false }
        return from.scheme?.equals("https", ignoreCase = true) == true &&
            to.scheme?.equals("http", ignoreCase = true) == true
    }

    /** Credential-like headers must not cross an origin boundary on direct redirects. */
    internal fun redirectCredentialHeaders(headerNames: Collection<String>, sameOrigin: Boolean): Set<String> {
        if (sameOrigin) return emptySet()
        val sensitiveFragments = listOf("cookie", "token", "key", "auth", "secret", "credential", "session", "password", "passwd", "bearer")
        return headerNames.filter { name ->
            val normalized = name.trim().lowercase()
            normalized in setOf("authorization", "proxy-authorization") ||
                sensitiveFragments.any { fragment -> normalized.contains(fragment) }
        }.toSet()
    }

    internal fun shouldFollowRedirect(
        statusCode: Int,
        sameOrigin: Boolean,
        hasNonEmptyBody: Boolean,
        hop: Int
    ): Boolean {
        if (statusCode !in 300..399 || hop >= 4) return false
        return statusCode !in setOf(307, 308) || sameOrigin || !hasNonEmptyBody
    }

    /**
     * Reject malformed, credential-bearing, unsupported, and local/special-use targets
     * before any egress. Valid public HTTP(S) targets resolve to a pinned direct
     * target; fake-IP placeholder answers resolve to the system-stack route.
     * There is no server relay.
     */
    internal fun inspectScriptUrl(
        rawUrl: String,
        resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): ScriptUrlValidation {
        fun rejected() = ScriptUrlValidation(validatedTarget = null)

        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) { return rejected() }
        val scheme = uri.scheme?.lowercase() ?: return rejected()
        if (scheme !in setOf("http", "https")) return rejected()
        // rawUserInfo also catches an explicitly empty userinfo (https://@host).
        if (uri.rawUserInfo != null) return rejected()
        // URI accepts numeric ports outside the usable TCP range; never proxy them.
        if (uri.port == 0 || uri.port > 65535) return rejected()
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return rejected()
        if (isLocalHostName(host)) return rejected()

        // Literal public IPs need no DNS lookup and are eligible for direct HTTP/HTTPS.
        // Mapped IPv6 and every local/reserved literal fail closed, including proxy fallback.
        if (looksLikeIpLiteral(host)) {
            // Leading-zero IPv4 octets are interpreted inconsistently (decimal vs octal).
            if (hasNonCanonicalIpv4Octets(host)) return rejected()
            if ('%' in host) return rejected()
            val literal = parseIpLiteral(host) ?: return rejected()
            val mappedIpv4Literal = host.contains(':') && literal is Inet4Address
            if (mappedIpv4Literal || !isPublicAddress(literal)) return rejected()
            return ScriptUrlValidation(
                validatedTarget = ValidatedScriptUrl(host.removePrefix("[").removeSuffix("]"), listOf(literal))
            )
        }

        // Short, integer, octal, and hexadecimal IPv4 spellings may be reinterpreted by
        // a proxy differently from this client. Reject them before DNS and both egress paths.
        if (looksLikeAmbiguousIpv4Literal(host)) return rejected()

        val addresses = try { resolver(host) } catch (_: Exception) { return rejected() }
        if (addresses.isEmpty()) return rejected()
        // Fake-IP placeholder answers: a local TUN proxy resolver (Clash/Surge style)
        // answers EVERY public hostname from the 198.18.0.0/15 benchmarking pool.
        // Those addresses are placeholders, not real targets and not a rebinding
        // pivot: pinned egress to them is meaningless, so no direct target is ever
        // issued. The hostname itself is still an ordinary public name, so the
        // request goes out through the system stack, whose resolver returns the
        // same placeholder answer for the TUN layer to translate.
        // This applies only when the placeholder range is the ENTIRE answer; any
        // answer outside it (public, private, metadata, or a mix) keeps the strict
        // rejection below.
        // sing-box-style stacks additionally answer AAAA queries from their
        // fc00::/18 fake-IP pool. Those v6 answers count as placeholders ONLY
        // alongside a 198.18/15 v4 answer (the anchor below): ULA space is also
        // legitimate private addressing, so a v6-only answer is never
        // reclassified and keeps the strict rejection below.
        if (addresses.any { isFakeIpPlaceholderAddress(it) } &&
            addresses.all { isFakeIpPlaceholderAddress(it) || isFakeIp6PlaceholderAddress(it) }
        ) {
            // H3（已转默认）：占位应答不走发夹。原生栈按系统解析原样拨号，
            // 占位地址由 TUN 的映射表在网络层还原——与竞品（Sollin）在同一
            // TUN 假 IP 环境下的现行做法一致。服务端中转已退役，系统栈直发
            // 是占位场景的唯一出口；直发失败即本次尝试失败，脚本链按既有
            // 语义继续（跨脚本/跨平台）。
            return ScriptUrlValidation(
                validatedTarget = null,
                systemStackDirect = true
            )
        }
        // Never route a hostname whose DNS answer includes even one local or
        // special-use address. Reuse this exact answer for direct OkHttp requests.
        if (addresses.any { !isPublicAddress(it) }) return rejected()
        return ScriptUrlValidation(
            validatedTarget = ValidatedScriptUrl(host, addresses.toList())
        )
    }

    internal fun validateScriptUrl(
        rawUrl: String,
        resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): ValidatedScriptUrl? = inspectScriptUrl(rawUrl, resolver).validatedTarget

    private fun isLocalHostName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")

    private fun looksLikeIpLiteral(host: String): Boolean {
        val unbracketed = host.removePrefix("[").removeSuffix("]")
        return unbracketed.contains(':') || Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(unbracketed)
    }

    private fun hasNonCanonicalIpv4Octets(host: String): Boolean =
        host.split('.').size == 4 && host.split('.').any { it.length > 1 && it.startsWith('0') }

    private fun looksLikeAmbiguousIpv4Literal(host: String): Boolean {
        if (':' in host) return false
        val labels = host.split('.')
        if (labels.none { it.isNotEmpty() }) return false
        fun numericOrHex(label: String): Boolean = label.isNotEmpty() &&
            (label.all { it in '0'..'9' } ||
                label.startsWith("0x", ignoreCase = true) && label.length > 2 &&
                label.substring(2).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        return labels.all { it.isEmpty() || numericOrHex(it) }
    }

    private fun parseIpLiteral(host: String): InetAddress? = try {
        InetAddress.getByName(host.removePrefix("[").removeSuffix("]").substringBefore('%'))
    } catch (_: Exception) {
        null
    }

    fun isAllowedScriptUrl(
        rawUrl: String,
        resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): Boolean = validateScriptUrl(rawUrl, resolver) != null

    /** Compatibility API for callers that explicitly require HTTPS. */
    fun isAllowedHttpsUrl(
        rawUrl: String,
        resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): Boolean = isAllowedScriptUrl(rawUrl, resolver) &&
        try { URI(rawUrl.trim()).scheme.equals("https", ignoreCase = true) } catch (_: Exception) { false }

    /**
     * 198.18.0.0/15 (RFC 2544 benchmarking) is never a routable service address on
     * the public Internet; as a DNS answer it is the signature of a local proxy's
     * fake-IP pool, not of a real or internal target.
     */
    private fun isFakeIpPlaceholderAddress(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address.map { it.toInt() and 0xff }
        return b[0] == 198 && b[1] in 18..19
    }

    /**
     * fc00::/18 is the default fake-IP IPv6 pool of sing-box-style TUN stacks.
     * It is only ever consulted next to a 198.18/15 IPv4 anchor answer (see
     * inspectScriptUrl); on its own it is indistinguishable from real ULA
     * addressing and never reclassifies an answer set.
     */
    private fun isFakeIp6PlaceholderAddress(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        val b = address.address
        return (b[0].toInt() and 0xff) == 0xfc && b[1].toInt() == 0 && (b[2].toInt() and 0xc0) == 0
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        return when (address) {
            is Inet4Address -> {
                val b = address.address.map { it.toInt() and 0xff }
                // Private, shared, documentation, benchmarking, metadata, and reserved ranges.
                b[0] != 0 &&
                    !(b[0] == 10) &&
                    !(b[0] == 100 && b[1] in 64..127) &&
                    !(b[0] == 127) &&
                    !(b[0] == 169 && b[1] == 254) &&
                    !(b[0] == 172 && b[1] in 16..31) &&
                    !(b[0] == 192 && b[1] == 168) &&
                    !(b[0] == 192 && b[1] == 0 && b[2] == 0) &&
                    !(b[0] == 192 && b[1] == 0 && b[2] == 2) &&
                    !(b[0] == 192 && b[1] == 88 && b[2] == 99) &&
                    !(b[0] == 198 && b[1] in 18..19) &&
                    !(b[0] == 198 && b[1] == 51 && b[2] == 100) &&
                    !(b[0] == 203 && b[1] == 0 && b[2] == 113) &&
                    !(b[0] >= 224)
            }
            is Inet6Address -> {
                val bytes = address.address
                val ipv4Mapped = bytes.take(10).all { it == 0.toByte() } &&
                    bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
                // Only global-unicast IPv6 is eligible. Exclude mapped, documentation,
                // deprecated 6to4, and IETF special-purpose allocations within 2000::/3.
                hasPrefix(bytes, byteArrayOf(0x20), 3) && !ipv4Mapped &&
                    !hasPrefix(bytes, byteArrayOf(0x20, 0x01, 0x00), 23) && // 2001::/23
                    !hasPrefix(bytes, byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()), 32) && // 2001:db8::/32
                    !hasPrefix(bytes, byteArrayOf(0x20, 0x02), 16) && // 2002::/16
                    !hasPrefix(bytes, byteArrayOf(0x3f, 0xff.toByte(), 0x00), 20) // 3fff::/20 docs
            }
            else -> false
        }
    }

    private fun hasPrefix(address: ByteArray, prefix: ByteArray, prefixBits: Int): Boolean {
        val fullBytes = prefixBits / 8
        for (index in 0 until fullBytes) {
            if (address[index] != prefix[index]) return false
        }
        val remainingBits = prefixBits % 8
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (address[fullBytes].toInt() and mask) == (prefix[fullBytes].toInt() and mask)
    }
}
