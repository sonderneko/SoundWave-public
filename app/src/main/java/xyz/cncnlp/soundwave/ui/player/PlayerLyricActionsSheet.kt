package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.ThemeTokens.LyricAction as ActionTokens

import xyz.cncnlp.soundwave.LyricCorrectionCandidate
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.lyricCorrectionDurationMs
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.height
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss
import xyz.cncnlp.soundwave.ui.common.SheetActionButtons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.cncnlp.soundwave.ui.common.VinylDiscSpinner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 长按歌词「歌词操作」抽屉（r658c P）：外壳改为公共 DesignSheetDialog（贴屏幕底边、自底部滑入，与其它抽屉一致，
 * 不再用 Material3 ModalBottomSheet）；字号 / 行高 / 偏移与「⋯ → 歌词设置」、设置页共用同一组偏好，所有播放器样式都显示。
 */
@Composable
internal fun PlayerLyricActionsSheet(
    selectedLine: LyricLine?,
    showTranslation: Boolean,
    showRomanization: Boolean,
    hasLyricCorrection: Boolean,
    onDismiss: () -> Unit,
    onCorrection: () -> Unit,
    onToggleTranslation: () -> Unit,
    onToggleRomanization: () -> Unit,
    lyricFontSp: Int,
    lyricGapDp: Int,
    lyricBlurDp: Int,
    lyricOffset: Int,
    onLyricFont: (Int) -> Unit,
    onLyricGap: (Int) -> Unit,
    onLyricBlur: (Int) -> Unit,
    onLyricOffset: (Int) -> Unit,
    onToggleFloating: (() -> Unit)? = null,
    /** SW-07-10（G 波）：空歌词长按打开的实例传入——网格首位出现「重新获取」；行选实例不传，保持原样。 */
    onRefetch: (() -> Unit)? = null
) {
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // ACCEPT5（退出动画普查 P9）：网格动作统一「动作→退场句柄」；宿主动作回调已去同步清状态，
        // 显隐清理由退场后的 onDismiss 承担（此前宿主点选即清状态、组合即刻移除、无退场）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ActionTokens.RowGap)) {
            xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("LYRICS", "歌词操作")
            selectedLine?.let { line ->
                val colors = MaterialTheme.colorScheme
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(colors.primary.copy(alpha = 0.055f))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前歌词", color = xyz.cncnlp.soundwave.designAccentTextColor(), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        playerLyricTimeLabel(line.timeMs)?.let { timestamp ->
                            Text(timestamp, color = colors.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                    Text(line.text, color = colors.onSurface, fontSize = 14.sp, lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val hasTranslation = !selectedLine?.translation.isNullOrBlank()
            val hasRomanization = !selectedLine?.romanization.isNullOrBlank()
            val correctionAction = LyricGridAction("歌词纠错", if (hasLyricCorrection) "管理当前歌词修正" else "查找更准确的歌词", Icons.Outlined.EditNote, true, hasLyricCorrection, if (hasLyricCorrection) "已修正" else null) { onCorrection(); dismiss() }
            // SW-07-10（G 波）：无选中行（空歌词长按）时只给整首歌级动作——重新获取 + 纠错；
            // 翻译/罗马音是逐句动作，无行时不出现，避免空态里出现「该句暂无译文」的误导文案。
            val actions = if (selectedLine == null) {
                listOfNotNull(
                    onRefetch?.let { refetch -> LyricGridAction("重新获取", "重新获取当前歌曲歌词", Icons.Outlined.Refresh, true, false, null) { refetch(); dismiss() } },
                    correctionAction
                )
            } else {
                listOfNotNull(
                    correctionAction,
                    LyricGridAction("翻译", when { !hasTranslation -> "该句暂无译文"; showTranslation -> "同时显示歌词译文"; else -> "显示已有歌词翻译" }, Icons.Outlined.Translate, hasTranslation, hasTranslation && showTranslation, if (hasTranslation && showTranslation) "已开启" else null) { onToggleTranslation(); dismiss() },
                    LyricGridAction("罗马音", when { !hasRomanization -> "该句暂无罗马音"; showRomanization -> "辅助跟唱与发音"; else -> "显示已有罗马音" }, Icons.Outlined.RecordVoiceOver, hasRomanization, hasRomanization && showRomanization, if (hasRomanization && showRomanization) "已开启" else null) { onToggleRomanization(); dismiss() },
                    onToggleFloating?.let { floating -> LyricGridAction("悬浮歌词", "在其它应用上方显示", Icons.Outlined.PictureInPictureAlt, true, false, null) { floating(); dismiss() } }
                )
            }
            actions.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ActionTokens.ColumnGap)) {
                    pair.forEach { action ->
                        LyricActionItem(action, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            val fg = MaterialTheme.colorScheme.onSurface
            Text("显示与排版", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            // 设置核心段与 ⋯ 菜单「歌词设置」共用 PlayerLyricsSettingsCore（2026-11-04 合并，只此一份；
            // 本表的当前歌词卡与行操作段为长按入口专属，留在本文件）。
            xyz.cncnlp.soundwave.PlayerLyricsSettingsCore(
                lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricOffset = lyricOffset, fg = fg,
                onLyricFont = onLyricFont, onLyricGap = onLyricGap, onLyricOffset = onLyricOffset
            )
        }
    }
}

private data class LyricGridAction(
    val title: String, val detail: String, val icon: ImageVector,
    val enabled: Boolean, val selected: Boolean, val status: String?, val onClick: () -> Unit
)

@Composable
private fun LyricActionItem(action: LyricGridAction, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) ActionTokens.PressedScale else 1f, tween(ActionTokens.PressMs), label = "lyricActionPress")
    val shape = RoundedCornerShape(ActionTokens.Radius)
    val ink = ActionTokens.ink(colors, action.selected)
    val fill = ActionTokens.fill(colors, action.selected)
    Column(
        modifier.fillMaxWidth().heightIn(min = ActionTokens.MinHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (action.enabled) 1f else ActionTokens.DisabledAlpha }
            .shadow(if (pressed) ActionTokens.PressedShadow else ActionTokens.RaisedShadow, shape, ambientColor = colors.onSurface.copy(alpha = ActionTokens.ShadowAmbientAlpha), spotColor = colors.onSurface.copy(alpha = ActionTokens.ShadowSpotAlpha))
            .clip(shape).background(fill)
            .border(ActionTokens.Border, ActionTokens.border(colors, action.selected), shape)
            .semantics { selected = action.selected }
            .clickable(enabled = action.enabled, role = Role.Button, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = action.onClick)
            .padding(horizontal = ActionTokens.HorizontalPadding, vertical = ActionTokens.VerticalPadding),
        verticalArrangement = Arrangement.spacedBy(ActionTokens.ContentGap)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(ActionTokens.IconContainer), contentAlignment = Alignment.Center) {
                Icon(action.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(ActionTokens.IconSize))
            }
            Spacer(Modifier.weight(1f))
            action.status?.let { status ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(12.dp))
                    Text(status, color = xyz.cncnlp.soundwave.designAccentTextColor(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(action.title, color = ink, fontSize = ActionTokens.TitleSize, fontWeight = FontWeight.Bold,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope)
        Text(action.detail, color = colors.onSurfaceVariant, fontSize = ActionTokens.DetailSize, lineHeight = ActionTokens.DetailLineHeight,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PlayerLyricCorrectionDialog(
    song: Song,
    correctionCandidates: List<LyricCorrectionCandidate>,
    correctionSearching: Boolean,
    correctionMessage: String,
    hasLyricCorrection: Boolean,
    selectedCandidateIndex: Int,
    onSelectCandidate: (Int) -> Unit,
    onSearchLyricCorrection: () -> Unit,
    onApplyLyricCorrection: (LyricCorrectionCandidate) -> Boolean,
    onClearLyricCorrection: () -> Boolean,
    onDismiss: () -> Unit
) {
    // 对话框清扫：AlertDialog 白框改 DesignSheet 弹层（滚动改由弹层承接，原内层限高滚动随之移除）；
    // 候选行、搜索/应用/清除语义逐字不变。
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // 收尾棒 J8 句柄补齐：应用/清除成功后与取消均走关闭句柄退场（动作即时执行、清状态归 onDismiss）；
        // 「重新搜索」不关弹层，不动。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        Box(Modifier.semantics { heading() }) { DesignSheetHeader("Lyrics", "歌词纠错候选") }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
                Text("${song.name} · ${song.singer.ifBlank { "未知歌手" }}", fontSize = 13.sp)
                if (correctionSearching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VinylDiscSpinner(16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在按当前来源查找并核对歌词…", fontSize = 12.sp)
                    }
                }
                if (correctionMessage.isNotBlank()) {
                    Text(correctionMessage, color = Color(0xFFB3261E), fontSize = 12.sp)
                }
                correctionCandidates.forEachIndexed { index, candidate ->
                    val selected = index == selectedCandidateIndex
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { onSelectCandidate(index) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectCandidate(index) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    candidate.song.name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "${candidate.song.singer.ifBlank { "未知歌手" }} · ${lyricCorrectionDurationLabel(candidate.song.duration)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Text(
                            if (candidate.exactNativeId) "平台原生 ID 精确命中 · 100%" else "标题/歌手/时长相似度 · ${(candidate.similarity * 100f).toInt()}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 44.dp)
                        )
                        Text(
                            lyricCorrectionPreview(candidate.lyrics),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 44.dp)
                        )
                    }
                }
                if (hasLyricCorrection) {
                    Text(
                        "清除修正，恢复默认歌词",
                        color = xyz.cncnlp.soundwave.SoundwaveDanger,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
                        modifier = Modifier.align(Alignment.Start)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { if (onClearLyricCorrection()) dismiss() }
                            .padding(vertical = 6.dp)
                    )
                }
        }
        SheetActionButtons(
            secondary = "重新搜索", onSecondary = {
                onSelectCandidate(-1)
                onSearchLyricCorrection()
            }, secondaryEnabled = !correctionSearching, primary = null
        )
        SheetActionButtons(
            secondary = "取消", onSecondary = dismiss,
            primary = "应用所选歌词",
            primaryEnabled = selectedCandidateIndex in correctionCandidates.indices && !correctionSearching,
            onPrimary = {
                correctionCandidates.getOrNull(selectedCandidateIndex)?.let { candidate ->
                    if (onApplyLyricCorrection(candidate)) dismiss()
                }
            }
        )
    }
}

private fun lyricCorrectionDurationLabel(durationMs: Long): String {
    val normalized = lyricCorrectionDurationMs(durationMs)
    if (normalized <= 0L) return "时长未知"
    val seconds = normalized / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun lyricCorrectionPreview(raw: String): String = raw.lineSequence()
    .map { it.trim().replace(Regex("^\\[\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?]"), "").trim() }
    .filter { it.isNotBlank() && !it.startsWith("[ti:", true) && !it.startsWith("[ar:", true) }
    .take(2)
    .joinToString(" / ")
    .ifBlank { "已取得有效歌词内容" }

/** r657：歌词操作里的「悬浮歌词」开关（无权限时跳到系统悬浮窗授权页）。 */
internal fun toggleFloatingLyric(ctx: android.content.Context) {
    val enable = !xyz.cncnlp.soundwave.LyricFloat.isUserEnabled(ctx)
    if (enable && !xyz.cncnlp.soundwave.LyricFloat.hasPermission(ctx)) {
        val openOverlaySettings: () -> Unit = {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        openOverlaySettings()
        AppNotice.attach(ctx.applicationContext)
        AppNotice.banner("请先开启悬浮窗权限", actionLabel = "去开启", onAction = openOverlaySettings, onClick = openOverlaySettings)
        return
    }
    xyz.cncnlp.soundwave.LyricFloat.toggleEnabled(ctx, enable)
}

/**
 * SW-07-10（G 波）抽取：行选框（selecting）与空歌词框（emptyOpen）两个 [PlayerLyricActionsSheet]
 * 实例的宿主——原在 FullPlayer 内联，player-default-mode / player-menu-sheet 守卫要求 FullPlayer
 * 保持 <1200 行，故接线收拢到本文件；两实例的参数与回调与内联版逐一对应，行为不变。
 */
@Composable
internal fun PlayerLyricActionSheets(
    selecting: Boolean,
    selectedLine: LyricLine?,
    emptyOpen: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    hasCorrection: Boolean,
    onDismissSelecting: () -> Unit,
    onDismissEmpty: () -> Unit,
    onCorrectionSelecting: () -> Unit,
    onCorrectionEmpty: () -> Unit,
    onToggleTranslationSelecting: () -> Unit,
    onToggleRomanizationSelecting: () -> Unit,
    onToggleTranslationEmpty: () -> Unit,
    onToggleRomanizationEmpty: () -> Unit,
    onRefetch: () -> Unit,
    lyricFontSp: Int,
    lyricGapDp: Int,
    lyricBlurDp: Int,
    lyricOffset: Int,
    onLyricFont: (Int) -> Unit,
    onLyricGap: (Int) -> Unit,
    onLyricBlur: (Int) -> Unit,
    onLyricOffsetDelta: (Int) -> Unit,
    onToggleFloatingSelecting: (() -> Unit)?,
    onToggleFloatingEmpty: (() -> Unit)?
) {
    if (selecting) {
        PlayerLyricActionsSheet(
            selectedLine = selectedLine,
            showTranslation = showTranslation,
            showRomanization = showRomanization,
            hasLyricCorrection = hasCorrection,
            onDismiss = onDismissSelecting,
            onCorrection = onCorrectionSelecting,
            onToggleTranslation = onToggleTranslationSelecting,
            onToggleRomanization = onToggleRomanizationSelecting,
            lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricBlurDp = lyricBlurDp, lyricOffset = lyricOffset,
            onLyricFont = onLyricFont, onLyricGap = onLyricGap, onLyricBlur = onLyricBlur,
            onLyricOffset = onLyricOffsetDelta,
            onToggleFloating = onToggleFloatingSelecting
        )
    }
    if (emptyOpen) {
        PlayerLyricActionsSheet(
            selectedLine = null,
            showTranslation = showTranslation,
            showRomanization = showRomanization,
            hasLyricCorrection = hasCorrection,
            onDismiss = onDismissEmpty,
            onCorrection = onCorrectionEmpty,
            onToggleTranslation = onToggleTranslationEmpty,
            onToggleRomanization = onToggleRomanizationEmpty,
            lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricBlurDp = lyricBlurDp, lyricOffset = lyricOffset,
            onLyricFont = onLyricFont, onLyricGap = onLyricGap, onLyricBlur = onLyricBlur,
            onLyricOffset = onLyricOffsetDelta,
            onToggleFloating = onToggleFloatingEmpty,
            onRefetch = onRefetch
        )
    }
}
