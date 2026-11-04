package xyz.cncnlp.soundwave

/** Stateless coordinate conversion only. Gesture lifetime, touch slop and playback stay at the caller. */
internal object PlayerSliderMath {
    fun tapFraction(positionPx: Float, widthPx: Float): Float =
        (positionPx / widthPx).coerceIn(0f, 1f)

    fun dragFraction(current: Float, deltaPx: Float, widthPx: Float): Float =
        if (widthPx > 0f) (current + deltaPx / widthPx).coerceIn(0f, 1f) else current
}
