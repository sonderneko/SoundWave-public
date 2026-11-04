package xyz.cncnlp.soundwave

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.sync.*

@RunWith(RobolectricTestRunner::class)
class WebDavAutoSyncStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("webdav_auto_sync_v1", 0)
    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun clean() { prefs.edit().clear().commit() }

    @Test fun absentStateIsOffAndNotManualReady() {
        assertEquals(WebDavAutoSyncState.DEFAULT, AndroidWebDavAutoSyncStore(context).read())
    }

    @Test fun uncertaintyAndExactBindingSurviveNewStoreInstanceWithoutPassword() {
        val binding = WebDavAutoSyncBinding.bind(AccountDataIdentity.key("Alice"), "https://fake.invalid/dav/", "User")
        val expected = WebDavAutoSyncState(binding, manualReady = true, enabled = true, uncertain = true)
        assertTrue(AndroidWebDavAutoSyncStore(context).writeAtomically(expected))
        assertEquals(expected, AndroidWebDavAutoSyncStore(context).read())
        assertFalse(prefs.getString("state", "")!!.contains("password"))
        assertNotEquals(binding.accountKey, AccountDataIdentity.key("alice"))
        assertTrue(AndroidWebDavAutoSyncStore(context).writeAtomically(WebDavAutoSyncState.DEFAULT))
        assertEquals(WebDavAutoSyncState.DEFAULT, AndroidWebDavAutoSyncStore(context).read())
    }

    @Test fun malformedOrUnqualifiedAuthorizationIsRejected() {
        prefs.edit().putString("state", "{\"enabled\":true}").commit()
        assertEquals(WebDavAutoSyncState.DEFAULT, AndroidWebDavAutoSyncStore(context).read())
        prefs.edit().putString("state", "not-json").commit()
        assertEquals(WebDavAutoSyncState.DEFAULT, AndroidWebDavAutoSyncStore(context).read())
    }

    @Test fun newProcessFenceNeverRestoresAnOldGrantEvenIfItsRevokeCouldNotPersist() {
        val binding = WebDavAutoSyncBinding.bind(AccountDataIdentity.key("Alice"), "https://fake.invalid/dav", "User")
        val old = WebDavAutoSyncState(binding, manualReady = true, enabled = true, uncertain = true)
        val failingStore = object : WebDavAutoSyncStore {
            override fun read() = old
            override fun writeAtomically(state: WebDavAutoSyncState) = false
        }
        val restored = SessionWebDavAutoSyncStore(failingStore).read()!!
        assertFalse(restored.enabled)
        assertTrue(restored.manualReady)
        assertTrue(restored.uncertain)
        assertEquals(binding, restored.binding)
    }

    @Test fun localEventSubscriptionIsExplicitAndDisposable() {
        val received = mutableListOf<String>()
        val unsubscribe = AccountOwnedDataChanges.subscribe { received += it }
        try {
            AccountOwnedDataChanges.localChange("exact-account")
            assertEquals(listOf("exact-account"), received)
        } finally { unsubscribe() }
        AccountOwnedDataChanges.localChange("other-account")
        assertEquals(listOf("exact-account"), received)
    }
}
