package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.USER_AUDIO_QUALITY_OPTIONS
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.download.AudioQualityProbe
import xyz.cncnlp.soundwave.download.DownloadQualityPresentation

/** Download-quality overlay; all size/format rows come from observed resolver and HTTP response facts. */
@Composable
internal fun DownloadQualityDialog(
    song: Song,
    probes: Map<String, AudioQualityProbe>,
    selectedQuality: String,
    onQualityChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // r658b 第 13 项（用户 10-19，1000073571）：改用统一设计抽屉外壳（同「歌曲操作」：r28 面板、进出场动效、
    // 遮罩盖住状态栏），表头 = 珊瑚眉题 + 大标题 + 条目头。
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        DownloadQualitySheetContent(song, probes, selectedQuality, onQualityChange, onConfirm, onDismiss)
    }
}

/** 抽屉内容（可在 DesignSheetPanel 中单独渲染供截图测试）。 */
@Composable
internal fun DownloadQualitySheetContent(
    song: Song,
    probes: Map<String, AudioQualityProbe>,
    selectedQuality: String,
    onQualityChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    run {
        // J 波：底部「取消/下载」走动画关闭句柄（动作先执行，退场后才由 onDismiss 清状态）。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        val qualityRows = USER_AUDIO_QUALITY_OPTIONS
        val allProbes = qualityRows.mapNotNull { probes[it.first] }
        val warnings = allProbes.associate { probe ->
            probe.requestedQuality to AudioQualityEvidence.suspectedDowngrade(probe, allProbes)
        }
        val suspicious = qualityRows.filter { warnings[it.first] != null }
        val other = qualityRows.filter { warnings[it.first] == null }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
                    DesignSettingSheetHeader("Download", "下载歌曲")
                    DesignSheetItemHeader(song.cover, song.name, song.singer.ifBlank { "未知歌手" })

                    if (suspicious.isNotEmpty()) {
                        QualityGroupHeader("疑似降级", "仅在档位、格式或同曲对比有明显证据时标记", warning = true)
                        suspicious.forEach { (quality, label) ->
                            QualityRow(
                                quality = quality,
                                label = label,
                                probe = probes[quality],
                                warning = warnings[quality],
                                selected = selectedQuality == quality,
                                onClick = { DownloadQualityPresentation.selectRequestedQuality(quality, onQualityChange) }
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                    }

                    QualityGroupHeader(if (suspicious.isEmpty()) "音质选项" else "其他音质选项")
                    other.forEach { (quality, label) ->
                        QualityRow(
                            quality = quality,
                            label = label,
                            probe = probes[quality],
                            warning = null,
                            selected = selectedQuality == quality,
                            onClick = { DownloadQualityPresentation.selectRequestedQuality(quality, onQualityChange) }
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    val downloadEnabled = selectedQuality.isNotBlank()
                    // r658b 第 13 项：底部按钮同设计抽屉——取消 = 表面色描边胶囊，下载 = 珊瑚红胶囊（此前取主题 primary 显示为蓝色）。
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier.weight(1f).height(50.dp).pressScale(PressMotion.SCALE_ROW).clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, designLineColor(), RoundedCornerShape(999.dp))
                                .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = dismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = DesignFonts.Manrope)
                        }
                        Box(
                            Modifier.weight(1f).height(50.dp).pressScale(PressMotion.SCALE_ROW, enabled = downloadEnabled).clip(RoundedCornerShape(999.dp))
                                .background(if (downloadEnabled) DownloadConfirmFill else MaterialTheme.colorScheme.surfaceVariant)
                                .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f), enabled = downloadEnabled)
                                .clickable(enabled = downloadEnabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onConfirm(selectedQuality); dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "下载",
                                color = if (downloadEnabled) DownloadConfirmInk else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                fontFamily = DesignFonts.Manrope
                            )
                        }
                    }
        }
    }
}

/** r658b 第 13 项：下载确认按钮 = 设计强调色（珊瑚红）+ 白字。 */
private val DownloadConfirmFill: Color get() = SoundwaveDesignPalette.Accent
private val DownloadConfirmInk = Color(0xFFFFFFFF)

// r658f（用户 10-20：下载音质弹窗没和整体风格统一）：分组头 = DM Mono 小眉题；选项行 = 设计抽屉选项行
// （56dp、1dp 分隔线、15sp Manrope 粗体、右侧 12sp 灰色说明、当前项珊瑚勾），去掉彩色单选圈。
@Composable
private fun QualityGroupHeader(title: String, detail: String? = null, warning: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        Text(
            title,
            color = if (warning) Color(0xFFB85B13.toInt()) else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = DesignFonts.DmMono,
            letterSpacing = 0.12.em
        )
        detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun QualityRow(
    quality: String,
    label: String,
    probe: AudioQualityProbe?,
    warning: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val qualityInteractionSource = remember { MutableInteractionSource() }
    val warningColor = Color(0xFFB85B13.toInt())
    val sizeText = DownloadQualityPresentation.trailingStatus(probe)
    val warningText = DownloadQualityPresentation.compactDowngradeReason(warning)
    Column(Modifier.fillMaxWidth()) {
        FrostDivider(designLineColor())
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(PressMotion.SCALE_ROW)
                .clip(RoundedCornerShape(12.dp))
                // J 波裁定：下载相关选中一律无整行填充（与 E1 下载管理同口径），
                // 选中指示只靠珊瑚字 + 右侧 ✓；clip 保留给 rippleGlow 裁形。
                .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.22f))
                .selectable(selected = selected, interactionSource = qualityInteractionSource, indication = null, role = Role.RadioButton, onClick = onClick)
                .padding(start = 4.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, color = if (selected) SoundwaveDesignPalette.Accent else colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (sizeText.isNotBlank()) Text(
                    sizeText, color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 10.dp).widthIn(max = 120.dp)
                )
                Box(Modifier.padding(start = 10.dp).size(20.dp), contentAlignment = Alignment.Center) {
                    if (selected) androidx.compose.material3.Icon(DesignLineGlyphs.Check, null, tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(18.dp))
                }
            }
            if (warningText != null) {
                Text(warningText, color = warningColor, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

internal fun downloadQualityAccent(quality: String): Color = when (quality) {
    "master" -> Color(0xFF8A3324.toInt())
    "atmos" -> Color(0xFF0F766E.toInt())
    "hires" -> Color(0xFFFF5C45.toInt())
    "flac24bit" -> Color(0xFFB45309.toInt())
    "flac" -> Color(0xFF15803D.toInt())
    "320k" -> Color(0xFFBE123C.toInt())
    "128k" -> Color(0xFF57504A.toInt())
    else -> Color(0xFF6E6A63.toInt())
}

private fun downloadPlatformName(platform: String) = when (platform.lowercase()) {
    "wy" -> "网易云"
    "kw" -> "酷我"
    "kg" -> "酷狗"
    "tx" -> "QQ音乐"
    "mg" -> "咪咕"
    else -> platform
}
