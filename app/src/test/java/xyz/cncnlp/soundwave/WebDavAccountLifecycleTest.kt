package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebDavAccountLifecycleTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun realAccountSwitchRejectsLateBundleApplyWithoutLocalMutation() = runTest {
        val controller = controller(this)
        controller.user = "fake-a" // 本地资料名即身份（账号去除后无 loggedIn 概念，user setter 即身份变更）
        controller.userBundle = bundle("local-a")
        val lease = WebDavOperations.capture()
        controller.user = "fake-b"
        controller.userBundle = bundle("local-b")
        val diskBefore = LocalStore.prefs(context).all.toMap()
        try {
            lease.commit { controller.applyWebDavBundleLocally(bundle("late-a")) }
            fail("old account must not apply")
        } catch (_: CancellationException) { }
        assertEquals(listOf("local-b"), controller.userBundle.favs.map { it.id })
        assertEquals(diskBefore, LocalStore.prefs(context).all.toMap())
    }

    @Test
    fun logoutSetterInvalidatesLeaseWithoutWaitingForTransport() = runTest {
        val controller = controller(this)
        controller.user = "fake-a" // 本地资料名即身份（账号去除后无 loggedIn 概念，user setter 即身份变更）
        val lease = WebDavOperations.capture()
        controller.user = "" // 旧 loggedIn=false 的后继语义：清空本地资料名，身份变更即刻使租约失效
        assertExpired(lease)
    }

    @Test
    fun transitionInvalidatesWebDavEvenWhileWaitingForAccountMutex() = runTest {
        val controller = controller(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lease = WebDavOperations.capture()
        val first = async {
            controller.withLocalIdentityTransition { entered.complete(Unit); release.await() }
        }
        entered.await()
        val second = async { controller.withLocalIdentityTransition {} }
        runCurrent()
        try {
            assertFalse(second.isCompleted)
            assertExpired(lease)
            try { WebDavOperations.capture(); fail("new work must be refused during the transition") }
            catch (_: CancellationException) { }
        } finally {
            release.complete(Unit)
            first.await()
            second.await()
        }
        WebDavOperations.capture().ensureActive()
    }

    @Test
    fun accountSwitchDuringUncooperativeGetPreventsPut() = runTest {
        val controller = controller(this)
        controller.user = "fake-a" // 本地资料名即身份（账号去除后无 loggedIn 概念，user setter 即身份变更）
        val getEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val puts = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val client = WebDavSyncClient(WebDavTransport { request ->
            when (request.method) {
                "GET" -> {
                    getEntered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    WebDavHttpResponse(404)
                }
                "PUT" -> { puts.incrementAndGet(); WebDavHttpResponse(201) }
                else -> WebDavHttpResponse(207)
            }
        })
        try {
            val result = worker.submit<Boolean> {
                try {
                    client.sync("https://fake.invalid/dav/SoundWave", "fake", "fake", bundle("a"),
                        gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY })
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(getEntered.await(5, TimeUnit.SECONDS))
            controller.user = "fake-b"
            release.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, puts.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertExpired(lease: WebDavOperationGate.Lease) {
        try { lease.ensureActive(); fail("lease should be expired") } catch (_: CancellationException) { }
    }

    private fun controller(scope: CoroutineScope): CloudSyncController {
        val prefs = context.getSharedPreferences("webdav-lifecycle-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return CloudSyncController(context, scope, prefs, getScripts = { emptyList() },
            ioDispatcher = Dispatchers.IO, mainDispatcher = Dispatchers.IO)
    }

    private fun bundle(id: String) = LocalStore.UserBundle(
        favs = mutableListOf(Song(id, "Fake", "Fake", "wy")))
}
