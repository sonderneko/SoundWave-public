package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.sync.WebDavAutoSync
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncBinding
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncOutcome
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncState
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncStore

class WebDavAutoSyncTest {
    private class MemoryStore(initial: WebDavAutoSyncState? = null) : WebDavAutoSyncStore {
        var value: WebDavAutoSyncState? = initial
        var failWrites = false
        override fun read(): WebDavAutoSyncState? = value
        override fun writeAtomically(state: WebDavAutoSyncState): Boolean {
            if (failWrites) return false
            value = state
            return true
        }
    }

    private fun binding(account: String = "Account-A", endpoint: String = " https://dav.example.test/dav/// ", user: String = "user") =
        WebDavAutoSyncBinding.bind(account, endpoint, user)

    private fun ready(store: MemoryStore, current: () -> WebDavAutoSyncBinding?, runner: suspend (WebDavAutoSyncBinding) -> WebDavAutoSyncOutcome = { WebDavAutoSyncOutcome.Success }, debounce: Long = 10L, backoff: List<Long> = emptyList(), scope: kotlinx.coroutines.CoroutineScope) =
        WebDavAutoSync(scope, store, current, runner, debounceMs = debounce, backoffMs = backoff)

    @Test
    fun defaultIsOffAndEnableRequiresManualSuccess() = runTest {
        val store = MemoryStore()
        val current = binding()
        var runs = 0
        val sync = ready(store, { current }, { runs++; WebDavAutoSyncOutcome.Success }, scope = this)

        assertEquals(WebDavAutoSyncState.DEFAULT, sync.state)
        assertFalse(sync.enable())
        sync.onForeground()
        advanceUntilIdle()
        assertEquals(0, runs)

        assertTrue(sync.manualSucceeded(current))
        assertTrue(sync.state.manualReady)
        assertFalse(sync.state.enabled)
        assertTrue(sync.enable())
        assertTrue(sync.state.enabled)
        assertEquals(true, store.value?.enabled)
    }

    @Test
    fun bindingFactoryNormalizesEndpointAndKeepsIdentityCaseSensitiveWithoutPassword() {
        val normalized = binding()
        assertEquals("Account-A", normalized.accountKey)
        assertEquals("https://dav.example.test/dav", normalized.endpoint)
        assertEquals("user", normalized.username)
        assertFalse(normalized == binding(account = "account-a"))
        assertFalse(normalized == binding(user = "User"))
    }

    @Test
    fun runtimeBindingChangeRevokesAndOldManualSuccessCannotReauthorize() = runTest {
        val store = MemoryStore()
        val first = binding()
        var current: WebDavAutoSyncBinding? = first
        var runs = 0
        val sync = ready(store, { current }, { runs++; WebDavAutoSyncOutcome.Success }, scope = this)
        assertTrue(sync.manualSucceeded(first))
        assertTrue(sync.enable())

        sync.onForeground() // queue an automatic run for the old identity
        current = binding(account = "Account-B")
        sync.onLocalChanged()
        advanceUntilIdle()
        assertEquals(WebDavAutoSyncState.DEFAULT, sync.state)
        assertEquals(WebDavAutoSyncState.DEFAULT, store.value)
        assertEquals(0, runs)
        assertFalse(sync.manualSucceeded(first))
    }

