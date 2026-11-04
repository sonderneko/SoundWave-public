package xyz.cncnlp.soundwave.recommend

import kotlinx.coroutines.CancellationException

/** Provider failures may be empty; cancellation must cross every recommendation boundary. */
internal suspend fun <T> recommendationAttempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
