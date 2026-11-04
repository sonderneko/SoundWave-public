package xyz.cncnlp.soundwave

/**
 * 下载链路支持加密媒体：.mflac/.qmcflac/.bkcflac/.mgg/.qmcogg/.tkm/.ncm 等加密文件
 * 下载完成后先解密成明文音频再落盘，本地文件即为可播放的明文容器。
 * 解密算法复用同项目 decrypt 包：QmcDecrypt（QQ 音乐系）、NcmDecrypt（网易云系）。
 */

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.download.ContentRangePolicy
import xyz.cncnlp.soundwave.download.DownloadDecryptLimitPolicy
import xyz.cncnlp.soundwave.download.DownloadResumePolicy
import xyz.cncnlp.soundwave.download.DownloadRequestRegistry
import xyz.cncnlp.soundwave.download.DownloadSafCommitProtocol
import xyz.cncnlp.soundwave.download.EncryptedMediaTooLargeException
import xyz.cncnlp.soundwave.decrypt.NcmDecrypt
import xyz.cncnlp.soundwave.decrypt.QmcDecrypt

internal const val DOWNLOAD_CALL_TIMEOUT_MINUTES = 5L

/** r657：媒体 CDN 返回的 HTTP 状态；4xx 多为直链过期，Worker 据此清掉 URL 缓存后重新取链。 */
internal class DownloadHttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

/** Only I/O failures originating in an HTTP request/response stream are safe for automatic retry. */
internal class DownloadTransportException(cause: java.io.IOException) : java.io.IOException("下载网络传输失败", cause)

internal object DownloadRetryPolicy {
    const val MAX_RETRIES = 3

    fun shouldRetry(failure: Throwable, runAttemptCount: Int, cancelled: Boolean): Boolean {
        if (cancelled || failure is java.util.concurrent.CancellationException || runAttemptCount >= MAX_RETRIES) return false
        return when (failure) {
            is DownloadTransportException -> true
            is DownloadHttpStatusException -> failure.code in setOf(403, 404, 410, 408, 429) || failure.code in 500..599
            else -> false
        }
    }

    fun isExpiredLinkStatus(code: Int): Boolean = code in setOf(403, 404, 410)
}

object DownloadHelper {
    private const val MAX_MEDIA_BYTES = 1L * 1024L * 1024L * 1024L
    // decryptStream 使用 64 KiB 流式缓冲；200 MiB 是受支持加密媒体的业务解密上限。
    private const val DOWNLOAD_TIMEOUT_SECONDS = 45L
    private const val RESUME_META_SUFFIX = ".resume"
    // ACCEPT6-MGCLOSE：注册表的取消回调由任务取消的发起线程执行（可为主线程侧的
    // 调度/界面动作），关连接一律投递 ResolveBlockingStage 的 IO scope，不内联关。
    private val activeCalls = DownloadRequestRegistry<Call> { ResolveBlockingStage.cancelCallAsync(it) }

    private data class RemoteObject(
        val total: Long,
        val etag: String,
        val lastModified: String,
        val rangeSupported: Boolean,
        val statusCode: Int = 0,
        val contentType: String = ""
    ) {
        val validator: String get() = etag.ifBlank { lastModified }
    }

