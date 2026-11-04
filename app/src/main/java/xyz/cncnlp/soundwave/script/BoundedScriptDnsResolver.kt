package xyz.cncnlp.soundwave.script

import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** DNS may ignore interruption, so it must never consume ScriptRequestScheduler workers. */
internal class BoundedScriptDnsResolver(
    private val executor: ThreadPoolExecutor,
    private val lookup: (String) -> Array<InetAddress>,
    private val timeoutMillis: Long = 1_500L
) {
    init { require(timeoutMillis > 0L) }

    fun resolve(host: String, isCancelled: () -> Boolean): Array<InetAddress> {
        if (isCancelled()) return emptyArray()
        val task = try {
            executor.submit(Callable { lookup(host) })
        } catch (_: RejectedExecutionException) {
            return emptyArray()
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            if (isCancelled()) {
                cancel(task)
                return emptyArray()
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                cancel(task)
                return emptyArray()
            }
            try {
                return task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(40L)), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Poll cancellation while the platform resolver is blocked.
            } catch (_: ExecutionException) {
                return emptyArray()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancel(task)
                return emptyArray()
            }
        }
    }

    private fun cancel(task: Future<*>) {
        task.cancel(true)
        executor.remove(task as? Runnable ?: return)
    }

    companion object {
        private fun newExecutor(): ThreadPoolExecutor {
            val sequence = AtomicInteger()
            val factory = ThreadFactory { runnable ->
                Thread(runnable, "soundwave-script-dns-${sequence.incrementAndGet()}").apply { isDaemon = true }
            }
            return ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(16), factory, ThreadPoolExecutor.AbortPolicy()
            )
        }

        val processWide = BoundedScriptDnsResolver(newExecutor(), InetAddress::getAllByName)
    }
}
