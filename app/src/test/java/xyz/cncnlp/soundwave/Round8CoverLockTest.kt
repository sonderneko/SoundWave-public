package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class Round8CoverLockTest {
    private fun await(latch: CountDownLatch) = assertTrue("latch timeout", latch.await(5, TimeUnit.SECONDS))
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("condition timeout", condition())
    }
    @Test fun legacyExactRegistrySemanticsRetainAllUniqueCompletedKeys() {
        // Exact original two-line acquisition and monitor semantics, no Android/network stubs.
        val locks = ConcurrentHashMap<String, Any>()
        repeat(10000) { i ->
            val lock = locks.computeIfAbsent("path-$i") { Any() }
            synchronized(lock) { /* a cache hit also takes this path */ }
        }
        assertEquals(10000, locks.size)
    }
    @Test fun completedUniqueKeysAreReclaimed() {
        val locks = BlockingKeyedOperationLock<String>()
        repeat(10000) { i -> assertEquals(i, locks.withKey("path-$i") { i }) }
        assertEquals(0, locks.trackedKeysForTests())
    }
    @Test fun sameKeyWaitersAndNewArrivalNeverSplitTheLock() {
        val locks = BlockingKeyedOperationLock<String>()
        val pool = Executors.newFixedThreadPool(4)
        val holderEntered=CountDownLatch(1);val releaseHolder=CountDownLatch(1)
        val inside=AtomicInteger();val peak=AtomicInteger();val calls=AtomicInteger()
        fun critical() { val n=inside.incrementAndGet();peak.accumulateAndGet(n, ::maxOf);Thread.sleep(1);calls.incrementAndGet();inside.decrementAndGet() }
        try {
            val holder=pool.submit { locks.withKey("same") { holderEntered.countDown();await(releaseHolder);critical() } }
            await(holderEntered)
            val waiters=(1..2).map { pool.submit { locks.withKey("same") { critical() } } }
            until { locks.referencesForTests("same")==3 }
            releaseHolder.countDown()
            val arriving=pool.submit { repeat(100) { locks.withKey("same") { critical() } } }
            holder.get(10,TimeUnit.SECONDS);waiters.forEach{it.get(10,TimeUnit.SECONDS)};arriving.get(10,TimeUnit.SECONDS)
            assertEquals(1,peak.get());assertEquals(103,calls.get());assertEquals(0,locks.trackedKeysForTests())
        } finally {releaseHolder.countDown();pool.shutdownNow()}
    }
    @Test fun differentKeysDoNotHoldTheGlobalRegistryDuringOperation() {
        val locks=BlockingKeyedOperationLock<String>();val pool=Executors.newFixedThreadPool(2)
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        try {
            val jobs=listOf("a","b").map { key -> pool.submit{locks.withKey(key){entered.countDown();await(release)}} }
            await(entered);assertEquals(2,locks.trackedKeysForTests());release.countDown();jobs.forEach{it.get(5,TimeUnit.SECONDS)}
            assertEquals(0,locks.trackedKeysForTests())
        }finally{release.countDown();pool.shutdownNow()}
    }
    @Test fun exceptionAndReentrantCallsReleaseAllReferences() {
        val locks=BlockingKeyedOperationLock<String>()
        try { locks.withKey("x") { locks.withKey("x"){assertEquals(2,locks.referencesForTests("x"));throw IllegalStateException("expected")} };fail() }
        catch(_:IllegalStateException){}
        assertEquals(0,locks.trackedKeysForTests());assertEquals(9,locks.withKey("x"){9})
    }
    @Test fun interruptionInsideOperationReclaimsThenAllowsNextCaller() {
        val locks=BlockingKeyedOperationLock<String>();val entered=CountDownLatch(1);val interrupted=CountDownLatch(1)
        val worker=Thread{try{locks.withKey("x"){entered.countDown();Thread.sleep(10000)}}catch(_:InterruptedException){interrupted.countDown()}}
        worker.start();await(entered);worker.interrupt();await(interrupted);worker.join(5000)
        assertFalse(worker.isAlive);assertEquals(0,locks.trackedKeysForTests());locks.withKey("x"){};assertEquals(0,locks.trackedKeysForTests())
    }
    @Test fun cleanupUsesSameKeyAndCannotOverlapInstall() {
        val locks=BlockingKeyedOperationLock<String>();val pool=Executors.newFixedThreadPool(2)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val cleanup=CountDownLatch(1)
        try {
            val install=pool.submit{locks.withKey("file"){entered.countDown();await(release)}};await(entered)
            val clean=pool.submit{locks.withKey("file"){cleanup.countDown()}}
            until{locks.referencesForTests("file")==2};assertEquals(1L,cleanup.count)
            release.countDown();install.get(5,TimeUnit.SECONDS);clean.get(5,TimeUnit.SECONDS);assertEquals(0L,cleanup.count);assertEquals(0,locks.trackedKeysForTests())
        }finally{release.countDown();pool.shutdownNow()}
    }
}
