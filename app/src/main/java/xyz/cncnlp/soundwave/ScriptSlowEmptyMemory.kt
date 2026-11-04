package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * ACCEPT6（qqslow）：慢速语义空返的短记忆——与 [ScriptTimeoutMemory] 同一
 * 分层纪律，补它与裁决/探针记忆都不覆盖的一格：
 * - 裁决只收脚本自报终局错误；超时记忆只收满窗软超时；探针记忆只收已
 *   交付 URL 的确定性死链。「脚本内部等后端数秒、最终语义空返」对三者
 *   全隐形，每首新歌都要在原平台/跨脚本段重新陪跑一遍（真机实证：
 *   安妮独家 kw FLAC 无结果约 3.3s，同类脚本快回 <0.5s）。
 * 记账纪律（ACCEPT4 负缓存口径）：只记 handler 已解析的**语义空**
 * （无传输失败分类）且实测耗时 ≥ [SLOW_FLOOR_MS]；网络/策略/超时类
 * 空返不入账——那不是脚本的事实。窗口内两次击发即降级，窗口滑过自动
 * 恢复；真实出链成功立即清账。粒度 = 脚本 × 平台 × 代码哈希（跨音质：
 * 慢与音质无关；不跨平台连坐——同脚本 kw 腿活、tx 腿慢是常态）。
 * 进程内实现，不落盘（同超时记忆理由：短记忆跨进程没有保留价值）。
 */
internal class ScriptSlowEmptyMemoryCore(
    private val windowMs: Long = WINDOW_MS,
    private val strikeThreshold: Int = STRIKE_THRESHOLD,
    private val slowFloorMs: Long = SLOW_FLOOR_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val strikes = mutableMapOf<String, MutableList<Long>>()

    /** 记一次慢速语义空返；未达慢速下限不记账（快速空返是正常未命中）。 */
    @Synchronized
    fun recordSlowEmpty(key: String, elapsedMs: Long) {
        if (elapsedMs < slowFloorMs) return
        val now = nowMs()
        val list = strikes.getOrPut(key) { mutableListOf() }
        list.removeAll { now - it > windowMs }
        list += now
    }

    /** 当前是否处于降级期：窗口内慢空返击发达到阈值。顺带清理过期击发。 */
    @Synchronized
    fun shouldSkip(key: String): Boolean {
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
        /** 滑动窗口：10 分钟——跨歌曲生效（逐歌陪跑正是要挡的形态），又远短于裁决 TTL。 */
        const val WINDOW_MS = 600_000L
        const val STRIKE_THRESHOLD = 2
        /** 慢速下限：软超时 6s 的一半；真机慢空返实证约 3.3s，正常空返 <1s。 */
        const val SLOW_FLOOR_MS = 3_000L
    }
}

/** 进程内单例门面：键的构成（owner/脚本/代码哈希/平台）与 [ScriptTimeoutMemory] 同构，调用方不拼键。 */
internal object ScriptSlowEmptyMemory {
    private val core = ScriptSlowEmptyMemoryCore()

    private fun key(owner: ProfileContext, script: Script, source: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(script.code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "${ProfileWorkIdentity.encodeOwner(owner)}:${owner.generation}|${script.id}|$hash|${source.lowercase()}"
    }

    fun recordSlowEmpty(owner: ProfileContext, script: Script, source: String, elapsedMs: Long) =
        core.recordSlowEmpty(key(owner, script, source), elapsedMs)

    fun shouldSkip(owner: ProfileContext, script: Script, source: String): Boolean =
        core.shouldSkip(key(owner, script, source))

    fun clearOnSuccess(owner: ProfileContext, script: Script, source: String) =
        core.clear(key(owner, script, source))

    fun clearAll() = core.clearAll()
}
