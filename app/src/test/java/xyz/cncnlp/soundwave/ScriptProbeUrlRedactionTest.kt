package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptProbeUrlRedactionTest {
    @Test
    fun serializedCacheUiAndExceptionDiagnosticsNeverExposeRawUrlCredentials() {
        val rawUrl = "https://alice:pass-123@cdn.example/private/signed/AbCdEf0123456789GhIjKlMnOpQrStUv/song.flac?token=secret-query&quality=320#private-fragment"
        val report = ScriptProbeCache.Report(
            scriptId = "url-redaction-test",
            scriptName = "URL 脱敏测试",
            attempts = listOf(
                ScriptProbeCache.Attempt(
                    platform = "wy",
                    quality = "flac",
                    outcome = ScriptProbeCache.Outcome.FAILED,
                    reason = "异常详情：$rawUrl",
                    urlHint = rawUrl
                )
            )
        )

        // save() persists Report.toJson() under the script ID; exercise that exact cache payload shape.
        val cachedJson = JSONObject().put(report.scriptId, report.toJson()).toString()
        val restored = ScriptProbeCache.Report.fromJson(JSONObject(cachedJson).getJSONObject(report.scriptId))
        val cachedAttempt = JSONObject(cachedJson).getJSONObject(report.scriptId).getJSONArray("a").getJSONObject(0)
        val formatted = ScriptProbeCache.format(restored)
        val expectedHint = "https://cdn.example/private/signed/__redacted__/song.flac?token=__redacted__&quality=__redacted__"

        assertEquals(expectedHint, cachedAttempt.getString("urlHint"))
        assertTrue(cachedAttempt.getString("reason").contains(expectedHint))
        assertTrue(formatted.contains(expectedHint))
        assertTrue(formatted.contains("cdn.example/private/signed/"))
        assertTrue(formatted.contains("song.flac"))
        listOf("alice", "pass-123", "AbCdEf0123456789GhIjKlMnOpQrStUv", "secret-query", "private-fragment").forEach { secret ->
            assertFalse("Raw credential/token leaked: $secret", cachedJson.contains(secret))
            assertFalse("Raw credential/token leaked: $secret", formatted.contains(secret))
        }

        val wouldBeExceptionLog = ScriptProbeCache.safeDiagnosticText("IOException while probing $rawUrl")
        assertTrue(wouldBeExceptionLog.contains(expectedHint))
        listOf("alice", "pass-123", "secret-query", "private-fragment").forEach { secret ->
            assertFalse("Raw URL component leaked to diagnostics: $secret", wouldBeExceptionLog.contains(secret))
        }

        val malformed = "https://user:password@invalid.example:bad/private?token=raw-value"
        assertEquals("http(s)://[地址已隐藏]", ScriptProbeCache.safeUrlSummary(malformed))
        assertFalse(ScriptProbeCache.safeUrlSummary(malformed).contains("password"))
        assertFalse(ScriptProbeCache.safeUrlSummary(malformed).contains("raw-value"))
    }
}
