package xyz.cncnlp.soundwave.script

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_SCRIPT_IMPORT_BYTES = 8 * 1024 * 1024

internal class ScriptImportTooLargeException : IOException("脚本文件超过 8MB")
internal class ScriptImportReadException(cause: Throwable) : Exception("读取脚本失败", cause)

/** Reads at most [MAX_SCRIPT_IMPORT_BYTES] plus one sentinel byte to detect overflow. */
internal object BoundedScriptImportReader {
    private const val BUFFER_SIZE = 8 * 1024

    fun read(input: InputStream, maxBytes: Int = MAX_SCRIPT_IMPORT_BYTES): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)

        while (true) {
            val remaining = maxBytes - output.size()
            if (remaining == 0) {
                if (input.read() == -1) return output.toByteArray()
                throw ScriptImportTooLargeException()
            }

            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val next = input.read()
                if (next < 0) return output.toByteArray()
                output.write(next)
            } else {
                output.write(buffer, 0, count)
            }
        }
    }
}
