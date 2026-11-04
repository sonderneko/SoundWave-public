package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J 波：播放路径接入真实 ResolveAttempt 后的步骤构建（纯函数）。 */
class PlaybackPathAttemptsTest {

    private fun entry() = PlaybackDiagnostics.Entry(
        songKey = "tx:1", songName = "a", resolveMs = 5200, scriptId = "s2",
        platform = "tx", originPlatform = "tx", quality = "320k", prepareMs = 300, totalMs = 5500
    )

    private fun attempt(
        stage: String, platform: String = "tx", script: String = "s1", quality: String = "flac",
        elapsed: Long = 1000, outcome: ResolveAttempt.Outcome = ResolveAttempt.Outcome.EMPTY,
        reason: ResolveAttempt.Reason = ResolveAttempt.Reason.NONE
    ) = ResolveAttempt(stage, platform, script, quality, elapsed, outcome, reason)

    @Test fun strategyOrderRowFollowsExecutedStageOrder() {
        val attempts = listOf(
            attempt("origin"),
            attempt("cross", script = "s2"),
            attempt("cross_plat", platform = "kw", script = "match", quality = "320k", outcome = ResolveAttempt.Outcome.HIT)
        )
        val steps = playbackPathStepsWithAttempts(entry(), attempts)
        val strategy = steps[1]
        assertEquals("换源策略顺序", strategy.title)
        assertEquals("原平台解析 → 跨源解析 → 跨平台换源", strategy.detail)
    }

    @Test fun attemptStepsAreCumulativeWithOutcomeAndReason() {
        val attempts = listOf(
            attempt("origin", elapsed = 1200, reason = ResolveAttempt.Reason.SCRIPT_ERROR, outcome = ResolveAttempt.Outcome.ERROR),
            attempt("origin", script = "s2", quality = "320k", elapsed = 800, outcome = ResolveAttempt.Outcome.HIT)
        )
        val steps = playbackPathStepsWithAttempts(entry(), attempts)
        // 开始 + 策略 + 2 次尝试 + 已获取链接 + 正在加载 + 已就绪
        assertEquals(7, steps.size)
        assertEquals(1200L, steps[2].atMs)
        assertEquals(2000L, steps[3].atMs)
        assertTrue(steps[2].detail.contains("脚本执行失败"))
        assertEquals(PathStepKind.Success, steps[3].kind)
    }

    @Test fun degradeSwitchAndCrossPlatformTagsDerivedFromFacts() {
        val attempts = listOf(
            attempt("origin", quality = "flac"),
            attempt("origin", script = "s2", quality = "320k"),
            attempt("cross_plat", platform = "kw", script = "match", quality = "320k", outcome = ResolveAttempt.Outcome.HIT)
        )
        val steps = playbackPathStepsWithAttempts(entry(), attempts)
        assertTrue(steps[3].tags.any { it == "降级 FLAC→320K" })
        assertTrue(steps[3].tags.any { it == "切换音源" })
        assertTrue(steps[4].tags.any { it.startsWith("跨平台 ") && it.contains("→") })
        // 伪脚本标识 match 不当作脚本名打印
        assertFalse(steps[4].detail.contains("match"))
    }

    @Test fun shortCircuitEntriesKeepSynthesizedSteps() {
        val cached = entry().copy(fromUrlCache = true)
        val attempts = listOf(attempt("cache", script = "cache", outcome = ResolveAttempt.Outcome.HIT))
        assertEquals(playbackPathSteps(cached), playbackPathStepsWithAttempts(cached, attempts))
    }

