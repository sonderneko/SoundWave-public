package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BackupInputReaderTest {
    private fun read(text: String, limit: Int = 4096) =
        BackupInputReader.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), limit)

    @Test fun exactByteLimitAccepted() { assertEquals("{}", read("{}", 2)) }
    @Test fun oneByteOverRejected() {
        assertThrows(IllegalArgumentException::class.java) { read("{} ", 2) }
    }
    @Test fun nestedBracketsInsideStringsDoNotCount() {
        val text = "{\"x\":\"" + "[".repeat(100) + "\"}"
        assertEquals(text, read(text))
    }
    @Test fun excessiveNestingRejected() {
        assertThrows(IllegalArgumentException::class.java) { read("[".repeat(65) + "]".repeat(65)) }
    }
    @Test fun incompleteInputRejected() {
        assertThrows(IllegalArgumentException::class.java) { read("{\"x\":\"unfinished") }
    }
    @Test fun zeroLengthBulkReadDoesNotSpin() {
        val source = object : InputStream() {
            private val bytes = "{}".toByteArray()
            private var index = 0
            override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertEquals("{}", BackupInputReader.read(source, 2))
    }
}
