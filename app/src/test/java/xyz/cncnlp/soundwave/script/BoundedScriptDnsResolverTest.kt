package xyz.cncnlp.soundwave.script

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BoundedScriptDnsResolverTest {
    private fun executor() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))

    @Test fun blockedFakeDnsReturnsWithinBoundAndReleasesSchedulerCaller() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = executor()
        try {
            val resolver = BoundedScriptDnsResolver(pool, { started.countDown(); release.await(); arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }, 80L)
            val start = System.nanoTime()
            assertTrue(resolver.resolve("fixture.invalid", { false }).isEmpty())
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("bounded DNS should return near the configured timeout: ${elapsedMs}ms", elapsedMs < 700L)
            assertTrue(started.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun cancellationIsPolledWhileFakeDnsIsBlocked() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val pool = executor()
        try {
            val resolver = BoundedScriptDnsResolver(pool, { started.countDown(); release.await(); emptyArray() }, 5_000L)
            val caller = Thread {
                resolver.resolve("fixture.invalid", { cancelled.get() })
            }
            caller.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            cancelled.set(true)
            caller.join(700L)
            assertTrue("DNS waiter must stop after cancellation", !caller.isAlive)
        } finally {
            cancelled.set(true)
            release.countDown()
            pool.shutdownNow()
        }
    }
}
