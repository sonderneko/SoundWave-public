package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.cncnlp.soundwave.ScriptProbeCache.Attempt
import xyz.cncnlp.soundwave.ScriptProbeCache.Outcome
import xyz.cncnlp.soundwave.ScriptProbeCache.Report
import xyz.cncnlp.soundwave.ScriptProbeCache.ScriptHealth

/** J-健康度：ScriptHealth.of 的状态派生与平均耗时格式（纯函数，数据只来自检测报告）。 */
class ScriptHealthTest {

    private fun validAttempt(resolve: Long, firstByte: Long) = Attempt(
        platform = "wy", quality = "flac", outcome = Outcome.OK,
        resolveMs = resolve, firstByteMs = firstByte, firstByteReceived = true,
        bytes = 1024, playable = true
    )

    private fun failedAttempt(reason: String) = Attempt(
        platform = "wy", quality = "flac", outcome = Outcome.FAILED, reason = reason
    )

    @Test fun noReportIsNone() {
        assertEquals(ScriptHealth.State.NONE, ScriptHealth.of(null).state)
        assertEquals("", ScriptHealth.of(null).label)
        assertEquals(ScriptHealth.State.NONE, ScriptHealth.of(Report("s", "n", emptyList())).state)
    }

    @Test fun validAttemptsAreUsableWithAverageLatency() {
        val report = Report("s", "n", listOf(validAttempt(1200, 600), validAttempt(800, 200)))
        val health = ScriptHealth.of(report)
        assertEquals(ScriptHealth.State.USABLE, health.state)
        // latencyMs = resolve + firstByte: (1800 + 1000) / 2 = 1400
        assertEquals(1400L, health.avgMs)
        assertEquals("可用 · 平均 1.4s", health.label)
    }

    @Test fun stopServicePhraseInFailureReasonsIsStopped() {
        val report = Report("s", "n", listOf(failedAttempt("脚本返回错误：该源已停止服务"), failedAttempt("timeout")))
        val health = ScriptHealth.of(report)
        assertEquals(ScriptHealth.State.STOPPED, health.state)
        assertEquals("已停服", health.label)
    }

    @Test fun otherFailuresAreFailedNotStopped() {
        val report = Report("s", "n", listOf(failedAttempt("network timeout"), failedAttempt("HTTP 500")))
        assertEquals(ScriptHealth.State.FAILED, ScriptHealth.of(report).state)
        assertEquals("检测未通过", ScriptHealth.of(report).label)
    }

    @Test fun skippedReportIsSkippedNotNone() {
        // ACCEPT5：跳过定论记录（attempts 为空）必须派生 SKIPPED，而不是回落成「无报告」的 NONE。
        val report = Report("s", "n", emptyList(), skippedReason = "批量检测被播放请求抢占，本轮未执行")
        val health = ScriptHealth.of(report)
        assertEquals(ScriptHealth.State.SKIPPED, health.state)
        assertEquals("已跳过", health.label)
    }

    @Test fun skippedReasonSurvivesSerializationAndDefaultsEmptyForLegacy() {
        val report = Report("s", "n", emptyList(), skippedReason = "检测异常中断（IOException），本轮未形成结论")
        assertEquals(report.skippedReason, Report.fromJson(report.toJson()).skippedReason)
        val legacy = Report.fromJson(org.json.JSONObject(report.toJson().toString()).apply { remove("skip") })
        assertEquals("", legacy.skippedReason)
        assertEquals(ScriptHealth.State.NONE, ScriptHealth.of(legacy).state)
    }

    @Test fun avgFormatting() {
        assertEquals("850ms", ScriptHealth.formatAvgMs(850))
        assertEquals("1.0s", ScriptHealth.formatAvgMs(1000))
        assertEquals("12.3s", ScriptHealth.formatAvgMs(12345))
    }
}
