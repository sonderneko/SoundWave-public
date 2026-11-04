package xyz.cncnlp.soundwave.playback

/**
 * r657 可靠性 F3：前台失败终止（连续多首失败 STOP、不可继续推进的解密失败）统一收尾。
 *
 * 只有当调用方捕获的播放意图 [expectedIntent] 仍是当前意图时，才暂停播放器、发布真实「未在播放」快照并释放
 * 曲间过渡唤醒锁；过期事务（用户已暂停 / 点了新歌）一律不动，避免释放新事务刚持有的锁或把新歌暂停。
 * 检查在调度前、执行时各做一次（执行时为准）。依赖全部注入，JVM 单测可直接驱动。
 */
internal class FailedTransitionFinisher(
    private val isIntentCurrent: (Int) -> Boolean,
    private val pausePlayer: () -> Unit,
    private val releaseTransitionWake: () -> Unit,
    private val publishStopped: () -> Unit,
    private val runOnPlayerThread: (() -> Unit) -> Unit
) {
    /** @return false = 调度前即已过期，什么都不做。 */
    fun finish(expectedIntent: Int): Boolean {
        if (!isIntentCurrent(expectedIntent)) return false
        runOnPlayerThread {
            if (!isIntentCurrent(expectedIntent)) return@runOnPlayerThread
            try { pausePlayer() } finally {
                releaseTransitionWake()
                publishStopped()
            }
        }
        return true
    }
}
