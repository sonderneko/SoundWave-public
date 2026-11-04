package xyz.cncnlp.soundwave

import android.content.SharedPreferences

/**
 * 「底栏/迷你条样式」合并偏好（r654）：default = 设计稿通栏底栏 + 圆角卡片迷你条；capsule = 副分支圆角胶囊底栏 + 胶囊迷你条。
 * 首次读取时从旧的 bottom_nav_style / mini_player_style 迁移：任一为胶囊即视为胶囊，并回写新键。
 */
internal object DockStylePrefs {
    const val KEY = "dock_style"

    fun read(prefs: SharedPreferences): String {
        prefs.getString(KEY, null)?.let { return if (it == "capsule") "capsule" else "default" }
        val migrated = if (prefs.getString("bottom_nav_style", "default") == "capsule" || prefs.getString("mini_player_style", "default") == "capsule") "capsule" else "default"
        prefs.edit().putString(KEY, migrated).putString("bottom_nav_style", migrated).putString("mini_player_style", migrated).apply()
        return migrated
    }
}
