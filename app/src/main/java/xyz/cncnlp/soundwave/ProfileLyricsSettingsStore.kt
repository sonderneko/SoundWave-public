package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/** Personal lyric presentation choices stored in an explicitly addressed local profile. */
internal data class ProfileLyricsSettings(
    val normalFontSp: Int = 18,
    val normalLineHeightDp: Int = 9,
    val immersiveFontSp: Int = 30,
    val immersiveLineHeightDp: Int = 14,
    val bold: Boolean = true,
    val distanceBlurDp: Int = 2,
    val showTranslation: Boolean = false,
    val showRomanization: Boolean = false,
    val highlightColor: String = "#FFFFFF",
    val unhighlightColor: String = "#A8A8B3",
    val offsetMs: Int = 0,
    val wordKaraokeEnabled: Boolean = true,
)

/**
 * Profile-scoped access to lyric presentation preferences. This store deliberately has no
 * no-ID/active-profile path and never caches a mutable global SharedPreferences instance.
 */
internal object ProfileLyricsSettingsStore {
    private const val KEY_NORMAL_FONT_SP = "lyric_font_sp"
    private const val KEY_NORMAL_LINE_HEIGHT_DP = "lyric_gap_dp"
    private const val KEY_IMMERSIVE_FONT_SP = "lyric_immersive_sp"
    private const val KEY_IMMERSIVE_LINE_HEIGHT_DP = "lyric_immersive_gap_dp"
    private const val KEY_BOLD = "lyric_bold"
    private const val KEY_DISTANCE_BLUR_DP = "lyric_blur_dp"
    private const val KEY_SHOW_TRANSLATION = "lyric_show_translation"
    private const val KEY_SHOW_ROMANIZATION = "lyric_show_romanization"
    private const val KEY_HIGHLIGHT_COLOR = "lyric_highlight_color"
    private const val KEY_UNHIGHLIGHT_COLOR = "lyric_unhighlight_color"
    private const val KEY_OFFSET_MS = "lyric_offset_ms"
    private const val KEY_WORD_KARAOKE = "lyric_word_karaoke"
    private const val KEY_WORD_LIFT = LYRIC_WORD_LIFT_PREFERENCE

    private val distanceBlurValues = listOf(0, 2, 4, 8)

    /** Resolve the named profile on every call; do not fall back to the legacy global preferences. */
    private fun profilePreferences(context: Context, profileId: ProfileId): SharedPreferences =
        context.applicationContext.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)

    fun load(context: Context, profileId: ProfileId): ProfileLyricsSettings {
        val preferences = profilePreferences(context, profileId)
        val normalLineHeightDp = preferences.getInt(KEY_NORMAL_LINE_HEIGHT_DP, 9)
        return ProfileLyricsSettings(
            normalFontSp = preferences.getInt(KEY_NORMAL_FONT_SP, 18),
            normalLineHeightDp = normalLineHeightDp,
            immersiveFontSp = preferences.getInt(KEY_IMMERSIVE_FONT_SP, 30).coerceIn(14, 56),
            immersiveLineHeightDp = preferences.getInt(
                KEY_IMMERSIVE_LINE_HEIGHT_DP,
                preferences.getInt(KEY_NORMAL_LINE_HEIGHT_DP, 14),
            ).coerceIn(6, 64),
            bold = preferences.getBoolean(KEY_BOLD, true),
            distanceBlurDp = preferences.getInt(KEY_DISTANCE_BLUR_DP, 2)
                .let { raw -> distanceBlurValues.minByOrNull { abs(it - raw) } ?: 2 },
            showTranslation = preferences.getBoolean(KEY_SHOW_TRANSLATION, false),
            showRomanization = preferences.getBoolean(KEY_SHOW_ROMANIZATION, false),
            highlightColor = preferences.getString(KEY_HIGHLIGHT_COLOR, "#FFFFFF") ?: "#FFFFFF",
            unhighlightColor = preferences.getString(KEY_UNHIGHLIGHT_COLOR, "#A8A8B3") ?: "#A8A8B3",
            offsetMs = preferences.getInt(KEY_OFFSET_MS, 0).coerceIn(-30_000, 30_000),
            wordKaraokeEnabled = preferences.getBoolean(KEY_WORD_KARAOKE, true),
        )
    }

    /** Keep lift independent from the settings model while preserving its established profile-local key. */
    fun loadWordLiftEnabled(context: Context, profileId: ProfileId): Boolean {
        val preferences = profilePreferences(context, profileId)
        val explicitLift = if (preferences.contains(KEY_WORD_LIFT)) {
            runCatching { preferences.getBoolean(KEY_WORD_LIFT, true) }.getOrNull()
        } else {
            null
        }
        val enabled = initialLyricWordLiftPreference(
            explicitLift = explicitLift,
            karaokeEnabled = runCatching { preferences.getBoolean(KEY_WORD_KARAOKE, true) }.getOrDefault(true),
        )
        // Match the existing one-time karaoke -> lift migration, but keep it inside this profile.
        if (!preferences.contains(KEY_WORD_LIFT)) {
            preferences.edit().putBoolean(KEY_WORD_LIFT, enabled).apply()
        }
        return enabled
    }

    fun saveWordLiftEnabled(context: Context, profileId: ProfileId, enabled: Boolean) {
        profilePreferences(context, profileId).edit().putBoolean(KEY_WORD_LIFT, enabled).apply()
    }

    /** Write only the lyric presentation whitelist; unrelated profile and legacy keys are untouched. */
    fun save(context: Context, profileId: ProfileId, settings: ProfileLyricsSettings) {
        profilePreferences(context, profileId).edit()
            .putInt(KEY_NORMAL_FONT_SP, settings.normalFontSp)
            .putInt(KEY_NORMAL_LINE_HEIGHT_DP, settings.normalLineHeightDp)
            .putInt(KEY_IMMERSIVE_FONT_SP, settings.immersiveFontSp.coerceIn(14, 56))
            .putInt(KEY_IMMERSIVE_LINE_HEIGHT_DP, settings.immersiveLineHeightDp.coerceIn(6, 64))
            .putBoolean(KEY_BOLD, settings.bold)
            .putInt(KEY_DISTANCE_BLUR_DP, distanceBlurValues.minByOrNull { abs(it - settings.distanceBlurDp) } ?: 2)
            .putBoolean(KEY_SHOW_TRANSLATION, settings.showTranslation)
            .putBoolean(KEY_SHOW_ROMANIZATION, settings.showRomanization)
            .putString(KEY_HIGHLIGHT_COLOR, settings.highlightColor)
            .putString(KEY_UNHIGHLIGHT_COLOR, settings.unhighlightColor)
            .putInt(KEY_OFFSET_MS, settings.offsetMs.coerceIn(-30_000, 30_000))
            .putBoolean(KEY_WORD_KARAOKE, settings.wordKaraokeEnabled)
            .apply()
    }
}
