package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ScriptScheduleCustomSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun sheet(dark: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        rule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
            }
        }
    }

    @Test fun cancelDiscardsDraftAndDismissesWithoutSaving() {
        val saves = mutableListOf<Pair<Boolean, Int>>()
        var dismissals = 0
        sheet {
            ScriptScheduleCustomSheetContent(false, 60, { enabled, minutes -> saves += enabled to minutes }, { dismissals++ })
        }
        rule.onNode(hasRole(Role.Switch)).assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(emptyList<Pair<Boolean, Int>>(), saves); assertEquals(1, dismissals) }
    }

    @Test fun savePassesCurrentDraftOnceAndCloses() {
        val saves = mutableListOf<Pair<Boolean, Int>>()
        var dismissals = 0
        sheet {
            ScriptScheduleCustomSheetContent(false, 30, { enabled, minutes -> saves += enabled to minutes }, { dismissals++ })
        }
        rule.onAllNodes(hasRole(Role.Switch)).assertCountEquals(1)
        rule.onNode(hasRole(Role.Switch)).performClick()
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(listOf(true to 30), saves); assertEquals(1, dismissals) }
        // Production removes the sheet in onDismiss; a retained composition cannot save twice.
        rule.onNodeWithText("保存").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, saves.size) }
    }

    @Test fun accessibleSliderEnablesScheduleAndClampsAt24HoursInDarkTheme() {
        val saves = mutableListOf<Pair<Boolean, Int>>()
        sheet(dark = true) {
            ScriptScheduleCustomSheetContent(false, 30, { enabled, minutes -> saves += enabled to minutes }, {})
        }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2000f) }
        rule.onNode(hasRole(Role.Switch)).assertIsOn()
        rule.onNodeWithText("每 1440 分钟检测一次").assertIsDisplayed()
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(listOf(true to 1440), saves) }
    }

    @Test fun saveDisabledScheduleRetainsItsNormalizedInterval() {
        val saves = mutableListOf<Pair<Boolean, Int>>()
        sheet {
            ScriptScheduleCustomSheetContent(true, 60, { enabled, minutes -> saves += enabled to minutes }, {})
        }
        rule.onNode(hasRole(Role.Switch)).performClick().assertIsOff()
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(listOf(false to 60), saves) }
    }

    @Test fun initialMinutesAreNormalizedIntoSupportedRange() {
        sheet { ScriptScheduleCustomSheetContent(true, 1, { _, _ -> }, {}) }
        rule.onNodeWithText("每 30 分钟检测一次").assertIsDisplayed()
        rule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test fun normalizedUpperBoundIsShownForLegacyOutOfRangeValue() {
        sheet { ScriptScheduleCustomSheetContent(true, 2000, { _, _ -> }, {}) }
        rule.onNodeWithText("每 1440 分钟检测一次").assertIsDisplayed()
        rule.onNodeWithText("24").assertIsDisplayed()
    }

    @Test fun parentRecompositionDoesNotReplaceAnOpenDraft() {
        val inputMinutes = mutableIntStateOf(60)
        val saves = mutableListOf<Pair<Boolean, Int>>()
        sheet {
            ScriptScheduleCustomSheetContent(false, inputMinutes.intValue, { enabled, minutes -> saves += enabled to minutes }, {})
        }
        rule.onNode(hasRole(Role.Switch)).performClick()
        rule.runOnIdle { inputMinutes.intValue = 300 }
        rule.onNodeWithText("每 60 分钟检测一次").assertIsDisplayed()
        rule.onNodeWithText("每 300 分钟检测一次").assertDoesNotExist()
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(listOf(true to 60), saves) }
    }
}
