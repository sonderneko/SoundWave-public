package xyz.cncnlp.soundwave

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bounded image fetcher for cover cache and media notifications.
 *
 * Every hop is validated and DNS-pinned before connecting. Automatic redirects are disabled
 * so a public image URL cannot silently redirect the app into a private network or metadata
 * endpoint. This is intentionally separate from media playback networking.
 *
 * One narrow exception to pinning: when a hostname's DNS answer consists entirely of
 * 198.18.0.0/15 fake-IP placeholders (a local TUN proxy resolver answering every public
 * name from the benchmarking pool), there is no real address to pin. The hop is then
 * fetched by name through the system resolver — the same transport shape as the platform
 * direct clients — so the device's TUN layer can forward it. Any other non-public answer
 * (private, metadata, documentation, or any mix) stays a hard rejection on every hop.
 */
internal object CoverNetworkClient {
    private const val MAX_REDIRECTS = 4
    private const val USER_AGENT = "SoundWave/1.0"
    // J 波：许可等待上限。原 tryAcquire() 即弃使一屏并发请求里后到者直接判死（封面不加载
    // 主因之一）；改为有界等待——只是排队而非丢弃。
    // ACCEPT5-covergate：等待 4s→6s（见下方许可数说明），给拥塞时后排请求多一波周转机会。
    private const val PERMIT_WAIT_MS = 6000L

    // MERGE7 修复：J 波分段失败日志。设备上走 Log.w；JVM 单测环境里 android.util.Log 是
    // 桩实现、调用即抛 RuntimeException，会反噬下载判定本身（CoverNetworkClientTest /
    // Round2SafetyTest 实测），故日志统一经此吞异常入口，日志失败永不改变返回值。
    private fun logFailure(message: String) {
        try {
            android.util.Log.w("SWCover", message)
        } catch (_: Throwable) {
            // 日志不可用（单测桩）时静默跳过。
        }
    }

    /** Resolve one redirect while enforcing the shared hop, scheme, and credential policy. */
    internal fun redirectTarget(currentUrl: String, location: String?, hop: Int): String? {
        if (hop !in 0 until MAX_REDIRECTS || location.isNullOrBlank()) return null
        val current = try { URI(currentUrl) } catch (_: Exception) { return null }
        val currentScheme = current.scheme?.lowercase() ?: return null
        if (currentScheme !in setOf("http", "https")) return null
        val next = try { current.resolve(location) } catch (_: Exception) { return null }
        val nextScheme = next.scheme?.lowercase() ?: return null
        if (nextScheme !in setOf("http", "https") || next.rawUserInfo != null) return null
        // Never follow an HTTPS downgrade, even if the destination is otherwise public.
        if (currentScheme == "https" && nextScheme != "https") return null
        return next.toASCIIString()
    }

