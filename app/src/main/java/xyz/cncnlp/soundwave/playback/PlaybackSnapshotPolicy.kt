package xyz.cncnlp.soundwave.playback

/** Which service-song change, if any, may be committed to controller state. */
internal enum class SnapshotSongAction {
    NO_CHANGE,
    HYDRATE_EMPTY,
    ACCEPT_SERVICE_CHANGE,
    IGNORE_SERVICE_CHANGE
}

/** Pure decision boundary shared by PlaybackController and JVM regression tests. */
internal data class PlaybackSnapshotDecision(
    val songAction: SnapshotSongAction,
    val preserveControllerQueue: Boolean
)

internal object PlaybackSnapshotPolicy {
    fun decide(
        currentSongKey: String?,
        snapshotSongKey: String?,
        resolving: Boolean,
        acceptedSnapshotGeneration: Int,
        snapshotGeneration: Int,
        controllerQueueIsNotEmpty: Boolean
    ): PlaybackSnapshotDecision {
        val songAction = when {
            snapshotSongKey == null || snapshotSongKey == currentSongKey ->
                SnapshotSongAction.NO_CHANGE
            currentSongKey == null && !resolving ->
                SnapshotSongAction.HYDRATE_EMPTY
            !resolving && snapshotGeneration >= acceptedSnapshotGeneration ->
                SnapshotSongAction.ACCEPT_SERVICE_CHANGE
            else -> SnapshotSongAction.IGNORE_SERVICE_CHANGE
        }
        return PlaybackSnapshotDecision(
            songAction = songAction,
            preserveControllerQueue = resolving && controllerQueueIsNotEmpty
        )
    }
}
