package xyz.cncnlp.soundwave

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** Policy admission only; egress is direct-only (server relay retired). Never sends private-address network requests. */
class Round7NetworkBoundaryTest {
    private fun publicDns(host: String) = arrayOf(InetAddress.getByName("93.184.216.34"))

    @Test fun mixedCaseSchemesStillRejectPrivateTargets() {
        listOf("HTTP://127.0.0.1/a", "HtTp://169.254.169.254/latest", "HTTPS://localhost/a", "hTtPs://printer.local/a", "HTTP://[::1]/a").forEach {
            val result = ScriptNetworkPolicy.inspectScriptUrl(it, resolver = ::publicDns)
            assertNull("private target produced a route: $it", result.validatedTarget)
            assertFalse("private target allowed system-stack route: $it", result.systemStackDirect)
        }
    }

    @Test fun mixedCasePublicSchemesKeepPublicDirect() {
        listOf("HTTP", "Http", "HTTPS", "hTtPs").forEach {
            val url = "$it://cdn.example.com/a"
            assertNotNull(url, ScriptNetworkPolicy.inspectScriptUrl(url, resolver = ::publicDns).validatedTarget)
        }
    }

    @Test fun invalidSyntaxUnsupportedSchemesAndUserInfoAreRejected() {
        listOf("ftp://api.example.com/a", "file:///etc/hosts", "gopher://api.example.com/", "//api.example.com/a", "not a url", "https:/missing-host", "https://user:pass@api.example.com/a", "https://@api.example.com/a").forEach {
            assertNull("invalid target produced a route: $it", ScriptNetworkPolicy.inspectScriptUrl(it, resolver = ::publicDns).validatedTarget)
        }
    }

    @Test fun invalidPortsAreRejectedWithoutResolvingDns() {
        listOf("http://api.example.com:0/a", "https://api.example.com:65536/a", "https://api.example.com:999999/a").forEach { url ->
            var resolutions = 0
            val result = ScriptNetworkPolicy.inspectScriptUrl(url) { resolutions++; publicDns(it) }
            assertNull(result.validatedTarget)
            assertFalse(result.systemStackDirect)
            assertEquals(0, resolutions)
        }
    }

    @Test fun publicDnsFailureYieldsNoRoute() {
        listOf("http://api.example.com/a", "HTTPS://api.example.com/a").forEach { url ->
            val result = ScriptNetworkPolicy.inspectScriptUrl(url) { throw java.net.UnknownHostException("offline") }
            assertNull(result.validatedTarget)
            assertFalse(result.systemStackDirect)
        }
    }

    @Test fun mixedCasePrivateAndMixedDnsAreRejected() {
        listOf("HTTP", "HTTPS", "http", "https").forEach { scheme ->
            listOf(listOf("127.0.0.1"), listOf("93.184.216.34", "10.0.0.1")).forEach { ips ->
                val result = ScriptNetworkPolicy.inspectScriptUrl("$scheme://cdn.example.com/a") { ips.map { InetAddress.getByName(it) }.toTypedArray() }
                assertNull(result.validatedTarget)
                assertFalse("$scheme $ips", result.systemStackDirect)
            }
        }
    }

    @Test fun originEqualityNormalizesSchemeButNotProtocolOrNondefaultPort() {
        assertTrue(ScriptNetworkPolicy.isSameOrigin("HTTPS://API.EXAMPLE.COM/a", "https://api.example.com:443/b"))
        assertTrue(ScriptNetworkPolicy.isSameOrigin("HtTp://api.example.com/a", "http://api.example.com:80/b"))
        assertFalse(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "http://api.example.com/a"))
        assertFalse(ScriptNetworkPolicy.isSameOrigin("https://api.example.com/a", "https://api.example.com:444/a"))
    }

    @Test fun privateDnsNeverBecomesPinnedConnectionTarget() {
        val result = ScriptNetworkPolicy.inspectScriptUrl("HtTpS://cdn.example.com/a") { arrayOf(InetAddress.getByName("192.168.1.1")) }
        assertNull(result.validatedTarget)
        assertFalse(result.systemStackDirect)
    }
}
