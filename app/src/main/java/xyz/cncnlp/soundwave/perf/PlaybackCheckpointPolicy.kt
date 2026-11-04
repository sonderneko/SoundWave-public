package xyz.cncnlp.soundwave.perf

/**
 * r658c F：定期断点保存的跳过规则（纯函数，可单测）。
 *
 * 旧实现每 5 秒在主线程把整条队列逐首 toJson、再把上一份断点整串 JSON 解析一遍，
 * 最后 apply()（ContextPreferences 还会给大体积 userdata 文件 bump 版本号触发整文件重写）。
 * 几千首的队列每 5 秒主线程卡几十到上百毫秒。现在挪到后台单线程，且暂停中/内容未变时直接跳过。
 */
internal object PlaybackCheckpointPolicy {
    data class Mark(
        val songKey: String,
        val generation: Int,
        val queueIdentity: Int,
        val queueIndex: Int,
        val isPlaying: Boolean,
        val positionMs: Long
    )

    /** 与上次已保存断点相比无实质变化（同曲同队列同状态，进度差 < [minPositionDeltaMs]）则跳过。 */
    fun shouldSkip(last: Mark?, next: Mark, minPositionDeltaMs: Long = 1_000L): Boolean {
        if (last == null) return false
        return last.songKey == next.songKey &&
            last.generation == next.generation &&
            last.queueIdentity == next.queueIdentity &&
            last.queueIndex == next.queueIndex &&
            last.isPlaying == next.isPlaying &&
            kotlin.math.abs(last.positionMs - next.positionMs) < minPositionDeltaMs
    }
}
