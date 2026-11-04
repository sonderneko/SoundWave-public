package xyz.cncnlp.soundwave.ui.home

import xyz.cncnlp.soundwave.ui.common.particleBurst
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FeedEmptySlot(loading: Boolean, error: String, emptyText: String, onRetry: () -> Unit) {
    when {
        // 加载中用轻量 skeleton 行替代纯文本「加载中…」：结构可见，避免白屏/闪烁（baseline-ui 骨架建议）。
        loading -> FeedSkeleton()
        // SW-12-13：空 / 错统一走 StateBlock；网络错误文案统一为「网络不可用」+「重试」，不再透出原始错误串。
        error.isNotEmpty() -> xyz.cncnlp.soundwave.ui.common.StateBlock(
            xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Cloud, "网络不可用",
            actionLabel = "重试", onAction = onRetry
        )
        else -> xyz.cncnlp.soundwave.ui.common.StateBlock(
            xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Disc, emptyText,
            actionLabel = "刷新", onAction = onRetry
        )
    }
}

/**
 * 首页加载骨架（验收 B 波重做）：几何与旧版一致（容器竖距 6、行竖距 8、封面 44dp r10、
 * 标题条 150×13、副标题条 96×11），视觉改走通用 SkeletonKit 体系 —— surfaceVariant
 * 实体底色（Paper 底上清晰可见，不再用 onBackground 8%/6% 的近隐形灰）+ 封面块同封面
 * 占位渐变 + 微光扫过；「减少动效」/ 测试环境静止无扫光（MotionPolicy 冻结装饰循环）。
 */
@Composable
internal fun FeedSkeleton() {
    xyz.cncnlp.soundwave.ui.common.SkeletonGroup(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column {
            repeat(4) {
                xyz.cncnlp.soundwave.ui.common.SkeletonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
    }
}

/** r657：下拉刷新指示器改为「蛛丝上的小蜘蛛」（见 SpiderRefreshIndicator）；签名与阈值不变，四处调用点无需改动。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun HomePullRefreshIndicator(
    state: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    isRefreshing: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    // ACCEPT5-press（稿 §4）：下拉越过刷新阈值（distanceFraction 穿越 1.0）只发一次 threshold
    // 触感；四路下拉刷新共用本指示器，一处覆盖。回落不发、刷新中不发。
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    var thresholdArmed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.distanceFraction, isRefreshing) {
        val over = state.distanceFraction >= 1.0f
        if (over && !thresholdArmed && !isRefreshing) {
            xyz.cncnlp.soundwave.ui.common.AppHaptics.thresholdCrossed(pressHapticView, true)
        }
        thresholdArmed = over
    }
    // r658d（用户 10-20）：蜘蛛换成旋转小黑胶。
    xyz.cncnlp.soundwave.ui.common.VinylRefreshIndicator(
        pull = state.distanceFraction, refreshing = isRefreshing,
        ink = MaterialTheme.colorScheme.onSurface, accent = color, modifier = modifier,
        muted = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