    @Test
    fun foregroundAndLocalChangesInsideDebounceWindowCoalesce() = runTest {
        val store = MemoryStore()
        val current = binding()
        var runs = 0
        val sync = ready(store, { current }, { runs++; WebDavAutoSyncOutcome.Success }, debounce = 10L, scope = this)
        sync.manualSucceeded(current)
        sync.enable()

        sync.onForeground()
        advanceTimeBy(5L)
        sync.onLocalChanged()
        advanceTimeBy(5L)
        sync.onForeground()
        advanceTimeBy(9L)
        runCurrent()
        assertEquals(0, runs)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun logoutAccountEndpointAndUsernameChangesCancelInFlightWorkAndRevoke() = runTest {
        val original = binding()
        val replacements = listOf(null, binding(account = "Account-B"), binding(endpoint = "https://other.invalid/dav"), binding(user = "other-user"))
        for (replacement in replacements) {
            var current: WebDavAutoSyncBinding? = original
            val store = MemoryStore()
            var cancelled = false
            var runs = 0
            val sync = ready(store, { current }, {
                runs++
                try { awaitCancellation() } finally { cancelled = true }
            }, debounce = 0L, scope = this)
            assertTrue(sync.manualSucceeded(original))
            assertTrue(sync.enable())
            sync.onForeground()
            runCurrent()
            assertEquals(1, runs)
            current = replacement
            assertFalse(sync.refreshBinding())
            runCurrent()
            assertTrue(cancelled)
            assertEquals(WebDavAutoSyncState.DEFAULT, sync.state)
            assertEquals(WebDavAutoSyncState.DEFAULT, store.value)
            sync.onForeground()
            sync.onLocalChanged()
            runCurrent()
            assertEquals(1, runs)
        }
    }

    @Test
    fun backgroundLocalChangeWaitsForForegroundResume() = runTest {
        val store = MemoryStore()
        val current = binding()
        var runs = 0
        val sync = ready(store, { current }, { runs++; WebDavAutoSyncOutcome.Success }, debounce = 10L, scope = this)
        sync.manualSucceeded(current)
        sync.enable()

        sync.onLocalChanged()
        advanceUntilIdle()
        assertEquals(0, runs)
        sync.onForeground()
        advanceUntilIdle()
        assertEquals(1, runs)
    }

    @Test
    fun eventsDuringRunnerDoNotCancelItAndDirtySuccessGetsOneDebouncedFollowUp() = runTest {
        val store = MemoryStore()
        val current = binding()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val sync = ready(store, { current }, {
            runs++
            if (runs == 1) {
                entered.complete(Unit)
                release.await()
            }
            WebDavAutoSyncOutcome.Success
        }, debounce = 10L, scope = this)
        sync.manualSucceeded(current)
        sync.enable()

        sync.onForeground()
        advanceTimeBy(10L)
        runCurrent()
        entered.await()
        sync.onLocalChanged()
        sync.onForeground()
        runCurrent()
        assertEquals(1, runs)

        release.complete(Unit)
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(10L)
        runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun eventsDuringBackoffDoNotRestartOrResetTheBoundedRetrySequence() = runTest {
        val store = MemoryStore()
        val current = binding()
        var runs = 0
        val sync = ready(store, { current }, {
            runs++
            WebDavAutoSyncOutcome.RetryableFailure
        }, debounce = 0L, backoff = listOf(10L, 20L), scope = this)
        sync.manualSucceeded(current)
        sync.enable()
        sync.onForeground()
        runCurrent()
        assertEquals(1, runs)

        advanceTimeBy(5L)
        sync.onLocalChanged()
        advanceTimeBy(5L)
        runCurrent()
        assertEquals(2, runs)
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(3, runs)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(3, runs)
    }

    @Test
    fun prePutMarkerIsDurableAndAcknowledgementAllowsSameRunRetry() = runTest {
        val store = MemoryStore()
        val current = binding()
        lateinit var sync: WebDavAutoSync
        var runs = 0
        sync = ready(store, { current }, {
            runs++
            if (runs == 1) {
                assertTrue(sync.remoteWriteStarting())
                assertTrue(sync.state.uncertain)
                assertTrue(store.value?.uncertain == true)
                assertTrue(sync.remoteWriteAcknowledged()) // e.g. a definite 412 response
                assertFalse(sync.state.uncertain)
                WebDavAutoSyncOutcome.RetryableFailure
            } else {
                WebDavAutoSyncOutcome.Success
            }
        }, debounce = 0L, backoff = listOf(10L), scope = this)
        sync.manualSucceeded(current)
        sync.enable()

        sync.onForeground()
        advanceUntilIdle()
        assertEquals(2, runs)
        assertFalse(sync.state.uncertain)
    }

    @Test
    fun ambiguousPutBlocksTriggersAndFailedMarkerPersistenceRefusesPut() = runTest {
        val store = MemoryStore()
        val current = binding()
        lateinit var sync: WebDavAutoSync
        var runs = 0
        sync = ready(store, { current }, {
            runs++
            assertTrue(sync.remoteWriteStarting())
            WebDavAutoSyncOutcome.PausedFailure
        }, debounce = 0L, scope = this)
        sync.manualSucceeded(current)
        sync.enable()
        sync.onForeground()
        runCurrent()
        assertEquals(1, runs)
        assertTrue(sync.state.uncertain)
        sync.onLocalChanged()
        runCurrent()
        assertEquals(1, runs)

        val failedStore = MemoryStore()
        lateinit var another: WebDavAutoSync
        another = ready(failedStore, { current }, {
            failedStore.failWrites = true
            assertFalse(another.remoteWriteStarting())
            WebDavAutoSyncOutcome.PausedFailure
        }, debounce = 0L, scope = this)
        assertTrue(another.manualSucceeded(current))
        assertTrue(another.enable())
        another.onForeground()
        runCurrent()
        assertEquals(WebDavAutoSyncState.DEFAULT, another.state)
    }

    @Test
    fun backgroundCancelsExecutingRunner() = runTest {
        val store = MemoryStore()
        val current = binding()
        var cancelled = false
        lateinit var sync: WebDavAutoSync
        sync = ready(store, { current }, {
            assertTrue(sync.remoteWriteStarting())
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }, debounce = 0L, scope = this)
        sync.manualSucceeded(current)
        sync.enable()
        sync.onForeground()
        runCurrent()
        sync.onBackground()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(sync.state.enabled)
        assertTrue(sync.state.uncertain)
        assertTrue(store.value?.uncertain == true)
    }

    @Test
    fun uncertainIsDurableBlocksEventsAndManualSuccessReconciles() = runTest {
        val store = MemoryStore()
        val current = binding()
        var runs = 0
        val first = ready(store, { current }, { runs++; WebDavAutoSyncOutcome.Uncertain }, debounce = 0L, scope = this)
        first.manualSucceeded(current)
        first.enable()
        first.onForeground()
        runCurrent()
        assertTrue(first.state.uncertain)
        assertTrue(store.value?.uncertain == true)
        assertTrue(first.state.enabled)

        val restored = ready(store, { current }, { runs++; WebDavAutoSyncOutcome.Success }, debounce = 0L, scope = this)
        restored.onForeground()
        runCurrent()
        assertEquals(1, runs)
        assertFalse(restored.enable())

        restored.manualStarted()
        assertTrue(restored.manualSucceeded(current))
        assertFalse(restored.state.uncertain)
        assertTrue(restored.state.enabled)
        restored.onLocalChanged()
        runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun retryableFailuresUseBoundedBackoffThenWaitForAnotherEvent() = runTest {
        val store = MemoryStore()
        val current = binding()
        var runs = 0
        val sync = ready(store, { current }, {
            runs++
            WebDavAutoSyncOutcome.RetryableFailure
        }, debounce = 0L, backoff = listOf(10L, 20L, 30L), scope = this)
        sync.manualSucceeded(current)
        sync.enable()
        sync.onForeground()
        advanceUntilIdle()
        assertEquals(4, runs) // initial attempt plus at most three explicitly safe retries
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(4, runs) // exhaustion is dormant until a new foreground/local event

        sync.onLocalChanged()
        advanceUntilIdle()
        assertEquals(8, runs)
    }

    @Test
    fun failedDurableEnableFailsClosed() = runTest {
        val store = MemoryStore()
        val current = binding()
        val sync = ready(store, { current }, scope = this)
        assertTrue(sync.manualSucceeded(current))
        store.failWrites = true
        assertFalse(sync.enable())
        assertEquals(WebDavAutoSyncState.DEFAULT, sync.state)
        assertFalse(sync.state.enabled)
        assertNull(sync.state.binding)
    }

    @Test
    fun manualAdmissionFailsClosedWhenItsSafetyMarkerCannotPersist() = runTest {
        val store = MemoryStore()
        val current = binding()
        val sync = ready(store, { current }, scope = this)
        assertTrue(sync.manualSucceeded(current))
        assertTrue(sync.enable())
        store.failWrites = true
        assertFalse(sync.manualStarted())
        assertFalse(sync.state.enabled)
        assertFalse(sync.manualStarted())
        store.failWrites = false
        assertTrue(sync.manualStarted())
        assertEquals(WebDavAutoSyncState.DEFAULT, store.value)
    }
}
