package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * ACCEPT4：脚本软超时的短记忆（分层记忆的短层，与 ScriptVerdictStore 的
 * 持久裁决同一纪律、不同载体——见 WAVE-ACCEPT4-PLAYBACK 台账 Sollin 核销 1）。
 *
 * 病灶（审计 §10 结论三）：软超时按 H2-b 口径永不记账，90s 歌曲级冷却又只拦
 * 跨脚本段，于是同一挂死脚本在原平台/音质段被反复执行，每次播放、每首新歌
 * 都重跑同一串 6s 绞杀。本层补的正是这块记忆，纪律与 Sollin 裁决一致：
 * **只有可证伪的终局事实配长记忆；超时/网络类只配短记忆**——
 * - 粒度 = 脚本 × 目标平台 × 脚本代码哈希（跨音质：挂死与音质无关，不得逐档重烧；
 *   代码变则旧账自动失效，与裁决存储同口径）；
 * - 击发 = 窗口内累计 [STRIKE_THRESHOLD] 次「全窗口软超时」（被阶段切片压短的
 *   窗口不计击发——那是预算决定，不是脚本的事实）；
 * - 降权期 = 最后一击起 [WINDOW_MS] 内直接跳过不执行；窗口滑过自动恢复试探，
 *   无需手动清理；任一次真实出链成功立即清账。
 * 进程内内存实现：不落盘（短记忆的事实跨进程重启没有保留价值，落盘反而
 * 需要额外的过期治理），进程重启即全清。
 */
internal class ScriptTimeoutMemoryCore(
    private val windowMs: Long = WINDOW_MS,
    private val strikeThreshold: Int = STRIKE_THRESHOLD,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val strikes = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun recordTimeout(key: String) {
        val now = nowMs()
        val list = strikes.getOrPut(key) { mutableListOf() }
        list.removeAll { now - it > windowMs }
        list += now
    }

    /** 当前是否处于降权期：窗口内击发达到阈值。顺带清理过期击发。 */
    @Synchronized
    fun isDegraded(key: String): Boolean {
        val list = strikes[key] ?: return false
        val now = nowMs()
        list.removeAll { now - it > windowMs }
        if (list.isEmpty()) {
            strikes.remove(key)
            return false
        }
        return list.size >= strikeThreshold
    }

    @Synchronized
    fun clear(key: String) {
        strikes.remove(key)
    }

    @Synchronized
    fun clearAll() {
        strikes.clear()
    }

    @Synchronized
    fun strikeCount(key: String): Int {
        val list = strikes[key] ?: return 0
        val now = nowMs()
        return list.count { now - it <= windowMs }
    }

    companion object {
        /** 短记忆窗口：两分钟内两次全窗口软超时即降权，窗口滑过自动恢复。 */
        const val WINDOW_MS = 120_000L
        const val STRIKE_THRESHOLD = 2
    }
}

/** 进程内单例门面：键的构成（owner/脚本/代码哈希/平台）在此收口，调用方不拼键。 */
internal object ScriptTimeoutMemory {
    private val core = ScriptTimeoutMemoryCore()

    private fun key(owner: ProfileContext, script: Script, source: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(script.code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "${ProfileWorkIdentity.encodeOwner(owner)}:${owner.generation}|${script.id}|$hash|${source.lowercase()}"
    }

    fun recordTimeout(owner: ProfileContext, script: Script, source: String) =
        core.recordTimeout(key(owner, script, source))

    fun isDegraded(owner: ProfileContext, script: Script, source: String): Boolean =
        core.isDegraded(key(owner, script, source))

    fun clearOnSuccess(owner: ProfileContext, script: Script, source: String) =
        core.clear(key(owner, script, source))

    fun clearAll() = core.clearAll()
}
