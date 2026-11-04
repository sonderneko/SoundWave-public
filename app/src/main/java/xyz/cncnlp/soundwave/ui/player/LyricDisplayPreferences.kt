package xyz.cncnlp.soundwave.ui.player

/** Stored lyric-row display choices; missing legacy values intentionally default to hidden. */
internal data class LyricDisplayPreferences(
    val translation: Boolean = false,
    val romanization: Boolean = false,
)

/** Keeps each saved preference independent while defaulting only missing values to off. */
internal fun resolveLyricDisplayPreferences(
    storedTranslation: Boolean?,
    storedRomanization: Boolean?,
): LyricDisplayPreferences = LyricDisplayPreferences(
    translation = storedTranslation ?: false,
    romanization = storedRomanization ?: false,
)
