package xyz.cncnlp.soundwave.sync

import android.graphics.BitmapFactory
import java.security.MessageDigest
import java.util.Base64

/** Account-bound avatar input; the caller owns authentication and the current-session guard. */
class WebDavAvatarSync internal constructor(
    val accountKey: String,
    val avatarB64: String,
    val isCurrent: () -> Boolean,
    val apply: (String) -> Unit
) {
    companion object {
        private const val MAX_DECODED_BYTES = 1_500_000
        private const val MAX_ENCODED_CHARS = 2_000_024
        private val DATA_URI = Regex("^data:image/(png|jpeg|jpg|webp|gif);base64,([A-Za-z0-9+/]*={0,2})$", RegexOption.IGNORE_CASE)

        /** Domain-separated, exact-case username identity; never embeds a username or credential. */
        internal fun stableAccountKey(username: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(("SoundWave/WebDAV/avatar/v1\u0000" + username.trim()).toByteArray(Charsets.UTF_8))
            return "swav1_" + digest.joinToString("") { "%02x".format(it) }
        }

        /** Return the original URI only when it is a bounded, actually decodable image. */
        internal fun validatedImageDataUri(value: String): String? {
            if (value.length > MAX_ENCODED_CHARS || value.isEmpty()) return null
            val match = DATA_URI.matchEntire(value) ?: return null
            val encoded = match.groupValues[2]
            if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
            if (bytes.isEmpty() || bytes.size > MAX_DECODED_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) } catch (_: Exception) { return null }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > 1024) return null
            val bitmap = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null } ?: return null
            bitmap.recycle()
            return value
        }
    }
}