    // 共享连接池；下载派生 client 单独设置 5 分钟 callTimeout，避免连续慢流被全局 60 秒总时限截断。
    private val client = AppHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(DOWNLOAD_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build()

    fun cancel(taskId: String) {
        activeCalls.cancel(taskId)
    }

    fun cancelAll() {
        activeCalls.cancelAll()
    }

    private fun cancelSession(session: DownloadRequestRegistry.Session) {
        activeCalls.cancelSession(session)
    }

    /** 删除下载实体：已完成文件、SAF 文件以及尚未完成任务留下的 staging .part 都要清。 */
    fun deleteArtifact(ctx: Context, item: DownloadItem) {
        try {
            if (item.path.isNotBlank() && !item.path.startsWith("pending:")) deleteLocalMediaResource(ctx, item.path)
        } catch (_: Exception) {}
        // pending task 没有最终路径：按其稳定 taskId 删除唯一 staging 片段，不能遗留半文件。
        val id = item.taskId.ifBlank { DownloadScheduler.taskId(item.song, item.quality) }
        val suffix = id.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(48)
        try { dir(ctx).listFiles()?.filter { it.name.endsWith("-$suffix.mp3.part") || it.name.endsWith("-$suffix.flac.part") || it.name.endsWith("-$suffix.m4a.part") || it.name.endsWith("-$suffix.ogg.part") || it.name.endsWith("-$suffix.ape.part") || it.name.endsWith("-$suffix.mflac.part") || it.name.endsWith("-$suffix.mgg.part") || it.name.endsWith("-$suffix.ncm.part") || it.name.endsWith("-$suffix.tkm.part") }?.forEach { it.delete() } } catch (_: Exception) {}
        // r657：续传指纹旁路文件与 .part 同生共死。
        try { dir(ctx).listFiles()?.filter { it.name.endsWith(".part.resume") && it.name.contains("-$suffix.") }?.forEach { it.delete() } } catch (_: Exception) {}
    }

    private fun track(session: DownloadRequestRegistry.Session, call: Call, shouldCancel: () -> Boolean = { false }): Call {
        if (!activeCalls.track(session, call, shouldCancel)) {
            throw java.util.concurrent.CancellationException("下载已取消")
        }
        return call
    }

    private fun untrack(session: DownloadRequestRegistry.Session, call: Call) {
        activeCalls.untrack(session, call)
    }

    private fun executeNetworkCall(call: Call): okhttp3.Response = try {
        call.execute()
    } catch (failure: java.io.IOException) {
        if (call.isCanceled()) throw java.util.concurrent.CancellationException("下载请求已取消")
        throw DownloadTransportException(failure)
    }

    private fun readNetwork(call: Call, input: java.io.InputStream, buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (failure: java.io.IOException) {
        if (call.isCanceled()) throw java.util.concurrent.CancellationException("下载请求已取消")
        throw DownloadTransportException(failure)
    }

    // Internal boundary also exercised by the two-worker regression (no network/SAF required).
    internal fun <T : Any> withCurrentGeneration(ctx: Context, generation: Long?, action: () -> T): T {
        if (generation == null) return action()
        return DownloadScheduler.commitIfCurrent(ctx, generation, action)
            ?: throw java.util.concurrent.CancellationException("下载任务已被清理")
    }

    internal fun <T : Any> withCurrentTask(
        ctx: Context,
        generation: Long?,
        taskId: String,
        expectedTaskEpoch: Long?,
        action: () -> T
    ): T = withCurrentGeneration(ctx, generation) {
        if (expectedTaskEpoch != null && !DownloadScheduler.isTaskCurrent(ctx, taskId, expectedTaskEpoch)) {
            throw java.util.concurrent.CancellationException("下载任务已暂停或被替换")
        }
        action()
    }

    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(ctx.filesDir, "music")
        val d = File(base, "SoundWave")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun safeName(song: Song, ctx: Context? = null, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): String {
        val tpl = try {
            ctx?.let { ContextPreferences.open(it, "soundwave", 0, owner).getString("download_name_tpl", "title_singer") }
        } catch (_: Exception) { null } ?: "title_singer"
        val singer = song.singer.ifBlank { "未知歌手" }
        val title = song.name.ifBlank { "未知歌曲" }
        val album = song.album.ifBlank { "单曲" }
        val raw = when (tpl) {
            "title_only" -> title
            "title_singer" -> "$title-$singer"
            "album_title" -> "$album - $title"
            else -> "$title-$singer"
        }.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return raw.take(80).ifEmpty { song.id.ifEmpty { "track" } }
    }

    /**
     * 下载可播 URL。onProgress 0..1。
     * 写入 app 专属目录，不需要额外存储权限。
     */
    fun download(
        ctx: Context,
        song: Song,
        url: String,
        resolve: ResolveResult? = null,
        taskId: String = "",
        previousOutputPath: String = "",
        expectedGeneration: Long? = null,
        expectedTaskEpoch: Long? = null,
        ownerContext: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime(),
        shouldCancel: () -> Boolean = { false },
        cancellationEpoch: Long? = null,
        /** r657：WorkManager 重试时改用可续传的单流，避免多线程稀疏 .part 每次都从零开始。 */
        preferSingleStream: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): DownloadItem {
        require(url.startsWith("http://") || url.startsWith("https://")) { "下载地址必须是 HTTP(S)" }
        val session = activeCalls.begin(taskId)
        // A task cancellation stays sticky across HEAD→GET/segment gaps, but not future retries.
        val cancellationGate = {
            session.isCancelled || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(ownerContext) ||
                shouldCancel() || (expectedGeneration != null && !DownloadScheduler.isCurrentGeneration(ctx, expectedGeneration))
        }
        try {
        val startedNanos = System.nanoTime()
        // 探测到文件大小后按大小放宽（慢网 flac 5 分钟下不完会每次重试都超时）。
        var deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(DownloadResumePolicy.MIN_DEADLINE_MS)
        fun ensureActive() {
            if (cancellationGate()) {
                cancelSession(session)
                throw java.util.concurrent.CancellationException("下载已取消")
            }
            if (System.nanoTime() >= deadlineNanos) {
                cancelSession(session)
                throw DownloadTransportException(java.io.IOException("下载任务超过 5 分钟，已停止"))
            }
        }
        // 下载必须是独立后台任务：取链/探测/分段 IO 全部不能占主线程。
        // 这里显式断言当前不在 Main Looper，避免后续改动把 call.execute() 带回 UI。
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "下载不得在主线程执行" }
        ensureActive()
        var ext = when {
            // 加密格式先归一到解密器认识的扩展名，下载完成后解密成明文再更新为真实格式。
            url.contains(".mflac", true) || url.contains(".qmcflac", true) || url.contains(".bkcflac", true) -> "mflac"
            url.contains(".mgg", true) || url.contains(".qmcogg", true) -> "mgg"
            url.contains(".ncm", true) -> "ncm"
            url.contains(".tkm", true) -> "tkm"
            url.contains(".flac", true) -> "flac"
            url.contains(".m4a", true) -> "m4a"
            url.contains(".ape", true) -> "ape"
            url.contains(".ogg", true) -> "ogg"
            else -> "mp3"
        }
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(ctx, "soundwave", 0, ownerContext)
        val tree = ctx.applicationContext.getSharedPreferences("soundwave", 0).getString("download_tree_uri", "").orEmpty().let { raw ->
            if (raw.isBlank()) null else try { DocumentFile.fromTreeUri(ctx, Uri.parse(raw)) } catch (_: Exception) { null }
        }
        // 分段下载必须落随机文件；自定义 SAF 目录在完成后以流复制过去。
        val stagingDir = dir(ctx)
        // taskId 用于私有可续传 .part staging；已存在目标一律使用唯一新文件名，不原位覆盖。
        val fileSuffix = taskId.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(48).ifBlank { "task" }
        val finalBase = safeName(song, ctx, ownerContext)
        val tmp = File(stagingDir, ".$finalBase-${fileSuffix}.$ext.part")
        val stagingExistedBefore = tmp.exists()
        val remote = probeRemote(session, url, cancellationGate)
        ensureActive()
        var total = remote.total
        if (total > MAX_MEDIA_BYTES) error("媒体文件超过 1GB 安全上限")
        val hintedType = remote.contentType.substringBefore(';').trim().lowercase()
        if (hintedType.startsWith("text/") || hintedType.contains("json") || hintedType.contains("html")) {
            error("媒体响应类型异常：$hintedType")
        }
        val deadlineMs = DownloadResumePolicy.deadlineMs(total)
        deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        val mt = prefs.getBoolean("download_mt", true)
        // r657：只有 .part 的来源指纹与本次远端一致才续传，否则从头覆盖（见 DownloadResumePolicy）。
        val resumeMeta = File(tmp.path + RESUME_META_SUFFIX)
        val resumeIdentity = DownloadResumePolicy.identity(
            xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(ownerContext), song.key(),
            resolve?.candidateKey.orEmpty(), resolve?.platform.orEmpty().ifBlank { song.source },
            resolve?.quality.orEmpty().ifBlank { song.actualQuality }, resolve?.scriptId.orEmpty(), url
        )
        val remoteFingerprint = DownloadResumePolicy.Fingerprint(remote.total, remote.validator, resumeIdentity)
        val storedFingerprint = try {
            DownloadResumePolicy.decode(resumeMeta.takeIf { it.isFile }?.readText())
        } catch (_: Exception) { null }
        val existingPartBytes = tmp.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
        val partialBytes = DownloadResumePolicy.resumeOffset(existingPartBytes, storedFingerprint, remoteFingerprint)
        // Do not relabel old bytes before GET has validated and opened/truncated the file.
        // The single writer publishes its actual response fingerprint; failed multi deletes both.
        // 多线程随机写只从干净 .part 开始；续传始终绑定同一个 ETag/Last-Modified。
        // 多段随机写必须绑定稳定实体；只有 Accept-Ranges/206 而没有 ETag/Last-Modified
        // 时，资源可能在分段期间变化，强制降级单流，避免拼出损坏文件。
        val hasValidator = remote.etag.isNotBlank() || remote.lastModified.isNotBlank()
        val ranged = partialBytes == 0L && mt && !preferSingleStream && total > 2L * 1024L * 1024L &&
            remote.rangeSupported && hasValidator
        if (ranged) {
            try {
                downloadMulti(ctx, expectedGeneration, url, tmp, remote, session, taskId, expectedTaskEpoch, cancellationGate, onProgress, deadlineMs)
            } catch (t: Throwable) {
                // 多线程随机写的是预分配的稀疏文件，无法判断哪些区段已完成，不能拿来续传。
                try {
                    withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) {
                        tmp.delete()
                        resumeMeta.delete()
                    }
                } catch (_: java.util.concurrent.CancellationException) { /* clear/pause owns stale staging cleanup */ }
                throw t
            }
        } else {
            // A successful validator match may still have an unknown HEAD/probe length.
            // Preserve the stored fingerprint's known total for the 206 range check.
            val resumeRemote = if (partialBytes > 0L && remote.total <= 0L && storedFingerprint != null && storedFingerprint.total > 0L) {
                remote.copy(total = storedFingerprint.total)
            } else remote
            total = downloadSingle(ctx, expectedGeneration, url, tmp, resumeRemote, partialBytes, session, taskId, expectedTaskEpoch, cancellationGate, onProgress, deadlineMs, resumeIdentity)
        }
        val finalizeDownload: () -> DownloadItem = {
            ensureActive()
            // 受支持的加密媒体超过解密上限时必须失败，不能把密文伪装成已完成的普通音频文件。
            val downloadedBytes = tmp.length()
            if (DownloadDecryptLimitPolicy.exceedsLimit(ext, downloadedBytes)) {
                try { tmp.delete() } catch (_: Exception) {}
                throw EncryptedMediaTooLargeException()
            }
            // 解密失败仍保持原有路径语义；本次仅明确拒绝超限加密文件。
            if (downloadedBytes in 1..DownloadDecryptLimitPolicy.MAX_DECRYPT_BYTES) {
                val plainTmp = File.createTempFile("sw-${fileSuffix.take(32)}-", ".$ext.plain.part", stagingDir)
                val realDecryptExt: String? = try {
                    tmp.inputStream().use { input ->
                        plainTmp.outputStream().use { out ->
                            when (ext) {
                                "ncm" -> NcmDecrypt.decryptStream(input, out)
                                "mflac", "mgg", "tkm", "qmcflac", "qmcogg", "bkcflac" ->
                                    QmcDecrypt.decryptStream(input, out, ext)
                                else -> null
                            }
                        }
                    }
                } catch (_: Exception) {
                    null
                }
                try {
                    if (realDecryptExt != null) {
                        // 解密成功：仅替换本任务的私有 staging；plainTmp 是本次创建的唯一临时文件。
                        tmp.delete()
                        if (!plainTmp.renameTo(tmp)) {
                            plainTmp.copyTo(tmp, overwrite = true)
                        }
                        ext = realDecryptExt
                        total = tmp.length()
                    }
                } finally {
                    try { plainTmp.delete() } catch (_: Exception) {}
                }
            }
            // 在任何输出目标创建前完成容器与音轨验证；失败时旧记录和旧文件均不动。
            validateCompletedFile(tmp, total)
            onProgress?.invoke(0.98f)
            val fileBase = finalBase
            val previousPath = previousOutputPath.takeUnless { it.isBlank() || it.startsWith("pending:") }
            val initialWarning = if (previousPath != null || (tree != null && !stagingExistedBefore)) {
                "新文件已保存，旧文件/临时文件清理状态待确认"
            } else ""
            fun makeItem(path: String, size: Long, warning: String) = DownloadItem(
                song = song,
                path = path,
                size = size,
                time = System.currentTimeMillis(),
                status = "done",
                progress = 1f,
                error = warning,
                // ACCEPT5（下载盖章）：脚本按请求档回链、自报档不可信（实证
                // master 档回 128k MP3）。落盘盖章只认实测——最终格式（ext
                // 已解密归一）+ 实收字节（提交时核验过的 byteCount）+ 时长；
                // 实测无证据才回退解析自报档，与 oiapi 实测盖章同纪律。
                quality = AudioQualityEvidence.stampedDownloadQuality(
                    claimed = resolve?.quality.orEmpty().ifBlank { song.actualQuality },
                    formatLabel = AudioQualityEvidence.formatHint("media.$ext", "")?.label,
                    totalBytes = size,
                    durationMs = song.duration
                ),
                platform = resolve?.platform.orEmpty().ifBlank { song.source },
                scriptId = resolve?.scriptId.orEmpty(),
                resolveFailure = resolve?.failure?.name.orEmpty(),
                taskId = taskId
            )
            var committedItem: DownloadItem? = null
            var committedPath = ""
            val commit = DownloadSafCommitProtocol.commit(
                createUniqueTarget = {
                    if (tree != null) {
                        val uniqueName = "$fileBase-${UUID.randomUUID().toString().take(12)}.$ext"
                        val document = tree.createFile("audio/*", uniqueName)
                            ?: throw java.io.IOException("无法在 SAF 目录创建新文件")
                        if (document.uri.toString() == previousPath) {
                            throw java.io.IOException("SAF provider 未创建独立的新目标")
                        }
                        document
                    } else {
                        createUniqueLocalTarget(stagingDir, fileBase, ext)
                    }
                },
                copyAndVerify = { target, checkpoint ->
                    if (target is DocumentFile) {
                        copyAndVerify(
                            source = tmp,
                            openOutput = {
                                ctx.contentResolver.openOutputStream(target.uri, "w")
                                    ?: throw java.io.IOException("无法写入 SAF 文件")
                            },
                            openInput = {
                                ctx.contentResolver.openInputStream(target.uri)
                                    ?: throw java.io.IOException("无法回读 SAF 文件")
                            },
                            checkpoint = checkpoint
                        )
                    } else {
                        val file = target as File
                        copyAndVerify(
                            source = tmp,
                            openOutput = { FileOutputStream(file, false) },
                            openInput = { FileInputStream(file) },
                            checkpoint = checkpoint
                        )
                    }
                },
                ensureActive = { ensureActive() },
                persist = { target, byteCount ->
                    val path = when (target) {
                        is DocumentFile -> target.uri.toString()
                        is File -> target.absolutePath
                        else -> error("未知下载目标类型")
                    }
                    committedPath = path
                    makeItem(path, byteCount, initialWarning).also { committedItem = it }
                        .let { ContextData(ctx, ownerContext, cancellationEpoch).addDownloadDurably(it) }
                },
                deleteNewTarget = { target ->
                    when (target) {
                        is DocumentFile -> ctx.contentResolver.delete(target.uri, null, null)
                        is File -> target.delete()
                    }
                },
                deletePreviousTarget = { deleteOutputPath(ctx, previousPath, committedPath) }
            )
            var item = committedItem ?: error("下载记录提交后未生成结果")
            val warnings = mutableListOf<String>()
            if (previousPath != null && !commit.previousDeleted) warnings += "旧文件未能删除，已保留旧文件"
            if (tmp.exists() && !tmp.delete()) warnings += "内部临时文件清理失败"
            val finalWarning = warnings.joinToString("；")
            if (item.error != finalWarning) {
                item = item.copy(error = finalWarning)
                if (!ContextData(ctx, ownerContext, cancellationEpoch).addDownloadDurably(item)) {
                    android.util.Log.w("DownloadHelper", "could not persist post-commit cleanup warning")
                }
            }
            if (commit.target is File) writeLocalSidecars(ctx, prefs, song, fileBase, commit.target)
            item
        }
        return withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { finalizeDownload() }.also {
            try { withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { resumeMeta.delete() } } catch (_: java.util.concurrent.CancellationException) {}
        }
        } finally { activeCalls.finish(session) }
    }

