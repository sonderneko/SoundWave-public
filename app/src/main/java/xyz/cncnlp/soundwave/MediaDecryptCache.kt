package xyz.cncnlp.soundwave

/**
 * 播放场景的加密媒体缓存。
 *
 * 脚本/账号取链返回加密 URL（.mflac/.qmcflac/.bkcflac/.mgg/.qmcogg/.ncm/.tkm）时，
 * 播放前由本对象把整个加密文件下载到缓存目录、解密成明文，再以明文本地路径交给播放器。
 * 与 DownloadHelper 的下载场景互补：DownloadHelper 解密后写 LocalStore（下载落盘），
 * 本对象只写 cacheDir，供播放临时使用；缓存清理走设置页 CacheManager.clearAll
 * （cacheDir 全清已覆盖本目录，本对象不再提供独立 clear）。
 *
 * 解密算法复用 decrypt 包：QmcDecrypt（QQ 音乐系）、NcmDecrypt（网易云系），
 * 均以流式（decryptStream）输出，避免整文件入堆。
 */

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import xyz.cncnlp.soundwave.decrypt.NcmDecrypt
import xyz.cncnlp.soundwave.decrypt.QmcDecrypt

object MediaDecryptCache {
    private const val DIR_NAME = "media_decrypt"
    // 解密为流式（峰值 64KB 缓冲），只需限制下载体大小防止 CDN 恶意无限流；200MB 为安全上限。
    private const val MAX_BYTES = 200L * 1024L * 1024L
    private const val BUFFER_BYTES = 64 * 1024

