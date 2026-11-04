package xyz.cncnlp.soundwave.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata JSON and storage locks must not run on the player's/UI application looper.
 * The second identity check prevents a delayed read from publishing into a newer song/session.
 * The storage operation retains its own owner gate; already committed history is not rolled back.
 */
internal object PlaybackMetadataWork {
    suspend fun <T> runIfCurrent(isCurrent: () -> Boolean, work: () -> T): T? =
        withContext(Dispatchers.IO) {
            if (!isCurrent()) null else work().takeIf { isCurrent() }
        }
}
