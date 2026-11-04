package xyz.cncnlp.soundwave

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
fun appSliderColors(): SliderColors {
    val primary = MaterialTheme.colorScheme.primary
    return SliderDefaults.colors(
        thumbColor = primary,
        activeTrackColor = primary,
        inactiveTrackColor = primary.copy(alpha = 0.22f)
    )
}

/**
 * 设置页圆点进度条（非 Material 竖条拇指）。
 * 不走 experimental Slider thumb API，避免 CI 失败。
 */
@Composable
fun AppRoundSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionHeight: androidx.compose.ui.unit.Dp = 28.dp,
    /** r657 第三轮第 34 项：睡眠定时等滑杆可指定珊瑚色；默认沿用主题 primary。 */
    accent: Color? = null,
    /** 档位刻度数：>1 时在轨道下方按档位位置画刻度短线（与拇指中心行程对齐）；0 = 不画（默认，其他滑杆零变化）。 */
    tickSteps: Int = 0,
) {
    val primary = accent ?: MaterialTheme.colorScheme.primary
    val trackInactive = primary.copy(alpha = 0.22f)
    val latest by rememberUpdatedState(value)
    val rangeStart = valueRange.start
    val rangeEnd = valueRange.endInclusive
    val span = (rangeEnd - rangeStart).takeIf { it > 0f } ?: 1f
    fun toRatio(v: Float) = ((v - rangeStart) / span).coerceIn(0f, 1f)
    fun fromRatio(r: Float) = rangeStart + r.coerceIn(0f, 1f) * span
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(interactionHeight)
            .then(
                if (enabled) Modifier.pointerInput(rangeStart, rangeEnd) {
                    detectTapGestures { o ->
                        onValueChange(fromRatio(o.x / size.width.coerceAtLeast(1)))
                    }
                }.pointerInput(rangeStart, rangeEnd) {
                    var local = latest
                    detectHorizontalDragGestures(
                        onDragStart = { local = latest },
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            if (size.width > 0) {
                                val r = toRatio(local) + drag / size.width
                                local = fromRatio(r)
                                onValueChange(local)
                            }
                        }
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        val ratio = toRatio(value)
        val thumb = 14.dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackInactive)
        )
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceAtLeast(0.001f))
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (enabled) primary else primary.copy(alpha = 0.4f))
        )
        if (tickSteps > 1) {
            // 刻度与档位序号一一对应：位置 = 拇指在该档的中心，与吸附落点同几何。
            val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            for (i in 0 until tickSteps) {
                val tickRatio = i.toFloat() / (tickSteps - 1)
                Box(
                    Modifier
                        .offset(x = (maxWidth - thumb) * tickRatio + thumb / 2 - 0.5.dp, y = 8.dp)
                        .size(1.dp, 5.dp)
                        .background(tickColor)
                )
            }
        }
        Box(
            Modifier
                .offset(x = (maxWidth - thumb) * ratio)
                .size(thumb)
                .clip(CircleShape)
                .background(if (enabled) primary else primary.copy(alpha = 0.5f))
        )
    }
}

@Composable
fun SettingsSubHeader(title: String, hint: String = "") {
    val onBg = MaterialTheme.colorScheme.onBackground
    // 所有管理页与设置分类共用 56dp 顶部锚点：提示有无、行数变化都不能带动下方内容。
    Column(
        Modifier
            .fillMaxWidth()
            .height(SettingsMetrics.RowH.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = onBg, fontWeight = FontWeight.Bold, fontSize = SettingsMetrics.SubTitleSp.sp, maxLines = 1)
        if (hint.isNotBlank()) {
            Text(hint, color = onBg.copy(alpha = 0.55f), fontSize = SettingsMetrics.SubHintSp.sp, maxLines = 2)
        }
    }
}
