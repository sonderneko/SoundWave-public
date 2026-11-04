package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ui.common.swMarquee

/**
 * r657 第二轮性能（长列表：我的喜欢 / 本地 / 在线歌单 / 专辑 / 歌手 / 播放历史）。
 *
 *  1. [rememberRowCoverRequest]：行封面按 48dp 实际像素解码（Precision.INEXACT），关闭淡入；
 *     本地导入歌曲的 data: 内嵌封面（首次需要 Base64 解码 + 落盘）放到 IO 线程解析，滑动时主线程不再做解码；
 *  2. [LocalRowListState] + [Modifier.rowMarquee]：列表正在滚动时行内跑马灯停用（每个可见长标题的无限动画
 *     会让整行每帧重绘），停止滚动后恢复（仍有 1.6s 起步延迟）；当前播放行始终保留。
 */
val LocalRowListState = staticCompositionLocalOf<LazyListState?> { null }

@Composable
fun rememberRowCoverRequest(rawCover: String, size: Dp): Any? {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val url = remember(rawCover) { normalizeCoverUrl(rawCover) }
    if (url.isBlank()) return null
    val model: Any? = if (url.startsWith("data:", ignoreCase = true)) {
        val resolved by produceState<Any?>(null, url) { value = withContext(Dispatchers.IO) { CacheManager.coverModel(ctx, url) } }
        resolved
    } else remember(url) { CacheManager.coverModel(ctx, url) ?: url }
    if (model == null) return null
    return remember(model, px) {
        ImageRequest.Builder(ctx).data(model).size(px, px).precision(Precision.INEXACT).crossfade(150).build() // r658b 第 4 项：网络封面淡入（内存缓存命中时 Coil 不做淡入）
    }
}

/** 行内单行文本跑马灯：所在列表滚动中停用（[alwaysOn] = 当前播放行）。 */
@Composable
fun Modifier.rowMarquee(alwaysOn: Boolean = false): Modifier {
    val list = LocalRowListState.current
    val scrolling = list != null && !alwaysOn && list.isScrollInProgress
    return if (scrolling) this else this.swMarquee()
}

/**
 * r657 第二轮性能：详情推入转场期间（≤ 360ms）曲目行先画同尺寸的轻量占位行（无点击 / 图片请求 / 动画 / 抽屉状态），
 * 转场结束后再换成完整行——推入动画首帧不再被十几行完整曲目行的首次组合拖慢（「打开详情卡顿」）。
 */
@Composable
internal fun TrackRowSkeleton(song: Song, showCover: Boolean) {
    val colors = MaterialTheme.colorScheme
    // r658b 第 4 项（用户 10-19「详情列表加载时曲目行跳动」）：占位行几何与完整行 TodayTrackRow 完全一致
    // （72dp 高、左 14 / 右 6、56dp r14 封面 + 同一封面请求、标题 15/20、副标题 12/16 + 音质标识、时长 + 36dp ⋯ 位），
    // 换成完整行时不再有 64→72dp 行高 / 48→56dp 封面的跳变与封面闪现。
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCover) Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant)) {
            val req = rememberRowCoverRequest(song.cover, 56.dp)
            // ACCEPT5 收编：占位常驻垫底——加载/失败不再是 surfaceVariant 平灰。
            DefaultLandscapeCover(Modifier.matchParentSize())
            if (req != null) coil.compose.AsyncImage(req, null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = if (showCover) 14.dp else 0.dp, end = 14.dp)) {
            Text(song.name, color = colors.onBackground, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val qBadge = songQualityBadgeLabel(song)
                if (qBadge.isNotEmpty()) { QualityBadge(qBadge); androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp)) }
                Text(song.singer.ifBlank { "未知歌手" }, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope, maxLines = 1)
            }
        }
        val dur = formatTrackDuration(song.duration)
        Text(dur, modifier = Modifier.width(48.dp), color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1)
        androidx.compose.foundation.layout.Spacer(Modifier.size(36.dp))
    }
}

/** 详情页曲目行是否可以换成完整行：推入转场完成（或 420ms 兜底）；截图 / 单测与简洁动效下立即就绪。 */
@Composable
internal fun rememberDetailRowsReady(key: Any): Boolean {
    var ready by remember(key) { mutableStateOf(MotionPolicy.decorativeLoopsFrozen || xyz.cncnlp.soundwave.ui.motion.DetailPush.coverage.floatValue >= 0.999f) }
    if (!ready) LaunchedEffect(key) {
        kotlinx.coroutines.withTimeoutOrNull(420) {
            androidx.compose.runtime.snapshotFlow { xyz.cncnlp.soundwave.ui.motion.DetailPush.coverage.floatValue }.first { it >= 0.999f }
        }
        ready = true
    }
    return ready
}
