package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore as Store

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xhdpi")
class PlaybackTransitionSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val prefs get() = ContextPreferences.open(rule.activity, "soundwave", 0)
    private var old: Any? = null
    @Before fun setup() {
        old = prefs.all[Store.KEY_DURATION]
        Store.write(prefs, 0)
    }
    @After fun cleanup() {
        val previous = old
        if (previous is Int) prefs.edit().putInt(Store.KEY_DURATION, previous).commit()
        else prefs.edit().remove(Store.KEY_DURATION).commit()
    }

    @Test fun sheetUpdatesBothObserversAndExternalCloseIsReflected() {
        val sharedPrefs = prefs
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column {
                    val menuSeconds by rememberPlaybackTransitionSeconds(sharedPrefs)
                    val settingsSeconds by rememberPlaybackTransitionSeconds(sharedPrefs)
                    Text("menu=$menuSeconds")
                    Text("settings=$settingsSeconds")
                }
                PlaybackTransitionSettingsSheet(onDismiss = {})
            }
        }
        rule.onNodeWithText("尚未启用，目前不可设置。").assertIsDisplayed()
        // Execute the real choice action; this test covers state wiring, not platform-dialog hit testing.
        rule.onNodeWithText("5 秒").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitForIdle()
        assertEquals(5, Store.read(sharedPrefs))
        rule.onNodeWithText("menu=5").assertExists()
        rule.onNodeWithText("settings=5").assertExists()
        rule.runOnIdle { Store.write(sharedPrefs, 0) }
        rule.waitForIdle()
        rule.onNodeWithText("menu=0").assertExists()
        rule.onNodeWithText("settings=0").assertExists()
    }
}
