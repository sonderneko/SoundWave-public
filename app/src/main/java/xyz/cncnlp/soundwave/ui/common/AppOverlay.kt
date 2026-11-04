package xyz.cncnlp.soundwave.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.cncnlp.soundwave.ui.notice.AppNoticeHost

/** An Activity-composition-owned registry: no process-global entries or installed flag. */
internal class AppOverlayHostState {
    class Entry(val id: Long, val content: @Composable () -> Unit)
    val entries = mutableStateListOf<Entry>()
    private var nextId = 0L
    fun newId(): Long = ++nextId
}

// Composable lambdas retain their own snapshot reads. Relaying a new lambda
// through snapshot state would invalidate the caller again when an entry invokes it.
private class OverlayCallbacks(var content: @Composable () -> Unit, var dismiss: () -> Unit)

private val LocalAppOverlayHost = compositionLocalOf<AppOverlayHostState?> { null }

/** Keep the registry available before any route composes its first sheet. */
@Composable
fun ProvideAppOverlayHost(content: @Composable () -> Unit) {
    val host = remember { AppOverlayHostState() }
    DisposableEffect(host) {
        onDispose { host.entries.clear() }
    }
    CompositionLocalProvider(LocalAppOverlayHost provides host, content = content)
}

/** Sheets in the Activity overlay must not rely on cross-window blur. */
val LocalInAppOverlay = compositionLocalOf { false }

/** Last child of the Activity root Box, with no navigation-bar or IME padding. */
@Composable
fun AppOverlayHostLayer(modifier: Modifier = Modifier) {
    val host = LocalAppOverlayHost.current ?: return
    // AppNoticeHost 必须常驻（提示系统宿主），故不再以 entries.isEmpty() 提前返回：
    // 空列表时 forEach 本就是 no-op，已有浮层的渲染逻辑不变；通知宿主最后渲染、在最上层。
    Box(modifier.fillMaxSize()) {
        host.entries.toList().forEach { entry -> key(entry.id) { entry.content() } }
        AppNoticeHost()
    }
}

/**
 * Contents retain their existing scrim, outside-tap handling and panel/inset layout.
 * Keeping insets at the sheet avoids double IME padding and keeps its scrim full screen.
 * Previews/tests without an Activity host retain the platform Dialog fallback.
 */
@Composable
fun AppOverlayDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalAppOverlayHost.current
    if (host == null) {
        Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            DialogEdgeToEdge()
            content()
        }
        return
    }
    // Restore the caller's design theme/fonts/density, not merely the root theme.
    val callerLocals = currentCompositionLocalContext
    // A context wrapper itself has identity equality, so only replace it when actual
    // caller environment values change. Never relay a fresh wrapper on every recompose.
    val environmentValues = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.inversePrimary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary,
        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary,
        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.onBackground, MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceTint,
        MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface,
        MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError,
        MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
        MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.colorScheme.scrim,
        MaterialTheme.typography, MaterialTheme.shapes,
        xyz.cncnlp.soundwave.LocalThemeVisual.current,
        xyz.cncnlp.soundwave.LocalGlobalFontFamily.current,
        xyz.cncnlp.soundwave.LocalLyricFontFamily.current,
        LocalDensity.current, LocalReduceMotion.current
    )
    val stableLocals = remember(environmentValues) { callerLocals }
    val latestLocals by rememberUpdatedState(stableLocals)
    val callbacks = remember { OverlayCallbacks(content, onDismissRequest) }
    callbacks.content = content
    callbacks.dismiss = onDismissRequest
    val id = remember(host) { host.newId() }
    DisposableEffect(host, id) {
        val entry = AppOverlayHostState.Entry(id) {
            CompositionLocalProvider(latestLocals) {
                CompositionLocalProvider(LocalInAppOverlay provides true) {
                    val keyboard = LocalSoftwareKeyboardController.current
                    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    // Only the visually topmost sheet handles back; first back hides the IME.
                    BackHandler(enabled = host.entries.lastOrNull()?.id == id) {
                        if (imeVisible && keyboard != null) keyboard.hide() else callbacks.dismiss()
                    }
                    Box(Modifier.fillMaxSize()) { callbacks.content() }
                }
            }
        }
        host.entries.add(entry)
        onDispose { host.entries.removeAll { it.id == id } }
    }
}
