package xyz.cncnlp.soundwave.migration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProfileSessionCoordinatorTest {
    @Test fun rotationRejectsLateSameOwnerCommit() {
        val sessions = ProfileSessionCoordinator(40L, requireRotationIo = {})
        val first = sessions.capture()
        val next = sessions.rotateLegacy()
        assertFalse(sessions.isCurrent(first))
        assertTrue(sessions.isCurrent(next))
        var writes = 0
        assertNull(sessions.commit(first) { writes++; true })
        assertEquals(0, writes)
        assertEquals(true, sessions.commit(next) { writes++; true })
        assertEquals(1, writes)
    }
    @Test fun immutableOwnerNeverChangesAcrossRotation() {
        val sessions = ProfileSessionCoordinator(9L, requireRotationIo = {})
        val first = sessions.capture()
        sessions.rotateLegacy()
        assertEquals(9L, first.generation)
        assertEquals(ProfileOwner.LegacyUnassigned, first.owner)
    }
    @Test fun inactiveIdentityCannotAuthorizeRuntimeOrRemote() {
        val sessions = ProfileSessionCoordinator(20L, requireRotationIo = {})
        val inactive = ProfileContext.recorded(xyz.cncnlp.soundwave.ProfileId.of("test-profile"), 20L)
        assertFalse(sessions.isCurrent(inactive))
        assertFalse(sessions.remoteReady(inactive))
        assertFalse(sessions.remoteReady(sessions.capture()))
    }
    @Test fun exhaustFailsClosedInsteadOfReusingGeneration() {
        val sessions = ProfileSessionCoordinator(Long.MAX_VALUE, requireRotationIo = {})
        assertTrue(runCatching { sessions.rotateLegacy() }.isFailure)
        assertFalse(sessions.isCurrent(sessions.capture()))
    }

    @Test fun queuedRotationRejectsNewButAllowsNestedCommitInAdmittedAction() {
        val rotationIoEntered = CountDownLatch(1)
        val outerEntered = CountDownLatch(1)
        val releaseOuter = CountDownLatch(1)
        val sessions = ProfileSessionCoordinator(73L, requireRotationIo = { rotationIoEntered.countDown() })
        val first = sessions.capture()
        val writes = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outer = pool.submit<Boolean?> {
                sessions.commit(first) {
                    outerEntered.countDown()
                    check(releaseOuter.await(5, TimeUnit.SECONDS))
                    sessions.commit(first) { writes.incrementAndGet(); true }
                }
            }
            assertTrue(outerEntered.await(5, TimeUnit.SECONDS))
            val rotation = pool.submit<ProfileContext> { sessions.rotateLegacy() }
            assertTrue(rotationIoEntered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!sessions.hasPendingRotation() && System.nanoTime() < deadline) Thread.yield()
            assertTrue("rotation should be queued before testing nested work", sessions.hasPendingRotation())

            assertNull("new transactions must fail fast while rotation is queued", sessions.commit(first) {
                writes.incrementAndGet()
                true
            })
            releaseOuter.countDown()

            assertEquals(true, outer.get(5, TimeUnit.SECONDS))
            val next = rotation.get(5, TimeUnit.SECONDS)
            assertEquals(1, writes.get())
            assertFalse(sessions.isCurrent(first))
            assertTrue(sessions.isCurrent(next))
        } finally {
            releaseOuter.countDown()
            pool.shutdownNow()
        }
    }
}
