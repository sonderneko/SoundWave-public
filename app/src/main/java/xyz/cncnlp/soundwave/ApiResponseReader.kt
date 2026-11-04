package xyz.cncnlp.soundwave

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Owns bounded response consumption and stream closure only. No URL policy, authentication,
 * retry, or account state belongs here; callers select the applicable byte budget.
 */
internal object ApiResponseReader {
    const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024

    fun readBytes(stream: InputStream, limit: Int = DEFAULT_MAX_BYTES): ByteArray = stream.use { input ->
        val out = ByteArrayOutputStream(minOf(16 * 1024, limit))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("响应过大（超过 ${limit / 1024}KB）")
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

    fun readText(stream: InputStream, limit: Int = DEFAULT_MAX_BYTES): String =
        readBytes(stream, limit).toString(Charsets.UTF_8)
}
