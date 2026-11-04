package xyz.cncnlp.soundwave.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.recommend.StylePreferenceStore
import xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher
import xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.Outcome
import xyz.cncnlp.soundwave.ui.notice.AppNotice

internal const val TODAY_REFRESH_USED_TOAST = "今日已换过一批，明天再来"

/**
 * r655 今日推荐「为你选了 N 首」行：排序按钮左侧依次为「风格推荐」「换一批」两个纯图标圆钮。
 * r658c U #2：四钮（风格 / 换一批 / 排序 / 多选）同为 DetailToolMetrics 规格、等间距。
 * 外观与排序 / 多选按钮一致：30dp 白色圆 + 1dp 描边（= 首页平台切换按钮描边 colors.outline）+ 柔和投影 + 1.5dp 线性图标。
 */
@Composable
internal fun TodayHeaderExtraActions(platform: String, currentSongs: List<Song>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshAvailable by remember { mutableStateOf(StylePreferenceStore.refreshAvailable(context)) }
    var busy by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    TodayExtraTool(TodayExtraGlyph.Style, "风格推荐", onClick = { showStyle = true })
    Spacer(Modifier.width(xyz.cncnlp.soundwave.DetailToolMetrics.Gap))
    TodayExtraTool(
        TodayExtraGlyph.Refresh, "换一批", enabled = refreshAvailable && !busy,
        state = if (refreshAvailable) "今日可换一次" else "今日已用"
    ) {
        if (busy) return@TodayExtraTool
        if (!StylePreferenceStore.refreshAvailable(context)) {
            refreshAvailable = false
            AppNotice.toast(TODAY_REFRESH_USED_TOAST, AppNotice.Kind.NEUTRAL)
            return@TodayExtraTool
        }
        busy = true
        scope.launch {
            val outcome = try {
                TodayRecommendationRefresher.refreshBatch(context, platform, currentSongs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Outcome.Empty
            } finally {
                busy = false
            }
            refreshAvailable = StylePreferenceStore.refreshAvailable(context)
            val message = when (outcome) {
                is Outcome.Done -> "已为你换了一批（${outcome.songs.size} 首）"
                Outcome.QuotaUsed -> TODAY_REFRESH_USED_TOAST
                Outcome.Empty -> "暂时没有更多推荐，稍后再试"
            }
            AppNotice.toast(message, AppNotice.Kind.NEUTRAL)
        }
    }
    Spacer(Modifier.width(xyz.cncnlp.soundwave.DetailToolMetrics.Gap))
    if (showStyle) StylePickerSheet(platform = platform, onDismiss = { showStyle = false })
}

internal enum class TodayExtraGlyph { Style, Refresh }

@Composable
internal fun TodayExtraTool(
    glyph: TodayExtraGlyph,
    description: String,
    enabled: Boolean = true,
    state: String = "",
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // 禁用态仍可点击（弹出「今日已换过」提示），仅图标与描边减淡。
    val ink = colors.onSurface.copy(alpha = if (enabled) 1f else 0.32f)
    Box(
        Modifier.size(xyz.cncnlp.soundwave.DetailToolMetrics.Size)
            .shadow(6.dp, CircleShape, ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.outline, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description; role = Role.Button
                if (state.isNotEmpty()) stateDescription = state
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(xyz.cncnlp.soundwave.DetailToolMetrics.Icon)) {
            when (glyph) {
                TodayExtraGlyph.Style -> drawStyleGlyph(ink)
                TodayExtraGlyph.Refresh -> drawRefreshGlyph(ink)
            }
        }
    }
}

/** 风格：标签（价签）轮廓 + 穿孔，线性 1.5dp 圆端点。 */
private fun DrawScope.drawStyleGlyph(ink: Color) {
    val w = size.width; val h = size.height
    val sw = 1.5.dp.toPx()
    val tag = Path().apply {
        moveTo(w * 0.08f, h * 0.14f)
        lineTo(w * 0.08f, h * 0.48f)
        lineTo(w * 0.52f, h * 0.92f)
        lineTo(w * 0.92f, h * 0.52f)
        lineTo(w * 0.48f, h * 0.08f)
        lineTo(w * 0.14f, h * 0.08f)
        close()
    }
    drawPath(tag, ink, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(ink, radius = w * 0.075f, center = Offset(w * 0.30f, h * 0.30f), style = Stroke(sw))
}

/** 换一批：约 300° 圆弧 + 顺时针箭头。 */
private fun DrawScope.drawRefreshGlyph(ink: Color) {
    val w = size.width; val h = size.height
    val sw = 1.5.dp.toPx()
    val inset = sw / 2f + w * 0.08f
    drawArc(
        ink, startAngle = -60f, sweepAngle = 290f, useCenter = false,
        topLeft = Offset(inset, inset), size = Size(w - inset * 2, h - inset * 2),
        style = Stroke(sw, cap = StrokeCap.Round)
    )
    // 箭头落在圆弧末端（230°），沿顺时针切线方向张开两翼。
    val r = (w - inset * 2) / 2f
    val end = Math.toRadians(230.0)
    val tip = Offset(w / 2f + r * kotlin.math.cos(end).toFloat(), h / 2f + r * kotlin.math.sin(end).toFloat())
    val dir = Math.atan2(kotlin.math.cos(end), -kotlin.math.sin(end))
    val arm = w * 0.30f
    fun wing(delta: Double) = Offset(
        tip.x - arm * kotlin.math.cos(dir + delta).toFloat(),
        tip.y - arm * kotlin.math.sin(dir + delta).toFloat()
    )
    val head = Path().apply {
        val a = wing(Math.toRadians(40.0)); val b = wing(Math.toRadians(-40.0))
        moveTo(a.x, a.y); lineTo(tip.x, tip.y); lineTo(b.x, b.y)
    }
    drawPath(head, ink, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