    private data class StreamDigest(val byteCount: Long, val sha256: ByteArray)

    private fun createUniqueLocalTarget(directory: File, base: String, ext: String): File {
        val preferred = File(directory, "$base.$ext")
        if (preferred.createNewFile()) return preferred
        repeat(8) {
            val candidate = File(directory, "$base-${UUID.randomUUID().toString().take(12)}.$ext")
            if (candidate.createNewFile()) return candidate
        }
        throw java.io.IOException("无法分配唯一的下载文件名")
    }

    /** Copies to a newly created target and reads it back to prove both length and contents. */
    private fun copyAndVerify(
        source: File,
        openOutput: () -> OutputStream,
        openInput: () -> InputStream,
        checkpoint: () -> Unit
    ): Long {
        val expected = source.inputStream().use { input ->
            openOutput().use { output -> digestStream(input, output, checkpoint) }
        }
        if (expected.byteCount != source.length()) throw java.io.IOException("下载目标复制长度不完整")
        checkpoint()
        val actual = openInput().use { input -> digestStream(input, null, checkpoint) }
        if (actual.byteCount != expected.byteCount || !MessageDigest.isEqual(actual.sha256, expected.sha256)) {
            throw java.io.IOException("下载目标回读校验失败")
        }
        checkpoint()
        return expected.byteCount
    }

