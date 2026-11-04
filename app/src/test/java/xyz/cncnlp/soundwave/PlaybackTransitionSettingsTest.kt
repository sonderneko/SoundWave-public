package xyz.cncnlp.soundwave

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore as Store

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackTransitionSettingsTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("crossfade-settings-unit", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun absentAndCorruptPreferencesAreSafelyOff() {
        assertEquals(0, Store.read(prefs))
        prefs.edit().putString(Store.KEY_DURATION, "5").commit()
        assertEquals(0, Store.read(prefs))
        prefs.edit().putInt(Store.KEY_DURATION, Int.MAX_VALUE).commit()
        assertEquals(0, Store.read(prefs))
    }

    @Test fun everySupportedValuePersistsAcrossReopenedPreferenceHandles() {
        listOf(0, 3, 5, 8).forEach {
            Store.write(prefs, it)
            assertEquals(it, Store.read(prefs))
        }
    }

    @Test fun unsupportedValuesNeverBecomeActive() {
        listOf(-1, 1, 2, 4, 6, 7, 9, Int.MAX_VALUE).forEach {
            Store.write(prefs, it)
            assertEquals(0, Store.read(prefs))
        }
    }

    @Test fun menuSubtitleReflectsActualValueAndNeverClaimsSmartSupport() {
        assertEquals("关闭", playerMenuSecondarySubtitle("menu_transition", 0, 0))
        assertEquals("固定 5 秒", playerMenuSecondarySubtitle("menu_transition", 0, 5))
        assertTrue(playerMenuSecondarySubtitle("menu_smart_transition", 0, 5).contains("不可用"))
    }
}
