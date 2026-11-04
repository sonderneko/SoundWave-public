package xyz.cncnlp.soundwave

/**
 * ACCEPT4：取链阶段链的截止感知预算切片（纯函数，可测）。
 *
 * 病灶（审计 §10 结论一/三）：阶段链串行执行，前段（原平台/音质降级）可把
 * 26s 全局预算整段吃满，跨脚本/跨平台转移腿结构性排不到队；外层超时一到，
 * 整轮以「全局超时」收场。修法不是改链策略（阶段顺序与构成由
 * SmartSourceStore 决定，用户已确认），而是给每个阶段划一条截止线：
 * 阶段只能在自己的切片内发起新尝试，切片用尽即让位下一阶段；最后一个
 * 阶段的截止线 = 链预算终点。切片之外仍保留全局硬超时作最终闸门。
 *
 * 权重按各阶段的典型成本分配：原平台重试最便宜（首脚本两枪），音质降级
 * 与跨平台最贵（多脚本全阶梯 / 搜索+匹配+解析），跨脚本居中。权重只影响
 * 切片比例，不影响阶段顺序与每阶段内部的尝试顺序。
 */
internal object ResolveStageBudget {

    /** 单次脚本尝试获准发起所需的最小剩余切片：低于此值不再开新枪（软超时窗口给不满只会制造假超时）。 */
    const val MIN_ATTEMPT_SLICE_MS = 2_000L

    private fun weightOf(stage: String): Int = when (stage) {
        SmartSourceStore.STAGE_ORIGIN -> 3
        SmartSourceStore.STAGE_QUALITY -> 4
        SmartSourceStore.STAGE_CROSS_SCRIPT -> 3
        SmartSourceStore.STAGE_CROSS_PLAT -> 4
        else -> 2
    }

    /**
     * 返回与 [stages] 平行的累计截止偏移（相对链起点，毫秒）：
     * 第 i 个阶段不得晚于 `start + offsets[i]` 发起新尝试；末位恒等于
     * [chainBudgetMs]。阶段列表为空或预算非正时返回空/全零，调用方回退
     * 到无切片行为。
     */
    fun stageDeadlineOffsets(stages: List<String>, chainBudgetMs: Long): List<Long> {
        if (stages.isEmpty()) return emptyList()
        if (chainBudgetMs <= 0L) return stages.map { 0L }
        val totalWeight = stages.sumOf { weightOf(it) }.coerceAtLeast(1)
        var acc = 0L
        return stages.mapIndexed { index, stage ->
            acc += chainBudgetMs * weightOf(stage) / totalWeight
            if (index == stages.lastIndex) chainBudgetMs else acc
        }
    }

    /**
     * 本次尝试的软超时窗口：取阶段剩余与单脚本软超时常量的较小者；
     * 剩余不足 [MIN_ATTEMPT_SLICE_MS] 时返回非正值，调用方不得发起尝试。
     */
    fun attemptWindowMs(stageRemainingMs: Long, softTimeoutMs: Long): Long {
        if (stageRemainingMs < MIN_ATTEMPT_SLICE_MS) return 0L
        return minOf(softTimeoutMs, stageRemainingMs)
    }
}
