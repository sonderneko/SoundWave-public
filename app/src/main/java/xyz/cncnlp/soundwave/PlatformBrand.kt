package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===== 平台品牌标记 + 平台名/色映射（自 MainActivity 迁入；private→internal 保持包内可见） =====
@Composable
internal fun PlatformBrandMark(code: String, tint: Color = Color.White) {
    // 统一单色线性平台标记（设计稿线性风格，见 PlatformLineGlyphs）。
    androidx.compose.material3.Icon(
        xyz.cncnlp.soundwave.ui.common.PlatformLineGlyphs.of(code), contentDescription = null, tint = tint,
        modifier = Modifier.size(24.dp)
    )
}

@Composable
internal fun UnifiedTopGlyph(tint: Color) {
    // 顶栏左：平台菜单 — 三横条 + 右侧错位点（区别底栏设置三滑块）
    Canvas(Modifier.size(24.dp)) {
        val c = tint
        val sw = 2.05.dp.toPx()
        drawLine(c, Offset(size.width*0.14f, size.height*0.28f), Offset(size.width*0.70f, size.height*0.28f), sw, StrokeCap.Round)
        drawCircle(c, size.width*0.075f, Offset(size.width*0.82f, size.height*0.28f))
        drawLine(c, Offset(size.width*0.14f, size.height*0.50f), Offset(size.width*0.56f, size.height*0.50f), sw, StrokeCap.Round)
        drawCircle(c, size.width*0.075f, Offset(size.width*0.70f, size.height*0.50f))
        drawLine(c, Offset(size.width*0.14f, size.height*0.72f), Offset(size.width*0.64f, size.height*0.72f), sw, StrokeCap.Round)
        drawCircle(c, size.width*0.075f, Offset(size.width*0.78f, size.height*0.72f))
    }
}

@Composable
internal fun PageTitleGlyph(title: String, tint: Color) {
    UnifiedTopGlyph(tint)
}

@Composable
internal fun PrettyPlatformRow(
    platforms: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit
) {
    // 主页/搜索共用：固定位置与边距，切换页看起来不动
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(48.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        platforms.forEach { (k, label) ->
            val on = current == k
            val c = platformColor(k)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPick(k) }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(99.dp)).background(c),
                    contentAlignment = Alignment.Center
                ) { PlatformBrandMark(k) }
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    color = if (on) c else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(if (on) 22.dp else 0.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (on) c else Color.Transparent)
                )
            }
        }
    }
}

@Composable
internal fun PlatformCycleGlyphButton(
    platforms: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit
) {
    val currentIndex = platforms.indexOfFirst { it.first == current }
    val next = platforms.getOrNull(if (currentIndex < 0) 0 else (currentIndex + 1) % platforms.size)
    Box(
        Modifier
            .size(42.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = next?.let { "切换至${it.second}" } ?: "切换来源",
                onClick = { next?.let { onPick(it.first) } }
            ),
        contentAlignment = Alignment.Center
    ) {
        PlatformBrandMark(current, tint = platformColor(current))
    }
}

internal fun smartPlats(): List<String> = try {
    SmartSourceStore.enabledPlatformsInOrder().ifEmpty { listOf("wy","kg","kw","tx","mg") }
} catch (_: Exception) { listOf("wy","kg","kw","tx","mg") }

internal fun platformName(src: String): String = when (src.lowercase()) {
    "wy", "netease" -> "网易云"
    "kg", "kugou" -> "酷狗"
    "kw", "kuwo" -> "酷我"
    "tx", "qq" -> "QQ"
    "mg", "migu" -> "咪咕"
    "local" -> "本地"
    else -> src
}

internal fun platformColor(src: String): Color = when (src.lowercase()) {
    "wy", "netease" -> Color(0xFFE53935.toInt())
    "kg", "kugou" -> Color(0xFF147BFF.toInt())
    "kw", "kuwo" -> Color(0xFFFF9800.toInt())
    "tx", "qq" -> Color(0xFF1DB954.toInt())
    "mg", "migu" -> Color(0xFFFF5BA7.toInt())
    else -> Color(0xFF8A8A96.toInt())
}

@Composable
internal fun TinyPlatformChip(src: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) platformColor(src) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
