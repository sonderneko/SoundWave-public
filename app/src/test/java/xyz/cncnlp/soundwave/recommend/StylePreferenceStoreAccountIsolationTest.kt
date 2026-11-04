package xyz.cncnlp.soundwave.recommend

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.AccountDataIdentity

@RunWith(RobolectricTestRunner::class)
class StylePreferenceStoreAccountIsolationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val homePrefs get() = context.getSharedPreferences("home_page_view_state_v1", Context.MODE_PRIVATE)
    private val authPrefs get() = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
    private val accountPrefsNames = listOf("Alice", "alice").map {
        "today_style_preferences_v2_" + AccountDataIdentity.key(it)
    }

    @Before fun setup() {
        homePrefs.edit().clear().commit()
        authPrefs.edit().clear().commit()
        accountPrefsNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        homePrefs.edit()
            .putString("today-style-tags:wy", "[\"legacy device style\"]")
            .putString("today-style-enabled:wy", "false")
            .putString("today-style-sync-v1", "legacy-sync-value")
            .commit()
    }

    @After fun cleanup() {
        homePrefs.edit().clear().commit()
        authPrefs.edit().clear().commit()
        accountPrefsNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    /** 账号会话已删除：身份 = 本机资料名（prefs `user`）本身；移除资料名即游客态。 */
    private fun login(user: String) {
        authPrefs.edit().putString("user", user).commit()
    }

    private fun selection(tags: List<String>, enabled: Boolean, time: Long) = StylePreferenceSync.encode(
        mapOf("wy" to StylePreferenceSync.Selection(tags, enabled, time, 1L))
    )

    @Test fun accountCaseVariantsAndGuestKeepIndependentStyleSnapshotsAndReaders() {
        login("Alice")
        assertEquals(emptyList<String>(), StylePreferenceStore.loadTags(context, "wy"))
        assertTrue(StylePreferenceStore.loadEnabled(context, "wy"))
        assertTrue(StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).isEmpty())
        assertEquals(StylePreferenceStore.SaveResult.Changed,
            StylePreferenceStore.saveSelection(context, "wy", listOf("Alice style"), true, 100L))
        assertEquals(listOf("Alice style"), StylePreferenceStore.loadTags(context, "wy"))

        login("alice")
        assertEquals(emptyList<String>(), StylePreferenceStore.loadTags(context, "wy"))
        assertTrue(StylePreferenceStore.loadEnabled(context, "wy"))
        assertTrue(StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).isEmpty())
        val lowerAccountRemote = selection(listOf("lowercase style"), false, 200L)
        assertEquals(lowerAccountRemote, StylePreferenceStore.applySynced(context, lowerAccountRemote))
        assertEquals(listOf("lowercase style"), StylePreferenceStore.loadTags(context, "wy"))
        assertFalse(StylePreferenceStore.loadEnabled(context, "wy"))
        assertEquals(listOf("lowercase style"),
            StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).getValue("wy").tags)

        login("Alice")
        assertEquals(listOf("Alice style"), StylePreferenceStore.loadTags(context, "wy"))
        assertTrue(StylePreferenceStore.loadEnabled(context, "wy"))
        assertEquals(listOf("Alice style"),
            StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).getValue("wy").tags)
        val aliceRemote = selection(listOf("Alice remote"), false, 300L)
        assertEquals(aliceRemote, StylePreferenceStore.applySynced(context, aliceRemote))
        assertEquals(listOf("Alice remote"), StylePreferenceStore.loadTags(context, "wy"))

        // Neither account read nor write may import, overwrite, or remove unproven legacy device state.
        assertEquals("[\"legacy device style\"]", homePrefs.getString("today-style-tags:wy", null))
        assertEquals("false", homePrefs.getString("today-style-enabled:wy", null))
        assertEquals("legacy-sync-value", homePrefs.getString("today-style-sync-v1", null))

        authPrefs.edit().remove("user").commit()
        assertEquals(listOf("legacy device style"), StylePreferenceStore.loadTags(context, "wy"))
        assertFalse(StylePreferenceStore.loadEnabled(context, "wy"))
        val guestSnapshot = StylePreferenceSync.decode(StylePreferenceStore.snapshot(context))
        assertEquals(listOf("legacy device style"), guestSnapshot.getValue("wy").tags)
        assertFalse(guestSnapshot.getValue("wy").enabled)
        // Legacy timestamp-less selections intentionally win remote conflicts. A confirmed
        // guest edit establishes a version before testing a later remote update.
        assertEquals(StylePreferenceStore.SaveResult.Changed,
            StylePreferenceStore.saveSelection(context, "wy", listOf("guest edited"), true, 350L))
        val guestRemote = selection(listOf("guest style"), true, 400L)
        assertEquals(guestRemote, StylePreferenceStore.applySynced(context, guestRemote))
        assertEquals(listOf("guest style"), StylePreferenceStore.loadTags(context, "wy"))

        login("alice")
        assertEquals(listOf("lowercase style"), StylePreferenceStore.loadTags(context, "wy"))
        login("Alice")
        assertEquals(listOf("Alice remote"), StylePreferenceStore.loadTags(context, "wy"))
    }
}
