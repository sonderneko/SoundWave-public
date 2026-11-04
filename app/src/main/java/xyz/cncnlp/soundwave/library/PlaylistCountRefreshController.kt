package xyz.cncnlp.soundwave.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.Playlist

/** Stable local target identity; provider is part of the key so equal IDs cannot cross providers. */
internal data class PlaylistCountIdentity(
    val id: String,
    val source: String,
    val remoteId: String,
) {
    companion object {
        fun of(playlist: Playlist) = PlaylistCountIdentity(playlist.id, playlist.source, playlist.remoteId)
    }
}

internal fun applyPlaylistCountUpdates(
    playlist: Playlist,
    counts: Map<PlaylistCountIdentity, Long>,
): Playlist {
    val count = counts[PlaylistCountIdentity.of(playlist)]
    return if (count != null && count != playlist.playCount) playlist.copy(playCount = count) else playlist
}

internal fun applyPlaylistCountUpdates(
    playlists: List<Playlist>,
    counts: Map<PlaylistCountIdentity, Long>,
): List<Playlist> = playlists.map { applyPlaylistCountUpdates(it, counts) }

internal fun applyPlaylistCountUpdates(
    bundle: LocalStore.UserBundle,
    counts: Map<PlaylistCountIdentity, Long>,
): LocalStore.UserBundle =
    bundle.copy(playlists = applyPlaylistCountUpdates(bundle.playlists, counts).toMutableList())

/** The Library route owns this lane: a newer refresh or owner/route invalidation cancels older IO. */
internal class PlaylistCountRefreshController(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val currentContext: () -> String? = { "active" },
    private val searchPlaylists: suspend (name: String, source: String) -> List<Playlist>,
) {
    private var generation = 0L
    private var activeJob: Job? = null

    fun refresh(
        playlists: List<Playlist>,
        onCounts: (Map<PlaylistCountIdentity, Long>) -> Unit,
    ): Job? {
        val request = ++generation
        activeJob?.cancel()
        activeJob = null
        val requestContext = currentContext() ?: return null
        val targets = playlists.asSequence()
            .filter { !it.local && it.id !in SPECIAL_PLAYLIST_IDS && it.source.isNotBlank() }
            .map { Target(PlaylistCountIdentity.of(it), it.name, it.source, it.remoteId, it.playCount) }
            .toList()
        if (targets.isEmpty()) return null

        val job = scope.launch {
            val refreshed = withContext(ioDispatcher) {
                targets.mapNotNull { target ->
                    val hits = try {
                        searchPlaylists(target.name, target.source)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val hit = hits.firstOrNull { candidate ->
                        (target.remoteId.isNotBlank() &&
                            (candidate.remoteId == target.remoteId || candidate.id == target.remoteId)) ||
                            candidate.name == target.name
                    } ?: hits.firstOrNull()
                    if (hit != null && hit.playCount > 0L && hit.playCount != target.playCount) {
                        target.identity to hit.playCount
                    } else {
                        null
                    }
                }.toMap()
            }
            if (request == generation && refreshed.isNotEmpty() && requestContext == currentContext()) {
                onCounts(refreshed)
            }
        }
        activeJob = job
        return job
    }

    fun invalidate() {
        generation += 1L
        activeJob?.cancel()
        activeJob = null
    }

    private data class Target(
        val identity: PlaylistCountIdentity,
        val name: String,
        val source: String,
        val remoteId: String,
        val playCount: Long,
    )

    private companion object {
        val SPECIAL_PLAYLIST_IDS = setOf("liked", "local", "history")
    }
}
