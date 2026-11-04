package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.script.BoundedScriptImportReader
import xyz.cncnlp.soundwave.script.MAX_SCRIPT_IMPORT_BYTES
import xyz.cncnlp.soundwave.script.ScriptImportTooLargeException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BoundedScriptImportReaderTest {
    @Test
    fun readsFileExactlyAtEightMegabyteLimit() {
        val expected = ByteArray(MAX_SCRIPT_IMPORT_BYTES) { (it % 251).toByte() }

        assertArrayEquals(expected, BoundedScriptImportReader.read(ByteArrayInputStream(expected)))
    }

    @Test
    fun rejectsOversizedFileAfterReadingOnlyOneBytePastLimit() {
        val limit = 32 * 1024
        val input = CountingInputStream(totalBytes = limit * 4L)

        try {
            BoundedScriptImportReader.read(input, maxBytes = limit)
            fail("Expected an oversized file to be rejected")
        } catch (_: ScriptImportTooLargeException) {
            assertEquals(limit + 1L, input.bytesRead)
        }
    }

    @Test
    fun propagatesStreamReadFailure() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic read failure")
        }

        try {
            BoundedScriptImportReader.read(input, maxBytes = 16)
            fail("Expected the stream error to propagate")
        } catch (error: IOException) {
            assertEquals("synthetic read failure", error.message)
        }
    }

    private class CountingInputStream(private val totalBytes: Long) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            if (bytesRead >= totalBytes) return -1
            bytesRead++
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= totalBytes) return -1
            val count = minOf(length.toLong(), totalBytes - bytesRead).toInt()
            buffer.fill(0, offset, offset + count)
            bytesRead += count
            return count
        }
    }
}
