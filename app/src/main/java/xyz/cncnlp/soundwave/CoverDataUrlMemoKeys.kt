package xyz.cncnlp.soundwave

import java.security.MessageDigest
import java.util.WeakHashMap

/**
 * Compact, collision-resistant memo keys for inline cover URLs without retaining their payloads.
 * Weak keys let the original URL be collected once its owning model is no longer live.
 */
internal class CoverDataUrlMemoKeys {
    private val fingerprints = WeakHashMap<String, String>()

    @Synchronized
    fun key(url: String): String {
        return fingerprints[url] ?: fingerprint(url).also { fingerprints[url] = it }
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Hash UTF-16 code units in bounded chunks; never allocate another full-size copy of a
        // potentially 20+ MiB inline URL just to derive its memo key.
        val chunk = ByteArray(8192)
        var offset = 0
        value.forEach { character ->
            chunk[offset++] = (character.code ushr 8).toByte()
            chunk[offset++] = character.code.toByte()
            if (offset == chunk.size) {
                digest.update(chunk)
                offset = 0
            }
        }
        if (offset > 0) digest.update(chunk, 0, offset)
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            hex[index * 2] = digits[unsigned ushr 4]
            hex[index * 2 + 1] = digits[unsigned and 0x0f]
        }
        return "data#${value.length}#${String(hex)}"
    }
}
