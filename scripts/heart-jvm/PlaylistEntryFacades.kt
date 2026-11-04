@file:Suppress("UNUSED_PARAMETER")
package xyz.cncnlp.soundwave

import kotlinx.coroutines.*

object FixtureCatalog {
    var fetch: suspend (String, String, String) -> Playlist? = { _, _, _ -> null }
}
internal object HomeFeedCache {
    object ResourceTtl { const val PLAYLIST_DETAIL_MS = 60000L }
    var cache = DetailCacheSnapshot(emptyList(), 0L)
    suspend fun loadDetailSnapshotOnIo(context: Any, id: String, source: String) = cache
}
object AlbumFavoriteMarks {
    var saved = emptySet<String>()
    fun marks(context: Any) = saved
    fun isKnownAlbum(playlist: Playlist, source: String) = false
}
object ProviderRegistry {
    data class Capability(val playlistDetail: Boolean)
    fun capability(source: String, prefs: Any) = Capability(source in MusicSourceRegistry.platformIds)
}
object MusicSourceRegistry {
    val platformIds = listOf("wy", "kg", "kw", "tx", "mg")
    class Adapter(val source: String) {
        suspend fun albumDetail(id: String) = FixtureCatalog.fetch(source, id, "album")
        suspend fun playlistDetail(id: String): List<Song> = FixtureCatalog.fetch(source, id, "playlist")?.songs.orEmpty()
    }
    fun platformSource(source: String) = Adapter(source)
}
class PlaylistEntry(private val scope: CoroutineScope, val playback: HeartController) {
    val context = Any()
    val prefs = Any()
    val userBundle get() = playback.getUserBundle()
    var history = emptyList<Song>()
    var playlistHeartLoadGeneration = 0
    var playlistHeartLoadJob: Job? = null
// METHODS_INSERTED_HERE
}
