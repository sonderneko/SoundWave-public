package xyz.cncnlp.soundwave

import android.content.Context
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionPolicyTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferencesAndSystemAnimationScale() {
        context.getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    @After
    fun restoreDefaults() {
        context.getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    @Test
    fun appPreferenceIsPersistedAndContributesToReduceMotionState() {
        assertFalse(MotionPolicy.appReduceMotion(context))
        assertFalse(MotionPolicy.isReduceMotionEnabled(context))

        MotionPolicy.setAppReduceMotion(context, true)

        assertTrue(MotionPolicy.appReduceMotion(context))
        assertTrue(MotionPolicy.isReduceMotionEnabled(context))

        MotionPolicy.setAppReduceMotion(context, false)
        assertFalse(MotionPolicy.isReduceMotionEnabled(context))
    }

    @Test
    fun zeroSystemAnimationScaleEnablesReduceMotionWithoutAppPreference() {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )

        assertTrue(MotionPolicy.systemReduceMotion(context))
        assertTrue(MotionPolicy.isReduceMotionEnabled(context))
    }

    @Test
    fun systemAndAppPreferencesAreCombinedWithLogicalOr() {
        assertTrue(MotionPolicy.shouldReduceMotion(systemEnabled = true, appEnabled = false))
        assertTrue(MotionPolicy.shouldReduceMotion(systemEnabled = false, appEnabled = true))
        assertFalse(MotionPolicy.shouldReduceMotion(systemEnabled = false, appEnabled = false))
    }
}
