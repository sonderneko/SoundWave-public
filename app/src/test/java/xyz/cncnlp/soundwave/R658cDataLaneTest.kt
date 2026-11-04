package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.playback.RecoveryAction
import xyz.cncnlp.soundwave.playback.localRecoveryAction
import java.time.LocalDate
import java.time.ZoneId

/** r658c D：数据/播放逻辑线回归。 */
class R658cDataLaneTest {
    // ── #1 本地音乐不取链 ──
    @Test
    fun localSongUriIsRecoveredFromIdentityWithoutNetwork() {
        val withUrl = Song("1", "a", "b", source = "local", resolvedUrl = "file:///sdcard/a.mp3")
        assertEquals("file:///sdcard/a.mp3", LocalPlayback.candidateUri(withUrl))
        val mediaStore = Song("42", "a", "b", source = "local")
        assertEquals("content://media/external/audio/media/42", LocalPlayback.candidateUri(mediaStore))
        val pathId = Song("/storage/emulated/0/Music/a.flac", "a", "", source = "local")
        assertEquals("/storage/emulated/0/Music/a.flac", LocalPlayback.candidateUri(pathId))
        assertNull(LocalPlayback.candidateUri(Song("123", "a", "b", source = "wy")))
        assertTrue(LocalPlayback.isLocalSong(mediaStore))
        assertFalse(LocalPlayback.isLocalSong(Song("1", "a", "b", source = "wy", resolvedUrl = "/sdcard/dl.mp3")))
    }

    @Test
    fun localFailureNeverRefreshesOrSwitchesSource() {
        assertEquals(RecoveryAction.SKIP_NEXT, localRecoveryAction(true, RecoveryAction.REFRESH_SAME_SOURCE))
        assertEquals(RecoveryAction.SKIP_NEXT, localRecoveryAction(true, RecoveryAction.SWITCH_SOURCE))
        assertEquals(RecoveryAction.STOP, localRecoveryAction(true, RecoveryAction.STOP))
        assertEquals(RecoveryAction.SWITCH_SOURCE, localRecoveryAction(false, RecoveryAction.SWITCH_SOURCE))
    }

    // ── #2 听歌统计 ──
    @Test
    fun accumulatorCountsPlayingTimeFlushesOnPauseAndSongChange() {
        val acc = ListenStatsAccumulator(flushEveryMs = 10_000L, maxTickGapMs = 3_000L)
        val a = Song("a", "A", "S", "wy")
        val b = Song("b", "B", "S", "wy")
        val first = acc.onTick(0L, a, true)
        assertEquals(a, first.markPlay)
        var flushed = 0L
        var t = 0L
        repeat(30) { t += 400L; acc.onTick(t, a, true).flushes.forEach { flushed += it.seconds } }
        assertEquals(10L, flushed) // 12s 播放：满 10s 落一批
        acc.onTick(t + 400L, a, false).flushes.forEach { flushed += it.seconds } // 暂停：余量落盘
        assertEquals(12L, flushed)
        // 暂停期间不计
        acc.onTick(t + 60_000L, a, false)
        val resumed = acc.onTick(t + 60_400L, a, true)
        assertNull("同一首暂停后继续不重复计次", resumed.markPlay)
        // 长间隔（进程挂起）只计上限
        acc.onTick(t + 600_400L, a, true)
        val change = acc.onTick(t + 600_800L, b, true)
        assertEquals(b, change.markPlay)
        assertEquals(3L, change.flushes.single().seconds)
    }

    private val nowMs = LocalDate.of(2026, 6, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun historyBackfillFillsOnlyPastDaysWithoutRealDataAndStaysLocal() {
        val day = 24L * 3600_000L
        val played = Song("x", "X", "Singer", "wy", duration = 200_000L, playedAt = nowMs - 2 * day)
        val today = Song("y", "Y", "Singer", "wy", duration = 200_000L, playedAt = nowMs)
        val realDay = Song("z", "Z", "Singer", "wy", duration = 200_000L, playedAt = nowMs - 3 * day)
        val real = ListenStatsDailyStore.record("{}", realDay, "00000000-0000-0000-0000-000000000001", 50L, true, nowMs - 3 * day)
        val filled = ListenStatsDailyStore.applyHistoryBackfill(real, listOf(played, today, realDay), nowMs)
        val summary = ListenStatsDailyStore.summary(filled, nowMs)
        assertEquals(200L, summary.dailySeconds[ListeningCalendar.dayKey(nowMs - 2 * day)])
        assertEquals(50L, summary.dailySeconds[ListeningCalendar.dayKey(nowMs - 3 * day)])
        assertFalse(summary.dailySeconds.containsKey(ListeningCalendar.dayKey(nowMs)))
        // 幂等
        assertEquals(filled, ListenStatsDailyStore.applyHistoryBackfill(filled, listOf(played, today, realDay), nowMs))
        // 不上云
        assertFalse(ListenStatsDailyStore.cloudProjection(filled, nowMs).contains(ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID))
        assertTrue(JSONObject(filled).toString().contains(ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID))
    }

    // ── #4 今日推荐按平台尊重标签 ──
    @Test
    fun instrumentalTagMapsPerPlatformAndRejectsPopSearchPlaylists() {
        val swr = xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation
        assertEquals(listOf("纯音乐", "轻音乐"), swr.tagSearchTerms("kg", "纯音乐"))
        assertTrue("轻音乐" in swr.tagSearchTerms("mg", "纯音乐"))
        assertTrue(swr.playlistMatchesTag("深夜钢琴纯音乐", "kg", "纯音乐"))
        assertTrue(swr.playlistMatchesTag("轻音乐 | 安静读书", "tx", "纯音乐"))
        assertFalse(swr.playlistMatchesTag("抖音热歌纯音乐版合集", "kw", "纯音乐"))
        assertFalse(swr.playlistMatchesTag("2024 流行华语热歌", "kw", "纯音乐"))
        assertTrue(swr.playlistMatchesTag("摇滚不死", "kg", "摇滚"))
        assertFalse(swr.playlistMatchesTag("华语流行", "kg", "摇滚"))
        assertTrue(swr.accepts(Song("1", "Kiss The Rain (钢琴曲)", "Yiruma", "kg"), "kg", listOf("纯音乐")))
        assertFalse(swr.accepts(Song("2", "晴天", "周杰伦", "kg"), "kg", listOf("纯音乐")))
    }

    // ── #5 酷我/咪咕高清封面 ──
    @Test
    fun kuwoSmallCoversMoveToReachableHostAndUpgrade() {
        assertEquals("https://img1.kuwo.cn/star/albumcover/1080/s3s94/93/211513640.jpg",
            normalizeCoverUrl("https://img2.kuwo.cn/star/albumcover/120/s3s94/93/211513640.jpg"))
        assertEquals("https://img1.kuwo.cn/star/starheads/500/s4s56/58/291211030.jpg",
            normalizeCoverUrl("http://img3.kuwo.cn/star/starheads/120/s4s56/58/291211030.jpg"))
        // 旧式数字-only 路径仍不猜
        assertEquals("https://img4.kuwo.cn/star/albumcover/120/95/6604395.jpg",
            normalizeCoverUrl("https://img4.kuwo.cn/star/albumcover/120/95/6604395.jpg"))
    }
}