    // ACCEPT4（修3）：探针拒收原文（分类文本）必须进时间线 detail——此前只显示
    // "链接校验未通过"，HTTP 403 与试听片段无从区分；机器码形态不得直出。
    @Test fun mediaUnverifiedAttemptsCarryProbeReasonText() {
        val attempts = listOf(
            ResolveAttempt(
                stage = "origin", platform = "tx", scriptId = "s1", quality = "flac",
                elapsedMs = 250, outcome = ResolveAttempt.Outcome.SKIPPED,
                reasonCode = ResolveAttempt.Reason.MEDIA_UNVERIFIED, reason = "权限拒绝 HTTP 403"
            ),
            ResolveAttempt(
                stage = "origin", platform = "tx", scriptId = "s1", quality = "320k",
                elapsedMs = 250, outcome = ResolveAttempt.Outcome.SKIPPED,
                reasonCode = ResolveAttempt.Reason.MEDIA_UNVERIFIED, reason = "media_unverified"
            )
        )
        val steps = playbackPathStepsWithAttempts(entry(), attempts)
        assertTrue(steps[2].detail.contains("链接校验未通过"))
        assertTrue(steps[2].detail.contains("权限拒绝 HTTP 403"))
        assertFalse(steps[3].detail.contains("media_unverified"))
    }

    @Test fun timeoutMemoryReasonHasLabel() {
        assertEquals("近期多次超时，暂跳过", playbackAttemptReasonLabel(ResolveAttempt.Reason.SCRIPT_TIMEOUT_MEMORY))
    }

    // ACCEPT5（E2）：本轮无果熔断行标签。
    @Test fun emptyMemoryReasonHasLabel() {
        assertEquals("本轮多次无结果，暂跳过", playbackAttemptReasonLabel(ResolveAttempt.Reason.SCRIPT_EMPTY_MEMORY))
    }

    // ACCEPT5（conformance/D）：计划内跳过行与探针记忆行标签。
    @Test fun planSkipAndProbeMemoryReasonsHaveLabels() {
        assertEquals("本轮已尝试，跳过", playbackAttemptReasonLabel(ResolveAttempt.Reason.ALREADY_ATTEMPTED))
        assertEquals("近期探针死链，暂跳过", playbackAttemptReasonLabel(ResolveAttempt.Reason.SCRIPT_PROBE_MEMORY))
    }

    // ACCEPT6（qqslow）：慢速语义空返记忆行标签。
    @Test fun slowEmptyMemoryReasonHasLabel() {
        assertEquals("近期多次慢速空返，暂跳过", playbackAttemptReasonLabel(ResolveAttempt.Reason.SCRIPT_SLOW_EMPTY_MEMORY))
    }

    // ACCEPT6（qqslow）：路径单去重——同腿实质行之后的「本轮已尝试」行不再
    // 重复展示（真机实证：停服失败行+跳过行成对出现）；不同音质是不同的腿，不吞。
    @Test fun displayDedupDropsAlreadyAttemptedAfterSubstantiveRow() {
        val attempts = listOf(
            attempt("origin", outcome = ResolveAttempt.Outcome.ERROR, reason = ResolveAttempt.Reason.SCRIPT_TERMINAL),
            attempt("quality", outcome = ResolveAttempt.Outcome.SKIPPED, reason = ResolveAttempt.Reason.ALREADY_ATTEMPTED),
            attempt("quality", quality = "320k", outcome = ResolveAttempt.Outcome.EMPTY, reason = ResolveAttempt.Reason.NO_URL)
        )
        val deduped = dedupeAttemptsForDisplay(attempts)
        assertEquals(2, deduped.size)
        assertEquals(ResolveAttempt.Reason.SCRIPT_TERMINAL, deduped[0].reasonCode)
        assertEquals(ResolveAttempt.Reason.NO_URL, deduped[1].reasonCode)
    }

    // ACCEPT6（qqslow）：跨源两轮搜索（直连轮+服务端回落轮）对同一候选产出的
    // 完全相同拒绝行只留一组；拒因不同则不是重复行，保留。
    @Test fun displayDedupCollapsesExactDuplicateRejectRows() {
        val reject = attempt("cross", script = "match", quality = "", outcome = ResolveAttempt.Outcome.SKIPPED, reason = ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED)
            .copy(reason = "artist")
        val other = reject.copy(reason = "title")
        val deduped = dedupeAttemptsForDisplay(listOf(reject, reject, other))
        assertEquals(2, deduped.size)
    }

