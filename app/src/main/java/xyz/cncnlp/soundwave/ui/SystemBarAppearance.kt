package xyz.cncnlp.soundwave.ui

import android.os.Build
import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat

/** Preserve the classic transparent system-bar contrast and icon behavior. */
internal fun applySystemBarAppearance(
    window: Window,
    controller: WindowInsetsControllerCompat,
    darkTheme: Boolean,
    playerVisible: Boolean
) {
    controller.isAppearanceLightNavigationBars = !darkTheme && !playerVisible
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = true
    }
}
