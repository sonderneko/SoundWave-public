package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.source.ScriptHttpCredentialGuardSetting
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import xyz.cncnlp.soundwave.script.ScriptEvaluationLanes
import xyz.cncnlp.soundwave.script.ScriptExecutionLimits
import xyz.cncnlp.soundwave.script.SingleFlightNegativeCache

/**
 * 洛雪 / Sollin 风格脚本运行时：
 * - 隐藏 WebView 只跑 LX JS（音频不在 WebView）
 * - 脚本只 init 一次（__swScripts）
 * - HTTP：原生 OkHttp 直连（公网 pinned；fake-IP 占位应答走系统栈直发，无服务端中转）
 * - 同脚本 LX 请求 single-flight；不同脚本只串行提交 WebView 调用
 * - 脚本 base64 注入，避免 quote 破坏大脚本
 */
internal const val SCRIPT_RUNTIME_CSP =
    "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"

internal fun scriptRuntimeBootstrapHtml(): String = """
    <!doctype html><html><head><meta charset=utf-8>
    <meta http-equiv="Content-Security-Policy" content="$SCRIPT_RUNTIME_CSP">
    <script>
    window.__swScripts={};
    window.__swFetchCbs={};
    window.__swBoot=true;
    </script></head><body></body></html>
""".trimIndent()

internal class ScriptRuntimeWebViewClient(
    private val onPageFinishedCallback: () -> Unit = {}
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        onPageFinishedCallback()
    }

    // ScriptRuntime is a JS host, not a browser. Deny all top-level and frame navigations.
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = true

    @Deprecated("Use the WebResourceRequest overload")
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
        blockNetworkResource(request.url.toString()) ?: super.shouldInterceptRequest(view, request)

    @Deprecated("Use the WebResourceRequest overload")
    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? =
        blockNetworkResource(url) ?: super.shouldInterceptRequest(view, url)

    private fun blockNetworkResource(url: String?): WebResourceResponse? {
        val scheme = Uri.parse(url.orEmpty()).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return WebResourceResponse(
            "text/plain", "UTF-8", 403, "Blocked by ScriptRuntime",
            mapOf("Cache-Control" to "no-store"),
            java.io.ByteArrayInputStream(ByteArray(0))
        )
    }
}

