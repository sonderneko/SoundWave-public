package xyz.cncnlp.soundwave.perf

import android.content.SharedPreferences
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * r656-G：把“整包 JSON 序列化 + SharedPreferences 写入”从主线程挪到单线程后台，并合并突发写。
 *
 * 语义：
 * - [enqueue] 只登记“最新一份快照的生产函数”（调用方必须传入已拷贝、不会再被修改的快照），
 *   [delayMs] 内的多次登记只序列化最后一份（合并写）。
 * - 任何读取/其它写入路径在访问同一 SharedPreferences 前调用 [flush]，同步落下待写快照，
 *   因此进程内“写后读”与“写入顺序”都与旧的同步实现一致。
 * - 生产函数在持有内部锁时执行；它不得再获取其它锁（只做纯序列化），以免锁序反转。
 */
internal class DeferredPrefsWriter(
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val executor: ScheduledExecutorService = defaultExecutor
) {
    private class PendingWrite(val key: String, val produce: () -> String)

    private val lock = Any()
    // SharedPreferencesImpl 按文件名进程内单例，用身份比较即可区分不同文件。
    private val pending = IdentityHashMap<SharedPreferences, PendingWrite>()
    private val serializationCount = AtomicInteger(0)

    /** 测试/诊断：真正执行过的序列化次数。 */
    val serializations: Int get() = serializationCount.get()

    fun enqueue(preferences: SharedPreferences, key: String, produce: () -> String) {
        val schedule = synchronized(lock) {
            val first = pending[preferences] == null
            pending[preferences] = PendingWrite(key, produce)
            first
        }
        if (schedule) {
            try {
                executor.schedule({ flush(preferences) }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                // 执行器不可用（极端情况）时退回同步写，绝不丢数据。
                flush(preferences)
            }
        }
    }

    fun hasPending(preferences: SharedPreferences): Boolean = synchronized(lock) { pending.containsKey(preferences) }

    /** 同步落下 [preferences] 上的待写快照（若有）。 */
    fun flush(preferences: SharedPreferences) {
        synchronized(lock) {
            val write = pending.remove(preferences) ?: return
            commitLocked(preferences, write)
        }
    }

    fun flushAll() {
        synchronized(lock) {
            // IdentityHashMap 的 Entry 是表内视图，clear 之后会失效；先复制成独立的 Pair。
            val entries = pending.entries.map { it.key to it.value }
            pending.clear()
            entries.forEach { (preferences, write) -> commitLocked(preferences, write) }
        }
    }

    private fun commitLocked(preferences: SharedPreferences, write: PendingWrite) {
        val raw = try {
            write.produce()
        } catch (_: Throwable) {
            return
        }
        serializationCount.incrementAndGet()
        preferences.edit().putString(write.key, raw).apply()
    }

    companion object {
        const val DEFAULT_DELAY_MS = 250L
        private val defaultExecutor: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "sw-prefs-writer").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
            }
        }
    }
}
