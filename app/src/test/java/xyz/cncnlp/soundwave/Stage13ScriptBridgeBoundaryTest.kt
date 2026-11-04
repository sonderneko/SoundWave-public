package xyz.cncnlp.soundwave

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Real nativeFetch admission/queue/cancellation using a deterministic scheduler and interceptor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage13ScriptBridgeBoundaryTest {
    private class Manual : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Fixture(
        maxRunning: Int = 1,
        maxQueued: Int = 2,
        reservePlaybackSlots: Boolean = false,
        val respond: (Request, Fixture) -> Response
    ) {
        val runtime = ScriptRuntime(RuntimeEnvironment.getApplication() as Context)
        val bridge = runtime.Bridge()
        val manual = Manual()
        val scheduler = ScriptRequestScheduler(maxRunning, maxQueued, manual, reservePlaybackSlots)
        val requests = mutableListOf<Request>()
        init {
            set("fetchScheduler", scheduler)
            set("http", OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain -> requests.add(chain.request()); respond(chain.request(), this) }.build())
        }
        fun set(name: String, value: Any) { ScriptRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value) }
        @Suppress("UNCHECKED_CAST") fun map(name: String): MutableMap<String, Any> =
            ScriptRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(runtime) as MutableMap<String, Any>
        fun seed(parent: String, purpose: ResolveTicket.Purpose = ResolveTicket.Purpose.PLAY) {
            val clazz = ScriptRuntime::class.java.declaredClasses.single { it.simpleName == "PendingUrlRequest" }
            val ctor = clazz.getDeclaredConstructor(String::class.java, CompletableDeferred::class.java, ResolveTicket.Purpose::class.java, Job::class.java).apply { isAccessible = true }
            map("pendingUrl")[parent] = ctor.newInstance(parent, CompletableDeferred<String>(), purpose, null)
        }
        fun fetch(cid: String, parent: String = "parent", url: String = "https://8.8.8.8/source") = bridge.nativeFetch(url, "GET", "{}", null, cid, parent)
        fun clean() { assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot()); assertTrue(map("activeCalls").isEmpty()); assertTrue(map("fetchParents").isEmpty()) }
        fun close() { runtime.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun response(r: Request) = Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body("body".toResponseBody()).build()

    @Test fun unknownParentAndDuplicateCallbackCannotCreateExtraWork() {
        val f = Fixture { r, _ -> response(r) }
        try {
            f.fetch("missing"); assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), f.scheduler.snapshot())
            f.seed("parent"); f.fetch("same"); f.fetch("same")
            assertEquals(1, f.manual.tasks.size); f.manual.runAll(); assertEquals(1, f.requests.size); f.clean()
        } finally { f.close() }
    }
    @Test fun queueRejectionIsAttributedToTheOwningScript() {
        val f = Fixture(maxRunning = 1, maxQueued = 1) { r, _ -> response(r) }
        try {
            f.seed("parent")
            f.map("capToScript")["parent"] = "fixture-script"
            f.fetch("running")
            f.fetch("queued")
            f.fetch("rejected")
            assertEquals(ScriptRequestScheduler.Snapshot(1, 1, 2), f.scheduler.snapshot())
            assertEquals("queue_full", f.map("lastFetchFailure")["parent"])
            assertEquals("queue_full", f.map("lastFailureByScript")["fixture-script"])
            f.manual.runAll(); f.clean()
        } finally { f.close() }
    }
    @Test fun queuedNativeCancelDoesNotEnterTransportOrAffectTheRunningSibling() {
        val f = Fixture { r, _ -> response(r) }
        try {
            f.seed("parent"); f.fetch("first"); f.fetch("queued")
            assertEquals(ScriptRequestScheduler.Snapshot(1, 1, 2), f.scheduler.snapshot())
            f.bridge.nativeCancel("queued"); assertFalse(f.map("fetchParents").containsKey("queued"))
            f.manual.runAll(); assertEquals(1, f.requests.size); f.clean()
        } finally { f.close() }
    }
    @Test fun runningNativeCancelReleasesCallAndStartsNoFurtherRequest() {
        val f = Fixture { _, state -> state.bridge.nativeCancel("running"); throw IOException("controlled cancellation") }
        try {
            f.seed("parent"); f.fetch("running"); f.manual.runAll()
            assertEquals(1, f.requests.size); assertTrue(f.map("lastFetchFailure").isEmpty()); f.clean()
        } finally { f.close() }
    }
    @Test fun nativeFetchUsesParentPurposeToPrioritizePlayWhenLowerQueueIsFull() {
        val f = Fixture(maxRunning = 2, maxQueued = 2, reservePlaybackSlots = true) { r, _ -> response(r) }
        try {
            f.seed("active-play", ResolveTicket.Purpose.PLAY)
            f.seed("active-download", ResolveTicket.Purpose.DOWNLOAD)
            f.seed("queued-prefetch", ResolveTicket.Purpose.PREFETCH)
            f.seed("queued-download", ResolveTicket.Purpose.DOWNLOAD)
            f.seed("queued-play", ResolveTicket.Purpose.PLAY)

            f.fetch("active-play-fetch", "active-play", "https://8.8.8.8/active-play")
            f.fetch("active-download-fetch", "active-download", "https://8.8.8.8/active-download")
            f.fetch("queued-prefetch-fetch", "queued-prefetch", "https://8.8.8.8/queued-prefetch")
            f.fetch("queued-download-fetch", "queued-download", "https://8.8.8.8/queued-download")
            assertEquals(ScriptRequestScheduler.Snapshot(2, 2, 4), f.scheduler.snapshot())

            f.fetch("priority-play-fetch", "queued-play", "https://8.8.8.8/priority-play")
            assertEquals("the incoming PLAY displaces the lowest queued PREFETCH", ScriptRequestScheduler.Snapshot(2, 2, 4), f.scheduler.snapshot())
            f.manual.runAll()

            assertEquals(
                listOf("/active-play", "/active-download", "/priority-play", "/queued-download"),
                f.requests.map { it.url.encodedPath }
            )
            f.clean()
        } finally { f.close() }
    }
    @Test fun destroyingOneRuntimeCancelsItsQueuedWorkWithoutCancellingAnotherOwner() {
        val a = Fixture { r, _ -> response(r) }; val b = Fixture { r, _ -> response(r) }
        try {
            // Shared scheduler, distinct runtime owners, same callback id is legitimate.
            b.set("fetchScheduler", a.scheduler); a.seed("parent"); b.seed("parent")
            a.fetch("same"); b.fetch("same"); a.runtime.destroy(); a.manual.runAll()
            assertEquals(0, a.requests.size); assertEquals(1, b.requests.size)
            assertTrue(a.map("activeCalls").isEmpty()); assertTrue(b.map("activeCalls").isEmpty())
            assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), a.scheduler.snapshot())
        } finally { a.close(); b.close() }
    }
}
