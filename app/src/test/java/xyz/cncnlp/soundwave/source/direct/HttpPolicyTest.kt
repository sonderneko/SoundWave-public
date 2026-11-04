package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpPolicyTest {
    @Test
    fun httpsIsAllowedAndKugouHttpExceptionIsExactAndPortBounded() {
        assertTrue(isCleartextApiAllowed("https://api.example.test/v1"))
        assertTrue(isCleartextApiAllowed("http://mobilecdn.kugou.com/api"))
        assertTrue(isCleartextApiAllowed("HTTP://MOBILECDNBJ.KUGOU.COM.:80/api"))

        assertFalse(isCleartextApiAllowed("http://other.kugou.com/api"))
        assertFalse(isCleartextApiAllowed("http://mobilecdn.kugou.com.evil.test/api"))
        assertFalse(isCleartextApiAllowed("http://mobilecdn.kugou.com:8080/api"))
        assertFalse(isCleartextApiAllowed("http://mobilecdn.kugou.com:443/api"))
    }

    @Test
    fun rejectsUnapprovedHttpAtTheNetworkInterceptorBeforeProceed() {
        val request = okhttp3.Request.Builder().url("http://evil.example/api").build()
        var proceeded = false
        val chain = java.lang.reflect.Proxy.newProxyInstance(
            okhttp3.Interceptor.Chain::class.java.classLoader,
            arrayOf(okhttp3.Interceptor.Chain::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "request" -> request
                "proceed" -> {
                    proceeded = true
                    throw AssertionError("network must not be reached")
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as okhttp3.Interceptor.Chain

        try {
            ApiUrlPolicyInterceptor().intercept(chain)
            throw AssertionError("expected API URL policy rejection")
        } catch (_: java.io.IOException) {
            assertFalse("rejected request must not reach network", proceeded)
        }
    }

    @Test
    fun malformedSchemesPortsAndUserInfoFailClosed() {
        listOf(
            "",
            "not a URL",
            "file:///tmp/cover",
            "ftp://mobilecdn.kugou.com/api",
            "https://user@api.example.test/v1",
            "http://user@mobilecdn.kugou.com/api",
            "http://mobilecdn.kugou.com:0/api",
            "http://mobilecdn.kugou.com:65536/api",
            "http://[broken/api",
        ).forEach { url -> assertFalse("must reject: $url", isCleartextApiAllowed(url)) }
    }
}
