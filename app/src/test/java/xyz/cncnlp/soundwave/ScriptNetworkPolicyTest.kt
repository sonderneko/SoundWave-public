package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ScriptNetworkPolicyTest {
    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun publicHttpsHostIsAllowedWhenDnsResolvesPublicly() {
        assertTrue(ScriptNetworkPolicy.isAllowedHttpsUrl("https://api.example.com/path") {
            arrayOf(ip("93.184.216.34"))
        })
    }

    @Test
    fun publicHttpScriptOriginRemainsEligibleAndSchemeLessUrlIsRejected() {
        assertTrue(ScriptNetworkPolicy.isAllowedScriptUrl("http://api.example.com/path") {
            arrayOf(ip("93.184.216.34"))
        })
        assertFalse(ScriptNetworkPolicy.isAllowedScriptUrl("//api.example.com/path") {
            arrayOf(ip("93.184.216.34"))
        })
        assertTrue(ScriptNetworkPolicy.isAllowedScriptUrl("HTTP://api.example.com/path") {
            arrayOf(ip("93.184.216.34"))
        })
    }

    @Test
    fun httpCredentialsAndLocalhostAreRejectedBeforeNetwork() {
        assertFalse(ScriptNetworkPolicy.isAllowedHttpsUrl("http://api.example.com") { emptyArray() })
        assertFalse(ScriptNetworkPolicy.isAllowedHttpsUrl("https://user:pass@api.example.com") { emptyArray() })
        assertFalse(ScriptNetworkPolicy.isAllowedHttpsUrl("https://localhost/test") { emptyArray() })
    }

    @Test
    fun dnsPrivateAndMetadataAddressesAreRejected() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.2", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "fe80::1")
            .forEach { address ->
                assertFalse("must reject $address", ScriptNetworkPolicy.isAllowedHttpsUrl("https://cdn.example.com/file") {
                    arrayOf(ip(address))
                })
            }
    }

    @Test
    fun privateLocalAndLinkLocalLiteralsAreRejectedBeforeNetwork() {
        val urls = listOf(
            "http://127.0.0.1/path", "https://10.1.2.3/path", "http://192.168.1.4/path",
            "https://[::1]/path", "http://[fe80::1]/path", "https://localhost/path",
            "http://api.localhost/path", "https://printer.local/path"
        )
        urls.forEach { url ->
            var resolverCalls = 0
            val result = ScriptNetworkPolicy.inspectScriptUrl(url) {
                resolverCalls++
                arrayOf(ip("93.184.216.34"))
            }
            assertNull("special literal/local name is not direct-eligible: $url", result.validatedTarget)
            assertFalse("special literal/local name gets no system-stack route: $url", result.systemStackDirect)
            assertEquals("literal/local-name checks must not resolve DNS: $url", 0, resolverCalls)
        }
    }

    @Test
    fun privateOrMixedDnsAnswersAreRejected() {
        val dnsAnswers = listOf(
            listOf("10.1.2.3"),
            listOf("169.254.169.254"),
            listOf("93.184.216.34", "192.168.1.5"),
            // Placeholder addresses only reclassify when they are the ENTIRE answer;
            // any mix with a real public/private address stays fully suppressed.
            listOf("198.18.0.1", "192.168.1.5"),
            listOf("198.18.0.1", "93.184.216.34"),
            // A v6 fake-IP answer never anchors a reclassification on its own,
            // and ULA outside the fc00::/18 fake pool stays private, not fake.
            listOf("fc00::1"),
            listOf("198.18.0.1", "fd00::1"),
            listOf("198.18.0.1", "2001:4860:4860::8888")
        )
        dnsAnswers.forEach { answers ->
            var resolverCalls = 0
            val result = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") {
                resolverCalls++
                answers.map(::ip).toTypedArray()
            }
            assertNull("DNS answers $answers must not create a direct target", result.validatedTarget)
            assertFalse("DNS answers $answers must not create a system-stack route", result.systemStackDirect)
            assertEquals(1, resolverCalls)
        }
    }

    @Test
    fun specialUseIpv4AndIpv6RangesAreRejectedForDirectEgress() {
        // 198.18.0.0/15 is deliberately NOT in this DNS-answer list: as an entire
        // answer set it is a fake-IP placeholder, covered by
        // fakeIpPlaceholderDnsAnswersRouteViaSystemStackNeverPinned below. As a
        // literal URL host it remains fully rejected (asserted there too).
        val reserved = listOf(
            "0.0.0.0", "100.64.0.1", "192.0.0.9", "192.0.2.1", "192.88.99.1",
            "198.51.100.1", "203.0.113.1", "240.0.0.1", "255.255.255.255",
            "2001::1", "2001:2::1", "2001:db8::1", "2002::1", "3fff::1", "64:ff9b::1", "100::1"
        )
        reserved.forEach { address ->
            val result = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") {
                arrayOf(ip(address))
            }
            assertNull("reserved address must never be direct-eligible: $address", result.validatedTarget)
            assertFalse("reserved address must never take the system-stack route: $address", result.systemStackDirect)
        }

        val mappedBytes = ByteArray(16).apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
            this[12] = 93
            this[13] = 184.toByte()
            this[14] = 216.toByte()
            this[15] = 34
        }
        val mapped = java.net.Inet6Address.getByAddress(null, mappedBytes, -1)
        val mappedResult = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") { arrayOf(mapped) }
        assertNull(mappedResult.validatedTarget)
        assertFalse("IPv4-mapped IPv6 is special-use and takes no system-stack route", mappedResult.systemStackDirect)
        val mappedLiteral = ScriptNetworkPolicy.inspectScriptUrl("http://[::ffff:93.184.216.34]/file")
        assertNull("mapped IPv6 literal is rejected even for a public embedded IPv4", mappedLiteral.validatedTarget)
    }

    @Test
    fun fakeIpPlaceholderDnsAnswersRouteViaSystemStackNeverPinned() {
        // A TUN proxy resolver in fake-IP mode answers public hostnames solely from
        // 198.18.0.0/15. Such answers must never become a pinned connection target;
        // since H3 graduated, the request goes out through the system stack
        // instead, whose resolver returns the same placeholder answer for the
        // TUN layer to translate. There is no server relay anymore.
        val placeholderAnswers = listOf(
            listOf("198.18.0.1"),
            listOf("198.19.200.1"),
            listOf("198.18.0.1", "198.19.0.2"),
            // sing-box-style stacks answer AAAA from their fc00::/18 fake-IP
            // pool; next to a 198.18/15 anchor answer those are placeholders too.
            listOf("198.18.0.1", "fc00::1"),
            listOf("198.18.0.1", "fc00:2::5")
        )
        placeholderAnswers.forEach { answers ->
            val result = ScriptNetworkPolicy.inspectScriptUrl("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                answers.map(::ip).toTypedArray()
            }
            assertNull("placeholder answers must never be direct-eligible: $answers", result.validatedTarget)
            assertTrue("public hostname behind fake-IP DNS takes the system-stack route: $answers", result.systemStackDirect)
            assertFalse(
                "direct egress stays rejected for placeholder answers: $answers",
                ScriptNetworkPolicy.isAllowedScriptUrl("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                    answers.map(::ip).toTypedArray()
                }
            )
        }
        // A literal placeholder-range host is not a public hostname at all: the
        // literal branch keeps it fully rejected on every route.
        val literal = ScriptNetworkPolicy.inspectScriptUrl("http://198.18.0.1/path") {
            arrayOf(ip("93.184.216.34"))
        }
        assertNull(literal.validatedTarget)
        assertFalse("literal 198.18 host takes no system-stack route", literal.systemStackDirect)
    }

    @Test
    fun publicHttpAndHttpsKeepDirectEligibility() {
        listOf("http://api.example.com/path", "https://api.example.com/path").forEach { url ->
            var resolverCalls = 0
            val result = ScriptNetworkPolicy.inspectScriptUrl(url) {
                resolverCalls++
                arrayOf(ip("93.184.216.34"))
            }
            assertEquals("ordinary public URL remains direct-eligible: $url", "api.example.com", result.validatedTarget?.host)
            assertFalse("ordinary public URL does not take the system-stack route: $url", result.systemStackDirect)
            assertEquals(1, resolverCalls)
        }
    }

    @Test
    fun publicLiteralIpIsDirectEligibleWithoutDnsLookup() {
        var resolverCalls = 0
        val publicLiteral = ScriptNetworkPolicy.inspectScriptUrl("http://93.184.216.34/path") {
            resolverCalls++
            emptyArray()
        }
        assertEquals("93.184.216.34", publicLiteral.validatedTarget?.host)
        assertEquals(listOf(ip("93.184.216.34")), publicLiteral.validatedTarget?.addresses)
        assertEquals(0, resolverCalls)

        assertNull(ScriptNetworkPolicy.inspectScriptUrl("ftp://api.example.com/path").validatedTarget)
        assertNull(ScriptNetworkPolicy.inspectScriptUrl("https://user:pass@api.example.com/path").validatedTarget)
        val emptyDns = ScriptNetworkPolicy.inspectScriptUrl("https://api.example.com/path") { emptyArray() }
        assertNull("empty DNS answer yields no route", emptyDns.validatedTarget)
        assertFalse("empty DNS answer yields no system-stack route", emptyDns.systemStackDirect)
    }

    @Test
    fun publicLiteralHttpsAndIpv6RemainDirectEligible() {
        listOf("https://93.184.216.34/path", "http://[2606:4700:4700::1111]/path").forEach { url ->
            var resolutions = 0
            val inspected = ScriptNetworkPolicy.inspectScriptUrl(url) {
                resolutions++
                emptyArray()
            }
            assertTrue("public literal direct egress: $url", inspected.validatedTarget != null)
            assertEquals(0, resolutions)
        }
        assertNull(ScriptNetworkPolicy.inspectScriptUrl("http://[2606:4700:4700::1111%25eth0]/path").validatedTarget)
    }

    @Test
    fun ambiguousNumericIpv4SpellingsAreRejectedBeforeDns() {
        val urls = listOf(
            "http://127.1/path",
            "https://2130706433/path",
            "http://0177.0.0.1/path",
            "https://0x7f000001/path",
            "http://0x7f.1/path",
            "https://127.000.0.1/path",
            "http://1.2.3.4.5/path"
        )
        urls.forEach { url ->
            var resolverCalls = 0
            val result = ScriptNetworkPolicy.inspectScriptUrl(url) {
                resolverCalls++
                arrayOf(ip("93.184.216.34"))
            }
            assertNull("ambiguous numeric host must not be direct-eligible: $url", result.validatedTarget)
            assertFalse("ambiguous numeric host gets no system-stack route: $url", result.systemStackDirect)
            assertEquals("ambiguous numeric host must be rejected before DNS: $url", 0, resolverCalls)
        }
    }

    @Test
    fun hostnameCaseTrailingDotAndMaximumPortNormalizeWithoutChangingPublicEligibility() {
        var resolvedHost = ""
        val result = ScriptNetworkPolicy.inspectScriptUrl("HTTPS://API.EXAMPLE.COM.:65535/path") { host ->
            resolvedHost = host
            arrayOf(ip("93.184.216.34"))
        }
        assertEquals("api.example.com", resolvedHost)
        assertEquals("api.example.com", result.validatedTarget?.host)
        assertFalse(result.systemStackDirect)
        assertTrue(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "HTTPS://API.EXAMPLE.COM.:443/b"))
    }

    @Test
    fun dnsFailureIsRejectedRatherThanFallingBackToUnverifiedHost() {
        assertFalse(ScriptNetworkPolicy.isAllowedHttpsUrl("https://cdn.example.com/file") { emptyArray() })
    }

    @Test
    fun validatedAddressesAreTheExactAddressesReturnedToTheHttpDnsLookup() {
        var resolverCalls = 0
        val validated = ScriptNetworkPolicy.validateScriptUrl("https://api.example.com/path") {
            resolverCalls++
            arrayOf(ip("93.184.216.34"))
        }!!

        assertEquals(listOf(ip("93.184.216.34")), validated.pinnedDns().lookup("api.example.com"))
        assertEquals("connection DNS must reuse preflight results, not resolve again", 1, resolverCalls)
    }

    @Test
    fun pinnedDnsNormalizesHostnameAndReturnsOnlyPrevalidatedAddresses() {
        var resolverCalls = 0
        val validated = ScriptNetworkPolicy.validateScriptUrl("https://api.example.com/path") {
            resolverCalls++
            arrayOf(ip("93.184.216.34"))
        }!!

        assertEquals(listOf(ip("93.184.216.34")), validated.pinnedDns().lookup("API.EXAMPLE.COM."))
        assertEquals("connection DNS must reuse preflight results", 1, resolverCalls)
    }

    @Test
    fun aValidatedDnsAnswerCannotBeReusedForAnotherHostname() {
        val validated = ScriptNetworkPolicy.validateScriptUrl("https://api.example.com/path") {
            arrayOf(ip("93.184.216.34"))
        }!!
        try {
            validated.pinnedDns().lookup("other.example.com")
            throw AssertionError("pinned DNS must reject a hostname mismatch")
        } catch (_: java.net.UnknownHostException) {
            // expected
        }
    }

    @Test
    fun disallowedDnsAnswersNeverProduceAValidatedConnectionTarget() {
        assertNull(ScriptNetworkPolicy.validateScriptUrl("https://api.example.com/path") {
            arrayOf(ip("93.184.216.34"), ip("127.0.0.1"))
        })
    }

    @Test
    fun headerWhitelistKeepsOnlyAllowedHeadersAndDropsBlankValues() {
        val filtered = ScriptNetworkPolicy.filterHeaders(
            mapOf(
                "Accept" to "application/json",
                "user-agent" to "LX/1.0",
                "REFERER" to "https://music.example.com/",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Cookie" to "session=secret",
                "Authorization" to "Bearer secret",
                "X-Custom" to "leak",
                "Referer" to "   "
            )
        )
        assertTrue(filtered.containsKey("Accept"))
        assertTrue(filtered.containsKey("user-agent"))
        assertTrue(filtered.containsKey("REFERER"))
        assertTrue(filtered.containsKey("Content-Type"))
        assertFalse(filtered.containsKey("Cookie"))
        assertFalse(filtered.containsKey("Authorization"))
        assertFalse(filtered.containsKey("X-Custom"))
        // 空值被丢弃，不允许占位绕过白名单
        assertFalse(filtered.values.any { it.isBlank() })
        assertEquals(4, filtered.size)
    }

    @Test
    fun redirectOriginComparisonUsesSchemeHostAndEffectivePort() {
        assertTrue(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "https://API.EXAMPLE.COM:443/b"))
        assertTrue(ScriptNetworkPolicy.isSameOrigin("HTTPS://API.example.com/a", "https://api.example.com:443/b"))
        assertTrue(ScriptNetworkPolicy.isSameOrigin("http://api.example.com/a", "http://api.example.com:80/b"))
        assertFalse(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "http://api.example.com/a"))
        assertFalse(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "https://other.example.com/a"))
        assertFalse(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "https://api.example.com:8443/a"))
    }

    @Test
    fun redirectHopLimitRejectsTheFifthHop() {
        assertTrue(ScriptNetworkPolicy.shouldFollowRedirect(302, sameOrigin = true, hasNonEmptyBody = false, hop = 3))
        assertFalse(ScriptNetworkPolicy.shouldFollowRedirect(302, sameOrigin = true, hasNonEmptyBody = false, hop = 4))
    }

    @Test
    fun crossOriginRedirectStripsCredentialLikeHeadersCaseInsensitively() {
        val names = listOf(
            "Authorization", "COOKIE", "Proxy-Authorization", "X-TOKEN", "x-Card-Key",
            "X-Api-Key", "X-Request-Key", "X-Auth-Secret", "Accept", "Content-Type"
        )
        val stripped = ScriptNetworkPolicy.redirectCredentialHeaders(names, sameOrigin = false)
        listOf("Authorization", "COOKIE", "Proxy-Authorization", "X-TOKEN", "x-Card-Key", "X-Api-Key", "X-Request-Key", "X-Auth-Secret")
            .forEach { assertTrue("must strip $it", it in stripped) }
        assertFalse("Accept is not a credential", "Accept" in stripped)
        assertFalse("Content-Type is not a credential", "Content-Type" in stripped)
        assertTrue(ScriptNetworkPolicy.redirectCredentialHeaders(names, sameOrigin = true).isEmpty())
    }

    @Test
    fun crossOrigin307And308DoNotReplayNonEmptyBodies() {
        for (status in listOf(307, 308)) {
            assertFalse(ScriptNetworkPolicy.shouldFollowRedirect(status, sameOrigin = false, hasNonEmptyBody = true, hop = 0))
            assertTrue(ScriptNetworkPolicy.shouldFollowRedirect(status, sameOrigin = true, hasNonEmptyBody = true, hop = 0))
            assertTrue(ScriptNetworkPolicy.shouldFollowRedirect(status, sameOrigin = false, hasNonEmptyBody = false, hop = 0))
        }
    }

    @Test
    fun cleartextCredentialGuardDefaultsOffAndPreservesLegacyHttpBehavior() {
        val decision = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "http://api.example.com/path", listOf("X-Token"), enabled = false
        )
        assertFalse(decision.blocked)
    }

    @Test
    fun enabledGuardRejectsHttpWithEveryAllowedSensitiveTokenOrKeyHeader() {
        listOf("X-Token", "X-Request-Key", "X-Card-Key").forEach { header ->
            val decision = ScriptNetworkPolicy.assessCleartextCredentialRequest(
                "http://api.example.com/path", listOf(header), enabled = true
            )
            assertTrue("must reject $header", decision.blocked)
        }
    }

    @Test
    fun enabledGuardAllowsPublicHttpWithoutSensitiveHeadersAndHttpsWithSensitiveHeaders() {
        val ordinaryHttp = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "http://api.example.com/path", listOf("Accept", "Content-Type"), enabled = true
        )
        assertFalse(ordinaryHttp.blocked)

        val httpsWithToken = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "https://api.example.com/path", listOf("X-Token", "X-Request-Key", "X-Card-Key"), enabled = true
        )
        assertFalse(httpsWithToken.blocked)
    }

    @Test
    fun guardRecognizesNormalizedAllowedHeadersButNotNonAllowlistedKeyNames() {
        val normalizedSensitive = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "HTTP://api.example.com/path", listOf(" x-ToKeN "), enabled = true
        )
        assertTrue(normalizedSensitive.blocked)

        val nonAllowlisted = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "http://api.example.com/path", listOf("X-Custom-Key"), enabled = true
        )
        assertFalse(nonAllowlisted.blocked)
    }

    @Test
    fun redirectToHttpIsBlockedWhenAnOutgoingSensitiveHeaderSurvives() {
        val decision = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "http://api.example.com/redirected", listOf("X-Request-Key"), enabled = true
        )
        assertTrue(decision.blocked)
    }

    @Test
    fun httpsToHttpRedirectRemainsForbiddenAfterCrossOriginCredentialsAreStripped() {
        val originalHeaders = listOf("X-Token", "X-Request-Key", "X-Card-Key", "Accept")
        val stripped = ScriptNetworkPolicy.redirectCredentialHeaders(originalHeaders, sameOrigin = false)
        val nextOutgoingHeaders = originalHeaders.filterNot { it in stripped }
        val decision = ScriptNetworkPolicy.assessCleartextCredentialRequest(
            "http://other.example.com/path", nextOutgoingHeaders, enabled = true
        )

        assertTrue(stripped.containsAll(listOf("X-Token", "X-Request-Key", "X-Card-Key")))
        assertEquals(listOf("Accept"), nextOutgoingHeaders)
        assertFalse("credential guard alone does not identify a credential-free downgrade", decision.blocked)
        assertTrue(ScriptNetworkPolicy.isHttpsDowngrade("https://api.example.com/path", "http://other.example.com/path"))
        assertFalse(ScriptNetworkPolicy.isHttpsDowngrade("http://api.example.com/path", "https://api.example.com/path"))
    }

    @Test
    fun restrictedRedirectTargetIsRejected() {
        assertNull(ScriptNetworkPolicy.validateScriptUrl("http://127.0.0.1/internal"))
        val originalUrlDecision = ScriptNetworkPolicy.inspectScriptUrl("https://api.example.com/redirect") {
            arrayOf(ip("93.184.216.34"))
        }
        assertTrue("the original public URL itself stays direct-eligible", originalUrlDecision.validatedTarget != null)
    }
}
