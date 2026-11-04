package xyz.cncnlp.soundwave.ui.route

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import xyz.cncnlp.soundwave.AppRoute
import xyz.cncnlp.soundwave.MotionPolicy
import xyz.cncnlp.soundwave.Tab

/** 原型 proto.js switchTab：旧页 90ms 线性淡出，新页 210ms 淡入并从 0.96 放大（emphasized-decelerate）。 */
internal object TabFadeThrough {
    const val OUT_MS = 90
    const val IN_MS = 210
    const val IN_SCALE = 0.96f
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 「简洁动效」：只减弱不取消——120ms 交叉淡入淡出，无缩放、无延迟。 */
    const val REDUCED_MS = 120

    fun transform(reduceMotion: Boolean): ContentTransform =
        if (reduceMotion) fadeIn(tween(REDUCED_MS, easing = LinearEasing)) togetherWith fadeOut(tween(REDUCED_MS, easing = LinearEasing))
        else (fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = Decelerate)) +
            scaleIn(tween(IN_MS, delayMillis = OUT_MS, easing = Decelerate), initialScale = IN_SCALE)) togetherWith
            fadeOut(tween(OUT_MS, easing = LinearEasing))
}

/**
 * Stateless renderer for the main-tab content hosted below the existing overlays.
 * r657 修复：Tab 切换改为 fade-through（[TabFadeThrough]）；「简洁动效」时硬切。首页由常驻的 Home 层承载，这里为空页。
 */
@Composable
internal fun AppRootMainTabRouteRenderer(
    route: AppRoute.MainTab,
    searchRoute: @Composable () -> Unit,
    playlistsRoute: @Composable () -> Unit,
    settingsRoute: @Composable () -> Unit
) {
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val target = if (route.tab == Tab.PLAYER) Tab.HOME else route.tab
    AnimatedContent(
        targetState = target,
        transitionSpec = { TabFadeThrough.transform(reduceMotion) },
        label = "mainTabFadeThrough"
    ) { tab ->
        // r657 第三轮 b（卡顿）：不透明前景页入场完成后通知常驻首页跳过绘制 / 停止装饰循环。
        if (tab == target) {
            val settled = transition.currentState == androidx.compose.animation.EnterExitState.Visible &&
                transition.targetState == androidx.compose.animation.EnterExitState.Visible
            androidx.compose.runtime.SideEffect {
                xyz.cncnlp.soundwave.ui.motion.PageOcclusion.setTabCovers(settled && tab != Tab.HOME && tab != Tab.PLAYER)
            }
        }
        // HOME is hosted below this route; only foreground pages own an opaque surface.
        if (tab != Tab.HOME && tab != Tab.PLAYER) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 1f))) {
                FrostedPageBackdrop(Modifier.matchParentSize(), dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
                when (tab) {
                    Tab.SEARCH -> searchRoute()
                    Tab.PLAYLISTS -> playlistsRoute()
                    Tab.SETTINGS -> settingsRoute()
                    Tab.HOME -> Unit
                    Tab.PLAYER -> Unit
                }
            }
        }
    }
}
