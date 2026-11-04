package xyz.cncnlp.soundwave

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

internal object BoundedCoverReader {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
            if (n < 0) return output.toByteArray()
            if (n == 0) continue
            if (n > maxBytes - output.size()) throw IOException("Cover size limit exceeded")
            output.write(buffer, 0, n)
        }
    }
}
