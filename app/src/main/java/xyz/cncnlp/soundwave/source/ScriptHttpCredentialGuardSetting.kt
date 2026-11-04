package xyz.cncnlp.soundwave.source

import android.content.SharedPreferences

internal object ScriptHttpCredentialGuardSetting {
    const val PREFERENCE_KEY = "script_http_credential_guard"

    fun isEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(PREFERENCE_KEY, false)

    fun setEnabled(preferences: SharedPreferences, enabled: Boolean) {
        preferences.edit().putBoolean(PREFERENCE_KEY, enabled).apply()
    }
}
