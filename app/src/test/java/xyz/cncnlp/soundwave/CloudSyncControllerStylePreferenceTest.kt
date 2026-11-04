package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.AccountDataIdentity
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.recommend.StylePreferenceStore
import xyz.cncnlp.soundwave.recommend.StylePreferenceSync

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerStylePreferenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val names = listOf(LegacyUnassignedSnapshot.SOURCE_PREFERENCES,
        LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, "soundwave", "soundwave_userdata",
        "home_page_view_state_v1", "listen_stats_v1",
        "today_style_preferences_v2_${AccountDataIdentity.key("style-user")}")
    private fun prefs(name: String) = context.getSharedPreferences(name, 0)

    @Before fun setup() {
        names.forEach { prefs(it).edit().clear().commit() }
        prefs(LegacyUnassignedSnapshot.SOURCE_PREFERENCES).edit()
            .putString(LegacyUnassignedSnapshot.USER_DATA_KEY, "{\"favs\":[],\"pls\":[]}")
            .putString(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY, "[]").commit()
        prefs("home_page_view_state_v1").edit()
            .putString("today-style-tags:wy", "[\"legacy guest style\"]")
            .putString("today-style-enabled:wy", "false")
            .putString("today-style-sync-v1", "legacy-guest-style")
            .commit()
        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(context))
    }
    @After fun cleanup() { names.forEach { prefs(it).edit().clear().commit() } }

    private fun controller(scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher): CloudSyncController = CloudSyncController(
        context, scope, prefs("soundwave"), { emptyList() },
        ioDispatcher = dispatcher, mainDispatcher = dispatcher
    )

    /** 账号会话已删除：等价设置 = 本地资料名 style-user。 */
    private fun session(controller: CloudSyncController) {
        prefs("soundwave").edit().putString("user", "style-user").commit()
        controller.user = "style-user"
    }

    private fun remote(tags: List<String>, enabled: Boolean, time: Long) = LocalStore.UserBundle(
        todayStylePreferencesJson = StylePreferenceSync.encode(mapOf("wy" to
            StylePreferenceSync.Selection(tags, enabled, time, 4L))),
        dailyListenStatsOwnerV2 = AccountDataIdentity.key("style-user")
    )

    @Test fun webDavApplyRestoresRemoteStylePreferences() = runTest {
        val controller = controller(this, StandardTestDispatcher(testScheduler))
        session(controller)
        controller.applyWebDavBundleLocally(remote(listOf("电子"), false, 200L))
        advanceUntilIdle()
        assertEquals(listOf("电子"), StylePreferenceStore.loadTags(context, "wy"))
        assertFalse(StylePreferenceStore.loadEnabled(context, "wy"))
    }

    @Test fun webDavApplyMergesAndPersistsNewerRemoteClear() = runTest {
        val cloud = remote(emptyList(), false, 200L)
        val controller = controller(this, StandardTestDispatcher(testScheduler))
        session(controller)
        StylePreferenceStore.saveSelection(context, "wy", listOf("民谣"), true, 100L)
        controller.applyWebDavBundleLocally(cloud)
        assertEquals(emptyList<String>(), StylePreferenceStore.loadTags(context, "wy"))
        assertFalse(StylePreferenceStore.loadEnabled(context, "wy"))
        assertEquals(cloud.todayStylePreferencesJson, LocalStore.load(context).todayStylePreferencesJson)
    }


    @Test fun applyingWebDavPreferenceStaysInLocalProfileNamespace() = runTest {
        val controller = controller(this, StandardTestDispatcher(testScheduler))
        session(controller)
        controller.applyWebDavBundleLocally(remote(listOf("古典"), true, 300L))
        advanceUntilIdle()
        assertEquals(listOf("古典"), StylePreferenceStore.loadTags(context, "wy"))
        assertFalse("legacy guest style must not leak into the local profile namespace",
            StylePreferenceStore.snapshot(context).contains("legacy guest style"))
    }



}
