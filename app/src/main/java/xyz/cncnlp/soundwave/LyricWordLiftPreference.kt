package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal const val LYRIC_WORD_LIFT_PREFERENCE = "lyric_word_lift"

/** Existing karaoke preference remains the migration default, then the two preferences are independent. */
internal fun initialLyricWordLiftPreference(explicitLift: Boolean?, karaokeEnabled: Boolean): Boolean =
    explicitLift ?: karaokeEnabled

@Composable
internal fun rememberLyricWordLiftEnabled(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val owner = remember(context) { xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime() }
    val preferences = remember(context, owner) {
        ContextPreferences.open(context, "soundwave", Context.MODE_PRIVATE, owner)
    }
    val enabled = remember(preferences) {
        val migrated = initialLyricWordLiftPreference(
            explicitLift = if (preferences.contains(LYRIC_WORD_LIFT_PREFERENCE)) {
                runCatching { preferences.getBoolean(LYRIC_WORD_LIFT_PREFERENCE, true) }.getOrNull()
            } else null,
            karaokeEnabled = runCatching { preferences.getBoolean("lyric_word_karaoke", true) }.getOrDefault(true)
        )
        if (!preferences.contains(LYRIC_WORD_LIFT_PREFERENCE)) {
            preferences.edit().putBoolean(LYRIC_WORD_LIFT_PREFERENCE, migrated).apply()
        }
        mutableStateOf(migrated)
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == LYRIC_WORD_LIFT_PREFERENCE || key == "lyric_word_karaoke") {
                enabled.value = runCatching { prefs?.getBoolean(LYRIC_WORD_LIFT_PREFERENCE, false) ?: false }.getOrDefault(false)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

internal fun setLyricWordLiftEnabled(context: Context, enabled: Boolean, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) {
    ContextPreferences.open(context.applicationContext, "soundwave", Context.MODE_PRIVATE, owner)
        .edit().putBoolean(LYRIC_WORD_LIFT_PREFERENCE, enabled).apply()
}
