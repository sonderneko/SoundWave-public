package xyz.cncnlp.soundwave

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CrashGuardTest {
    @Test
    fun clearingLocalCrashRecordAndPendingCopyRemainsExplicitAndIndependent() {
        val context: Context = RuntimeEnvironment.getApplication()
        val last = context.filesDir.resolve("last_startup_crash.txt")
        val pending = context.filesDir.resolve("pending_bug_report.txt")
        last.writeText("local diagnostic")
        pending.writeText("queued diagnostic")

        try {
            CrashGuard.clear(context)
            assertTrue("clearing the visible crash record removes only its local copy", !last.exists())
            assertEquals("queued diagnostic", CrashGuard.pending(context))

            CrashGuard.clearPending(context)
            assertTrue("clearing the pending copy removes only its local copy", !pending.exists())
            assertEquals("", CrashGuard.last(context))
            assertEquals("", CrashGuard.pending(context))
        } finally {
            last.delete()
            pending.delete()
        }
    }

    /** 承接原 CrashUploadToggleTest 的本机保留行为：升级前未脱敏的本机副本在读取时同样脱敏。 */
    @Test
    fun legacyUnredactedLocalCopiesAreRedactedOnRead() {
        val context: Context = RuntimeEnvironment.getApplication()
        val last = context.filesDir.resolve("last_startup_crash.txt")
        last.writeText("Cookie: MUSIC_U=secret\nuser me@example.com")
        try {
            val shown = CrashGuard.last(context)
            assertFalse(shown.contains("MUSIC_U=secret"))
            assertFalse(shown.contains("me@example.com"))
        } finally {
            last.delete()
        }
    }

    /**
     * 删除合同（用户 2026-11-04 裁定）：一键反馈与崩溃上传机制（自动补传 / 手动发送 / /music/bug 客户端路径）
     * 已整体删除，崩溃报告只留本机。任何上传面回潮都应在此失败。
     */
    @Test
    fun uploadSurfaceStaysDeleted() {
        val crashMethods = CrashGuard::class.java.methods.map { it.name }
        assertTrue(
            "CrashGuard must not regain upload entry points",
            crashMethods.none { it in setOf("submitNow", "flushPending", "isAutoUploadEnabled", "setAutoUploadEnabled") }
        )
        assertTrue(
            "ApiClient must not regain the /music/bug report path",
            ApiClient::class.java.methods.none { it.name == "reportBug" }
        )
        for (fqcn in listOf(
            "xyz.cncnlp.soundwave.CrashReportAutoUpload",
            "xyz.cncnlp.soundwave.CrashUploadPrefs",
            "xyz.cncnlp.soundwave.InstallId"
        )) {
            try {
                Class.forName(fqcn)
                fail("deleted upload-era type resurrected: $fqcn")
            } catch (_: ClassNotFoundException) {
                // expected: stays deleted
            }
        }
    }
}
