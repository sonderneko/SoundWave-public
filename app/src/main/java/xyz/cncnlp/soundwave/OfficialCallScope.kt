package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/** Explicit per-operation ownership, never a thread-local or shared dispatcher cancellation. */
@OptIn(InternalCoroutinesApi::class)
internal class OfficialCallScope(private val job: Job) : AutoCloseable {
    private val lock = Any()
    private val calls = LinkedHashSet<Call>()
    private var closed = false
    // Completion-only handlers run too late for execute()/body reads. Observe the
    // cancelling transition, before a structured parent waits for its IO child.
    private val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) cancel()
    }

    private fun cancel() {
        val outstanding = synchronized(lock) {
            closed = true
            val snapshot = calls.toList()
            calls.clear()
            snapshot
        }
        // ACCEPT6-MGCLOSE（崩溃取证 2026-11-05 02:24，mg 直连路径）：上方的
        // invokeOnCompletion(onCancelling) handler 由取消发起线程同步执行，而 job 树的
        // 取消发起方常在主线程（搜索切词/切页、解析顶替）——在此内联关连接会让
        // OkHttp 在主线程同步关闭 SSLSocket（TLS 收尾写），即
        // NetworkOnMainThreadException（ACCEPT6-FREEZE 同族，当时只覆盖播放路径）。
        // 关连接一律投递 ResolveBlockingStage 的 IO scope，本 handler 只做簿记。
        outstanding.forEach { ResolveBlockingStage.cancelCallAsync(it) }
    }

    fun ensureActive() = synchronized(lock) {
        if (closed || !job.isActive) throw jobCancellation()
    }

    fun <T> execute(call: Call, consume: (Response) -> T): T {
        synchronized(lock) {
            if (closed || !job.isActive) {
                // 未执行过的 call 也走同一异步关停口径：本类永不内联关连接（见 cancel() 注）。
                ResolveBlockingStage.cancelCallAsync(call)
                throw jobCancellation()
            }
            calls.add(call)
        }
        try {
            val value = call.execute().use(consume)
            // Completion vs cancellation has one gate; don't deliver a stale
            // result or translate a cancelled IOException into fallback.
            synchronized(lock) {
                if (closed || !job.isActive) throw jobCancellation()
                calls.remove(call)
            }
            return value
        } catch (error: Exception) {
            synchronized(lock) {
                if (closed || !job.isActive) throw jobCancellation()
            }
            throw error
        } finally {
            synchronized(lock) { calls.remove(call) }
        }
    }

    private fun jobCancellation() = CancellationException("Official transport operation cancelled")

    override fun close() {
        cancel()
        cancellation.dispose()
    }
}

/** Works across dispatcher hops because ownership is passed explicitly. */
internal suspend fun <T> withOfficialCallScope(block: suspend (OfficialCallScope) -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val scope = OfficialCallScope(requireNotNull(context[Job]))
    return try {
        val result = block(scope)
        currentCoroutineContext().ensureActive()
        result
    } finally {
        scope.close()
    }
}

/** Keep synchronous consumers supported, with execute AND body read owned until use closes. */
internal suspend fun <T> officialPlatformCall(call: Call, consume: (Response) -> T): T =
    withContext(Dispatchers.IO) {
        withOfficialCallScope { scope -> scope.execute(call, consume) }
    }
