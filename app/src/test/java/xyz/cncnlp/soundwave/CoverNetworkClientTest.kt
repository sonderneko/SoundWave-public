package xyz.cncnlp.soundwave

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class CoverNetworkClientTest {
    @Test
    fun refererMappingRecognizesPlatformDomainSuffixesButNotLookalikes() {
        assertEquals("https://music.163.com/", CoverNetworkClient.defaultRefererForHost("music.163.com"))
        assertEquals("https://www.kuwo.cn/", CoverNetworkClient.defaultRefererForHost("img1.kwcdn.kuwo.cn."))
        assertEquals("https://y.qq.com/", CoverNetworkClient.defaultRefererForHost("y.gtimg.cn"))
        assertEquals("https://music.migu.cn/", CoverNetworkClient.defaultRefererForHost("app.c.nf.migu.cn"))
        assertEquals("https://m.kugou.com/", CoverNetworkClient.defaultRefererForHost("m.kugou.com"))

        // A platform-name substring in an unrelated registrable domain must not select its Referer.
        assertEquals("https://music.163.com/", CoverNetworkClient.defaultRefererForHost("cdn.qq.com.attacker.test"))
        assertEquals("https://music.163.com/", CoverNetworkClient.defaultRefererForHost("kugou.com.attacker.test"))
        assertEquals("https://music.163.com/", CoverNetworkClient.defaultRefererForHost("migu.cn.attacker.test"))
    }

    @Test
    fun resolvesRelativeRedirectsAgainstCurrentUrl() {
        assertEquals(
            "https://cdn.example.test/covers/next.jpg",
            CoverNetworkClient.redirectTarget(
                "https://cdn.example.test/covers/current.jpg",
                "next.jpg",
                0
            )
        )
    }

    @Test
    fun allowsUpgradeButRejectsHttpsDowngrade() {
        assertEquals(
            "https://cdn.example.test/cover.jpg",
            CoverNetworkClient.redirectTarget("http://cdn.example.test/start", "https://cdn.example.test/cover.jpg", 0)
        )
        assertNull(
            CoverNetworkClient.redirectTarget("https://cdn.example.test/start", "http://cdn.example.test/cover.jpg", 0)
        )
    }

    @Test
    fun rejectsUnsupportedSchemesAndCredentialedTargets() {
        assertNull(CoverNetworkClient.redirectTarget("https://cdn.example.test/start", "file:///tmp/cover.jpg", 0))
        assertNull(
            CoverNetworkClient.redirectTarget(
                "https://cdn.example.test/start",
                "https://user:secret@cdn.example.test/cover.jpg",
                0
            )
        )
    }

    @Test
    fun enforcesFourRedirectsAndRequiresLocation() {
        assertEquals(
            "https://cdn.example.test/cover.jpg",
            CoverNetworkClient.redirectTarget("https://cdn.example.test/start", "/cover.jpg", 3)
        )
        assertNull(CoverNetworkClient.redirectTarget("https://cdn.example.test/start", "/cover.jpg", 4))
        assertNull(CoverNetworkClient.redirectTarget("https://cdn.example.test/start", null, 0))
        assertNull(CoverNetworkClient.redirectTarget("https://cdn.example.test/start", "   ", 0))
    }

    @Test
    fun rejectsMalformedCurrentOrRelativeUrls() {
        assertNull(CoverNetworkClient.redirectTarget("not a url", "/cover.jpg", 0))
        assertNull(CoverNetworkClient.redirectTarget("https://cdn.example.test/start", "http://[bad", 0))
    }

    @Test
    fun copyLimitedAcceptsExactByteLimitAndRejectsOneExtra() {
        val exact = ByteArray(128) { it.toByte() }
        val exactOutput = ByteArrayOutputStream()
        assertEquals(128L, CoverNetworkClient.copyLimited(ByteArrayInputStream(exact), exactOutput, 128L))
        assertArrayEquals(exact, exactOutput.toByteArray())

        val oversizedOutput = ByteArrayOutputStream()
        try {
            // The reader copies one 8 KiB chunk, then rejects the extra byte before writing it.
            CoverNetworkClient.copyLimited(ByteArrayInputStream(ByteArray(8193)), oversizedOutput, 8192L)
            fail("oversized response accepted")
        } catch (_: IOException) {
            // expected; caller discards the bounded partial staging output
        }
        assertEquals(8192, oversizedOutput.size())
    }

    @Test
    fun boundedReadersRejectNonPositiveLimits() {
        try {
            CoverNetworkClient.copyLimited(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 0L)
            fail("zero copy limit accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun streamsRedirectedResponseToFileAndDropsRefererAcrossOrigins() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network", ".part").apply { delete() }
        try {
            val port = server.port
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://cdn-b.example.test:$port/image"))
            server.enqueue(MockResponse().setBody("validated-cover"))
            val observedHosts = mutableListOf<String>()
            val resolver: (String) -> ValidatedScriptUrl? = { raw ->
                val uri = URI(raw)
                val host = uri.host?.lowercase()?.trimEnd('.')
                if (host == "cdn-a.example.test" || host == "cdn-b.example.test") {
                    observedHosts += host
                    ValidatedScriptUrl(host, listOf(InetAddress.getByName("127.0.0.1")))
                } else null
            }

            val succeeded = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://cdn-a.example.test:$port/start",
                destination = destination,
                refererForHost = { "https://music.example.test/" },
                targetResolver = resolver
            )

            assertTrue(succeeded)
            assertArrayEquals("validated-cover".toByteArray(), destination.readBytes())
            val first = server.takeRequest(2, TimeUnit.SECONDS)
            val second = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals("https://music.example.test/", first!!.getHeader("Referer"))
            assertNull(second!!.getHeader("Referer"))
            assertTrue(observedHosts.contains("cdn-a.example.test"))
            assertTrue(observedHosts.contains("cdn-b.example.test"))
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

    @Test
    fun redirectCycleStopsAfterInitialRequestAndFourRedirects() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network-cycle", ".part").apply { delete() }
        try {
            val port = server.port
            repeat(5) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop"))
            }
            val resolver: (String) -> ValidatedScriptUrl? = { raw ->
                val uri = URI(raw)
                val host = uri.host?.lowercase()?.trimEnd('.')
                if (host == "cdn-a.example.test") {
                    ValidatedScriptUrl(host, listOf(InetAddress.getByName("127.0.0.1")))
                } else null
            }

            val succeeded = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://cdn-a.example.test:$port/start",
                destination = destination,
                targetResolver = resolver
            )

            assertFalse(succeeded)
            assertEquals("a redirect cycle must stop at the configured five-request budget", 5, server.requestCount)
            assertEquals("/start", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            repeat(4) { assertEquals("/loop", server.takeRequest(2, TimeUnit.SECONDS)?.path) }
            assertFalse(destination.exists())
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

    @Test
    fun rejectsRedirectBeforeRequestingUnapprovedSecondHost() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network", ".part").apply { delete() }
        try {
            val port = server.port
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://blocked.example.test:$port/image"))
            val resolver: (String) -> ValidatedScriptUrl? = { raw ->
                val uri = URI(raw)
                if (uri.host == "cdn-a.example.test") {
                    ValidatedScriptUrl(uri.host, listOf(InetAddress.getByName("127.0.0.1")))
                } else null
            }

            val succeeded = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://cdn-a.example.test:$port/start",
                destination = destination,
                targetResolver = resolver
            )

            assertFalse(succeeded)
            assertEquals("/start", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertEquals(1, server.requestCount)
            assertFalse(destination.exists())
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

    @Test
    fun fakeIpPlaceholderOnlyAcceptsExclusivePlaceholderAnswers() {
        val ip = { value: String -> InetAddress.getByName(value) }
        assertTrue(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("198.18.0.1"))
        })
        assertTrue(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("198.18.244.11"), ip("198.19.255.254"))
        })
        assertTrue(CoverNetworkClient.isFakeIpPlaceholderOnly("http://y.gtimg.cn/cover.jpg") {
            arrayOf(ip("198.19.0.1"))
        })
        // sing-box-style AAAA answers from fc00::/18 count when a v4 placeholder anchors the set.
        assertTrue(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("198.18.0.1"), ip("fc00::1"))
        })
    }

    @Test
    fun fakeIpPlaceholderOnlyRejectsPublicPrivateAndMixedAnswers() {
        val ip = { value: String -> InetAddress.getByName(value) }
        // A healthy public answer takes the pinned path, never this fallback.
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("93.184.216.34"))
        })
        // Any mix — placeholder + public, placeholder + private — is a hard rejection.
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("198.18.0.1"), ip("93.184.216.34"))
        })
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("198.18.0.1"), ip("192.168.1.2"))
        })
        // ULA outside the fc00::/18 fake pool is private, not a placeholder.
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            arrayOf(ip("198.18.0.1"), ip("fd00::1"))
        })
        // Private, metadata, documentation and IPv6 answers on their own are never
        // placeholders (a v6 fake-pool answer needs a v4 placeholder anchor).
        listOf("10.1.2.3", "172.16.0.1", "192.168.1.2", "169.254.169.254", "192.0.2.1", "::1", "2001:db8::1", "fc00::1")
            .forEach { address ->
                assertFalse("must reject $address", CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
                    arrayOf(ip(address))
                })
            }
        // Empty or failing resolutions never qualify.
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") { emptyArray() })
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test/cover.jpg") {
            throw java.net.UnknownHostException("boom")
        })
    }

    @Test
    fun fakeIpPlaceholderOnlyRejectsLiteralsLocalNamesAndCredentialedUrls() {
        val ip = { value: String -> InetAddress.getByName(value) }
        val placeholder = { _: String -> arrayOf(ip("198.18.0.1")) }
        // A literal 198.18 host stays hard-rejected, exactly like the script policy.
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://198.18.0.5/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://93.184.216.34/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://[::1]/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://localhost/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://printer.local/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://user:secret@cdn.example.test/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("ftp://cdn.example.test/cover.jpg", placeholder))
        assertFalse(CoverNetworkClient.isFakeIpPlaceholderOnly("https://cdn.example.test:0/cover.jpg", placeholder))
    }

    @Test
    fun systemDnsFallbackFetchesWhenNoPinnedTargetExists() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network", ".part").apply { delete() }
        try {
            server.enqueue(MockResponse().setBody("placeholder-routed-cover"))
            val succeeded = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://localhost:${server.port}/image",
                destination = destination,
                targetResolver = { null },
                systemDnsFallback = { true }
            )
            assertTrue(succeeded)
            assertArrayEquals("placeholder-routed-cover".toByteArray(), destination.readBytes())
            assertEquals(1, server.requestCount)
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

    @Test
    fun noRouteExistsWhenNeitherPinnedNorFallbackApplies() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network", ".part").apply { delete() }
        try {
            val succeeded = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://localhost:${server.port}/image",
                destination = destination,
                targetResolver = { null },
                systemDnsFallback = { false }
            )
            assertFalse(succeeded)
            assertEquals(0, server.requestCount)
            assertFalse(destination.exists())
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

    @Test
    fun redirectAcrossFallbackHopsIsFollowedButRejectedWhenNextHopLosesFallback() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network", ".part").apply { delete() }
        try {
            val port = server.port
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:$port/image"))
            server.enqueue(MockResponse().setBody("redirected-cover"))
            val followed = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://localhost:$port/start",
                destination = destination,
                targetResolver = { null },
                systemDnsFallback = { true }
            )
            assertTrue(followed)
            assertArrayEquals("redirected-cover".toByteArray(), destination.readBytes())

            // Same setup, but the redirect target no longer qualifies: no second request.
            val secondDestination = File.createTempFile("cover-network", ".part").apply { delete() }
            try {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:$port/blocked"))
                val rejected = CoverNetworkClient.downloadToFileForTest(
                    rawUrl = "http://localhost:$port/start",
                    destination = secondDestination,
                    targetResolver = { null },
                    systemDnsFallback = { url -> url.endsWith("/start") }
                )
                assertFalse(rejected)
                assertEquals(3, server.requestCount)
                assertFalse(secondDestination.exists())
            } finally {
                secondDestination.delete()
            }
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

    @Test
    fun rejectsChunkedResponseThatExceedsFileLimit() {
        val server = MockWebServer()
        server.start()
        val destination = File.createTempFile("cover-network", ".part").apply { delete() }
        try {
            val port = server.port
            server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(ByteArray(129)), 17))
            val resolver: (String) -> ValidatedScriptUrl? = { raw ->
                val uri = URI(raw)
                if (uri.host == "cdn-a.example.test") {
                    ValidatedScriptUrl(uri.host, listOf(InetAddress.getByName("127.0.0.1")))
                } else null
            }

            val succeeded = CoverNetworkClient.downloadToFileForTest(
                rawUrl = "http://cdn-a.example.test:$port/image",
                destination = destination,
                maxBytes = 128,
                targetResolver = resolver
            )

            assertFalse(succeeded)
            assertFalse(destination.exists())
        } finally {
            destination.delete()
            server.shutdown()
        }
    }

}
