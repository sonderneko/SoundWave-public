package xyz.cncnlp.soundwave

import java.io.File

/**
 * Fast format-header validation for provider cover files. This deliberately does not decode
 * pixels; Coil remains responsible for decoding and rendering the image.
 */
internal fun isSupportedCoverImageSignature(bytes: ByteArray, length: Int = bytes.size): Boolean {
    val size = length.coerceIn(0, bytes.size)
    fun matchesAscii(offset: Int, value: String): Boolean =
        offset + value.length <= size && value.indices.all { index ->
            bytes[offset + index].toInt().and(0xff) == value[index].code
        }

    val jpeg = size >= 3 && bytes[0].toInt().and(0xff) == 0xff &&
        bytes[1].toInt().and(0xff) == 0xd8 && bytes[2].toInt().and(0xff) == 0xff
    val png = size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    )
    val gif = matchesAscii(0, "GIF87a") || matchesAscii(0, "GIF89a")
    val webp = matchesAscii(0, "RIFF") && matchesAscii(8, "WEBP")
    val avif = matchesAscii(4, "ftyp") &&
        listOf("avif", "avis", "heic", "heix", "hevc", "hevx", "mif1", "msf1").any { matchesAscii(8, it) }
    return jpeg || png || gif || webp || avif
}

/** Read only the small header needed to avoid permanently serving HTML/JSON as an image. */
internal fun isValidCoverImageFile(file: File): Boolean {
    if (!file.isFile || file.length() <= 64L) return false
    return try {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            isSupportedCoverImageSignature(header, count)
        }
    } catch (_: Exception) {
        false
    }
}
