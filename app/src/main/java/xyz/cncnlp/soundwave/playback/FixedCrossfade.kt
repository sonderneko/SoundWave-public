package xyz.cncnlp.soundwave.playback

/** Fixed linear mixing only. No beat, silence, loudness or content analysis. */
internal object FixedCrossfadePolicy {
    fun shouldStart(
        durationMs: Long, positionMs: Long, nextDurationMs: Long, fadeMs: Long,
        speed: Float, playing: Boolean, prepared: Boolean, sequential: Boolean,
        blocked: Boolean
    ): Boolean {
        if (fadeMs <= 0 || !speed.isFinite() || speed <= 0f || !playing || !prepared || !sequential || blocked) return false
        val mediaWindow = (fadeMs * speed).toLong()
        return durationMs > mediaWindow * 2 && nextDurationMs > mediaWindow * 2 &&
            positionMs >= mediaWindow && durationMs - positionMs in 1..mediaWindow
    }

    fun duration(configuredMs: Long, remainingMs: Long, speed: Float): Long =
        if (configuredMs <= 0 || remainingMs <= 0 || !speed.isFinite() || speed <= 0f) 0L
        else minOf(configuredMs, (remainingMs / speed).toLong())
}

/** Main-thread only. A cancelled runnable cannot touch a released or repurposed player. */
internal class FixedCrossfade<P : Any>(
    private val now: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val isCurrent: (P) -> Boolean,
    private val isPlaying: (P) -> Boolean,
    private val volumeScale: () -> Float,
    private val setVolume: (P, Float) -> Unit,
    private val release: (P) -> Unit
) {
    private class Mix<P>(val outgoing: P, val incoming: P, val durationMs: Long, val requestedAt: Long) {
        var startedAt: Long? = null
        var progress = 0f
        var holdingOutgoing = false
    }
    private var mix: Mix<P>? = null
    private var pending: Runnable? = null
    val active: Boolean get() = mix != null
    val retainingOutgoing: Boolean get() = mix?.holdingOutgoing == true

    fun start(outgoing: P, incoming: P, durationMs: Long) {
        cancel()
        if (durationMs <= 0 || outgoing === incoming || !isCurrent(incoming)) {
            if (outgoing !== incoming) release(outgoing)
            if (isCurrent(incoming)) setVolume(incoming, volumeScale())
            return
        }
        val next = Mix(outgoing, incoming, durationMs, now())
        mix = next
        apply(next)
        enqueue(next)
    }

    /** Pause, stop, seek, error, rapid skip and shutdown all finish the same way. */
    fun cancel() {
        pending?.let(unschedule)
        pending = null
        val previous = mix ?: return
        mix = null
        release(previous.outgoing)
        if (isCurrent(previous.incoming)) setVolume(previous.incoming, volumeScale())
    }

    /** Audio ducking multiplies both gains; it must never reset an in-flight fade. */
    fun refreshVolume() {
        mix?.let(::apply)
    }

    /** A failed/stalled incoming renderer must not immediately kill still-playable tail audio.
     * Keep it only within the original bounded transition budget; normal recovery owns the UI/queue.
     * A new commit, explicit interruption or resumed incoming renderer releases it immediately. */
    fun holdOutgoingForRecovery() {
        val current = mix ?: return
        current.holdingOutgoing = true
        apply(current)
    }

    private fun apply(current: Mix<P>) {
        val scale = volumeScale().coerceIn(0f, 1f)
        setVolume(current.outgoing, if (current.holdingOutgoing) scale else (1f - current.progress) * scale)
        setVolume(current.incoming, if (current.holdingOutgoing) scale else current.progress * scale)
    }

    private fun enqueue(current: Mix<P>) {
        val runnable = Runnable {
            if (mix !== current) return@Runnable
            pending = null
            if (!isCurrent(current.incoming)) { cancel(); return@Runnable }
            val time = now()
            if (current.holdingOutgoing) {
                if (isPlaying(current.incoming) || !isPlaying(current.outgoing) ||
                    time - current.requestedAt >= current.durationMs + 1_500L) {
                    cancel()
                    return@Runnable
                }
                enqueue(current)
                return@Runnable
            }
            if (!isPlaying(current.incoming)) {
                // Keep the outgoing song audible while the new renderer starts or recovers.
                if (current.startedAt != null || time - current.requestedAt >= 1_500L) {
                    holdOutgoingForRecovery()
                }
            } else {
                val start = current.startedAt ?: time.also { current.startedAt = it }
                current.progress = ((time - start).toFloat() / current.durationMs).coerceIn(0f, 1f)
                if (current.progress >= 1f) { cancel(); return@Runnable }
                apply(current)
            }
            enqueue(current)
        }
        pending = runnable
        schedule(runnable, 40L)
    }
}
