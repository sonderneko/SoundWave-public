package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
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

@RunWith(RobolectricTestRunner::class)
class ListenStatsAccountV2Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val stats: SharedPreferences get() = context.getSharedPreferences("listen_stats_v1", Context.MODE_PRIVATE)
    private val auth: SharedPreferences get() = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
    private val prefsField = ListenStats::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private var oldCachedPrefs: SharedPreferences? = null
    private lateinit var oldStats: Map<String, *>
    private lateinit var oldAuth: Map<String, *>

    @Before
    fun isolate() {
        oldCachedPrefs = prefsField.get(ListenStats) as? SharedPreferences
        prefsField.set(ListenStats, null)
        oldStats = stats.all.toMap()
        oldAuth = auth.all.toMap()
        assertTrue(stats.edit().clear().commit())
        assertTrue(auth.edit().clear().commit())
    }

    @After
    fun restore() {
        restorePrefs(stats, oldStats)
        restorePrefs(auth, oldAuth)
        prefsField.set(ListenStats, oldCachedPrefs)
    }

    @Test
    fun v1AccountDataIsQuarantinedAndPreservedWhenNewAccountFactsAreWritten() {
        val oldV1 = JSONObject().put("legacy-ambiguous-owner", "legacy facts").toString()
        assertTrue(stats.edit().putString("account_daily_listen_stats_v1", oldV1).commit())
        setLoggedIn("Alice")

        assertTrue(ListenStats.addDuration(context, song("new-alice-song"), 13L))

        assertEquals(oldV1, stats.getString("account_daily_listen_stats_v1", null))
        assertEquals("the pre-upgrade v1 account bytes are retained in the local archive", oldV1,
            stats.getString("listen_stats_v1_archive__account_daily_listen_stats_v1", null))
        val v2 = JSONObject(stats.getString("account_daily_listen_stats_v2", "{}")!!)
        assertEquals(setOf(AccountDataIdentity.key("Alice")), v2.keys().asSequence().toSet())
        assertEquals(13L, dailyTotal(ListenStats.accountDailyListenStatsForAccount(context, "Alice", "{}")))
        assertEquals("malformed legacy baseline must not erase Alice's own facts", 13L,
            dailyTotal(ListenStats.accountDailyListenStatsForAccount(context, "Alice", oldV1)))
    }

    @Test
    fun authenticatedFactsNeverEnterGuestAggregateOrLeakAcrossLogoutAndLogin() {
        assertTrue(ListenStats.addDuration(context, song("guest-legacy"), 5L))
        val guestBefore = stats.getString("daily_listen_stats_v1", "{}")
        assertEquals(5L, ListenStats.totalSec(context))

        setLoggedIn("Alice")
        assertTrue(ListenStats.addDuration(context, song("alice-private"), 11L))
        assertEquals("authenticated event must not append to the guest daily stream", guestBefore,
            stats.getString("daily_listen_stats_v1", null))
        assertEquals(11L, ListenStats.totalSec(context))
        assertEquals(listOf("wy:alice-private"), ListenStats.topSongs(context).map { it.key })
        assertEquals(11L, dailyTotal(ListenStats.localDailyListenStats(context)))

        clearProfile()
        assertEquals(5L, ListenStats.totalSec(context))
        assertEquals(listOf("wy:guest-legacy"), ListenStats.topSongs(context).map { it.key })
        assertEquals(5L, dailyTotal(ListenStats.localDailyListenStats(context)))

        setLoggedIn("alice")
        assertEquals("a different exact account has no access to Alice's facts", 0L, ListenStats.totalSec(context))
        assertTrue(ListenStats.topSongs(context).isEmpty())
        assertEquals(0L, dailyTotal(ListenStats.localDailyListenStats(context)))

        setLoggedIn("Alice")
        assertEquals(11L, ListenStats.totalSec(context))
        assertEquals(listOf("wy:alice-private"), ListenStats.topSongs(context).map { it.key })
    }

    @Test
    fun originalExpiredDailyBytesAreArchivedBeforeNormalRetentionPruning() {
        val oldMillis = System.currentTimeMillis() - 400L * 24L * 60L * 60L * 1000L
        val original = ListenStatsDailyStore.record("{}", song("expired"), "legacy-device", 17L, true, oldMillis)
        assertTrue(stats.edit().putString("daily_listen_stats_v1", original).commit())

        ListenStats.totalSec(context)

        assertEquals("archive must preserve exact original bytes", original,
            stats.getString("listen_stats_v1_archive__daily_listen_stats_v1", null))
        assertTrue("ordinary 365-day retention may prune the live v1 stream",
            stats.getString("daily_listen_stats_v1", "") != original)
    }

    @Test
    fun accountReadsAndWritesAreExactCaseAndRefuseOtherOrLoggedOutIdentities() {
        setLoggedIn("Alice")
        assertTrue(ListenStats.addDuration(context, song("alice-upper"), 11L))
        assertEquals(11L, dailyTotal(ListenStats.accountDailyListenStatsForAccount(context, "Alice", "{}")))
        assertEquals("a case-variant cannot read Alice's private bucket", "{}",
            ListenStats.accountDailyListenStatsForAccount(context, "alice", "{}"))

        setLoggedIn("alice")
        assertTrue(ListenStats.addDuration(context, song("alice-lower"), 7L))
        assertEquals(7L, dailyTotal(ListenStats.accountDailyListenStatsForAccount(context, "alice", "{}")))
        assertEquals("the authenticated lowercase account cannot project Alice's facts", "{}",
            ListenStats.accountDailyListenStatsForAccount(context, "Alice", "{}"))

        clearProfile()
        assertEquals("no-profile account-specific projection is empty", "{}",
            ListenStats.accountDailyListenStatsForAccount(context, "alice", "{}"))
        assertEquals("generic export fails closed with no profile", "{}", ListenStats.accountDailyListenStats(context))
        assertFalse(ListenStats.mergeAccountDailyListenStatsForAccount(context, "alice", "{}"))
    }

    @Test
    fun accountRemoteMergePreservesCloudDevicesWithoutTouchingGenericAggregateOrV1() {
        val now = System.currentTimeMillis()
        val oldV1 = "{\"unchanged\":\"v1\"}"
        assertTrue(stats.edit().putString("account_daily_listen_stats_v1", oldV1).commit())
        setLoggedIn("merge-owner")
        ListenStats.init(context)
        val guestDailyBefore = stats.getString("daily_listen_stats_v1", null)
        val guestTotalBefore = stats.getLong("total_sec", 0L)
        assertTrue(ListenStats.addDuration(context, song("local"), 8L))
        val remote = ListenStatsDailyStore.record("{}", song("remote"), "other-device", 21L, true, now)

        assertTrue(ListenStats.mergeAccountDailyListenStatsForAccount(context, "merge-owner", remote, now))

        val projected = ListenStats.accountDailyListenStatsForAccount(context, "merge-owner", "{}", now)
        val summary = ListenStatsDailyStore.summary(projected, now)
        assertEquals(29L, summary.totalSeconds)
        assertEquals(setOf("wy:local", "wy:remote"), summary.songs.keys)
        val localOnly = ListenStatsDailyStore.summary(ListenStats.localDailyListenStats(context, now), now)
        assertEquals(29L, localOnly.totalSeconds)
        assertEquals(oldV1, stats.getString("account_daily_listen_stats_v1", null))
        assertEquals("authenticated merge must not alter guest V1 daily facts", guestDailyBefore,
            stats.getString("daily_listen_stats_v1", null))
        assertEquals("authenticated merge must not alter guest V1 aggregate", guestTotalBefore,
            stats.getLong("total_sec", 0L))
    }

    /** 账号会话已删除：身份 = 本机资料名（prefs `user`）本身；无资料名即游客态。 */
    private fun setLoggedIn(user: String) {
        assertTrue(auth.edit().putString("user", user).commit())
    }

    private fun clearProfile() {
        assertTrue(auth.edit().remove("user").commit())
    }

    private fun song(id: String) = Song(id, id, "Artist", "wy")

    private fun dailyTotal(json: String) = ListenStatsDailyStore.summary(json, System.currentTimeMillis()).totalSeconds

    private fun restorePrefs(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
    }
}
