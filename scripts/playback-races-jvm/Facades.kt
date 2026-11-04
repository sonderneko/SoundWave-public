@file:Suppress("UNUSED_PARAMETER")
package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import xyz.cncnlp.soundwave.playback.*
import xyz.cncnlp.soundwave.migration.*
// JVM Main event loop seam only; controller method bodies remain verbatim.
object Dispatchers {
    val IO = kotlinx.coroutines.Dispatchers.IO
    val Main = kotlinx.coroutines.Dispatchers.Unconfined
    val Unconfined = kotlinx.coroutines.Dispatchers.Unconfined
}
class KgAlbumMetadata
class Toast { fun show() {} companion object {
    const val LENGTH_SHORT = 0; const val LENGTH_LONG = 1
    val messages = mutableListOf<String>()
    fun makeText(context: Any, message: String, duration: Int): Toast { messages += message; return Toast() }
} }
object BuildConfig { const val DEBUG = false }
enum class PlayMode { SEQUENCE, LOOP_ONE, SHUFFLE, HEART }
class MediaCacheLease(val path: String = "/plain") { var closes = 0; fun close() { closes++ } }
class TestPlayer { var seeks = 0; var plays = 0; fun seekTo(position: Long) { seeks++ }; fun play() { plays++ } }
class TestHandler { fun post(block: () -> Unit): Boolean { block(); return true } }
class TestInstance { var wakeHolds = 0; var notifications = 0; var fallbacks = 0; fun holdTransitionWake() { wakeHolds++ }; fun updateSessionAndNotify() { notifications++ }; fun resolveQueueFallback(delta: Int) { fallbacks++ } }
class PlayerService {
    companion object {
        data class PlaybackFailure(val songKey: String, val generation: Int, val message: String = "network", val positionMs: Long = 7000, val durationMs: Long = 200000)
        data class Snapshot(val song: Song? = null, val generation: Int = 0, val queueIndex: Int = 0, val mode: PlayMode = PlayMode.SEQUENCE, val queue: List<Song> = emptyList())
        var state = Snapshot()
        var recoveryPolicy = PlaybackRecoveryPolicy()
        val media = mutableListOf<String>()
        val holds = mutableSetOf<String>()
        var transferred: MediaCacheLease? = null
        private val playbackIntent = java.util.concurrent.atomic.AtomicInteger(0)
        var currentSong: Song? = null
        var activePlaybackToken = "play-token"
        var naturalEndHoldToken = ""
        var endedHandledToken = ""
        var isPlayingFlag = true
        val playGen = java.util.concurrent.atomic.AtomicInteger(0)
        var playMode = PlayMode.SEQUENCE
        var sleepStopAfterCurrent = false
        var sleepDeadlineElapsed = 1L
        var queueIndex = 0
        var onSleepTick: ((Long) -> Unit)? = null
        var onPlayState: ((Boolean) -> Unit)? = null
        var onPreparedNext: (() -> Unit)? = null
        var onNeedNext: (() -> Unit)? = null
        var onError: ((PlaybackFailure) -> Unit)? = null
        var instance: TestInstance? = null
        var player: TestPlayer? = null
        var mainHandler = TestHandler()
        var preparedNext = false
        var nextDispatches = 0
        var fallbackDispatches = 0
        val serviceOwner = ProfileWorkIdentity.runtime()
        fun currentPlaybackIntent() = playbackIntent.get()
        fun isPlaybackInterrupted(): Boolean = false
        fun beginPlaybackIntent(): Int = playbackIntent.incrementAndGet()
        fun isPlaybackIntentCurrent(token: Int): Boolean = token == playbackIntent.get()
        fun finishFailedTransition(expectedIntent: Int): Boolean = expectedIntent == playbackIntent.get()
        fun snapshot() = state
        fun currentGeneration() = state.generation
        fun preparedNextKey(): String? = null
        fun holdNaturalEndForTransition(): String = activePlaybackToken.also { naturalEndHoldToken = it; holds += it }
        fun releaseNaturalEndHold(token: String) { if (token.isNotBlank() && naturalEndHoldToken == token) { naturalEndHoldToken = ""; holds -= token } }
        fun hasPreparedNext() = preparedNext
        fun playNextPrepared() = false
        fun resolveQueueFallback(delta: Int) { fallbackDispatches++ }
        // SERVICE_METHOD
        fun setQueue(queue: List<Song>, index: Int, keepPreparedNext: Boolean = false) { state = state.copy(queue = queue.toList(), queueIndex = index) }
        fun pause() {} // Real pause does not advance service generation: deliberately faithful.
        fun stopPlayback() { state = state.copy(generation = state.generation + 1) }
        fun playUrl(context: Any, url: String, song: Song, startMs: Long = 0, cacheLease: MediaCacheLease? = null, sourcePlatform: String = "") {
            media += url; transferred = cacheLease; state = state.copy(song = song, generation = state.generation + 1)
        }
    }
}
class Runtime {
    val ownerContext = ProfileWorkIdentity.runtime()
    var initAction: () -> Unit = {}
    fun init() = initAction()
    fun cancelPlayUrlRequests() {}
}
class Bundle { fun isLiked(song: Song) = false }
object LocalFirst {
    var action: suspend (Song) -> String? = { null }
    suspend fun findLocal(song: Song, context: Any) = action(song)
}
object PlaySourceTracker { fun noteQueue(queue: List<Song>) {} }
object PlaybackDiagnostics {
    fun markStart(song: Song) {}
    fun markFailure(song: Song, layer: String) {}
    fun failureLayerOf(msg: String) = msg
    fun explainFailure(msg: String) = msg
    fun markResolved(song: Song, elapsed: Long, fromCache: Boolean, scriptId: String, platform: String, quality: String) {}
}
object SmartSourceStore {
    fun platformPlayQuality(source: String, quality: String) = quality
    fun forget(key: String) {}
}
object PlaylistImportChecker {
    fun shouldForceCross(key: String) = false
    fun notePlayOutcome(songKey: String, originSource: String, servedPlatform: String, wasForceCross: Boolean) {}
}
object ApiClient { fun playableUrl(raw: String) = raw }
object LyricSourceLoader { fun load(context: Any, song: Song, priority: String, owner: ProfileContext) = "" }
fun parseLyricTextBundle(raw: String) = emptyList<String>()
class ContextData(context: Any, owner: ProfileContext) {
    fun pushHistory(song: Song) {}
    fun history() = emptyList<Song>()
}
object MediaDecryptCache {
    var action: suspend () -> MediaCacheLease? = { MediaCacheLease() }
    fun encryptedExtOf(raw: String): String? = if (raw.endsWith(".ncm")) "ncm" else null
    suspend fun prepareLeased(context: Any, url: String, key: String, ext: String) = action()
    suspend fun prepareResolvedWithLease(context: Any, url: String, result: UrlResolver.Result, ext: String) = action()
}
data class PreparedPlaybackMedia(val url: String, val lease: MediaCacheLease?)
suspend fun preparePlaybackMedia(context: Any, playableUrl: String, result: UrlResolver.Result): PreparedPlaybackMedia? {
    val encrypted = MediaDecryptCache.encryptedExtOf(result.url) ?: MediaDecryptCache.encryptedExtOf(playableUrl)
    if (encrypted == null) return PreparedPlaybackMedia(playableUrl, null)
    var handoffLease: MediaCacheLease? = null
    return try {
        val lease = withContext(kotlinx.coroutines.Dispatchers.IO) {
            MediaDecryptCache.action().also { handoffLease = it }
        } ?: return null
        PreparedPlaybackMedia(lease.path, lease)
    } catch (cancelled: CancellationException) {
        handoffLease?.close()
        throw cancelled
    }
}
object LocalPlayback { fun isLocalSong(song: Song) = song.source.equals("local", ignoreCase = true) }
object PlaybackMetadataWork {
    suspend fun <T> runIfCurrent(isCurrent: () -> Boolean, work: () -> T): T? =
        if (isCurrent()) work().takeIf { isCurrent() } else null
}
enum class ResolveFailure { NONE, STALE_REQUEST, FAILED }
data class ResolveTicket(val songKey: String, val queueRevision: Long, val serviceGeneration: Int,
    val purpose: Purpose, val ownerContext: ProfileContext = ProfileWorkIdentity.runtime()) { enum class Purpose { PLAY } }
