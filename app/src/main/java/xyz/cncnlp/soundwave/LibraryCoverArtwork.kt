package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

@Composable
internal fun LibraryCoverArtwork(
    cover: String,
    fallbackKind: LibraryDefaultCoverKind,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    imageModel: Any? = null
) {
    if (libraryCoverHasImage(cover)) {
        val context = LocalContext.current
        val model = imageModel ?: remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
        // ACCEPT5 收编：分类默认封面常驻垫底——加载/失败不再透出卡面底。
        Box(modifier) {
            LibraryDefaultCover(kind = fallbackKind, modifier = Modifier.fillMaxSize())
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        LibraryDefaultCover(kind = fallbackKind, modifier = modifier)
    }
}

@Composable
internal fun LibraryDefaultCover(
    kind: LibraryDefaultCoverKind,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.semantics { contentDescription = kind.contentDescription }) {
        val width = size.width
        val height = size.height
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(kind.gradientStart), Color(kind.gradientEnd)),
                start = Offset.Zero,
                end = Offset(width, height)
            ),
            size = size
        )
        when (kind.motif) {
            LibraryCoverMotif.HEART -> {
                val heart = Path().apply {
                    moveTo(width * 0.50f, height * 0.79f)
                    cubicTo(width * 0.12f, height * 0.57f, width * 0.10f, height * 0.34f, width * 0.25f, height * 0.22f)
                    cubicTo(width * 0.36f, height * 0.13f, width * 0.48f, height * 0.19f, width * 0.50f, height * 0.33f)
                    cubicTo(width * 0.52f, height * 0.19f, width * 0.64f, height * 0.13f, width * 0.75f, height * 0.22f)
                    cubicTo(width * 0.90f, height * 0.34f, width * 0.88f, height * 0.57f, width * 0.50f, height * 0.79f)
                    close()
                }
                drawCircle(Color.White.copy(alpha = 0.14f), width * 0.12f, Offset(width * 0.20f, height * 0.73f))
                drawCircle(Color.White.copy(alpha = 0.20f), width * 0.045f, Offset(width * 0.80f, height * 0.24f))
                drawPath(heart, Color(kind.motifColor))
                drawPath(
                    heart,
                    Color.White.copy(alpha = 0.34f),
                    style = Stroke(width = (width * 0.018f).coerceAtLeast(1f), join = StrokeJoin.Round)
                )
                drawCircle(Color.White.copy(alpha = 0.48f), width * 0.035f, Offset(width * 0.34f, height * 0.30f))
                drawCircle(Color.White.copy(alpha = 0.80f), width * 0.018f, Offset(width * 0.77f, height * 0.66f))
                drawLine(Color.White.copy(alpha = 0.72f), Offset(width * 0.18f, height * 0.30f), Offset(width * 0.23f, height * 0.30f), width * 0.012f, StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.72f), Offset(width * 0.205f, height * 0.275f), Offset(width * 0.205f, height * 0.325f), width * 0.012f, StrokeCap.Round)
            }
            LibraryCoverMotif.PLAYLIST -> {
                val panelSize = Size(width * 0.54f, height * 0.63f)
                val panelTop = Offset(width * 0.23f, height * 0.17f)
                val corner = CornerRadius(width * 0.065f)
                drawRoundRect(
                    Color.White.copy(alpha = 0.20f),
                    topLeft = Offset(width * 0.30f, height * 0.20f),
                    size = panelSize,
                    cornerRadius = corner
                )
                rotate(degrees = -7f, pivot = Offset(width * 0.50f, height * 0.50f)) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.34f),
                        topLeft = Offset(width * 0.24f, height * 0.16f),
                        size = panelSize,
                        cornerRadius = corner
                    )
                }
                drawRoundRect(Color.White, topLeft = panelTop, size = panelSize, cornerRadius = corner)
                val ink = Color(kind.motifColor)
                val strokeWidth = (width * 0.025f).coerceAtLeast(1f)
                val rows = listOf(0.36f, 0.50f, 0.64f)
                rows.forEachIndexed { index, y ->
                    drawCircle(ink, width * 0.026f, Offset(width * 0.34f, height * y))
                    drawLine(
                        ink.copy(alpha = if (index == 0) 0.56f else 0.82f),
                        Offset(width * 0.41f, height * y),
                        Offset(width * if (index == 2) 0.66f else 0.69f, height * y),
                        strokeWidth,
                        StrokeCap.Round
                    )
                }
                drawCircle(Color(0xFFFFD878), width * 0.105f, Offset(width * 0.73f, height * 0.73f))
                drawLine(Color.White, Offset(width * 0.74f, height * 0.68f), Offset(width * 0.74f, height * 0.77f), strokeWidth, StrokeCap.Round)
                drawLine(Color.White, Offset(width * 0.74f, height * 0.68f), Offset(width * 0.79f, height * 0.70f), strokeWidth, StrokeCap.Round)
                drawCircle(Color.White, width * 0.025f, Offset(width * 0.72f, height * 0.78f))
                drawCircle(Color.White, width * 0.025f, Offset(width * 0.78f, height * 0.79f))
            }
        }
    }
}
