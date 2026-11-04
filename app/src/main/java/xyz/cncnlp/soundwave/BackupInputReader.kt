package xyz.cncnlp.soundwave

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounds untrusted legacy backup input before decoding or allocating a JSON tree. */
internal object BackupInputReader {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_DEPTH = 64

    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val remaining = maxBytes - output.size()
            if (remaining == 0) {
                require(input.read() == -1) { "备份文件超过允许大小" }
                break
            }
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            if (n == 0) {
                val one = input.read()
                if (one < 0) break
                output.write(one)
            } else output.write(buffer, 0, n)
        }
        val text = output.toString(Charsets.UTF_8.name())
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> quoted = false
                }
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= MAX_DEPTH) { "备份嵌套层级超过限制" } }
                '}', ']' -> { depth--; require(depth >= 0) { "备份结构无效" } }
            }
        }
        require(!quoted && depth == 0) { "备份结构不完整" }
        return text
    }
}
