package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全 App 统一的封面占位体系：未加载 / 加载失败 / 无封面共用同一套视觉。
 *
 * ACCEPT4 按 v8 稿（v8phSVG）重做母题：底 = 中性暖灰斜向渐变（浅 #ECECE6→#F8F8F4 /
 * 夜 #272B26→#161916），左上珊瑚柔光（稿 radial cx.16 cy.08，alpha 浅 .20 / 夜 .30）；
 * 中央 = 黑胶唱片：盘面径向渐变（浅 #2B302C→#3D433E / 夜 #0C0E0C→#1F231F）、5 圈白色
 * 低透沟纹、左上一道高光弧、珊瑚芯标（稿 r31/94）+ 底色中心孔。几何全部按稿 400 单位
 * 等比缩放，40dp 级列表行内仍可辨（黑盘 + 珊瑚芯）。稿 SVG 右下另有一道蓝色柔光——与
 * 「双色板已废、应用自有色不许蓝」站规冲突，故意不取（台账有记）。
 * 父级负责裁形（方形圆角 / 圆形头像均可），本组件只填满边界。
 *
 * 验收二轮 E4 口径沿袭：颜色走设计调色板字面值（按背景亮度分浅/夜），不读主题角色，
 * 避免根主题表面落到 Material 默认色。
 */
