package xyz.cncnlp.soundwave

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.script.BoundedZipReader

class BoundedZipReaderTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test
    fun limitsCountForAllEntriesIncludingDirectoriesAndIgnoredFiles() {
        val accepted = archive(*(0 until 128).map { "dir$it/" to byteArrayOf() }.toTypedArray())
        assertTrue(BoundedZipReader.read(accepted).isEmpty())
        val rejected = archive(*(0 until 129).map { "ignored$it.txt" to byteArrayOf() }.toTypedArray())
        assertThrows(IllegalArgumentException::class.java) { BoundedZipReader.read(rejected) }
    }

    @Test
    fun limitsTotalExpandedBytesIncludingIgnoredEntries() {
        val block = ByteArray(2 * 1024 * 1024) { 65 }
        val entries = (0 until 4).map { "$it.js" to block }.toTypedArray()
        assertEquals(4, BoundedZipReader.read(archive(*entries)).size)
        assertThrows(IllegalArgumentException::class.java) {
            BoundedZipReader.read(archive(*entries, "ignored.txt" to byteArrayOf(65)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedZipReader.read(archive("ignored.txt" to ByteArray(block.size + 1)))
        }
    }

    @Test
    fun rejectsTruncatedPayloadAndMissingEndOfArchive() {
        val bytes = archive("first.js" to "lx.on('request', () => {});".toByteArray())
        listOf(40, bytes.size - 1, bytes.size - 22).forEach { length ->
            assertThrows("truncated at $length", java.io.IOException::class.java) {
                BoundedZipReader.read(bytes.copyOf(length))
            }
        }
    }

    @Test
    fun acceptsEntryExactlyAtTwoMiBAndRejectsOneByteMoreWithUnknownSize() {
        val limit = 2 * 1024 * 1024
        val valid = archive("exact.js" to ByteArray(limit) { 65 })
        ZipInputStream(valid.inputStream()).use { assertEquals(-1L, it.nextEntry.size) }
        assertEquals(limit, BoundedZipReader.read(valid).single().bytes.size)
        val oversized = archive("large.js" to ByteArray(limit + 1) { 65 })
        assertTrue(oversized.size < 8 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { BoundedZipReader.read(oversized) }
    }
}
