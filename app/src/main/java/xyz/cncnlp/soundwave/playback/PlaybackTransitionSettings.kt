package xyz.cncnlp.soundwave.playback

import android.content.SharedPreferences

/** User-facing fixed crossfade preference. Playback execution remains owned by PlayerService. */
object PlaybackTransitionSettingsStore {
    const val KEY_DURATION = "playback_crossfade_seconds"
    val OPTIONS_SECONDS: List<Int> = listOf(0, 3, 5, 8)
    const val DEFAULT_SECONDS = 0

    fun read(prefs: SharedPreferences): Int = try {
        prefs.getInt(KEY_DURATION, DEFAULT_SECONDS).takeIf { it in OPTIONS_SECONDS } ?: DEFAULT_SECONDS
    } catch (_: Exception) {
        DEFAULT_SECONDS
    }

    fun write(prefs: SharedPreferences, seconds: Int) {
        val normalized = seconds.takeIf { it in OPTIONS_SECONDS } ?: DEFAULT_SECONDS
        prefs.edit().putInt(KEY_DURATION, normalized).apply()
    }
}
