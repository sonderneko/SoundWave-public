package xyz.cncnlp.soundwave.ui.home

import kotlinx.coroutines.CancellationException
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song

/** Home and plaza play affordances use the existing playlist loader and playback callback.
 * UI-scope confined: coalesce repeat taps while loading; a newer selection wins over stale loads.
 * It never navigates to details or resolves stream URLs itself. */
internal class HomePlaylistPlayController {
    private val pending = mutableSetOf<String>()
    private var selection = 0L

    suspend fun play(
        playlist: Playlist,
        platform: String,
        load: suspend (source: String, remoteId: String) -> List<Song>,
        isCurrent: () -> Boolean,
        onPlay: (Song, List<Song>) -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val source = playlist.source.ifBlank { platform }
        val remoteId = playlist.remoteId.ifBlank { playlist.id.removePrefix("reco_") }
        val key = "$source:$remoteId"
        if (!pending.add(key)) return
        val request = ++selection
        try {
            val songs = if (playlist.songs.isNotEmpty()) playlist.songs.toList()
                else if (remoteId.isBlank() || playlist.local) emptyList() else load(source, remoteId)
            if (request != selection || !isCurrent()) return
            if (songs.isEmpty()) onUnavailable() else onPlay(songs.first(), songs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (request == selection && isCurrent()) onUnavailable()
        } finally {
            pending.remove(key)
        }
    }
}
