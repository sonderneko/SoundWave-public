package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放诊断两缺陷回归：
 * 1) 播放路径弹层「复制诊断」必须是单曲范围（汇总行 + 该曲时间线），不得混入其它歌曲；
 * 2) 平台位置必须是可读来源标识——实际/原始平台码、跨源标注或脚本名，
 *    解析链回退的未校验来源串（64 位十六进制哈希）绝不落盘、绝不展示。
 */
class PlaybackDiagnosticsSourceLabelTest {

    private val hash = "f1ddce8dd5850cd4306695381f942700c6b60a06eaaad50a2b6935dcb1663529"

    @Before fun reset() { PlaybackDiagnostics.clear() }

    @Test fun canonicalPlatformAcceptsOnlyKnownCodes() {
        assertEquals("tx", PlaybackDiagnostics.canonicalPlatform("tx"))
        assertEquals("wy", PlaybackDiagnostics.canonicalPlatform("WY"))
        assertEquals("local", PlaybackDiagnostics.canonicalPlatform(" local "))
        assertEquals("", PlaybackDiagnostics.canonicalPlatform(hash))
        assertEquals("", PlaybackDiagnostics.canonicalPlatform("custom-source"))
        assertEquals("", PlaybackDiagnostics.canonicalPlatform(""))
    }

    @Test fun sourceLabelPrefersActualPlatform() {
        val e = PlaybackDiagnostics.Entry(songKey = "tx:1", songName = "a", platform = "tx", originPlatform = "tx")
        assertEquals("QQ音乐", PlaybackDiagnostics.sourceLabel(e))
    }

    @Test fun sourceLabelAnnotatesCrossSource() {
        val e = PlaybackDiagnostics.Entry(songKey = "tx:1", songName = "a", platform = "kw", originPlatform = "tx")
        assertEquals("QQ音乐→酷我", PlaybackDiagnostics.sourceLabel(e))
    }

    @Test fun sourceLabelFallsBackToOriginThenScript() {
        val originOnly = PlaybackDiagnostics.Entry(songKey = "wy:1", songName = "a", platform = "", originPlatform = "wy")
        assertEquals("网易云", PlaybackDiagnostics.sourceLabel(originOnly))
        val junkWithScript = PlaybackDiagnostics.Entry(songKey = "x:1", songName = "a", platform = hash, scriptId = "安妮独家音源.js")
        assertEquals("安妮独家音源.js", PlaybackDiagnostics.sourceLabel(junkWithScript))
    }

    @Test fun sourceLabelNeverPrintsDigest() {
        val junk = PlaybackDiagnostics.Entry(songKey = "x:1", songName = "a", platform = hash)
        assertEquals("未知来源", PlaybackDiagnostics.sourceLabel(junk))
        val local = PlaybackDiagnostics.Entry(songKey = "local:1", songName = "a", platform = "local")
        assertEquals("本地", PlaybackDiagnostics.sourceLabel(local))
        // 人类可读的自定义来源名不是哈希，保留原样。
        val custom = PlaybackDiagnostics.Entry(songKey = "x:2", songName = "a", platform = "my-source")
        assertEquals("my-source", PlaybackDiagnostics.sourceLabel(custom))
    }

    @Test fun recordCreationCanonicalizesPlatformAndKeepsOrigin() {
        val song = Song(id = "42", name = "隔岸（女版）", singer = "可楼", source = "tx")
        PlaybackDiagnostics.markStart(song)
        // 解析链回退给出的未校验来源串（哈希）不得落盘为 platform；原始平台另存 originPlatform。
        PlaybackDiagnostics.markResolved(song, 5807L, fromCache = false, scriptId = "安妮独家音源.js", platform = hash, quality = "320k")
        PlaybackDiagnostics.markReady(song, prefetched = false)
        val e = PlaybackDiagnostics.recent(1).single()
        assertEquals("", e.platform)
        assertEquals("tx", e.originPlatform)
        assertEquals("QQ音乐", PlaybackDiagnostics.sourceLabel(e))
    }

