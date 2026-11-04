package xyz.cncnlp.soundwave.download

/**
 * r657：下载断点续传的安全判定。
 *
 * 旧实现只要 `.part` 存在且比远端短就带 Range 续传，校验的是“本次新取到的链接”的 ETag，
 * 并不知道 `.part` 前半段来自哪个链接。下载失败重试会重新取链，智能换源可能换到另一个平台/音质的文件，
 * 结果把两份不同的音频拼在一起（长度刚好对上时还能通过容器头校验）。
 * `.part` 旁边记录首次写入时的远端指纹（总长 + ETag/Last-Modified + 来源/音质/URL 身份摘要）。
 * 旧版无身份的 metadata 一律不续传；URL 变化也保守重下，避免跨 CDN 的 validator 碰撞。
 */
internal object DownloadResumePolicy {
    data class Fingerprint(val total: Long, val validator: String, val identity: String = "")

    /** 慢速网络下也给足时间：按 32 KiB/s 估算，最少 5 分钟、最多 30 分钟。 */
    private const val MIN_BYTES_PER_SECOND = 32L * 1024L
    const val MIN_DEADLINE_MS = 5L * 60L * 1000L
    const val MAX_DEADLINE_MS = 30L * 60L * 1000L

    fun encode(fp: Fingerprint): String = "v2\n${fp.total}\n${fp.validator.replace('\n', ' ').replace('\r', ' ')}\n${fp.identity}"

    fun decode(raw: String?): Fingerprint? {
        if (raw.isNullOrBlank()) return null
        val fields = raw.split('\n')
        // v1 contains no source/quality identity and must never authorize append.
        if (fields.size != 4 || fields[0] != "v2") return null
        val total = fields[1].toLongOrNull() ?: return null
        if (!fields[3].matches(Regex("[0-9a-f]{64}"))) return null
        return Fingerprint(total, fields[2], fields[3])
    }

    /** 返回可以安全续传的起点；0 表示必须从头下载（并覆盖旧 `.part`）。 */
    fun resumeOffset(existingBytes: Long, stored: Fingerprint?, remote: Fingerprint): Long {
        if (existingBytes <= 0L || stored == null) return 0L
        // 没有实体验证器就无法证明是同一个文件。
        if (stored.identity.isBlank() || stored.identity != remote.identity) return 0L
        if (stored.validator.isBlank() || remote.validator.isBlank()) return 0L
        if (stored.validator != remote.validator) return 0L
        // r657 r3b（F8）：弱 ETag（W/…）只表示语义等价、不保证逐字节相同，HTTP 也不允许用于 If-Range，不能授权拼接。
        if (isWeakEtag(stored.validator) || isWeakEtag(remote.validator)) return 0L
        if (stored.total > 0L && remote.total > 0L && stored.total != remote.total) return 0L
        if (remote.total > 0L && existingBytes >= remote.total) return 0L
        return existingBytes
    }

    fun isWeakEtag(validator: String): Boolean = validator.trimStart().startsWith("W/", ignoreCase = true)

    /** Length-prefix each field; persist only a digest, never a signed URL or account token. */
    fun identity(owner: String, songKey: String, candidateKey: String, platform: String,
                 quality: String, scriptId: String, url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        listOf(owner, songKey, candidateKey, platform, quality, scriptId, url).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** 根据文件大小给整次下载的时限；未知大小按下限。 */
    fun deadlineMs(totalBytes: Long): Long {
        if (totalBytes <= 0L) return MIN_DEADLINE_MS
        val estimated = totalBytes / MIN_BYTES_PER_SECOND * 1000L
        return estimated.coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)
    }
}