@Composable
internal fun coverPlaceholderBrush(): Brush {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (dark) {
        listOf(SoundwaveDesignPalette.NightCoverBgStart, SoundwaveDesignPalette.NightCoverBgEnd)
    } else {
        listOf(SoundwaveDesignPalette.CoverBgStart, SoundwaveDesignPalette.CoverBgEnd)
    }
    return Brush.linearGradient(
        colors = colors,
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
}

/** 占位底图：中性暖灰渐变底 + 左上珊瑚柔光；内容由调用方居中排布。 */
@Composable
internal fun CoverPlaceholderBase(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glow = SoundwaveDesignPalette.Accent.copy(alpha = if (dark) 0.30f else 0.20f)
    Box(
        modifier.fillMaxSize().background(coverPlaceholderBrush())
            .drawBehind {
                // 稿 radialGradient：cx=.16w、cy=.08h、r=.8（以 400 方形边长计 → 取 minDimension 的 .8 倍贴近方形封面）。
                val center = Offset(size.width * 0.16f, size.height * 0.08f)
                val radius = size.minDimension * 0.8f
                drawCircle(
                    Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = radius),
                    radius = radius, center = center
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 黑胶唱片母题（v8 稿 v8phSVG 的中央唱片）：填满父级、按短边等比绘制。
 * 盘半径 = 短边 × 94/400，沟纹半径比 84/74/64/54/45（/94），芯标 31/94，孔 5.5/94。
 */
@Composable
internal fun VinylDiscMotif(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val core = if (dark) SoundwaveDesignPalette.NightVinylCore else SoundwaveDesignPalette.VinylCore
    val edge = if (dark) SoundwaveDesignPalette.NightVinylEdge else SoundwaveDesignPalette.VinylEdge
    val hole = if (dark) SoundwaveDesignPalette.NightCoverBgEnd else SoundwaveDesignPalette.CoverBgEnd
    val groove = Color.White.copy(alpha = if (dark) 0.06f else 0.09f)
    val accent = SoundwaveDesignPalette.Accent
    Canvas(modifier.fillMaxSize()) {
        val unit = size.minDimension / 400f
        val center = Offset(size.width / 2f, size.height / 2f)
        val discR = 94f * unit
        // 盘面：径向渐变（稿 stop .3 取芯色 → 边缘色）。
        drawCircle(
            Brush.radialGradient(
                colorStops = arrayOf(0.3f to core, 1f to edge),
                center = center, radius = discR
            ),
            radius = discR, center = center
        )
        // 盘缘描边：黑 12%。
        drawCircle(Color.Black.copy(alpha = 0.12f), radius = discR, center = center, style = Stroke(width = 2f * unit))
        // 沟纹 5 圈。
        for (gr in listOf(84f, 74f, 64f, 54f, 45f)) {
            drawCircle(groove, radius = gr * unit, center = center, style = Stroke(width = 1.4f * unit))
        }
        // 左上高光弧（稿 path：r86 圆上 (−74,−42)→(−28,−80) 一段，≈209.6°→250.7°，线宽 6、白 20%、圆头）。
        drawArc(
            color = Color.White.copy(alpha = 0.20f),
            startAngle = 209.6f, sweepAngle = 41.1f, useCenter = false,
            topLeft = Offset(center.x - 86f * unit, center.y - 86f * unit),
            size = Size(172f * unit, 172f * unit),
            style = Stroke(width = 6f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
        // 珊瑚芯标 + 白 25% 细环 + 中心孔。
        drawCircle(accent, radius = 31f * unit, center = center)
        drawCircle(Color.White.copy(alpha = 0.25f), radius = 31f * unit, center = center, style = Stroke(width = 1.5f * unit))
        drawCircle(hole, radius = 5.5f * unit, center = center)
    }
}

/** 占位字形圆盘：表面色半透圆 + 强调色字形。仅供显式传字形的语义占位（歌手头像等）使用；封面占位已改走 [VinylDiscMotif]。 */
@Composable
internal fun CoverPlaceholderGlyph(glyph: ImageVector, glyphSizeDp: Int) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val disc = if (dark) SoundwaveDesignPalette.NightSurface else SoundwaveDesignPalette.Surface
    Box(
        Modifier.size((glyphSizeDp * 2).dp).clip(CircleShape)
            .background(disc.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            glyph,
            contentDescription = null,
            tint = SoundwaveDesignPalette.Accent.copy(alpha = 0.92f),
            modifier = Modifier.size(glyphSizeDp.dp)
        )
    }
}

@Composable
internal fun DefaultCoverPlaceholder(
    modifier: Modifier = Modifier,
    glyph: ImageVector = Icons.Outlined.MusicNote,
    glyphSizeDp: Int = 22,
    contentDescription: String? = null
) {
    CoverPlaceholderBase(
        if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    ) {
        if (glyph == Icons.Outlined.MusicNote) {
            // 默认封面母题 = 黑胶唱片（v8 稿）；尺寸由父级短边决定，glyphSizeDp 不再参与。
            VinylDiscMotif(Modifier.fillMaxSize())
        } else {
            CoverPlaceholderGlyph(glyph, glyphSizeDp)
        }
    }
}

/**
 * 封面到达淡入（v8 稿加载语言：内容到达 260ms 淡入，cubic-bezier(.2,.8,.2,1) 近似 FastOutSlowIn）。
 * Coil crossfade 对内存缓存命中不生效，故在 Compose 侧以 onSuccess 驱动 alpha，保证逐图一致淡入；
 * 「减少动效」时直接全显。列表长行已有请求级 crossfade(150)（ListRowPerf），本组件供重点封面位使用。
 */
@Composable
fun FadingCoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fadeMs: Int = 260,
    onError: (() -> Unit)? = null
) {
    val reduce = MotionPolicy.rememberReduceMotion()
    var revealed by remember(model) { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            if (reduce) 0 else fadeMs,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "coverFadeIn"
    )
    coil.compose.AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.alpha(alpha),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        onSuccess = { revealed = true },
        onError = { onError?.invoke() }
    )
}

/** Explicit missing-art state; it never resembles a real provider cover. */
@Composable
fun DefaultLandscapeCover(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.fillMaxSize()
            .semantics { contentDescription = "暂无封面" },
        contentAlignment = Alignment.Center
    ) {
        val showLabel = maxWidth >= 72.dp && maxHeight >= 72.dp
        if (showLabel) {
            CoverPlaceholderBase(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 母题盘径 = 盒短边 × 47%（稿比例）；96dp 盒 → 盘 ≈45dp，与旧字形盘视觉量级相当。
                    Box(Modifier.size(96.dp)) { VinylDiscMotif(Modifier.fillMaxSize()) }
                    Text(
                        "暂无封面",
                        color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                            SoundwaveDesignPalette.NightMuted
                        } else {
                            SoundwaveDesignPalette.Muted
                        },
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        } else {
            DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 20)
        }
    }
}
