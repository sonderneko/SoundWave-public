package xyz.cncnlp.soundwave.ui.player

/** Convert a lyric timestamp into bounded player progress without touching playback or UI state. */
internal fun playerLyricSeekProgress(lineTimeMs: Long, durationSeconds: Float, lyricOffsetMs: Long): Float? {
    if (lineTimeMs < 0L || !durationSeconds.isFinite() || durationSeconds <= 0f) return null
    val durationMs = (durationSeconds * 1000f).toLong()
    if (durationMs <= 0L || lineTimeMs > durationMs) return null

    val targetMs = (lineTimeMs - lyricOffsetMs).coerceIn(0L, durationMs)
    return (targetMs.toFloat() / 1000f / durationSeconds).coerceIn(0f, 1f)
}
