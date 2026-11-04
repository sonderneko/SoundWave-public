package xyz.cncnlp.soundwave

import android.net.Uri
import java.io.IOException
import java.io.OutputStream

/** SAF writer with cancellation as a no-op and success reported only after OutputStream.close(). */
internal object InactiveProfileBackupDocumentWriter {
    fun writeSelectedDestination(
        destination: Uri?,
        payload: ByteArray,
        openOutput: (Uri) -> OutputStream?
    ): Boolean {
        if (destination == null) return false
        val output = openOutput(destination) ?: throw IOException("无法打开所选保存位置")
        output.use { stream ->
            stream.write(payload)
            stream.flush()
        }
        return true
    }
}
