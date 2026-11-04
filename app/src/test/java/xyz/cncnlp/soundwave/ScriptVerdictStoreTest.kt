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
 * H1-a 持久裁决语义：
 * - 终局错误落裁后，同一（脚本×平台×音质）跨歌命中（isVerdicted）——后续歌曲秒跳；
 * - 裁决粒度不放大：换音质/换平台/脚本改版（代码哈希变）都不命中；
 * - 成功出链清裁；裁决过期（TTL 7 天）自动失效。
 * “同首不重试”与“EMPTY 不入裁决”由 UrlResolver 的分类通道保证（recordTerminal
 * 是唯一写入口，仅在分类器判终局时调用），分类边界由
 * ScriptTerminalErrorClassifierTest 钉住。
 */
@RunWith(RobolectricTestRunner::class)
class ScriptVerdictStoreTest {
    private val owner = ProfileWorkIdentity.runtime()

    @Before
    fun setUp() {
        ScriptVerdictStore.init(RuntimeEnvironment.getApplication())
    }

    private fun script(id: String, code: String = "module.exports = {}") =
        Script(id = id, name = "裁决测试脚本", code = code)

    @Test
    fun terminalVerdictPersistsPerScriptSourceQuality() {
        val sc = script("verdict-basic")
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "tx", "320k"))
        ScriptVerdictStore.recordTerminal(owner, sc, "tx", "320k")
        // 跨歌命中：存储层无歌曲维度，下一首歌走到 tryScript 即被跳过。
        assertTrue(ScriptVerdictStore.isVerdicted(owner, sc, "tx", "320k"))
        // 粒度不放大：其它音质/平台不被连坐。
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "tx", "flac"))
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "wy", "320k"))
    }

    @Test
    fun verdictDoesNotSurviveScriptCodeChange() {
        val sc = script("verdict-codehash")
        ScriptVerdictStore.recordTerminal(owner, sc, "tx", "320k")
        val updated = sc.copy(code = "module.exports = { v: 2 }")
        assertFalse(ScriptVerdictStore.isVerdicted(owner, updated, "tx", "320k"))
    }

    @Test
    fun successfulResolutionClearsVerdict() {
        val sc = script("verdict-clear")
        ScriptVerdictStore.recordTerminal(owner, sc, "kg", "128k")
        assertTrue(ScriptVerdictStore.isVerdicted(owner, sc, "kg", "128k"))
        ScriptVerdictStore.clearVerdict(owner, sc, "kg", "128k")
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "kg", "128k"))
    }

    @Test
    fun expiredVerdictNoLongerMatches() {
        val sc = script("verdict-expired")
        val eightDaysAgo = System.currentTimeMillis() - ScriptVerdictStore.TERMINAL_TTL_MS - 24 * 3600 * 1000L
        ScriptVerdictStore.recordTerminalAt(owner, sc, "tx", "320k", eightDaysAgo)
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "tx", "320k"))
    }

    // ACCEPT4（修3）：软终局只配 30 分钟短裁决——新鲜时命中、过期即失效；
    // 同一时间点的硬终局仍在 7 天长记忆内。分级前两者同为 7 天，瞬时/误报
    // 失败会被化石化一周。
    @Test
    fun shortVerdictExpiresAfterThirtyMinutesWhileHardVerdictPersists() {
        val soft = script("verdict-short")
        val hard = script("verdict-hard")
        val thirtyOneMinutesAgo = System.currentTimeMillis() - ScriptVerdictStore.SHORT_TTL_MS - 60_000L
        ScriptVerdictStore.recordShortAt(owner, soft, "wy", "flac", thirtyOneMinutesAgo)
        ScriptVerdictStore.recordTerminalAt(owner, hard, "wy", "flac", thirtyOneMinutesAgo)
        assertFalse(ScriptVerdictStore.isVerdicted(owner, soft, "wy", "flac"))
        assertTrue(ScriptVerdictStore.isVerdicted(owner, hard, "wy", "flac"))
    }

    @Test
    fun freshShortVerdictMatchesWithSameGranularity() {
        val sc = script("verdict-short-fresh")
        ScriptVerdictStore.recordShortVerdict(owner, sc, "kg", "320k")
        assertTrue(ScriptVerdictStore.isVerdicted(owner, sc, "kg", "320k"))
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "kg", "flac"))
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "wy", "320k"))
    }

    // ACCEPT5（E1）：世代 1 存量（裸 Long 格式、分级前单档 7 天、写于全链失效
    // 窗口）在世代 2 下整体失效——哪怕条目本身的键与未过期时间戳完全对得上。
    // 键格式在测试内按存储实现同法复刻：JSONArray([owner, 脚本 id, 代码 SHA-256,
    // 平台, 音质])；格式若再变，本测试应先红、逼迫同步迁移口径。
    private fun legacyKey(sc: Script, source: String, quality: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(sc.code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val ownerKey = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner) + ":" + owner.generation
        return org.json.JSONArray(listOf(ownerKey, sc.id, hash, source, quality.lowercase())).toString()
    }

    private fun seedLegacyStore(entries: Map<String, Long>) {
        // 必须写入存储实际使用的那个 SharedPreferences（经测试缝取）——
        // ContextPreferences 按资料身份定文件名，测试自开同名文件会落到
        // 另一个命名空间，种子形同虚设（首版测试即栽于此）。
        val prefs = ScriptVerdictStore.sharedPreferencesForTest()!!
        val root = org.json.JSONObject()
        entries.forEach { (k, v) -> root.put(k, v) }
        prefs.edit().putString("verdicts", root.toString()).commit()
    }

    private fun rawVerdictsValue(): String =
        ScriptVerdictStore.sharedPreferencesForTest()!!.getString("verdicts", "")!!

    @Test
    fun legacyGenerationOneVerdictsAreAmnestied() {
        val sc = script("verdict-legacy")
        val future = System.currentTimeMillis() + ScriptVerdictStore.TERMINAL_TTL_MS
        seedLegacyStore(mapOf(legacyKey(sc, "tx", "master") to future))
        // 读侧防御：不重新 init，旧世代条目也不得命中。
        assertFalse(ScriptVerdictStore.isVerdicted(owner, sc, "tx", "master"))
        // 写侧迁移：init 后落盘新格式空表，旧键彻底消失。
        ScriptVerdictStore.init(RuntimeEnvironment.getApplication())
        val raw = rawVerdictsValue()
        assertFalse(raw.contains("verdict-legacy"))
        assertTrue(raw.contains("\"gen\":2"))
    }

    @Test
    fun verdictsRecordedAfterAmnestyPersistAndMatch() {
        val sc = script("verdict-post-amnesty")
        val future = System.currentTimeMillis() + ScriptVerdictStore.TERMINAL_TTL_MS
        seedLegacyStore(mapOf(legacyKey(sc, "tx", "flac") to future))
        ScriptVerdictStore.init(RuntimeEnvironment.getApplication())
        // 大赦只清存量：迁移之后新落的裁决照常命中、且不被后续 init 误清。
        ScriptVerdictStore.recordTerminal(owner, sc, "tx", "flac")
        ScriptVerdictStore.init(RuntimeEnvironment.getApplication())
        assertTrue(ScriptVerdictStore.isVerdicted(owner, sc, "tx", "flac"))
    }
}
