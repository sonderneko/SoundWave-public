package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test

class ScriptNotificationTest {
    private val script = Script(id = "notice-test", name = "测试脚本", code = "const version = 1")
    private fun report() = ScriptProbeCache.Report(
        scriptId = script.id, scriptName = script.name, attempts = emptyList(),
        checkedAt = 1234L, scriptHash = ScriptProbeCache.scriptHash(script.code)
    )

    @Test fun noReportDoesNotClaimSuccessOrInventTime() {
        val notice = scriptNotification(script, null)
        assertTrue(notice.summary.contains("暂无检测报告"))
        assertNull(notice.checkedAt)
    }

    @Test fun changedCodeNeverDisplaysOldSuccess() {
        val notice = scriptNotification(script.copy(code = "changed"), report())
        assertTrue(notice.summary.contains("已过期"))
        assertFalse(notice.summary.contains("已验证可播放"))
        assertEquals(1234L, notice.checkedAt)
    }

    @Test fun changedRuntimeOrWrongIdentityInvalidatesReport() {
        assertTrue(scriptNotification(script, report().copy(runtimeAbi = "old")).summary.contains("已过期"))
        assertTrue(scriptNotification(script, report().copy(scriptId = "other")).summary.contains("已过期"))
    }

    @Test fun incompleteAndLegacyReportsAreExplicit() {
        val incomplete = scriptNotification(script, report().copy(complete = false))
        assertTrue(incomplete.summary.contains("未完成"))
        assertTrue(incomplete.summary.contains("0 / 0 项实际尝试"))
        assertTrue(incomplete.summary.contains("仅代表本次检测覆盖范围"))
        val legacy = scriptNotification(script, report().copy(type = ScriptProbeCache.ReportType.QUICK, complete = false))
        assertTrue(legacy.summary.contains("快速抽检"))
        assertTrue(legacy.summary.contains("未完成"))
    }

    @Test fun missingReportTimeIsNotReplacedByCurrentTime() {
        assertNull(scriptNotification(script, report().copy(checkedAt = 0L)).checkedAt)
    }

    // 验收二轮 E3：脚本检测报告在通知中心只保留 30 分钟（以报告完成时间为准）。
    @Test fun reportFreshnessWindowIsThirtyMinutes() {
        val now = 1_700_000_000_000L
        assertFalse(isScriptReportFresh(null, now))
        assertFalse(isScriptReportFresh(0L, now))
        assertFalse(isScriptReportFresh(-5L, now))
        assertTrue(isScriptReportFresh(now, now))
        assertTrue(isScriptReportFresh(now - 29L * 60L * 1000L, now))
        assertTrue(isScriptReportFresh(now - 30L * 60L * 1000L, now))
        assertFalse(isScriptReportFresh(now - 31L * 60L * 1000L, now))
        // 时钟回拨/未来时间戳不算过期（差为负，仍在窗内）。
        assertTrue(isScriptReportFresh(now + 60_000L, now))
    }
}
