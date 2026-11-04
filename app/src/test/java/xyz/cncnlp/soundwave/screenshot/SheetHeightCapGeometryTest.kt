package xyz.cncnlp.soundwave.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import xyz.cncnlp.soundwave.ui.common.DesignSheetPanel

/**
 * M 波（ISSUE-SONGMENU §4.1）几何断言：DesignSheetPanel 传 maxHeightFraction=0.70 时，
 * 面板实测高 = 宿主高 × 0.70（内容更高时钳制）；不传时面板按内容自然长高（超过封顶值）。
 * xhdpi → density 2.0：宿主 800dp = 1600px，封顶 = 1120px。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h1100dp-xhdpi")
class SheetHeightCapGeometryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun tallContent(): @androidx.compose.runtime.Composable ColumnScope.() -> Unit = {
        Column { repeat(10) { Box(Modifier.fillMaxWidth().height(56.dp)) } }
    }

    @Test fun panelHeightIsCappedAtFractionOfHost() {
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.width(400.dp).height(800.dp)) {
                    DesignSheetPanel(modifier = Modifier.testTag("panel"), scrollContent = false, maxHeightFraction = 0.70f, content = tallContent())
                }
            }
        }
        val heightPx = rule.onNodeWithTag("panel").fetchSemanticsNode().size.height
        assertEquals(1120, heightPx)
    }

    @Test fun panelWithoutFractionGrowsBeyondCap() {
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.width(400.dp).height(800.dp)) {
                    DesignSheetPanel(modifier = Modifier.testTag("panel"), scrollContent = false, content = tallContent())
                }
            }
        }
        val heightPx = rule.onNodeWithTag("panel").fetchSemanticsNode().size.height
        assertTrue("uncapped panel should exceed the 0.70 cap (1120px), was ${heightPx}px", heightPx > 1120)
    }
}
