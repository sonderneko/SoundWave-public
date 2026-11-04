package xyz.cncnlp.soundwave.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose registration tests; not OEM window/IME or screenshot certification. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class AppOverlayHostTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstCompositionUsesActivityHostAndKeepsCallerTheme() {
        var inApp = false
        var observed = Color.Unspecified
        val primary = mutableStateOf(Color.Magenta)
        rule.setContent {
            ProvideAppOverlayHost {
                Box(Modifier.fillMaxSize()) {
                    Text("host-anchor")
                    MaterialTheme(colorScheme = darkColorScheme(primary = primary.value)) {
                        AppOverlayDialog({}) {
                            inApp = LocalInAppOverlay.current
                            observed = MaterialTheme.colorScheme.primary
                            Text("caller-theme")
                        }
                    }
                    AppOverlayHostLayer()
                }
            }
        }
        rule.onNodeWithText("caller-theme").assertExists()
        rule.runOnIdle { assertTrue(inApp); assertEquals(Color.Magenta, observed) }
        rule.runOnIdle { primary.value = Color.Cyan }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(Color.Cyan, observed) }
    }

    @Test fun onlyTopSheetHandlesBackAndUnregistersBeforeReopen() {
        val lower = mutableStateOf(true)
        val upper = mutableStateOf(true)
        var lowerDismissals = 0
        var upperDismissals = 0
        rule.setContent {
            ProvideAppOverlayHost {
                Box(Modifier.fillMaxSize()) {
                    Text("host-anchor")
                    if (lower.value) AppOverlayDialog({ lowerDismissals++; lower.value = false }) { Text("lower") }
                    if (upper.value) AppOverlayDialog({ upperDismissals++; upper.value = false }) { Text("upper") }
                    AppOverlayHostLayer()
                }
            }
        }
        rule.onNodeWithText("upper").assertExists()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("upper").assertDoesNotExist()
        rule.onNodeWithText("lower").assertExists()
        rule.runOnIdle { assertEquals(1, upperDismissals); assertEquals(0, lowerDismissals) }
        rule.runOnIdle { upper.value = true }
        rule.onNodeWithText("upper").assertExists()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("upper").assertDoesNotExist()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("lower").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, upperDismissals); assertEquals(1, lowerDismissals) }
    }

    @Test fun disposingHostDoesNotLeaveEntriesInNextHost() {
        val installed = mutableStateOf(true)
        val show = mutableStateOf(true)
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Text("root-anchor")
            if (installed.value) ProvideAppOverlayHost {
                Box(Modifier.fillMaxSize()) {
                    Text("host-anchor")
                    if (show.value) AppOverlayDialog({}) { Text("old-host-sheet") }
                    AppOverlayHostLayer()
                }
            }
            }
        }
        rule.onNodeWithText("old-host-sheet").assertExists()
        rule.runOnIdle { installed.value = false }
        rule.onNodeWithText("old-host-sheet").assertDoesNotExist()
        rule.runOnIdle { show.value = false; installed.value = true }
        rule.onNodeWithText("old-host-sheet").assertDoesNotExist()
        rule.runOnIdle { show.value = true }
        rule.onNodeWithText("old-host-sheet").assertExists()
    }

    @Test fun callerOwnsOutsideDismissAndPanelConsumesItsClick() {
        val visible = mutableStateOf(true)
        rule.setContent {
            ProvideAppOverlayHost {
                Box(Modifier.fillMaxSize()) {
                    Text("host-anchor")
                    if (visible.value) AppOverlayDialog({ visible.value = false }) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).clickable { visible.value = false }) {
                            Text("outside", Modifier.align(Alignment.TopCenter))
                            Box(Modifier.align(Alignment.BottomCenter).width(300.dp).height(100.dp).clickable {}) { Text("panel") }
                        }
                    }
                    AppOverlayHostLayer()
                }
            }
        }
        rule.onNodeWithText("panel").performClick()
        rule.onNodeWithText("panel").assertExists()
        rule.onNodeWithText("outside").performClick()
        rule.onNodeWithText("panel").assertDoesNotExist()
    }
}
