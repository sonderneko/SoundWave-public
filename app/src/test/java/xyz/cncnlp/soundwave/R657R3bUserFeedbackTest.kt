package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation
import xyz.cncnlp.soundwave.ui.motion.PageOcclusion

/** r657 第三轮 b：用户反馈 a / b / h 的纯逻辑回归（卡顿遮挡、今日推荐快照键与召回、播放路径时间轴）。 */
class R657R3bUserFeedbackTest {
    @Test fun coveredHomeSkipsDrawAndStopsDecorativeLoops() {
        assertFalse(PageOcclusion.homeDrawSkippable(tabCovers = false, detailCoverage = 0f))
        assertFalse(PageOcclusion.homeDrawSkippable(tabCovers = false, detailCoverage = 0.6f)) // 推入 / 推出中必须照常绘制
        assertTrue(PageOcclusion.homeDrawSkippable(tabCovers = false, detailCoverage = 1f))
        assertTrue(PageOcclusion.homeDrawSkippable(tabCovers = true, detailCoverage = 0f))
        assertTrue(PageOcclusion.homeDecorativeActive(false, 0f, playerCovers = false))
        assertFalse(PageOcclusion.homeDecorativeActive(false, 0f, playerCovers = true)) // 全屏播放器：停循环但不跳过绘制
        assertFalse(PageOcclusion.homeDecorativeActive(true, 0f, playerCovers = false))
    }

    @Test fun todaySnapshotFingerprintIgnoresLiveListeningActivity() {
        val a = TodayRecommendationSlot.snapshotFingerprint("wy", "scope", listOf("摇滚"), batch = 0, enabled = true)
        // 同一时段内，口味画像（收听秒数 / 最近播放）变化不得换键；平台 / 标签 / 换一批 / 开关 / 账号作用域变化必须换键。
        assertEquals(a, TodayRecommendationSlot.snapshotFingerprint("WY", "scope", listOf(" 摇滚 "), batch = 0, enabled = true))
        listOf(
            TodayRecommendationSlot.snapshotFingerprint("kg", "scope", listOf("摇滚")),
            TodayRecommendationSlot.snapshotFingerprint("wy", "other", listOf("摇滚")),
            TodayRecommendationSlot.snapshotFingerprint("wy", "scope", listOf("民谣")),
            TodayRecommendationSlot.snapshotFingerprint("wy", "scope", listOf("摇滚"), batch = 1),
            TodayRecommendationSlot.snapshotFingerprint("wy", "scope", listOf("摇滚"), enabled = false)
        ).forEach { assertNotEquals(a, it) }
    }

    @Test fun categoryPlaylistMembershipCountsAsStyleEvidence() {
        val rock = Song("1", "海阔天空", "Beyond", source = "wy", album = "乐与怒")
        // 旧的严格文本门槛：曲名 / 专辑名不含「摇滚」→ 全部被筛掉（今日推荐为空的根因之一）。
        assertFalse(StyleWeightedRecommendation.accepts(rock, "wy", listOf("摇滚")))
        assertTrue(StyleWeightedRecommendation.acceptsFromCategory(rock, "wy", listOf("摇滚")))
        assertFalse(StyleWeightedRecommendation.acceptsFromCategory(rock, "kg", listOf("摇滚")))
        val vocal = Song("2", "主题曲 (Vocal)", "X", source = "wy")
        assertFalse(StyleWeightedRecommendation.acceptsFromCategory(vocal, "wy", listOf("纯音乐")))
        assertTrue(StyleWeightedRecommendation.acceptsFromCategory(Song("3", "River Flows In You", "Yiruma", source = "wy"), "wy", listOf("纯音乐")))
        assertTrue(StyleWeightedRecommendation.COLD_START_TAGS.isNotEmpty())
    }

    @Test fun playbackPathNodesDistinguishCurrentSuccessFailure() {
        val ok = PlaybackDiagnostics.Entry(songKey = "wy:1", songName = "a", resolveMs = 1032, scriptId = "s", platform = "wy", quality = "flac", prepareMs = 366, totalMs = 1398)
        val steps = playbackPathSteps(ok)
        val roles = playbackPathNodeRoles(steps, playbackPathStatus(ok))
        assertFalse(PathNodeRole.Current in roles)
        assertEquals(PathNodeRole.Success, roles.last())
        val resolving = ok.copy(resolveMs = -1, totalMs = -1)
        val rSteps = playbackPathSteps(resolving)
        val rRoles = playbackPathNodeRoles(rSteps, playbackPathStatus(resolving))
        assertEquals(1, rRoles.count { it == PathNodeRole.Current })
        assertEquals(rSteps.indexOfLast { it.kind == PathStepKind.Attempt }, rRoles.indexOf(PathNodeRole.Current))
        val fail = ok.copy(failureLayer = "script", totalMs = -1)
        assertEquals(PathNodeRole.Failure, playbackPathNodeRoles(playbackPathSteps(fail), PathStatus.Failed).last())
        val d = playbackPathStepDurations(steps)
        assertEquals(0L, d.first())
        assertEquals(1398L - 1032L, d.last())
        assertTrue(d.all { it >= 0 })
    }
}
