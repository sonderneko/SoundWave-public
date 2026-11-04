package xyz.cncnlp.soundwave.ui.common

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.USER_AUDIO_QUALITY_OPTIONS
import xyz.cncnlp.soundwave.ui.settings.PLAYBACK_SPEEDS
import xyz.cncnlp.soundwave.ui.settings.PlaybackQualitySheetContent
import xyz.cncnlp.soundwave.ui.settings.PlaybackSpeedSheetContent
import xyz.cncnlp.soundwave.ui.settings.SleepTimerSheetContent
import xyz.cncnlp.soundwave.ui.settings.playbackSpeedLabel

/** Exercises real semantics/actions, not source-string substitutes or optional screenshot tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class SettingValueDialogInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun sheet(dark: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        rule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
            }
        }
    }

    @Test fun speedPreservesAllSixOptionsAndSelectedRadioSemantics() {
        val current = mutableStateOf(1.25f)
        val picks = mutableListOf<Float>()
        sheet {
            PlaybackSpeedSheetContent(current.value) { picks += it; current.value = it }
        }
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(6)
        rule.onNodeWithText("1.25×").assertIsSelected()
        PLAYBACK_SPEEDS.forEach { speed ->
            rule.onNodeWithText(playbackSpeedLabel(speed)).performScrollTo().performClick().assertIsSelected()
        }
        rule.runOnIdle { assertEquals(PLAYBACK_SPEEDS, picks) }
    }

    @Test fun streamQualityPreservesEveryRequestedTierInDarkTheme() {
        val current = mutableStateOf("320k")
        val picks = mutableListOf<String>()
        sheet(dark = true) {
            PlaybackQualitySheetContent(current.value) { picks += it; current.value = it }
        }
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(USER_AUDIO_QUALITY_OPTIONS.size)
        USER_AUDIO_QUALITY_OPTIONS.forEach { (key, label) ->
            rule.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(key, current.value) }
        }
        rule.runOnIdle { assertEquals(USER_AUDIO_QUALITY_OPTIONS.map { it.first }, picks) }
    }

    @Test fun inactiveSleepCancelNeverCommitsDraft() {
        val applied = mutableListOf<Int>()
        var dismissed = 0
        sheet { SleepTimerSheetContent(0, { applied += it }, { dismissed++ }) }
        rule.onNodeWithText("8 小时").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(applied.isEmpty()); assertEquals(1, dismissed) }
    }

    @Test fun sleepAfterCurrentTrackPreservesSentinel() {
        val applied = mutableListOf<Int>()
        var dismissed = 0
        sheet { SleepTimerSheetContent(0, { applied += it }, { dismissed++ }) }
        rule.onNodeWithText("播完当前").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("开始定时").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(-1), applied); assertEquals(1, dismissed) }
    }

    @Test fun activeSleepCloseCommitsZeroNotEditedDuration() {
        val applied = mutableListOf<Int>()
        var dismissed = 0
        sheet(dark = true) { SleepTimerSheetContent(60 * 60_000L, { applied += it }, { dismissed++ }) }
        rule.onNodeWithText("1 小时").assertIsSelected()
        rule.onNodeWithText("2 小时").performScrollTo().performClick()
        rule.onNodeWithText("关闭定时").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(0), applied); assertEquals(1, dismissed) }
    }

    @Test fun sleepServiceTicksDoNotOverwriteDraft() {
        val remain = mutableStateOf(60 * 60_000L)
        val applied = mutableListOf<Int>()
        sheet { SleepTimerSheetContent(remain.value, { applied += it }, {}) }
        rule.onNodeWithText("8 小时").performScrollTo().performClick()
        rule.runOnIdle { remain.value = 59 * 60_000L }
        rule.onNodeWithText("8 小时").assertIsSelected()
        rule.onNodeWithText("开始定时").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(480), applied) }
    }

    @Test fun sleepPreservesLegacyExactMinuteValue() {
        val applied = mutableListOf<Int>()
        sheet { SleepTimerSheetContent(7 * 60_000L, { applied += it }, {}) }
        rule.onNodeWithText("开始定时").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(7), applied) }
    }

    @Test fun sleepAccessibleSliderCanReach24HoursAndLeavesAfterTrackMode() {
        val applied = mutableListOf<Int>()
        sheet(dark = true) { SleepTimerSheetContent(Long.MAX_VALUE, { applied += it }, {}) }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1440f) }
        rule.onNodeWithText("24").assertExists()
        rule.onNodeWithText("开始定时").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(1440), applied) }
    }

    @Test fun sleepExistingAfterTrackStateIsSelectedOnOpen() {
        val applied = mutableListOf<Int>()
        sheet { SleepTimerSheetContent(Long.MAX_VALUE, { applied += it }, {}) }
        rule.onNodeWithText("播完当前").assertIsSelected()
        rule.onNodeWithText("关闭定时").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(0), applied) }
    }

    @Test fun downloadQualitySelectionIsDraftUntilConfirmAndKeepsAllTiers() {
        val current = mutableStateOf("320k")
        val confirmed = mutableListOf<String>()
        var dismissed = 0
        sheet(dark = true) {
            DownloadQualitySheetContent(Song("test", "测试歌曲", "测试歌手"), emptyMap(), current.value,
                { current.value = it }, { confirmed += it }, { dismissed++ })
        }
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(USER_AUDIO_QUALITY_OPTIONS.size)
        USER_AUDIO_QUALITY_OPTIONS.forEach { (_, label) ->
            rule.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
        }
        rule.runOnIdle { assertTrue(confirmed.isEmpty()); assertEquals(0, dismissed) }
        rule.onNodeWithText("下载").performScrollTo().performClick()
        // MERGE7 对齐（J8 选即关纪律）：「下载」先执行确认动作、随即经关闭句柄发起退场
        // （宿主 onDismiss 在退场后清状态）；无宿主的本夹具里句柄回退为直接 onDismiss，
        // 故确认后 dismissed 即为 1。草稿-确认核心（上方逐档点选不触发确认/关闭）不变。
        rule.runOnIdle { assertEquals(listOf(USER_AUDIO_QUALITY_OPTIONS.last().first), confirmed); assertEquals(1, dismissed) }
    }

    @Test fun downloadWithoutSelectionCannotConfirm() {
        var confirmed = false
        sheet {
            DownloadQualitySheetContent(Song("test", "测试歌曲", "测试歌手"), emptyMap(), "", {}, { confirmed = true }, {})
        }
        rule.onNodeWithText("下载").performScrollTo().assertIsNotEnabled().performClick()
        rule.runOnIdle { assertTrue(!confirmed) }
    }
}