    // 共享连接池；此下载派生 client 使用专属 5 分钟 callTimeout，读取空闲超时仍为 15 秒。
    private val client = AppHttp.shared.newBuilder()
        .callTimeout(DOWNLOAD_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val preparationLocks = KeyedOperationLock<String>()

    /**
     * 纯字符串检测：URL 是否指向加密媒体。
     * @return 加密格式标识（"mflac"/"mgg"/"ncm"/"tkm"），非加密返回 null（调用方直接播 URL）。
     */
    fun encryptedExtOf(url: String): String? {
        val extension = try {
            Uri.parse(url).lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
        return when (extension) {
            "mflac", "qmcflac", "bkcflac" -> "mflac"
            "mgg", "qmcogg" -> "mgg"
            "ncm" -> "ncm"
            "tkm" -> "tkm"
            else -> null
        }
    }

    /**
     * 下载加密媒体到缓存目录并解密成明文。
     *
     * @param cacheKey 非播放兼容入口的调用方缓存键；播放必须使用 [prepareResolvedWithLease]。
     * @param ext [encryptedExtOf] 返回的加密格式标识。
     * @return 明文本地文件路径；已有同缓存键的明文则直接返回；任何失败返回 null，不抛异常（取消除外）。
     */
    suspend fun prepare(ctx: Context, url: String, cacheKey: String, ext: String): String? {
        val lease = prepareLeased(ctx, url, cacheKey, ext) ?: return null
        return try { lease.path } finally { lease.close() }
    }

    /** Playback callers keep this lease until the owning ExoPlayer detaches the media item. */
    suspend fun prepareLeased(ctx: Context, url: String, cacheKey: String, ext: String): MediaCacheLease? =
        prepareWithLease(ctx, url, cacheKey, ext, preparationLocks, ::download, ::decryptToFile)

    /**
     * Capture ownership from the resolver ticket, never the mutable active account.
     * URL/token is a download credential only. Missing provenance gets a one-use scope.
     * Probe size/MIME cannot prove equality: hash the actual ciphertext before any reuse.
     */
    /** Playback entry: lease for a resolver result with provenance-bound cache identity. */
    suspend fun prepareLeased(ctx: Context, url: String, result: ResolveResult, ext: String): MediaCacheLease? =
        prepareResolvedWithLease(ctx, url, result, ext)

    internal suspend fun prepareResolvedWithLease(
        ctx: Context, url: String, result: ResolveResult, ext: String,
        operationLock: KeyedOperationLock<String> = preparationLocks,
        downloader: suspend (String, File) -> Boolean = ::download,
        decryptor: (File, File, String) -> String? = ::decryptToFile
    ): MediaCacheLease? = prepareWithLease(
        ctx, url, resolvedScope(result, ext), ext,
        operationLock, downloader, decryptor, verifyContent = true
    )

    private fun resolvedScope(result: ResolveResult, ext: String): String {
        val owner = result.ticket.ownerContext
        val fields = listOf(
            "playback-decrypt-v2", xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner),
            owner.generation.toString(), result.ticket.songKey, result.platform,
            result.candidateKey, result.scriptId, result.quality, ext
        )
        return if (result.isSuccess && result.failure == ResolveFailure.NONE && fields.all { it.isNotBlank() }) {
            xyz.cncnlp.soundwave.download.TrustedMediaCacheIdentity.requestScope(fields)
        } else java.util.UUID.randomUUID().toString()
    }

    /** 内部依赖注入入口：生产和并发回归测试共用完全相同的缓存/锁定路径。 */
    internal suspend fun prepareWith(
        ctx: Context,
        url: String,
        cacheKey: String,
        ext: String,
        operationLock: KeyedOperationLock<String>,
        downloader: suspend (String, File) -> Boolean,
        decryptor: (File, File, String) -> String?
    ): String? {
        val lease = prepareWithLease(ctx, url, cacheKey, ext, operationLock, downloader, decryptor) ?: return null
        return try { lease.path } finally { lease.close() }
    }

    internal suspend fun prepareWithLease(
        ctx: Context,
        url: String,
        cacheKey: String,
        ext: String,
        operationLock: KeyedOperationLock<String>,
        downloader: suspend (String, File) -> Boolean,
        decryptor: (File, File, String) -> String?,
        verifyContent: Boolean = false,
        installDecryptedOutput: (File, File) -> Unit = { source, destination ->
            if (!source.renameTo(destination)) {
                throw java.io.IOException("无法原子提交解密缓存文件")
            }
        }
    ): MediaCacheLease? {
        // withContext has prompt cancellation at dispatcher handoff. A lease created on IO
        // must be closed if cancellation discards the return before its caller owns it.
        var handoffLease: MediaCacheLease? = null
        try {
            return withContext(Dispatchers.IO) {
                val cacheId = cacheFileId(cacheKey)
                val dir = File(ctx.cacheDir, DIR_NAME)
                val part = File(dir, "dec_${cacheId}.${ext}.part")
                val plainTmp = File(dir, "dec_${cacheId}.${ext}.plain.part")
                var partLease: MediaCacheLease? = null
                var plainLease: MediaCacheLease? = null
                var completedLease: MediaCacheLease? = null
                fun acquireCached(cacheId: String): MediaCacheLease? {
                    val cached = dir.listFiles()?.firstOrNull { file ->
                        file.isFile && file.length() > 0L &&
                            file.nameWithoutExtension == "dec_${cacheId}" && file.extension.isNotEmpty()
                    } ?: return null
                    return MediaCacheLeaseManager.acquireExisting(cached)
                }
                operationLock.withKey(cacheId) {
                    try {
                        val scheme = url.lowercase()
                        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
                            return@withKey null
                        }

                        // Legacy non-playback callers retain their explicit-key contract.
                        if (!verifyContent) acquireCached(cacheId)?.let {
                            handoffLease = it
                            return@withKey it
                        }

                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("媒体解密缓存已取消")
                        }

                        // 同一缓存键的下载、解密及暂存清理由单个锁保护；其他键仍可并行。
                        partLease = MediaCacheLeaseManager.reserveInDirectory(dir, part) ?: return@withKey null
                        plainLease = MediaCacheLeaseManager.reserveInDirectory(dir, plainTmp) ?: return@withKey null
                        if (!downloader(url, part)) return@withKey null
                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("媒体解密缓存已取消")
                        }
                        if (part.length() <= 0L || part.length() > MAX_BYTES) return@withKey null
                        val completedId = if (verifyContent) {
                            val digest = MessageDigest.getInstance("SHA-256")
                            part.inputStream().use { input ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    digest.update(buffer, 0, n)
                                }
                            }
                            val contentHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            cacheFileId("playback-content-v2|$cacheId|$contentHash")
                        } else cacheId
                        if (verifyContent) acquireCached(completedId)?.let {
                            handoffLease = it
                            return@withKey it
                        }

                        val decryptedExt: String? = try {
                            if (part.length() <= 0L || part.length() > MAX_BYTES) return@withKey null
                            // 流式解密：边读边写，峰值 64KB 缓冲。成功后原子改名，半成品不会污染缓存命中。
                            if (plainTmp.exists() && !plainTmp.delete()) return@withKey null
                            val realExt = decryptor(part, plainTmp, ext)
                            currentCoroutineContext().ensureActive()
                            if (realExt == null || plainTmp.length() <= 0L) {
                                try { plainTmp.delete() } catch (_: Exception) {}
                                return@withKey null
                            }
                            realExt
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            null
                        }
                        if (decryptedExt == null) return@withKey null

                        val target = File(dir, "dec_${completedId}.$decryptedExt")
                        if (target.exists() && !MediaCacheLeaseManager.deleteIfUnleased(target)) return@withKey null
                        completedLease = MediaCacheLeaseManager.reserveInDirectory(dir, target) ?: return@withKey null
                        // 同目录 rename 失败时必须失败关闭；绝不把半成品直接复制到正式缓存名。
                        installDecryptedOutput(plainTmp, target)
                        currentCoroutineContext().ensureActive()
                        if (target.isFile && target.length() > 0L) {
                            val result = completedLease ?: return@withKey null
                            handoffLease = result
                            completedLease = null
                            result
                        } else null
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        null
                    } finally {
                        // 锁仍持有时清理，避免删除另一个同键请求正在写入的暂存文件。
                        try {
                            part.delete()
                            plainTmp.delete()
                        } catch (_: Exception) {
                        }
                        partLease?.close()
                        plainLease?.close()
                        completedLease?.let {
                            try { it.file.delete() } catch (_: Exception) {}
                            it.close()
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            handoffLease?.close()
            throw cancelled
        }
    }

    /** 流式下载到 [out]（覆盖写）；取消抛 CancellationException，其余失败返回 false。 */
    private suspend fun download(url: String, out: File): Boolean {
        return try {
            if (out.exists()) out.delete()
            // 复用 DownloadHelper.baseRequest 的共享匿名请求头策略（UA/Referer），保持链路一致。
            val request = MediaRequestPolicy.apply(Request.Builder().url(url)).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                body.byteStream().use { input ->
                    FileOutputStream(out).use { fos ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var read = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("媒体解密缓存下载已取消")
                            }
                            val n = input.read(buffer)
                            if (n <= 0) break
                            fos.write(buffer, 0, n)
                            read += n
                            if (read > MAX_BYTES) return false
                        }
                        fos.flush()
                    }
                }
            }
            out.isFile && out.length() > 0L
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptToFile(part: File, plainTmp: File, ext: String): String? =
        FileInputStream(part).use { input ->
            FileOutputStream(plainTmp).use { output ->
                if (ext == "ncm") NcmDecrypt.decryptStream(input, output)
                else QmcDecrypt.decryptStream(input, output, ext)
            }
        }

    /** Full-key SHA-256 avoids lossy sanitization/truncation collisions in files and locks. */
    internal fun cacheFileId(cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
