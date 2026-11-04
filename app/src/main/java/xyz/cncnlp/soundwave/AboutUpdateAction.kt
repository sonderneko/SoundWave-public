package xyz.cncnlp.soundwave

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.cncnlp.soundwave.ui.common.DesignFonts

internal fun aboutUpdateChecking(status: String): Boolean = status.startsWith("正在检查 GitHub")

/** Visual feedback follows the real request; no timers delay, repeat or simulate an update check. */
@Composable
internal fun AboutUpdateAction(updateStatus: String, onCheckUpdate: () -> Unit) {
    val checking = aboutUpdateChecking(updateStatus)
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.97f else 1f,
        tween(if (reduceMotion) 0 else 150), label = "aboutUpdatePress"
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val rotate = checking && started && LocalDecorativeActive.current && MotionPolicy.allowDecorativeLoops(reduceMotion)
    // Do not create an infinite transition when hidden, backgrounded, idle or motion-reduced.
    val angle = if (rotate) {
        val transition = rememberInfiniteTransition(label = "aboutUpdateChecking")
        val value by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "aboutUpdateRotation")
        value
    } else 0f
    val resultAlpha = remember { Animatable(1f) }
    LaunchedEffect(updateStatus, reduceMotion) {
        if (reduceMotion || checking || updateStatus.isBlank()) resultAlpha.snapTo(1f)
        else {
            resultAlpha.snapTo(0.55f)
            resultAlpha.animateTo(1f, tween(180))
        }
    }
    val accent = SoundwaveDesignPalette.Accent
    Box(
        Modifier.padding(top = 16.dp).fillMaxWidth().height(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(12.dp, CircleShape, ambientColor = accent.copy(alpha = 0.32f), spotColor = accent.copy(alpha = 0.4f))
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A73), accent, Color(0xFFE8452F))))
            .clickable(enabled = !checking, interactionSource = interaction, indication = null, role = Role.Button, onClick = onCheckUpdate)
            .semantics { stateDescription = if (checking) "正在检查更新" else "可检查更新" },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checking) {
                // Decorative only: the button and live status already announce the request state.
                Canvas(Modifier.size(16.dp)) {
                    drawArc(Color.White, angle - 90f, 270f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Text(if (checking) "检查中…" else "检查更新", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
        }
    }
    Text(
        updateStatus.ifEmpty { "当前版本 ${BuildConfig.VERSION_NAME} · 点按检查更新" },
        color = accent, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
        modifier = Modifier.padding(top = 10.dp).alpha(resultAlpha.value).semantics { liveRegion = LiveRegionMode.Polite }
    )
}