    private fun digestStream(input: InputStream, output: OutputStream?, checkpoint: () -> Unit): StreamDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var byteCount = 0L
        while (true) {
            checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            checkpoint()
            output?.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            byteCount += read
        }
        output?.flush()
        checkpoint()
        return StreamDigest(byteCount, digest.digest())
    }

    internal fun deleteOutputPath(ctx: Context, previousPath: String?, currentPath: String): Boolean {
        if (previousPath.isNullOrBlank() || previousPath == currentPath) return true
        return deleteLocalMediaResource(ctx, previousPath)
    }

    /** SAF copies intentionally do not leave internal lyric/cover sidecars behind. */
    private fun writeLocalSidecars(ctx: Context, prefs: android.content.SharedPreferences, song: Song, base: String, output: File) {
        if (prefs.getBoolean("save_ext_lyric", true)) {
            var created: File? = null
            try {
                    val lrc = lyricBundleMainText(CacheManager.loadLyric(ctx, lyricCacheKey(song, "v2yrc"))
                        .ifBlank { CacheManager.loadLyric(ctx, lyricCacheKey(song)) })
                val target = File(output.parentFile, "$base.lrc")
                if (lrc.isNotEmpty() && target.createNewFile()) {
                    created = target
                    target.writeText(lrc)
                }
            } catch (_: Exception) {
                created?.delete()
            }
        }
        if (prefs.getBoolean("save_ext_cover", true) && song.cover.isNotEmpty()) {
            var created: File? = null
            try {
                val source = File(CacheManager.ensureCover(ctx, song.cover))
                val target = File(output.parentFile, "$base.jpg")
                if (source.isFile && target.createNewFile()) {
                    created = target
                    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            } catch (_: Exception) {
                created?.delete()
            }
        }
    }


    private fun baseRequest(url: String): Request.Builder {
        return MediaRequestPolicy.apply(Request.Builder().url(url))
    }

    private fun probeRemote(session: DownloadRequestRegistry.Session, url: String, shouldCancel: () -> Boolean): RemoteObject {
        fun fromResponse(resp: okhttp3.Response): RemoteObject {
            val length = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val range = resp.header("Accept-Ranges")?.contains("bytes", true) == true || resp.code == 206
            return RemoteObject(length, resp.header("ETag").orEmpty(), resp.header("Last-Modified").orEmpty(), range, resp.code, resp.header("Content-Type").orEmpty())
        }
        val head = baseRequest(url).head().build()
        val call = track(session, client.newCall(head), shouldCancel)
        try {
            executeNetworkCall(call).use { resp -> if (resp.isSuccessful) return fromResponse(resp) }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Some CDNs reject HEAD; the bounded range probe below is authoritative.
        } finally { untrack(session, call) }
        val range = baseRequest(url).header("Range", "bytes=0-0").build()
        val rangeCall = track(session, client.newCall(range), shouldCancel)
        try {
            executeNetworkCall(rangeCall).use { resp ->
                if (resp.code == 206) {
                    val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    return RemoteObject(total, resp.header("ETag").orEmpty(), resp.header("Last-Modified").orEmpty(), true, resp.code, resp.header("Content-Type").orEmpty())
                }
                // Some CDNs ignore Range and return a normal bounded GET response. Keep the
                // response metadata and fall back to a single stream instead of rejecting it.
                if (resp.isSuccessful) {
                    val total = resp.header("Content-Length")?.toLongOrNull() ?: resp.body?.contentLength() ?: -1L
                    return RemoteObject(total, resp.header("ETag").orEmpty(), resp.header("Last-Modified").orEmpty(), false, resp.code, resp.header("Content-Type").orEmpty())
                }
                return RemoteObject(-1L, "", "", false)
            }
        } finally { untrack(session, rangeCall) }
    }

    private fun conditional(builder: Request.Builder, remote: RemoteObject): Request.Builder =
        if (remote.validator.isBlank()) builder else builder.header("If-Range", remote.validator)

    private fun assertValidator(resp: okhttp3.Response, remote: RemoteObject) {
        if (remote.validator.isBlank()) return
        val actual = if (remote.etag.isNotBlank()) resp.header("ETag").orEmpty() else resp.header("Last-Modified").orEmpty()
        if (actual != remote.validator) throw java.io.IOException("下载资源已变更，已保留断点等待重试")
    }

    private fun validateCompletedFile(file: File, expectedTotal: Long) {
        if (!file.isFile || file.length() <= 256L) throw java.io.IOException("下载文件为空或过小")
        if (expectedTotal > 0L && file.length() != expectedTotal) throw java.io.IOException("下载文件长度不完整：${file.length()}/$expectedTotal")
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        val valid = read >= 3 && (
            (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
            (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) ||
            (read >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "fLaC") ||
            (read >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "OggS") ||
            (read >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp")
        )
        if (!valid) throw java.io.IOException("下载内容不是可识别的音频容器")
        // 容器头合法不等于可播：CDN 可能返回伪装成音频的 HTML/JSON 或损坏头。
        // 用 MediaExtractor 做最小解析，确认至少存在一条音频轨道，失败不标 done。
        validateDecodable(file)
    }

    /** MediaExtractor 最小解析：至少一条音频轨道才算可播文件。 */
    private fun validateDecodable(file: File) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTracks = 0
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audioTracks++
            }
            if (audioTracks == 0) throw java.io.IOException("下载内容无可解析的音频轨道")
        } catch (e: java.io.IOException) {
            throw e
        } catch (_: Exception) {
            throw java.io.IOException("下载内容无法解析为音频")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** 206 必须覆盖请求的完整剩余范围；200 始终覆盖，并以实际响应长度完成验收。 */
    private fun downloadSingle(ctx: Context, expectedGeneration: Long?, url: String, tmp: File, remote: RemoteObject, existingBytes: Long, session: DownloadRequestRegistry.Session, taskId: String, expectedTaskEpoch: Long?, shouldCancel: () -> Boolean, onProgress: ((Float) -> Unit)?, deadlineMs: Long = DownloadResumePolicy.MIN_DEADLINE_MS, resumeIdentity: String = ""): Long {
        val resumeFrom = existingBytes.takeIf { it > 0L && (remote.total <= 0L || it < remote.total) } ?: 0L
        val request = baseRequest(url).apply {
            if (resumeFrom > 0L) { header("Range", "bytes=$resumeFrom-"); conditional(this, remote) }
        }.build()
        // 单流的 callTimeout 跟随整次下载时限；超时后保留 .part，由 WorkManager 重试续传。
        val streamClient = if (deadlineMs > TimeUnit.MINUTES.toMillis(DOWNLOAD_CALL_TIMEOUT_MINUTES)) {
            client.newBuilder().callTimeout(deadlineMs, TimeUnit.MILLISECONDS).build()
        } else client
        val call = track(session, streamClient.newCall(request), shouldCancel)
        try {
            return executeNetworkCall(call).use { resp ->
                if (!resp.isSuccessful) throw DownloadHttpStatusException(resp.code)
                if (resp.code != 200 && resp.code != 206) throw java.io.IOException("不支持的媒体响应：${resp.code}")
                val append = resumeFrom > 0L && resp.code == 206
                if (append) assertValidator(resp, remote)
                val body = resp.body ?: error("empty body")
                val range = if (resp.code == 206) ContentRangePolicy.requireSingle(
                    resp.header("Content-Range"), resumeFrom, remote.total, body.contentLength()
                ) else null
                // A 200 may be a new entity: never inherit the probe's old length.
                val responseTotal = range?.total ?: body.contentLength()
                if (responseTotal > MAX_MEDIA_BYTES) error("媒体文件超过 1GB 安全上限")
                val expectedBodyBytes = range?.byteCount ?: body.contentLength()
                var bodyRead = 0L
                val writeOffset = if (append) resumeFrom else 0L
                body.byteStream().use { input ->
                    val output = withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { FileOutputStream(tmp, append) }
                    output.use { fos ->
                        // Preserve source identity while refreshing the actual 200 entity metadata.
                        if (!append) {
                            val actual = DownloadResumePolicy.Fingerprint(responseTotal,
                                resp.header("ETag").orEmpty().ifBlank { resp.header("Last-Modified").orEmpty() }, resumeIdentity)
                            val meta = File(tmp.path + RESUME_META_SUFFIX)
                            try {
                                withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) {
                                    if (actual.validator.isNotBlank()) meta.writeText(DownloadResumePolicy.encode(actual))
                                    else meta.delete()
                                }
                            } catch (cancelled: java.util.concurrent.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { meta.delete() }
                            }
                        }
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (shouldCancel()) { ResolveBlockingStage.cancelCallAsync(call); throw java.util.concurrent.CancellationException("下载已取消") }
                            val n = readNetwork(call, input, buf)
                            if (n < 0) break
                            if (n == 0) continue
                            val nextBodyRead = bodyRead + n
                            if (expectedBodyBytes >= 0L && nextBodyRead > expectedBodyBytes) {
                                throw java.io.IOException("媒体响应正文超长")
                            }
                            val read = writeOffset + nextBodyRead
                            if (read > MAX_MEDIA_BYTES) error("媒体文件超过 1GB 安全上限")
                            withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { fos.write(buf, 0, n) }
                            bodyRead = nextBodyRead
                            if (responseTotal > 0) onProgress?.invoke((read.toFloat() / responseTotal).coerceIn(0f, 1f))
                        }
                        if (range != null) ContentRangePolicy.requireBodyLength(range, bodyRead)
                        else if (expectedBodyBytes >= 0L && bodyRead != expectedBodyBytes) {
                            throw java.io.IOException("媒体响应正文短读")
                        }
                        withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { fos.flush() }
                    }
                }
                if (responseTotal >= 0L) responseTotal else bodyRead
            }
        } finally { untrack(session, call) }
    }

    private fun downloadMulti(ctx: Context, expectedGeneration: Long?, url: String, tmp: File, remote: RemoteObject, session: DownloadRequestRegistry.Session, taskId: String, expectedTaskEpoch: Long?, shouldCancel: () -> Boolean, onProgress: ((Float) -> Unit)?, deadlineMs: Long = DownloadResumePolicy.MIN_DEADLINE_MS) {
        val total = remote.total
        val threads = 4
        val stagingFile = withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { RandomAccessFile(tmp, "rw") }
        stagingFile.use { withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { it.setLength(total) } }
        val done = AtomicLong(0L)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val chunk = (total + threads - 1) / threads
        for (i in 0 until threads) {
            val start = i * chunk
            val end = minOf(total - 1, start + chunk - 1)
            if (start > end) { latch.countDown(); continue }
            pool.execute {
                var call: Call? = null
                try {
                    val req = conditional(baseRequest(url).header("Range", "bytes=$start-$end"), remote).build()
                    val activeCall = track(session, client.newCall(req), shouldCancel)
                    call = activeCall
                    executeNetworkCall(activeCall).use { resp ->
                        if (!resp.isSuccessful) throw DownloadHttpStatusException(resp.code)
                        assertValidator(resp, remote)
                        val range = resp.header("Content-Range").orEmpty()
                        val expectedRange = "bytes $start-$end/$total"
                        if (resp.code != 206 || !range.substringBefore(';').trim().equals(expectedRange, ignoreCase = true)) error("Range 响应不匹配")
                        val body = resp.body ?: error("empty body")
                        val expectedBytes = end - start + 1L
                        var segmentRead = 0L
                        val output = withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { RandomAccessFile(tmp, "rw") }
                        body.byteStream().use { input -> output.use { raf ->
                            withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { raf.seek(start) }
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (shouldCancel()) { ResolveBlockingStage.cancelCallAsync(activeCall); throw java.util.concurrent.CancellationException("下载已取消") }
                                val n = readNetwork(activeCall, input, buf)
                                if (n <= 0) break
                                segmentRead += n
                                if (segmentRead > expectedBytes || start + segmentRead > MAX_MEDIA_BYTES) error("媒体文件超过 1GB 安全上限或 Range 分段超长")
                                withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { raf.write(buf, 0, n) }
                                onProgress?.invoke((done.addAndGet(n.toLong()).toFloat() / total).coerceIn(0f, 1f))
                            }
                        } }
                        if (segmentRead != expectedBytes) error("Range 分段短读：$segmentRead/$expectedBytes")
                    }
                } catch (t: Throwable) { errors.add(t) }
                finally { call?.let { untrack(session, it) }; latch.countDown() }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (shouldCancel()) { cancelSession(session); pool.shutdownNow(); throw java.util.concurrent.CancellationException("下载已取消") }
            if (System.nanoTime() >= deadline) { cancelSession(session); pool.shutdownNow(); throw DownloadTransportException(java.io.IOException("分段下载超时")) }
        }
        pool.shutdownNow()
        if (errors.isNotEmpty()) throw errors.first()
    }

    /** 打开下载目录（系统文件管理 / 选择器） */
    fun openDir(ctx: Context): Boolean {
        return try {
            val d = dir(ctx)
            if (!d.exists()) d.mkdirs()
            val marker = File(d, ".soundwave_folder")
            if (!marker.exists()) marker.writeText("SoundWave downloads\n")
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", marker)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.startActivity(Intent.createChooser(view, "打开下载目录"))
                true
            } catch (_: Exception) {
                val pathText = "下载目录：" + "\n" + d.absolutePath
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, pathText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(send, "下载目录"))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 选择自定义下载目录（需调用方用 ActivityResult 接收 treeUri 并持久化） */
    fun pickDirIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    fun ensureNomedia(ctx: Context) {
        try {
            val f = File(dir(ctx), ".nomedia")
            if (!f.exists()) f.writeText("")
        } catch (_: Exception) {}
    }
}
