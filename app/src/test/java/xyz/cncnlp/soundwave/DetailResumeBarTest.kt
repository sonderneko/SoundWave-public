package xyz.cncnlp.soundwave

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailResumeBarTest {
    @get:Rule val rule = createComposeRule()

    private val song = Song(
        id = "detail-track",
        name = "真实歌曲",
        singer = "真实歌手",
        source = "tx",
    )

    private fun render(onResume: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        rule.setContent {
            MaterialTheme {
                DetailResumeBar(song = song, onResume = onResume, onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun displaysTheActualSongAndSingerLabel() {
        render()

        rule.onNodeWithText("真实歌曲 - 真实歌手").assertIsDisplayed()
    }

    @Test
    fun clickingTheSongLabelCallsOnlyResume() {
        var resumeCalls = 0
        var dismissCalls = 0
        render(onResume = { resumeCalls++ }, onDismiss = { dismissCalls++ })

        rule.onNode(hasClickAction() and hasText("真实歌曲 - 真实歌手", substring = true)).performClick()

        assertEquals(1, resumeCalls)
        assertEquals(0, dismissCalls)
    }

    @Test
    fun clickingCloseCallsOnlyDismiss() {
        var resumeCalls = 0
        var dismissCalls = 0
        render(onResume = { resumeCalls++ }, onDismiss = { dismissCalls++ })

        rule.onNodeWithContentDescription("关闭继续播放").performClick()

        assertEquals(0, resumeCalls)
        assertEquals(1, dismissCalls)
        rule.onNodeWithText("真实歌曲 - 真实歌手").assertIsDisplayed()
    }
}
