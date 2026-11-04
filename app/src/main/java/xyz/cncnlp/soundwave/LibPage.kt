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

// ===== 资料库图标字形 + 资料库条目（自 MainActivity 迁入；LibEntry private→internal） =====
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibEntry(
    title: String,
    sub: String,
    icon: ImageVector,
    cover: String,
    tint: Color,
    accentBg: Boolean = false,
    redesignDefault: Boolean = false,
    importStyle: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onLongPressMenu: (() -> Unit)? = null,
    kind: String = "",
    emptyCoverKind: LibraryDefaultCoverKind? = null,
    onHeartMode: (() -> Unit)? = null,
    onClearHistory: (() -> Unit)? = null,
    flat: Boolean = true,
    subFontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    contentInset: androidx.compose.ui.unit.Dp = 12.dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "lib")
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (flat) Modifier.padding(horizontal = 0.dp, vertical = 0.dp) else Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            .graphicsLayer { scaleX = scale; scaleY = scale }

            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPressMenu?.let { cb -> { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); cb() } }
            )
            .semantics { role = Role.Button }
            .padding(horizontal = contentInset, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 设计稿音乐库行：64dp 封面圆角 12 + 柔和投影，标题 15sp 粗体，右侧淡色箭头。
        Box(
            Modifier.size(64.dp)
                .shadow(6.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xFF131915).copy(alpha = 0.18f), spotColor = Color(0xFF131915).copy(alpha = 0.18f))
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (cover.isNotBlank() && kind !in setOf("import", "newpl")) {
                val ctxCover = LocalContext.current
                val model = remember(cover) { CacheManager.coverModel(ctxCover, cover) ?: cover }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else if (emptyCoverKind != null) {
                LibraryDefaultCover(kind = emptyCoverKind, modifier = Modifier.fillMaxSize())
            } else {
                LibGlyph(if (kind.isNotEmpty()) kind else "playlist", tint)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier.weight(1f).heightIn(min = 64.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().basicMarquee())
            Text(sub, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f), fontSize = subFontSize, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 2.dp).basicMarquee())
        }
        if (onClearHistory != null) {
            Canvas(Modifier.size(28.dp).clickable { onClearHistory() }.padding(end = 2.dp)) {
                val c = Color(0xFFB0B0B0.toInt())
                val st = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawLine(c, Offset(size.width * 0.28f, size.height * 0.30f), Offset(size.width * 0.72f, size.height * 0.30f), 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.38f, size.height * 0.22f), Offset(size.width * 0.62f, size.height * 0.22f), 1.7.dp.toPx(), StrokeCap.Round)
                drawRoundRect(
                    c,
                    topLeft = Offset(size.width * 0.30f, size.height * 0.32f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.40f, size.height * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = st
                )
                drawLine(c, Offset(size.width * 0.42f, size.height * 0.42f), Offset(size.width * 0.42f, size.height * 0.68f), 1.4.dp.toPx(), StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.58f, size.height * 0.42f), Offset(size.width * 0.58f, size.height * 0.68f), 1.4.dp.toPx(), StrokeCap.Round)
            }
        }
        if (onEdit == null && onClearHistory == null && kind !in setOf("import", "newpl")) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (onEdit != null) {
            Icon(
                Icons.Filled.MoreHoriz,
                "编辑",
                tint = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White.copy(alpha = 0.72f) else Color.Black,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onEdit() }
            )
        }
    }
}
