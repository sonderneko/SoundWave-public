package xyz.cncnlp.soundwave

import java.util.concurrent.ConcurrentHashMap

/**
 * ACCEPT5-covergate（台账 WAVE-ACCEPT5-COVERGATE）：封面取图的两项策略纯逻辑。
 *
 * 1. [CoverFailureCache]——ensureCover 失败负缓存（短 TTL）。封面审计 §六(b)3 实证：
 *    失败无负缓存时，每次重组/回滚都会整链重试同一批注定慢的下载，与并发闸饥饿叠加
 *    放大拥堵。这里按归一化 URL 记首次失败时刻，TTL 内 ensureCover 直接走失败回落
 *    （陈旧文件优先，否则原 URL → 面上垫底母题），不再打网络；成功即清。
 *    只记取图管线自身的失败（下载/校验/安装），不记存储闸（canWriteOptionalCache）等本地决策，
 *    不放宽任何既有校验（DNS 钉扎/图片头校验/大小上限全不变）。
 *
 * 2. [queueCoverPrewarmIndices]——队列打开时的有界预热选序：当前播放邻域优先
 *    （抽屉打开即滚动到当前曲目，可见行就在邻域内），队列头补齐（用户回滚到顶时命中）。
 */
internal class CoverFailureCache(
    private val ttlMs: Long = FAILURE_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val failures = ConcurrentHashMap<String, Long>()

    /** [nowMs] 时刻该 URL 是否仍在失败黑窗内。 */
    fun isRecentlyFailed(url: String, nowMs: Long): Boolean {
        val failedAt = failures[url] ?: return false
        // 时钟回拨（failedAt 在未来）保守视为仍在窗内，避免刚失败就被重试风暴打穿。
        return nowMs - failedAt < ttlMs
    }

    /**
     * 记一次失败。窗内重复失败**不刷新**时间戳——黑窗自首次失败起算，
     * 防止持续重试把黑窗无限续期；过期后再次失败才重新起算。
     */
    fun recordFailure(url: String, nowMs: Long) {
        val existing = failures[url]
        if (existing != null && nowMs - existing < ttlMs) return
        failures[url] = nowMs
        prune(nowMs)
    }

    /** 下载并校验成功后清除该 URL 的失败记录。 */
    fun recordSuccess(url: String) {
        failures.remove(url)
    }

    fun clear() {
        failures.clear()
    }

    internal fun sizeForTest(): Int = failures.size

    private fun prune(nowMs: Long) {
        if (failures.size <= maxEntries) return
        // 先清过期项；仍超限时按失败时刻最旧者逐个淘汰，保住较新的黑窗。
        failures.entries.removeAll { nowMs - it.value >= ttlMs }
        while (failures.size > maxEntries) {
            val oldest = failures.entries.minByOrNull { it.value } ?: break
            failures.remove(oldest.key, oldest.value)
        }
    }

    companion object {
        /**
         * TTL 3 分钟：重组/滚动的重试风暴发生在秒级，一次队列会话内的反复重组也多在
         * 分钟级——3 分钟足够把风暴整段吞掉；同时足够短，代理/网络抖动恢复后用户
         * 重新打开队列即可自然重试，不会长时间误 blank 好图。
         */
        const val FAILURE_TTL_MS = 3L * 60 * 1000

        /** 容量上限：远超单队列规模（178 首），仅防异常 URL 洪泛把内存表撑大。 */
        const val MAX_ENTRIES = 512
    }
}

/** 队列预热总量上限：邻域 16 + 队列头补齐 8 = 24（见选序函数说明）。 */
internal const val QUEUE_COVER_PREWARM_LIMIT = 24

/** 预热中「当前播放邻域」的名额：当前曲目 ± 环形展开至多取 16 个下标。 */
internal const val QUEUE_COVER_PREWARM_NEIGHBORHOOD = 16

/** 队列行封面请求的尺寸（与 DefaultPlayerQueuePage 行图 targetSizePx=144 同口径）。 */
internal const val QUEUE_COVER_PREWARM_TARGET_SIZE_PX = 144

/**
 * 队列打开预热的下标选序（纯函数）：
 * 1. 当前曲目起环形展开（+1、-1、+2、-2……）取至多 [neighborhood] 个——抽屉打开时
 *    可见行与手势最可能滚向的范围，也是点播下一首最可能命中的邻域；
 * 2. 再按队列头顺序（0、1、2……）补齐到 [limit]——用户滚回顶部或从头点播时命中。
 * [currentIndex] 不在队列范围内时只取队列头。[limit]/[neighborhood] 均按队列规模夹紧，
 * 去重且保持顺序。预热本身走共享预取池 UI 低优先通道（播放预取有保留名额），
 * 本函数只决定「预热哪些」，不决定并发。
 */
internal fun queueCoverPrewarmIndices(
    queueSize: Int,
    currentIndex: Int,
    limit: Int = QUEUE_COVER_PREWARM_LIMIT,
    neighborhood: Int = QUEUE_COVER_PREWARM_NEIGHBORHOOD,
): List<Int> {
    if (queueSize <= 0 || limit <= 0) return emptyList()
    val cap = minOf(limit, queueSize)
    val picked = LinkedHashSet<Int>()
    if (currentIndex in 0 until queueSize) {
        picked.add(currentIndex)
        var radius = 1
        while (picked.size < minOf(neighborhood, cap)) {
            val forward = currentIndex + radius
            val backward = currentIndex - radius
            var added = false
            if (forward in 0 until queueSize && picked.add(forward)) added = true
            if (picked.size >= minOf(neighborhood, cap)) break
            if (backward in 0 until queueSize && picked.add(backward)) added = true
            if (!added && forward >= queueSize && backward < 0) break
            radius++
        }
    }
    var head = 0
    while (picked.size < cap && head < queueSize) {
        picked.add(head)
        head++
    }
    return picked.toList()
}
