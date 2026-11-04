package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptProbeFullReportFormatTest {
    private fun fullReport() = ScriptProbeCache.Report(
        scriptId = "script-a",
        scriptName = "脚本 A",
        type = ScriptProbeCache.ReportType.FULL,
        complete = true,
        coverage = "按脚本声明完整覆盖：酷我 2 首 × flac/320k，共 4 项",
        attempts = listOf(
            ScriptProbeCache.Attempt(
                platform = "kw", quality = "flac", outcome = ScriptProbeCache.Outcome.FAILED,
                resolveMs = 16_000L, reason = "timeout", sampleQueryName = "晴天", sampleQuerySinger = "周杰伦"
            ),
            ScriptProbeCache.Attempt(
                platform = "kw", quality = "320k", outcome = ScriptProbeCache.Outcome.UNSUPPORTED,
                resolveMs = 120L, reason = "脚本未返回链接；该平台/档位可能不可用", sampleQueryName = "晴天", sampleQuerySinger = "周杰伦"
            )
        )
    )

    @Test
    fun fullReportIsTitledDetectionReportWithCoverageAndPerItemTotals() {
        val text = ScriptProbeCache.format(fullReport())

        assertTrue(text.contains("检测报告 · "))
        assertTrue(text.contains("（完整报告）"))
        assertTrue(text.contains("覆盖范围：按脚本声明完整覆盖"))
        assertTrue(text.contains("逐项结果：成功 0 · 无可验证链接 1 · 样本未匹配 0 · 失败 1"))
        assertTrue(text.contains("timeout"))
        assertTrue(text.contains("脚本取链16000ms"))
        assertFalse(text.contains("快速抽检只覆盖"))
    }

    @Test
    fun coverageSurvivesJsonRoundTrip() {
        val restored = ScriptProbeCache.Report.fromJson(JSONObject(fullReport().toJson().toString()))
        assertEquals(fullReport().coverage, restored.coverage)
        assertEquals(ScriptProbeCache.ReportType.FULL, restored.type)
        assertTrue(restored.complete)
    }
}
