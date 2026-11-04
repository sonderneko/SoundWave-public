package xyz.cncnlp.soundwave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * ACCEPT5（D）：探针死链短记忆语义：
 * - 只有确定性 HTTP 状态（401/403/404/410）入记忆；其余状态不记；
 * - 粒度 = 脚本×平台×音质×代码哈希，不放大（MASTER 死链不连坐 FLAC——
 *   真机有同脚本 MASTER 404 后 FLAC 成功的反例）；
 * - 30 分钟 TTL 过期自动失效；成功清除；脚本改版（哈希变）自动失效。
 */
@RunWith(RobolectricTestRunner::class)
class ScriptProbeMemoryStoreTest {
    private val owner = ProfileWorkIdentity.runtime()

    @Before
    fun setUp() {
        ScriptProbeMemoryStore.init(RuntimeEnvironment.getApplication())
    }

    private fun script(id: String, code: String = "module.exports = {}") =
        Script(id = id, name = "探针记忆测试脚本", code = code)

    @Test
    fun definitiveProbeFailureIsMemorizedPerExactCombo() {
        val sc = script("probe-basic")
        assertFalse(ScriptProbeMemoryStore.isMemorized(owner, sc, "mg", "master"))
        ScriptProbeMemoryStore.recordProbeFailure(owner, sc, "mg", "master", 404)
        assertTrue(ScriptProbeMemoryStore.isMemorized(owner, sc, "mg", "master"))
        // 粒度不放大：同脚本的 FLAC 与其它平台不被 MASTER 的 404 连坐。
        assertFalse(ScriptProbeMemoryStore.isMemorized(owner, sc, "mg", "flac"))
        assertFalse(ScriptProbeMemoryStore.isMemorized(owner, sc, "tx", "master"))
    }

    @Test
    fun nonDefinitiveStatusesAreNotMemorized() {
        val sc = script("probe-nondeterministic")
        for (status in listOf(0, 200, 206, 429, 500, 502, 503)) {
            ScriptProbeMemoryStore.recordProbeFailure(owner, sc, "mg", "flac", status)
            assertFalse("status $status must not be memorized", ScriptProbeMemoryStore.isMemorized(owner, sc, "mg", "flac"))
        }
        for (status in listOf(401, 403, 404, 410)) {
            val other = script("probe-definitive-$status")
            ScriptProbeMemoryStore.recordProbeFailure(owner, other, "mg", "flac", status)
            assertTrue("status $status must be memorized", ScriptProbeMemoryStore.isMemorized(owner, other, "mg", "flac"))
        }
    }

    @Test
    fun memoryExpiresAfterTtl() {
        val sc = script("probe-expired")
        val longAgo = System.currentTimeMillis() - ScriptProbeMemoryStore.PROBE_MEMORY_TTL_MS - 60_000L
        ScriptProbeMemoryStore.recordProbeFailureAt(owner, sc, "mg", "flac", 404, longAgo)
        assertFalse(ScriptProbeMemoryStore.isMemorized(owner, sc, "mg", "flac"))
    }

    @Test
    fun successClearsMemoryAndCodeChangeInvalidates() {
        val sc = script("probe-clear")
        ScriptProbeMemoryStore.recordProbeFailure(owner, sc, "kg", "320k", 403)
        assertTrue(ScriptProbeMemoryStore.isMemorized(owner, sc, "kg", "320k"))
        ScriptProbeMemoryStore.clearOnSuccess(owner, sc, "kg", "320k")
        assertFalse(ScriptProbeMemoryStore.isMemorized(owner, sc, "kg", "320k"))

        ScriptProbeMemoryStore.recordProbeFailure(owner, sc, "kg", "320k", 404)
        val updated = sc.copy(code = "module.exports = { v: 2 }")
        assertFalse(ScriptProbeMemoryStore.isMemorized(owner, updated, "kg", "320k"))
    }
}
