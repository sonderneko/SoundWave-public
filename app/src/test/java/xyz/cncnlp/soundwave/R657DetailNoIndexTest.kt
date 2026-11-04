package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** r657 第三轮：详情列表无曲目编号（用户硬约束）；音质标识 / 热门歌手卡宽规则。 */
class R657DetailNoIndexTest {
    @Test fun detailTrackListsHideIndex() = assertFalse(DetailTrackListShowsIndex)

    @Test fun qualityBadgeLabels() {
        assertEquals("无损", designQualityBadgeLabel("flac"))
        assertEquals("HR", designQualityBadgeLabel("hires"))
        assertEquals("HR", designQualityBadgeLabel("flac24bit"))
        assertEquals("MASTER", designQualityBadgeLabel("master"))
        assertEquals("", designQualityBadgeLabel("320k"))
        assertEquals("", designQualityBadgeLabel(""))
    }

    @Test fun hotArtistCardsFitFourPerScreen() {
        assertEquals(96f, homeArtistCardWidth(androidx.compose.ui.unit.Dp(480f)).value, 0.01f)
        assertEquals(74f, homeArtistCardWidth(androidx.compose.ui.unit.Dp(360f)).value, 0.01f)
        assertEquals(86.75f, homeArtistCardWidth(androidx.compose.ui.unit.Dp(411f)).value, 0.01f)
    }

    @Test fun playbackPathTimeline() {
        val ok = PlaybackDiagnostics.Entry(songKey = "wy:1", songName = "a", resolveMs = 1032, scriptId = "墨澜聚合音源", platform = "wy", quality = "flac", prepareMs = 366, totalMs = 1398)
        val steps = playbackPathSteps(ok)
        assertEquals(PathStatus.Success, playbackPathStatus(ok))
        assertEquals("音频数据已就绪", steps.last().title)
        assertEquals(1398L, steps.last().atMs)
        assertEquals(PathStepKind.Attempt, steps.first { it.title == "尝试音源解析" }.kind)
        val fail = ok.copy(failureLayer = "script", totalMs = -1)
        assertEquals(PathStatus.Failed, playbackPathStatus(fail))
        assertEquals(PathStepKind.Failure, playbackPathSteps(fail).last().kind)
        assertEquals(PathStatus.Resolving, playbackPathStatus(ok.copy(totalMs = -1)))
        assertEquals("+1.032s", playbackPathTime(1032))
        assertEquals("+0s", playbackPathTime(0))
    }

    @Test fun sleepAndCustomNumber() {
        assertEquals(1440, xyz.cncnlp.soundwave.ui.settings.SLEEP_MAX_MINUTES)
        assertEquals(null, xyz.cncnlp.soundwave.ui.settings.parseCustomNumber("0", 1, 1440))
        assertEquals(1440, xyz.cncnlp.soundwave.ui.settings.parseCustomNumber("1440", 1, 1440))
        assertEquals(null, xyz.cncnlp.soundwave.ui.settings.parseCustomNumber("1441", 1, 1440))
        assertEquals("1:30" to "小时", xyz.cncnlp.soundwave.ui.settings.sleepReadout(90))
        assertEquals("45" to "分钟", xyz.cncnlp.soundwave.ui.settings.sleepReadout(45))
    }
}
