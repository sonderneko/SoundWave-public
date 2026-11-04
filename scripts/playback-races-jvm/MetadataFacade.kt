package xyz.cncnlp.soundwave.playback

object PlaybackMetadataWork {
    suspend fun <T> runIfCurrent(isCurrent: () -> Boolean, work: () -> T): T? =
        if (isCurrent()) work().takeIf { isCurrent() } else null
}
