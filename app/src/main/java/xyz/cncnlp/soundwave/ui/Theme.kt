package xyz.cncnlp.soundwave.ui

import android.content.SharedPreferences
import xyz.cncnlp.soundwave.normalizeThemeMode

// 启动主题只从偏好加载仍支持的模式；旧视觉自定义字段保留但不再读取或覆盖Material色板。
internal data class ThemeBootstrapResult(
    val themeMode: String
)

internal fun bootstrapTheme(prefs: SharedPreferences): ThemeBootstrapResult {
    val storedMode = prefs.getString("theme_mode", "light") ?: "light"
    val themeMode = normalizeThemeMode(storedMode)
    if (storedMode != themeMode) {
        prefs.edit().putString("theme_mode", themeMode).apply()
    }
    return ThemeBootstrapResult(themeMode = themeMode)
}
