package xyz.cncnlp.soundwave.script

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

internal const val MAX_ZIP_ENTRY_BYTES = 2 * 1024 * 1024
internal const val MAX_ZIP_EXPANDED_BYTES = 8 * 1024 * 1024
internal const val MAX_ZIP_ENTRIES = 128

/** Drains every entry through the same limits, including non-script files and directories. */
internal object BoundedZipReader {
    data class Entry(val name: String, val bytes: ByteArray)

    private fun expectedEntries(bytes: ByteArray): Int {
        fun u16(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        fun u32(at: Int): Long = (0 until 4).fold(0L) { value, shift ->
            value or ((bytes[at + shift].toLong() and 255) shl (8 * shift))
        }
        // ZipInputStream accepts a missing central directory; require a complete single-disk archive.
        for (at in bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)) {
            if (u32(at) != 0x06054b50L || at + 22 + u16(at + 20) != bytes.size) continue
            if (u16(at + 4) != 0 || u16(at + 6) != 0 || u16(at + 8) != u16(at + 10) ||
                u32(at + 12) + u32(at + 16) != at.toLong()) continue
            return u16(at + 10)
        }
        throw java.util.zip.ZipException("ZIP 不完整或格式不支持")
    }

    fun read(bytes: ByteArray): List<Entry> {
        val expectedEntries = expectedEntries(bytes)
        val result = ArrayList<Entry>()
        var entries = 0
        var total = 0
        val buffer = ByteArray(8 * 1024)
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ZIP_ENTRIES) { "ZIP 条目过多" }
                val keep = !entry.isDirectory && entry.name.endsWith(".js", ignoreCase = true)
                val output = if (keep) ByteArrayOutputStream() else null
                var size = 0
                while (true) {
                    val remaining = minOf(MAX_ZIP_ENTRY_BYTES - size, MAX_ZIP_EXPANDED_BYTES - total)
                    // Read at most the remaining budget plus one sentinel; never trust entry.size.
                    val count = zip.read(buffer, 0, minOf(buffer.size, remaining + 1))
                    if (count < 0) break
                    require(count <= remaining) { "ZIP 展开内容超过限额" }
                    size += count
                    total += count
                    output?.write(buffer, 0, count)
                }
                if (output != null) result += Entry(entry.name, output.toByteArray())
            }
        }
        if (entries != expectedEntries) throw java.util.zip.ZipException("ZIP 条目不完整")
        return result
    }
}
