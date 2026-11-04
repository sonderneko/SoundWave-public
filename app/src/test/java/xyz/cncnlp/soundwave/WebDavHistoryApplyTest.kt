package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.AccountDataIdentity
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebDavHistoryApplyTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private lateinit var localBefore: Map<String, *>
    private lateinit var accountBefore: Map<String, *>
    private lateinit var authBefore: Map<String, *>

    @Before
    fun isolatePreferences() {
        localBefore = LocalStore.prefs(context).all.toMap()
        accountBefore = accountPreferences().all.toMap()
        authBefore = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).all.toMap()
        LocalStore.prefs(context).edit().clear().commit()
        accountPreferences().edit().clear().commit()
        context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun restorePreferences() {
        restore(LocalStore.prefs(context), localBefore)
        restore(accountPreferences(), accountBefore)
        restore(context.getSharedPreferences("soundwave", Context.MODE_PRIVATE), authBefore)
    }

    @Test
    fun playbackRecordedWhileWebDavPutIsInFlightIsAppliedToBothHistoryStores() = runTest {
        val account = "fake-user"
        context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).edit()
            .putBoolean("logged_in", true).putString("user", account).commit()
        accountPreferences().edit().putBoolean("logged_in", true).putString("user", account).commit()
        val now = System.currentTimeMillis()
        val bundleSong = song("bundle-before", playedAt = now - 200L)
        val independentSong = song("independent-before", playedAt = now - 300L)
        val duringSyncSong = song("played-during-sync", playedAt = now)
        val controller = CloudSyncController(
            context = context,
            scope = this,
            prefs = accountPreferences(),
            getScripts = { emptyList() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler)
        )
        val initialHistory = listOf(bundleSong, independentSong)
        LocalStore.saveWithIndependentHistory(
            context, LocalStore.UserBundle(history = initialHistory.toMutableList()), initialHistory, account
        )
        controller.userBundle = LocalStore.load(context)
        controller.history = LocalStore.loadHistory(context)
        val requests = mutableListOf<WebDavHttpRequest>()
        val transport = WebDavTransport { request ->
            requests += request
            when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "GET" -> WebDavHttpResponse(404)
                "PUT" -> {
                    // This is an in-memory fake transport; emulate a playback callback while PUT is pending.
                    writeIndependentHistory(listOf(duringSyncSong, independentSong))
                    WebDavHttpResponse(201)
                }
                else -> WebDavHttpResponse(405)
            }
        }

        val result = WebDavSyncClient(
            transport = transport,
            localPlaybackHistory = { LocalStore.loadHistory(context) },
            accountDataKey = AccountDataIdentity.key(account),
            accountDataCurrent = { true }
        ).sync(
            url = "https://fake.invalid/dav",
            username = account,
            password = "fake-password",
            local = controller.userBundle.syncSnapshot().copy(
                dailyListenStatsJson = "{}",
                dailyListenStatsOwnerV2 = AccountDataIdentity.key(account)
            ),
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        )

        assertTrue("$result", result is WebDavSyncResult.Success)
        val success = result as WebDavSyncResult.Success
        val ownerKey = AccountDataIdentity.key(account)
        assertEquals(ownerKey, success.bundle.dailyListenStatsOwnerV2)
        val uploaded = JSONObject(String(requireNotNull(requests.single { it.method == "PUT" }.body), StandardCharsets.UTF_8))
        assertEquals(ownerKey, uploaded.getString("owner"))
        val uploadedUser = uploaded.getJSONObject("user")
        val uploadedStats = uploadedUser.getJSONObject("dailyListenStatsV2")
        assertEquals(ownerKey, uploadedStats.getString("owner"))
        assertEquals(0, uploadedStats.getJSONObject("days").length())
        assertFalse(uploadedUser.has("dailyListenStats"))
        val uploadedIds = uploadedUser
            .getJSONArray("history")
            .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") } }
        assertEquals(listOf("bundle-before", "independent-before"), uploadedIds)

        controller.applyWebDavBundleLocally(success.bundle)

        val expectedIds = listOf("played-during-sync", "bundle-before", "independent-before")
        assertEquals(expectedIds, controller.history.map { it.id })
        assertEquals(expectedIds, LocalStore.loadHistory(context).map { it.id })
        assertEquals(expectedIds, LocalStore.load(context).history.map { it.id })
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PUT"), requests.map { it.method })
        assertTrue(requests.none { it.url.endsWith("/soundwave-sync.json") })
    }

    private fun writeIndependentHistory(songs: List<Song>) {
        val json = JSONArray().apply { songs.forEach { put(it.toJson()) } }.toString()
        LocalStore.prefs(context).edit().putString("play_history_v1", json).commit()
    }

    private fun song(id: String, playedAt: Long) = Song(
        id = id,
        name = id,
        singer = "Test Artist",
        source = "wy",
        playedAt = playedAt
    )

    private fun accountPreferences(): SharedPreferences =
        context.getSharedPreferences("webdav-history-apply-test-account", Context.MODE_PRIVATE)

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }
}
