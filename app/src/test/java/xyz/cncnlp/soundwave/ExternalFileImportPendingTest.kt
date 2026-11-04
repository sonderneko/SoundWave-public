package xyz.cncnlp.soundwave

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.cncnlp.soundwave.SchemeActionHolder.Action

/** r657：外部打开的备份 JSON 只交给备份页一次（取走即清空），显示名优先于 URI 末段判定类型。 */
@RunWith(RobolectricTestRunner::class)
class ExternalFileImportPendingTest {
    @Test
    fun pendingBackupIsHandedOverExactlyOnce() {
        val uri = Uri.parse("content://downloads/x/soundwave-backup.json")
        ExternalFileImport.offerBackup(uri)
        assertEquals(uri, ExternalFileImport.pendingBackup.value)
        assertEquals(uri, ExternalFileImport.takePendingBackup())
        assertNull(ExternalFileImport.takePendingBackup())
        assertNull(ExternalFileImport.pendingBackup.value)
    }

    @Test
    fun displayNameWinsOverOpaqueUriSegment() {
        val opaque = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        assertTrue(ExternalFileImport.actionFor(opaque, "application/octet-stream", "lx-source.js") is Action.ImportScript)
        assertTrue(ExternalFileImport.actionFor(opaque, "application/octet-stream", "backup.json") is Action.ImportBackup)
        assertEquals(Action.Notice(ExternalFileImport.UNSUPPORTED_MESSAGE), ExternalFileImport.actionFor(opaque, "application/octet-stream", null))
    }
}
