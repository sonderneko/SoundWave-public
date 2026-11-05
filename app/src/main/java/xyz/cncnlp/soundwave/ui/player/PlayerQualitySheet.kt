package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.USER_AUDIO_QUALITY_OPTIONS
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.DesignSheetItemHeader
import xyz.cncnlp.soundwave.ui.common.designLineColor

/** 每档音质的等宽英文注脚（设计稿 quality-pill 文案族）。 */
internal fun playerQualityTag(key: String): String = when (key) {
    "master" -> "MASTER"
    "hires" -> "HI-RES"
    "flac24bit" -> "24 BIT"
    "flac" -> "LOSSLESS"
    "320k" -> "320 KBPS"
    "128k" -> "128 KBPS"
    else -> key.uppercase()
}

/**
 * r657 第二轮（音质抽屉码率 + 大小）：只有恒定码率档（320K / 128K）能由歌曲时长真实推算文件大小；
 * 无损 / Hi-Res / 母带为可变码率，音源不返回各档大小，故不显示（不编造）。时长未知时返回空。
 */
internal fun playerQualitySizeHint(key: String, durationMs: Long): String {
    val kbps = when (key) { "320k" -> 320; "128k" -> 128; else -> return "" }
    if (durationMs < 1_000L) return ""
    val mb = kbps * 1000.0 / 8.0 * (durationMs / 1000.0) / (1024.0 * 1024.0)
    return "≈ " + String.format(java.util.Locale.ROOT, "%.1f", mb) + " MB"
}

/**
 * r656：播放器点按音质胶囊弹出的「播放音质」抽屉，统一设计风格：
 * 珊瑚 DM Mono 眉题 AUDIO QUALITY · 当前歌曲 + 24sp 标题 → 当前歌曲条目头 → 竖排单选列表（r657 原型），
 * 当前档位珊瑚文字 + 珊瑚实心单选圆 → 灰色说明。外壳与「⋯ 歌曲操作」抽屉共用 DesignSheetDialog。
 */
@Composable
internal fun PlayerQualitySheet(
    song: Song?,
    current: String,
    actualLabel: String = "",
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    DesignSheetDialog(onDismiss = onDismiss) { PlayerQualitySheetContent(song, current, actualLabel, onPick) }
}

/** 抽屉内容（可单独渲染供截图测试）。 */
@Composable
internal fun ColumnScope.PlayerQualitySheetContent(song: Song?, current: String, actualLabel: String, onPick: (String) -> Unit) {
    // ACCEPT4（审计 §9「双重坏」修复）：内容侧补退场句柄——选档先执行 onPick 再走句柄退场；
    // 宿主 onPick 不再同步关抽屉（原两侧都同步清状态，退出动画从未播过）。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { }
    // r657：原型 sheet-quality——眉题 AUDIO QUALITY · 当前歌曲，档位改竖排单选列表（分隔线 + 右侧单选圆）。
    DesignSheetHeader("Audio quality · 当前歌曲", "播放音质")
    if (song != null) {
        val currentLabel = USER_AUDIO_QUALITY_OPTIONS.firstOrNull { it.first == current }?.second ?: current
        val subtitle = if (actualLabel.isNotBlank()) "正在播放 ${actualLabel.uppercase()} · 偏好 $currentLabel" else "${song.singer.ifBlank { "未知歌手" }} · 偏好 $currentLabel"
        DesignSheetItemHeader(song.cover, song.name, subtitle)
    } else Spacer(Modifier.height(18.dp))
    USER_AUDIO_QUALITY_OPTIONS.forEach { (key, label) ->
        Box1dpLine()
        val sizeHint = playerQualitySizeHint(key, song?.duration ?: 0L)
        PlayerQualityOption(label, playerQualityTag(key) + if (sizeHint.isNotEmpty()) " · $sizeHint" else "", current == key, Modifier.fillMaxWidth()) { onPick(key); dismiss() }
    }
    Box1dpLine()
    Spacer(Modifier.height(12.dp))
    Text(
        "切换后将重新解析当前歌曲；实际音质以音源返回为准，不支持的档位会自动降级。",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = DesignFonts.Manrope,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
    )
}

@Composable
private fun Box1dpLine() = androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(designLineColor())))

@Composable
private fun PlayerQualityOption(label: String, tag: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    Row(
        modifier.height(54.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label${if (selected) "，当前音质" else ""}"; role = Role.RadioButton; this.selected = selected }
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) accent else colors.onSurface, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(tag, color = colors.onSurfaceVariant, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, maxLines = 1, modifier = Modifier.weight(1f))
        if (selected) {
            androidx.compose.foundation.layout.Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(DesignLineGlyphs.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        } else {
            androidx.compose.foundation.layout.Box(Modifier.size(18.dp).border(1.5.dp, designLineColor(), androidx.compose.foundation.shape.CircleShape))
        }
    }
}