    @Test fun recordCreationKeepsActualPlatformOnCrossSource() {
        val song = Song(id = "7", name = "n", singer = "s", source = "tx")
        PlaybackDiagnostics.markStart(song)
        PlaybackDiagnostics.markResolved(song, 900L, fromCache = false, scriptId = "sc.js", platform = "kw", quality = "320k")
        PlaybackDiagnostics.markReady(song, prefetched = false)
        val e = PlaybackDiagnostics.recent(1).single()
        assertEquals("kw", e.platform)
        assertEquals("tx", e.originPlatform)
        assertEquals("QQ音乐→酷我", PlaybackDiagnostics.sourceLabel(e))
    }

    @Test fun singleSongExportContainsOnlyThatSong() {
        val a = PlaybackDiagnostics.Entry(
            songKey = "tx:42", songName = "隔岸（女版）", singer = "可楼",
            resolveMs = 5807, scriptId = "安妮独家音源.js", platform = hash, quality = "320k",
            prepareMs = 86, totalMs = 5940, purpose = "播放"
        )
        val text = PlaybackDiagnostics.songDiagnosticsText(a)
        assertTrue(text.contains("单曲"))
        assertTrue(text.contains("隔岸（女版）"))
        assertTrue(text.contains("安妮独家音源.js"))
        assertTrue(text.contains("播放路径："))
        assertTrue(text.contains("已获取真实播放链接"))
        assertFalse(text.contains(hash))
        assertFalse(text.contains("其它歌曲"))
        // 全局导出才有的标题不得出现在单曲导出里。
        assertFalse(text.contains("全部"))
    }

    @Test fun globalExportHeaderMarksAllAndLinesAreReadable() {
        val song = Song(id = "9", name = "我不难过", singer = "孙燕姿", source = "tx")
        PlaybackDiagnostics.markStart(song)
        PlaybackDiagnostics.markResolved(song, 9974L, fromCache = false, scriptId = "sc.js", platform = hash, quality = "320k")
        PlaybackDiagnostics.markReady(song, prefetched = false)
        val text = PlaybackDiagnostics.diagnosticsText()
        assertTrue(text.startsWith("=== SoundWave 播放诊断（全部"))
        assertTrue(text.contains("QQ音乐"))
        assertFalse(text.contains(hash))
    }

    @Test fun pathStepsNeverLeakDigestPlatform() {
        val e = PlaybackDiagnostics.Entry(
            songKey = "x:1", songName = "a", resolveMs = 100, scriptId = "安妮独家音源.js",
            platform = hash, quality = "320k", totalMs = 200
        )
        val steps = playbackPathSteps(e)
        assertTrue(steps.isNotEmpty())
        steps.forEach { s ->
            assertFalse("detail leaks digest: ${s.detail}", s.detail.contains(hash))
            s.tags.forEach { t -> assertFalse("tag leaks digest: $t", t.contains(hash)) }
        }
        // 第一步没有可读原平台时只写通用文案；脚本名仍出现在尝试步标签里。
        assertEquals("开始获取播放链接", steps.first().detail)
        assertTrue(steps.any { s -> s.tags.contains("安妮独家音源.js") })
    }

    @Test fun pathStepsUseOriginPlatformForStartStep() {
        val e = PlaybackDiagnostics.Entry(
            songKey = "tx:1", songName = "a", resolveMs = 100, scriptId = "sc.js",
            platform = "kw", originPlatform = "tx", quality = "320k", totalMs = 200
        )
        val steps = playbackPathSteps(e)
        assertEquals("从原平台 QQ音乐 开始获取播放链接", steps.first().detail)
        assertTrue(steps.first().tags.contains("QQ音乐→酷我"))
    }

    @Test fun noRecordExportStaysSingleSongScoped() {
        val text = PlaybackDiagnostics.songDiagnosticsText("某首歌", "某歌手")
        assertTrue(text.contains("某首歌"))
        assertTrue(text.contains("暂无取链记录"))
        assertFalse(text.contains("全部"))
    }
}
