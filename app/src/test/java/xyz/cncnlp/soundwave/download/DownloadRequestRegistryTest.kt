package xyz.cncnlp.soundwave.download

import java.net.InetAddress
import java.net.ServerSocket
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import org.junit.Assert.*
import org.junit.Test

class DownloadRequestRegistryTest {
    private class Request { val cancelled = AtomicBoolean(false) }

    @Test fun cancelBetweenRequestsRejectsLateRegistrationButAllowsNewAttempt() {
        val registry = DownloadRequestRegistry<Request> { it.cancelled.set(true) }
        val old = registry.begin("task")
        registry.cancel("task")
        val late = Request()
        assertFalse(registry.track(old, late) { old.isCancelled })
        assertTrue(late.cancelled.get())
        val fresh = registry.begin("task")
        val current = Request()
        assertTrue(registry.track(fresh, current) { fresh.isCancelled })
        registry.finish(old)
        registry.cancel("task")
        assertTrue(current.cancelled.get())
        registry.finish(fresh)
    }

    @Test fun delayedOldSessionCancellationCannotCancelNewAttemptCalls() {
        val registry = DownloadRequestRegistry<Request> { it.cancelled.set(true) }
        val old = registry.begin("task")
        val oldCall = Request()
        assertTrue(registry.track(old, oldCall) { old.isCancelled })

        registry.cancelSession(old)
        assertTrue(oldCall.cancelled.get())
        val fresh = registry.begin("task")
        val freshCall = Request()
        assertTrue(registry.track(fresh, freshCall) { fresh.isCancelled })

        // Models an old helper reaching its cancellation path after a rapid resume/retry.
        registry.cancelSession(old)
        assertFalse("late old-session cancel must not reach the new call", freshCall.cancelled.get())
        assertTrue(registry.track(fresh, Request()) { fresh.isCancelled })

        registry.cancel("task")
        assertTrue("explicit task cancel still reaches all active attempts", freshCall.cancelled.get())
        registry.finish(old)
        registry.finish(fresh)
    }

    @Test fun cancellationDuringRegistrationCannotOrphanTheNextCall() {
        val registry = DownloadRequestRegistry<Request> { it.cancelled.set(true) }
        val session = registry.begin("task")
        val registered = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val call = Request()
        val cancel = Thread { registered.await(); registry.cancel("task"); cancelStarted.countDown() }
        cancel.start()
        assertFalse(registry.track(session, call) {
            registered.countDown()
            assertTrue(cancelStarted.await(2, TimeUnit.SECONDS))
            session.isCancelled
        })
        cancel.join(2000)
        assertFalse(cancel.isAlive)
        assertTrue(call.cancelled.get())
        val late = Request()
        assertFalse(registry.track(session, late) { session.isCancelled })
        assertTrue(late.cancelled.get())
        registry.finish(session)
    }

    @Test fun cancelAllAlsoInvalidatesSessionsWithNoCallsAndBlankTaskIds() {
        val registry = DownloadRequestRegistry<Request> { it.cancelled.set(true) }
        val sessions = listOf(registry.begin("a"), registry.begin(""))
        registry.cancelAll()
        sessions.forEach { session ->
            assertTrue(session.isCancelled)
            val call = Request()
            assertFalse(registry.track(session, call) { session.isCancelled })
            assertTrue(call.cancelled.get())
            registry.finish(session)
        }
    }

    @Test fun cancelTrackedCallAbortsAnInFlightLoopbackHttpRequest() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().orEmpty().isNotEmpty()) Unit
                    accepted.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                    runCatching {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {
                accepted.countDown()
            }
        }
        serverThread.start()
        val registry = DownloadRequestRegistry<okhttp3.Call> { it.cancel() }
        val session = registry.begin("loopback")
        val call = OkHttpClient().newCall(HttpRequest.Builder().url("http://127.0.0.1:${server.localPort}/media").build())
        assertTrue(registry.track(session, call) { session.isCancelled })
        val requestDone = CountDownLatch(1)
        val requestThread = Thread {
            try { call.execute().use { it.body?.bytes() } } catch (_: IOException) { Unit }
            finally { requestDone.countDown() }
        }
        requestThread.start()
        try {
            assertTrue("fake media endpoint accepted request", accepted.await(3, TimeUnit.SECONDS))
            registry.cancel("loopback")
            assertTrue("cancellation closes the active HTTP call", call.isCanceled())
            assertTrue("blocked local request exits after cancel", requestDone.await(3, TimeUnit.SECONDS))
        } finally {
            releaseResponse.countDown()
            registry.finish(session)
            server.close()
            requestThread.join(3_000)
            serverThread.join(3_000)
        }
    }

    @Test fun untrackingLastCallNeverDropsAConcurrentRegistration() {
        val registry = DownloadRequestRegistry<Request> { it.cancelled.set(true) }
        repeat(500) {
            val session = registry.begin("task")
            val first = Request()
            val next = Request()
            assertTrue(registry.track(session, first) { false })
            val start = CountDownLatch(1)
            val t = Thread { start.await(); registry.untrack(session, first) }
            t.start(); start.countDown()
            assertTrue(registry.track(session, next) { false })
            t.join()
            registry.cancel("task")
            assertTrue(next.cancelled.get())
            registry.finish(session)
        }
    }
}
