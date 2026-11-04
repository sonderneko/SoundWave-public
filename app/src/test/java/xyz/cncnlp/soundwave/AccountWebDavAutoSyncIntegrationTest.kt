package xyz.cncnlp.soundwave

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs the production bridge, controller, lease, snapshot/apply, and HTTP client together.
 * Only credential decoding and the HTTP transport are fake. Lifecycle entrypoints are invoked
 * explicitly: this does not claim Activity/Compose lifecycle or AndroidKeyStore coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountWebDavAutoSyncIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun verifiedEmptyLocalProvenance() {
        listOf("soundwave", "soundwave_account_local_snapshots_v1", "soundwave_webdav",
            "webdav_auto_sync_v1", LegacyUnassignedSnapshot.SOURCE_PREFERENCES,
            LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES).forEach { prefs(it).edit().clear().commit() }
        LocalStore.prefs(context).edit().clear().commit()
        prefs(LegacyUnassignedSnapshot.SOURCE_PREFERENCES).edit()
            .putString(LegacyUnassignedSnapshot.USER_DATA_KEY, "{\"favs\":[],\"pls\":[]}")
            .putString(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY, "[]").commit()
        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(context))
        assertTrue(LegacyUnassignedSnapshot.canExplicitlyEnableAccountSync(context))
    }

    @After
    fun detachAndCloseWorkers() {
        fixtures.forEach {
            it.bridge.detach()
            it.remote.release.countDown()
            it.worker.close()
        }
        WebDavOperations.invalidate()
    }

    private class MemoryStore(initial: WebDavAutoSyncState = WebDavAutoSyncState.DEFAULT) : WebDavAutoSyncStore {
        var value = initial
        val writes = CopyOnWriteArrayList<WebDavAutoSyncState>()
        override fun read() = value
        override fun writeAtomically(state: WebDavAutoSyncState): Boolean {
            value = state
            writes += state
            return true
        }
    }

    private class Remote(private val owner: String, private val store: MemoryStore) : WebDavTransport {
        val requests = CopyOnWriteArrayList<WebDavHttpRequest>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val returned = Channel<Unit>(Channel.UNLIMITED)
        val release = CountDownLatch(1)
        var pauseAt: String? = null
        var losePutResponse = false
        var rejectFirstPut = false
        private var puts = 0
        val remoteBody = JSONObject().put("schema", "soundwave-webdav-sync").put("version", 2)
            .put("owner", owner).put("user", LocalStore.UserBundle(favs = mutableListOf(
                Song("remote-favorite", "Remote", "Fixture", "wy")
            )).toCloudJson(includeAvatar = false)).toString()

        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse =
            error("The production client must use the lease-aware transport entrypoint")

        override fun execute(request: WebDavHttpRequest, lease: WebDavOperationGate.Lease): WebDavHttpResponse {
            lease.ensureActive()
            requests += request
            if (request.method == "PUT") {
                check(store.value.uncertain) { "pre-PUT uncertainty must already be durable" }
                puts++
            }
            if (request.method == pauseAt) {
                val unregister = lease.onCancel { cancelled.complete(Unit) }
                try {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "test did not release fake transport" }
                } finally { unregister() }
            }
            // Deliberately permit a late transport response; the real client/lease must reject it.
            return when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "MKCOL" -> WebDavHttpResponse(405)
                "GET" -> WebDavHttpResponse(200, remoteBody, "\"remote-v1\"")
                "PUT" -> {
                    returned.trySend(Unit)
                    if (losePutResponse) throw IOException("fake PUT response lost after remote acceptance")
                    if (rejectFirstPut && puts == 1) WebDavHttpResponse(412)
                    else WebDavHttpResponse(204, etag = "\"written-v2\"")
                }
                else -> error("unexpected method ${request.method}")
            }
        }
    }

    private inner class Fixture(val scope: TestScope, initial: WebDavAutoSyncState = WebDavAutoSyncState.DEFAULT) {
        val worker: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val main = StandardTestDispatcher(scope.testScheduler)
        val store = MemoryStore(initial)
        val owner = AccountDataIdentity.key("Exact User")
        var saved: WebDavSavedCredentialState = WebDavSavedCredentialState.Ready(
            WebDavSavedCredentials("https://coordinator.invalid/Dav", "Dav User", "fake-password"))
        val account = controller(scope, worker, main)
        val remote = Remote(owner, store)
        val bridge: AccountWebDavAutoSync
        var fingerprint = 0

        init {
            // Stats attribution reads the persisted profile name, while the sync capability
            // reads the live controller. Establishing the local profile in both places forms
            // both boundaries (account system removed: no login state or account password).
            prefs("soundwave").edit().putString("user", "Exact User").commit()
            account.user = "Exact User"
            account.userBundle = LocalStore.UserBundle(favs = mutableListOf(
                Song("local-favorite", "Local", "Fixture", "wy")))
            LocalStore.saveWithIndependentHistory(context, account.userBundle, emptyList(), account.user)
            assertNotNull(account.prepareWebDavAccountDataSync())
            bridge = AccountWebDavAutoSync(context, scope, account, worker, main, store, remote,
                credentialState = { saved })
            bridge.attach()
            fixtures += this
        }

        fun readyAndEnable() {
            assertTrue(bridge.manualStarted())
            bridge.manualSucceeded(owner)
            assertTrue(bridge.canEnable)
            assertTrue(bridge.setEnabled(true))
        }

        fun changeCredentials(next: WebDavSavedCredentialState) {
            saved = next
            // Exercise the actual SharedPreferences listener, but store no real secret/ciphertext.
            prefs("soundwave_webdav").edit().putInt("fixture_revision", ++fingerprint).commit()
            shadowOf(Looper.getMainLooper()).idle()
            scope.runCurrent()
        }

        suspend fun finishRun() {
            // Called only after transport entry: the production coordinator already holds this mutex.
            account.withWebDavAccountLock { }
            scope.runCurrent()
        }

        suspend fun successfulForeground() {
            bridge.foreground()
            remote.returned.receive()
            finishRun()
            assertFalse(bridge.state.uncertain)
        }
    }

    private fun controller(scope: CoroutineScope, io: ExecutorCoroutineDispatcher,
        main: CoroutineDispatcher) = CloudSyncController(
        context = context, scope = scope, prefs = prefs("soundwave"), getScripts = { emptyList() },
        ioDispatcher = io, mainDispatcher = main
    )

    @Test fun defaultOffAndManualReadinessDoNotIssueRequestsUntilExplicitEnableAndTrigger() = runTest {
        val f = Fixture(this)
        f.bridge.foreground()
        AccountOwnedDataChanges.localChange(f.owner)
        advanceUntilIdle()
        assertEquals(WebDavAutoSyncState.DEFAULT, f.bridge.state)
        assertFalse(f.bridge.setEnabled(true))
        assertTrue(f.remote.requests.isEmpty())
        f.bridge.manualSucceeded(AccountDataIdentity.key("exact user"))
        assertFalse(f.bridge.canEnable)
        assertTrue(f.bridge.manualStarted())
        f.bridge.manualSucceeded(f.owner)
        assertTrue(f.bridge.canEnable)
        assertFalse(f.bridge.state.enabled)
        advanceUntilIdle()
        assertTrue(f.remote.requests.isEmpty())
        assertTrue(f.bridge.setEnabled(true))
        advanceUntilIdle()
        assertTrue("enable itself is not a trigger", f.remote.requests.isEmpty())
        f.successfulForeground()
        assertEquals(1, f.remote.requests.count { it.method == "PUT" })
        assertEquals(setOf("local-favorite", "remote-favorite"), f.account.userBundle.favs.map { it.id }.toSet())
        val expected = "https://coordinator.invalid/Dav/SoundWave/soundwave-account-v2-${f.owner.removePrefix("swown2_")}.json"
        assertTrue(f.remote.requests.filter { it.method in listOf("GET", "PUT") }.all { it.url == expected })
        assertTrue(f.remote.requests.all { it.username == "Dav User" && it.password == "fake-password" })
        val payload = JSONObject(String(requireNotNull(f.remote.requests.first { it.method == "PUT" }.body), Charsets.UTF_8))
        assertEquals(f.owner, payload.getString("owner"))
        assertEquals(2, payload.getInt("version"))
        assertFalse(f.store.value.uncertain)
    }

    @Test fun persistedEnablementDoesNotSurviveANewCoordinatorSession() = runTest {
        val binding = WebDavAutoSyncBinding.bind(AccountDataIdentity.key("Exact User"),
            "https://coordinator.invalid/Dav", "Dav User")
        val f = Fixture(this, WebDavAutoSyncState(binding, manualReady = true, enabled = true))
        assertTrue(f.bridge.canEnable)
        assertFalse(f.bridge.state.enabled)
        f.bridge.foreground()
        AccountOwnedDataChanges.localChange(f.owner)
        advanceUntilIdle()
        assertTrue(f.remote.requests.isEmpty())
    }

    @Test fun localChangesAreOwnerFilteredAndBackgroundedUntilForeground() = runTest {
        val f = Fixture(this)
        f.readyAndEnable()
        AccountOwnedDataChanges.localChange(f.owner)
        advanceUntilIdle()
        assertTrue(f.remote.requests.isEmpty())
        f.successfulForeground()
        val before = f.remote.requests.size
        AccountOwnedDataChanges.localChange(AccountDataIdentity.key("exact user"))
        advanceUntilIdle()
        assertEquals(before, f.remote.requests.size)
        AccountOwnedDataChanges.localChange(f.owner)
        f.remote.returned.receive()
        f.finishRun()
        assertEquals(2, f.remote.requests.count { it.method == "PUT" })
        f.bridge.background()
        AccountOwnedDataChanges.localChange(f.owner)
        advanceUntilIdle()
        assertEquals(2, f.remote.requests.count { it.method == "PUT" })
        f.successfulForeground()
        assertEquals(3, f.remote.requests.count { it.method == "PUT" })
    }

    @Test fun exactEndpointAndUsernameChangesRevokeReadinessThroughCredentialListener() = runTest {
        val f = Fixture(this)
        val original = (f.saved as WebDavSavedCredentialState.Ready).credentials
        for (changed in listOf(original.copy(url = "https://coordinator.invalid/dav"),
            original.copy(url = "https://other.invalid/Dav"), original.copy(username = "dav user"))) {
            f.changeCredentials(WebDavSavedCredentialState.Ready(original))
            f.readyAndEnable()
            f.changeCredentials(WebDavSavedCredentialState.Ready(changed))
            assertEquals(WebDavAutoSyncState.DEFAULT, f.bridge.state)
            assertFalse(f.bridge.canEnable)
            assertFalse(f.bridge.setEnabled(true))
            f.bridge.foreground()
            advanceUntilIdle()
            assertTrue(f.remote.requests.isEmpty())
        }
    }

    @Test fun missingAndUnavailableCredentialsNeverGrantManualReadiness() = runTest {
        val f = Fixture(this)
        for (missing in listOf(WebDavSavedCredentialState.None,
            WebDavSavedCredentialState.PasswordUnavailable("https://coordinator.invalid/Dav", "Dav User", true),
            WebDavSavedCredentialState.PasswordUnavailable("https://coordinator.invalid/Dav", "Dav User", false))) {
            f.changeCredentials(missing)
            f.bridge.manualSucceeded(f.owner)
            assertFalse(f.bridge.canEnable)
            assertFalse(f.bridge.setEnabled(true))
            f.bridge.foreground()
            advanceUntilIdle()
            assertTrue(f.remote.requests.isEmpty())
        }
    }

    @Test fun unverifiedProvenanceBlocksReadinessDespiteLiveAccountAndCredentials() = runTest {
        val f = Fixture(this)
        prefs(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES).edit().clear().commit()
        assertFalse(LegacyUnassignedSnapshot.canExplicitlyEnableAccountSync(context))
        f.bridge.manualSucceeded(f.owner)
        assertFalse(f.bridge.canEnable)
        assertFalse(f.bridge.setEnabled(true))
        f.bridge.foreground()
        advanceUntilIdle()
        assertTrue(f.remote.requests.isEmpty())
    }

    private suspend fun TestScope.cancelBlockedRead(action: (Fixture) -> Unit) {
        val f = Fixture(this)
        f.remote.pauseAt = "GET"
        f.readyAndEnable()
        f.bridge.foreground()
        f.remote.entered.await()
        action(f)
        runCurrent()
        f.remote.cancelled.await()
        f.remote.release.countDown()
        f.finishRun()
        advanceUntilIdle()
        assertEquals("cancelled read must never progress to PUT", 0, f.remote.requests.count { it.method == "PUT" })
        assertFalse("late remote data must not apply (logout may archive/clear local data)",
            f.account.userBundle.favs.any { it.id == "remote-favorite" })
        val before = f.remote.requests.size
        f.bridge.foreground()
        AccountOwnedDataChanges.localChange(f.owner)
        advanceUntilIdle()
        assertEquals("revoked/disabled authorization cannot be reused", before, f.remote.requests.size)
    }

    @Test fun disableCancelsInFlightReadBeforePut() = runTest {
        cancelBlockedRead { assertTrue(it.bridge.setEnabled(false)) }
    }

    @Test fun logoutSetterCancelsInFlightReadAndRevokesGrant() = runTest {
        cancelBlockedRead { it.account.user = "" } // 旧 loggedIn=false 的后继语义：清空本地资料名撤销身份
        assertEquals(WebDavAutoSyncState.DEFAULT, fixtures.single().bridge.state)
    }

    @Test fun exactAccountChangeSetterCancelsInFlightReadAndRevokesGrant() = runTest {
        cancelBlockedRead { it.account.user = "exact user" }
        assertEquals(WebDavAutoSyncState.DEFAULT, fixtures.single().bridge.state)
    }

    @Test fun credentialEndpointChangeCancelsInFlightReadBeforePut() = runTest {
        cancelBlockedRead {
            val saved = (it.saved as WebDavSavedCredentialState.Ready).credentials
            it.changeCredentials(WebDavSavedCredentialState.Ready(saved.copy(url = "https://other.invalid/Dav")))
        }
    }

    @Test fun backgroundCancelsReadAndDetachUnsubscribesCallbacks() = runTest {
        val f = Fixture(this)
        f.remote.pauseAt = "GET"
        f.readyAndEnable()
        f.bridge.foreground()
        f.remote.entered.await()
        f.bridge.background()
        runCurrent()
        f.remote.cancelled.await()
        f.remote.release.countDown()
        f.finishRun()
        assertEquals(0, f.remote.requests.count { it.method == "PUT" })
        f.bridge.detach()
        val state = f.bridge.state
        f.account.invalidatePendingLocalIdentityChange() // 已 detach：身份失效事件不应再触达 bridge
        f.changeCredentials(WebDavSavedCredentialState.None)
        AccountOwnedDataChanges.localChange(f.owner)
        advanceUntilIdle()
        assertEquals("detached callbacks must not alter coordinator state", state, f.bridge.state)
        assertEquals(0, f.remote.requests.count { it.method == "PUT" })
    }

    @Test fun ambiguousPutPausesAllTriggersUntilSuccessfulManualReconciliation() = runTest {
        val f = Fixture(this)
        f.remote.losePutResponse = true
        f.readyAndEnable()
        f.bridge.foreground()
        f.remote.returned.receive()
        f.finishRun()
        assertTrue(f.bridge.state.uncertain)
        assertTrue(f.store.value.uncertain)
        assertFalse(f.bridge.canEnable)
        assertFalse(f.bridge.setEnabled(true))
        repeat(3) {
            f.bridge.foreground()
            AccountOwnedDataChanges.localChange(f.owner)
            advanceUntilIdle()
        }
        assertEquals(1, f.remote.requests.count { it.method == "PUT" })
        assertEquals(listOf("local-favorite"), f.account.userBundle.favs.map { it.id })
        assertTrue(f.bridge.manualStarted())
        f.bridge.foreground()
        advanceUntilIdle()
        assertEquals(1, f.remote.requests.count { it.method == "PUT" })
        f.bridge.manualSucceeded(f.owner)
        assertFalse(f.bridge.state.uncertain)
        f.remote.losePutResponse = false
        f.successfulForeground()
        assertEquals(2, f.remote.requests.count { it.method == "PUT" })
    }

    @Test fun cancellationAfterPutAdmissionPreservesUncertaintyAndRejectsLateSuccess() = runTest {
        val f = Fixture(this)
        f.remote.pauseAt = "PUT"
        f.readyAndEnable()
        f.bridge.foreground()
        f.remote.entered.await()
        assertTrue(f.bridge.state.uncertain)
        assertTrue(f.store.value.uncertain)
        f.bridge.background()
        runCurrent()
        f.remote.cancelled.await()
        f.remote.release.countDown()
        f.finishRun()
        assertTrue(f.bridge.state.uncertain)
        assertTrue(f.store.value.uncertain)
        assertEquals(listOf("local-favorite"), f.account.userBundle.favs.map { it.id })
        f.bridge.foreground()
        advanceUntilIdle()
        assertEquals(1, f.remote.requests.count { it.method == "PUT" })
    }

    @Test fun definiteConflictAllowsBoundedRetryAndClearsMarkerOnlyAfterLocalApply() = runTest {
        val f = Fixture(this)
        f.remote.rejectFirstPut = true
        f.readyAndEnable()
        f.successfulForeground()
        assertEquals(2, f.remote.requests.count { it.method == "PUT" })
        assertEquals(listOf(true, false, true, false), f.store.writes
            .dropWhile { !it.uncertain }.map { it.uncertain })
        assertTrue(f.account.userBundle.favs.any { it.id == "remote-favorite" })
    }
}
