package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.source.ScriptHttpCredentialGuardSetting
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScriptHttpCredentialGuardSettingTest {
    @Test
    fun preferenceDefaultsOffAndPersistsExplicitChanges() {
        val context = RuntimeEnvironment.getApplication() as Context
        val preferences = context.getSharedPreferences("script-http-credential-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        assertFalse(ScriptHttpCredentialGuardSetting.isEnabled(preferences))
        ScriptHttpCredentialGuardSetting.setEnabled(preferences, true)
        assertTrue(ScriptHttpCredentialGuardSetting.isEnabled(preferences))
        ScriptHttpCredentialGuardSetting.setEnabled(preferences, false)
        assertFalse(ScriptHttpCredentialGuardSetting.isEnabled(preferences))
    }
}
