package xyz.cncnlp.soundwave

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.sync.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production adapter with fake OkHttp calls: no sockets or remote requests. */
class WebDavHttpCancellationTest {
    private val request = WebDavHttpRequest("PROPFIND", "https://fake.invalid/dav/", "fake", "fake")

    @Test
    fun coroutineCancellationCallsOkHttpCancelAndRejectsItsLateResponse() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val gate = WebDavOperationGate { executor.execute(it) }
        val lease = gate.capture()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        val call = FakeCall {
            entered.countDown()
            assertTrue(released.await(5, TimeUnit.SECONDS))
        }
        call.onCancel = { released.countDown() }
        val transport = OkHttpWebDavTransport(Call.Factory { calls.incrementAndGet(); call })
        var applied = false
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.run(lease) {
                    transport.execute(request, lease)
                    lease.commit { applied = true }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(call.isCanceled())
            assertEquals(1, calls.get())
            assertFalse(applied)
        } finally {
            released.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun suspendTransactionCancellationCancelsCallBeforeIoChildReturns() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val gate = WebDavOperationGate()
        val lease = gate.capture()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val call = FakeCall {
            entered.countDown()
            assertTrue(released.await(5, TimeUnit.SECONDS))
        }
        call.onCancel = { released.countDown() }
        val transport = OkHttpWebDavTransport(Call.Factory { call })
        var applied = false
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.transaction(lease) {
                    withContext(dispatcher) { transport.execute(request, lease) }
                    lease.commit { applied = true }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("cancellation must reach Call.cancel while IO is still blocked", call.isCanceled())
            assertFalse(applied)
        } finally {
            released.countDown()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun expiredLeaseBeforeCallRegistrationCancelsWithoutExecuting() {
        val gate = WebDavOperationGate()
        val lease = gate.capture()
        val call = FakeCall {}
        val transport = OkHttpWebDavTransport(Call.Factory { call })
        lease.cancel()
        try {
            transport.execute(request, lease)
            fail("expired request must throw cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
            assertTrue(call.isCanceled())
            assertFalse(call.isExecuted())
        }
    }

    @Test
    fun successfulCallUnregistersCancellationHook() {
        val gate = WebDavOperationGate()
        val lease = gate.capture()
        val call = FakeCall {}
        val transport = OkHttpWebDavTransport(Call.Factory { call })
        assertEquals(207, transport.execute(request, lease).code)
        lease.cancel()
        assertFalse("completed call must no longer be registered", call.isCanceled())
    }

    private class FakeCall(private val beforeResponse: () -> Unit) : Call {
        private val executed = AtomicInteger()
        private val cancelled = AtomicInteger()
        var onCancel: () -> Unit = {}
        override fun request(): Request = Request.Builder().url("https://fake.invalid/dav/").build()
        override fun execute(): Response {
            executed.incrementAndGet()
            beforeResponse()
            // Intentionally return success even after cancellation: the adapter must reject it.
            return Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                .code(207).message("fake").body("".toResponseBody()).build()
        }
        override fun enqueue(responseCallback: Callback) = error("synchronous adapter expected")
        override fun cancel() { cancelled.incrementAndGet(); onCancel() }
        override fun isExecuted(): Boolean = executed.get() > 0
        override fun isCanceled(): Boolean = cancelled.get() > 0
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(beforeResponse)
    }
}
