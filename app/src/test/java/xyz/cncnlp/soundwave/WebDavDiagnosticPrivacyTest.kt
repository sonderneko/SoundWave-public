package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentials
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavSyncRun

/** Synthetic values only: diagnostics are not a serialization or transport API. */
class WebDavDiagnosticPrivacyTest {
    private val url = "https://private-dav.invalid/private-personal-path?token=private-token"
    private val username = "private-user@example.invalid"
    private val password = "private-password-canary"
    private val etag = "private-etag-canary"
    private val payload = "private-sync-payload-canary"

    private fun assertRedacted(value: Any) {
        val diagnostic = "$value"
        listOf(url, "private-dav", "private-personal-path", "private-token", username, password, etag, payload)
            .forEach { secret -> assertFalse("diagnostic leaked a synthetic private field", diagnostic.contains(secret)) }
        assertTrue(diagnostic.contains("redacted"))
    }

    @Test fun requestHidesAllFieldsIncludingUntrustedMethodAndHeaders() {
        val body = payload.toByteArray()
        val request = WebDavHttpRequest(payload, url, username, password, body, payload, payload to etag)
        assertRedacted(request)
        assertRedacted(listOf(request))
        assertEquals(url, request.url)
        assertEquals(username, request.username)
        assertEquals(password, request.password)
        assertSame(body, request.body)
        assertEquals(payload to etag, request.conditionHeader)
        assertEquals(request, request.copy())
        assertRedacted(request.copy(password = password + "-changed"))
    }

    @Test fun responseRetainsStatusButHidesBodyAndEntityTag() {
        val response = WebDavHttpResponse(207, payload, etag)
        assertRedacted(response)
        assertEquals("WebDavHttpResponse(code=207, redacted)", response.toString())
        assertEquals(payload, response.body)
        assertEquals(etag, response.etag)
        assertEquals(response, response.copy())
    }

    @Test fun savedCredentialAndReadyStateRemainRedacted() {
        val credentials = WebDavSavedCredentials(url, username, password)
        assertRedacted(credentials)
        assertRedacted(WebDavSavedCredentialState.Ready(credentials))
        assertEquals(password, credentials.copy().password)
    }

    @Test fun unavailablePasswordStateRetainsRecoverableFieldsWithoutPrintingThem() {
        val state = WebDavSavedCredentialState.PasswordUnavailable(url, username, transient = true)
        assertRedacted(state)
        assertEquals("PasswordUnavailable(transient=true, redacted)", state.toString())
        assertEquals(url, state.url)
        assertEquals(username, state.username)
        assertTrue(state.transient)
        assertEquals(state, state.copy())
        assertRedacted(state.copy(transient = false))
    }

    @Test fun successfulSyncAndWrapperNeverPrintTheUserBundle() {
        val song = Song(id = payload, name = payload, singer = payload, source = "wy")
        val bundle = LocalStore.UserBundle(favs = mutableListOf(song))
        val result = WebDavSyncResult.Success(bundle, true, etag, true)
        assertRedacted(result)
        assertRedacted(WebDavSyncRun(result, credentialsSaved = true))
        assertSame(bundle, result.bundle)
        assertEquals(etag, result.etag)
        assertTrue(result.remoteFileCreated)
        assertTrue(result.migratedLegacyFile)
        assertEquals(result, result.copy())
    }
}