    // ACCEPT5（策略 conformance）：策略行宣布完整计划——原平台中途成功时，
    // 跨平台腿仍在计划行里（旧口径只报实际执行到的阶段，看起来像没排跨平台）。
    // ACCEPT5 删除兜底棒改钉：计划终点原为服务端兜底段（official-server-fallback），
    // 该腿整条删除后计划以 oiapi 本机段收尾——「宣布完整计划」的行为断言不变，
    // 仅终点段按删除合同换为 official-oiapi。
    @Test fun strategyRowAnnouncesFullPlanIncludingUnexecutedCrossPlatform() {
        val attempts = listOf(
            attempt("origin", outcome = ResolveAttempt.Outcome.HIT)
        )
        val plan = listOf("official-direct", "origin", "quality", "cross_script", "cross_plat", "official-oiapi")
        val steps = playbackPathStepsWithAttempts(entry(), attempts, plan)
        assertEquals("换源策略顺序", steps[1].title)
        assertTrue(steps[1].detail.contains("跨平台换源"))
        assertTrue(steps[1].detail.contains("oiapi 取链"))
        // 无计划留痕时回退旧口径（只报已执行阶段）。
        val legacy = playbackPathStepsWithAttempts(entry(), attempts)
        assertFalse(legacy[1].detail.contains("跨平台换源"))
    }

    // ACCEPT5（时钟基统一）：步骤时刻锚定尝试的开始偏移 + 耗时，不再累计
    // 求和。求和口径的爆点是整轮量级的行（budget 尾注的 elapsed = 整轮耗时）：
    // 一入列，后续偏移被整段重复计入（实证 +13.845s vs 取链 10509ms）。
    // 锚定口径下每步时刻 = 自身开始 + 自身耗时，与取链总时长可互相推导。
    @Test fun stepTimesAnchorToAttemptStartOffsetsNotCumulativeSums() {
        val attempts = listOf(
            ResolveAttempt(
                stage = "origin", platform = "mg", scriptId = "s1", quality = "master",
                elapsedMs = 3000, outcome = ResolveAttempt.Outcome.EMPTY,
                reasonCode = ResolveAttempt.Reason.NO_URL, reason = "no_url",
                startedAtMs = 0
            ),
            ResolveAttempt(
                stage = "budget", platform = "mg", scriptId = "", quality = "flac",
                elapsedMs = 10509, outcome = ResolveAttempt.Outcome.TIMEOUT,
                reasonCode = ResolveAttempt.Reason.GLOBAL_TIMEOUT, reason = "global_timeout",
                startedAtMs = 0
            )
        )
        val mgEntry = entry().copy(platform = "mg", originPlatform = "mg", resolveMs = 10509, totalMs = 11404)
        val steps = playbackPathStepsWithAttempts(mgEntry, attempts)
        assertEquals(3000L, steps[2].atMs)
        // 锚定：0 + 10509 = 取链总时长本身；累计求和口径会给出 13509（虚增 3s）。
        assertEquals(10509L, steps[3].atMs)
        // 无偏移记录的旧行回退累计口径（行为不变）。
        val legacy = listOf(
            attempt("origin", elapsed = 1200),
            attempt("origin", script = "s2", elapsed = 800, outcome = ResolveAttempt.Outcome.HIT)
        )
        val legacySteps = playbackPathStepsWithAttempts(entry(), legacy)
        assertEquals(1200L, legacySteps[2].atMs)
        assertEquals(2000L, legacySteps[3].atMs)
    }

