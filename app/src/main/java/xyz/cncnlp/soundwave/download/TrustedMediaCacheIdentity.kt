package xyz.cncnlp.soundwave.download

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Stable identity for a verified media object. The signed URL is retained as
 * provenance, but is deliberately not the cache key: CDN token rotation must
 * not duplicate a verified object. Reuse still requires identical content
 * evidence and all logical ownership/source fields.
 */
data class TrustedMediaCacheIdentity(
    val owner: String,
    val candidate: String,
    val platform: String,
    val actualQuality: String,
    val script: String,
    val url: String,
    val contentEvidence: String
) {
    fun isComplete(): Boolean = owner.isNotBlank() && candidate.isNotBlank() &&
        platform.isNotBlank() && actualQuality.isNotBlank() && script.isNotBlank() &&
        url.isNotBlank() && contentEvidence.isNotBlank()

    companion object {
        private const val VERSION = "trusted-media-v1"

        /** Content facts that are stable enough to distinguish changed media. */
        fun contentEvidence(
            httpStatus: Int,
            contentType: String,
            totalBytes: Long,
            format: String,
            validator: String
        ): String = listOf(
            httpStatus.toString(), contentType.trim().lowercase(), totalBytes.toString(),
            format.trim().lowercase(), validator.trim()
        ).joinToString("|")

        /** Request scope is distinct from Song.key and includes all stable catalog facts. */
        fun requestScope(parts: List<String>): String = digest(parts)

        private fun digest(parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        /** Key excludes the signed URL so a token refresh can reuse verified content. */
        fun cacheKey(identity: TrustedMediaCacheIdentity): String {
            require(identity.isComplete()) { "verified media identity is incomplete" }
            return "$VERSION:" + digest(listOf(
                identity.owner, identity.candidate, identity.platform,
                identity.actualQuality, identity.script, identity.contentEvidence
            ))
        }

        fun canReuse(stored: TrustedMediaCacheIdentity, current: TrustedMediaCacheIdentity): Boolean =
            stored.isComplete() && current.isComplete() &&
                stored.owner == current.owner &&
                stored.candidate == current.candidate &&
                stored.platform == current.platform &&
                stored.actualQuality == current.actualQuality &&
                stored.script == current.script &&
                stored.contentEvidence == current.contentEvidence
    }
}
