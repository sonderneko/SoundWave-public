package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Concurrency contracts for the process-wide WebDAV transaction gate; transports are all in-memory. */
class WebDavOperationGateTest {
    private val verifiedGate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
    private val credentials = WebDavSavedCredentials("https://fake.invalid/dav/SoundWave", "test-user", "test-password")
    private fun success() = WebDavSyncResult.Success(LocalStore.UserBundle(), remoteFileCreated = false)
    private fun await(latch: CountDownLatch) = check(latch.await(3, TimeUnit.SECONDS)) { "Timed out waiting for test latch" }

    @Test
    fun twoManualSyncClientsExcludeTheEntireRequestSequence() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEnteredTransport = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val transportA = WebDavTransport { request ->
            requests += "a" to request.method
            firstEntered.countDown()
            await(releaseFirst)
            responseFor(request)
        }
        val transportB = WebDavTransport { request ->
            requests += "b" to request.method
            secondEnteredTransport.countDown()
            responseFor(request)
        }
        fun invoke(client: WebDavSyncClient) = client.sync(
            credentials.url, "user", "password", LocalStore.UserBundle(), verifiedGate
        )
        try {
            val first = pool.submit<WebDavSyncResult> { invoke(WebDavSyncClient(transportA)) }
            await(firstEntered)
            val second = pool.submit<WebDavSyncResult> {
                secondCallStarted.countDown()
                invoke(WebDavSyncClient(transportB))
            }
            await(secondCallStarted)
            assertFalse("second client's transport must not enter while the first transaction is held",
                secondEnteredTransport.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(first.get(3, TimeUnit.SECONDS) is WebDavSyncResult.Success)
            assertTrue(second.get(3, TimeUnit.SECONDS) is WebDavSyncResult.Success)
            assertEquals("whole transactions, not individual requests, must be contiguous",
                listOf("a", "a", "a", "b", "b", "b"), requests.map { it.first })
            assertEquals(listOf("PROPFIND", "GET", "PUT"), requests.filter { it.first == "a" }.map { it.second })
            assertEquals(listOf("PROPFIND", "GET", "PUT"), requests.filter { it.first == "b" }.map { it.second })
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transactionHoldsSlotFromSnapshotThroughLocalApplyAndNextReadsLatestValue() = runTest {
        val gate = WebDavOperationGate()
        val firstLease = gate.capture()
        val secondLease = gate.capture()
        val httpFinished = CompletableDeferred<Unit>()
        val allowFirstApply = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var localValue = "initial"
        val first = async {
            gate.transaction(firstLease) {
                val snapshot = localValue
                events += "first snapshot:$snapshot"
                httpFinished.complete(Unit)
                allowFirstApply.await()
                localValue = "first-applied"
                events += "first apply:$localValue"
            }
        }
        runCurrent()
        httpFinished.await()

        var secondSnapshot: String? = null
        val second = async {
            gate.transaction(secondLease) {
                events += "second snapshot:$localValue"
                secondSnapshot = localValue
            }
        }
        runCurrent()
        assertEquals(listOf("first snapshot:initial"), events)
        assertEquals("second transaction must not snapshot before first local apply", null, secondSnapshot)

        allowFirstApply.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first snapshot:initial", "first apply:first-applied", "second snapshot:first-applied"), events)
        assertEquals("first-applied", secondSnapshot)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledQueuedTransactionPerformsNoWork() = runTest {
        val gate = WebDavOperationGate()
        val firstLease = gate.capture()
        val queuedLease = gate.capture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var queuedWork = 0
        val first = async {
            gate.transaction(firstLease) { entered.complete(Unit); release.await() }
        }
        runCurrent()
        entered.await()
        val queued = async {
            gate.transaction(queuedLease) { queuedWork++ }
        }
        runCurrent()
        assertEquals("queued transaction has not entered its action", 0, queuedWork)
        queued.cancel()
        runCurrent()
        assertEquals("cancellation while waiting must perform no work", 0, queuedWork)
        release.complete(Unit)
        first.await()
        queued.join()
        assertTrue(queued.isCancelled)
        assertEquals(0, queuedWork)
    }

    @Test
    fun captureAdmissionStaysClosedUntilEveryTransitionHandleClosesAndOldLeaseStaysInvalid() {
        val gate = WebDavOperationGate()
        val oldLease = gate.capture()
        val closeFirst = gate.beginIdentityTransition()
        val closeSecond = gate.beginIdentityTransition()
        fun assertCaptureRejected() {
            try {
                gate.capture()
                throw AssertionError("capture admission must remain closed during identity transition")
            } catch (_: CancellationException) { }
        }
        assertCaptureRejected()
        closeFirst()
        assertCaptureRejected()
        closeSecond()
        gate.capture().ensureActive()
        try {
            oldLease.ensureActive()
            throw AssertionError("the pre-transition lease must stay invalid after admission reopens")
        } catch (_: CancellationException) { }
    }

    @Test
    fun savedSessionAndFutureRunUseTheSameProcessWideGate() = runBlocking {
        val enteredSession = CountDownLatch(1)
        val releaseSession = CountDownLatch(1)
        val enteredFuture = CountDownLatch(1)
        val session = WebDavSavedSession(MemoryVault(WebDavSavedCredentialState.Ready(credentials))) { _, _, _ ->
            enteredSession.countDown()
            await(releaseSession)
            success()
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val manual = pool.submit<WebDavSyncResult> { session.syncWithSaved() }
            await(enteredSession)
            val future = async(Dispatchers.IO) {
                WebDavOperations.run { enteredFuture.countDown(); "future" }
            }
            assertFalse("future operation must wait behind saved-session transaction",
                enteredFuture.await(150, TimeUnit.MILLISECONDS))
            releaseSession.countDown()
            assertTrue(manual.get(3, TimeUnit.SECONDS) is WebDavSyncResult.Success)
            assertEquals("future", future.await())
            assertEquals(0L, enteredFuture.count)
        } finally {
            releaseSession.countDown()
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    @Test
    fun queuedCancellationRunsTokenHookAndSendsNoRequests() = runBlocking {
        val queue = ConcurrentLinkedQueue<Runnable>()
        val gate = WebDavOperationGate { queue.add(it) }
        val lease = gate.capture()
        val hookCalled = AtomicBoolean(false)
        lease.onCancel { hookCalled.set(true) }
        val requests = AtomicInteger()
        val waiting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            gate.run(lease) { requests.incrementAndGet() }
        }
        waiting.cancel()
        assertTrue(hookCalled.get())
        assertTrue("dispatch should be queued until explicitly run", queue.isNotEmpty())
        queue.remove().run()
        waiting.join()
        assertTrue(waiting.isCancelled)
        assertEquals(0, requests.get())
    }

    @Test
    fun cancelledUncooperativeTransportRetainsSlotUntilItActuallyExits() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstLease = WebDavOperations.capture()
        try {
            val first = async(Dispatchers.IO) {
                WebDavOperations.run(firstLease) {
                    firstEntered.countDown()
                    // Deliberately ignore interruption/cancellation until the test releases us.
                    while (true) {
                        try { releaseFirst.await(); break } catch (_: InterruptedException) { }
                    }
                    "late"
                }
            }
            await(firstEntered)
            first.cancel()
            val second = async(Dispatchers.IO) { WebDavOperations.run { secondEntered.countDown(); "next" } }
            assertFalse("a cancelled caller must not release an occupied transport slot",
                secondEntered.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertEquals("next", second.await())
            assertTrue(secondEntered.count == 0L)
        } finally {
            releaseFirst.countDown()
            WebDavOperations.invalidate()
        }
    }

    @Test
    fun accountSwitchRejectsLateSuccessfulResultBeforeCredentialsCanBeSaved() {
        val runnerEntered = CountDownLatch(1)
        val releaseRunner = CountDownLatch(1)
        val saves = AtomicInteger()
        val vault = MemoryVault(WebDavSavedCredentialState.None, onSave = { saves.incrementAndGet(); true })
        val session = WebDavSavedSession(vault) { _, _, _ ->
            runnerEntered.countDown()
            // Simulate a transport that returns success despite the account switch.
            while (true) {
                try { releaseRunner.await(); break } catch (_: InterruptedException) { }
            }
            success()
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val work = pool.submit { session.loginAndSync(credentials.url, "user", "password") }
            await(runnerEntered)
            WebDavOperations.invalidate()
            releaseRunner.countDown()
            try {
                work.get(3, TimeUnit.SECONDS)
                throw AssertionError("expired operation must not return a successful login-and-sync")
            } catch (failure: ExecutionException) {
                assertTrue(failure.cause is CancellationException)
            }
            assertEquals("late credentials must not be persisted", 0, saves.get())
        } finally {
            releaseRunner.countDown()
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
            WebDavOperations.invalidate()
        }
    }

    @Test
    fun accountEpochCommitRejectsLateLocalApplyAfterSuccessfulWorkerReturn() = runBlocking {
        val lease = WebDavOperations.capture()
        val result = WebDavOperations.run(lease) { success() }
        assertTrue(result is WebDavSyncResult.Success)
        WebDavOperations.invalidate()
        val applies = AtomicInteger()
        try {
            lease.commit { applies.incrementAndGet() }
            throw AssertionError("an expired account epoch must reject local result application")
        } catch (_: CancellationException) {
            // Expected: the remote worker returned, but its account epoch no longer owns local state.
        }
        assertEquals(0, applies.get())
    }

    private class MemoryVault(
        private val initial: WebDavSavedCredentialState,
        private val onSave: () -> Boolean = { true }
    ) : WebDavCredentialVault {
        override fun read(): WebDavSavedCredentialState = initial
        override fun save(url: String, username: String, password: String): Boolean = onSave()
        override fun clear(): Boolean = true
    }

    private fun responseFor(request: WebDavHttpRequest): WebDavHttpResponse = when (request.method) {
        "PROPFIND" -> WebDavHttpResponse(207)
        "GET" -> WebDavHttpResponse(404)
        "PUT" -> WebDavHttpResponse(201)
        else -> WebDavHttpResponse(500)
    }
}