object UrlResolver {
    data class Result(val url: String = "https://media", val quality: String = "128k", val failure: ResolveFailure = ResolveFailure.NONE,
        val elapsedMs: Long = 1, val fromCache: Boolean = false, val scriptId: String = "script", val platform: String = "wy", val attempts: List<String> = emptyList()) {
        val isSuccess get() = url.isNotEmpty()
    }
    var progress: ((String) -> Unit)? = null
    var action: suspend (Song) -> Result = { Result() }
    var clearAction: suspend () -> Unit = {}
    suspend fun clearSongCache(song: Song, owner: ProfileContext) = clearAction()
    fun markWinningScriptBad(key: String) = "script"
    fun playbackFailureMessage(msg: String) = msg
    fun failureMessage(failure: ResolveFailure) = failure.name
    fun attemptsSummary(attempts: List<String>) = ""
    suspend fun resolveResult(ticket: ResolveTicket, song: Song, scripts: List<String>, runtime: Runtime, preferQuality: String,
        skipScripts: Set<String>, forceCross: Boolean, requestStillCurrent: () -> Boolean, onProgress: (String) -> Unit): Result { progress = onProgress; return action(song) }
}
class RaceController(private val scope: CoroutineScope) {
    val context = Any()
    val scriptRuntime = Runtime()
    val controllerOwner = ProfileWorkIdentity.runtime()
    var currentSong: Song? = null
    var activeResolveJob: Job? = null
    var activeHeartJob: Job? = null
    var queueRevision = 0L
    val playbackTransactionGate = PlaybackTransactionGate()
    fun cancelPrefetchBatch() {}
    fun cancelLyrics() {}
    var queue = emptyList<Song>()
    var resolving = false
    var liked = false
    var lyrics = emptyList<String>()
    var lyricIdx = -1
    var status = ""
    fun getUserBundle() = Bundle()
    fun getScripts() = emptyList<String>()
    fun getPreferQ() = "128k"
    fun getLyricPriority() = "external"
    fun refreshLyrics(song: Song) {}
    fun onHistoryChanged(history: List<Song>) {}
    var skips = 0
    suspend fun safePlayDelta(delta: Int) { skips++ }
    // RECOVERY
    // METHODS
}
