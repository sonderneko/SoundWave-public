package xyz.cncnlp.soundwave

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r657 P0-5：正式包日志收敛——PlaybackController 带歌曲 key / 异常消息的日志仅 debug 输出；R8 剥离 Log.v/d/i、保留 w/e。 */
class ReleaseLogHygieneTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
        assertNotNull("missing $path", file)
        return file!!.readText(Charsets.UTF_8)
    }

    @Test
    fun songKeyLogsInPlaybackControllerAreDebugOnly() {
        val controller = source("src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt")
        val logLines = controller.lines().filter { Regex("\\bLog\\.[vdiwe]\\(").containsMatchIn(it) }
        assertTrue(logLines.isNotEmpty())
        logLines.filter { "key()" in it || "e.message" in it }.forEach { line ->
            val guarded = line.substringBefore(" else ")
            val release = line.substringAfter(" else ", "")
            assertTrue("unguarded: $line", guarded.trimStart().startsWith("if (BuildConfig.DEBUG)"))
            assertFalse("release branch leaks key: $line", "key()" in release)
            assertFalse("release branch leaks message: $line", "e.message" in release)
        }
    }

    @Test
    fun r8StripsVerboseDebugInfoButKeepsWarnAndError() {
        val rules = source("proguard-rules.pro")
        val body = Regex("-assumenosideeffects\\s+class\\s+android\\.util\\.Log\\s*\\{([^}]*)\\}").find(rules)?.groupValues?.get(1)
        assertNotNull("proguard-rules.pro must strip android.util.Log", body)
        listOf("v", "d", "i").forEach { assertTrue("Log.$it must be stripped", Regex("\\b$it\\(\\.\\.\\.\\)").containsMatchIn(body!!)) }
        listOf("w", "e").forEach { assertFalse("Log.$it must be kept", Regex("\\b$it\\(\\.\\.\\.\\)").containsMatchIn(body!!)) }
    }
}
