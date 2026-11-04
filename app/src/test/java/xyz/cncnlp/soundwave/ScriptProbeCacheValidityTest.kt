package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.probe.ScriptProbeStartupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptProbeCacheValidityTest {
    private val script = Script(id = "script-a", name = "A", code = "lx.on('musicUrl', () => {})")

    private fun report(
        scriptId: String = script.id,
        scriptHash: String = ScriptProbeCache.scriptHash(script.code),
        runtimeAbi: String = ScriptProbeCache.RUNTIME_ABI,
        type: ScriptProbeCache.ReportType = ScriptProbeCache.ReportType.FULL,
        complete: Boolean = true,
        attempts: List<ScriptProbeCache.Attempt> = emptyList(),
        checkedAt: Long = 1_700_000_000_000L
    ) = ScriptProbeCache.Report(
        scriptId = scriptId,
        scriptName = "A",
        attempts = attempts,
        checkedAt = checkedAt,
        scriptHash = scriptHash,
        runtimeAbi = runtimeAbi,
        type = type,
        complete = complete
    )

    private fun reportFor(
        target: Script,
        attempts: List<ScriptProbeCache.Attempt>,
        type: ScriptProbeCache.ReportType = ScriptProbeCache.ReportType.FULL,
        complete: Boolean = true,
        scriptHash: String = ScriptProbeCache.scriptHash(target.code)
    ) = ScriptProbeCache.Report(
        scriptId = target.id,
        scriptName = target.name,
        attempts = attempts,
        checkedAt = 1_700_000_000_000L,
        scriptHash = scriptHash,
        runtimeAbi = ScriptProbeCache.RUNTIME_ABI,
        type = type,
        complete = complete
    )

    private fun verified(platform: String, quality: String, resolveMs: Long) =
        ScriptProbeCache.Attempt(
            platform = platform,
            quality = quality,
            outcome = ScriptProbeCache.Outcome.OK,
            resolveMs = resolveMs,
            firstByteMs = 40L,
            firstByteReceived = true,
            bytes = 10_000L,
            playable = true
        )

    @Test
    fun onlyCurrentCompleteFullReportsAreEligibleForSorting() {
        assertTrue(ScriptProbeCache.isValidFullReport(report(), script))
        assertFalse(
            ScriptProbeCache.isValidFullReport(
                report(type = ScriptProbeCache.ReportType.QUICK),
                script
            )
        )
        assertFalse(ScriptProbeCache.isValidFullReport(report(complete = false), script))
        assertFalse(ScriptProbeCache.isValidFullReport(report(scriptId = "other"), script))
        assertFalse(ScriptProbeCache.isValidFullReport(report(scriptHash = "stale-hash"), script))
        assertFalse(ScriptProbeCache.isValidFullReport(report(runtimeAbi = "old-runtime"), script))
    }

    @Test
    fun skippedProbeRecordIsDefiniteButNotEvidence() {
        // ACCEPT5：跳过记录有定论（健康派生 SKIPPED）但不是检测证据——
        // 不得进有效报告门（评分/自动配置只认完整报告），也不得有有效尝试。
        val skipped = report(complete = false).copy(skippedReason = "批量检测被播放请求抢占，本轮未执行")
        assertFalse(ScriptProbeCache.isValidFullReport(skipped, script))
        assertTrue(skipped.valid.isEmpty())
        assertEquals(
            ScriptProbeCache.ScriptHealth.State.SKIPPED,
            ScriptProbeCache.ScriptHealth.of(skipped).state
        )
        // 格式化报告必须明说未执行，不能伪装成一次 0 分的完整检测。
        assertTrue(ScriptProbeCache.format(skipped).contains("本轮检测未执行"))
    }

    @Test
    fun reportTypeSurvivesSerializationAndRemainsExplicit() {
        listOf(ScriptProbeCache.ReportType.QUICK, ScriptProbeCache.ReportType.FULL).forEach { type ->
            val decoded = ScriptProbeCache.Report.fromJson(report(type = type).toJson())
            assertEquals(type, decoded.type)
        }
    }

    @Test
    fun formattedReportShowsObservedCoverageAndDoesNotExposeUrl() {
        val rawUrl = "https://alice:pass-123@cdn.example/private/signed/AbCdEf0123456789GhIjKlMnOpQrStUv/song.flac?token=secret-query&quality=320#private-fragment"
        val report = report(
            type = ScriptProbeCache.ReportType.QUICK,
            complete = false,
            attempts = listOf(
                ScriptProbeCache.Attempt(
                    platform = "wy",
                    quality = "320k",
                    outcome = ScriptProbeCache.Outcome.UNSUPPORTED,
                    reason = "异常详情：$rawUrl",
                    sampleQueryName = "鲜花",
                    urlHint = rawUrl
                ),
                ScriptProbeCache.Attempt(
                    platform = "kw",
                    quality = "128k",
                    outcome = ScriptProbeCache.Outcome.FAILED,
                    reason = "请求超时",
                    sampleQueryName = "晴天",
                    sampleQuerySinger = "周杰伦"
                )
            )
        )

        val formatted = ScriptProbeCache.format(report)
        assertTrue(formatted.contains("快速抽检"))
        assertTrue(formatted.contains("2 项实际尝试"))
        assertTrue(formatted.contains("网易云/鲜花：320k"))
        assertTrue(formatted.contains("酷我/晴天 - 周杰伦：128k"))
        assertTrue(formatted.contains("不支持"))
        assertTrue(formatted.contains("失败"))
        assertTrue(formatted.contains("本次未探测"))
        assertTrue(formatted.contains("URL https://cdn.example/private/signed/__redacted__/song.flac?token=__redacted__&quality=__redacted__"))
        assertFalse(formatted.contains("alice"))
        assertFalse(formatted.contains("pass-123"))
        assertFalse(formatted.contains("AbCdEf0123456789GhIjKlMnOpQrStUv"))
        assertFalse(formatted.contains("secret-query"))
        assertFalse(formatted.contains("private-fragment"))
    }

    @Test
    fun noMatchIsDistinctFromUnsupportedAndKeepsTheFixedQuerySample() {
        val attempt = ScriptProbeCache.Attempt(
            platform = "wy",
            quality = "flac",
            outcome = ScriptProbeCache.Outcome.NO_MATCH,
            sampleQueryName = "夜曲",
            sampleQuerySinger = "周杰伦",
            searchMs = 27L,
            requestDescription = "平台搜索未匹配样本；未进入脚本取链/媒体请求"
        )
        val decoded = ScriptProbeCache.Attempt.fromJson(attempt.toJson())
        val text = ScriptProbeCache.format(report(attempts = listOf(decoded)))

        assertEquals(ScriptProbeCache.Outcome.NO_MATCH, decoded.outcome)
        assertEquals("夜曲", decoded.sampleQueryName)
        assertEquals("周杰伦", decoded.sampleQuerySinger)
        assertTrue(text.contains("网易云/夜曲 - 周杰伦：flac"))
        assertTrue(text.contains("样本查询: 夜曲 - 周杰伦"))
        assertTrue(text.contains("样本未匹配，无法验证"))
        assertFalse(text.contains("未返回可验证链接 · 档位/平台可能不可用"))
    }

    @Test
    fun autoConfigurationUsesOnlyVerifiedSuccessesFromCurrentFullReports() {
        val second = Script(id = "script-b", name = "B", code = "lx.on('musicUrl', () => 2)")
        val noReport = Script(id = "script-c", name = "C", code = "lx.on('musicUrl', () => 3)")
        val failed = ScriptProbeCache.Attempt("kw", "320k", ScriptProbeCache.Outcome.FAILED, reason = "网络失败")
        val noMatch = ScriptProbeCache.Attempt("tx", "320k", ScriptProbeCache.Outcome.NO_MATCH, reason = "样本未匹配")
        val unsupported = ScriptProbeCache.Attempt("kg", "320k", ScriptProbeCache.Outcome.UNSUPPORTED, reason = "脚本未返回链接")
        val reportA = reportFor(script, listOf(verified("wy", "320k", 100L), failed, noMatch, unsupported))
        val reportB = reportFor(second, listOf(verified("wy", "320k", 400L), verified("tx", "128k", 50L)))
        val quickOnly = reportFor(script, listOf(verified("mg", "master", 1L)), type = ScriptProbeCache.ReportType.QUICK)
        val staleCode = reportFor(second, listOf(verified("mg", "master", 1L)), scriptHash = "stale-hash")

        val plan = ScriptProbeCache.buildAutoConfigurationPlan(
            scripts = listOf(script, second, noReport),
            reports = listOf(reportA, reportB, quickOnly, staleCode),
            platformCodes = listOf("wy", "kw", "tx", "kg", "mg")
        )

        assertEquals(2, plan.reportCount)
        assertEquals(3, plan.verifiedAttemptCount)
        assertEquals("tx", plan.platformOrder.first())
        assertEquals("320k", plan.platformQualities["wy"])
        assertEquals("128k", plan.platformQualities["tx"])
        assertEquals(listOf("script-a", "script-b", "script-c"), plan.platformScriptOrders["wy"])
        assertEquals(listOf("script-b", "script-a", "script-c"), plan.platformScriptOrders["tx"])
        assertFalse(plan.platformQualities.containsKey("kw")) // network failure is not a capability result
        assertTrue(plan.platformQualities.containsKey("tx"))
        assertFalse(plan.platformQualities.containsKey("kg")) // explicit unsupported is not a success
        assertFalse(plan.platformQualities.containsKey("mg")) // absent attempt is unprobed, not unsupported
        assertFalse(plan.platformScriptOrders.containsKey("mg"))
    }

    @Test
    fun autoConfigurationHasNoWritableEvidenceWhenReportsAreUnavailableOrHaveNoSuccess() {
        val failed = ScriptProbeCache.Attempt("wy", "320k", ScriptProbeCache.Outcome.FAILED, reason = "网络失败")
        val noMatch = ScriptProbeCache.Attempt("kw", "320k", ScriptProbeCache.Outcome.NO_MATCH, reason = "样本未匹配")
        val unsupported = ScriptProbeCache.Attempt("tx", "320k", ScriptProbeCache.Outcome.UNSUPPORTED)
        val noSuccessFull = reportFor(script, listOf(failed, noMatch, unsupported))
        val noReports = ScriptProbeCache.buildAutoConfigurationPlan(
            listOf(script),
            listOf(reportFor(script, listOf(verified("wy", "master", 10L)), type = ScriptProbeCache.ReportType.QUICK)),
            listOf("wy", "kw", "tx")
        )
        val noVerifiedResults = ScriptProbeCache.buildAutoConfigurationPlan(
            listOf(script), listOf(noSuccessFull), listOf("wy", "kw", "tx")
        )

        assertEquals(0, noReports.reportCount)
        assertFalse(noReports.hasVerifiedSuccess)
        assertEquals(1, noVerifiedResults.reportCount)
        assertEquals(0, noVerifiedResults.verifiedAttemptCount)
        assertFalse(noVerifiedResults.hasVerifiedSuccess)
        assertTrue(noVerifiedResults.platformQualities.isEmpty())
        assertTrue(noVerifiedResults.platformScriptOrders.isEmpty())
        assertEquals(listOf("script-a"), noVerifiedResults.globalScriptOrder)
        assertEquals(listOf("wy", "kw", "tx"), noVerifiedResults.platformOrder)
    }

    @Test
    fun startupTargetsOnlyEnabledScriptsWithoutCurrentCompleteFullReports() {
        val current = script
        val quickOnly = Script(id = "quick", name = "Quick", code = "lx.on('musicUrl', () => 2)")
        val stale = Script(id = "stale", name = "Stale", code = "lx.on('musicUrl', () => 3)")
        val disabled = Script(id = "disabled", name = "Disabled", code = "lx.on('musicUrl', () => 4)", enabled = false)
        val empty = Script(id = "empty", name = "Empty", code = "")
        val reports = mapOf(
            current.id to reportFor(current, listOf(verified("wy", "320k", 100L))),
            quickOnly.id to reportFor(quickOnly, emptyList(), type = ScriptProbeCache.ReportType.QUICK),
            stale.id to reportFor(stale, emptyList(), scriptHash = "old-hash"),
            disabled.id to reportFor(disabled, emptyList())
        )

        val targets = ScriptProbeStartupPolicy.missingFullReportTargets(
            listOf(current, quickOnly, stale, disabled, empty)
        ) { reports[it.id] }

        assertEquals(listOf(quickOnly, stale), targets)
    }

    @Test
    fun startupSortRequiresVerifiedSuccessAndRanksOnlyBySuppliedFullReportScore() {
        val slower = script
        val faster = Script(id = "script-fast", name = "Fast", code = "lx.on('musicUrl', () => 2)")
        val unmeasured = Script(id = "script-unmeasured", name = "Unmeasured", code = "lx.on('musicUrl', () => 3)")
        val noCode = Script(id = "script-empty", name = "Empty", code = "")
        val reports = mapOf(
            slower.id to reportFor(slower, listOf(verified("wy", "320k", 300L))),
            faster.id to reportFor(faster, listOf(verified("wy", "flac", 100L)))
        )
        val mode = ScriptProbeCache.SortMode.BALANCED

        val noEvidence = ScriptProbeStartupPolicy.sortWithVerifiedReports(
            listOf(slower, faster), mode, { null }
        ) { _, _ -> 100L }
        val noVerifiedSuccess = ScriptProbeStartupPolicy.sortWithVerifiedReports(
            listOf(slower), mode, { reportFor(slower, emptyList()) }
        ) { _, _ -> 100L }
        val sorted = ScriptProbeStartupPolicy.sortWithVerifiedReports(
            listOf(slower, unmeasured, faster, noCode),
            mode,
            { reports[it.id] },
            { target, _ -> if (target.id == faster.id) 20L else if (target.id == slower.id) 10L else Long.MIN_VALUE }
        )

        assertEquals(null, noEvidence)
        assertEquals(null, noVerifiedSuccess)
        assertEquals(listOf(faster, slower, unmeasured, noCode), sorted)
    }
}
