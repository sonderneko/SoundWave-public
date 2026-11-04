package xyz.cncnlp.soundwave

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.download.AudioQualityProbe

/**
 * 下载编排单点：从 AppRoot 抽出。持有下载探音质选择态与下载队列，统一「探音质 + 入队」逻辑。
 * 音质偏好（downloadQ/preferQ）与脚本为跨切共享状态，通过 getter 注入。
 */
class DownloadController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scriptRuntime: ScriptRuntime,
    private val getScripts: () -> List<Script>,
    private val getDownloadQ: () -> String,
    private val getPreferQ: () -> String,
) {
    val downloadPickSongState = mutableStateOf<Song?>(null)
    var downloadPickSong: Song?
        get() = downloadPickSongState.value
        set(v) { downloadPickSongState.value = v }
    val downloadPickSizesState = mutableStateOf<Map<String, AudioQualityProbe>>(emptyMap())
    var downloadPickSizes: Map<String, AudioQualityProbe>
        get() = downloadPickSizesState.value
        set(v) { downloadPickSizesState.value = v }
    val downloadPickQualityState = mutableStateOf("")
    var downloadPickQuality: String
        get() = downloadPickQualityState.value
        set(v) { downloadPickQualityState.value = v }
    val downloadsState = mutableStateOf(LocalStore.loadDownloads(context))
    var downloads: List<DownloadItem>
        get() = downloadsState.value
        set(v) { downloadsState.value = v }

    /** 当前音质探测任务；确认下载或切换歌曲时必须主动取消，而不是只让回调返回 false。 */
    private var qualityProbeJob: Job? = null
    @Volatile private var activeProbeRuntime: ScriptRuntime? = null

    fun startDownload(s: Song) {
        // 同一面板重复打开时，旧歌曲的探测必须立即退出，避免多个 PROBE 串在脚本运行时之后。
        qualityProbeJob?.cancel(CancellationException("new download target"))
        activeProbeRuntime?.cancelSpeculativeUrlRequests()
        scriptRuntime.cancelSpeculativeUrlRequests()

        downloadPickSong = s
        downloadPickQuality = normalizeUserSelectableQuality(getDownloadQ().ifBlank { getPreferQ() })
        val qualities = USER_AUDIO_QUALITY_OPTIONS.map { it.first }
        downloadPickSizes = qualities.associateWith { AudioQualityProbe(requestedQuality = it, failure = "检测中…") }
        // 每档独立走同一 Resolver；HEAD 优先，缺失总长度时 Range 回退，不把 206 分段长度当文件大小。
        qualityProbeJob = scope.launch(Dispatchers.IO) {
            var probeRuntime: ScriptRuntime? = null
            try {
                // r658d（用户 10-20 点下载卡顿）：抽屉入场动画（约 420ms）期间不跑取链探测——脚本取链要占主线程 WebView，
                // 与抽屉位移动画抢帧；动画结束后再开始逐档探测。
                kotlinx.coroutines.delay(450L)
                val isolatedRuntime = scriptRuntime.forkForProbe()
                probeRuntime = isolatedRuntime
                activeProbeRuntime = isolatedRuntime
                val probes = linkedMapOf<String, AudioQualityProbe>()
                for (q in qualities) {
                    if (!currentCoroutineContext().isActive || downloadPickSong?.key() != s.key()) return@launch
                    val result = try {
                        withTimeoutOrNull(9_000L) {
                            UrlResolver.resolveResult(
                                ticket = ResolveTicket(songKey = s.key(), purpose = ResolveTicket.Purpose.PROBE),
                                song = s,
                                scripts = getScripts(),
                                runtime = isolatedRuntime,
                                preferQuality = q,
                                requestStillCurrent = { downloadPickSong?.key() == s.key() }
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (!currentCoroutineContext().isActive) return@launch
                    val probe = when {
                        result == null -> AudioQualityProbe(requestedQuality = q, durationMs = s.duration, failure = "取链超时或失败")
                        !result.isSuccess -> AudioQualityProbe(
                            requestedQuality = q,
                            durationMs = s.duration,
                            resolveMs = result.elapsedMs.takeIf { it > 0L } ?: -1L,
                            platform = result.platform.ifBlank { s.source },
                            scriptId = result.scriptId,
                            failure = UrlResolver.failureMessage(result.failure).ifBlank { "未取得可用链接" }
                        )
                        else -> {
                            val metadata = ApiClient.headContentInfo(result.url)
                            val format = AudioQualityEvidence.formatHint(result.url, metadata.contentType)
                            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                                .digest(result.url.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it) }
                            AudioQualityProbe(
                                requestedQuality = q,
                                resolvedQuality = result.quality,
                                bytes = metadata.sizeBytes,
                                durationMs = s.duration,
                                contentType = metadata.contentType,
                                format = format?.label.orEmpty(),
                                formatEvidence = format?.evidence.orEmpty(),
                                httpStatus = metadata.httpStatus,
                                requestMethod = metadata.method,
                                rangeHeader = metadata.rangeHeader,
                                contentRange = metadata.contentRange,
                                sizeSource = metadata.sizeSource,
                                resolveMs = result.elapsedMs,
                                platform = result.platform.ifBlank { s.source },
                                scriptId = result.scriptId,
                                failure = if (metadata.httpStatus == 0) "媒体大小/格式响应未取得" else "",
                                urlFingerprint = fingerprint
                            )
                        }
                    }
                    probes[q] = probe
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (downloadPickSong?.key() == s.key()) downloadPickSizes = probes.toMap()
                    }
                }
            } finally {
                probeRuntime?.let { isolated ->
                    isolated.cancelSpeculativeUrlRequests()
                    if (activeProbeRuntime === isolated) activeProbeRuntime = null
                    withContext(NonCancellable) { isolated.closeAndWait() }
                }
                if (qualityProbeJob === currentCoroutineContext()[Job]) {
                    qualityProbeJob = null
                }
            }
        }
    }

    /**
     * r658d（批量下载卡顿）：批量加入只开一个后台协程顺序入队、最后刷新一次列表、只弹一次提示；
     * 原先每首各开协程并发读写同一份下载记录，且每首弹一次 Toast。
     */
    fun confirmDownloads(songs: List<Song>, quality: String) {
        if (songs.isEmpty()) return
        qualityProbeJob?.cancel(CancellationException("batch download"))
        activeProbeRuntime?.cancelSpeculativeUrlRequests()
        qualityProbeJob = null
        val preferred = normalizeUserSelectableQuality(getDownloadQ().ifEmpty { getPreferQ() })
        scope.launch(Dispatchers.IO) {
            val requests = songs.map { s -> s to normalizeUserSelectableQuality(
                quality.ifBlank { SmartSourceStore.platformDownloadQuality(s.source.ifEmpty { "wy" }, preferred) }) }
            val ok = try { DownloadScheduler.enqueueBatch(context, requests).size } catch (_: Exception) { 0 }
            val latest = LocalStore.loadDownloads(context)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                downloads = latest
                AppNotice.attach(context.applicationContext)
                AppNotice.toast(if (ok == songs.size) "已加入下载 $ok 首" else "已加入下载 $ok / ${songs.size} 首", AppNotice.Kind.SUCCESS)
            }
        }
    }

    fun confirmDownload(s: Song, quality: String) {
        // 用户已选定音质后立即停止音质探测，不能只依赖 requestStillCurrent；
        // 显式取消 Coroutine + ScriptRuntime 中的 PROBE 网络请求，避免后台探测继续占用资源。
        qualityProbeJob?.cancel(CancellationException("download quality selected"))
        activeProbeRuntime?.cancelSpeculativeUrlRequests()
        qualityProbeJob = null
        scriptRuntime.cancelSpeculativeUrlRequests()

        val preferred = normalizeUserSelectableQuality(getDownloadQ().ifEmpty { getPreferQ() })
        val q = normalizeUserSelectableQuality(
            quality.ifBlank { SmartSourceStore.platformDownloadQuality(s.source.ifEmpty { "wy" }, preferred) }
        )
        // J 波：downloadPickSong 不在此处置空——下载音质弹层点「下载」后改走动画关闭，
        // 退场结束时由弹层 onDismiss 统一置空（此前在此同步置空会让弹层即刻卸载、跳过退场）。
        downloadPickQuality = ""
        // WorkManager 首次初始化会打开/迁移数据库，不能在 Compose 点击回调的主线程执行。
        // 前台只提交任务，解析与下载全部由后台 Worker 执行。
        scope.launch(Dispatchers.IO) {
            try {
                DownloadScheduler.enqueue(context, s, q)
                val latest = LocalStore.loadDownloads(context)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    downloads = latest
                    AppNotice.attach(context.applicationContext)
                    AppNotice.toast("已加入下载 · $q", AppNotice.Kind.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    AppNotice.attach(context.applicationContext)
                    AppNotice.toast("加入下载失败", AppNotice.Kind.FAILURE)
                }
            }
        }
    }
}
