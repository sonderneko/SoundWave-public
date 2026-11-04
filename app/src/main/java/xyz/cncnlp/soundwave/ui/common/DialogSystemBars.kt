package xyz.cncnlp.soundwave.ui.common

import android.graphics.Color
import android.os.Build
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * r658b（用户 10-19 #12）：弹窗/抽屉打开时状态栏区域与遮罩不一致（状态栏一条亮带）。
 * 原因：Compose Dialog 窗口默认不延伸到状态栏，或其 decor 仍按对话框主题绘制状态栏底色，
 * 我们自绘的遮罩只盖到状态栏下沿。在每个全屏遮罩型 Dialog 内容顶部调用本函数，
 * 让对话框窗口真正铺满（含刘海/状态栏），系统栏透明且不加对比度蒙层，遮罩因而连续覆盖状态栏。
 */
@Composable
internal fun DialogEdgeToEdge(lightStatusIcons: Boolean = false) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        applyDialogEdgeToEdge(window, lightStatusIcons)
    }
}

internal fun applyDialogEdgeToEdge(window: Window, lightStatusIcons: Boolean = false) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    @Suppress("DEPRECATION")
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    @Suppress("DEPRECATION")
    window.statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    // r658d（用户 10-20 真机：账号中心等弹窗的遮罩没盖住状态栏 / 底部导航，面板下方露出底栏）：
    // Android 11+ 的 Dialog 窗口默认 fitInsetsTypes = 系统栏，窗口本身被系统缩到两条系统栏之间（HyperOS 尤其明显）；
    // 显式清零，让窗口真正铺满整屏，内容自己用 navigationBarsPadding 让位。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) try {
        val attrs = window.attributes
        if (attrs.fitInsetsTypes != 0 || attrs.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            attrs.fitInsetsTypes = 0
            attrs.fitInsetsSides = 0
            attrs.width = ViewGroup.LayoutParams.MATCH_PARENT
            attrs.height = ViewGroup.LayoutParams.MATCH_PARENT
            window.attributes = attrs
        }
    } catch (_: Throwable) {}
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val attrs = window.attributes
        if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
    }
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = lightStatusIcons
}