    // ACCEPT5（E3）：跨源未匹配行必须带字段级原因——单独原因与汇总行两种形态；
    // 只显示「跨源未匹配到候选」不足以区分数据缺失与真没这首歌。
    @Test fun candidateRejectRowsCarryFieldLevelReason() {
        val attempts = listOf(
            ResolveAttempt(
                stage = "cross", platform = "wy", scriptId = "match", quality = "",
                elapsedMs = 0, outcome = ResolveAttempt.Outcome.SKIPPED,
                reasonCode = ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED, reason = "duration_missing"
            ),
            ResolveAttempt(
                stage = "cross", platform = "kg", scriptId = "match", quality = "",
                elapsedMs = 0, outcome = ResolveAttempt.Outcome.SKIPPED,
                reasonCode = ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED,
                reason = "reject_summary:duration_missing:9"
            )
        )
        val steps = playbackPathStepsWithAttempts(entry(), attempts)
        assertTrue(steps[2].detail.contains("跨源未匹配到候选"))
        assertTrue(steps[2].detail.contains("缺时长"))
        assertTrue(steps[3].detail.contains("另有 9 个候选被拒"))
    }

    // ACCEPT5（A）：直连空结果行必须带传输定性——跳转 404 与网络失败要在弹层
    // 可区分（旧形态只有一个 direct_empty 机器码，失败不可定性）。
    @Test fun directEmptyRowsCarryTransportDetail() {
        assertEquals("直连未出链（歌曲受限，官方直链 404）", directEmptyDetailLabel("direct_empty:redirect_404"))
        assertEquals("直连未出链（请求超时）", directEmptyDetailLabel("direct_empty:transport_timeout"))
        assertEquals("直连未出链（无跳转应答（HTTP 200））", directEmptyDetailLabel("direct_empty:no_redirect_200"))
        assertEquals("oiapi 未出链（接口无数据）", directEmptyDetailLabel("oiapi_empty:oiapi_no_data"))
        assertEquals("", directEmptyDetailLabel("no_url"))
        val attempts = listOf(
            ResolveAttempt(
                stage = "official-direct", platform = "wy", scriptId = "wy-direct", quality = "320k",
                elapsedMs = 300, outcome = ResolveAttempt.Outcome.EMPTY,
                reasonCode = ResolveAttempt.Reason.NO_URL, reason = "direct_empty:redirect_404"
            )
        )
        val wyEntry = entry().copy(platform = "wy", originPlatform = "wy")
        val steps = playbackPathStepsWithAttempts(wyEntry, attempts)
        assertTrue(steps[2].detail.contains("官方直链 404"))
    }

    // ACCEPT4（修7）：副标题与状态徽标同源——失败条目无音质/耗时事实时写
    // "解析失败"，不再出现徽标=失败、副标题=仍在解析的矛盾。
    @Test fun subtitleFollowsStatusWhenNoFacts() {
        val failed = entry().copy(quality = "", totalMs = -1, resolveMs = -1, failureLayer = "resolve")
        assertEquals(PathStatus.Failed, playbackPathStatus(failed))
        assertEquals("解析失败", playbackPathSubtitle(failed))
        val resolving = entry().copy(quality = "", totalMs = -1, resolveMs = -1)
        assertEquals(PathStatus.Resolving, playbackPathStatus(resolving))
        assertEquals("仍在解析", playbackPathSubtitle(resolving))
        // 有事实时事实优先，与旧展示一致。
        assertTrue(playbackPathSubtitle(entry()).contains("音质 320K"))
    }

