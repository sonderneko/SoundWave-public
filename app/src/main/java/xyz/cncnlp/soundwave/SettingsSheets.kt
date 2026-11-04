package xyz.cncnlp.soundwave

import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ===== 设置卡片 + 睡眠定时 + 歌单封面选择 + 选项底栏 + 输入框配色（自 MainActivity 迁入；private→internal） =====
@Composable
internal fun SettingCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(subtitle.takeIf { it.isNotEmpty() && title in listOf("主题", "播放音质", "下载音质", "定时关闭") }?.let { "" } ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SleepTimerBarDialog(remainMs: Long, onMinutes: (Int) -> Unit, onDismiss: () -> Unit) {
    xyz.cncnlp.soundwave.ui.settings.SleepTimerSheet(remainMs = remainMs, onApply = onMinutes, onDismiss = onDismiss)
}

@Composable
internal fun AddPlaylistCoverSheet(
    title: String,
    playlists: List<Playlist>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val on = MaterialTheme.colorScheme.onSurface
    // 对话框清扫：悬浮白卡 Dialog 改 DesignSheet 弹层（滚动改由弹层承接）；歌单行与「新建歌单」语义逐字不变。
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // 收尾棒 J8 句柄补齐：点行先执行 onPick、再走关闭句柄退场（清状态归 onDismiss）；
        // 调用方 onPick 不再自行清可见状态（配对改动在 MainActivity 调用点）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Playlist", title)
        Spacer(Modifier.height(6.dp))
        playlists.forEach { pl ->
            val cover = playlistDisplayCover(pl)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(pl.id); dismiss() }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是「♪」文本、加载时只剩 primary 12% 底）。
                    DefaultCoverPlaceholder(Modifier.fillMaxSize())
                    if (cover.isNotEmpty()) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name.ifEmpty { pl.id }, color = on, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                    Text("${pl.songs.size} 首", color = on.copy(alpha = 0.62f), fontSize = 12.sp)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick("__new__"); dismiss() }.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Text("＋", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text("新建歌单", color = on, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        xyz.cncnlp.soundwave.ui.common.SheetActionButtons(secondary = "取消", onSecondary = dismiss, primary = null)
    }
}

internal enum class ChoiceOptionColorRole { PRIMARY, SECONDARY, TERTIARY, ERROR }

internal data class ChoiceOptionStyle(
    val glyph: String,
    val colorRole: ChoiceOptionColorRole
)

internal fun choiceOptionStyle(key: String): ChoiceOptionStyle = when (key) {
    "128k" -> ChoiceOptionStyle("menu_quality", ChoiceOptionColorRole.PRIMARY)
    "320k" -> ChoiceOptionStyle("menu_speed", ChoiceOptionColorRole.SECONDARY)
    "flac" -> ChoiceOptionStyle("menu_wave", ChoiceOptionColorRole.TERTIARY)
    "flac24bit" -> ChoiceOptionStyle("menu_bits", ChoiceOptionColorRole.SECONDARY)
    "hires" -> ChoiceOptionStyle("menu_spark", ChoiceOptionColorRole.TERTIARY)
    "master" -> ChoiceOptionStyle("menu_master", ChoiceOptionColorRole.PRIMARY)
    "false" -> ChoiceOptionStyle("menu_hide", ChoiceOptionColorRole.SECONDARY)
    "true" -> ChoiceOptionStyle("menu_spark", ChoiceOptionColorRole.PRIMARY)
    "default" -> ChoiceOptionStyle("menu_star", ChoiceOptionColorRole.PRIMARY)
    "old" -> ChoiceOptionStyle("menu_timer", ChoiceOptionColorRole.SECONDARY)
    "name" -> ChoiceOptionStyle("menu_edit", ChoiceOptionColorRole.TERTIARY)
    "singer" -> ChoiceOptionStyle("menu_music", ChoiceOptionColorRole.PRIMARY)
    "like_sel" -> ChoiceOptionStyle("heart_filled", ChoiceOptionColorRole.SECONDARY)
    "next" -> ChoiceOptionStyle("menu_next", ChoiceOptionColorRole.TERTIARY)
    "add_sel" -> ChoiceOptionStyle("menu_playlist", ChoiceOptionColorRole.PRIMARY)
    "download_sel" -> ChoiceOptionStyle("menu_download", ChoiceOptionColorRole.SECONDARY)
    "remove_sel" -> ChoiceOptionStyle("menu_delete", ChoiceOptionColorRole.ERROR)
    "" -> ChoiceOptionStyle("menu_settings", ChoiceOptionColorRole.PRIMARY)
    "wy" -> ChoiceOptionStyle("menu_wave", ChoiceOptionColorRole.SECONDARY)
    "tx" -> ChoiceOptionStyle("menu_music", ChoiceOptionColorRole.TERTIARY)
    "kg" -> ChoiceOptionStyle("menu_spark", ChoiceOptionColorRole.PRIMARY)
    "kw" -> ChoiceOptionStyle("menu_speed", ChoiceOptionColorRole.SECONDARY)
    "mg" -> ChoiceOptionStyle("menu_atmos", ChoiceOptionColorRole.TERTIARY)
    "100" -> ChoiceOptionStyle("menu_quality", ChoiceOptionColorRole.PRIMARY)
    "200" -> ChoiceOptionStyle("menu_speed", ChoiceOptionColorRole.SECONDARY)
    "500" -> ChoiceOptionStyle("menu_wave", ChoiceOptionColorRole.TERTIARY)
    "1024" -> ChoiceOptionStyle("menu_master", ChoiceOptionColorRole.PRIMARY)
    "2048" -> ChoiceOptionStyle("menu_bits", ChoiceOptionColorRole.SECONDARY)
    "5120" -> ChoiceOptionStyle("menu_spark", ChoiceOptionColorRole.TERTIARY)
    "0" -> ChoiceOptionStyle("menu_atmos", ChoiceOptionColorRole.PRIMARY)
    else -> error("ChoiceSheet option key has no explicit visual mapping: $key")
}

internal fun choiceOptionTint(role: ChoiceOptionColorRole, colors: ColorScheme) = when (role) {
    ChoiceOptionColorRole.PRIMARY -> colors.primary
    ChoiceOptionColorRole.SECONDARY -> colors.secondary
    ChoiceOptionColorRole.TERTIARY -> colors.tertiary
    ChoiceOptionColorRole.ERROR -> colors.error
}

@Composable
internal fun ChoiceSheet(
    title: String,
    opts: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    extraActionLabel: String = "",
    onExtraAction: () -> Unit = {}
) {
    // r658f（用户 10-20，1000073712：设置里「下载音质」等选项弹窗与「歌曲操作」风格不统一）：
    // 改用全 App 统一设计抽屉 DesignSheetDialog（同一面板、进出场、遮罩与窗口铺满处理）+ 珊瑚眉题 + 粗体标题 +
    // 56dp 单列选项行（分隔线、线性图标、Manrope 粗体、当前项珊瑚勾），不再是双列卡片 + 衬线标题。
    var closing by remember { mutableStateOf(false) }
    val sheetScope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val sheetBg = colors.surface
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // J 波：选项点击保留原有 120ms 选中反馈延时，之后改走动画关闭句柄（退场后才清状态）；
        // 调用方 onPick 只执行动作、不再清状态（原 closing 延时后即刻卸载的做法退场仍被跳过）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Settings", title)
        Spacer(Modifier.height(14.dp))
        opts.forEach { (k, l) ->
            val style = choiceOptionStyle(k)
            val selected = current == k
            val optionTint = colors.onSurface
            Column(Modifier.fillMaxWidth()) {
                xyz.cncnlp.soundwave.ui.common.FrostDivider(xyz.cncnlp.soundwave.ui.common.designLineColor())
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).height(56.dp)
                        .clickable(enabled = !closing) {
                            closing = true
                            sheetScope.launch { delay(120); onPick(k); dismiss() }
                        }
                        .padding(start = 4.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WhiteGlyph(style.glyph, 21.dp, if (selected) SoundwaveDesignPalette.Accent else optionTint)
                    Spacer(Modifier.width(18.dp))
                    Text(
                        l, color = if (selected) SoundwaveDesignPalette.Accent else colors.onSurface, fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (selected) Icon(xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Check, "当前选项", tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (extraActionLabel.isNotEmpty()) {
            xyz.cncnlp.soundwave.ui.common.FrostDivider(xyz.cncnlp.soundwave.ui.common.designLineColor())
            Text(
                extraActionLabel, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
                modifier = Modifier.fillMaxWidth().clickable { onExtraAction(); dismiss() }.padding(start = 4.dp, top = 16.dp, bottom = 12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