class ScriptRuntime(private val context: Context,
    val ownerContext: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime(),
    private val probeOnly: Boolean = false
) {
    private val fetchScheduler = if (probeOnly) ScriptRequestScheduler.probes else ScriptRequestScheduler.processWide
    private fun ownerCurrent() = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(ownerContext)

    private val main = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private var webView: WebView? = null
    @Volatile private var initRequested = false
    @Volatile private var destroyed = false
    @Volatile private var healthUnhealthy = false
    private var initAttempts = 0
    private var rebuildRequested = false
    /** CAPs whose handler call has started but has not returned synchronously. */
    private val synchronousHandlers = ConcurrentHashMap.newKeySet<String>()
    /** 可重建：死循环脚本卡死 WebView 后销毁重建，ready 必须随新实例重新创建。 */
    @Volatile private var ready = CompletableDeferred<Boolean>()
    private data class PendingUrlRequest(
        val capability: String,
        val deferred: CompletableDeferred<String>,
        /** A PLAY request may preempt speculative work, never an explicit download. */
        val purpose: ResolveTicket.Purpose,
        /** Also cancel a coroutine waiting for its script-slot lane. */
        val ownerJob: Job?
    )
    /** Request completion is capability-bound, not an incrementing public integer. */
    private val pendingUrl = ConcurrentHashMap<String, PendingUrlRequest>()
    /** nativeFetch is also capability-bound so one script cannot guess another callback id. */
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()

    /** ACCEPT6-FREEZE（崩溃取证 2026-11-04）：Call.cancel() 在调用方线程同步关
     *  SSLSocket（TLS 收尾写）；releaseRequest 的调用方常在主线程（点播顶替序幕），
     *  内联关连接即 NetworkOnMainThreadException——一律投递到这条专属守护线程。 */
    private val callCancelExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "soundwave-call-cancel").apply { isDaemon = true }
    }

    internal fun cancelCallAsync(call: okhttp3.Call) {
        callCancelExecutor.execute { runCatching { call.cancel() } }
    }
    /** One process-wide queue is shared by all ScriptRuntime/WebView instances. */
    private val fetchOwner = Any()
    private val requestLock = Any()
    /** Network callback id → URL capability; never confuse these two namespaces. */
    private val fetchParents = ConcurrentHashMap<String, String>()

    private fun forgetFetch(capability: String) {
        fetchParents.remove(capability)
        responseHeadersByCapability.remove(capability)
        lastFetchFailure.remove(capability)
    }

    private fun releaseRequest(capability: String, invalidate: Boolean = true) {
        synchronized(requestLock) {
            fetchParents.entries.filter { it.value == capability }.map { it.key }.forEach { cid ->
                fetchScheduler.cancel(fetchOwner, cid)
                activeCalls.remove(cid)?.let { cancelCallAsync(it) }
                forgetFetch(cid)
            }
        }
        evalJs("try{window.__swCancelRequest(${JSONObject.quote(capability)},$invalidate);}catch(e){}")
    }
    /** capability → scriptId：把网络层失败原因回填到具体脚本的诊断。 */
    private val capToScript = ConcurrentHashMap<String, String>()
    /** capability → 脱敏失败分类（timeout/policy/too_large/network），不存 URL/正文/Cookie。 */
    private val lastFetchFailure = ConcurrentHashMap<String, String>()
    /** capability → safe response headers, serialized only for the matching JS callback. */
    private val responseHeadersByCapability = ConcurrentHashMap<String, String>()

    /** scriptId → 最近一次脱敏失败分类，供 Resolver 解释 EMPTY（区分网络失败与脚本真空链）。 */
    private val lastFailureByScript = ConcurrentHashMap<String, String>()
    /** capability → 脚本自报错误文本（已清理/截断），供 H1-a 终局分类；原始文本不落盘。 */
    private val explicitErrorByCapability = ConcurrentHashMap<String, String>()
    /** scriptId → 最近一次脚本自报错误（清理后），由 Resolver 在尝试结束后消费。 */
    private val lastExplicitErrorByScript = ConcurrentHashMap<String, String>()
    /** capability → 最近一次脚本 fetch 的实际通道（direct/system/proxy），供 H3 诊断标注。 */
    private val routeByCapability = ConcurrentHashMap<String, String>()
    /** scriptId → 最近一次取链用过的通道（direct/system/proxy）。 */
    private val lastRouteByScript = ConcurrentHashMap<String, String>()
    private val evaluationLanes = ScriptEvaluationLanes()
    // P2-1：共享连接池，仅覆写超时与禁重定向（脚本请求不得静默跨协议/跨域跳转，语义不变）。
    // SW-02-12：脚本 HTTP 走独立 scriptDispatcher（8/4），不再与普通 API 抢同一调度器；
    // probeOnly 运行时仍是 1/1 强隔离，关闭时经 closeProbeHttpResources 释放。
    private val http = AppHttp.shared.newBuilder()
        .dispatcher(if (probeOnly) okhttp3.Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 } else AppHttp.scriptDispatcher)
        .apply { if (probeOnly) {
            connectionPool(okhttp3.ConnectionPool(1, 30, TimeUnit.SECONDS))
        } }
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        // 脚本请求不得静默跨协议/跨域跳转；重定向目标必须由脚本显式、重新通过策略检查。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val fetchTransport = ScriptFetchTransport(
        http = { http },
        credentialGuardEnabled = {
            ScriptHttpCredentialGuardSetting.isEnabled(
                ContextPreferences.open(context, "soundwave", 0, ownerContext)
            )
        },
        observer = object : ScriptFetchObserver {
            override fun failure(capability: String, category: String) {
                lastFetchFailure[capability] = category
            }
            override fun route(capability: String, route: String) {
                routeByCapability[capability] = route
            }
            override fun responseHeaders(capability: String, headersJson: String) {
                responseHeadersByCapability[capability] = headersJson
            }
            override fun callStarted(capability: String, call: okhttp3.Call) {
                activeCalls[capability] = call
            }
            override fun callFinished(capability: String, call: okhttp3.Call?) {
                if (call == null) activeCalls.remove(capability) else activeCalls.remove(capability, call)
            }
        },
        requestHeaderFilter = { raw -> ScriptNetworkPolicy.filterHeaders(raw) },
    )

    private fun newCapability(): String = UUID.randomUUID().toString().replace("-", "")
    private fun slotId(id: String, code: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "${xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(ownerContext)}:${ownerContext.generation}:$id:$hash"
    }

    /** 最近一次该脚本取链失败的脱敏分类（timeout/policy/too_large/network；空串=无失败记录）。 */
    fun lastFailureReason(scriptId: String): String = lastFailureByScript[scriptId].orEmpty()

    /** 读取并清除最近一次脱敏失败分类，避免探测把旧失败误记到后续空链上。 */
    fun consumeLastFailureReason(scriptId: String): String = lastFailureByScript.remove(scriptId).orEmpty()

    /** 读取并清除最近一次脚本自报错误（H1-a 分类输入）；每次 getMusicUrl 入口都会先清旧值。 */
    fun consumeLastExplicitError(scriptId: String): String =
        lastExplicitErrorByScript.remove(scriptId).orEmpty()

    /** 最近一次该脚本取链实际走的通道（direct/system/proxy；空=尚无记录），供诊断标注。 */
    fun lastRoute(scriptId: String): String = lastRouteByScript[scriptId].orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        val attempt = synchronized(lifecycleLock) {
            if (destroyed) return@synchronized null
            if (healthUnhealthy && rebuildRequested) return@synchronized null
            if (initRequested) return@synchronized null
            val next = ScriptRuntimeHealthPolicy.nextInitAttempt(initAttempts) ?: return@synchronized null
            initAttempts = next
            initRequested = true
            ready = CompletableDeferred()
            next
        } ?: return
        main.post {
            try {
                val wv = WebView(context.applicationContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.databaseEnabled = true
                wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
                wv.settings.allowFileAccess = false
                wv.settings.blockNetworkLoads = true
                wv.settings.mediaPlaybackRequiresUserGesture = false
                wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                wv.setLayerType(WebView.LAYER_TYPE_NONE, null)
                wv.webViewClient = ScriptRuntimeWebViewClient {
                    if (webView !== wv) return@ScriptRuntimeWebViewClient
                    synchronized(lifecycleLock) { healthUnhealthy = false }
                    if (!ready.isCompleted) ready.complete(true)
                }
                wv.addJavascriptInterface(Bridge(), "NativeBridge")
                webView = wv
                wv.loadDataWithBaseURL("https://localhost/", scriptRuntimeBootstrapHtml(), "text/html", "UTF-8", null)
                main.postDelayed({
                    if (webView !== wv) return@postDelayed
                    if (!ready.isCompleted) {
                        synchronized(lifecycleLock) { healthUnhealthy = false }
                        ready.complete(true)
                    }
                }, 800)
            } catch (_: Exception) {
                synchronized(lifecycleLock) {
                    initRequested = false
                    healthUnhealthy = true
                }
                if (!ready.isCompleted) ready.complete(false)
                if (attempt < ScriptRuntimeHealthPolicy.MAX_INIT_ATTEMPTS) {
                    main.postDelayed({ init() }, 50L * attempt)
                }
            }
        }
    }

    /**
     * r658c 冷启动：首帧之后主线程空闲时预建脚本 WebView，冷启动后第一次播放不再现场初始化 WebView（约数百毫秒）。
     * 幂等：已启动 / 不健康重建中时什么都不做。
     */
    fun prewarmWhenIdle() {
        main.postDelayed({
            android.os.Looper.myQueue().addIdleHandler {
                try { ensureStarted() } catch (_: Throwable) {}
                false
            }
        }, 1500L)
    }

    private fun ensureStarted() {
        if (!healthUnhealthy && initRequested) return
        synchronized(lifecycleLock) {
            if (healthUnhealthy && rebuildRequested) return
        }
        init()
    }

    private fun requestHealthRecovery(purpose: ResolveTicket.Purpose) {
        if (!ScriptRuntimeHealthPolicy.shouldRebuildAfterTimeout(
                handlerReturned = false,
                isDownload = purpose == ResolveTicket.Purpose.DOWNLOAD
            )) return
        synchronized(lifecycleLock) {
            healthUnhealthy = true
            rebuildRequested = true
        }
        maybeRebuildWhenIdle()
    }

    private fun maybeRebuildWhenIdle() {
        synchronized(lifecycleLock) {
            if (!rebuildRequested || pendingUrl.isNotEmpty()) return
            rebuildRequested = false
            initRequested = false
            initAttempts = 0
        }
        main.post {
            try {
                webView?.removeJavascriptInterface("NativeBridge")
                webView?.destroy()
            } catch (_: Exception) { }
            webView = null
            init()
        }
    }

    internal fun forkForProbe(): ScriptRuntime = ScriptRuntime(context.applicationContext, ownerContext, probeOnly = true).also {
        it.init()
    }

    fun invalidate(scriptId: String? = null) {
        if (scriptId == null) {
            // 槽位与 __swScripts 均已钉死，不能整体替换；逐脚本翻转 invalidated 状态触发重建。
            evalJs("try{var s=window.__swScripts;Object.keys(s).forEach(function(k){try{window.__swInvalidate(k);}catch(e){}});}catch(e){}")
        } else {
            evalJs("try{window.__swInvalidate(${JSONObject.quote(scriptId)});}catch(e){}")
        }
    }

    private fun cancelActiveCalls() {
        pendingUrl.values.forEach { releaseRequest(it.capability) }
        // Only teardown of the whole runtime may cancel unbound/orphan transports.
        fetchScheduler.cancelOwner(fetchOwner)
    }

    private fun completeAndRemove(capability: String, url: String?) {
        val pending = pendingUrl[capability] ?: return
        pending.deferred.complete(if (ownerCurrent()) url?.trim().orEmpty() else "")
    }

    /**
     * A user tap may preempt speculative prefetch/probe work, but must never cancel
     * an explicit download task sharing the resident WebView runtime.
     */
    fun cancelSpeculativeUrlRequests() {
        cancelUrlRequests { it.purpose == ResolveTicket.Purpose.PREFETCH || it.purpose == ResolveTicket.Purpose.PROBE }
    }

    /** A new explicit PLAY supersedes an older PLAY, but never a DOWNLOAD. */
    fun cancelPlayUrlRequests() {
        cancelUrlRequests { it.purpose == ResolveTicket.Purpose.PLAY }
    }

    private fun cancelUrlRequests(predicate: (PendingUrlRequest) -> Boolean) {
        val selected = pendingUrl.entries.toList().filter { predicate(it.value) }
        selected.forEach { (capability, pending) ->
            synchronized(requestLock) {
                if (pendingUrl.remove(capability, pending)) {
                    releaseRequest(capability)
                    pending.deferred.cancel(kotlinx.coroutines.CancellationException("request superseded"))
                    pending.ownerJob?.cancel(kotlinx.coroutines.CancellationException("request superseded"))
                }
            }
        }
    }

    fun destroy() {
        synchronized(lifecycleLock) {
            destroyed = true
            healthUnhealthy = true
            rebuildRequested = false
            initRequested = false
        }
        synchronousHandlers.clear()
        urlCallFlights.clearFailures()
        cancelActiveCalls()
        closeProbeHttpResources()
        main.post {
            pendingUrl.values.forEach { it.deferred.complete("") }
            pendingUrl.clear()
            try {
                webView?.removeJavascriptInterface("NativeBridge")
                webView?.destroy()
            } catch (_: Exception) {
            }
            webView = null
        }
    }

    /** Worker 专用：等主线程真正释放 WebView 后再结束任务，避免后台实例泄漏。 */
    suspend fun closeAndWait() {
        synchronized(lifecycleLock) {
            destroyed = true
            healthUnhealthy = true
            rebuildRequested = false
            initRequested = false
        }
        synchronousHandlers.clear()
        urlCallFlights.clearFailures()
        cancelActiveCalls()
        closeProbeHttpResources()
        val closed = CompletableDeferred<Unit>()
        main.post {
            pendingUrl.values.forEach { it.deferred.complete("") }
            pendingUrl.clear()
            try {
                webView?.removeJavascriptInterface("NativeBridge")
                webView?.destroy()
            } catch (_: Exception) {
            }
            webView = null
            closed.complete(Unit)
        }
        withTimeoutOrNull(2000L) { closed.await() }
    }

    private fun closeProbeHttpResources() {
        if (!probeOnly) return
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private data class ScriptUrlCallResult(val url: String, val failureCategory: String, val cacheableFailure: Boolean = true)

    /**
     * ACCEPT4：本次调用是否由脚本 handler 真正 resolve 回传（经 Bridge.onUrlResult）。
     * 求值提交失败、WebView 未就绪、销毁兜底等「从未真正执行」的空返回不带此标记，
     * 不得进负缓存——否则一次运行时抖动会被 60s 负缓存固化成「1ms 空链」快死形态。
     */
    private val handlerResolvedCaps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * getMusicUrlUncoalesced 的旁路事实（返回值保持 String 的结构契约不变）：
     * 本次调用是否由 handler 真正 resolve 回传，由 producer 写入、getMusicUrl 读取。
     */
    private class HandlerOutcome {
        @Volatile var resolved: Boolean = false
    }

    /**
     * SW-02-13：完全相同的脚本/歌曲/来源/音质请求共享一次在飞执行；只有「语义空链」
     * 结果进 60s 负缓存（瞬时失败分类不缓存、保持可重试）——与 H 波 ScriptVerdictStore
     * 分工：VerdictStore 是唯一的持久裁决记忆，本层只是进程内合并/备忘。
     * ACCEPT4 收紧 cacheableFailure 的构成：必须是 handler 真正 resolve 出的空
     * （见 [handlerResolvedCaps]），且无失败分类、无脚本自报错误；其余空返回
     * （未执行/无效返回）一律不缓存、不毒化后续尝试。
     */
    private val urlCallFlights = SingleFlightNegativeCache<String, ScriptUrlCallResult>(
        ScriptExecutionLimits.NEGATIVE_RESULT_TTL_MS,
        ScriptExecutionLimits.MAX_NEGATIVE_RESULTS,
        isFailure = { it.url.isBlank() && it.failureCategory.isBlank() && it.cacheableFailure }
    )

    /**
     * 取链（洛雪：handlers.request → musicUrl Promise/string）
     * 同脚本槽位保持 single-flight；不同脚本只在 WebView 提交阶段串行。
     */
    suspend fun getMusicUrl(
        id: String,
        code: String,
        musicInfo: JSONObject,
        source: String,
        quality: String,
        purpose: ResolveTicket.Purpose = ResolveTicket.Purpose.PLAY,
        scriptMeta: String = "{}",
        /**
         * H2-b 阶段内软超时：>0 且小于 16s 硬看门狗时，本次尝试的等待上限取此值。
         * 软超时只是调用方的预算决定，不构成引擎卡死证据，绝不触发健康重建；
         * 迟到的 JS 结果仍按 capability 失效规则丢弃。0 = 沿用 16s 硬看门狗。
         */
        attemptTimeoutMs: Long = 0L
    ): String {
        if (!ownerCurrent() || code.isBlank()) return ""
        if (!ScriptExecutionLimits.scriptCodeAllowed(code) ||
            !ScriptExecutionLimits.scriptMetadataAllowed(scriptMeta) ||
            !ScriptExecutionLimits.musicInfoAllowed(musicInfo.toString())) {
            lastFailureByScript[id] = "too_large"
            return ""
        }
        val requestKey = scriptUrlRequestKey(id, code, musicInfo, source, quality, purpose, scriptMeta, attemptTimeoutMs)
        // 总预算封顶：泳道排队 + WebView 求值整体不得超过解析链预算，超时按 timeout 分类（可立即重试，不进负缓存）。
        val result = withTimeoutOrNull(ScriptExecutionLimits.SCRIPT_URL_CALL_TOTAL_MS) {
            urlCallFlights.run(requestKey) {
                lastFailureByScript.remove(id)
                val outcome = HandlerOutcome()
                val url = getMusicUrlUncoalesced(id, code, musicInfo, source, quality, purpose, scriptMeta, attemptTimeoutMs, outcome)
                val failure = if (url.isBlank()) lastFailureByScript[id].orEmpty() else ""
                // ACCEPT4：可进负缓存的只有 handler 真正 resolve 的语义空（无失败分类、
                // 无自报错误）；未执行/无效空返回 cacheableFailure=false，绝不毒化缓存。
                val definitiveEmpty = outcome.resolved && failure.isEmpty() &&
                    lastExplicitErrorByScript[id].isNullOrEmpty()
                ScriptUrlCallResult(url, failure, ownerCurrent() && definitiveEmpty)
            }
        }
        if (result == null) {
            if (ownerCurrent()) lastFailureByScript[id] = "timeout"
            return ""
        }
        if (result.failureCategory.isEmpty()) lastFailureByScript.remove(id)
        else lastFailureByScript[id] = result.failureCategory
        return result.url
    }

    private fun scriptUrlRequestKey(
        id: String,
        code: String,
        musicInfo: JSONObject,
        source: String,
        quality: String,
        purpose: ResolveTicket.Purpose,
        scriptMeta: String,
        attemptTimeoutMs: Long
    ): String {
        val material = JSONObject().apply {
            put("slot", slotId(id, code))
            put("song", musicInfo.toString())
            put("source", source)
            put("quality", quality)
            put("purpose", purpose.name)
            put("meta", scriptMeta)
            put("attemptTimeoutMs", attemptTimeoutMs)
        }.toString()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun getMusicUrlUncoalesced(
        id: String,
        code: String,
        musicInfo: JSONObject,
        source: String,
        quality: String,
        purpose: ResolveTicket.Purpose = ResolveTicket.Purpose.PLAY,
        scriptMeta: String = "{}",
        attemptTimeoutMs: Long = 0L,
        outcome: HandlerOutcome
    ): String = coroutineScope {
        if (!ownerCurrent() || code.isBlank()) return@coroutineScope ""
        // SW-02-12：全局评估互斥改按脚本槽位的泳道——同脚本 single-flight，
        // 不同脚本只在 WebView 提交瞬间串行，Promise 等待不再占用全局锁。
        val scriptSlot = slotId(id, code)
        val capability = newCapability()
        val def = CompletableDeferred<String>()
        pendingUrl[capability] = PendingUrlRequest(capability, def, purpose, currentCoroutineContext()[Job])
        capToScript[capability] = id
        // H1-a：自报错误按次归属，进新一次尝试前先清掉上一首的残留，避免误记。
        lastExplicitErrorByScript.remove(id)
        explicitErrorByCapability.remove(capability)
        routeByCapability.remove(capability)
        var cleanupDone = false
        suspend fun cleanupRequest() = withContext(NonCancellable) {
            evaluationLanes.submit {
                synchronized(requestLock) {
                    pendingUrl.remove(capability)
                    releaseRequest(capability, invalidate = !def.isCompleted || def.isCancelled)
                    capToScript.remove(capability)
                    synchronousHandlers.remove(capability)
                    lastFetchFailure.remove(capability)
                    explicitErrorByCapability.remove(capability)
                    routeByCapability.remove(capability)
                    handlerResolvedCaps.remove(capability)
                }
                maybeRebuildWhenIdle()
            }
        }
        try {
            evaluationLanes.withSlot(scriptSlot) {
                try {
                    ensureStarted()
                    // WebView 冷启动 + 脚本 init + 一次 HTTPS 往返，4500ms 会被误杀成“脚本失败”。
                    // Sollin/洛雪同脚本可播时，我们这里必须给单次 musicUrl 足够完成窗口。
                    // WebView 首次创建在低端机/冷启动时可能超过 2 秒；过早销毁会让同一轮所有脚本都变成超时。
                    if (withTimeoutOrNull(6_000L) { ready.await() } != true) {
                        // Readiness failure is local to this request; never destroy shared work.
                        lastFailureByScript[id] = "timeout"
                        return@withSlot ""
                    }
                    val submitted = evaluationLanes.submit {
                        if (!ownerCurrent()) return@submit false
                        val js = buildJs(capability, scriptSlot, code, musicInfo.toString(), source, quality, scriptMeta)
                        main.post {
                            if (!pendingUrl.containsKey(capability)) return@post
                            try {
                                webView?.evaluateJavascript(js, null) ?: completeAndRemove(capability, "")
                            } catch (_: Exception) {
                                completeAndRemove(capability, "")
                            }
                        }
                        true
                    }
                    if (!submitted) return@withSlot ""
                    val watchdogMs = if (attemptTimeoutMs in 1L until 16_000L) attemptTimeoutMs else 16_000L
                    val url = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(watchdogMs) { def.await() }
                }
                    // ACCEPT4：handler 是否真正 resolve 过（负缓存准入的事实来源）；
                    // 看门狗超时/合成完成一律无此标记。读取后即移除，迟到标记由 cleanup 兜底清。
                    val handlerResolved = handlerResolvedCaps.remove(capability)
                    // H3 诊断：本次尝试实际走的通道随结果一并归属到脚本（成功/失败都记）。
                    routeByCapability.remove(capability)?.let { lastRouteByScript[id] = it }
                    val result = if (url == null) {
                        if (watchdogMs < 16_000L) {
                            // H2-b 软超时：预算决定而非引擎故障——不请求健康重建，
                            // 以独立分类回传 Resolver 按瞬时失败记账（不入 H1-a 裁决）。
                            lastFailureByScript[id] = "soft_timeout"
                        } else {
                            val handlerReturned = !synchronousHandlers.contains(capability)
                            // A slow Promise has returned and is not evidence of a dead JS engine.
                            // Only a handler still executing synchronously is a bounded rebuild signal.
                            if (!handlerReturned) requestHealthRecovery(purpose)
                            lastFailureByScript[id] = "timeout"
                        }
                        ""
                    } else if ((url.startsWith("http://") || url.startsWith("https://")) && url.length <= 2048) {
                        url
                    } else {
                        val reason = lastFetchFailure.remove(capability).orEmpty()
                        if (reason.isNotEmpty()) lastFailureByScript[id] = reason
                        // H1-a：脚本自报错误（Promise reject 的消息）在此归属到脚本，
                        // 供 Resolver 做终局分类；空链且无自报错误仍只是 EMPTY。
                        explicitErrorByCapability.remove(capability)?.let { lastExplicitErrorByScript[id] = it }
                        ""
                    }
                    // 不复位 WebView：同一 resolve 内多次取链复用当前 WebView，
                    // buildJs 保持脚本槽位与初始化状态；前台点歌只取消预取/探测，不影响下载。
                    outcome.resolved = handlerResolved
                    if (ownerCurrent()) result else ""
                } finally {
                    // 清理经泳道提交（NonCancellable）：即使本请求已被取消，JS 侧的
                    // cancel 也必须发出，否则新请求可能重绑到旧 lx 状态上。
                    cleanupRequest()
                    cleanupDone = true
                }
            }
        } finally {
            // 在槽位队列上被取消、从未进入内层 finally 的请求在此兜底清理。
            if (!cleanupDone) cleanupRequest()
        }
    }

    /** 预热：只 init 不取链 */
    suspend fun warmup(id: String, code: String, scriptMeta: String = "{}"): Unit = coroutineScope {
        if (!ownerCurrent() || code.isBlank()) return@coroutineScope
        if (!ScriptExecutionLimits.scriptCodeAllowed(code) || !ScriptExecutionLimits.scriptMetadataAllowed(scriptMeta)) {
            lastFailureByScript[id] = "too_large"
            return@coroutineScope
        }
        val scriptSlot = slotId(id, code)
        val capability = newCapability()
        val def = CompletableDeferred<String>()
        pendingUrl[capability] = PendingUrlRequest(capability, def, ResolveTicket.Purpose.PROBE, currentCoroutineContext()[Job])
        var cleanupDone = false
        suspend fun cleanupRequest() = withContext(NonCancellable) {
            evaluationLanes.submit {
                synchronized(requestLock) {
                    pendingUrl.remove(capability)
                    releaseRequest(capability, invalidate = !def.isCompleted || def.isCancelled)
                }
            }
        }
        try {
            evaluationLanes.withSlot(scriptSlot) {
                try {
                    // 预热以前没有启动 WebView，冷启动/首次导入时 ready 永远等不到，导致检测看起来全部无结果。
                    ensureStarted()
                    if (withTimeoutOrNull(2500) { ready.await() } != true) {
                        // A warmup timeout cannot tear down another request's WebView.
                        return@withSlot Unit
                    }
                    val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val js = """
                    (function(){
                      var CAP=${JSONObject.quote(capability)};
                      var sid=${JSONObject.quote(scriptSlot)};
                      var __meta=${JSONObject.quote(scriptMeta)};
                      try {
                        if(!window.__swRoot){ ${runtimeBootJs()} }
                        if(!window.__swScripts[sid]){
                          var code = decodeURIComponent(escape(atob(${JSONObject.quote(b64)})));
                          window.__ensureScript(sid, code, __meta, CAP);
                        }
                        var n=0;
                        function tick(){
                          var e=window.__swScripts[sid];
                          if(e && e.inited){ window.__swOnUrl(CAP, 'ok'); return; }
                          if(++n>50){ window.__swOnUrl(CAP, ''); return; }
                          setTimeout(tick, 60);
                        }
                        tick();
                      } catch(e) { try{window.__swOnUrl(CAP,'');}catch(_){ } }
                    })();
                    """.trimIndent()
                    evaluationLanes.submit {
                        main.post {
                            if (!pendingUrl.containsKey(capability)) return@post
                            try {
                                webView?.evaluateJavascript(js, null)
                            } catch (_: Exception) {
                                completeAndRemove(capability, "")
                            }
                        }
                    }
                    withContext(Dispatchers.IO) { withTimeoutOrNull(4000L) { def.await() } }
                } finally {
                    cleanupRequest()
                    cleanupDone = true
                }
            }
        } finally {
            if (!cleanupDone) cleanupRequest()
        }
    }

    /**
     * r658c S：读取脚本 `send(inited, {sources})` 声明的平台与音质（只读，不取链），供完整检测按声明遍历。
     * 返回紧凑串 `key=q1,q2;key2=*;key3=!`（* = 未列音质，! = 未声明 musicUrl）；
     * 已初始化但未声明返回 [ScriptProbeMatrix.NO_DECLARATION]；初始化失败/超时返回空串（调用方按全矩阵兼容遍历）。
     */
    suspend fun declaredSources(id: String, code: String, scriptMeta: String = "{}"): String = coroutineScope {
        if (!ownerCurrent() || code.isBlank()) return@coroutineScope ""
        if (!ScriptExecutionLimits.scriptCodeAllowed(code) || !ScriptExecutionLimits.scriptMetadataAllowed(scriptMeta)) {
            lastFailureByScript[id] = "too_large"
            return@coroutineScope ""
        }
        val scriptSlot = slotId(id, code)
        val capability = newCapability()
        val def = CompletableDeferred<String>()
        pendingUrl[capability] = PendingUrlRequest(capability, def, ResolveTicket.Purpose.PROBE, currentCoroutineContext()[Job])
        var cleanupDone = false
        suspend fun cleanupRequest() = withContext(NonCancellable) {
            evaluationLanes.submit {
                synchronized(requestLock) {
                    pendingUrl.remove(capability)
                    releaseRequest(capability, invalidate = !def.isCompleted || def.isCancelled)
                }
            }
        }
        try {
            evaluationLanes.withSlot(scriptSlot) {
                try {
                    ensureStarted()
                    if (withTimeoutOrNull(6_000L) { ready.await() } != true) return@withSlot ""
                    val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val js = """
                    (function(){
                      var CAP=${JSONObject.quote(capability)};
                      var sid=${JSONObject.quote(scriptSlot)};
                      var __meta=${JSONObject.quote(scriptMeta)};
                      function describe(s){
                        try {
                          var keys=Object.keys(s||{});
                          if(!keys.length) return ${JSONObject.quote(ScriptProbeMatrix.NO_DECLARATION)};
                          var okKey=/^[A-Za-z0-9_\-\u4e00-\u9fff]{1,32}$/, okQ=/^[A-Za-z0-9_\-]{1,24}$/, out=[];
                          keys.forEach(function(k){
                            if(!okKey.test(k)) return;
                            var d=s[k]||{};
                            if(Array.isArray(d.actions) && d.actions.indexOf('musicUrl')<0){ out.push(k+'=!'); return; }
                            var q=d.qualitys || d.qualities;
                            if(!Array.isArray(q)){ out.push(k+'=*'); return; }
                            out.push(k+'='+q.map(function(x){ return String(x); }).filter(function(x){ return okQ.test(x); }).join(','));
                          });
                          return out.length ? out.join(';').slice(0, 8000) : ${JSONObject.quote(ScriptProbeMatrix.NO_DECLARATION)};
                        } catch(err) { return ''; }
                      }
                      try {
                        if(!window.__swRoot){ ${runtimeBootJs()} }
                        if(!window.__swScripts[sid]){
                          var code = decodeURIComponent(escape(atob(${JSONObject.quote(b64)})));
                          window.__ensureScript(sid, code, __meta, CAP);
                        }
                        var n=0;
                        function tick(){
                          var e=window.__swScripts[sid];
                          if(e && e.inited){ window.__swOnUrl(CAP, describe(e.sources)); return; }
                          if(++n>55){ window.__swOnUrl(CAP, ''); return; }
                          setTimeout(tick, 60);
                        }
                        tick();
                      } catch(e) { try{window.__swOnUrl(CAP,'');}catch(_){ } }
                    })();
                    """.trimIndent()
                    evaluationLanes.submit {
                        main.post {
                            if (!pendingUrl.containsKey(capability)) return@post
                            try {
                                webView?.evaluateJavascript(js, null) ?: completeAndRemove(capability, "")
                            } catch (_: Exception) {
                                completeAndRemove(capability, "")
                            }
                        }
                    }
                    withContext(Dispatchers.IO) { withTimeoutOrNull(5_000L) { def.await() } }.orEmpty()
                } finally {
                    cleanupRequest()
                    cleanupDone = true
                }
            }
        } finally {
            if (!cleanupDone) cleanupRequest()
        }
    }

    private fun buildJs(
        capability: String,
        scriptId: String,
        code: String,
        musicInfoStr: String,
        source: String,
        quality: String,
        scriptMeta: String
    ): String {
        val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // musicInfoStr is raw JSON object literal
        return """
        (function(){
          var CAP=${JSONObject.quote(capability)};
          var sid=${JSONObject.quote(scriptId)};
          var __src=${JSONObject.quote(source)};
          var __q=${JSONObject.quote(quality)};
          var __meta=${JSONObject.quote(scriptMeta)};
          var __info=$musicInfoStr;
          function done(u){ try{ window.__swOnUrl(CAP, u||''); }catch(e){} }
          function reportErr(x){
            // H1-a：把脚本自报错误（reject 的 Error / 错误对象）回传原生层做终局分类；
            // 以前这里被静默吞掉，解析器无法区分“脚本已死”与“脚本没这首歌”。
            try{
              var m='';
              if(typeof x==='string') m=x;
              else if(x && typeof x==='object') m=String(x.message||x.error||x.msg||'');
              if(m) window.__swOnScriptError(CAP, m.slice(0,200));
            }catch(e){}
          }
          try {
            if(!window.__swRoot){ ${runtimeBootJs()} }
            // 对齐洛雪/LX 协议与老版本：脚本只在导入/启用时初始化一次，handler 复用。
            // 洛雪与 Sollin v2 每首歌只是换 musicInfo 再调同一个 handlers.request；
            // 这里绝不能每次取链都 __swInvalidate + __ensureScript 重跑整段脚本，
            // 否则脚本异步初始化（token/握手）被反复重置，第二首起返回空链。
            var code = decodeURIComponent(escape(atob(${JSONObject.quote(b64)})));
            window.__ensureScript(sid, code, __meta, CAP);
            var entry = window.__swScripts[sid];
            if(!entry){ done(''); return; }
            var n=0;
            function waitInited(cb){
              // LX scripts may register request before async token/config/bootstrap completes.
              // Do not invoke musicUrl until send(EVENT_NAMES.inited, ...) has arrived;
              // checking only handlers.request races the script's own initialization.
              if(entry.inited){ cb(true); return; }
              if(++n>55){ cb(false); return; }
              setTimeout(function(){ waitInited(cb); }, 60);
            }
            waitInited(function(ok){
              if(!ok && !(entry.handlers && entry.handlers.request)){ done(''); return; }
              try {
                var sources = entry.sources || {};
                var keys = Object.keys(sources);
                var raw = __src;
                var requestQuality = __q;
                if(keys.length){
                  var norm=function(s){
                    var key=String(s||'').toLowerCase().replace(/music$/,'');
                    var aliases={netease:'wy',kugou:'kg',kuwo:'kw',qq:'tx',tencent:'tx',migu:'mg',网易:'wy',网易云:'wy',酷狗:'kg',酷我:'kw',腾讯:'tx',咪咕:'mg'};
                    return aliases[key] || key;
                  };
                  var hit=keys.find(function(k){return norm(k)===norm(__src);});
                  // Explicit declaration is authoritative; missing declarations retain legacy compatibility.
                  if(!hit){ done(''); return; }
                  raw=hit;
                  var declared=sources[hit] || {};
                  if(Array.isArray(declared.actions) && declared.actions.indexOf('musicUrl')<0){ done(''); return; }
                  var qualities=declared.qualitys || declared.qualities;
                  if(Array.isArray(qualities)){
                    var qualityKey=function(q){return q==='hires' ? 'flac24bit' : q;};
                    var match=qualities.find(function(q){return q===__q;});
                    if(!match) match=qualities.find(function(q){return qualityKey(q)===qualityKey(__q);});
                    if(!match){ done(''); return; }
                    requestQuality=match;
                  }
                }
                var h = entry.handlers && entry.handlers.request;
                if(!h){ done(''); return; }
                var ret = window.__swInvokeHandler(CAP, h, { source: raw, action: 'musicUrl', info: { type: requestQuality, musicInfo: __info } });
                Promise.resolve(ret).then(function(v){
                  var u='';
                  if(typeof v==='string') u=v;
                  else if(v && typeof v==='object') {
                    // LX/Sollin 兼容返回：string、{url}、{data:{url}}、{body:{data:{url}}}。
                    // 只读取 URL，不把整段响应正文传回原生层。
                    function pickUrl(x, depth){
                      if(!x || depth>5) return '';
                      if(typeof x==='string') return /^https?:\/\//i.test(x.trim()) ? x.trim() : '';
                      if(Array.isArray(x)){
                        for(var ai=0; ai<x.length; ai++){ var au=pickUrl(x[ai], depth+1); if(au) return au; }
                        return '';
                      }
                      if(typeof x==='object'){
                        var direct=['url','playUrl','play_url','audioUrl','audio_url','src'];
                        for(var di=0; di<direct.length; di++){ var du=pickUrl(x[direct[di]], depth+1); if(du) return du; }
                        var nested=['data','body','result','response','info'];
                        for(var ni=0; ni<nested.length; ni++){ var nu=pickUrl(x[nested[ni]], depth+1); if(nu) return nu; }
                      }
                      return '';
                    }
                    u=pickUrl(v,0);
                  }
                  if(u && /^https?:\/\//i.test(u) && u.length<=2048) done(u); else { reportErr(v); done(''); }
                }).catch(function(e){ reportErr(e); done(''); });
              } catch(e){ done(''); }
            });
          } catch(e){ done(''); }
        })();
        """.trimIndent()
    }

    private fun runtimeBootJs(): String = """
      (function(){
      window.__swBridge = window.NativeBridge;   // 内核自持真实桥引用，脚本替换 window.NativeBridge 不影响取链
      // Android WebView 的 JavascriptInterface 方法必须通过 bridge receiver 调用；
      // 不能把 Java bridge method 脱离对象保存后再裸调用，否则部分 WebView 会静默失败，
      // 表现为“脚本已执行但没有返回可播放链接”。
      window.__swNativeFetch = function(){
        if(!window.__swBridge || !window.__swBridge.nativeFetch) throw new Error('nativeFetch bridge unavailable');
        return window.__swBridge.nativeFetch.apply(window.__swBridge, arguments);
      };
      window.__swNativeCancel = function(){
        if(!window.__swBridge || !window.__swBridge.nativeCancel) return;
        return window.__swBridge.nativeCancel.apply(window.__swBridge, arguments);
      };
      window.__swOnUrl = function(){
        if(!window.__swBridge || !window.__swBridge.onUrlResult) throw new Error('onUrlResult bridge unavailable');
        return window.__swBridge.onUrlResult.apply(window.__swBridge, arguments);
      };
      window.__swOnScriptError = function(){
        if(!window.__swBridge || !window.__swBridge.onScriptError) return;
        return window.__swBridge.onScriptError.apply(window.__swBridge, arguments);
      };
      window.__swHandlerStart = function(cap){
        if(window.__swBridge && window.__swBridge.handlerStart) window.__swBridge.handlerStart(cap);
      };
      window.__swHandlerReturned = function(cap){
        if(window.__swBridge && window.__swBridge.handlerReturned) window.__swBridge.handlerReturned(cap);
      };
      // Track only the synchronous call, never the lifetime of its returned Promise.
      // A thrown handler has also returned control and must not look like a hung engine.
      window.__swInvokeHandler = function(CAP, handler, request){
        window.__swHandlerStart(CAP);
        try { return handler(request); }
        finally { window.__swHandlerReturned(CAP); }
      };
      window.__swRoot=true;
      window.__swScripts=window.__swScripts||{};
      window.__swFetchCbs=window.__swFetchCbs||{};
      window.__swResponseHeaders=window.__swResponseHeaders||{};
      window.__swFetchSeq=1;
      // nativeFetch 回调表移入闭包：任何脚本都无法从 window 枚举/读取/覆盖他人挂起的回调。
      // 原生回调经钉死的 __swDispatch 分发；cid 含随机数不可猜测，伪造 nativeFetch 无法命中他人回调。
      var __swCbMap={};
      var __swRequestScopes={};
      window.__swBindRequest=function(sid, cap){
        var scope=__swRequestScopes[cap];
        if(!scope) scope=__swRequestScopes[cap]={sid:sid,closed:false,children:{}};
        var e=window.__swScripts[sid];
        if(e && e.lx) e.lx.__bindRequest(scope,cap);
      };
      window.__swCancelRequest=function(cap,invalidate){
        var scope=__swRequestScopes[cap];
        if(!scope) return;
        scope.closed=true;
        Object.keys(scope.children).forEach(function(cid){scope.children[cid]();});
        // A cancelled handler may resume after await. Keep its old lx closed;
        // the next request gets a fresh slot rather than inheriting its children.
        var e=window.__swScripts[scope.sid];
        if(invalidate!==false && e && e.lx && e.lx.__ownsRequest(scope)) delete window.__swScripts[scope.sid];
        delete __swRequestScopes[cap];
      };
      window.__swDispatch=function(cid, code, text, headersJson){
        try{ if(headersJson) __swResponseHeaders[cid]=headersJson; }catch(_){}
        var fn=__swCbMap[cid];
        if(fn){ try{ fn(code, text); }catch(e){} }
        else { delete __swResponseHeaders[cid]; }
      };
      // 脚本失效/重载：直接删除槽位，下一次 __ensureScript 会重新创建干净 entry。
      window.__swInvalidate=function(sid){
        try{ delete window.__swScripts[sid]; }catch(_){}
      };
      // utils.zlib → Kotlin java.util.zip 桥（与 nativeFetch 同通道异步回传）
      window.__swLxCrypto=function(op, b64){
        return new Promise(function(resolve, reject){
          var cid='lxz'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2)+'_'+(window.__swFetchSeq++);
          var settled=false;
          var timer=setTimeout(function(){
            if(settled) return; settled=true; delete __swCbMap[cid];
            reject(new Error('zlib timeout'));
          }, 15000);
          __swCbMap[cid]=function(code, text){
            if(settled) return; settled=true; clearTimeout(timer); delete __swCbMap[cid];
            if(code>0 && code<400 && text) resolve(text);
            else reject(new Error('zlib '+code));
          };
          try{
            if(!window.__swBridge || !window.__swBridge.lxCrypto) throw new Error('lxCrypto bridge unavailable');
            window.__swBridge.lxCrypto(op, b64, cid);
          }catch(e){
            if(settled) return; settled=true; clearTimeout(timer); delete __swCbMap[cid];
            reject(e);
          }
        });
      };
      // md5 同步（洛雪脚本大量同步调用）
      window.__md5=(function(){
        function md5cycle(x,k){var a=x[0],b=x[1],c=x[2],d=x[3];
          a=ff(a,b,c,d,k[0],7, -680876936);d=ff(d,a,b,c,k[1],12,-389564586);c=ff(c,d,a,b,k[2],17, 606105819);b=ff(b,c,d,a,k[3],22,-1044525330);
          a=ff(a,b,c,d,k[4],7, -176418897);d=ff(d,a,b,c,k[5],12, 1200080426);c=ff(c,d,a,b,k[6],17,-1473231341);b=ff(b,c,d,a,k[7],22, -45705983);
          a=ff(a,b,c,d,k[8],7, 1770035416);d=ff(d,a,b,c,k[9],12,-1958414417);c=ff(c,d,a,b,k[10],17, -42063);b=ff(b,c,d,a,k[11],22,-1990404162);
          a=ff(a,b,c,d,k[12],7, 1804603682);d=ff(d,a,b,c,k[13],12, -40341101);c=ff(c,d,a,b,k[14],17,-1502002290);b=ff(b,c,d,a,k[15],22, 1236535329);
          a=gg(a,b,c,d,k[1],5, -165796510);d=gg(d,a,b,c,k[6],9,-1069501632);c=gg(c,d,a,b,k[11],14, 643717713);b=gg(b,c,d,a,k[0],20, -373897302);
          a=gg(a,b,c,d,k[5],5, -701558691);d=gg(d,a,b,c,k[10],9, 38016083);c=gg(c,d,a,b,k[15],14,-660478335);b=gg(b,c,d,a,k[4],20, -405537848);
          a=gg(a,b,c,d,k[9],5, 568446438);d=gg(d,a,b,c,k[14],9,-1019803690);c=gg(c,d,a,b,k[3],14,-187363961);b=gg(b,c,d,a,k[8],20, 1163531501);
          a=gg(a,b,c,d,k[13],5, -1444681467);d=gg(d,a,b,c,k[2],9, -51403784);c=gg(c,d,a,b,k[7],14, 1735328473);b=gg(b,c,d,a,k[12],20,-1926607734);
          a=hh(a,b,c,d,k[5],4, -378558);d=hh(d,a,b,c,k[8],11,-2022574463);c=hh(c,d,a,b,k[11],16, 1839030562);b=hh(b,c,d,a,k[14],23, -35309556);
          a=hh(a,b,c,d,k[1],4, -1530992060);d=hh(d,a,b,c,k[4],11, 1272893353);c=hh(c,d,a,b,k[7],16, -155497632);b=hh(b,c,d,a,k[10],23,-1094730640);
          a=hh(a,b,c,d,k[13],4, 681279174);d=hh(d,a,b,c,k[0],11, -358537222);c=hh(c,d,a,b,k[3],16, -722521979);b=hh(b,c,d,a,k[6],23, 76029189);
          a=hh(a,b,c,d,k[9],4, -640364487);d=hh(d,a,b,c,k[12],11, -421815835);c=hh(c,d,a,b,k[15],16, 530742520);b=hh(b,c,d,a,k[2],23, -995338651);
          a=ii(a,b,c,d,k[0],6, -198630844);d=ii(d,a,b,c,k[7],10, 1126891415);c=ii(c,d,a,b,k[14],15,-1416354905);b=ii(b,c,d,a,k[5],21, -57434055);
          a=ii(a,b,c,d,k[12],6, 1700485571);d=ii(d,a,b,c,k[3],10,-1894986606);c=ii(c,d,a,b,k[10],15, -1051523);b=ii(b,c,d,a,k[1],21,-2054922799);
          a=ii(a,b,c,d,k[8],6, 1873313359);d=ii(d,a,b,c,k[15],10, -30611744);c=ii(c,d,a,b,k[6],15,-1560198380);b=ii(b,c,d,a,k[13],21, 1309151649);
          a=ii(a,b,c,d,k[4],6, -145523070);d=ii(d,a,b,c,k[11],10,-1120210379);c=ii(c,d,a,b,k[2],15, 718787259);b=ii(b,c,d,a,k[9],21, -343485551);
          x[0]=add32(a,x[0]);x[1]=add32(b,x[1]);x[2]=add32(c,x[2]);x[3]=add32(d,x[3]);}
        function cmn(q,a,b,x,s,t){a=add32(add32(a,q),add32(x,t));return add32((a<<s)|(a>>> (32-s)),b);}
        function ff(a,b,c,d,x,s,t){return cmn((b&c)|((~b)&d),a,b,x,s,t);}
        function gg(a,b,c,d,x,s,t){return cmn((b&d)|(c&(~d)),a,b,x,s,t);}
        function hh(a,b,c,d,x,s,t){return cmn(b^c^d,a,b,x,s,t);}
        function ii(a,b,c,d,x,s,t){return cmn(c^(b|(~d)),a,b,x,s,t);}
        function md51(s){var n=s.length,state=[1732584193,-271733879,-1732584194,271733878],i;
          for(i=64;i<=n;i+=64) md5cycle(state, md5blk(s.substring(i-64,i)));
          s=s.substring(i-64); var tail=new Array(16).fill(0);
          for(i=0;i<s.length;i++) tail[i>>2]|=s.charCodeAt(i)<<((i%4)<<3);
          tail[i>>2]|=0x80<<((i%4)<<3); if(i>55){md5cycle(state,tail); tail=new Array(16).fill(0);}
          tail[14]=n*8; md5cycle(state,tail); return state;}
        function md5blk(s){var md5blks=[],i; for(i=0;i<64;i+=4) md5blks[i>>2]=s.charCodeAt(i)+(s.charCodeAt(i+1)<<8)+(s.charCodeAt(i+2)<<16)+(s.charCodeAt(i+3)<<24); return md5blks;}
        var hex_chr='0123456789abcdef'.split('');
        function rhex(n){var s='',j; for(j=0;j<4;j++) s+=hex_chr[(n>>(j*8+4))&0x0F]+hex_chr[(n>>(j*8))&0x0F]; return s;}
        function hex(x){for(var i=0;i<x.length;i++) x[i]=rhex(x[i]); return x.join('');}
        function add32(a,b){return (a+b)&0xFFFFFFFF;}
        function md5(s){ return hex(md51(s)); }
        return function(input){ try{ return md5(String(input)); }catch(e){ return ''; } };
      })();

(function(){
function _lxUtf8ToBytes(str) {
          var utf8 = unescape(encodeURIComponent(str));
          var arr = new Uint8Array(utf8.length);
          for (var i = 0; i < utf8.length; i++) arr[i] = utf8.charCodeAt(i);
          return arr;
        }
        function _lxBytesToUtf8(bytes) {
          var s = '';
          for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
          try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
        }
        function _lxBytesToBase64(bytes) {
          var binary = '';
          for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
          var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
          var output = '';
          var i2 = 0;
          while (i2 < binary.length) {
            var chr1 = binary.charCodeAt(i2++);
            var chr2 = binary.charCodeAt(i2++);
            var chr3 = binary.charCodeAt(i2++);
            var enc1 = chr1 >> 2;
            var enc2 = ((chr1 & 3) << 4) | (chr2 >> 4);
            var enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
            var enc4 = chr3 & 63;
            if (isNaN(chr2)) {
              enc3 = enc4 = 64;
            } else if (isNaN(chr3)) {
              enc4 = 64;
            }
            output = output +
              chars.charAt(enc1) +
              chars.charAt(enc2) +
              chars.charAt(enc3) +
              chars.charAt(enc4);
          }
          return output;
        }
        function _lxBase64ToBytes(str) {
          var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
          var input = str.replace(/[^A-Za-z0-9\+\/\=]/g, '');
          var output = [];
          var i = 0;
          while (i < input.length) {
            var enc1 = chars.indexOf(input.charAt(i++));
            var enc2 = chars.indexOf(input.charAt(i++));
            var enc3 = chars.indexOf(input.charAt(i++));
            var enc4 = chars.indexOf(input.charAt(i++));
            var chr1 = (enc1 << 2) | (enc2 >> 4);
            var chr2 = ((enc2 & 15) << 4) | (enc3 >> 2);
            var chr3 = ((enc3 & 3) << 6) | enc4;
            output.push(chr1);
            if (enc3 != 64) output.push(chr2);
            if (enc4 != 64) output.push(chr3);
          }
          return new Uint8Array(output);
        }
        function _lxInputToBytes(input) {
          if (input == null) return new Uint8Array();
          if (input instanceof Uint8Array) return input;
          if (input instanceof ArrayBuffer) return new Uint8Array(input);
          if (typeof input === 'string') return _lxUtf8ToBytes(input);
          if (Array.isArray(input)) return new Uint8Array(input);
          return new Uint8Array();
        }
        function _lxCallHandler(name, payload) {
          if (name === 'LxCrypto' && typeof window.__swLxCrypto === 'function') {
            return window.__swLxCrypto(payload.op, payload.data);
          }
          return Promise.reject('bridge_not_ready');
        }

        function _lxBytesToHex(bytes) {
          var hex = '';
          for (var i = 0; i < bytes.length; i++) {
            var h = bytes[i].toString(16);
            if (h.length < 2) h = '0' + h;
            hex += h;
          }
          return hex;
        }
        function _lxHexToBytes(hex) {
          var clean = String(hex || '').replace(/[^a-fA-F0-9]/g, '');
          if (clean.length % 2) clean = '0' + clean;
          var out = new Uint8Array(clean.length / 2);
          for (var i = 0; i < out.length; i++) {
            out[i] = parseInt(clean.substr(i * 2, 2), 16);
          }
          return out;
        }
        function _lxConcatBytes(a, b) {
          var out = new Uint8Array(a.length + b.length);
          out.set(a, 0);
          out.set(b, a.length);
          return out;
        }
        function _lxPkcs7Pad(bytes) {
          var pad = 16 - (bytes.length % 16);
          var out = new Uint8Array(bytes.length + pad);
          out.set(bytes, 0);
          for (var i = bytes.length; i < out.length; i++) out[i] = pad;
          return out;
        }
        function _lxPkcs7Unpad(bytes) {
          if (!bytes || !bytes.length) return bytes;
          var pad = bytes[bytes.length - 1];
          if (pad <= 0 || pad > 16) return bytes;
          for (var i = bytes.length - pad; i < bytes.length; i++) {
            if (bytes[i] !== pad) return bytes;
          }
          return bytes.slice(0, bytes.length - pad);
        }

        function _lxAdd32(a, b) { return (a + b) | 0; }
        function _lxRol(num, cnt) { return (num << cnt) | (num >>> (32 - cnt)); }
        function _lxRor(num, cnt) { return (num >>> cnt) | (num << (32 - cnt)); }
        function _lxWordsToBytes(words) {
          var out = new Uint8Array(words.length * 4);
          for (var i = 0; i < words.length; i++) {
            var w = words[i];
            out[i * 4] = w & 0xff;
            out[i * 4 + 1] = (w >>> 8) & 0xff;
            out[i * 4 + 2] = (w >>> 16) & 0xff;
            out[i * 4 + 3] = (w >>> 24) & 0xff;
          }
          return out;
        }
        function _lxWordsToHex(words) {
          var hex = '';
          for (var i = 0; i < words.length; i++) {
            var w = words[i];
            for (var j = 3; j >= 0; j--) {
              var b = (w >>> (j * 8)) & 0xff;
              hex += (b + 0x100).toString(16).substring(1);
            }
          }
          return hex;
        }

        function _lxMd5Cmn(q, a, b, x, s, t) { return _lxAdd32(_lxRol(_lxAdd32(_lxAdd32(a, q), _lxAdd32(x, t)), s), b); }
        function _lxMd5F(a, b, c, d, x, s, t) { return _lxMd5Cmn((b & c) | (~b & d), a, b, x, s, t); }
        function _lxMd5G(a, b, c, d, x, s, t) { return _lxMd5Cmn((b & d) | (c & ~d), a, b, x, s, t); }
        function _lxMd5H(a, b, c, d, x, s, t) { return _lxMd5Cmn(b ^ c ^ d, a, b, x, s, t); }
        function _lxMd5I(a, b, c, d, x, s, t) { return _lxMd5Cmn(c ^ (b | ~d), a, b, x, s, t); }
        function _lxMd5Bytes(bytes) {
          var len = bytes.length;
          var x = [];
          var _mb = (((len + 8) >>> 6) + 1) << 4; for (var _mi = 0; _mi < _mb; _mi++) x[_mi] = 0;
          for (var i = 0; i < len; i++) {
            x[i >> 2] |= bytes[i] << ((i % 4) * 8);
          }
          x[len >> 2] |= 0x80 << ((len % 4) * 8);
          x[(((len + 8) >>> 6) << 4) + 14] = len * 8;
          var a = 1732584193;
          var b = -271733879;
          var c = -1732584194;
          var d = 271733878;
          for (i = 0; i < x.length; i += 16) {
            var oa = a, ob = b, oc = c, od = d;
            a = _lxMd5F(a, b, c, d, x[i + 0], 7, -680876936);
            d = _lxMd5F(d, a, b, c, x[i + 1], 12, -389564586);
            c = _lxMd5F(c, d, a, b, x[i + 2], 17, 606105819);
            b = _lxMd5F(b, c, d, a, x[i + 3], 22, -1044525330);
            a = _lxMd5F(a, b, c, d, x[i + 4], 7, -176418897);
            d = _lxMd5F(d, a, b, c, x[i + 5], 12, 1200080426);
            c = _lxMd5F(c, d, a, b, x[i + 6], 17, -1473231341);
            b = _lxMd5F(b, c, d, a, x[i + 7], 22, -45705983);
            a = _lxMd5F(a, b, c, d, x[i + 8], 7, 1770035416);
            d = _lxMd5F(d, a, b, c, x[i + 9], 12, -1958414417);
            c = _lxMd5F(c, d, a, b, x[i + 10], 17, -42063);
            b = _lxMd5F(b, c, d, a, x[i + 11], 22, -1990404162);
            a = _lxMd5F(a, b, c, d, x[i + 12], 7, 1804603682);
            d = _lxMd5F(d, a, b, c, x[i + 13], 12, -40341101);
            c = _lxMd5F(c, d, a, b, x[i + 14], 17, -1502002290);
            b = _lxMd5F(b, c, d, a, x[i + 15], 22, 1236535329);

            a = _lxMd5G(a, b, c, d, x[i + 1], 5, -165796510);
            d = _lxMd5G(d, a, b, c, x[i + 6], 9, -1069501632);
            c = _lxMd5G(c, d, a, b, x[i + 11], 14, 643717713);
            b = _lxMd5G(b, c, d, a, x[i + 0], 20, -373897302);
            a = _lxMd5G(a, b, c, d, x[i + 5], 5, -701558691);
            d = _lxMd5G(d, a, b, c, x[i + 10], 9, 38016083);
            c = _lxMd5G(c, d, a, b, x[i + 15], 14, -660478335);
            b = _lxMd5G(b, c, d, a, x[i + 4], 20, -405537848);
            a = _lxMd5G(a, b, c, d, x[i + 9], 5, 568446438);
            d = _lxMd5G(d, a, b, c, x[i + 14], 9, -1019803690);
            c = _lxMd5G(c, d, a, b, x[i + 3], 14, -187363961);
            b = _lxMd5G(b, c, d, a, x[i + 8], 20, 1163531501);
            a = _lxMd5G(a, b, c, d, x[i + 13], 5, -1444681467);
            d = _lxMd5G(d, a, b, c, x[i + 2], 9, -51403784);
            c = _lxMd5G(c, d, a, b, x[i + 7], 14, 1735328473);
            b = _lxMd5G(b, c, d, a, x[i + 12], 20, -1926607734);

            a = _lxMd5H(a, b, c, d, x[i + 5], 4, -378558);
            d = _lxMd5H(d, a, b, c, x[i + 8], 11, -2022574463);
            c = _lxMd5H(c, d, a, b, x[i + 11], 16, 1839030562);
            b = _lxMd5H(b, c, d, a, x[i + 14], 23, -35309556);
            a = _lxMd5H(a, b, c, d, x[i + 1], 4, -1530992060);
            d = _lxMd5H(d, a, b, c, x[i + 4], 11, 1272893353);
            c = _lxMd5H(c, d, a, b, x[i + 7], 16, -155497632);
            b = _lxMd5H(b, c, d, a, x[i + 10], 23, -1094730640);
            a = _lxMd5H(a, b, c, d, x[i + 13], 4, 681279174);
            d = _lxMd5H(d, a, b, c, x[i + 0], 11, -358537222);
            c = _lxMd5H(c, d, a, b, x[i + 3], 16, -722521979);
            b = _lxMd5H(b, c, d, a, x[i + 6], 23, 76029189);
            a = _lxMd5H(a, b, c, d, x[i + 9], 4, -640364487);
            d = _lxMd5H(d, a, b, c, x[i + 12], 11, -421815835);
            c = _lxMd5H(c, d, a, b, x[i + 15], 16, 530742520);
            b = _lxMd5H(b, c, d, a, x[i + 2], 23, -995338651);

            a = _lxMd5I(a, b, c, d, x[i + 0], 6, -198630844);
            d = _lxMd5I(d, a, b, c, x[i + 7], 10, 1126891415);
            c = _lxMd5I(c, d, a, b, x[i + 14], 15, -1416354905);
            b = _lxMd5I(b, c, d, a, x[i + 5], 21, -57434055);
            a = _lxMd5I(a, b, c, d, x[i + 12], 6, 1700485571);
            d = _lxMd5I(d, a, b, c, x[i + 3], 10, -1894986606);
            c = _lxMd5I(c, d, a, b, x[i + 10], 15, -1051523);
            b = _lxMd5I(b, c, d, a, x[i + 1], 21, -2054922799);
            a = _lxMd5I(a, b, c, d, x[i + 8], 6, 1873313359);
            d = _lxMd5I(d, a, b, c, x[i + 15], 10, -30611744);
            c = _lxMd5I(c, d, a, b, x[i + 6], 15, -1560198380);
            b = _lxMd5I(b, c, d, a, x[i + 13], 21, 1309151649);
            a = _lxMd5I(a, b, c, d, x[i + 4], 6, -145523070);
            d = _lxMd5I(d, a, b, c, x[i + 11], 10, -1120210379);
            c = _lxMd5I(c, d, a, b, x[i + 2], 15, 718787259);
            b = _lxMd5I(b, c, d, a, x[i + 9], 21, -343485551);

            a = _lxAdd32(a, oa);
            b = _lxAdd32(b, ob);
            c = _lxAdd32(c, oc);
            d = _lxAdd32(d, od);
          }
          return _lxWordsToBytes([a, b, c, d]);
        }
        function _lxMd5(str) {
          return _lxBytesToHex(_lxMd5Bytes(_lxUtf8ToBytes(String(str))));
        }

        function _lxSha1(str) {
          var bytes = _lxUtf8ToBytes(String(str));
          var words = [];
          for (var i = 0; i < bytes.length; i++) {
            words[i >> 2] |= bytes[i] << (24 - (i % 4) * 8);
          }
          words[bytes.length >> 2] |= 0x80 << (24 - (bytes.length % 4) * 8);
          words[((bytes.length + 8) >> 6) * 16 + 15] = bytes.length * 8;
          var h0 = 0x67452301;
          var h1 = 0xEFCDAB89;
          var h2 = 0x98BADCFE;
          var h3 = 0x10325476;
          var h4 = 0xC3D2E1F0;
          var w = new Array(80);
          for (var i = 0; i < words.length; i += 16) {
            for (var t = 0; t < 16; t++) w[t] = words[i + t] | 0;
            for (t = 16; t < 80; t++) w[t] = _lxRol(w[t - 3] ^ w[t - 8] ^ w[t - 14] ^ w[t - 16], 1);
            var a = h0, b = h1, c = h2, d = h3, e = h4;
            for (t = 0; t < 80; t++) {
              var f, k;
              if (t < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
              else if (t < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
              else if (t < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
              else { f = b ^ c ^ d; k = 0xCA62C1D6; }
              var temp = (_lxRol(a, 5) + f + e + k + w[t]) | 0;
              e = d; d = c; c = _lxRol(b, 30); b = a; a = temp;
            }
            h0 = (h0 + a) | 0;
            h1 = (h1 + b) | 0;
            h2 = (h2 + c) | 0;
            h3 = (h3 + d) | 0;
            h4 = (h4 + e) | 0;
          }
          return _lxWordsToHex([h0, h1, h2, h3, h4]);
        }

        var _lxSha256K = [
          0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,
          0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
          0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,
          0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
          0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,
          0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
          0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,
          0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
          0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,
          0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
          0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,
          0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
          0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,
          0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
          0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
          0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
        ];
        function _lxSha256(str) {
          var bytes = _lxUtf8ToBytes(String(str));
          var words = [];
          for (var i = 0; i < bytes.length; i++) {
            words[i >> 2] |= bytes[i] << (24 - (i % 4) * 8);
          }
          words[bytes.length >> 2] |= 0x80 << (24 - (bytes.length % 4) * 8);
          words[((bytes.length + 8) >> 6) * 16 + 15] = bytes.length * 8;
          var h0 = 0x6a09e667;
          var h1 = 0xbb67ae85;
          var h2 = 0x3c6ef372;
          var h3 = 0xa54ff53a;
          var h4 = 0x510e527f;
          var h5 = 0x9b05688c;
          var h6 = 0x1f83d9ab;
          var h7 = 0x5be0cd19;
          var w = new Array(64);
          for (var i = 0; i < words.length; i += 16) {
            for (var t = 0; t < 16; t++) w[t] = words[i + t] | 0;
            for (t = 16; t < 64; t++) {
              var s0 = _lxRor(w[t - 15], 7) ^ _lxRor(w[t - 15], 18) ^ (w[t - 15] >>> 3);
              var s1 = _lxRor(w[t - 2], 17) ^ _lxRor(w[t - 2], 19) ^ (w[t - 2] >>> 10);
              w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
            }
            var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
            for (t = 0; t < 64; t++) {
              var S1 = _lxRor(e, 6) ^ _lxRor(e, 11) ^ _lxRor(e, 25);
              var ch = (e & f) ^ (~e & g);
              var temp1 = (h + S1 + ch + _lxSha256K[t] + w[t]) | 0;
              var S0 = _lxRor(a, 2) ^ _lxRor(a, 13) ^ _lxRor(a, 22);
              var maj = (a & b) ^ (a & c) ^ (b & c);
              var temp2 = (S0 + maj) | 0;
              h = g; g = f; f = e; e = (d + temp1) | 0;
              d = c; c = b; b = a; a = (temp1 + temp2) | 0;
            }
            h0 = (h0 + a) | 0;
            h1 = (h1 + b) | 0;
            h2 = (h2 + c) | 0;
            h3 = (h3 + d) | 0;
            h4 = (h4 + e) | 0;
            h5 = (h5 + f) | 0;
            h6 = (h6 + g) | 0;
            h7 = (h7 + h) | 0;
          }
          return _lxWordsToHex([h0, h1, h2, h3, h4, h5, h6, h7]);
        }

        function _lxRandomBytes(size) {
          var len = (size && size > 0) ? (size | 0) : 0;
          var out = new Uint8Array(len);
          if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
            crypto.getRandomValues(out);
            return out;
          }
          for (var i = 0; i < len; i++) out[i] = Math.floor(Math.random() * 256);
          return out;
        }

        var _lxAesSbox = [
          0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
          0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
          0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
          0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
          0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
          0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
          0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
          0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
          0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
          0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
          0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
          0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
          0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
          0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
          0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
          0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
        ];
        var _lxAesInvSbox = [
          0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb,
          0x7c,0xe3,0x39,0x82,0x9b,0x2f,0xff,0x87,0x34,0x8e,0x43,0x44,0xc4,0xde,0xe9,0xcb,
          0x54,0x7b,0x94,0x32,0xa6,0xc2,0x23,0x3d,0xee,0x4c,0x95,0x0b,0x42,0xfa,0xc3,0x4e,
          0x08,0x2e,0xa1,0x66,0x28,0xd9,0x24,0xb2,0x76,0x5b,0xa2,0x49,0x6d,0x8b,0xd1,0x25,
          0x72,0xf8,0xf6,0x64,0x86,0x68,0x98,0x16,0xd4,0xa4,0x5c,0xcc,0x5d,0x65,0xb6,0x92,
          0x6c,0x70,0x48,0x50,0xfd,0xed,0xb9,0xda,0x5e,0x15,0x46,0x57,0xa7,0x8d,0x9d,0x84,
          0x90,0xd8,0xab,0x00,0x8c,0xbc,0xd3,0x0a,0xf7,0xe4,0x58,0x05,0xb8,0xb3,0x45,0x06,
          0xd0,0x2c,0x1e,0x8f,0xca,0x3f,0x0f,0x02,0xc1,0xaf,0xbd,0x03,0x01,0x13,0x8a,0x6b,
          0x3a,0x91,0x11,0x41,0x4f,0x67,0xdc,0xea,0x97,0xf2,0xcf,0xce,0xf0,0xb4,0xe6,0x73,
          0x96,0xac,0x74,0x22,0xe7,0xad,0x35,0x85,0xe2,0xf9,0x37,0xe8,0x1c,0x75,0xdf,0x6e,
          0x47,0xf1,0x1a,0x71,0x1d,0x29,0xc5,0x89,0x6f,0xb7,0x62,0x0e,0xaa,0x18,0xbe,0x1b,
          0xfc,0x56,0x3e,0x4b,0xc6,0xd2,0x79,0x20,0x9a,0xdb,0xc0,0xfe,0x78,0xcd,0x5a,0xf4,
          0x1f,0xdd,0xa8,0x33,0x88,0x07,0xc7,0x31,0xb1,0x12,0x10,0x59,0x27,0x80,0xec,0x5f,
          0x60,0x51,0x7f,0xa9,0x19,0xb5,0x4a,0x0d,0x2d,0xe5,0x7a,0x9f,0x93,0xc9,0x9c,0xef,
          0xa0,0xe0,0x3b,0x4d,0xae,0x2a,0xf5,0xb0,0xc8,0xeb,0xbb,0x3c,0x83,0x53,0x99,0x61,
          0x17,0x2b,0x04,0x7e,0xba,0x77,0xd6,0x26,0xe1,0x69,0x14,0x63,0x55,0x21,0x0c,0x7d
        ];
        var _lxAesRcon = [0x00,0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36,0x6c,0xd8,0xab,0x4d,0x9a];

        function _lxGfMul(a, b) {
          var p = 0;
          for (var counter = 0; counter < 8; counter++) {
            if (b & 1) p ^= a;
            var hi = a & 0x80;
            a = (a << 1) & 0xff;
            if (hi) a ^= 0x1b;
            b >>= 1;
          }
          return p;
        }
        function _lxAesSubBytes(state) {
          for (var i = 0; i < 16; i++) state[i] = _lxAesSbox[state[i]];
        }
        function _lxAesInvSubBytes(state) {
          for (var i = 0; i < 16; i++) state[i] = _lxAesInvSbox[state[i]];
        }
        function _lxAesShiftRows(state) {
          var t = state.slice(0);
          state[1]  = t[5];  state[5]  = t[9];  state[9]  = t[13]; state[13] = t[1];
          state[2]  = t[10]; state[6]  = t[14]; state[10] = t[2];  state[14] = t[6];
          state[3]  = t[15]; state[7]  = t[3];  state[11] = t[7];  state[15] = t[11];
        }
        function _lxAesInvShiftRows(state) {
          var t = state.slice(0);
          state[1]  = t[13]; state[5]  = t[1];  state[9]  = t[5];  state[13] = t[9];
          state[2]  = t[10]; state[6]  = t[14]; state[10] = t[2];  state[14] = t[6];
          state[3]  = t[7];  state[7]  = t[11]; state[11] = t[15]; state[15] = t[3];
        }
        function _lxAesMixColumns(state) {
          for (var c = 0; c < 4; c++) {
            var i = c * 4;
            var a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3];
            state[i]     = _lxGfMul(a0, 2) ^ _lxGfMul(a1, 3) ^ a2 ^ a3;
            state[i + 1] = a0 ^ _lxGfMul(a1, 2) ^ _lxGfMul(a2, 3) ^ a3;
            state[i + 2] = a0 ^ a1 ^ _lxGfMul(a2, 2) ^ _lxGfMul(a3, 3);
            state[i + 3] = _lxGfMul(a0, 3) ^ a1 ^ a2 ^ _lxGfMul(a3, 2);
          }
        }
        function _lxAesInvMixColumns(state) {
          for (var c = 0; c < 4; c++) {
            var i = c * 4;
            var a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3];
            state[i]     = _lxGfMul(a0, 14) ^ _lxGfMul(a1, 11) ^ _lxGfMul(a2, 13) ^ _lxGfMul(a3, 9);
            state[i + 1] = _lxGfMul(a0, 9)  ^ _lxGfMul(a1, 14) ^ _lxGfMul(a2, 11) ^ _lxGfMul(a3, 13);
            state[i + 2] = _lxGfMul(a0, 13) ^ _lxGfMul(a1, 9)  ^ _lxGfMul(a2, 14) ^ _lxGfMul(a3, 11);
            state[i + 3] = _lxGfMul(a0, 11) ^ _lxGfMul(a1, 13) ^ _lxGfMul(a2, 9)  ^ _lxGfMul(a3, 14);
          }
        }
        function _lxAesExpandKey(keyBytes) {
          var Nk = keyBytes.length / 4;
          var Nr = Nk + 6;
          var w = new Uint8Array(16 * (Nr + 1));
          w.set(keyBytes);
          var temp = new Uint8Array(4);
          for (var i = Nk; i < 4 * (Nr + 1); i++) {
            temp[0] = w[(i - 1) * 4];
            temp[1] = w[(i - 1) * 4 + 1];
            temp[2] = w[(i - 1) * 4 + 2];
            temp[3] = w[(i - 1) * 4 + 3];
            if (i % Nk === 0) {
              var t = temp[0];
              temp[0] = temp[1];
              temp[1] = temp[2];
              temp[2] = temp[3];
              temp[3] = t;
              temp[0] = _lxAesSbox[temp[0]];
              temp[1] = _lxAesSbox[temp[1]];
              temp[2] = _lxAesSbox[temp[2]];
              temp[3] = _lxAesSbox[temp[3]];
              temp[0] ^= _lxAesRcon[i / Nk];
            } else if (Nk > 6 && i % Nk === 4) {
              temp[0] = _lxAesSbox[temp[0]];
              temp[1] = _lxAesSbox[temp[1]];
              temp[2] = _lxAesSbox[temp[2]];
              temp[3] = _lxAesSbox[temp[3]];
            }
            w[i * 4]     = w[(i - Nk) * 4]     ^ temp[0];
            w[i * 4 + 1] = w[(i - Nk) * 4 + 1] ^ temp[1];
            w[i * 4 + 2] = w[(i - Nk) * 4 + 2] ^ temp[2];
            w[i * 4 + 3] = w[(i - Nk) * 4 + 3] ^ temp[3];
          }
          return { key: w, rounds: Nr };
        }
        function _lxAesAddRoundKey(state, w, round) {
          var offset = round * 16;
          for (var i = 0; i < 16; i++) state[i] ^= w[offset + i];
        }
        function _lxAesEncryptBlock(block, schedule) {
          var state = new Uint8Array(block);
          _lxAesAddRoundKey(state, schedule.key, 0);
          for (var round = 1; round < schedule.rounds; round++) {
            _lxAesSubBytes(state);
            _lxAesShiftRows(state);
            _lxAesMixColumns(state);
            _lxAesAddRoundKey(state, schedule.key, round);
          }
          _lxAesSubBytes(state);
          _lxAesShiftRows(state);
          _lxAesAddRoundKey(state, schedule.key, schedule.rounds);
          return state;
        }
        function _lxAesDecryptBlock(block, schedule) {
          var state = new Uint8Array(block);
          _lxAesAddRoundKey(state, schedule.key, schedule.rounds);
          for (var round = schedule.rounds - 1; round > 0; round--) {
            _lxAesInvShiftRows(state);
            _lxAesInvSubBytes(state);
            _lxAesAddRoundKey(state, schedule.key, round);
            _lxAesInvMixColumns(state);
          }
          _lxAesInvShiftRows(state);
          _lxAesInvSubBytes(state);
          _lxAesAddRoundKey(state, schedule.key, 0);
          return state;
        }
        function _lxNormalizeAesKey(bytes) {
          if (bytes.length === 16 || bytes.length === 24 || bytes.length === 32) return bytes;
          if (bytes.length < 16) {
            var out16 = new Uint8Array(16);
            out16.set(bytes, 0);
            return out16;
          }
          if (bytes.length < 24) {
            var out24 = new Uint8Array(24);
            out24.set(bytes, 0);
            return out24;
          }
          if (bytes.length < 32) {
            var out32 = new Uint8Array(32);
            out32.set(bytes, 0);
            return out32;
          }
          return bytes.slice(0, 32);
        }
        function _lxNormalizeAesIv(bytes) {
          if (bytes.length === 16) return bytes;
          if (bytes.length < 16) {
            var out16 = new Uint8Array(16);
            out16.set(bytes, 0);
            return out16;
          }
          return bytes.slice(0, 16);
        }
        function _lxNormalizeAesMode(mode) {
          var m = String(mode || 'cbc').toLowerCase();
          if (m.indexOf('ecb') >= 0) return 'ecb';
          if (m.indexOf('cfb') >= 0) return 'cfb';
          if (m.indexOf('ctr') >= 0) return 'ctr';
          if (m.indexOf('ofb') >= 0) return 'ofb';
          return 'cbc';
        }
        function _lxAesEncrypt(data, mode, key, iv) {
          var bytes = _lxInputToBytes(data);
          var keyBytes = _lxNormalizeAesKey(_lxInputToBytes(key));
          var ivBytes = _lxNormalizeAesIv(_lxInputToBytes(iv));
          var m = _lxNormalizeAesMode(mode);
          var schedule = _lxAesExpandKey(keyBytes);
          var input = (m === 'ecb' || m === 'cbc') ? _lxPkcs7Pad(bytes) : bytes;
          var out = new Uint8Array(input.length);
          var prev = ivBytes;
          for (var i = 0; i < input.length; i += 16) {
            var block = input.slice(i, i + 16);
            var result;
            if (m === 'ecb') {
              result = _lxAesEncryptBlock(block, schedule);
            } else if (m === 'cbc') {
              for (var j = 0; j < 16; j++) block[j] ^= prev[j];
              result = _lxAesEncryptBlock(block, schedule);
              prev = result;
            } else if (m === 'ctr') {
              var ks = _lxAesEncryptBlock(prev, schedule);
              result = new Uint8Array(block.length);
              for (var j = 0; j < block.length; j++) result[j] = block[j] ^ ks[j];
              for (var k = 15; k >= 0; k--) { prev[k] = (prev[k] + 1) & 0xff; if (prev[k] !== 0) break; }
            } else if (m === 'cfb') {
              var ks2 = _lxAesEncryptBlock(prev, schedule);
              result = new Uint8Array(block.length);
              for (var j = 0; j < block.length; j++) result[j] = block[j] ^ ks2[j];
              prev = result;
            } else if (m === 'ofb') {
              prev = _lxAesEncryptBlock(prev, schedule);
              result = new Uint8Array(block.length);
              for (var j = 0; j < block.length; j++) result[j] = block[j] ^ prev[j];
            } else {
              result = _lxAesEncryptBlock(block, schedule);
            }
            out.set(result, i);
          }
          return out;
        }
        function _lxAesDecrypt(data, mode, key, iv) {
          var bytes = _lxInputToBytes(data);
          var keyBytes = _lxNormalizeAesKey(_lxInputToBytes(key));
          var ivBytes = _lxNormalizeAesIv(_lxInputToBytes(iv));
          var m = _lxNormalizeAesMode(mode);
          var schedule = _lxAesExpandKey(keyBytes);
          var out = new Uint8Array(bytes.length);
          var prev = ivBytes;
          for (var i = 0; i < bytes.length; i += 16) {
            var block = bytes.slice(i, i + 16);
            var result;
            if (m === 'ecb') {
              result = _lxAesDecryptBlock(block, schedule);
            } else if (m === 'cbc') {
              var dec = _lxAesDecryptBlock(block, schedule);
              for (var j = 0; j < dec.length; j++) dec[j] ^= prev[j];
              result = dec;
              prev = block;
            } else if (m === 'ctr') {
              var ks = _lxAesEncryptBlock(prev, schedule);
              result = new Uint8Array(block.length);
              for (var j = 0; j < block.length; j++) result[j] = block[j] ^ ks[j];
              for (var k = 15; k >= 0; k--) { prev[k] = (prev[k] + 1) & 0xff; if (prev[k] !== 0) break; }
            } else if (m === 'cfb') {
              var ks2 = _lxAesEncryptBlock(prev, schedule);
              result = new Uint8Array(block.length);
              for (var j = 0; j < block.length; j++) result[j] = block[j] ^ ks2[j];
              prev = block;
            } else if (m === 'ofb') {
              prev = _lxAesEncryptBlock(prev, schedule);
              result = new Uint8Array(block.length);
              for (var j = 0; j < block.length; j++) result[j] = block[j] ^ prev[j];
            } else {
              result = _lxAesDecryptBlock(block, schedule);
            }
            out.set(result, i);
          }
          if (m === 'ecb' || m === 'cbc') return _lxPkcs7Unpad(out);
          return out;
        }

        function _lxPemToBytes(pem) {
          var text = String(pem || '');
          var b64 = text.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
          if (!b64) return new Uint8Array();
          return _lxBase64ToBytes(b64);
        }
        function _lxAsn1Read(bytes, offset) {
          var i = offset || 0;
          var tag = bytes[i++];
          var len = bytes[i++];
          if (len & 0x80) {
            var n = len & 0x7f;
            len = 0;
            for (var j = 0; j < n; j++) {
              len = (len << 8) | bytes[i++];
            }
          }
          var start = i;
          var end = i + len;
          return { tag: tag, start: start, end: end };
        }
        function _lxAsn1ReadSeq(bytes, offset) {
          var node = _lxAsn1Read(bytes, offset);
          if (node.tag !== 0x30) throw new Error('invalid asn1');
          return node;
        }
        function _lxBytesToBigInt(bytes) {
          var i = 0;
          while (i < bytes.length && bytes[i] === 0) i++;
          var hex = '';
          for (; i < bytes.length; i++) {
            var h = bytes[i].toString(16);
            if (h.length < 2) h = '0' + h;
            hex += h;
          }
          if (!hex) return 0n;
          return BigInt('0x' + hex);
        }
        function _lxBigIntToBytes(bi, size) {
          var hex = bi.toString(16);
          if (hex.length % 2) hex = '0' + hex;
          var bytes = _lxHexToBytes(hex);
          if (size != null) {
            if (bytes.length > size) bytes = bytes.slice(bytes.length - size);
            if (bytes.length < size) {
              var out = new Uint8Array(size);
              out.set(bytes, size - bytes.length);
              return out;
            }
          }
          return bytes;
        }
        function _lxModPow(base, exp, mod) {
          var result = 1n;
          var b = base % mod;
          var e = exp;
          while (e > 0) {
            if (e & 1n) result = (result * b) % mod;
            e >>= 1n;
            b = (b * b) % mod;
          }
          return result;
        }
        function _lxParseRsaPublicKeyFromSeq(bytes) {
          var seq = _lxAsn1ReadSeq(bytes, 0);
          var off = seq.start;
          var nNode = _lxAsn1Read(bytes, off);
          if (nNode.tag !== 0x02) throw new Error('invalid rsa key');
          var n = _lxBytesToBigInt(bytes.slice(nNode.start, nNode.end));
          off = nNode.end;
          var eNode = _lxAsn1Read(bytes, off);
          if (eNode.tag !== 0x02) throw new Error('invalid rsa key');
          var e = _lxBytesToBigInt(bytes.slice(eNode.start, eNode.end));
          return { n: n, e: e };
        }
        function _lxParseRsaPublicKey(pem) {
          var bytes = _lxPemToBytes(pem);
          if (!bytes.length) throw new Error('invalid rsa key');
          var seq = _lxAsn1ReadSeq(bytes, 0);
          var off = seq.start;
          var first = _lxAsn1Read(bytes, off);
          if (first.tag === 0x30) {
            var second = _lxAsn1Read(bytes, first.end);
            if (second.tag === 0x03) {
              var bitBytes = bytes.slice(second.start + 1, second.end);
              return _lxParseRsaPublicKeyFromSeq(bitBytes);
            }
          }
          return _lxParseRsaPublicKeyFromSeq(bytes.slice(0, seq.end));
        }
        function _lxParseRsaPrivateKeyFromPkcs1(bytes) {
          var seq = _lxAsn1ReadSeq(bytes, 0);
          var off = seq.start;
          var vNode = _lxAsn1Read(bytes, off);
          if (vNode.tag !== 0x02) throw new Error('invalid rsa key');
          off = vNode.end;
          var nNode = _lxAsn1Read(bytes, off); off = nNode.end;
          var eNode = _lxAsn1Read(bytes, off); off = eNode.end;
          var dNode = _lxAsn1Read(bytes, off);
          var n = _lxBytesToBigInt(bytes.slice(nNode.start, nNode.end));
          var e = _lxBytesToBigInt(bytes.slice(eNode.start, eNode.end));
          var d = _lxBytesToBigInt(bytes.slice(dNode.start, dNode.end));
          return { n: n, e: e, d: d };
        }
        function _lxParseRsaPrivateKey(pem) {
          var bytes = _lxPemToBytes(pem);
          if (!bytes.length) throw new Error('invalid rsa key');
          var seq = _lxAsn1ReadSeq(bytes, 0);
          var off = seq.start;
          var first = _lxAsn1Read(bytes, off);
          if (first.tag === 0x02) {
            return _lxParseRsaPrivateKeyFromPkcs1(bytes);
          }
          if (first.tag === 0x30) {
            var second = _lxAsn1Read(bytes, first.end);
            if (second.tag === 0x04) {
              var pkcs1 = bytes.slice(second.start, second.end);
              return _lxParseRsaPrivateKeyFromPkcs1(pkcs1);
            }
          }
          var ver = first;
          if (ver.tag === 0x02) {
            off = ver.end;
            var alg = _lxAsn1Read(bytes, off); off = alg.end;
            var pk = _lxAsn1Read(bytes, off);
            if (pk.tag === 0x04) return _lxParseRsaPrivateKeyFromPkcs1(bytes.slice(pk.start, pk.end));
          }
          throw new Error('invalid rsa key');
        }
        function _lxRsaEncrypt(data, keyPem) {
          var key = _lxParseRsaPublicKey(keyPem);
          var bytes = _lxInputToBytes(data);
          var nHex = key.n.toString(16);
          var k = Math.ceil(nHex.length / 2);
          if (bytes.length < k) {
            var padded = new Uint8Array(k);
            padded.set(bytes, k - bytes.length);
            bytes = padded;
          } else if (bytes.length > k) {
            bytes = bytes.slice(bytes.length - k);
          }
          var m = _lxBytesToBigInt(bytes);
          var c = _lxModPow(m, key.e, key.n);
          return _lxBigIntToBytes(c, k);
        }
        function _lxRsaDecrypt(data, keyPem) {
          var key = _lxParseRsaPrivateKey(keyPem);
          var bytes = _lxInputToBytes(data);
          var nHex = key.n.toString(16);
          var k = Math.ceil(nHex.length / 2);
          if (bytes.length < k) {
            var padded = new Uint8Array(k);
            padded.set(bytes, k - bytes.length);
            bytes = padded;
          } else if (bytes.length > k) {
            bytes = bytes.slice(bytes.length - k);
          }
          var c = _lxBytesToBigInt(bytes);
          var m = _lxModPow(c, key.d, key.n);
          return _lxBigIntToBytes(m, k);
        }

        window.__lxCrypto = {
          md5: function(s){ return _lxMd5(String(s)); },
          sha1: function(s){ return _lxSha1(String(s)); },
          sha256: function(s){ return _lxSha256(String(s)); },
          randomBytes: function(n){ return _lxRandomBytes(n|0); },
          aesEncrypt: function(d,m,k,iv){ return _lxAesEncrypt(d,m,k,iv); },
          aesDecrypt: function(d,m,k,iv){ return _lxAesDecrypt(d,m,k,iv); },
          rsaEncrypt: function(d,k){ return _lxRsaEncrypt(d,k); },
          rsaDecrypt: function(d,k){ return _lxRsaDecrypt(d,k); },
          base64Encode: function(i){ return _lxBytesToBase64(_lxInputToBytes(i)); },
          base64Decode: function(s){ return _lxBase64ToBytes(s); },
          zlibInflate: function(buf){
            return _lxCallHandler('LxCrypto',{op:'zlibInflate',data:_lxBytesToBase64(_lxInputToBytes(buf))})
              .then(function(b64){ return _lxBase64ToBytes(b64).buffer; });
          },
          zlibDeflate: function(buf){
            return _lxCallHandler('LxCrypto',{op:'zlibDeflate',data:_lxBytesToBase64(_lxInputToBytes(buf))})
              .then(function(b64){ return _lxBase64ToBytes(b64).buffer; });
          }
        };
      })();
      window.__makeLx=function(sid,meta){
        var handlers={}; var sources={}; var self={};
        var requestScope=null, requestCap='';
        Object.defineProperty(self,'__bindRequest',{value:function(scope,cap){requestScope=scope;requestCap=cap;}});
        Object.defineProperty(self,'__ownsRequest',{value:function(scope){return requestScope===scope;}});
        self.version='1.0.0'; self.env='mobile'; self.platform='android';
        self.EVENT_NAMES={inited:'inited', request:'request', updateAlert:'updateAlert'};
        self.currentScriptInfo={};
        try{
          if(typeof meta==='string'){ self.currentScriptInfo=JSON.parse(meta)||{}; }
          else if(meta && typeof meta==='object'){ self.currentScriptInfo=meta; }
        }catch(_){ }
        self.appInfo={
          version: (typeof window.__swAppVersion==='string')?window.__swAppVersion:'',
          versionNum: (typeof window.__swAppVersionNum==='number')?window.__swAppVersionNum:0,
          locale: (typeof window.__swAppLocale==='string')?window.__swAppLocale:'zh-cn'
        };
        self.on=function(name, fn){ handlers[name]=fn; };
        self.send=function(name, data){
          if(name==='inited' && data){
            sources = data.sources || {};
            var e=window.__swScripts[sid]; if(e){ e.sources=sources; e.inited=true; e.handlers=handlers; }
          }
        };
        self.request=function(url, options, cb){
          var scope=requestScope, parentCap=requestCap;
          if(!scope || scope.closed) throw new Error('request owner closed');
          options=options||{};
          var requestUrl=String(url);
          var query=options.query!=null?options.query:(options.params!=null?options.params:null);
          if(query!=null){
            try{
              var qs=typeof query==='string'?query:Object.keys(query).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(query[k]==null?'':query[k]);}).join('&');
              if(qs) requestUrl+=(requestUrl.indexOf('?')>=0?'&':'?')+qs;
            }catch(_){ }
          }
          var method=(options.method||'GET').toUpperCase();
          var headers={};
          try{
            var oh=options.headers||{};
            Object.keys(oh).forEach(function(k){ headers[k]=oh[k]; });
          }catch(_){ headers=options.headers||{}; }
          var body=null;
          var isForm=false;
          if(options.body!=null){
            body=options.body;
          }else if(options.form!=null){
            body=options.form;
            isForm=true;
          }
          if(body!=null && typeof body!=='string'){
            if(isForm){
              // 洛雪 needle：form 对象按 x-www-form-urlencoded，不能 JSON.stringify。
              try{
                body=Object.keys(body).map(function(k){
                  return encodeURIComponent(k)+'='+encodeURIComponent(body[k]==null?'':body[k]);
                }).join('&');
              }catch(_){ body=String(body); }
              var hasCt=Object.keys(headers).some(function(k){return String(k).toLowerCase()==='content-type';});
              if(!hasCt) headers['Content-Type']='application/x-www-form-urlencoded';
            }else{
              try{ body=JSON.stringify(body); }catch(_){ body=String(body); }
              var hasCt2=Object.keys(headers).some(function(k){return String(k).toLowerCase()==='content-type';});
              if(!hasCt2) headers['Content-Type']='application/json';
            }
          }
          var requestedTimeout=Number(options.timeout);
          var ms=typeof options.timeout==='number' && isFinite(requestedTimeout)
            ? Math.max(250,Math.min(15000,requestedTimeout)) : 10000;
          var cid='sw_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2)+'_'+(window.__swFetchSeq++);
          var settled=false;
          var promiseResolve, promiseReject;
          // LX reference runtimes support both callback and Promise request styles.
          // Keep the callback ABI while returning a real thenable response for scripts
          // that use `await request(...)` or `request(...).then(...)`.
          var responsePromise=new Promise(function(resolve,reject){ promiseResolve=resolve; promiseReject=reject; });
          var timer=setTimeout(function(){
            if(settled) return; settled=true; delete __swCbMap[cid]; delete __swResponseHeaders[cid]; delete scope.children[cid];
            try{ window.__swNativeCancel && window.__swNativeCancel(cid); }catch(e){}
            var err=new Error('timeout');
            try{ cb && cb(err); }catch(e){}
            try{ promiseReject(err); }catch(e){}
          }, ms);
          // 回调注册进 boot 闭包私有表；另一脚本无法枚举/覆盖，伪造 nativeFetch 也命中不了。
          var cbFn=function(code, text){
            if(settled) return; settled=true; clearTimeout(timer); delete __swCbMap[cid];
            try{
              if(code>0 && code<400){
                var bodyOut=text;
                if(typeof text==='string'){
                  var t=text.trim();
                  if((t.charAt(0)==='{'&&t.charAt(t.length-1)==='}')||(t.charAt(0)==='['&&t.charAt(t.length-1)===']')){
                    try{ bodyOut=JSON.parse(text); }catch(_){}
                  }
                }
                // 兼容两种 LX 请求协议：新版 Promise/response.body 读取第二个参数，
                // 旧版 request(url, opts, (err, resp, body)) 从第三个参数读取正文。
                // 之前只传两个参数，旧版导入脚本会把 body 当成 undefined，随后统一返回空 URL。
                var responseHeaders={};
                try{
                  var rh={};
                  try{ rh=JSON.parse(__swResponseHeaders[cid]||'{}'); }catch(_){}
                  Object.keys(rh).forEach(function(k){responseHeaders[k]=rh[k];});
                }catch(_){}
                var statusCode=code||200;
                var statusMessage=(function(c){
                  var m={200:'OK',201:'Created',202:'Accepted',204:'No Content',206:'Partial Content',
                    301:'Moved Permanently',302:'Found',303:'See Other',307:'Temporary Redirect',308:'Permanent Redirect',
                    400:'Bad Request',401:'Unauthorized',403:'Forbidden',404:'Not Found',408:'Request Timeout',
                    429:'Too Many Requests',500:'Internal Server Error',502:'Bad Gateway',503:'Service Unavailable'};
                  return m[c]||'';
                })(statusCode);
                var responseObj={ body: bodyOut, statusCode: statusCode, status: statusCode, statusMessage: statusMessage,
                  ok: statusCode>=200 && statusCode<300, headers: responseHeaders };
                cb && cb(null, responseObj, bodyOut);
                promiseResolve(responseObj);
                delete __swResponseHeaders[cid];
              } else {
                var err=new Error('http '+code);
                cb && cb(err);
                promiseReject(err);
              }
            }catch(e){ try{ cb && cb(e); }catch(_){}; try{ promiseReject(e); }catch(_){} }
            finally{delete __swResponseHeaders[cid];}
          };
          var abortFn=function(){
            if(settled) return;
            settled=true; clearTimeout(timer); delete __swCbMap[cid]; delete __swResponseHeaders[cid]; delete scope.children[cid];
            try{ window.__swNativeCancel && window.__swNativeCancel(cid); }catch(e){}
            try{ promiseReject(new Error('aborted')); }catch(e){}
          };
          var requestHandle={ abort: abortFn, cancel: abortFn, then: responsePromise.then.bind(responsePromise),
            catch: responsePromise.catch.bind(responsePromise), finally: responsePromise.finally.bind(responsePromise) };
          // Keep cancellation methods while also supporting the Promise request contract.
          scope.children[cid]=abortFn;
          var originalCb=cbFn;
          __swCbMap[cid]=function(code,text){delete scope.children[cid];originalCb(code,text);};
          try{
            window.__swNativeFetch(
              String(requestUrl), method,
              JSON.stringify(headers||{}),
              body==null? null : String(body),
              cid, parentCap
            );
          }catch(e){
            if(settled) return requestHandle; settled=true; clearTimeout(timer);
            delete __swCbMap[cid]; delete __swResponseHeaders[cid];
            try{ cb && cb(e); }catch(_){}
            try{ promiseReject(e); }catch(_){}
          }
          return requestHandle;
        };
        self.utils={
          buffer:{
            from: function(s, enc){
              s=String(s||'');
              var arr=[];
              if(enc==='base64'){
                try{
                  var bin=atob(s);
                  for(var i=0;i<bin.length;i++) arr.push(bin.charCodeAt(i)&0xff);
                  return arr;
                }catch(e){ return []; }
              }
              for(var i=0;i<s.length;i++){
                var c=s.charCodeAt(i);
                if(c<128) arr.push(c);
                else if(c<2048){ arr.push(192|(c>>6), 128|(c&63)); }
                else { arr.push(224|(c>>12), 128|((c>>6)&63), 128|(c&63)); }
              }
              return arr;
            },
            bufToString: function(buf, enc){
              if(!buf) return '';
              if(enc==='base64'){
                var s='';
                for(var i=0;i<buf.length;i++) s+=String.fromCharCode(buf[i]&0xff);
                try{ return btoa(s); }catch(e){ return ''; }
              }
              if(enc==='hex'){
                var hex='';
                for(var j=0;j<buf.length;j++){
                  var h=(buf[j]&0xff).toString(16);
                  hex+=h.length===1?'0'+h:h;
                }
                return hex;
              }
              var out='',i=0;
              while(i<buf.length){
                var c=buf[i]&0xff;
                if(c<128){ out+=String.fromCharCode(c); i++; }
                else if(c>=192&&c<224){ out+=String.fromCharCode(((c&31)<<6)|(buf[i+1]&63)); i+=2; }
                else { out+=String.fromCharCode(((c&15)<<12)|((buf[i+1]&63)<<6)|(buf[i+2]&63)); i+=3; }
              }
              return out;
            }
          },
          crypto:{
            md5: function(s){ return window.__lxCrypto?window.__lxCrypto.md5(s):window.__md5(s); },
            sha1: function(s){ return window.__lxCrypto?window.__lxCrypto.sha1(s):''; },
            sha256: function(s){ return window.__lxCrypto?window.__lxCrypto.sha256(s):''; },
            randomBytes: function(n){ return window.__lxCrypto?window.__lxCrypto.randomBytes(n):[]; },
            aesEncrypt: function(d,m,k,iv){ return window.__lxCrypto?window.__lxCrypto.aesEncrypt(d,m,k,iv):null; },
            aesDecrypt: function(d,m,k,iv){ return window.__lxCrypto?window.__lxCrypto.aesDecrypt(d,m,k,iv):null; },
            rsaEncrypt: function(d,k){ return window.__lxCrypto?window.__lxCrypto.rsaEncrypt(d,k):null; },
            rsaDecrypt: function(d,k){ return window.__lxCrypto?window.__lxCrypto.rsaDecrypt(d,k):null; },
            base64Encode: function(i){ return window.__lxCrypto?window.__lxCrypto.base64Encode(i):''; },
            base64Decode: function(s){ return window.__lxCrypto?window.__lxCrypto.base64Decode(s):[]; }
          },
          zlib:{
            inflate: function(buf){ return window.__lxCrypto?window.__lxCrypto.zlibInflate(buf):Promise.reject(new Error('no zlib')); },
            deflate: function(buf){ return window.__lxCrypto?window.__lxCrypto.zlibDeflate(buf):Promise.reject(new Error('no zlib')); }
          }
        };
        Object.defineProperty(self,'_handlers',{ get:function(){ return handlers; }});
        return self;
      };
      window.__ensureScript=function(sid, code, metaJson, cap){
        var entry = window.__swScripts[sid];
        if(entry && entry.handlers){ window.__swBindRequest(sid,cap); return entry; }
        entry={ handlers:null, sources:{}, inited:false, lx:null };
        window.__swScripts[sid]=entry;
        var lx=window.__makeLx(sid, metaJson);
        entry.lx=lx; entry.handlers=lx._handlers || {};
        window.__swBindRequest(sid,cap);
        try{
          var fakeGlobal={ lx:lx, console:console, setTimeout:setTimeout, clearTimeout:clearTimeout,
            JSON:JSON, Math:Math, Date:Date, encodeURIComponent:encodeURIComponent, decodeURIComponent:decodeURIComponent,
            Promise:Promise, parseInt:parseInt, parseFloat:parseFloat, isNaN:isNaN, unescape:unescape,
            Number:Number, String:String, Boolean:Boolean, Array:Array, Object:Object, Map:Map, Set:Set, Error:Error,
            URL:URL, URLSearchParams:URLSearchParams,
            atob:atob, btoa:btoa };
          fakeGlobal.globalThis=fakeGlobal; fakeGlobal.window=fakeGlobal;
          // 严格模式：顶层 this=undefined，切断「非严格模式 this→真实 window」逃逸通道
          //（否则脚本顶层 this 可触达 WebView 全局 fetch/XHR 出网，绕过 fakeGlobal 白名单）。
          // 注意：正式函数体仍可经 (0, eval)('this') 逃逸（V8 级限制，Android 无 node:vm 等价物），
          // 此处为最佳努力收窄；完整隔离待可隔离 JS 引擎方案。
          var runner=new Function('globalThis','lx','console', '"use strict";\n' + code);
          runner.call(undefined, fakeGlobal, lx, console);
          // 若脚本同步 send inited
          if(entry.inited) entry.handlers = lx._handlers || entry.handlers;
        }catch(e){ console && console.warn && console.warn('script init', sid, e); }
        return entry;
      };
      // 全部定义完成后，统一钉死共享全局（顺序不可反：先定义后冻结，否则赋值静默失败）。
      // 坏脚本 window.__swScripts=null / delete / 替换内核函数 / 替换 NativeBridge 全部静默失败。
      try{ Object.defineProperty(window,'__swRoot',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swScripts',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swFetchCbs',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__md5',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__makeLx',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__ensureScript',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swInvalidate',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swDispatch',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swBindRequest',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swCancelRequest',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swNativeCancel',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swBridge',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swNativeFetch',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swOnUrl',{writable:false,configurable:false}); }catch(e){}
      try{ Object.defineProperty(window,'__swOnScriptError',{writable:false,configurable:false}); }catch(e){}
      })();
    """.trimIndent()

    private fun evalJs(js: String) {
        main.post {
            try {
                webView?.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    inner class Bridge {
        // 仅 @JavascriptInterface 方法暴露给脚本；新增桥方法必须显式注解
        @JavascriptInterface
        fun onUrlResult(capability: String?, url: String?) {
            if (!capability.isNullOrBlank()) {
                // ACCEPT4：只有经此桥的回传才是 handler 真正 resolve（负缓存准入标记）。
                handlerResolvedCaps.add(capability)
                completeAndRemove(capability, url)
            }
        }

        /**
         * H1-a：脚本 handler 自报错误（Promise reject 的 message）。
         * 只做清理后暂存供分类，永不落盘、永不回传脚本层。
         */
        @JavascriptInterface
        fun onScriptError(capability: String?, message: String?) {
            val cap = capability?.takeIf { it.isNotBlank() } ?: return
            val cleaned = ScriptTerminalErrorClassifier.sanitize(message)
            if (cleaned.isNotEmpty()) explicitErrorByCapability[cap] = cleaned
        }

        @JavascriptInterface
        fun handlerStart(capability: String?) {
            capability?.takeIf { it.isNotBlank() }?.let { synchronousHandlers.add(it) }
        }

        @JavascriptInterface
        fun handlerReturned(capability: String?) {
            capability?.let { synchronousHandlers.remove(it) }
        }

        @JavascriptInterface
        fun nativeCancel(callbackId: String?) {
            val id = callbackId?.trim().orEmpty()
            if (id.isNotEmpty()) fetchScheduler.cancel(fetchOwner, id)
        }

        /** utils.zlib.inflate/deflate：JS 侧无 zlib 实现，委托 Kotlin（java.util.zip）异步回传 base64。 */
        @JavascriptInterface
        fun lxCrypto(op: String?, b64: String?, callbackId: String?) {
            val capability = callbackId?.takeIf { it.isNotBlank() } ?: return
            Thread {
                var code = 500
                var text = ""
                try {
                    val raw = try {
                        android.util.Base64.decode(b64 ?: "", android.util.Base64.DEFAULT)
                    } catch (_: Exception) { null }
                    when (op?.lowercase()) {
                        "zlibinflate" -> {
                            if (raw == null) { text = "bad-b64" } else {
                                val out = java.io.ByteArrayOutputStream()
                                val inf = java.util.zip.Inflater()
                                inf.setInput(raw)
                                val buf = ByteArray(8192)
                                while (!inf.finished()) {
                                    val n = inf.inflate(buf)
                                    if (n > 0) out.write(buf, 0, n) else break
                                }
                                inf.end()
                                text = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                                code = 200
                            }
                        }
                        "zlibdeflate" -> {
                            if (raw == null) { text = "bad-b64" } else {
                                val df = java.util.zip.Deflater()
                                df.setInput(raw); df.finish()
                                val out = java.io.ByteArrayOutputStream()
                                val buf = ByteArray(8192)
                                while (!df.finished()) {
                                    val n = df.deflate(buf)
                                    if (n > 0) out.write(buf, 0, n) else break
                                }
                                df.end()
                                text = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                                code = 200
                            }
                        }
                        else -> text = "unknown-op"
                    }
                } catch (_: Exception) {
                    text = "zlib-error"
                }
                val safe = JSONObject.quote(text)
                val js = "try{window.__swDispatch(${JSONObject.quote(capability)},$code,$safe,'{}');}catch(e){}"
                main.post {
                    try { webView?.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            }.start()
        }

        @JavascriptInterface
        fun nativeFetch(url: String, method: String, headersJson: String?, body: String?, callbackId: String?, requestCapability: String?) {
            if (!ownerCurrent()) return
            val capability = callbackId?.takeIf { it.isNotBlank() } ?: return
            val parent = requestCapability?.takeIf { it.isNotBlank() } ?: return
            synchronized(requestLock) {
            val parentRequest = pendingUrl[parent] ?: return
            if (fetchParents.putIfAbsent(capability, parent) != null) return
            val priority = when (parentRequest.purpose) {
                ResolveTicket.Purpose.PLAY -> ScriptRequestScheduler.Priority.PLAY
                ResolveTicket.Purpose.DOWNLOAD -> ScriptRequestScheduler.Priority.DOWNLOAD
                ResolveTicket.Purpose.PREFETCH, ResolveTicket.Purpose.PROBE -> ScriptRequestScheduler.Priority.PREFETCH
            }
            fetchScheduler.submit(
                owner = fetchOwner,
                cid = capability,
                priority = priority,
                onRejected = {
                    lastFetchFailure[parent] = "queue_full"
                    capToScript[parent]?.let { lastFailureByScript[it] = "queue_full" }
                    forgetFetch(capability)
                    dispatchFetchResult(capability, 429, "request queue full")
                },
                onCancelled = {
                    activeCalls.remove(capability)?.let { cancelCallAsync(it) }
                    responseHeadersByCapability.remove(capability)
                    lastFetchFailure.remove(capability)
                    forgetFetch(capability)
                },
                onFailure = { _ ->
                    activeCalls.remove(capability)?.let { cancelCallAsync(it) }
                    responseHeadersByCapability.remove(capability)
                    lastFetchFailure.remove(capability)
                    val failureCompletion: () -> Unit = {
                        forgetFetch(capability)
                        dispatchFetchResult(capability, 500, "request failed")
                    }
                    failureCompletion
                }
            ) { control ->
                val result = doFetch(capability, url, method, headersJson, body, control)
                if (control.isCancelled()) {
                    responseHeadersByCapability.remove(capability)
                    lastFetchFailure.remove(capability)
                    val cancelledCompletion: () -> Unit = {}
                    cancelledCompletion
                } else {
                    val headersJsonOut = responseHeadersByCapability.remove(capability).orEmpty()
                    val failure = lastFetchFailure.remove(capability).orEmpty()
                    if (failure.isNotEmpty() && pendingUrl.containsKey(parent)) {
                        lastFetchFailure[parent] = failure
                        capToScript[parent]?.let { lastFailureByScript[it] = failure }
                    }
                    val completion: () -> Unit = {
                        fetchParents.remove(capability)
                        dispatchFetchResult(capability, result.code, result.body, headersJsonOut)
                    }
                    completion
                }
            }
            }
        }

        private fun dispatchFetchResult(capability: String, code: Int, text: String, headersJson: String = "{}") {
            val js = ScriptFetchCodec.callback(capability, code, text, headersJson)
            main.post {
                try { webView?.evaluateJavascript(js, null) } catch (_: Exception) { }
            }
        }

        private fun doFetch(
            capability: String,
            url: String,
            method: String,
            headersJson: String?,
            body: String?,
            control: ScriptRequestScheduler.RequestControl,
        ): ScriptFetchResult = fetchTransport.fetch(capability, url, method, headersJson, body, control)

    }

    /**
     * SW160 轻量探测：用脚本取链检测原平台是否可播。
     * 返回 "ok"(有) / "cross"(无/失败)。
     */
    suspend fun probe(script: Script, song: Song): String {
        if (script.code.isEmpty()) return "cross"
        return try {
            val url = getMusicUrl(
                script.id,
                script.code,
                song.toJson(),
                song.source.ifEmpty { "wy" },
                "128k",
                purpose = ResolveTicket.Purpose.PROBE,
                scriptMeta = scriptMetaJson(script)
            )
            if (url.isNotEmpty()) "ok" else "cross"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { "cross" }
    }
}

/** 洛雪 currentScriptInfo 元信息；v3/v4 脚本读取 name/version 用于签名、配置校验与版本比对。 */
fun scriptMetaJson(script: Script): String = JSONObject()
    .put("name", script.name)
    .put("version", script.version)
    .put("description", script.description)
    .put("author", script.author)
    .toString()

object DownloadScriptRuntimeHolder {
    private val instances = java.util.concurrent.ConcurrentHashMap<String, ScriptRuntime>()
    fun get(context: Context,
            owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): ScriptRuntime {
        check(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner)) { "Inactive/expired runtime owner" }
        val key = "${xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner)}:${owner.generation}"
        return instances.getOrPut(key) { ScriptRuntime(context.applicationContext, owner) }
    }
}

/**
 * 进程级共享 ScriptRuntime（对齐老版本 / 洛雪：WebView 常驻、脚本只初始化一次）。
 * 播放与 Service 恢复共用；下载使用 DownloadScriptRuntimeHolder 隔离。
 * 常驻实例由进程生命周期持有，不随 Activity/Worker 销毁。
 */
object ScriptRuntimeHolder {
    private val instances = java.util.concurrent.ConcurrentHashMap<String, ScriptRuntime>()
    fun get(context: Context,
            owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): ScriptRuntime {
        check(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner)) { "Inactive/expired runtime owner" }
        val key = "${xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner)}:${owner.generation}"
        return instances.getOrPut(key) { ScriptRuntime(context.applicationContext, owner) }
    }
}
