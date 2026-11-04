package xyz.cncnlp.soundwave.ui.motion

import androidx.compose.ui.geometry.Rect

/** Single-use, click-time geometry. No dependency on a later moving/list-clipped source. */
internal data class SharedCoverRequest(val key: String, val serial: Long, val origin: Rect)

internal class SharedCoverArms {
    private var serial = 0L
    private var pending: Pair<SharedCoverRequest, Long>? = null

    fun arm(key: String, origin: Rect?, now: Long): Long {
        serial++
        pending = origin?.takeIf { key.isNotBlank() && it.width > 0f && it.height > 0f }
            ?.let { SharedCoverRequest(key, serial, it) to now }
        return serial
    }

    fun take(key: String, now: Long): SharedCoverRequest? {
        val (request, at) = pending ?: return null
        if (request.key != key) return null
        pending = null
        return request.takeIf { now - at in 0L..1500L }
    }
}

/** Cancellation is terminal, including turning reduced motion on and then off mid-flight. */
internal class SharedCoverFlightLease(val request: SharedCoverRequest) {
    private var finished = false
    fun finish() { finished = true }
    fun draws(activeKey: String?, reduce: Boolean, frozen: Boolean, attached: Boolean, progress: Float): Boolean {
        if (activeKey != request.key || reduce || frozen || progress >= 1f || !attached) finish()
        return !finished
    }
}

/** Window-space interpolation, converted into the root's space only by its renderer. */
internal fun sharedCoverFlightRect(origin: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return Rect(lerp(origin.left, target.left), lerp(origin.top, target.top),
        lerp(origin.right, target.right), lerp(origin.bottom, target.bottom))
}
