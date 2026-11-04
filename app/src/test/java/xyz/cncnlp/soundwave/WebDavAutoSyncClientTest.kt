package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavAutoSync
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncBinding
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncOutcome
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncState
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncStore
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavOperations
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Integration coverage: real auto-sync state machine + real account-v2 WebDAV client + in-memory transport. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebDavAutoSyncClientTest {
    private val baseUrl = "https://autosync.invalid/dav"
    private val username = "dav-user"
    private val password = "dav-password"
    private val gateState = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }

    private class MemoryStore(initial: WebDavAutoSyncState? = null) : WebDavAutoSyncStore {
        var value: WebDavAutoSyncState? = initial
        override fun read(): WebDavAutoSyncState? = value
        override fun writeAtomically(state: WebDavAutoSyncState): Boolean {
            value = state
            return true
        }
    }

    private data class Recorded(val method: String, val url: String, val body: String? = null)

    private class Remote(
        initial: Map<String, String> = emptyMap(),
        private val responseForPut: (WebDavHttpRequest, Int) -> WebDavHttpResponse = { _, _ -> WebDavHttpResponse(201, etag = "\"v2\"") }
    ) : WebDavTransport {
        val documents = initial.toMutableMap()
        val requests = mutableListOf<Recorded>()
        var puts = 0
            private set
        var gets = 0
            private set

        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
            val body = request.body?.toString(StandardCharsets.UTF_8)
            requests += Recorded(request.method, request.url, body)
            return when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "MKCOL" -> WebDavHttpResponse(405)
                "GET" -> {
                    gets++
                    documents[request.url]?.let { WebDavHttpResponse(200, it, "\"v1\"") }
                        ?: WebDavHttpResponse(404)
                }
                "PUT" -> {
                    puts++
                    responseForPut(request, puts).also { response ->
                        if (response.code in setOf(200, 201, 204)) {
                            documents[request.url] = body.orEmpty()
                        }
                    }
                }
                else -> WebDavHttpResponse(405)
            }
        }
    }

    private fun accountKey() = AccountDataIdentity.key("Exact User")
    private fun binding() = WebDavAutoSyncBinding.bind("account-key", baseUrl, username)
    private fun sidecarUrl(owner: String) =
        "$baseUrl/SoundWave/soundwave-account-v2-${owner.removePrefix("swown2_")}.json"

    private fun bundle(id: String, stats: String = "{}", statsOwner: String? = null) =
        LocalStore.UserBundle(
            favs = mutableListOf(Song(id = id, name = id, singer = "fixture", source = "wy")),
            dailyListenStatsJson = stats,
            dailyListenStatsOwnerV2 = statsOwner
        )

    private fun envelope(owner: String, user: LocalStore.UserBundle): String = JSONObject()
        .put("schema", "soundwave-webdav-sync")
        .put("version", 2)
        .put("owner", owner)
        .put("user", user.toCloudJson(includeAvatar = false))
        .toString()

    private fun client(
        transport: WebDavTransport,
        owner: String,
        beforePut: (() -> Unit)? = null,
        onPutResponse: ((Int) -> Unit)? = null
    ) = WebDavSyncClient(
        transport = transport,
        accountDataKey = owner,
        accountDataCurrent = { true },
        beforeAccountPut = beforePut,
        onAccountPutResponse = onPutResponse
    )

    private suspend fun runClient(client: WebDavSyncClient, local: LocalStore.UserBundle): WebDavSyncResult =
        WebDavOperations.transaction {
            // WebDavSyncClient is synchronous; never execute it on the coroutine/UI test thread.
            withContext(Dispatchers.IO) {
                client.sync(baseUrl, username, password, local, gateState)
            }
        }

    /** Synchronous client hooks hop back to the state machine's single runTest/UI dispatcher. */
    private fun onTestDispatcher(dispatcher: CoroutineDispatcher, action: () -> Unit) {
        runBlocking { withContext(dispatcher) { action() } }
    }

    private fun outcome(result: WebDavSyncResult): WebDavAutoSyncOutcome = when (result) {
        is WebDavSyncResult.Success -> WebDavAutoSyncOutcome.Success
        is WebDavSyncResult.Failure -> when {
            result.remoteStateUncertain -> WebDavAutoSyncOutcome.Uncertain
            result.retryable -> WebDavAutoSyncOutcome.RetryableFailure
            else -> WebDavAutoSyncOutcome.PausedFailure
        }
    }

    @Test
    fun failedDurableAdmissionDoesNotSendPutOrClaimRemoteUncertainty() = runTest {
        val remote = Remote()
        val denied = client(remote, accountKey(), beforePut = {
            throw xyz.cncnlp.soundwave.sync.WebDavPutAuthorizationException()
        })
        val result = runClient(denied, bundle("local"))
        assertTrue(result is WebDavSyncResult.Failure)
        result as WebDavSyncResult.Failure
        assertFalse(result.remoteStateUncertain)
        assertFalse(result.retryable)
        assertEquals(0, remote.puts)
    }

    @Test
    fun firstManualEnableAndForegroundGateRealAccountV2SyncAndAcknowledgeConflicts() = runTest {
        val stateDispatcher = StandardTestDispatcher(testScheduler)
        val owner = accountKey()
        val url = sidecarUrl(owner)
        val legacyRoot = "$baseUrl/soundwave-sync.json"
        val legacyApp = "$baseUrl/SoundWave/soundwave-sync.json"
        val oldV1 = JSONObject().put("schema", "soundwave-webdav-sync").put("version", 1)
            .put("user", JSONObject().put("favs", "retained-v1")).toString()
        val oldOwned = envelope(owner, bundle("remote-v2-favorite"))
        val remote = Remote(
            initial = mapOf(legacyRoot to oldV1, legacyApp to oldV1, url to oldOwned),
            responseForPut = { _, attempt -> if (attempt == 1) WebDavHttpResponse(412) else WebDavHttpResponse(204, etag = "\"v2-written\"") }
        )
        val current = binding()
        val store = MemoryStore()
        val clientReturned = CompletableDeferred<WebDavSyncResult>()
        val allowLocalApply = CompletableDeferred<Unit>()
        val runnerFinished = CompletableDeferred<WebDavSyncResult>()
        var localApplied: LocalStore.UserBundle? = null
        var successfulResponses = 0
        lateinit var auto: WebDavAutoSync
        val ownedStats = ListenStatsDailyStore.record(
            "{}", Song(id = "stats-song", name = "Stats", singer = "stats", source = "wy"),
            "exact-owner-device", 37L, true, System.currentTimeMillis()
        )
        val local = bundle("local-v2-favorite", ownedStats, owner)
        val accountClient = client(
            remote,
            owner,
            beforePut = { onTestDispatcher(stateDispatcher) {
                assertTrue("uncertainty must be durably set before account PUT", auto.remoteWriteStarting())
            } },
            onPutResponse = { code -> onTestDispatcher(stateDispatcher) {
                if (code == 409 || code == 412) {
                    assertTrue("definite conflict response acknowledges pre-PUT uncertainty", auto.remoteWriteAcknowledged())
                } else if (code in setOf(200, 201, 204)) {
                    successfulResponses++
                    assertTrue("HTTP success must retain uncertainty until local apply completes", auto.state.uncertain)
                    assertTrue("the durable pre-PUT marker must remain set after HTTP success", store.value?.uncertain == true)
                }
            } }
        )
        auto = WebDavAutoSync(
            scope = this,
            store = store,
            getCurrentBinding = { current },
            runner = {
                val lease = WebDavOperations.capture()
                WebDavOperations.transaction(lease) {
                    val result = withContext(Dispatchers.IO) {
                        accountClient.sync(baseUrl, username, password, local, gateState)
                    }
                    clientReturned.complete(result)
                    if (result is WebDavSyncResult.Success) {
                        allowLocalApply.await()
                        lease.commit {
                            assertTrue("uncertainty is retained throughout guarded local apply", auto.state.uncertain)
                            assertTrue(store.value?.uncertain == true)
                            localApplied = result.bundle
                        }
                    }
                    // As in AccountWebDavAutoSync, only return Success after lease.commit/local apply.
                    runnerFinished.complete(result)
                    outcome(result)
                }
            },
            debounceMs = 0L,
            backoffMs = listOf(0L)
        )

        auto.onForeground()
        advanceUntilIdle()
        assertEquals("not ready: foreground alone cannot issue any HTTP request", 0, remote.requests.size)
        assertTrue(auto.manualSucceeded(current))
        assertFalse(auto.state.enabled)
        advanceUntilIdle()
        assertEquals(0, remote.requests.size)
        assertTrue(auto.enable())
        assertTrue(auto.state.enabled)
        // Enabling is not itself a trigger; only the subsequent foreground/local event starts work.
        advanceUntilIdle()
        assertEquals(0, remote.requests.size)
        auto.onForeground()
        advanceUntilIdle()
        val received = clientReturned.await()
        runCurrent()
        assertTrue(received is WebDavSyncResult.Success)
        assertEquals(1, successfulResponses)
        assertEquals("conflict plus successful PUT, with no retry while local apply is pending", 2, remote.puts)
        assertNull("the HTTP result alone must not apply the local bundle", localApplied)
        assertFalse("the automatic run must not finish before guarded local apply", runnerFinished.isCompleted)
        assertTrue(auto.state.uncertain)
        assertTrue(store.value?.uncertain == true)

        allowLocalApply.complete(Unit)
        val result = runnerFinished.await()
        runCurrent()

        assertTrue("real account-v2 client should complete after its 412 re-read: $result", result is WebDavSyncResult.Success)
        assertEquals((result as WebDavSyncResult.Success).bundle, localApplied)
        assertEquals(2, remote.puts)
        assertFalse(auto.state.uncertain)
        assertFalse(store.value?.uncertain == true)
        assertEquals(oldV1, remote.documents[legacyRoot])
        assertEquals(oldV1, remote.documents[legacyApp])
        assertTrue(remote.requests.filter { it.method == "GET" || it.method == "PUT" }.all { it.url == url })
        assertFalse("account sync must neither read nor modify either v1 path", remote.requests.any {
            it.url == legacyRoot || it.url == legacyApp
        })
        assertEquals("only its hashed v2 sidecar is written", 1, remote.documents.keys.count { it.contains("soundwave-account-v2-") })
        val firstPutBody = JSONObject(requireNotNull(remote.requests.first { it.method == "PUT" }.body))
        assertEquals(2, firstPutBody.getInt("version"))
        assertEquals(owner, firstPutBody.getString("owner"))
        val payloadUser = firstPutBody.getJSONObject("user")
        val statsV2 = payloadUser.getJSONObject("dailyListenStatsV2")
        assertEquals("stats marker and envelope owner are the exact authenticated owner key", owner, statsV2.getString("owner"))
        assertTrue(statsV2.getJSONObject("days").toString().contains("exact-owner-device"))
        assertTrue(payloadUser.getJSONArray("favs").toString().contains("remote-v2-favorite"))
        assertTrue(payloadUser.getJSONArray("favs").toString().contains("local-v2-favorite"))

        // Ownerless stats are discarded by the account boundary, rather than smuggled into v1 or v2 fields.
        val ownerlessStats = ListenStatsDailyStore.record(
            "{}", Song(id = "ownerless-song", name = "Ownerless", singer = "stats", source = "wy"),
            "must-not-upload-ownerless", 91L, true, System.currentTimeMillis()
        )
        val ownerlessClient = client(remote, owner)
        val ownerlessResult = runClient(ownerlessClient, bundle("ownerless-local", ownerlessStats, null))
        assertTrue(ownerlessResult is WebDavSyncResult.Success)
        val ownerlessPayload = JSONObject(requireNotNull(remote.requests.last { it.method == "PUT" }.body)).getJSONObject("user")
        assertFalse(ownerlessPayload.toString().contains("must-not-upload-ownerless"))
        assertFalse(ownerlessPayload.has("dailyListenStats"))
    }

    @Test
    fun successfulPutWithFailedLocalApplyRetainsUncertaintyAndNeverAutomaticallyRewrites() = runTest {
        val stateDispatcher = StandardTestDispatcher(testScheduler)
        val owner = accountKey()
        val current = binding()
        val store = MemoryStore()
        val remote = Remote()
        val applyAttempted = CompletableDeferred<Unit>()
        lateinit var auto: WebDavAutoSync
        val accountClient = client(remote, owner,
            beforePut = { onTestDispatcher(stateDispatcher) { assertTrue(auto.remoteWriteStarting()) } },
            onPutResponse = { code -> onTestDispatcher(stateDispatcher) {
                assertEquals(201, code)
                assertTrue(auto.state.uncertain)
                assertTrue(store.value?.uncertain == true)
            } })
        val failingLocalApply: (LocalStore.UserBundle) -> Unit = {
            assertTrue("local apply begins with the durable marker still set", auto.state.uncertain)
            assertTrue(store.value?.uncertain == true)
            applyAttempted.complete(Unit)
            throw IllegalStateException("deterministic local apply failure")
        }
        auto = WebDavAutoSync(
            scope = this,
            store = store,
            getCurrentBinding = { current },
            runner = {
                val lease = WebDavOperations.capture()
                WebDavOperations.transaction(lease) {
                    val result = withContext(Dispatchers.IO) {
                        accountClient.sync(baseUrl, username, password, bundle("local-apply-fails"), gateState)
                    }
                    assertTrue(result is WebDavSyncResult.Success)
                    lease.commit { failingLocalApply((result as WebDavSyncResult.Success).bundle) }
                    WebDavAutoSyncOutcome.Success
                }
            },
            debounceMs = 0L,
            backoffMs = listOf(0L, 0L, 0L)
        )
        assertTrue(auto.manualSucceeded(current))
        assertTrue(auto.enable())
        auto.onForeground()
        applyAttempted.await()
        runCurrent()
        assertEquals(1, remote.puts)
        assertTrue(auto.state.uncertain)
        assertTrue(store.value?.uncertain == true)
        val requestsAfterFailedApply = remote.requests.size
        auto.onForeground()
        auto.onLocalChanged()
        advanceUntilIdle()
        assertEquals("no duplicate write after HTTP success with a failed local apply", 1, remote.puts)
        assertEquals("uncertainty pauses even fresh automatic reads", requestsAfterFailedApply, remote.requests.size)
    }

    @Test
    fun acceptedPutLostResponsePersistsUncertaintyUntilManualReconciliation() = runTest {
        val stateDispatcher = StandardTestDispatcher(testScheduler)
        val owner = accountKey()
        val url = sidecarUrl(owner)
        lateinit var remote: Remote
        remote = Remote(responseForPut = { request, attempt ->
            // Simulate the server committing the complete first request and then losing its response.
            remote.documents[url] = request.body?.toString(StandardCharsets.UTF_8).orEmpty()
            if (attempt == 1) throw IOException("lost after accepted PUT")
            WebDavHttpResponse(204, etag = "\"manual-or-resumed\"")
        })
        val current = binding()
        val store = MemoryStore()
        val automaticResult = CompletableDeferred<WebDavSyncResult>()
        lateinit var auto: WebDavAutoSync
        val automaticClient = client(
            remote,
            owner,
            beforePut = { onTestDispatcher(stateDispatcher) { assertTrue(auto.remoteWriteStarting()) } }
        )
        auto = WebDavAutoSync(
            scope = this,
            store = store,
            getCurrentBinding = { current },
            runner = {
                val result = runClient(automaticClient, bundle("local-before-lost-response"))
                automaticResult.complete(result)
                outcome(result)
            },
            debounceMs = 0L,
            backoffMs = listOf(0L, 0L, 0L)
        )
        assertTrue(auto.manualSucceeded(current))
        assertTrue(auto.enable())
        auto.onForeground()
        advanceUntilIdle()
        val lost = automaticResult.await()
        runCurrent()

        assertTrue(lost is WebDavSyncResult.Failure)
        lost as WebDavSyncResult.Failure
        assertTrue("PUT IOException is ambiguous", lost.remoteStateUncertain)
        assertFalse("ambiguous PUT must not be marked automatically retryable", lost.retryable)
        assertEquals(1, remote.puts)
        assertTrue(auto.state.uncertain)
        assertTrue(store.value?.uncertain == true)

        auto.onLocalChanged()
        auto.onForeground()
        advanceUntilIdle()
        assertEquals("foreground and local edits cannot repeat an ambiguous PUT", 1, remote.puts)

        // A manual sync is the explicit reconciliation path; no network is involved beyond the fake transport.
        auto.manualStarted()
        val manualResult = runClient(client(remote, owner), bundle("manual-reconciliation"))
        assertTrue("manual read/merge/write against the accepted document succeeds: $manualResult", manualResult is WebDavSyncResult.Success)
        assertEquals("exactly one failed-response PUT plus one manual reconciliation PUT", 2, remote.puts)
        assertTrue(auto.manualSucceeded(current))
        assertFalse(auto.state.uncertain)
        assertFalse(store.value?.uncertain == true)

        // The automatic runner may resume only after the durable manual reconciliation cleared the marker.
        val resumedResult = CompletableDeferred<WebDavSyncResult>()
        val resumedClient = client(remote, owner, beforePut = {
            onTestDispatcher(stateDispatcher) { assertTrue(auto.remoteWriteStarting()) }
        })
        auto = WebDavAutoSync(
            scope = this,
            store = store,
            getCurrentBinding = { current },
            runner = {
                val result = runClient(resumedClient, bundle("local-after-reconcile"))
                resumedResult.complete(result)
                outcome(result)
            },
            debounceMs = 0L,
            backoffMs = emptyList()
        )
        auto.onForeground()
        advanceUntilIdle()
        assertTrue(resumedResult.await() is WebDavSyncResult.Success)
        runCurrent()
        assertEquals("exactly one ambiguous PUT, one manual reconciliation PUT, and one resumed automatic PUT", 3, remote.puts)
        assertFalse(auto.state.uncertain)
    }

    @Test
    fun explicitlyRetryableGetIOExceptionUsesAtMostFourAttemptsAndNeverPuts() = runTest {
        val stateDispatcher = StandardTestDispatcher(testScheduler)
        val owner = accountKey()
        val remote = Remote()
        val current = binding()
        val store = MemoryStore()
        val completed = CompletableDeferred<WebDavSyncResult>()
        var attempts = 0
        val failingTransport = object : WebDavTransport {
            override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
                if (request.method == "GET") {
                    remote.requests += Recorded(request.method, request.url)
                    throw IOException("explicitly retryable GET failure")
                }
                return remote.execute(request)
            }
        }
        val accountClient = client(failingTransport, owner, beforePut = {
            onTestDispatcher(stateDispatcher) {
                assertTrue("a GET failure must never reach the pre-PUT hook", false)
            }
        })
        val auto = WebDavAutoSync(
            scope = this,
            store = store,
            getCurrentBinding = { current },
            runner = {
                val result = runClient(accountClient, bundle("get-failure"))
                if (++attempts == 4) completed.complete(result)
                outcome(result)
            },
            debounceMs = 0L,
            backoffMs = listOf(0L, 0L, 0L)
        )
        auto.manualSucceeded(current)
        auto.enable()
        auto.onForeground()
        advanceUntilIdle()
        val failure = completed.await()
        runCurrent()

        assertTrue(failure is WebDavSyncResult.Failure)
        failure as WebDavSyncResult.Failure
        assertTrue("GET IOException is explicitly safe to retry", failure.retryable)
        assertFalse(failure.remoteStateUncertain)
        assertEquals("initial attempt + three bounded retries", 4, remote.requests.count { it.method == "GET" })
        assertEquals("GET failure must not issue PUT", 0, remote.puts)
        assertFalse(auto.state.uncertain)
    }

    @Test
    fun queuedAutomaticTransactionCancelsAndManualSnapshotReadsFreshStateWithoutOverlap() = runTest {
        val current = binding()
        val store = MemoryStore()
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val autoRunnerWaiting = CompletableDeferred<Unit>()
        val autoRunnerCancelled = CompletableDeferred<Unit>()
        var activeTransactions = 0
        var maxConcurrentTransactions = 0
        var autoActionCount = 0
        var localSnapshot = "old-snapshot"
        var manualSnapshot: String? = null
        val holder = async {
            WebDavOperations.transaction {
                activeTransactions++
                maxConcurrentTransactions = maxOf(maxConcurrentTransactions, activeTransactions)
                lockEntered.complete(Unit)
                try {
                    releaseLock.await()
                } finally {
                    activeTransactions--
                }
            }
        }
        runCurrent()
        lockEntered.await()

        val auto = WebDavAutoSync(
            scope = this,
            store = store,
            getCurrentBinding = { current },
            runner = {
                autoRunnerWaiting.complete(Unit)
                try {
                    WebDavOperations.transaction {
                        activeTransactions++
                        maxConcurrentTransactions = maxOf(maxConcurrentTransactions, activeTransactions)
                        autoActionCount++
                        activeTransactions--
                    }
                    WebDavAutoSyncOutcome.Success
                } catch (cancelled: CancellationException) {
                    autoRunnerCancelled.complete(Unit)
                    throw cancelled
                }
            },
            debounceMs = 0L,
            backoffMs = emptyList()
        )
        auto.manualSucceeded(current)
        auto.enable()
        auto.onForeground()
        runCurrent()
        autoRunnerWaiting.await()
        assertEquals("the held transaction prevents the auto body from entering", 0, autoActionCount)

        // Background/logout-style cancellation while it waits must not retain or steal the slot.
        auto.onBackground()
        runCurrent()
        autoRunnerCancelled.await()
        localSnapshot = "newer-manual-state"
        var manualEntered = false
        val manual = async {
            WebDavOperations.transaction {
                activeTransactions++
                maxConcurrentTransactions = maxOf(maxConcurrentTransactions, activeTransactions)
                manualEntered = true
                manualSnapshot = localSnapshot
                activeTransactions--
            }
        }
        runCurrent()
        assertFalse("manual waits for the existing shared transaction too", manualEntered)

        releaseLock.complete(Unit)
        holder.await()
        manual.await()
        assertTrue(manualEntered)
        assertEquals("manual takes its snapshot only after obtaining the shared slot", "newer-manual-state", manualSnapshot)
        assertEquals("no concurrent transaction bodies", 1, maxConcurrentTransactions)
        assertEquals("cancelled automatic waiter never entered", 0, autoActionCount)
        WebDavOperations.invalidate()
    }
}
