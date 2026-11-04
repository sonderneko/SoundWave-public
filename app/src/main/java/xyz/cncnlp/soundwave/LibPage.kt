package xyz.cncnlp.soundwave

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage

// ===== 资料库图标字形（自 MainActivity 迁入） =====
@Composable
fun LibGlyph(kind: String, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val st = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        when (kind) {
            "liked" -> {
                val p = Path()
                p.moveTo(w * 0.50f, h * 0.78f)
                p.cubicTo(w * 0.18f, h * 0.58f, w * 0.10f, h * 0.32f, w * 0.32f, h * 0.22f)
                p.cubicTo(w * 0.42f, h * 0.16f, w * 0.50f, h * 0.26f, w * 0.50f, h * 0.34f)
                p.cubicTo(w * 0.50f, h * 0.26f, w * 0.58f, h * 0.16f, w * 0.68f, h * 0.22f)
                p.cubicTo(w * 0.90f, h * 0.32f, w * 0.82f, h * 0.58f, w * 0.50f, h * 0.78f)
                p.close()
                drawPath(p, Color(0xFFFF4D8D.toInt()))
                drawPath(p, Color.White.copy(alpha = 0.28f), style = Stroke(1.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(Color.White.copy(alpha = 0.35f), w * 0.07f, Offset(w * 0.38f, h * 0.34f))
            }
            "local" -> {
                drawRoundRect(tint, Offset(w * 0.16f, h * 0.38f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.44f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), st)
                drawRoundRect(tint, Offset(w * 0.16f, h * 0.26f), androidx.compose.ui.geometry.Size(w * 0.30f, h * 0.16f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), st)
            }
            "default" -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.32f), Offset(w * 0.78f, h * 0.32f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.50f), Offset(w * 0.78f, h * 0.50f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.68f), Offset(w * 0.62f, h * 0.68f), 1.8.dp.toPx(), StrokeCap.Round)
            }
            "newpl" -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.30f), Offset(w * 0.78f, h * 0.30f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.50f), Offset(w * 0.56f, h * 0.50f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.70f, h * 0.44f), Offset(w * 0.70f, h * 0.76f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.54f, h * 0.60f), Offset(w * 0.86f, h * 0.60f), 1.8.dp.toPx(), StrokeCap.Round)
            }
            "import" -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.16f), Offset(w * 0.50f, h * 0.58f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.44f), Offset(w * 0.50f, h * 0.60f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.44f), Offset(w * 0.50f, h * 0.60f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.78f), Offset(w * 0.78f, h * 0.78f), 1.8.dp.toPx(), StrokeCap.Round)
            }
            "history" -> {
                drawCircle(tint, w * 0.38f, Offset(w * 0.50f, h * 0.52f), style = st)
                drawLine(tint, Offset(w * 0.50f, h * 0.52f), Offset(w * 0.50f, h * 0.34f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.52f), Offset(w * 0.68f, h * 0.58f), 1.8.dp.toPx(), StrokeCap.Round)
            }
            else -> {
                drawCircle(tint, 3.dp.toPx(), Offset(w * 0.36f, h * 0.70f))
                drawLine(tint, Offset(w * 0.36f, h * 0.70f), Offset(w * 0.36f, h * 0.22f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, h * 0.22f), Offset(w * 0.70f, h * 0.30f), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.70f, h * 0.30f), Offset(w * 0.70f, h * 0.62f), 1.8.dp.toPx(), StrokeCap.Round)
                drawCircle(tint, 3.dp.toPx(), Offset(w * 0.70f, h * 0.62f))
            }
        }
    }
}

@Composable
internal fun PlayCountHeadset(
    modifier: Modifier = Modifier.size(15.dp),
    tint: Color = Color(0xFF8E8E93.toInt())
) {
    Canvas(modifier) {
        val c = tint
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(c, 205f, 130f, false,
            Offset(size.width * .18f, size.height * .08f),
            androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .72f),
            style = stroke)
        drawRoundRect(c, Offset(size.width * .10f, size.height * .48f), androidx.compose.ui.geometry.Size(size.width * .20f, size.height * .30f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        drawRoundRect(c, Offset(size.width * .70f, size.height * .48f), androidx.compose.ui.geometry.Size(size.width * .20f, size.height * .30f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    }
}

