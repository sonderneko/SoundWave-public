package xyz.cncnlp.soundwave

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import xyz.cncnlp.soundwave.script.BoundedScriptDnsResolver
import xyz.cncnlp.soundwave.script.ScriptExecutionLimits

internal data class ScriptFetchResult(val code: Int, val body: String)

/** Effects are recorded by the runtime owning this capability, never by a global transport map. */
internal interface ScriptFetchObserver {
    fun failure(capability: String, category: String)
    fun responseHeaders(capability: String, headersJson: String)
    fun callStarted(capability: String, call: okhttp3.Call)
    fun callFinished(capability: String, call: okhttp3.Call?)
    /** H3 诊断：本次交换实际使用的通道（direct/system）；后写覆盖先写，最终值即产出结果的通道。 */
    fun route(capability: String, route: String) {}
}

/**
 * ACCEPT6-FREEZE（崩溃取证 2026-11-04）：装进 RequestControl 的取消动作由调度器在
 * 「取消发起方」线程上执行，发起方可以是主线程（点播顶替）；Call.cancel() 会同步关闭
 * SSLSocket 并 flush TLS 收尾写，在主线程执行即 NetworkOnMainThreadException。
 * 关连接一律投递到这条专属守护线程，绝不在发起方线程内联执行。
 */
internal fun cancelFetchCallAsync(call: okhttp3.Call) {
    FetchCallCancelExecutor.executor.execute { runCatching { call.cancel() } }
}

private object FetchCallCancelExecutor {
    val executor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "soundwave-fetch-cancel").apply { isDaemon = true }
        }
}

/**
 * One synchronous native HTTP exchange, including validated redirects.
 * Egress is direct-only: validated public targets are pinned to their checked DNS
 * answer, fake-IP placeholder answers go through the system stack (H3, graduated),
 * and everything else is refused by policy. The server relay is retired.
 * No Context, WebView, scheduler admission, callback dispatch, profile or lifecycle authority.
 * Suppliers intentionally read owner configuration at the same points as the former Bridge code.
 * The caller retains RequestControl and all capability bookkeeping/cancellation ownership.
 */
