package xyz.cncnlp.soundwave.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.DefaultLandscapeCover
import xyz.cncnlp.soundwave.coverRequestStateKey
import xyz.cncnlp.soundwave.featuredCoverMemoryCacheKey
import xyz.cncnlp.soundwave.normalizeCoverUrl

/** Keeps featured cover request/cache state local to one rendered card. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FoldedCoverCard(
    cover: String,
    size: Dp,
    primary: Color,
    source: String,
    itemId: String,
    onClick: () -> Unit,
    /** 长按（未进入歌单）出现右下角播放按钮的入口回调；null 表示不启用长按。 */
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val targetSizePx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val normalizedCover = remember(cover, targetSizePx) { normalizeCoverUrl(cover, targetSizePx) }
    val requestStateKey = remember(source, itemId, normalizedCover, targetSizePx) {
        coverRequestStateKey(source, itemId, normalizedCover, targetSizePx)
    }
    var coverLoadFailed by remember(requestStateKey) { mutableStateOf(false) }
    // J 波：首次失败先自动重试一次再锁存（原实现瞬时失败即永久占位；管线无负缓存，重试是真重下）。
    var coverRetry by remember(requestStateKey) { androidx.compose.runtime.mutableIntStateOf(0) }
    val cacheKey = remember(normalizedCover, targetSizePx) {
        featuredCoverMemoryCacheKey(normalizedCover, targetSizePx)
    }
    val coverModel = remember(context, normalizedCover, targetSizePx) {
        if (normalizedCover.isBlank()) null else CacheManager.coverModel(context, normalizedCover, targetSizePx) ?: normalizedCover
    }
    val coverRequest = remember(context, coverModel, cacheKey, normalizedCover, targetSizePx, coverRetry) {
        if (coverModel == null) null else coil.request.ImageRequest.Builder(context)
            .data(coverModel)
            .size(targetSizePx, targetSizePx)
            .precision(coil.size.Precision.EXACT)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
    // One painter/request feeds all three visual layers; preserve the folded-card look without three concurrent decodes.
    val coverPainter = rememberAsyncImagePainter(
        model = coverRequest,
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> coverLoadFailed = false
                is AsyncImagePainter.State.Error -> if (coverRetry == 0) coverRetry = 1 else coverLoadFailed = true
                else -> Unit
            }
        }
    )
    val hasArtwork = normalizedCover.isNotBlank() && !coverLoadFailed && coverPainter.state is AsyncImagePainter.State.Success
    // 设计稿 v2 .cover-wrap：单张封面铺满（圆角/投影由外层卡片负责），不再叠加错位虚影。
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Box(Modifier.size(size).combinedClickable(onClick = onClick, onLongClick = { if (onLongClick != null) { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongClick.invoke() } })) {
        // r658b 第 4 项（用户 10-19「甄选歌单封面加载突兀」）：网络封面到达时 220ms 淡入盖过默认封面，不再硬切；
        // 首帧已命中内存缓存的封面直接不透明显示（无动画）。
        val reveal = androidx.compose.runtime.remember(requestStateKey) { androidx.compose.animation.core.Animatable(if (hasArtwork) 1f else 0f) }
        androidx.compose.runtime.LaunchedEffect(hasArtwork, requestStateKey) {
            if (hasArtwork) reveal.animateTo(1f, androidx.compose.animation.core.tween(220)) else reveal.snapTo(0f)
        }
        Box(Modifier.fillMaxSize().background(primary.copy(alpha = 0.06f))) {
            if (!hasArtwork || reveal.value < 1f) DefaultLandscapeCover(Modifier.fillMaxSize())
            if (hasArtwork) Image(
                coverPainter, null, Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
    }
}
