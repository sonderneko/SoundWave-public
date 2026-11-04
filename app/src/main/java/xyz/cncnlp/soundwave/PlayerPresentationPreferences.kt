package xyz.cncnlp.soundwave

import android.content.SharedPreferences

/** Independent presentation preferences. Never reads/writes the system media volume. */
internal class PlayerPresentationPreferences(private val prefs: SharedPreferences) {
    private fun mode(raw: String) = normalizePlayerLayout(raw)
    fun volumeKey(raw: String) = "player_volume_visible_v1_${mode(raw)}"
    fun coverKey(raw: String) = "player_cover_immersive_v1_${mode(raw)}"
    fun volumeVisible(raw: String): Boolean = prefs.getBoolean(volumeKey(raw), !playerLayoutIsDefault(raw))
    fun setVolumeVisible(raw: String, value: Boolean) { prefs.edit().putBoolean(volumeKey(raw), value).apply() }
    fun immersiveCover(raw: String): Boolean = prefs.getBoolean(coverKey(raw), playerUsesCoverOnlyImmersion(raw))
    fun setImmersiveCover(raw: String, value: Boolean) { prefs.edit().putBoolean(coverKey(raw), value).apply() }
    fun effectiveLayout(raw: String, immersive: Boolean): String = when {
        mode(raw) == "apple" && immersive -> "immersive_cover"
        playerLayoutIsDefault(raw) -> if (immersive) PLAYER_LAYOUT_COVER_ONLY else PLAYER_LAYOUT_DEFAULT
        else -> mode(raw)
    }
}
