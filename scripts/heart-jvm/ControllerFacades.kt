@file:Suppress("UNUSED_PARAMETER")
package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import kotlin.random.Random
import xyz.cncnlp.soundwave.recommend.TasteProfileV2
object Toast {
    const val LENGTH_SHORT = 0
    val messages = mutableListOf<String>()
    fun makeText(context: Any, message: String, duration: Int): Toast { messages += message; return this }
    fun show() {}
}
enum class PlayMode { SEQUENCE, HEART }
enum class HeartModeToggleAction { START, STOP }
fun heartModeToggleAction(mode: PlayMode) = if (mode == PlayMode.HEART) HeartModeToggleAction.STOP else HeartModeToggleAction.START
class KgAlbumMetadata
object PlayerService {
    private val intent = java.util.concurrent.atomic.AtomicInteger()
    fun beginPlaybackIntent() = intent.incrementAndGet()
    fun isPlaybackIntentCurrent(token: Int) = intent.get() == token
    data class Snapshot(val queue: List<Song> = emptyList(), val mode: PlayMode = PlayMode.SEQUENCE, val generation: Int = 0, val queueIndex: Int = 0)
    var state = Snapshot()
    fun snapshot() = state
    fun reset() { state = Snapshot() }
    fun setMode(mode: PlayMode) { state = state.copy(mode = mode) }
    fun currentGeneration() = state.generation
    fun setQueue(queue: List<Song>, index: Int) { state = state.copy(queue = queue, queueIndex = index) }
    val media = mutableListOf<Song>()
    fun playUrl(context: Any, url: String, song: Song, sourcePlatform: String) { media += song; state = state.copy(generation = state.generation + 1) }
}
object PlaySourceTracker { fun force(name: String, queue: List<Song>) {} }
class Bundle(val favs: List<Song>) { fun isLiked(song: Song) = song in favs }
class Runtime { fun init() {} }
class Prefs {
    val values = mutableMapOf<String, String>()
    var value: String
        get() = values["heart_seen_v1"].orEmpty()
        set(raw) { values.clear(); values["heart_seen_v1"] = raw }
    fun getString(key: String, default: String?) = values[key] ?: default
    fun edit() = this
    fun putString(key: String, raw: String): Prefs { values[key] = raw; return this }
    fun apply() {}
}
object ContextPreferences { val prefs = Prefs(); fun open(context: Any, namespace: String, mode: Int, owner: Any) = prefs }
object ApiClient {
    fun playableUrl(raw: String) = raw
    suspend fun albumDetail(id: String, source: String) = FixtureCatalog.fetch(source, id, "album")
    suspend fun playlistDetail(id: String, source: String): List<Song> = FixtureCatalog.fetch(source, id, "playlist")?.songs.orEmpty()
}
object SmartSourceStore { fun platformPlayQuality(source: String, quality: String) = quality }
object PlaylistImportChecker {
    fun shouldForceCross(key: String) = false
    fun notePlayOutcome(songKey: String, originSource: String, servedPlatform: String, wasForceCross: Boolean) {}
}
object DislikeStore {
    var blocked = emptySet<String>()
    fun filter(context: Any, songs: List<Song>) = songs.filter { it.key() !in blocked }
}
class ResolveTicket(val songKey: String, val queueRevision: Long, val serviceGeneration: Int, val purpose: Purpose, val ownerContext: Any) { enum class Purpose { PLAY } }
object UrlResolver {
    data class Result(val url: String = "url", val isSuccess: Boolean = true, val quality: String = "128k", val platform: String = "wy")
    var action: suspend () -> Result = { Result() }
    suspend fun resolveResult(ticket: ResolveTicket, song: Song, scripts: List<String>, runtime: Runtime, preferQuality: String, forceCross: Boolean, requestStillCurrent: () -> Boolean) = action()
}
class HeartController(private val scope: CoroutineScope) {
    val context = Any()
    val scriptRuntime = Runtime()
    var currentSong: Song? = null
    var activeHeartJob: Job? = null
    var queueRevision = 0L
    val controllerOwner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
    val playbackTransactionGate = PlaybackTransactionGate()
    var heartExclude = emptySet<String>()
    var heartCandidates = emptyList<Song>()
    private var activeHeartPlaylistContext: HeartPlaylistContext? = null
    private var heartSessionRevision = 0L
    class State<T>(var value: T)
    val activeHeartPlaylistKeyState = State<String?>(null)
    var favorites = emptyList<Song>()
    fun getUserBundle() = Bundle(favorites)
    fun getTasteProfile(): xyz.cncnlp.soundwave.recommend.TasteProfileV2? = null
    var resolving = false
    var liked = false
    var currentTime = 0f
    var duration = 0f
    var progress = 0f
    var lyrics = emptyList<String>()
    var lyricIdx = -1
    var status = ""
    var queue = emptyList<Song>()
    var playMode = PlayMode.SEQUENCE
    val played = mutableListOf<Song>()
    var search: suspend (String, String) -> List<Song> = { _, _ -> emptyList() }
    suspend fun searchHeartSimilarServer(keyword: String, source: String) = search(keyword, source)
    suspend fun refreshLyrics(song: Song) {}
    fun getScripts() = emptyList<String>()
    fun getPreferQ() = "128k"
    suspend fun replaceQueue(songs: List<Song>, index: Int) { queue = songs; PlayerService.setQueue(songs, index) }
    suspend fun resolveAndPlay(song: Song, songs: List<Song>, index: Int) { played += song; currentSong = song }
// METHODS_INSERTED_HERE
}
