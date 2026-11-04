package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WEB_DAV_NO_SAVED_CREDENTIALS_MESSAGE
import xyz.cncnlp.soundwave.sync.WEB_DAV_SAVED_PASSWORD_UNAVAILABLE_MESSAGE
import xyz.cncnlp.soundwave.sync.WebDavCredentialVault
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentials
import xyz.cncnlp.soundwave.sync.WebDavSavedSession
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import xyz.cncnlp.soundwave.sync.isWebDavAuthRejection
import xyz.cncnlp.soundwave.sync.maskWebDavUsername
import xyz.cncnlp.soundwave.sync.webDavSavedAccountLabel
import java.nio.charset.StandardCharsets

/** 1.0.656 #13：首次登录同步成功后保存凭据，之后只需点「同步」。 */
class WebDavSavedSessionTest {
    private class MemoryVault(var state: WebDavSavedCredentialState = WebDavSavedCredentialState.None) : WebDavCredentialVault {
        var saveSucceeds = true
        var saves = 0
        override fun read(): WebDavSavedCredentialState = state
        override fun save(url: String, username: String, password: String): Boolean {
            saves++
            if (saveSucceeds) state = WebDavSavedCredentialState.Ready(WebDavSavedCredentials(url, username, password))
            return saveSucceeds
        }
        override fun clear(): Boolean { state = WebDavSavedCredentialState.None; return true }
    }

    private val calls = mutableListOf<Triple<String, String, String>>()
    private fun runner(result: WebDavSyncResult): (String, String, String) -> WebDavSyncResult = { u, n, p ->
        calls += Triple(u, n, p)
        result
    }
    private val ok = WebDavSyncResult.Success(LocalStore.UserBundle(), remoteFileCreated = true)

    @Test
    fun firstSuccessfulLoginSyncPersistsCredentialsSoNextSyncNeedsNoForm() {
        val vault = MemoryVault()
        val session = WebDavSavedSession(vault, runner(ok))
        assertFalse(session.hasSavedWebDavCredentials())

        val first = session.loginAndSync("https://dav.example.com/dav/", "me@example.com", "app-pw")
        assertTrue(first.result is WebDavSyncResult.Success)
        assertEquals(true, first.credentialsSaved)
        assertTrue(session.hasSavedWebDavCredentials())

        val second = session.syncWithSaved()
        assertTrue(second is WebDavSyncResult.Success)
        assertEquals(Triple("https://dav.example.com/dav/", "me@example.com", "app-pw"), calls.last())
        assertEquals(2, calls.size)
    }

    @Test
    fun failedLoginNeverPersistsThePassword() {
        val vault = MemoryVault()
        val session = WebDavSavedSession(vault, runner(WebDavSyncResult.Failure("WebDAV 登录失败（认证错误，HTTP 401）")))
        val run = session.loginAndSync("https://dav.example.com/dav/", "me", "bad")
        assertTrue(run.result is WebDavSyncResult.Failure)
        assertNull(run.credentialsSaved)
        assertEquals(0, vault.saves)
        assertFalse(session.hasSavedWebDavCredentials())
        assertTrue(isWebDavAuthRejection(run.result))
    }

    @Test
    fun saveFailureIsReportedButSyncResultIsKept() {
        val vault = MemoryVault().apply { saveSucceeds = false }
        val run = WebDavSavedSession(vault, runner(ok)).loginAndSync("https://dav.example.com/dav/", "me", "pw")
        assertTrue(run.result is WebDavSyncResult.Success)
        assertEquals(false, run.credentialsSaved)
    }

    @Test
    fun unreadableOrMissingSavedPasswordSendsNoRequest() {
        val unreadable = WebDavSavedSession(
            MemoryVault(WebDavSavedCredentialState.PasswordUnavailable("https://dav.example.com/dav/", "me", transient = true)),
            runner(ok)
        )
        assertFalse(unreadable.hasSavedWebDavCredentials())
        assertEquals(WebDavSyncResult.Failure(WEB_DAV_SAVED_PASSWORD_UNAVAILABLE_MESSAGE), unreadable.syncWithSaved())

        val none = WebDavSavedSession(MemoryVault(), runner(ok))
        assertEquals(WebDavSyncResult.Failure(WEB_DAV_NO_SAVED_CREDENTIALS_MESSAGE), none.syncWithSaved())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun forgetClearsSavedCredentials() {
        val vault = MemoryVault(WebDavSavedCredentialState.Ready(WebDavSavedCredentials("https://dav.example.com/dav/", "me", "pw")))
        val session = WebDavSavedSession(vault, runner(ok))
        assertTrue(session.hasSavedWebDavCredentials())
        assertTrue(session.forget())
        assertFalse(session.hasSavedWebDavCredentials())
        assertEquals(WebDavSyncResult.Failure(WEB_DAV_NO_SAVED_CREDENTIALS_MESSAGE), session.syncWithSaved())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun vaultReadFailureFallsBackToLoginForm() {
        val throwing = object : WebDavCredentialVault {
            override fun read(): WebDavSavedCredentialState = throw IllegalStateException("prefs gone")
            override fun save(url: String, username: String, password: String) = false
            override fun clear() = false
        }
        val session = WebDavSavedSession(throwing, runner(ok))
        assertFalse(session.hasSavedWebDavCredentials())
        assertEquals(WebDavSavedCredentialState.None, session.state())
    }

    @Test
    fun savedAccountLabelShowsHostAndMaskedUserOnly() {
        val label = webDavSavedAccountLabel("https://dav.jianguoyun.com/dav/private-folder", "alice@qq.com")
        assertEquals("dav.jianguoyun.com · al•••@qq.com", label)
        assertFalse(label.contains("alice"))
        assertFalse(label.contains("private-folder"))
        assertEquals("a•••", maskWebDavUsername("ab"))
        assertEquals("•••", maskWebDavUsername(" "))
    }

    @Test
    fun endToEndSecondSyncReusesSavedCredentialsWithRealClient() {
        var remote: String? = null
        val seen = mutableListOf<WebDavHttpRequest>()
        val transport = WebDavTransport { request ->
            seen += request
            when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "MKCOL" -> WebDavHttpResponse(201)
                "GET" -> remote?.let { WebDavHttpResponse(200, it, "\"v${seen.size}\"") } ?: WebDavHttpResponse(404)
                "PUT" -> { remote = request.body?.toString(StandardCharsets.UTF_8); WebDavHttpResponse(201) }
                else -> WebDavHttpResponse(405)
            }
        }
        val vault = MemoryVault()
        val local = LocalStore.UserBundle(favs = mutableListOf(Song(id = "s1", name = "s1", singer = "A", source = "wy", syncAddedAt = 100L)))
        val session = WebDavSavedSession(vault) { url, username, password ->
            WebDavSyncClient(transport).sync(
                url, username, password, local,
                gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
            )
        }

        val first = session.loginAndSync("https://dav.example.com/dav", "me", "app-pw")
        assertTrue(first.result.toString(), first.result is WebDavSyncResult.Success)
        val firstRequests = seen.size
        val second = session.syncWithSaved()
        assertTrue(second.toString(), second is WebDavSyncResult.Success)
        assertTrue(seen.size > firstRequests)
        assertTrue(seen.drop(firstRequests).all { it.username == "me" && it.password == "app-pw" })
    }
}