internal class ScriptFetchTransport(
    private val http: () -> OkHttpClient,
    private val credentialGuardEnabled: () -> Boolean,
    private val observer: ScriptFetchObserver,
    private val requestHeaderFilter: (Map<String, String>) -> Map<String, String> =
        { raw -> ScriptNetworkPolicy.filterHeaders(raw) },
) {
    private fun classifyFetchError(msg: String?): String {
        val m = msg.orEmpty().lowercase()
        return when {
            m.contains("timeout") || m.contains("timed out") -> "timeout"
            m.contains("2mb") || m.contains("超过") -> "too_large"
            else -> "network"
        }
    }

    private fun classifyHttpCode(code: Int): String = when {
        code == 403 -> "http_403"
        code == 404 || code == 410 -> "http_404"
        code == 429 -> "http_429"
        code >= 500 -> "http_5xx"
        code in 400..499 -> "http_4xx"
        else -> "http_$code"
    }

    private fun sanitizedScriptRequestHeaders(headersJson: String?): Map<String, String> =
        ScriptFetchCodec.requestHeaders(headersJson)

    fun fetch(
        capability: String,
        url: String,
        method: String,
        headersJson: String?,
        body: String?,
        control: ScriptRequestScheduler.RequestControl
    ): ScriptFetchResult {
        if (control.isCancelled()) return ScriptFetchResult(0, "aborted")
        // SW-02-13：请求体量前置门禁——超限请求不进入任何网络通道，按 too_large 归因。
        if (!ScriptExecutionLimits.requestAllowed(url, headersJson, body)) {
            observer.failure(capability, "too_large")
            return ScriptFetchResult(0, "request_too_large")
        }
        val cleartextCredentialGuardEnabled = credentialGuardEnabled()
        val outgoingHeaders = if (cleartextCredentialGuardEnabled) {
            sanitizedScriptRequestHeaders(headersJson).keys
        } else emptySet()
        val cleartextDecision = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            url, outgoingHeaders, cleartextCredentialGuardEnabled
        )
        if (cleartextDecision.blocked) {
            observer.failure(capability, "policy")
            return ScriptFetchResult(
                403,
                "HTTP request with token/key headers is blocked"
            )
        }
        // 直连是唯一出口：只允许客户端直接访问经过 SSRF 校验的公网域名。
        // 无效目标、内嵌凭据及本地/特殊地址一律拒绝，没有服务端中转兜底。
        // 应答为 fake-IP 占位集时（H3 已转默认），策略给出系统栈原样直发
        // （不分类、不 pinning），占位地址由 TUN 网络层承接。
        val urlValidation = ScriptNetworkPolicy.inspectScriptUrl(url) { host ->
            BoundedScriptDnsResolver.processWide.resolve(host) { control.isCancelled() }
        }
        if (control.isCancelled()) return ScriptFetchResult(0, "aborted")
        val directTarget = urlValidation.validatedTarget
        val direct = if (directTarget != null) {
            fetchOnce(
                capability, url, method, headersJson, body,
                validatedTarget = directTarget,
                cleartextCredentialGuardEnabled = cleartextCredentialGuardEnabled,
                control = control
            )
        } else if (urlValidation.systemStackDirect) {
            fetchOnce(
                capability, url, method, headersJson, body,
                systemStackDirect = true,
                cleartextCredentialGuardEnabled = cleartextCredentialGuardEnabled,
                control = control
            )
        } else {
            observer.failure(capability, "policy")
            ScriptFetchResult(0, "script request blocked by network policy")
        }
        return direct
    }

    private fun safeResponseHeadersJson(headers: okhttp3.Headers): String = ScriptFetchCodec.responseHeaders(headers)

    private fun readRedirectResponse(capability: String, response: okhttp3.Response): ScriptFetchResult {
        val text = response.body?.byteStream()?.let { ScriptFetchCodec.readLimited(it) }.orEmpty()
        observer.responseHeaders(capability, safeResponseHeadersJson(response.headers))
        return ScriptFetchResult(response.code, text)
    }

    private fun fetchOnce(
        capability: String,
        url: String,
        method: String,
        headersJson: String?,
        body: String?,
        validatedTarget: ValidatedScriptUrl? = null,
        systemStackDirect: Boolean = false,
        cleartextCredentialGuardEnabled: Boolean = false,
        control: ScriptRequestScheduler.RequestControl
    ): ScriptFetchResult {
        try {
            if (control.isCancelled()) return ScriptFetchResult(0, "aborted")
            // H3 诊断标注：记录本跳实际通道（重定向跳数内以最后一跳为准）。
            observer.route(capability, if (systemStackDirect) "system" else "direct")
            val m = method.uppercase().ifEmpty { "GET" }
            val target = url

            val bld = Request.Builder().url(target)
            val sanitizedHeaders = try {
                val hs = JSONObject(headersJson ?: "{}")
                val rawHeaders = linkedMapOf<String, String>()
                val it = hs.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    rawHeaders[key] = hs.optString(key)
                }
                requestHeaderFilter(rawHeaders)
            } catch (_: Exception) { emptyMap() }
            run {
                var hasUa = false
                sanitizedHeaders.forEach { (key, value) ->
                    if (key.equals("User-Agent", true)) hasUa = true
                    bld.header(key, value)
                }
                if (!hasUa) {
                    bld.header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                }
            }
            fun contentTypeOf(): String {
                try {
                    val hs = JSONObject(headersJson ?: "{}")
                    val it = hs.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        if (k.equals("Content-Type", true) || k.equals("content-type", true)) {
                            val v = hs.optString(k).trim()
                            if (v.isNotEmpty()) return v
                        }
                    }
                } catch (_: Exception) {
                }
                // 默认：像 form 的 body 用 urlencoded，其余 json
                val raw = body.orEmpty().trim()
                return if (raw.contains('=') && !raw.startsWith("{") && !raw.startsWith("[")) {
                    "application/x-www-form-urlencoded; charset=utf-8"
                } else {
                    "application/json; charset=utf-8"
                }
            }
            val req = when {
                m == "POST" || m == "PUT" || m == "PATCH" -> {
                    val mt = contentTypeOf().toMediaType()
                    bld.method(m, (body ?: "").toRequestBody(mt)).build()
                }
                m != "GET" && m != "HEAD" -> {
                    val mt = contentTypeOf().toMediaType()
                    bld.method(m, (body ?: "").toRequestBody(mt)).build()
                }
                m == "HEAD" -> bld.head().build()
                else -> bld.get().build()
            }
            // 手动跟随有限次 HTTPS 跳转（脚本 API 常见 302），每次重验公开域名策略。
            var current = req
            var currentValidatedTarget = validatedTarget
            var currentSystemStack = systemStackDirect
            var hop = 0
            while (true) {
                if (control.isCancelled()) return ScriptFetchResult(0, "aborted")
                val callClient = if (currentSystemStack) {
                    // H3：系统栈原样直发——不覆写 Dns，系统解析结果（占位地址）直拨，TUN 网络层承接。
                    http()
                } else {
                    val pinnedTarget = currentValidatedTarget ?: return ScriptFetchResult(403, "request blocked")
                    http().newBuilder().dns(pinnedTarget.pinnedDns()).build()
                }
                val call = callClient.newCall(current)
                if (!control.installCancellation(call) { cancelFetchCallAsync(call) }) return ScriptFetchResult(0, "aborted")
                observer.callStarted(capability, call)
                try {
                    val resp = call.execute()
                    val code = resp.code
                    if (code in 300..399 && hop < 4) {
                        val loc = resp.header("Location").orEmpty()
                        if (loc.isBlank()) return resp.use { readRedirectResponse(capability, it) }
                        val redirectUrl = try {
                            current.url.resolve(loc)?.toString() ?: loc
                        } catch (_: Exception) {
                            return resp.use { readRedirectResponse(capability, it) }
                        }
                        if (ScriptNetworkPolicy.isHttpsDowngrade(current.url.toString(), redirectUrl)) {
                            observer.responseHeaders(capability, safeResponseHeadersJson(resp.headers))
                            resp.close()
                            observer.failure(capability, "policy")
                            return ScriptFetchResult(
                                403,
                                "HTTPS redirect downgrade is blocked"
                            )
                        }
                        val sameOrigin = ScriptNetworkPolicy.isSameOrigin(current.url.toString(), redirectUrl)
                        val hasNonEmptyBody = (current.body?.contentLength() ?: 0L) != 0L
                        if (!ScriptNetworkPolicy.shouldFollowRedirect(
                                statusCode = code,
                                sameOrigin = sameOrigin,
                                hasNonEmptyBody = hasNonEmptyBody,
                                hop = hop
                            )
                        ) {
                            return resp.use { readRedirectResponse(capability, it) }
                        }
                        val redirectBuilder = current.newBuilder().url(redirectUrl)
                        ScriptNetworkPolicy.redirectCredentialHeaders(current.headers.names(), sameOrigin)
                            .forEach { redirectBuilder.removeHeader(it) }
                        when (code) {
                            301, 302, 303 -> if (m == "HEAD") redirectBuilder.head() else redirectBuilder.get()
                            // LX/HTTP semantics: 307/308 preserve method/body after same-origin or empty-body checks.
                            307, 308 -> { /* preserve current method/body */ }
                        }
                        val next = redirectBuilder.build()
                        val redirectedDecision = ScriptNetworkPolicy.assessCleartextCredentialRequest(
                            redirectUrl, next.headers.names(), cleartextCredentialGuardEnabled
                        )
                        if (redirectedDecision.blocked) {
                            observer.responseHeaders(capability, safeResponseHeadersJson(resp.headers))
                            resp.close()
                            observer.failure(capability, "policy")
                            return ScriptFetchResult(
                                403,
                                "HTTP redirect with token/key headers is blocked"
                            )
                        }
                        // 重定向目标与首跳同一套策略：真实公网→pinned；占位→系统栈；
                        // 其余（私网/混合/凭据）维持拦截，H3 转默认不放宽重定向边界。
                        val redirectValidation = ScriptNetworkPolicy.inspectScriptUrl(redirectUrl) { host ->
                            BoundedScriptDnsResolver.processWide.resolve(host) { control.isCancelled() }
                        }
                        if (redirectValidation.validatedTarget == null && !redirectValidation.systemStackDirect) {
                            observer.responseHeaders(capability, safeResponseHeadersJson(resp.headers))
                            resp.close()
                            return ScriptFetchResult(403, "redirect blocked")
                        }
                        observer.responseHeaders(capability, safeResponseHeadersJson(resp.headers))
                        resp.close()
                        currentValidatedTarget = redirectValidation.validatedTarget
                        currentSystemStack = redirectValidation.systemStackDirect
                        observer.route(capability, if (currentSystemStack) "system" else "direct")
                        current = next
                        hop++
                        continue
                    }
                    return resp.use { r ->
                        val text: String = r.body?.byteStream()?.let { ScriptFetchCodec.readLimited(it) }.orEmpty()
                        // response headers are part of LX request contract; expose only safe metadata.
                        observer.responseHeaders(capability, safeResponseHeadersJson(r.headers))
                        // HTTP 错误码透传诊断（仅脱敏分类，不存响应正文）；
                        // 本地 policy 拦截在各拦截点已按 policy 归因，不经此处。
                        if (r.code in 400..599) observer.failure(capability, classifyHttpCode(r.code))
                        ScriptFetchResult(r.code, text)
                    }
                } finally {
                    observer.callFinished(capability, call)
                    control.clearCancellation(call)
                }
            }
        } catch (e: Exception) {
            if (!control.isCancelled()) observer.failure(capability, classifyFetchError(e.message))
            ScriptFetchResult(0, e.message ?: "error")
        } finally {
            observer.callFinished(capability, null)
        }
        return ScriptFetchResult(0, "error")
    }
}
