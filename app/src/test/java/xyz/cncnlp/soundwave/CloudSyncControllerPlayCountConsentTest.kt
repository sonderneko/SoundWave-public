package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerPlayCountConsentTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val preferenceNames = listOf(
        LegacyUnassignedSnapshot.SOURCE_PREFERENCES,
        LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES,
        "soundwave",
        "soundwave_userdata",
        "listen_stats_v1"
    )
    private lateinit var previous: Map<String, Map<String, *>>
    private val cachedStatsField = ListenStats::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private var previousCachedStats: SharedPreferences? = null

    @Before
    fun isolateLocalAccountState() {
        previousCachedStats = cachedStatsField.get(ListenStats) as? SharedPreferences
        cachedStatsField.set(ListenStats, null)
        previous = preferenceNames.associateWith { prefs(it).all.toMap() }
        preferenceNames.forEach { prefs(it).edit().clear().commit() }
        prefs(LegacyUnassignedSnapshot.SOURCE_PREFERENCES).edit()
            .putString(LegacyUnassignedSnapshot.USER_DATA_KEY, "{\"favs\":[],\"pls\":[]}")
            .putString(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY, "[]")
            .commit()
        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(context))
    }

    @After
    fun restoreLocalAccountState() {
        previous.forEach { (name, values) -> restore(prefs(name), values) }
        cachedStatsField.set(ListenStats, previousCachedStats)
    }



    @Test
    fun webDavApplyIgnoresGuestStatsAndUndatedLegacyCounts() = runTest {
        val localSong = song("webdav-local")
        assertTrue(ListenStats.markPlay(context, localSong))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(this, dispatcher)
        controller.userBundle = LocalStore.load(context)

        controller.applyWebDavBundleLocally(LocalStore.UserBundle(
            songPlayCountCounters = linkedMapOf(localSong.key() to linkedMapOf("other-install" to 5L)),
            dailyListenStatsJson = dailyStats(localSong, "00000000-0000-0000-0000-000000000005", 17L),
            dailyListenStatsOwnerV2 = AccountDataIdentity.key("webdav-account")
        ))

        assertEquals(1, ListenStats.topSongs(context).single { it.key == localSong.key() }.cnt)
        assertFalse(controller.userBundle.dailyListenStatsJson.contains("other-install"))
        assertFalse(controller.userBundle.dailyListenStatsJson.contains("webdav-local"))
    }





    @Test
    fun webDavApplyNeverResetsAccumulatedTotalsAndPlaybackContinuesAddingToThem() = runTest {
        val account = "webdav-playback-${System.nanoTime()}"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(this, dispatcher)
        setSession(controller, account)
        val played = song("continuous-playback")
        val accumulator = ListenStatsAccumulator()
        val data = ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        fun tick(second: Int) {
            val step = accumulator.onTick(second * 1000L, played, true)
            step.markPlay?.let { assertTrue(data.markPlay(it)) }
            step.flushes.forEach { assertTrue(data.addDuration(it.song, it.seconds)) }
        }
        (0..10).forEach(::tick)
        assertEquals(10L, ListenStats.totalSec(context))
        val owner = AccountDataIdentity.key(account)
        val staleSnapshot = LocalStore.UserBundle(
            dailyListenStatsJson = ListenStats.localDailyListenStats(context),
            dailyListenStatsOwnerV2 = owner
        )
        controller.userBundle = LocalStore.load(context)

        // A further batch lands while the stale WebDAV request is in flight.
        (11..20).forEach(::tick)
        assertEquals(20L, ListenStats.totalSec(context))
        controller.applyWebDavBundleLocally(staleSnapshot)
        assertEquals("stale result cannot erase the latest local total", 20L, ListenStats.totalSec(context))
        controller.applyWebDavBundleLocally(LocalStore.UserBundle(dailyListenStatsOwnerV2 = owner))
        assertEquals("an empty remote is not a reset command", 20L, ListenStats.totalSec(context))
        (21..30).forEach(::tick)
        assertEquals(30L, ListenStats.totalSec(context))
        assertEquals(30L, ListenStats.todaySec(context))
        assertEquals(30L, ListenStats.weekSec(context))
        assertEquals(30L, ListenStats.topSongs(context).single().sec)
    }


    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) = CloudSyncController(
        context = context,
        scope = scope,
        prefs = prefs("soundwave"),
        getScripts = { emptyList() },
        ioDispatcher = dispatcher,
        mainDispatcher = dispatcher
    )

    /** 账号会话已删除：等价设置 = 本地资料名（prefs + 控制器）。 */
    private fun setSession(controller: CloudSyncController, user: String) {
        prefs("soundwave").edit().putString("user", user).commit()
        controller.user = user
    }

    private fun song(id: String) = Song(id, id, "Artist", "wy")

    private fun dailyStats(song: Song, deviceId: String, seconds: Long): String =
        ListenStatsDailyStore.record(
            "{}", song, deviceId, seconds = seconds, incrementCount = true,
            nowMs = System.currentTimeMillis()
        )

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

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
