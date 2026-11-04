package xyz.cncnlp.soundwave.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.first
import xyz.cncnlp.soundwave.MotionPolicy

private class CoverOverlayHost {
    var root: LayoutCoordinates? by mutableStateOf(null)
    var flight: CoverOverlayFlight? by mutableStateOf(null)
    var activeKey: String? by mutableStateOf(null)
    var reduce by mutableStateOf(false)
    /** r657 第三轮 b（第 24 项）：详情封面槽最近一次绘制所在的 key / 坐标；返回时据此反向缩回来源。 */
    var lastTargetKey: String? = null
    var lastTarget: (() -> LayoutCoordinates?)? = null
    var lastTargetRect: Rect? = null
    var reverse: CoverReverseFlight? by mutableStateOf(null)
    var sharedLayer: GraphicsLayer? = null
}

/** 返回时的反向飞行：详情封面槽 → 来源封面（来源仍按其实时窗口坐标定位，跟随底页复位）。 */
private class CoverReverseFlight(val key: String, val from: () -> Rect?, val to: () -> Rect?) {
    val progress = Animatable(0f)
}

private class CoverOverlayFlight(
    val lease: SharedCoverFlightLease,
    val layer: GraphicsLayer,
    val target: () -> LayoutCoordinates?
) {
    val progress = Animatable(0f)
    var recorded by mutableStateOf(false)
    fun draws(host: CoverOverlayHost): Boolean = lease.draws(host.activeKey, host.reduce,
        MotionPolicy.decorativeLoopsFrozen, host.root?.isAttached == true && target()?.isAttached == true,
        progress.value)
}

private val LocalCoverOverlayHost = compositionLocalOf<CoverOverlayHost?> { null }

/**
 * Root sibling of the page push/alpha and LazyColumn, below full-player/dialog layers.
 * Replays the actual target GraphicsLayer (including its shape/shadow/placeholder), not
 * a second AsyncImage. The stationary target and root use the exact same draw decision.
 */
@Composable
fun SharedCoverOverlayRoot(activeKey: String?, content: @Composable BoxScope.() -> Unit) {
    val host = remember { CoverOverlayHost() }
    val previousKey = host.activeKey
    host.activeKey = activeKey
    host.reduce = MotionPolicy.rememberReduceMotion()
    host.sharedLayer = rememberGraphicsLayer()
    // 第 24 项：关闭详情回到顶层（activeKey → null）时，封面从详情槽反向缩回来源卡片；
    // 详情→子详情、减少动效、截图冻结、来源不可见（已滚出 / 被裁剪）时不飞。
    if (SharedCoverTransition.FLIGHT_ENABLED && previousKey != null && activeKey == null && previousKey != host.reverse?.key) {
        val key = previousKey
        val targetRect = host.lastTargetRect
        val sourceVisible = SharedCoverTransition.visibleSourceRect(key) != null
        if (!host.reduce && !MotionPolicy.decorativeLoopsFrozen && host.lastTargetKey == key && targetRect != null && sourceVisible) {
            host.flight?.lease?.finish()
            val target = host.lastTarget
            host.reverse = CoverReverseFlight(key,
                from = { target?.invoke()?.takeIf { it.isAttached }?.let(::windowRect) ?: host.lastTargetRect },
                to = { SharedCoverTransition.sourceRect(key) })
        }
    }
    host.reverse?.let { rev ->
        LaunchedEffect(rev) {
            try {
                rev.progress.animateTo(1f, tween(DetailPush.EXIT_MS, easing = DetailPush.EmphasizedAccelerate))
            } finally {
                if (host.reverse === rev) host.reverse = null
            }
        }
        if (activeKey != null) {
            // 反向飞行中又打开了详情：立即终止，交给正向飞行。
            SideEffectCancelReverse(host)
        }
    }
    Box {
        CompositionLocalProvider(LocalCoverOverlayHost provides host) { content() }
        Box(Modifier.matchParentSize().onGloballyPositioned { host.root = it }.drawWithContent {
            val flight = host.flight
            // Both reads participate in draw invalidation, even when page coordinates
            // change through a parent graphicsLayer without another layout callback.
            DetailPush.coverage.floatValue
            host.reverse?.let { rev ->
                val q = rev.progress.value
                val from = rev.from() ?: return@drawWithContent
                val to = rev.to() ?: return@drawWithContent
                val layer = host.sharedLayer ?: return@drawWithContent
                if (q >= 1f || layer.size.width <= 0 || layer.size.height <= 0) return@drawWithContent
                drawReplay(host, layer, sharedCoverFlightRect(from, to, q))
                return@drawWithContent
            }
            if (flight == null || !flight.recorded || !flight.draws(host)) return@drawWithContent
            val root = host.root ?: return@drawWithContent
            val target = flight.target() ?: return@drawWithContent
            val topLeft = target.localToWindow(Offset.Zero)
            val bottomRight = target.localToWindow(Offset(target.size.width.toFloat(), target.size.height.toFloat()))
            val rect = sharedCoverFlightRect(flight.lease.request.origin, Rect(topLeft, bottomRight), flight.progress.value)
            val local = root.windowToLocal(Offset(rect.left, rect.top))
            val end = root.windowToLocal(Offset(rect.right, rect.bottom))
            if (flight.layer.size.width <= 0 || flight.layer.size.height <= 0) return@drawWithContent
            withTransform({
                translate(local.x, local.y)
                scale((end.x - local.x) / flight.layer.size.width,
                    (end.y - local.y) / flight.layer.size.height, pivot = Offset.Zero)
            }) { drawLayer(flight.layer) }
        })
    }
}