    // ACCEPT4（修7）：复制诊断带 attempts 时用真实时间线（含探针拒收原文），
    // 不再只有合成四步的 "+0s" 假即时失败。
    @Test fun songDiagnosticsTextIncludesRealAttempts() {
        val attempts = listOf(
            ResolveAttempt(
                stage = "origin", platform = "tx", scriptId = "s1", quality = "flac",
                elapsedMs = 250, outcome = ResolveAttempt.Outcome.SKIPPED,
                reasonCode = ResolveAttempt.Reason.MEDIA_UNVERIFIED, reason = "权限拒绝 HTTP 403"
            )
        )
        val text = PlaybackDiagnostics.songDiagnosticsText(entry(), attempts)
        assertTrue(text.contains("权限拒绝 HTTP 403"))
        assertTrue(text.contains("换源策略顺序"))
        // 无 attempts 时回退合成时间线（旧行为不回退）。
        val fallback = PlaybackDiagnostics.songDiagnosticsText(entry(), emptyList())
        assertEquals(PlaybackDiagnostics.songDiagnosticsText(entry()), fallback)
    }

    private fun localEntry() = PlaybackDiagnostics.Entry(
        songKey = "wy:186016", songName = "雨爱", resolveMs = 0, scriptId = "local",
        platform = "local", originPlatform = "wy", quality = "128k", prepareMs = 40, totalMs = 120
    )

    private fun localAttempt() = ResolveAttempt(
        stage = STAGE_LOCAL_FILE, platform = "wy", scriptId = "local", quality = "128k",
        elapsedMs = 0, outcome = ResolveAttempt.Outcome.HIT, reason = "downloaded_local", startedAtMs = 0
    )

    // ACCEPT5（本地直读）：本地条目 + 本地尝试行 → 策略行宣布「本地文件」，
    // 成功行带实测音质，且不得出现「已获取真实播放链接」这类联网收尾。
    @Test fun localPlaybackAnnouncesLocalFilePlanAndAttempt() {
        val steps = playbackPathStepsWithAttempts(localEntry(), listOf(localAttempt()), listOf(STAGE_LOCAL_FILE))
        assertEquals("换源策略顺序", steps[1].title)
        assertEquals("本地文件", steps[1].detail)
        val hit = steps[2]
        assertEquals("本地文件", hit.title)
        assertEquals(PathStepKind.Success, hit.kind)
        assertTrue(hit.detail.contains("128K"))
        assertTrue(hit.detail.contains("成功"))
        assertFalse(steps.any { it.title == "已获取真实播放链接" })
        assertTrue(steps.last().title == "音频数据已就绪")
    }

    // ACCEPT5（本地直读）：本地条目但留存仍是旧联网尝试 → 回退合成时间线，
    // 合成行明说「已下载」并带音质标签，不把旧取链链冒充成本次播放。
    @Test fun localEntryWithStaleOnlineAttemptsFallsBackToSyntheticLocalSteps() {
        val steps = playbackPathStepsWithAttempts(localEntry(), listOf(attempt("origin")), listOf("origin"))
        assertFalse(steps.any { it.title == "换源策略顺序" })
        val local = steps.first { it.title == "本地文件" }
        assertTrue(local.detail.contains("已下载"))
        assertTrue(local.tags.contains("128K"))
    }

    // ACCEPT5（本地直读）：本机扫描文件（原平台即 local）的合成文案不冒称「已下载」。
    @Test fun scannedLocalFileKeepsPlainLocalWording() {
        val e = localEntry().copy(originPlatform = "local", quality = "本地")
        val steps = playbackPathSteps(e)
        val local = steps.first { it.title == "本地文件" }
        assertEquals("直接读取本机音频文件，无需取链", local.detail)
    }

    @Test fun labelsCoverKnownStagesAndPassUnknownThrough() {
        assertEquals("平台官方直连", playbackAttemptStageLabel("official-direct"))
        assertEquals("跨脚本换源", playbackAttemptStageLabel(SmartSourceStore.STAGE_CROSS_SCRIPT))
        assertEquals("mystery", playbackAttemptStageLabel("mystery"))
        assertEquals("被网络策略拦截", playbackAttemptReasonLabel(ResolveAttempt.Reason.POLICY_BLOCKED))
        assertEquals("", playbackAttemptReasonLabel(ResolveAttempt.Reason.NONE))
    }
}
