package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import xyz.cncnlp.soundwave.sync.*

/** Runs against the real store with a host preferences/crypto adapter in the no-SDK harness. */
@RunWith(AndroidJUnit4::class)
class WebDavCredentialPersistenceTest {
    private var before: Map<String, *> = emptyMap<String, String>()
    private val prefs get() = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("soundwave_webdav", 0)
    @Before fun preserveExistingCredentials() { before = prefs.all.toMap(); prefs.edit().clear().commit() }
    @After fun restoreExistingCredentials() {
        val editor = prefs.edit().clear()
        before.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
    }
    @Test fun savedCredentialsSurviveVaultRecreationAndSavedSyncReadsVault() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(WebDavCredentialStore.save(context, "https://dav.example/dav/", "Alice", "secret"))
        val restored = WebDavCredentialStore.loadState(context) as WebDavSavedCredentialState.Ready
        assertEquals("Alice", restored.credentials.username)
        assertEquals("secret", restored.credentials.password)
        assertFalse(context.getSharedPreferences("soundwave_webdav", 0).all.values.contains("secret"))
        var used: String? = null
        val vault = object : WebDavCredentialVault {
            override fun read() = WebDavCredentialStore.loadState(context)
            override fun save(u: String, n: String, p: String) = WebDavCredentialStore.save(context, u, n, p)
            override fun clear() = WebDavCredentialStore.clear(context)
        }
        val session = WebDavSavedSession(vault) { _, user, _ -> used = user; WebDavSyncResult.Success(LocalStore.UserBundle(), true) }
        session.syncWithSaved()
        assertEquals("Alice", used)
        assertTrue(session.forget())
        used = null
        assertTrue(session.syncWithSaved() is WebDavSyncResult.Failure)
        assertNull(used)
    }

    @Test fun invalidEndpointAndEmptyCredentialsCannotReplaceExistingOwner() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(WebDavCredentialStore.save(context, "https://dav.example/dav/", "Alice", "secret"))
        for ((url, user, pass) in listOf(
            Triple("http://dav.example/", "Bob", "pw"),
            Triple("https://inline:secret@dav.example/", "Bob", "pw"),
            Triple("https://dav.example/", "", "pw"),
            Triple("https://dav.example/", "Bob", "")
        )) assertFalse(WebDavCredentialStore.save(context, url, user, pass))
        assertEquals("Alice", (WebDavCredentialStore.loadState(context) as WebDavSavedCredentialState.Ready).credentials.username)
    }

    @Test fun credentialDebugRepresentationNeverContainsPassword() {
        val credentials = WebDavSavedCredentials("https://dav.example/", "Alice", "never-log-this")
        assertFalse(credentials.toString().contains("never-log-this"))
        assertFalse(WebDavSavedCredentialState.Ready(credentials).toString().contains("never-log-this"))
    }
}