@Composable
internal fun Modifier.sharedCoverOverlayTarget(key: String, targetCoordinates: () -> LayoutCoordinates?): Modifier {
    val host = LocalCoverOverlayHost.current ?: return this
    val generation = SharedCoverTransition.generation.longValue
    val coordinates by rememberUpdatedState(targetCoordinates)
    val layer = rememberGraphicsLayer()
    val flight = remember(host, key, generation) {
        SharedCoverTransition.takeRequest(key)?.let { CoverOverlayFlight(SharedCoverFlightLease(it), layer) { coordinates() } }
    }
    // 第 24 项：无正向飞行时也记录封面层与槽位坐标，供返回时反向缩回。
    val passive = Modifier.drawWithContent {
        val shared = host.sharedLayer
        if (host.activeKey == key && shared != null) {
            shared.record { this@drawWithContent.drawContent() }
            host.lastTargetKey = key
            host.lastTarget = { coordinates() }
            coordinates()?.takeIf { it.isAttached }?.let { host.lastTargetRect = windowRect(it) }
        }
        if (host.reverse?.key == key) return@drawWithContent
        drawContent()
    }
    if (flight == null) return this.then(passive)
    DisposableEffect(host, flight) {
        host.flight?.lease?.finish()
        host.flight = flight
        onDispose {
            flight.lease.finish()
            if (host.flight === flight) host.flight = null
        }
    }
    LaunchedEffect(flight, host.activeKey, host.reduce) {
        if (host.activeKey != key || host.reduce || MotionPolicy.decorativeLoopsFrozen) {
            flight.lease.finish()
            flight.progress.snapTo(1f)
        } else {
            // Do not burn flight time before the very first target capture/layout.
            snapshotFlow { flight.recorded }.first { it }
            try {
                flight.progress.animateTo(1f, tween(SharedCoverTransition.DURATION_MS, easing = SharedCoverTransition.Emphasized))
            } finally {
                flight.lease.finish()
            }
        }
    }
    return this.then(passive).drawWithContent {
        // Record only artwork inside this modifier, preserving the anchor/layout outside.
        layer.record { this@drawWithContent.drawContent() }
        flight.recorded = true
        if (!flight.draws(host)) drawLayer(layer)
    }
}

private fun windowRect(c: LayoutCoordinates): Rect =
    Rect(c.localToWindow(Offset.Zero), c.localToWindow(Offset(c.size.width.toFloat(), c.size.height.toFloat())))

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReplay(host: CoverOverlayHost, layer: GraphicsLayer, rect: Rect) {
    val root = host.root ?: return
    val local = root.windowToLocal(Offset(rect.left, rect.top))
    val end = root.windowToLocal(Offset(rect.right, rect.bottom))
    withTransform({
        translate(local.x, local.y)
        scale((end.x - local.x) / layer.size.width, (end.y - local.y) / layer.size.height, pivot = Offset.Zero)
    }) { drawLayer(layer) }
}

@Composable
private fun SideEffectCancelReverse(host: CoverOverlayHost) {
    androidx.compose.runtime.SideEffect { host.reverse = null }
}
