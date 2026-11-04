package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1-a 分类边界：只有脚本自报的终局错误（停服/版本类）进持久裁决；
 * 网络、无此歌等瞬时失败一律不入——误判会把活源拉黑。
 */
class ScriptTerminalErrorClassifierTest {

    @Test
    fun discontinuedServiceMessagesAreTerminal() {
        val terminal = listOf(
            "当前版本已停止服务，请升级到最新版本后重试",
            "该音源已停服",
            "服务已终止",
            "脚本版本过低，请升级",
            "API 版本太低，不再支持",
            "source discontinued",
            "This source is no longer supported, please upgrade",
            "deprecated API, upgrade required"
        )
        terminal.forEach { message ->
            assertTrue("应判终局: $message", ScriptTerminalErrorClassifier.isTerminal(message))
        }
    }

    @Test
    fun transientFailuresAreNeverTerminal() {
        val transient = listOf(
            "",
            "Network error",
            "request timeout",
            "未找到歌曲",
            "no url",
            "暂无版权",
            "需要登录",
            "服务器繁忙，请稍后重试",
            "404 Not Found"
        )
        transient.forEach { message ->
            assertFalse("不应判终局: $message", ScriptTerminalErrorClassifier.isTerminal(message))
        }
    }

    // ACCEPT4（修3）：硬/软分级——只有停服/版本不支持这类可证伪终局配硬级
    // （7 天）；下线/deprecated 等措辞含糊类只配软级（30 分钟短裁决）。
    @Test
    fun hardTerminalRequiresProvableFinality() {
        val hard = listOf(
            "该音源已停服",
            "服务已终止",
            "脚本版本过低，请升级",
            "source discontinued",
            "no longer supported"
        )
        hard.forEach { message ->
            assertTrue("应判硬终局: $message", ScriptTerminalErrorClassifier.isHardTerminal(message))
            assertTrue(ScriptTerminalErrorClassifier.isTerminal(message))
        }
    }

    @Test
    fun ambiguousSunsetMessagesAreSoftOnly() {
        val soft = listOf(
            "该音源已下线",
            "歌曲已下线",
            "deprecated API",
            "this endpoint is outdated"
        )
        soft.forEach { message ->
            assertTrue("应判终局: $message", ScriptTerminalErrorClassifier.isTerminal(message))
            assertFalse("不应判硬终局: $message", ScriptTerminalErrorClassifier.isHardTerminal(message))
            assertTrue("应判软终局: $message", ScriptTerminalErrorClassifier.isSoftTerminal(message))
        }
        // 同时命中硬词的文本不降级为软级（"服务已终止"含"已终止"仍是硬终局）。
        assertFalse(ScriptTerminalErrorClassifier.isSoftTerminal("服务已终止"))
    }

    @Test
    fun sanitizeStripsControlCharsAndCapsLength() {
        val dirty = "  error\u0000\u0007\n\t版本已停止服务  " + "x".repeat(300)
        val clean = ScriptTerminalErrorClassifier.sanitize(dirty)
        assertTrue(clean.length <= 160)
        assertFalse(clean.any { it.isISOControl() })
        assertTrue(clean.contains("版本已停止服务"))
        assertEquals("", ScriptTerminalErrorClassifier.sanitize(null))
        assertEquals("", ScriptTerminalErrorClassifier.sanitize("   "))
    }
}
