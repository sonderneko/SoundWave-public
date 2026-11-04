package xyz.cncnlp.soundwave.playback

/**
 * ACCEPT6-FREEZE：解析进度文案的发布节流（最新优先合并）。
 *
 * 背景（台账 WAVE-ACCEPT6-FREEZE）：UrlResolver 每完成一次尝试就经 onProgress 回调
 * 写 PlaybackController.status（Compose 状态）。慢解析在快失败阶段（直连/跨源候选）
 * 每秒可推进数次，每次写都使消费 status/resolving 的组合作用域整树重组，主线程在
 * 整个解析窗口被重组占满（「加载多久冻多久、出声即恢复」）。进度文案只是「正在尝试
 * 什么」的滚动提示，语义上只需最新值：本合并器把发布 cadence 压到 [minIntervalMs]，
 * 窗口内的新值只保留最新一条，下一次到窗的 offer 发布它（而不是过期值）。终局文案
 * （成功/失败后的 status 清空与失败说明）不走 onProgress、由控制器直接写状态，不受
 * 本节流影响。
 *
 * 线程：仅由单次 resolveAndPlay 的解析链调用（onProgress 在解析的 IO 线程触发、
 * 写入动作由调用方在 offer 返回非空时执行）；解析阶段切换会换 IO 线程，内部状态
 * 以 @Synchronized 串行化（调用频率低，无竞争成本）。
 */
internal class ResolveProgressCoalescer(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var lastPublishedMs: Long = Long.MIN_VALUE
    private var pending: String? = null

    /** 递入一条最新进度文案；返回非空表示「现在就发布这条」，空表示已合并待后续发布。 */
    @Synchronized
    fun offer(label: String): String? {
        val now = clock()
        val elapsed = if (lastPublishedMs == Long.MIN_VALUE) Long.MAX_VALUE else now - lastPublishedMs
        return if (elapsed >= minIntervalMs) {
            lastPublishedMs = now
            pending = null
            label
        } else {
            pending = label
            null
        }
    }

    /** 解析收尾时取走尚未发布的最新一条（调用方决定是否补发）；取后即清空。 */
    @Synchronized
    fun drainPending(): String? {
        val p = pending
        pending = null
        if (p != null) lastPublishedMs = clock()
        return p
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS: Long = 350L
    }
}
