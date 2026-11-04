package xyz.cncnlp.soundwave.recommend

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StylePreferenceStoreSyncTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("home_page_view_state_v1", 0)

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun legacyPlatformsMigrateWithoutInventingModificationTimesOrSyncingOtherState() {
        prefs.edit().putString("today-style-tags:wy", "[\"摇滚\"]")
            .putString("today-style-enabled:tx", "false")
            .putString("today-refresh-batch-day", "2026-10-23")
            .putString("playlist_category_groups_v3_wy", "private cache").commit()
        val migrated = StylePreferenceSync.decode(StylePreferenceStore.snapshot(context))
        assertEquals(setOf("wy", "tx"), migrated.keys)
        assertEquals(listOf("摇滚"), migrated.getValue("wy").tags)
        assertEquals(0L, migrated.getValue("wy").modifiedAt)
        assertFalse(migrated.getValue("tx").enabled)
        assertFalse(StylePreferenceStore.snapshot(context).contains("private cache"))
        assertFalse(migrated.containsKey("kg"))
    }

    @Test fun confirmedSavePersistsTagsToggleAndOneRevisionTogetherWithoutDailyQuota() {
        repeat(7) { i ->
            assertEquals(StylePreferenceStore.SaveResult.Changed,
                StylePreferenceStore.saveSelection(context, "WY", listOf("风格$i"), i % 2 == 0, 100L))
        }
        val state = StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).getValue("wy")
        assertEquals(7L, state.version)
        assertEquals(106L, state.modifiedAt)
        assertEquals(listOf("风格6"), StylePreferenceStore.loadTags(context, "wy"))
        assertTrue(StylePreferenceStore.loadEnabled(context, "wy"))
        assertEquals(StylePreferenceStore.SaveResult.Unchanged,
            StylePreferenceStore.saveSelection(context, "wy", listOf("风格6"), true, 500L))
        assertEquals(state, StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).getValue("wy"))
    }

    @Test fun remoteClearAndDisableReachActualReaderAndDoNotMintAnotherEdit() {
        StylePreferenceStore.saveSelection(context, "wy", listOf("民谣"), true, 100L)
        val incoming = StylePreferenceSync.encode(mapOf("wy" to StylePreferenceSync.Selection(emptyList(), false, 200L, 8L)))
        val merged = StylePreferenceStore.applySynced(context, incoming)
        assertEquals(incoming, merged)
        assertEquals(emptyList<String>(), StylePreferenceStore.loadTags(context, "wy"))
        assertFalse(StylePreferenceStore.loadEnabled(context, "wy"))
        assertEquals(emptyList<String>(), StylePreferenceStore.activeTags(context, "wy"))
        assertEquals(merged, StylePreferenceStore.applySynced(context, incoming))
        assertEquals(8L, StylePreferenceSync.decode(StylePreferenceStore.snapshot(context)).getValue("wy").version)
    }

    @Test fun staleSyncCannotRestoreTagsAfterLocalClearAndPlatformsRemainIndependent() {
        StylePreferenceStore.saveSelection(context, "wy", listOf("摇滚"), true, 100L)
        val stale = StylePreferenceStore.snapshot(context)
        StylePreferenceStore.saveSelection(context, "wy", emptyList(), true, 300L)
        StylePreferenceStore.saveSelection(context, "tx", listOf("电子"), false, 400L)
        StylePreferenceStore.applySynced(context, stale)
        assertEquals(emptyList<String>(), StylePreferenceStore.loadTags(context, "wy"))
        assertEquals(listOf("电子"), StylePreferenceStore.loadTags(context, "tx"))
        assertFalse(StylePreferenceStore.loadEnabled(context, "tx"))
    }
}
