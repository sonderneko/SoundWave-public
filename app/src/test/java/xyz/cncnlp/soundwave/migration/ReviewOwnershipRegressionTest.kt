package xyz.cncnlp.soundwave.migration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Authored source regressions; execution intentionally deferred (no compiler/JVM authorization). */
class ReviewOwnershipRegressionTest {
    @Test fun blockedDurableWriterDoesNotBlockIdentityReads() {
        val sessions = ProfileSessionCoordinator(40L, requireRotationIo = {})
        val owner = sessions.capture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val writer = pool.submit<Boolean> { sessions.commit(owner) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
            } == true }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(pool.submit<Boolean> { sessions.commit(owner) { true } == true }.get(1, TimeUnit.SECONDS))
            val rotation = pool.submit<ProfileContext> { sessions.rotateLegacy() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!sessions.hasPendingRotation() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(sessions.hasPendingRotation())
            assertFalse(rotation.isDone)
            // This mutation is AFTER rotation queued, unlike the old regression.
            assertTrue(pool.submit<Boolean> {
                assertTrue(sessions.capture().matches(owner))
                assertTrue(sessions.isCurrent(owner))
                var applied = false
                assertNull(sessions.commit(owner) { applied = true; true })
                !applied
            }.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(writer.get(2, TimeUnit.SECONDS))
            val next = rotation.get(2, TimeUnit.SECONDS)
            assertFalse(sessions.hasPendingRotation())
            assertFalse(sessions.isCurrent(owner))
            assertEquals(true, sessions.commit(next) { true })
            var staleWrite = false
            assertNull(sessions.commit(owner) { staleWrite = true })
            assertFalse(staleWrite)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun recursiveReadToWriteRotationIsRejectedBeforeWaiting() {
        val sessions = ProfileSessionCoordinator(4L, requireRotationIo = {})
        val owner = sessions.capture()
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            assertEquals(true, pool.submit<Boolean> {
                sessions.commit(owner) {
                    assertThrows(IllegalStateException::class.java) { sessions.rotateLegacy() }
                    true
                } == true
            }.get(1, TimeUnit.SECONDS))
            assertTrue(sessions.isCurrent(owner))
            assertFalse(sessions.hasPendingRotation())
        } finally { pool.shutdownNow() }
    }

    @Test fun rotationBoundaryIsCheckedBeforeChangingOrQueuingIdentity() {
        val sessions = ProfileSessionCoordinator(4L, requireRotationIo = { error("not IO") })
        val owner = sessions.capture()
        assertThrows(IllegalStateException::class.java) { sessions.rotateLegacy() }
        assertTrue(sessions.isCurrent(owner))
        assertFalse(sessions.hasPendingRotation())
        assertEquals(true, sessions.commit(owner) { true })
    }
}