    // Each redirect hop needs its own DNS-pinned client, but shouldn't create a new
    // dispatcher/executor and connection-pool cleanup thread for every image request.
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 8
        maxRequestsPerHost = 8
    }
    private val sharedConnectionPool = ConnectionPool(8, 2, TimeUnit.MINUTES)
    // execute() is synchronous: OkHttp Dispatcher limits async enqueue() calls, not these.
    // Bound the whole request/redirect chain so Coil callers cannot create unbounded socket work.
    // ACCEPT5-covergate：许可 4→8。依据（台账 WAVE-ACCEPT5-COVERGATE）：封面审计实证
    // 4 许可 × 队列整表冷下载 = 闸口饥饿（§六(b)1）；队列抽屉一屏约 9 行（66dp 行高、
    // 76% 屏高），8 许可 ≈ 一屏行数同时在飞；行图已按 144px 小图下载（单张数十 KB），
    // 单许可周转快，8 并发 + 公平排队 + 6s 等待可在一轮预热内排空可见行与邻近行。
    // 仍远低于 OkHttp 默认（64），且通知封面/校验取图共用此闸、行为不变。
    private val requestPermits = Semaphore(8, true)

    internal fun defaultRefererForHost(host: String): String? {
        val normalizedHost = host.trim().lowercase().trimEnd('.')
        fun belongsTo(domain: String): Boolean =
            normalizedHost == domain || normalizedHost.endsWith(".$domain")
        return when {
            belongsTo("126.net") || belongsTo("163.com") -> "https://music.163.com/"
            belongsTo("gtimg.cn") || belongsTo("gtimg.com") || belongsTo("qq.com") -> "https://y.qq.com/"
            belongsTo("kugou.com") -> "https://m.kugou.com/"
            belongsTo("kuwo.cn") -> "https://www.kuwo.cn/"
            belongsTo("migu.cn") -> "https://music.migu.cn/"
            else -> "https://music.163.com/"
        }
    }

    internal fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        if (maxBytes <= 0) throw IllegalArgumentException("maxBytes must be positive")
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maxBytes - count) throw IOException("Cover response exceeds size limit")
            output.write(buffer, 0, count)
            total += count
        }
        return total
    }

    /**
     * Stream a validated cover response directly to a staging file to avoid full-image heap buffering.
     * The caller must validate the image and install the staging file only after this returns true.
     */
    fun downloadToFile(
        rawUrl: String,
        destination: java.io.File,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 12000,
        maxBytes: Long = 16L * 1024 * 1024,
        refererForHost: ((String) -> String?)? = ::defaultRefererForHost
    ): Boolean = downloadToFileInternal(
        rawUrl = rawUrl,
        destination = destination,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        maxBytes = maxBytes,
        refererForHost = refererForHost,
        targetResolver = { url -> ScriptNetworkPolicy.inspectScriptUrl(url).validatedTarget },
        systemDnsFallback = { url -> isFakeIpPlaceholderOnly(url) }
    )

    /** Test seam for exercising the actual OkHttp redirect/streaming path with loopback fixtures. */
    internal fun downloadToFileForTest(
        rawUrl: String,
        destination: java.io.File,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 12000,
        maxBytes: Long = 16L * 1024 * 1024,
        refererForHost: ((String) -> String?)? = ::defaultRefererForHost,
        targetResolver: (String) -> ValidatedScriptUrl?,
        systemDnsFallback: (String) -> Boolean = { false }
    ): Boolean = downloadToFileInternal(
        rawUrl = rawUrl,
        destination = destination,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        maxBytes = maxBytes,
        refererForHost = refererForHost,
        targetResolver = targetResolver,
        systemDnsFallback = systemDnsFallback
    )

    private fun downloadToFileInternal(
        rawUrl: String,
        destination: java.io.File,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBytes: Long,
        refererForHost: ((String) -> String?)?,
        targetResolver: (String) -> ValidatedScriptUrl?,
        systemDnsFallback: (String) -> Boolean
    ): Boolean {
        if (maxBytes <= 0 || connectTimeoutMs <= 0 || readTimeoutMs <= 0) return false
        val acquired = try {
            requestPermits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            logFailure("cover fetch failed: permit wait timed out")
            return false
        }
        try {
            var currentUrl = rawUrl.trim()
            var previousUrl: String? = null
            repeat(MAX_REDIRECTS + 1) { hop ->
                val uri = try { URI(currentUrl) } catch (_: Exception) { return false }
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme !in setOf("http", "https") || uri.rawUserInfo != null) return false
                val target = try { targetResolver(currentUrl) } catch (_: Exception) { null }
                // No pinnable target: the only remaining route is the fake-IP system-DNS
                // fallback, and only when this hop's answer is exclusively placeholders.
                val useSystemDns = target == null &&
                    try { systemDnsFallback(currentUrl) } catch (_: Exception) { false }
                if (target == null && !useSystemDns) {
                    logFailure("cover fetch failed: no validated target host=${uri.host}")
                    return false
                }
                val client = try {
                    OkHttpClient.Builder()
                        .dispatcher(sharedDispatcher)
                        .connectionPool(sharedConnectionPool)
                        .apply { if (target != null) dns(target.pinnedDns()) }
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                        .build()
                } catch (_: Exception) { return false }
                val host = uri.host ?: return false
                val referer = if (previousUrl == null ||
                    ScriptNetworkPolicy.isSameOrigin(previousUrl, currentUrl)
                ) refererForHost?.invoke(host) else null
                val request = try {
                    Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", USER_AGENT)
                        .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                        .get()
                        .build()
                } catch (_: Exception) { return false }
                val response = try { client.newCall(request).execute() } catch (e: Exception) {
                    logFailure("cover fetch failed: ${e.javaClass.simpleName} host=${uri.host}")
                    return false
                }
                response.use { res ->
                    if (res.code in 200..299) {
                        val body = res.body ?: return false
                        if (body.contentLength() > maxBytes) return false
                        return try {
                            body.byteStream().use { input ->
                                destination.outputStream().buffered().use { output ->
                                    copyLimited(input, output, maxBytes)
                                }
                            }
                            true
                        } catch (_: Exception) {
                            try { destination.delete() } catch (_: Exception) {}
                            false
                        }
                    }
                    if (res.code !in setOf(301, 302, 303, 307, 308)) {
                        logFailure("cover fetch failed: http ${res.code} host=${uri.host}")
                        return false
                    }
                    val next = redirectTarget(currentUrl, res.header("Location"), hop) ?: return false
                    // Validate before following; the next loop re-evaluates the destination
                    // (pinned target, or fake-IP system-DNS fallback) before connecting.
                    val nextPinned = try { targetResolver(next) } catch (_: Exception) { null }
                    val nextSystemDns = nextPinned == null &&
                        try { systemDnsFallback(next) } catch (_: Exception) { false }
                    if (nextPinned == null && !nextSystemDns) return false
                    previousUrl = currentUrl
                    currentUrl = next
                }
            }
            return false
        } finally {
            requestPermits.release()
        }
    }

    /**
     * True only when [rawUrl] is a syntactically public hostname URL (the same gates
     * [ScriptNetworkPolicy.inspectScriptUrl] applies: HTTP(S), no embedded credentials,
     * usable port, not a local name, not an IP literal) whose DNS answer is non-empty and
     * consists exclusively of 198.18.0.0/15 fake-IP placeholders.
     *
     * Such an answer is the signature of a local TUN proxy resolver (Clash/Surge style),
     * not of a real or internal target: the range is unroutable on the public Internet
     * and carries no service, so connecting by name through the system resolver can only
     * reach the device's own TUN layer, which forwards by hostname. Any answer outside
     * the placeholder range — public, private, metadata, documentation, or any mix —
     * returns false, as do literal 198.18 hosts (literals never take this fallback).
     */
    internal fun isFakeIpPlaceholderOnly(
        rawUrl: String,
        resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): Boolean {
        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("http", "https")) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port == 0 || uri.port > 65535) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (isLocalHostName(host)) return false
        if (looksLikeIpLiteral(host)) return false
        val addresses = try { resolver(host) } catch (_: Exception) { return false }
        if (addresses.isEmpty()) return false
        // Same anchor rule as the script policy: v6 fake-IP answers count only
        // alongside a 198.18/15 v4 placeholder answer.
        return addresses.any { isFakeIpPlaceholderAddress(it) } &&
            addresses.all { isFakeIpPlaceholderAddress(it) || isFakeIp6PlaceholderAddress(it) }
    }

    /** Same definition as the script policy's fake-IP classification (RFC 2544 pool). */
    private fun isFakeIpPlaceholderAddress(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address
        return (b[0].toInt() and 0xff) == 198 && (b[1].toInt() and 0xff) in 18..19
    }

    /** Same definition as the script policy: fc00::/18 sing-box fake-IP pool (anchor-gated). */
    private fun isFakeIp6PlaceholderAddress(address: InetAddress): Boolean {
        if (address !is java.net.Inet6Address) return false
        val b = address.address
        return (b[0].toInt() and 0xff) == 0xfc && b[1].toInt() == 0 && (b[2].toInt() and 0xc0) == 0
    }

    private fun isLocalHostName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")

    private fun looksLikeIpLiteral(host: String): Boolean {
        val unbracketed = host.removePrefix("[").removeSuffix("]")
        return unbracketed.contains(':') || Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(unbracketed)
    }

}
