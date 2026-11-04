package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 无底板圆角五角星：顶点用二次贝塞尔圆滑，点击前空心、点击后实心。
 * 仅在浅色封面上加一圈极淡描边保证可见，不画背景块。
 */
@Composable
internal fun RoundedStar(
    filled: Boolean,
    color: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f + size.minDimension * 0.02f
        val outer = size.minDimension * 0.46f
        val inner = outer * 0.42f
        val pts = Array(10) { i ->
            val radius = if (i % 2 == 0) outer else inner
            val angle = -Math.PI / 2.0 + i * Math.PI / 5.0
            Offset(cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius)
        }
        val path = Path()
        // 五个外角和内凹角都加大圆角半径，避免收藏按钮呈现尖锐锯齿感。
        val round = size.minDimension * 0.25f
        for (i in pts.indices) {
            val prev = pts[(i + pts.lastIndex) % pts.size]
            val curr = pts[i]
            val next = pts[(i + 1) % pts.size]
            val toPrev = prev - curr
            val toNext = next - curr
            val lenPrev = toPrev.getDistance().coerceAtLeast(0.001f)
            val lenNext = toNext.getDistance().coerceAtLeast(0.001f)
            val r = round.coerceAtMost(lenPrev * 0.46f).coerceAtMost(lenNext * 0.46f)
            val p1 = curr + toPrev * (r / lenPrev)
            val p2 = curr + toNext * (r / lenNext)
            if (i == 0) path.moveTo(p1.x, p1.y) else path.lineTo(p1.x, p1.y)
            path.quadraticTo(curr.x, curr.y, p2.x, p2.y)
        }
        path.close()
        // 细描边优先：小尺寸收藏图案不再因粗实心 glyph 显得糊成一团。
        val halo = Color.Black.copy(alpha = 0.18f)
        if (filled) {
            drawPath(path, halo, style = Stroke(width = 1.35.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color)
        } else {
            drawPath(path, halo, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color, style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun PlaylistFavoriteMark(
    filled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(iconSize + 10.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        RoundedStar(filled = filled, color = color, modifier = Modifier.size(iconSize))
    }
}

// ===== 播放器/菜单图标字形（Canvas 绘制，自 MainActivity 迁入；保持 internal） =====
@Composable
internal fun WhiteGlyph(kind: String, size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = (w * 0.145f).coerceIn(3.4f, 5.6f)
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "select_empty" -> {
                drawCircle(color.copy(alpha = 0.55f), w * 0.34f, Offset(w * 0.50f, h * 0.50f), style = Stroke(width = sw * 1.1f))
                val ck = Path().apply { moveTo(w * 0.40f, h * 0.52f); lineTo(w * 0.48f, h * 0.60f); lineTo(w * 0.62f, h * 0.42f) }
                drawPath(ck, color.copy(alpha = 0.45f), style = Stroke(width = sw * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "select_filled" -> {
                drawCircle(color, w * 0.36f, Offset(w * 0.50f, h * 0.50f))
                val ck = Path().apply { moveTo(w * 0.38f, h * 0.52f); lineTo(w * 0.47f, h * 0.61f); lineTo(w * 0.63f, h * 0.41f) }
                drawPath(ck, Color.White, style = Stroke(width = sw * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "lyric_float" -> {
                drawRoundRect(color, Offset(w * 0.12f, h * 0.14f), Size(w * 0.62f, h * 0.56f), CornerRadius(w * 0.14f), style = Stroke(width = sw * 1.35f, cap = StrokeCap.Round))
                drawLine(color, Offset(w * 0.23f, h * 0.34f), Offset(w * 0.60f, h * 0.34f), sw, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.6f), Offset(w * 0.23f, h * 0.49f), Offset(w * 0.50f, h * 0.49f), sw, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.35f), Offset(w * 0.23f, h * 0.62f), Offset(w * 0.42f, h * 0.62f), sw * 0.85f, StrokeCap.Round)
                drawCircle(color.copy(alpha = 0.95f), w * 0.05f, Offset(w * 0.80f, h * 0.26f))
                drawLine(color.copy(alpha = 0.95f), Offset(w * 0.85f, h * 0.26f), Offset(w * 0.85f, h * 0.14f), sw * 0.75f, StrokeCap.Round)
            }
            "prev_solid" -> {
                val p1 = Path().apply { moveTo(w*.50f,h*.18f); cubicTo(w*.47f,h*.13f,w*.41f,h*.14f,w*.36f,h*.19f); lineTo(w*.11f,h*.43f); cubicTo(w*.04f,h*.49f,w*.04f,h*.53f,w*.11f,h*.59f); lineTo(w*.36f,h*.82f); cubicTo(w*.43f,h*.88f,w*.50f,h*.84f,w*.50f,h*.76f); close() }
                val p2 = Path().apply { moveTo(w*.90f,h*.18f); cubicTo(w*.87f,h*.13f,w*.81f,h*.14f,w*.76f,h*.19f); lineTo(w*.51f,h*.43f); cubicTo(w*.44f,h*.49f,w*.44f,h*.53f,w*.51f,h*.59f); lineTo(w*.76f,h*.82f); cubicTo(w*.83f,h*.88f,w*.90f,h*.84f,w*.90f,h*.76f); close() }
                drawPath(p1,color); drawPath(p2,color)
            }
            "next_solid" -> {
                val p1 = Path().apply { moveTo(w*.10f,h*.18f); cubicTo(w*.13f,h*.13f,w*.19f,h*.14f,w*.24f,h*.19f); lineTo(w*.49f,h*.43f); cubicTo(w*.56f,h*.49f,w*.56f,h*.53f,w*.49f,h*.59f); lineTo(w*.24f,h*.82f); cubicTo(w*.17f,h*.88f,w*.10f,h*.84f,w*.10f,h*.76f); close() }
                val p2 = Path().apply { moveTo(w*.50f,h*.18f); cubicTo(w*.53f,h*.13f,w*.59f,h*.14f,w*.64f,h*.19f); lineTo(w*.89f,h*.43f); cubicTo(w*.96f,h*.49f,w*.96f,h*.53f,w*.89f,h*.59f); lineTo(w*.64f,h*.82f); cubicTo(w*.57f,h*.88f,w*.50f,h*.84f,w*.50f,h*.76f); close() }
                drawPath(p1,color); drawPath(p2,color)
            }
            "play_round" -> {
                val p = Path().apply { moveTo(w*0.28f,h*0.17f); quadraticBezierTo(w*0.34f,h*0.13f,w*0.43f,h*0.20f); lineTo(w*0.82f,h*0.44f); quadraticBezierTo(w*0.92f,h*0.50f,w*0.82f,h*0.56f); lineTo(w*0.43f,h*0.80f); quadraticBezierTo(w*0.28f,h*0.89f,w*0.28f,h*0.72f); close() }
                drawPath(p,color)
            }
            "pause_solid" -> {
                drawRoundRect(color,Offset(w*0.25f,h*0.16f),Size(w*0.18f,h*0.68f),CornerRadius(w*0.08f))
                drawRoundRect(color,Offset(w*0.57f,h*0.16f),Size(w*0.18f,h*0.68f),CornerRadius(w*0.08f))
            }
            "mode_loop" -> {
                drawRoundRect(color,Offset(w*.10f,h*.22f),Size(w*.80f,h*.56f),CornerRadius(w*.22f),style=Stroke(width=sw*1.6f,cap=StrokeCap.Round))
                val a=Path().apply{ moveTo(w*.63f,h*.08f); quadraticBezierTo(w*.78f,h*.13f,w*.91f,h*.27f); lineTo(w*.65f,h*.34f); close() }; drawPath(a,color)
            }
            "mode_one" -> {
                drawRoundRect(color,Offset(w*.10f,h*.22f),Size(w*.80f,h*.56f),CornerRadius(w*.22f),style=Stroke(width=sw*1.6f,cap=StrokeCap.Round))
                drawRoundRect(color,Offset(w*.46f,h*.34f),Size(w*.09f,h*.34f),CornerRadius(w*.045f))
            }
            "mode_shuffle" -> {
                val p=Path().apply{ moveTo(w*.10f,h*.29f); cubicTo(w*.34f,h*.29f,w*.50f,h*.70f,w*.76f,h*.70f); moveTo(w*.10f,h*.71f); cubicTo(w*.34f,h*.71f,w*.50f,h*.30f,w*.76f,h*.30f) }; drawPath(p,color,style=Stroke(width=sw*1.7f,cap=StrokeCap.Round))
                drawCircle(color,sw*1.45f,Offset(w*.82f,h*.30f)); drawCircle(color,sw*1.45f,Offset(w*.82f,h*.70f))
            }
            "menu_playlist" -> { drawRoundRect(color,Offset(w*.12f,h*.18f),Size(w*.62f,h*.64f),CornerRadius(w*.12f),style=st); drawLine(color,Offset(w*.28f,h*.38f),Offset(w*.58f,h*.38f),sw,StrokeCap.Round); drawLine(color,Offset(w*.28f,h*.58f),Offset(w*.52f,h*.58f),sw,StrokeCap.Round); drawCircle(color,w*.07f,Offset(w*.79f,h*.70f)) }
            "menu_new" -> { drawRoundRect(color,Offset(w*.12f,h*.18f),Size(w*.58f,h*.64f),CornerRadius(w*.12f),style=st); drawLine(color,Offset(w*.77f,h*.34f),Offset(w*.77f,h*.70f),sw,StrokeCap.Round); drawLine(color,Offset(w*.59f,h*.52f),Offset(w*.95f,h*.52f),sw,StrokeCap.Round) }
            "menu_next" -> { drawRoundRect(color,Offset(w*.08f,h*.15f),Size(w*.84f,h*.70f),CornerRadius(w*.18f),style=st); val p=Path().apply{moveTo(w*.35f,h*.30f);quadraticBezierTo(w*.30f,h*.28f,w*.30f,h*.38f);lineTo(w*.58f,h*.50f);lineTo(w*.30f,h*.62f);quadraticBezierTo(w*.30f,h*.72f,w*.35f,h*.70f);close()};drawPath(p,color);drawRoundRect(color,Offset(w*.65f,h*.31f),Size(w*.08f,h*.38f),CornerRadius(w*.04f)) }
            "menu_download" -> { drawRoundRect(color,Offset(w*.14f,h*.12f),Size(w*.72f,h*.76f),CornerRadius(w*.16f),style=st); drawLine(color,Offset(w*.50f,h*.24f),Offset(w*.50f,h*.62f),sw,StrokeCap.Round); val p=Path().apply{moveTo(w*.32f,h*.49f);lineTo(w*.50f,h*.67f);lineTo(w*.68f,h*.49f)};drawPath(p,color,style=st) }
            "menu_cast" -> { drawRoundRect(color,Offset(w*.12f,h*.16f),Size(w*.76f,h*.58f),CornerRadius(w*.12f),style=st); drawArc(color,-75f,72f,false,Offset(w*.10f,h*.62f),Size(w*.25f,h*.25f),style=st); drawCircle(color,sw*.85f,Offset(w*.16f,h*.82f)) }
            "menu_similar" -> { drawCircle(color,w*.23f,Offset(w*.35f,h*.39f),style=st); drawCircle(color,w*.18f,Offset(w*.65f,h*.64f),style=st); drawLine(color,Offset(w*.50f,h*.50f),Offset(w*.58f,h*.57f),sw,StrokeCap.Round) }
            "menu_heart", "sparkles_filled", "sparkles_outline" -> {
                val filled = kind != "sparkles_outline"
                fun diamond(cx: Float, cy: Float, r: Float) {
                    val p = Path().apply { moveTo(cx, cy-r); lineTo(cx+r, cy); lineTo(cx, cy+r); lineTo(cx-r, cy); close() }
                    drawPath(p, color, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = sw*1.35f, join = StrokeJoin.Round))
                }
                diamond(w*.48f,h*.46f,w*.22f); diamond(w*.76f,h*.25f,w*.09f); diamond(w*.23f,h*.72f,w*.08f)
            }
            "menu_timer" -> { drawCircle(color,w*.34f,Offset(w*.50f,h*.54f),style=st); drawLine(color,Offset(w*.50f,h*.20f),Offset(w*.50f,h*.08f),sw,StrokeCap.Round); drawLine(color,Offset(w*.50f,h*.54f),Offset(w*.50f,h*.33f),sw,StrokeCap.Round); drawLine(color,Offset(w*.50f,h*.54f),Offset(w*.67f,h*.62f),sw,StrokeCap.Round) }
            "menu_lyrics" -> { drawRoundRect(color,Offset(w*.12f,h*.16f),Size(w*.76f,h*.58f),CornerRadius(w*.14f),style=st); val tail=Path().apply{moveTo(w*.32f,h*.74f);lineTo(w*.24f,h*.90f);lineTo(w*.48f,h*.74f)};drawPath(tail,color,style=st);drawLine(color,Offset(w*.28f,h*.36f),Offset(w*.72f,h*.36f),sw,StrokeCap.Round);drawLine(color,Offset(w*.28f,h*.53f),Offset(w*.62f,h*.53f),sw,StrokeCap.Round) }
            "menu_sort" -> {
                drawLine(color, Offset(w*.18f,h*.28f), Offset(w*.82f,h*.28f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.18f,h*.50f), Offset(w*.64f,h*.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.18f,h*.72f), Offset(w*.44f,h*.72f), sw, StrokeCap.Round)
                drawCircle(color, sw * 1.1f, Offset(w*.82f,h*.72f))
            }
            "menu_quality", "menu_speed", "menu_music", "menu_wave", "menu_spark", "menu_master", "menu_bits", "menu_atmos" -> {
                when (kind) {
                    "menu_quality" -> { drawLine(color, Offset(w*.18f,h*.72f), Offset(w*.82f,h*.72f), sw, StrokeCap.Round); drawCircle(color, sw*1.8f, Offset(w*.38f,h*.72f)); drawCircle(color, sw*1.8f, Offset(w*.68f,h*.72f)) }
                    "menu_speed" -> { drawArc(color, 210f, 120f, false, Offset(w*.16f,h*.16f), Size(w*.68f,h*.68f), style=st); drawLine(color,Offset(w*.50f,h*.50f),Offset(w*.70f,h*.33f),sw,StrokeCap.Round); drawCircle(color,sw,Offset(w*.50f,h*.50f)) }
                    "menu_music" -> { drawCircle(color,w*.24f,Offset(w*.36f,h*.68f)); drawCircle(color,w*.24f,Offset(w*.72f,h*.58f)); drawLine(color,Offset(w*.58f,h*.22f),Offset(w*.58f,h*.66f),sw,StrokeCap.Round); drawLine(color,Offset(w*.58f,h*.22f),Offset(w*.88f,h*.16f),sw,StrokeCap.Round); drawLine(color,Offset(w*.88f,h*.16f),Offset(w*.88f,h*.56f),sw,StrokeCap.Round) }
                    "menu_wave" -> { val p=Path().apply{moveTo(w*.12f,h*.54f);cubicTo(w*.25f,h*.20f,w*.38f,h*.86f,w*.50f,h*.50f);cubicTo(w*.62f,h*.14f,w*.75f,h*.80f,w*.88f,h*.42f)}; drawPath(p,color,style=st) }
                    "menu_spark" -> { drawCircle(color,w*.18f,Offset(w*.50f,h*.50f),style=st); drawLine(color,Offset(w*.50f,h*.10f),Offset(w*.50f,h*.90f),sw,StrokeCap.Round); drawLine(color,Offset(w*.10f,h*.50f),Offset(w*.90f,h*.50f),sw,StrokeCap.Round) }
                    "menu_master" -> { drawRoundRect(color,Offset(w*.16f,h*.20f),Size(w*.68f,h*.60f),CornerRadius(w*.10f),style=st); drawLine(color,Offset(w*.28f,h*.38f),Offset(w*.72f,h*.38f),sw,StrokeCap.Round); drawLine(color,Offset(w*.28f,h*.58f),Offset(w*.58f,h*.58f),sw,StrokeCap.Round) }
                    "menu_atmos" -> { drawArc(color, 205f, 130f, false, Offset(w*.16f,h*.16f), Size(w*.68f,h*.68f), style=st); drawArc(color, 205f, 130f, false, Offset(w*.30f,h*.30f), Size(w*.40f,h*.40f), style=st) }
                    else -> { drawRoundRect(color,Offset(w*.18f,h*.20f),Size(w*.64f,h*.60f),CornerRadius(w*.08f),style=st); drawLine(color,Offset(w*.30f,h*.40f),Offset(w*.70f,h*.40f),sw,StrokeCap.Round); drawLine(color,Offset(w*.30f,h*.60f),Offset(w*.62f,h*.60f),sw,StrokeCap.Round) }
                }
            }
            "menu_settings" -> {
                drawCircle(color, w*.28f, Offset(w*.50f,h*.50f), style=st)
                for (i in 0..7) {
                    val a = Math.PI * i / 4.0
                    val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                    drawLine(color, Offset(w*.50f+c*w*.36f,h*.50f+s*h*.36f), Offset(w*.50f+c*w*.46f,h*.50f+s*h*.46f), sw, StrokeCap.Round)
                }
            }
            "menu_edit" -> {
                drawLine(color, Offset(w*.22f,h*.78f), Offset(w*.70f,h*.30f), sw*1.4f, StrokeCap.Round)
                drawLine(color, Offset(w*.62f,h*.22f), Offset(w*.78f,h*.38f), sw*1.4f, StrokeCap.Round)
                drawLine(color, Offset(w*.18f,h*.84f), Offset(w*.40f,h*.80f), sw, StrokeCap.Round)
            }
            "menu_import" -> {
                drawRoundRect(color, Offset(w*.12f,h*.16f), Size(w*.60f,h*.66f), CornerRadius(w*.12f), style=st)
                drawLine(color, Offset(w*.24f,h*.34f), Offset(w*.50f,h*.34f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.24f,h*.50f), Offset(w*.44f,h*.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.80f,h*.30f), Offset(w*.80f,h*.60f), sw*1.2f, StrokeCap.Round)
                val impArrow = Path().apply { moveTo(w*.66f,h*.48f); lineTo(w*.80f,h*.62f); lineTo(w*.94f,h*.48f) }
                drawPath(impArrow, color, style=Stroke(width=sw*1.2f, cap=StrokeCap.Round, join=StrokeJoin.Round))
            }
            "menu_scan" -> {
                drawRoundRect(color, Offset(w*.16f,h*.16f), Size(w*.68f,h*.68f), CornerRadius(w*.14f), style=st)
                drawLine(color, Offset(w*.30f,h*.50f), Offset(w*.70f,h*.50f), sw*1.2f, StrokeCap.Round)
                drawLine(color, Offset(w*.30f,h*.34f), Offset(w*.42f,h*.34f), sw*.8f, StrokeCap.Round)
                drawLine(color, Offset(w*.58f,h*.34f), Offset(w*.70f,h*.34f), sw*.8f, StrokeCap.Round)
                drawLine(color, Offset(w*.30f,h*.66f), Offset(w*.42f,h*.66f), sw*.8f, StrokeCap.Round)
                drawLine(color, Offset(w*.58f,h*.66f), Offset(w*.70f,h*.66f), sw*.8f, StrokeCap.Round)
            }
            "menu_star" -> {
                val star = Path().apply {
                    moveTo(w*.50f,h*.14f)
                    lineTo(w*.61f,h*.36f); lineTo(w*.86f,h*.39f); lineTo(w*.68f,h*.56f)
                    lineTo(w*.73f,h*.82f); lineTo(w*.50f,h*.70f); lineTo(w*.27f,h*.82f)
                    lineTo(w*.32f,h*.56f); lineTo(w*.14f,h*.39f); lineTo(w*.39f,h*.36f)
                    close()
                }
                drawPath(star, color, style=Stroke(width=sw*1.1f, cap=StrokeCap.Round, join=StrokeJoin.Round))
            }
            "menu_cover" -> {
                drawRoundRect(color, Offset(w*.14f,h*.18f), Size(w*.72f,h*.64f), CornerRadius(w*.10f), style=st)
                drawCircle(color, sw*1.2f, Offset(w*.36f,h*.38f))
                val imagePath = Path().apply { moveTo(w*.20f,h*.70f); lineTo(w*.42f,h*.50f); lineTo(w*.56f,h*.62f); lineTo(w*.68f,h*.46f); lineTo(w*.82f,h*.70f) }
                drawPath(imagePath, color, style=st)
            }
            "menu_hide" -> {
                drawOval(color, Offset(w*.14f,h*.28f), Size(w*.72f,h*.44f), style=st)
                drawCircle(color, sw*1.15f, Offset(w*.50f,h*.50f))
                drawLine(color, Offset(w*.20f,h*.20f), Offset(w*.80f,h*.80f), sw, StrokeCap.Round)
            }
            "menu_delete" -> {
                drawRoundRect(color, Offset(w*.25f,h*.30f), Size(w*.50f,h*.52f), CornerRadius(w*.08f), style=Fill)
                drawLine(color, Offset(w*.18f,h*.24f), Offset(w*.82f,h*.24f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.40f,h*.16f), Offset(w*.60f,h*.16f), sw, StrokeCap.Round)
                drawLine(Color.White.copy(alpha=.8f), Offset(w*.42f,h*.42f), Offset(w*.42f,h*.70f), sw*.7f, StrokeCap.Round)
                drawLine(Color.White.copy(alpha=.8f), Offset(w*.58f,h*.42f), Offset(w*.58f,h*.70f), sw*.7f, StrokeCap.Round)
            }
            "loop_solid" -> {
                drawRoundRect(color,Offset(w*0.12f,h*0.28f),Size(w*0.76f,h*0.44f),CornerRadius(w*0.18f),style=Stroke(width=sw*1.8f,cap=StrokeCap.Round))
                val a=Path().apply{ moveTo(w*0.68f,h*0.12f); lineTo(w*0.92f,h*0.30f); lineTo(w*0.66f,h*0.37f); close() }; drawPath(a,color)
            }
            "one_solid" -> {
                drawRoundRect(color,Offset(w*0.12f,h*0.28f),Size(w*0.76f,h*0.44f),CornerRadius(w*0.18f),style=Stroke(width=sw*1.8f,cap=StrokeCap.Round))
                drawLine(color,Offset(w*0.50f,h*0.37f),Offset(w*0.50f,h*0.64f),sw*1.7f,StrokeCap.Round)
            }
            "shuffle_solid" -> {
                val st2=Stroke(width=sw*1.8f,cap=StrokeCap.Round,join=StrokeJoin.Round)
                val p=Path().apply{ moveTo(w*.12f,h*.30f); lineTo(w*.36f,h*.30f); lineTo(w*.82f,h*.74f); moveTo(w*.12f,h*.70f); lineTo(w*.36f,h*.70f); lineTo(w*.82f,h*.26f) }; drawPath(p,color,style=st2)
                drawCircle(color,sw*1.4f,Offset(w*.84f,h*.26f)); drawCircle(color,sw*1.4f,Offset(w*.84f,h*.74f))
            }
            "lyrics_quote" -> {
                // 轻线对白气泡 + 成对引号；未选中态用极淡宽描边而非背景底板提气。
                val fine = (w * .068f).coerceIn(1.35.dp.toPx(), 1.9.dp.toPx())
                val outline = Stroke(width = fine, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val bubble = Path().apply {
                    moveTo(w*.29f,h*.12f); lineTo(w*.71f,h*.12f)
                    quadraticTo(w*.86f,h*.12f,w*.86f,h*.27f); lineTo(w*.86f,h*.61f)
                    quadraticTo(w*.86f,h*.76f,w*.71f,h*.76f); lineTo(w*.47f,h*.76f)
                    lineTo(w*.28f,h*.91f); lineTo(w*.31f,h*.76f); lineTo(w*.29f,h*.76f)
                    quadraticTo(w*.14f,h*.76f,w*.14f,h*.61f); lineTo(w*.14f,h*.27f)
                    quadraticTo(w*.14f,h*.12f,w*.29f,h*.12f)
                }
                drawPath(bubble, color.copy(alpha = color.alpha * .13f), style = Stroke(width = fine * 2.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bubble, color, style = outline)
                listOf(w*.40f, w*.59f).forEach { x ->
                    drawCircle(color, fine*.78f, Offset(x, h*.39f))
                    val quoteTail = Path().apply {
                        moveTo(x, h*.43f); quadraticTo(x, h*.50f, x-w*.045f, h*.54f)
                    }
                    drawPath(quoteTail, color.copy(alpha = color.alpha*.88f), style = Stroke(width = fine*.72f, cap = StrokeCap.Round))
                }
            }
            "lyrics_immersive_active" -> {
                // 与 lyrics_quote 共用 .12/.18→.72/.74 的外框和 25dp 画布；激活态只改内部标记。
                val st2 = Stroke(width=sw*1.4f, cap=StrokeCap.Round, join=StrokeJoin.Round)
                drawRoundRect(color, Offset(w*.12f,h*.18f), Size(w*.60f,h*.56f), CornerRadius(w*.10f), style=st2)
                drawLine(color, Offset(w*.24f,h*.34f), Offset(w*.58f,h*.34f), sw*1.25f, StrokeCap.Round)
                drawLine(color, Offset(w*.24f,h*.49f), Offset(w*.54f,h*.49f), sw*1.25f, StrokeCap.Round)
                drawLine(color, Offset(w*.24f,h*.63f), Offset(w*.47f,h*.63f), sw*1.25f, StrokeCap.Round)
                drawCircle(color, sw*1.05f, Offset(w*.82f,h*.70f))
                drawLine(color, Offset(w*.82f,h*.70f), Offset(w*.82f,h*.42f), sw*1.1f, StrokeCap.Round)
            }
            "lyrics_chat" -> {
                drawRoundRect(color,Offset(w*.12f,h*.16f),Size(w*.76f,h*.56f),CornerRadius(w*.14f),style=Stroke(width=sw*1.7f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                val tail=Path().apply{moveTo(w*.32f,h*.72f);lineTo(w*.24f,h*.90f);lineTo(w*.48f,h*.72f)};drawPath(tail,color,style=Stroke(width=sw*1.7f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                drawLine(color,Offset(w*.28f,h*.36f),Offset(w*.70f,h*.36f),sw,StrokeCap.Round)
                drawLine(color,Offset(w*.28f,h*.52f),Offset(w*.58f,h*.52f),sw,StrokeCap.Round)
            }
            "lyrics_chat_check" -> {
                drawRoundRect(color,Offset(w*.12f,h*.16f),Size(w*.76f,h*.56f),CornerRadius(w*.14f),style=Stroke(width=sw*1.5f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                val tail=Path().apply{ moveTo(w*.32f,h*.72f); lineTo(w*.24f,h*.90f); lineTo(w*.48f,h*.72f) }; drawPath(tail,color,style=Stroke(width=sw*1.5f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                val check=Path().apply{ moveTo(w*.30f,h*.44f); lineTo(w*.45f,h*.57f); lineTo(w*.72f,h*.31f) }; drawPath(check,color,style=Stroke(width=sw*1.7f,cap=StrokeCap.Round,join=StrokeJoin.Round))
            }
            "lyrics_focus" -> {
                // 激活歌词：沿用 lyrics_quote 的外接框，改为三条对齐的当前句，画布尺寸不变。
                val st2 = Stroke(width=sw*1.4f, cap=StrokeCap.Round, join=StrokeJoin.Round)
                drawRoundRect(color, Offset(w*.12f,h*.18f), Size(w*.60f,h*.56f), CornerRadius(w*.10f), style=st2)
                drawLine(color, Offset(w*.24f,h*.33f), Offset(w*.58f,h*.33f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.24f,h*.47f), Offset(w*.58f,h*.47f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*.24f,h*.61f), Offset(w*.51f,h*.61f), sw, StrokeCap.Round)
                drawCircle(color, sw*1.05f, Offset(w*.82f,h*.70f))
                drawLine(color, Offset(w*.82f,h*.70f), Offset(w*.82f,h*.42f), sw*1.1f, StrokeCap.Round)
            }
            "device_active" -> {
                // 激活设备：与未激活相同外框/底座，在屏内加入三段输出指示。
                val st2 = Stroke(width=sw*1.7f, cap=StrokeCap.Round, join=StrokeJoin.Round)
                drawRoundRect(color,Offset(w*.14f,h*.16f),Size(w*.72f,h*.50f),CornerRadius(w*.10f),style=st2)
                drawRoundRect(color,Offset(w*.37f,h*.73f),Size(w*.26f,h*.10f),CornerRadius(w*.05f))
                drawLine(color,Offset(w*.34f,h*.52f),Offset(w*.34f,h*.40f),sw,StrokeCap.Round)
                drawLine(color,Offset(w*.50f,h*.52f),Offset(w*.50f,h*.31f),sw,StrokeCap.Round)
                drawLine(color,Offset(w*.66f,h*.52f),Offset(w*.66f,h*.37f),sw,StrokeCap.Round)
            }
            "queue_active" -> {
                // 激活队列：严格使用 queue_outline 的外框和播放杆，仅把列表改成实心短线。
                val st2 = Stroke(width=sw*1.35f,cap=StrokeCap.Round,join=StrokeJoin.Round)
                drawRoundRect(color, Offset(w*.08f,h*.18f), Size(w*.62f,h*.56f), CornerRadius(w*.10f), style=st2)
                drawLine(color, Offset(w*.22f,h*.36f), Offset(w*.56f,h*.36f), sw*1.55f, StrokeCap.Round)
                drawLine(color, Offset(w*.22f,h*.51f), Offset(w*.48f,h*.51f), sw*1.55f, StrokeCap.Round)
                drawLine(color, Offset(w*.22f,h*.64f), Offset(w*.42f,h*.64f), sw*1.55f, StrokeCap.Round)
                drawLine(color, Offset(w*.82f,h*.28f), Offset(w*.82f,h*.70f), sw*1.2f, StrokeCap.Round)
                drawCircle(color, sw*1.15f, Offset(w*.82f,h*.70f))
            }
            "device_outline" -> {
                drawRoundRect(color,Offset(w*.14f,h*.16f),Size(w*.72f,h*.50f),CornerRadius(w*.10f),style=Stroke(width=sw*1.7f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                drawRoundRect(color,Offset(w*.37f,h*.73f),Size(w*.26f,h*.10f),CornerRadius(w*.05f))
            }
            "device_earbuds" -> {
                // 两枚微微向内倾斜的独立耳塞，保留耳机轮廓和短柄，不画设备面板。
                val fine = (w * .068f).coerceIn(1.35.dp.toPx(), 1.9.dp.toPx())
                val podStroke = Stroke(width = fine, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val left = Path().apply {
                    moveTo(w*.28f,h*.12f); cubicTo(w*.13f,h*.15f,w*.11f,h*.35f,w*.21f,h*.47f)
                    cubicTo(w*.27f,h*.55f,w*.40f,h*.52f,w*.44f,h*.42f); lineTo(w*.43f,h*.24f)
                    cubicTo(w*.42f,h*.15f,w*.36f,h*.10f,w*.28f,h*.12f)
                }
                val right = Path().apply {
                    moveTo(w*.72f,h*.12f); cubicTo(w*.87f,h*.15f,w*.89f,h*.35f,w*.79f,h*.47f)
                    cubicTo(w*.73f,h*.55f,w*.60f,h*.52f,w*.56f,h*.42f); lineTo(w*.57f,h*.24f)
                    cubicTo(w*.58f,h*.15f,w*.64f,h*.10f,w*.72f,h*.12f)
                }
                listOf(left, right).forEach {
                    drawPath(it, color.copy(alpha = color.alpha*.13f), style = Stroke(width = fine*2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(it, color, style = podStroke)
                }
                val leftStem = Path().apply { moveTo(w*.33f,h*.49f); cubicTo(w*.31f,h*.61f,w*.31f,h*.72f,w*.34f,h*.86f) }
                val rightStem = Path().apply { moveTo(w*.67f,h*.49f); cubicTo(w*.69f,h*.61f,w*.69f,h*.72f,w*.66f,h*.86f) }
                listOf(leftStem, rightStem).forEach {
                    drawPath(it, color.copy(alpha = color.alpha*.13f), style = Stroke(width = fine*2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(it, color, style = podStroke)
                }
                drawCircle(color.copy(alpha = color.alpha*.78f), fine*.52f, Offset(w*.31f,h*.32f))
                drawCircle(color.copy(alpha = color.alpha*.78f), fine*.52f, Offset(w*.69f,h*.32f))
            }
            "queue_outline" -> {
                // 未选中：三层队列卡片轮廓 + 右侧播放指示。
                drawRoundRect(color, Offset(w*.08f,h*.18f), Size(w*.62f,h*.56f), CornerRadius(w*.10f), style=Stroke(width=sw*1.35f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                drawLine(color, Offset(w*.22f,h*.36f), Offset(w*.56f,h*.36f), sw*.95f, StrokeCap.Round)
                drawLine(color.copy(alpha=.52f), Offset(w*.22f,h*.51f), Offset(w*.48f,h*.51f), sw*.9f, StrokeCap.Round)
                drawLine(color.copy(alpha=.36f), Offset(w*.22f,h*.64f), Offset(w*.42f,h*.64f), sw*.9f, StrokeCap.Round)
                drawLine(color, Offset(w*.82f,h*.28f), Offset(w*.82f,h*.70f), sw*1.2f, StrokeCap.Round)
                drawCircle(color, sw, Offset(w*.82f,h*.70f))
            }
            "queue_note" -> {
                // 参考图的三条轻列表线 + 单音符：轮廓清晰、无卡片框或底板。
                val fine = (w * .068f).coerceIn(1.35.dp.toPx(), 1.9.dp.toPx())
                val ys = listOf(h*.27f, h*.50f, h*.73f)
                val ends = listOf(w*.59f, w*.54f, w*.48f)
                ys.indices.forEach { i ->
                    val start = Offset(w*.12f, ys[i]); val end = Offset(ends[i], ys[i])
                    drawLine(color.copy(alpha = color.alpha*.13f), start, end, fine*2.1f, StrokeCap.Round)
                    drawLine(color, start, end, fine, StrokeCap.Round)
                }
                val stem = Path().apply {
                    moveTo(w*.78f,h*.19f); quadraticTo(w*.85f,h*.16f,w*.91f,h*.15f)
                    lineTo(w*.91f,h*.57f)
                }
                drawPath(stem, color.copy(alpha = color.alpha*.13f), style = Stroke(width = fine*2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(stem, color, style = Stroke(width = fine, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawOval(color, Offset(w*.66f,h*.65f), Size(w*.17f,h*.12f))
            }
            "volume_on" -> { drawPath(Path().apply{ moveTo(w*.10f,h*.42f); lineTo(w*.30f,h*.42f); lineTo(w*.54f,h*.22f); lineTo(w*.54f,h*.78f); lineTo(w*.30f,h*.58f); lineTo(w*.10f,h*.58f); close() }, color); drawArc(color,-42f,84f,false,Offset(w*.50f,h*.25f),Size(w*.36f,h*.50f),style=Stroke(width=sw*1.55f,cap=StrokeCap.Round)); drawArc(color,-38f,76f,false,Offset(w*.48f,h*.14f),Size(w*.50f,h*.72f),style=Stroke(width=sw*1.25f,cap=StrokeCap.Round)) }
            "volume_off" -> { drawPath(Path().apply{ moveTo(w*.10f,h*.42f); lineTo(w*.30f,h*.42f); lineTo(w*.54f,h*.22f); lineTo(w*.54f,h*.78f); lineTo(w*.30f,h*.58f); lineTo(w*.10f,h*.58f); close() }, color.copy(alpha=.62f)); drawLine(color,Offset(w*.68f,h*.32f),Offset(w*.90f,h*.68f),sw*1.6f,StrokeCap.Round); drawLine(color,Offset(w*.90f,h*.32f),Offset(w*.68f,h*.68f),sw*1.6f,StrokeCap.Round) }
            "device_solid" -> {
                drawRoundRect(color,Offset(w*.14f,h*.16f),Size(w*.72f,h*.50f),CornerRadius(w*.10f))
                drawRoundRect(color,Offset(w*.37f,h*.73f),Size(w*.26f,h*.10f),CornerRadius(w*.05f))
                drawCircle(Color(0xFF77777E.toInt()),w*.08f,Offset(w*.50f,h*.41f))
            }
            "volume_low", "volume_high" -> {
                val body=Path().apply{ moveTo(w*.10f,h*.42f); lineTo(w*.30f,h*.42f); lineTo(w*.54f,h*.22f); lineTo(w*.54f,h*.78f); lineTo(w*.30f,h*.58f); lineTo(w*.10f,h*.58f); close() }; drawPath(body,color)
                drawArc(color,-42f,84f,false,Offset(w*.50f,h*.30f),Size(w*.30f,h*.40f),style=Stroke(width=sw*1.3f,cap=StrokeCap.Round))
                if(kind=="volume_high") drawArc(color,-38f,76f,false,Offset(w*.48f,h*.18f),Size(w*.46f,h*.64f),style=Stroke(width=sw*1.1f,cap=StrokeCap.Round))
            }
            "prev" -> {
                val p = Path().apply {
                    moveTo(w * 0.78f, h * 0.18f); lineTo(w * 0.28f, h * 0.50f); lineTo(w * 0.78f, h * 0.82f); close()
                }
                drawPath(p, color)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f), androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.82f), sw, StrokeCap.Round)
            }
            "next" -> {
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.18f); lineTo(w * 0.72f, h * 0.50f); lineTo(w * 0.22f, h * 0.82f); close()
                }
                drawPath(p, color)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.18f), androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.82f), sw, StrokeCap.Round)
            }
            "play" -> {
                val p = Path().apply {
                    moveTo(w * 0.30f, h * 0.16f); lineTo(w * 0.84f, h * 0.50f); lineTo(w * 0.30f, h * 0.84f); close()
                }
                drawPath(p, color)
            }
            "pause" -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.18f), androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.82f), sw * 1.6f, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.18f), androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.82f), sw * 1.6f, StrokeCap.Round)
            }
            "loop" -> {
                drawArc(color, 20f, 280f, false, androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.16f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.68f), style = st)
            }
            "one" -> {
                drawArc(color, 20f, 280f, false, androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.16f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.68f), style = st)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.68f), sw, StrokeCap.Round)
            }
            "shuffle" -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.32f), sw, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.72f), sw, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.68f), androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.68f), sw, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.68f), androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.28f), sw, StrokeCap.Round)
            }
            "heart_filled" -> {
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.78f)
                    cubicTo(w * 0.18f, h * 0.58f, w * 0.14f, h * 0.30f, w * 0.36f, h * 0.24f)
                    cubicTo(w * 0.46f, h * 0.20f, w * 0.50f, h * 0.32f, w * 0.50f, h * 0.32f)
                    cubicTo(w * 0.50f, h * 0.32f, w * 0.54f, h * 0.20f, w * 0.64f, h * 0.24f)
                    cubicTo(w * 0.86f, h * 0.30f, w * 0.82f, h * 0.58f, w * 0.50f, h * 0.78f)
                    close()
                }
                drawPath(p, color) // filled
            }
            "download" -> {
                // 下载箭头 + 底线
                drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.68f), sw, StrokeCap.Round)
                val arr = Path().apply {
                    moveTo(w * 0.28f, h * 0.50f); lineTo(w * 0.5f, h * 0.72f); lineTo(w * 0.72f, h * 0.50f)
                }
                drawPath(arr, color, style = st)
                drawLine(color, Offset(w * 0.22f, h * 0.82f), Offset(w * 0.78f, h * 0.82f), sw, StrokeCap.Round)
            }
            "source" -> {
                drawCircle(color, w * 0.28f, Offset(w * 0.38f, h * 0.42f), style = st)
                drawLine(color, Offset(w * 0.60f, h * 0.24f), Offset(w * 0.84f, h * 0.24f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.60f, h * 0.24f), Offset(w * 0.60f, h * 0.74f), sw, StrokeCap.Round)
                drawArc(color, 195f, 150f, false, Offset(w * 0.18f, h * 0.32f), Size(w * 0.42f, h * 0.42f), style = st)
            }
            "queue" -> {
                drawRoundRect(color, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), CornerRadius(3.dp.toPx()), style = st)
                drawLine(color, Offset(w * 0.32f, h * 0.38f), Offset(w * 0.68f, h * 0.38f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.55f), Offset(w * 0.60f, h * 0.55f), sw, StrokeCap.Round)
                drawCircle(color, sw * 0.9f, Offset(w * 0.70f, h * 0.74f))
            }
            "locate" -> {
                drawCircle(color, w * 0.28f, Offset(w * 0.50f, h * 0.50f), style = st)
                drawLine(color, Offset(w * 0.50f, h * 0.08f), Offset(w * 0.50f, h * 0.24f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.50f, h * 0.76f), Offset(w * 0.50f, h * 0.92f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.08f, h * 0.50f), Offset(w * 0.24f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.76f, h * 0.50f), Offset(w * 0.92f, h * 0.50f), sw, StrokeCap.Round)
            }
            "device" -> {
                drawRoundRect(color, Offset(w*0.18f,h*0.22f), Size(w*0.64f,h*0.44f), CornerRadius(4.dp.toPx()), style=st)
                drawLine(color, Offset(w*0.36f,h*0.80f), Offset(w*0.64f,h*0.80f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*0.50f,h*0.66f), Offset(w*0.50f,h*0.80f), sw, StrokeCap.Round)
            }
            "lyrics" -> {
                drawLine(color, Offset(w*0.18f,h*0.30f), Offset(w*0.82f,h*0.30f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*0.18f,h*0.50f), Offset(w*0.68f,h*0.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w*0.18f,h*0.70f), Offset(w*0.74f,h*0.70f), sw, StrokeCap.Round)
            }
            "volume" -> {
                val p=Path().apply{ moveTo(w*0.18f,h*0.44f); lineTo(w*0.36f,h*0.44f); lineTo(w*0.58f,h*0.26f); lineTo(w*0.58f,h*0.74f); lineTo(w*0.36f,h*0.56f); lineTo(w*0.18f,h*0.56f); close() }
                drawPath(p,color,style=st)
                drawArc(color,-35f,70f,false,Offset(w*0.58f,h*0.32f),Size(w*0.22f,h*0.36f),style=st)
            }
            "heart" -> {
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.78f)
                    cubicTo(w * 0.18f, h * 0.58f, w * 0.14f, h * 0.30f, w * 0.36f, h * 0.24f)
                    cubicTo(w * 0.46f, h * 0.20f, w * 0.50f, h * 0.32f, w * 0.50f, h * 0.32f)
                    cubicTo(w * 0.50f, h * 0.32f, w * 0.54f, h * 0.20f, w * 0.64f, h * 0.24f)
                    cubicTo(w * 0.86f, h * 0.30f, w * 0.82f, h * 0.58f, w * 0.50f, h * 0.78f)
                    close()
                }
                drawPath(p, color, style = st)
            }
            else -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.30f), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.30f), sw, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.50f), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.70f), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.70f), sw, StrokeCap.Round)
            }
        }
    }
}
