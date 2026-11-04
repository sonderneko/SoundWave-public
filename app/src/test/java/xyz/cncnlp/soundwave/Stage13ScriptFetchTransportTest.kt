package xyz.cncnlp.soundwave

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Independent transport contract: no Context, WebView, real sockets or account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage13ScriptFetchTransportTest {
    private class Fixture(var guard: Boolean = false, val respond: (Request, Fixture) -> Response) : ScriptFetchObserver {
        val requests = mutableListOf<Request>()
        val failures = mutableListOf<Pair<String, String>>()
        val headers = linkedMapOf<String, String>()
        val calls = linkedMapOf<String, Call>()
        var started = 0
        var finished = 0
        var cancelled = false
        var cancel: (() -> Unit)? = null
        val control = ScriptRequestScheduler.RequestControl(
            { cancelled }, { _, action -> if (cancelled) false else { cancel = action; true } }, { _ -> cancel = null })
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> requests.add(chain.request()); respond(chain.request(), this) }.build()
        val transport = ScriptFetchTransport({ client }, { guard }, this)
        override fun failure(capability: String, category: String) { failures.add(capability to category) }
        override fun responseHeaders(capability: String, headersJson: String) { headers[capability] = headersJson }
        override fun callStarted(capability: String, call: Call) { calls[capability] = call; started++ }
        override fun callFinished(capability: String, call: Call?) {
            if (call == null) calls.remove(capability) else { calls.remove(capability, call); finished++ }
        }
        fun run(cap: String = "cap", url: String = "https://8.8.8.8/source", method: String = "GET", hdr: String = "{}", body: String? = null) =
            transport.fetch(cap, url, method, hdr, body, control)
        fun clean() { assertTrue(calls.isEmpty()); assertNull(cancel); assertEquals(started, finished) }
    }
    private fun response(r: Request, code: Int = 200, text: String = "ok", vararg headers: Pair<String, String>) =
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(text.toResponseBody()).apply { headers.forEach { header(it.first, it.second) } }.build()

    @Test fun ownerReceivesOnlySafeResponseHeadersUnderMatchingCapability() {
        val f = Fixture { r, _ -> response(r, 200, "body", "Content-Type" to "text/plain", "Set-Cookie" to "test=private", "ETag" to "fixture-version") }
        assertEquals("body", f.run("matching").body)
        assertEquals(setOf("matching"), f.headers.keys)
        val json = org.json.JSONObject(f.headers.getValue("matching"))
        assertEquals("fixture-version", json.getString("ETag")); assertFalse(f.headers.getValue("matching").contains("Set-Cookie", true))
        f.clean()
    }
    @Test fun cleartextCredentialGuardBlocksBeforeTransportAndReadsOwnerSetting() {
        val f = Fixture(true) { r, _ -> response(r) }
        val result = f.run(url = "http://8.8.8.8/api", hdr = "{\"X-Token\":\"fixture-token\"}")
        assertEquals(403, result.code); assertTrue(f.requests.isEmpty()); assertEquals("policy", f.failures.last().second)
        f.guard = false
        assertEquals(200, f.run(url = "http://8.8.8.8/api", hdr = "{\"X-Token\":\"fixture-token\"}").code)
        assertEquals(1, f.requests.size); f.clean()
    }
    @Test fun requestMethodBodyAndHeaderFilteringRemainUnchanged() {
        val f = Fixture { r, _ -> response(r) }
        assertEquals(200, f.run(method = "post", hdr = "{\"Host\":\"evil-fixture\",\"X-Client\":\"marker\"}", body = "a=b").code)
        val r = f.requests.single(); assertEquals("POST", r.method); assertNull(r.header("Host")); assertEquals("marker", r.header("X-Client"))
        assertTrue(r.body!!.contentType().toString().startsWith("application/x-www-form-urlencoded"))
        val buf = okio.Buffer(); r.body!!.writeTo(buf); assertEquals("a=b", buf.readUtf8()); f.clean()
    }
    @Test fun oversizedBodyIsBoundedAndClassifiedWithoutKeepingResponseContent() {
        val f = Fixture { r, _ -> response(r, text = "x".repeat(2 * 1024 * 1024 + 1)) }
        assertEquals(0, f.run().code); assertEquals("too_large", f.failures.last().second); f.clean()
    }
    @Test fun oversizedRequestBodyIsRejectedBeforeAHttpCallIsCreated() {
        val f = Fixture { r, _ -> response(r) }
        assertEquals("request_too_large", f.run(body = "x".repeat(1024 * 1024 + 1)).body)
        assertTrue(f.requests.isEmpty()); assertEquals("too_large", f.failures.last().second); f.clean()
    }
    @Test fun crossOriginRedirectDropsCredentialHeadersButKeepsSafeHeaders() {
        val f = Fixture { r, _ ->
            if (r.url.host == "8.8.8.8") response(r, 302, "", "Location" to "https://1.1.1.1/target")
            else response(r, 200, "redirected")
        }
        assertEquals("redirected", f.run(hdr = """{"X-Token":"fixture-token","X-Request-Key":"fixture-key","Accept":"audio/*"}""").body)
        assertEquals(2, f.requests.size)
        assertEquals("fixture-token", f.requests[0].header("X-Token"))
        assertEquals("fixture-key", f.requests[0].header("X-Request-Key"))
        assertNull(f.requests[1].header("X-Token")); assertNull(f.requests[1].header("X-Request-Key"))
        assertEquals("audio/*", f.requests[1].header("Accept")); f.clean()
    }
    @Test fun cancellationDuringTransportSkipsFailureClassification() {
        val f = Fixture { _, state -> state.cancelled = true; state.cancel!!.invoke(); throw IOException("controlled cancellation") }
        assertEquals(0, f.run().code); assertEquals(1, f.requests.size); assertTrue(f.failures.isEmpty()); f.clean()
    }
    @Test fun httpsDowngradeIsBlockedBeforeSecondRequest() {
        val f = Fixture { r, _ -> response(r, 302, "downgrade", "Location" to "http://1.1.1.1/target") }
        val result = f.run(hdr = "{\"X-Token\":\"fixture-token\"}")
        assertEquals(403, result.code)
        assertEquals(1, f.requests.size); assertEquals("https", f.requests.single().url.scheme)
        assertEquals("fixture-token", f.requests.single().header("X-Token")); f.clean()
    }
    @Test fun redirectCycleStopsAtFifthResponseAndReleasesEveryCall() {
        val f = Fixture { r, _ -> response(r, 302, "budget exhausted", "Location" to "/source") }
        assertEquals(302, f.run().code); assertEquals(5, f.requests.size); assertEquals(5, f.started)
        assertEquals(List(5) { "/source" }, f.requests.map { it.url.encodedPath }); f.clean()
    }
    @Test fun directFailureIsFinalAndNeverLeavesTheTargetHost() {
        val f = Fixture { _, _ -> throw IOException("controlled direct failure") }
        assertEquals(0, f.run().code)
        assertEquals(0, f.run("other").code)
        assertEquals(listOf("8.8.8.8", "8.8.8.8"), f.requests.map { it.url.host }); f.clean()
    }
}
