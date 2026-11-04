package xyz.cncnlp.soundwave

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Isolate legacy blocking provider entry points from the caller's deadline.
 * Eight active workers in total, partitioned by purpose: abandoned background
 * work cannot borrow foreground capacity. Both active and waiting work are bounded.
 * A cancelled non-cooperative worker retains its permit until it really exits.
 * New HTTP code should use cancellation-owned transports rather than interruption.
 */
internal object ResolveBlockingStage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class PurposeContext(val purpose: ResolveTicket.Purpose) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PurposeContext>
    }

    fun context(purpose: ResolveTicket.Purpose): CoroutineContext = PurposeContext(purpose)

    private class Lane(active: Int, queued: Int) {
        val slots = Semaphore(active)
        val admission = Semaphore(active + queued)
    }

    private val play = Lane(4, 8)
    private val download = Lane(2, 4)
    private val prefetch = Lane(1, 2)
    private val probe = Lane(1, 2)

    suspend fun <T> run(block: suspend () -> T): T {
        val purpose = currentCoroutineContext()[PurposeContext]?.purpose ?: ResolveTicket.Purpose.PLAY
        val lane = when (purpose) {
            ResolveTicket.Purpose.PLAY -> play
            ResolveTicket.Purpose.DOWNLOAD -> download
            ResolveTicket.Purpose.PREFETCH -> prefetch
            ResolveTicket.Purpose.PROBE -> probe
        }
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            if (!lane.admission.tryAcquire()) {
                continuation.resumeWithException(java.util.concurrent.RejectedExecutionException("Resolve stage capacity exhausted: $purpose"))
                return@suspendCancellableCoroutine
            }
            val job = scope.launch(context(purpose)) {
                lane.slots.withPermit {
                    try {
                        val value = block()
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
            // Includes cancellation before dispatch/while waiting. Never release
            // admission merely because the caller stopped waiting for a worker.
            job.invokeOnCompletion { lane.admission.release() }
            continuation.invokeOnCancellation { job.cancel() }
        }
    }

    /**
     * ACCEPT6-FREEZE（崩溃取证 2026-11-04 19:15）：OkHttp 的 Call.cancel() 会在调用方
     * 线程同步关闭 SSLSocket（Conscrypt close 需 flush TLS 收尾写）。取消的发起方可以
     * 是主线程（点播顶替在 Main 上 cancel 旧解析 job），invokeOnCancellation 的 handler
     * 由发起线程同步执行——直接 call.cancel() 即主线程网络关停，StrictMode 以
     * NetworkOnMainThreadException 杀进程（异常被压入 Cancelling 协程上下文的
     * Suppressed，与真机帧一致）。关连接一律投递到本 stage 的 IO scope 执行。
     */
    internal fun cancelCallAsync(call: okhttp3.Call) {
        scope.launch { runCatching { call.cancel() } }
    }
}

/** Execute AND consume a response while retaining cancellation ownership. */
internal suspend fun <T> resolveCall(call: okhttp3.Call, consume: (okhttp3.Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        // 取消 handler 由取消发起线程同步执行（可为主线程）：关连接必须异步投递，
        // 见 ResolveBlockingStage.cancelCallAsync 的取证注释。
        continuation.invokeOnCancellation { ResolveBlockingStage.cancelCallAsync(call) }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val value = response.use(consume)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
