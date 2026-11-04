package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.ui.common.LocalReduceMotion

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class AboutUpdateActionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun setAction(status: androidx.compose.runtime.MutableState<String>, onCheck: () -> Unit = {}) {
        rule.setContent {
            // Keep the decorative spinner loop out of Robolectric's frozen frame clock.
            CompositionLocalProvider(LocalDecorativeActive provides false) {
                SoundwaveDesignTheme(isDark = false) {
                    androidx.compose.foundation.layout.Column {
                        AboutUpdateAction(updateStatus = status.value, onCheckUpdate = onCheck)
                    }
                }
            }
        }
    }

    @Test
    fun firstIdleClickCallsUpdateCallbackExactlyOnceAndStatusIsPoliteLiveRegion() {
        val status = mutableStateOf("")
        var requests = 0
        setAction(status) { requests++ }

        rule.onNodeWithText("检查更新").assertExists().performClick()
        assertEquals(1, requests)

        val message = "正在检查 GitHub Releases…"
        rule.runOnIdle { status.value = message }
        rule.waitForIdle()
        rule.onNodeWithText(message).assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite
            )
        )
        rule.onNodeWithText("检查中…").assertExists()
    }

    @Test
    fun busyActionIsDisabledAndCannotIssueAnotherRequest() {
        val status = mutableStateOf("")
        var requests = 0
        setAction(status) { requests++ }

        rule.onNodeWithText("检查更新").performClick()
        rule.runOnIdle { status.value = "正在检查 GitHub Releases…" }
        rule.waitForIdle()

        rule.onNode(hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("检查中…").performTouchInput { click() }
        assertEquals(1, requests)
    }

    @Test
    fun successFailureAndNoReleaseResultsAllAllowRetry() {
        val status = mutableStateOf("")
        var requests = 0
        setAction(status) { requests++ }
        val results = listOf(
            "已是最新或本地构建更新 · 当前 1.0 · GitHub v1.0",
            "检查失败：无法读取 GitHub Releases，当前 1.0",
            "尚无可用 GitHub 正式发布，当前 1.0"
        )

        results.forEachIndexed { index, result ->
            rule.runOnIdle { status.value = result }
            rule.waitForIdle()
            rule.onNodeWithText(result).assertExists()
            rule.onNodeWithText("检查更新").assertIsEnabled().performClick()
            assertEquals(index + 1, requests)
        }
    }

    @Test
    fun updateStatusFilterRejectsPlaybackMessages() {
        listOf("智能取链中…", "正在播放", "缓冲中", "已加载歌词").forEach { playbackStatus ->
            assertEquals("$playbackStatus must not leak into About update status", "", aboutUpdateStatus(playbackStatus))
        }
        assertEquals("正在检查 GitHub Releases…", aboutUpdateStatus("正在检查 GitHub Releases…"))
    }

    @Test
    fun reducedMotionKeepsBusyStateStaticAndAllowsRetryAfterCompletion() {
        val status = mutableStateOf("正在检查 GitHub Releases…")
        var requests = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalReduceMotion provides true,
                LocalDecorativeActive provides false
            ) {
                SoundwaveDesignTheme(isDark = false) {
                    androidx.compose.foundation.layout.Column {
                        AboutUpdateAction(updateStatus = status.value, onCheckUpdate = { requests++ })
                    }
                }
            }
        }

        rule.onNodeWithText("检查中…").assertExists()
        rule.onNode(hasClickAction()).assertIsNotEnabled()
        rule.runOnIdle { status.value = "检查失败：网络不可用" }
        rule.waitForIdle()
        rule.onNodeWithText("检查失败：网络不可用").assertExists()
        rule.onNodeWithText("检查更新").assertIsEnabled().performClick()
        assertEquals(1, requests)
    }

    @Test
    fun aboutPagePrivacyNavigationAndLicenseExpansionRemainAvailable() {
        var privacyOpens = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                AboutAppPage(status = "", serverUrl = "", onCheckUpdate = {}, onOpenDataFlow = { privacyOpens++ })
            }
        }

        rule.onNodeWithText("开源许可").performScrollTo().performClick()
        rule.onNodeWithText("Jetpack Compose (Apache 2.0)", substring = true).assertExists()
        rule.onNodeWithText("开源许可").performScrollTo().performClick()
        rule.onNodeWithText("Jetpack Compose (Apache 2.0)", substring = true).assertDoesNotExist()

        rule.onNodeWithText("隐私说明").performScrollTo().performClick()
        assertEquals(1, privacyOpens)
    }
}
