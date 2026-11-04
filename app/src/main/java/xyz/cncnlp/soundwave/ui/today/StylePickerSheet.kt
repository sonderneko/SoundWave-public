package xyz.cncnlp.soundwave.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import xyz.cncnlp.soundwave.ui.common.sheetPanelGlass
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.TodayRecommendationScheduler
import xyz.cncnlp.soundwave.platformName
import xyz.cncnlp.soundwave.recommend.StylePreferenceStore
import xyz.cncnlp.soundwave.recommend.StyleTagCatalog
import xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.settings.SettingsSwitch
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/** Installed by the app root; saving never turns on account consent or background WebDAV. */
internal val LocalStylePreferencesSaved = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/**
 * r655「风格推荐」弹窗（有状态外壳）：读取当前平台真实歌单分类（缓存优先），最多选 3 个标签并按平台持久化；
 * 选择变化后立即重算今日推荐（不占「换一批」次数），并登记 18:00 后台刷新沿用该选择。
 */
@Composable
internal fun StylePickerSheet(platform: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onSaved = LocalStylePreferencesSaved.current
    val initial = remember(platform) { StylePreferenceStore.loadTags(context, platform) }
    var groups by remember(platform) { mutableStateOf(StyleTagCatalog.cached(context, platform).orEmpty()) }
    var loading by remember(platform) { mutableStateOf(groups.isEmpty()) }
    val initialEnabled = remember(platform) { StylePreferenceStore.loadEnabled(context, platform) }
    var enabled by remember(platform) { mutableStateOf(initialEnabled) }
    var selected by remember(platform) { mutableStateOf(initial) }
    var saving by remember(platform) { mutableStateOf(false) }
    // ACCEPT5（退出动画普查 S2）：保存完成点改用关闭信号触发弹层退场动画，
    // onDismiss 改由退场走完才触发（此前 finally/else 里直调 onDismiss 瞬时卸载）。
    var closeRequested by remember(platform) { mutableStateOf(false) }
    LaunchedEffect(platform) {
        try {
            val loaded = StyleTagCatalog.load(context, platform)
            if (loaded.isNotEmpty()) groups = loaded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep this platform's catalog cache, never another platform's tags. */ }
        finally { loading = false }
    }
    StylePickerDialog(
        platformLabel = platformName(platform).ifBlank { platform.uppercase() },
        groups = groups,
        selected = selected,
        loading = loading,
        enabled = enabled,
        saving = saving,
        closeRequested = closeRequested,
        onEnabledChange = { enabled = it },
        onToggle = { tag ->
            val next = StylePreferenceStore.toggle(selected, tag)
            if (next == selected && tag !in selected) {
                AppNotice.toast("最多选择 ${StylePreferenceStore.MAX_TAGS} 个风格", AppNotice.Kind.NEUTRAL)
            }
            selected = next
        },
        onClear = { selected = emptyList() },
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            val next = StylePreferenceStore.normalize(selected)
            if (next != StylePreferenceStore.normalize(initial) || enabled != initialEnabled) {
                // Tags + enabled + edit metadata are one revision, including clear and disable.
                val saved = StylePreferenceStore.saveSelection(context, platform, next, enabled)
                if (saved == StylePreferenceStore.SaveResult.Failed) {
                    AppNotice.toast("风格设置保存失败，请重试", AppNotice.Kind.FAILURE)
                    return@StylePickerDialog
                }
                // The selection is already durable; a sync failure must not undo it or mint another edit.
                runCatching { onSaved() }
                saving = true
                scope.launch {
                    try {
                    val outcome = try {
                        TodayRecommendationScheduler.scheduleForStyle(context, platform)
                        TodayRecommendationRefresher.regenerateForStyle(context, platform)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { TodayRecommendationRefresher.Outcome.Empty }
                    val msg = when {
                        outcome is TodayRecommendationRefresher.Outcome.Done && (!enabled || next.isEmpty()) -> "已恢复按口味推荐"
                        outcome is TodayRecommendationRefresher.Outcome.Done -> "已按所选风格更新今日推荐"
                        else -> "设置已保存；暂无符合条件的曲目，未以其它风格补位"
                    }
                    AppNotice.toast(msg, AppNotice.Kind.NEUTRAL)
                    } finally { saving = false; closeRequested = true }
                }
            } else closeRequested = true
        }
    )
}

/** 无状态弹窗：统一底部面板样式（拖拽条 + 珊瑚等宽英文眉题 + 中文大标题 + 圆角 chip 网格）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StylePickerDialog(
    platformLabel: String,
    groups: List<StyleTagCatalog.Group>,
    selected: List<String>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
    saving: Boolean = false,
    /** ACCEPT5：宿主异步完成后置 true 请求动画退场（见 StylePickerSheet 的 closeRequested）。 */
    closeRequested: Boolean = false,
    onEnabledChange: (Boolean) -> Unit = {}
) {
    // ACCEPT5（退出动画普查 S2）：本壳原为裸 AppOverlayDialog、无进/退场，关闭靠宿主直调
    // onDismiss 瞬时卸载。补与 DesignSheetDialog 同参的进/退场（420 emphasized 入 / 220 出、
    // 遮罩同步淡变、面板自底滑入/滑出）；保存中（saving）不受理关闭（与原门控一致），
    // 宿主完成点经 closeRequested 信号触发退场，onDismiss 在退场走完才触发。
    val reduce = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val enterAnim = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    val sheetScope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (saving) Unit
        else if (reduce) onDismiss()
        else if (!closing) {
            closing = true
            sheetScope.launch {
                enterAnim.animateTo(0f, androidx.compose.animation.core.tween(xyz.cncnlp.soundwave.ui.common.SheetMotion.EXIT_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!reduce) try {
            enterAnim.animateTo(1f, androidx.compose.animation.core.tween(xyz.cncnlp.soundwave.ui.common.SheetMotion.ENTER_MS, easing = xyz.cncnlp.soundwave.ui.common.SheetMotion.Emphasized))
        } finally {
            if (!closing) withContext(kotlinx.coroutines.NonCancellable) { enterAnim.snapTo(1f) }
        }
    }
    LaunchedEffect(closeRequested) { if (closeRequested) close() }
    xyz.cncnlp.soundwave.ui.common.AppOverlayDialog(onDismissRequest = close) { // r658h 应用内覆盖层，贴屏幕底边
        xyz.cncnlp.soundwave.ui.common.DialogEdgeToEdge()
        xyz.cncnlp.soundwave.ui.common.DesignSheetWindowEffect() // r658d
        val colors = MaterialTheme.colorScheme
        val accent = SoundwaveDesignPalette.Accent
        // 分隔线 / chip 描边 = 首页平台切换按钮描边色（colors.outline），不取偏蓝的 outlineVariant。
        val line = colors.outline
        val maxSheet = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
        Box(Modifier.fillMaxSize()) {
        // ACCEPT6 dialogfrost：与 DesignSheet 家族同等处理——全屏雾化背板 + 面板玻璃（自绘壳归队）。
        xyz.cncnlp.soundwave.ui.common.SheetFrostBackdrop(enterAnim)
        Box(
            Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.45f * enterAnim.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier.fillMaxWidth().heightIn(max = maxSheet)
                    .graphicsLayer { translationY = (1f - enterAnim.value) * size.height }
                    .sheetPanelGlass(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), colors.surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = false) {}
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 18.dp)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp)
                            .clip(RoundedCornerShape(999.dp)).background(colors.onSurfaceVariant.copy(alpha = 0.26f))
                    )
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(
                            "STYLE PICKS", color = accent, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.DmMono,
                            fontWeight = FontWeight.Medium, letterSpacing = 0.22.em, maxLines = 1
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "风格推荐", color = colors.onSurface, fontSize = 30.sp, lineHeight = 38.sp,
                            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em, maxLines = 1
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "来自${platformLabel}歌单分类 · 最多选 ${StylePreferenceStore.MAX_TAGS} 个",
                                color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${selected.size}/${StylePreferenceStore.MAX_TAGS}",
                                color = if (selected.isNotEmpty()) accent else colors.onSurfaceVariant,
                                fontSize = 13.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用${platformLabel}风格筛选", color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (enabled) "仅保留曲目文本有明确依据的结果，可为空" else "已关闭；保留标签，按真实口味推荐", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                        // accept6-switchfix：全站开关只剩 SettingsSwitch 一套（原 Material3 Switch 直用迁入；
                        //SettingsSwitch 自带 Role.Switch 语义与 TOGGLE 触感）。
                        SettingsSwitch(checked = enabled, enabled = !saving, onClick = { onEnabledChange(!enabled) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(line)))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                        when {
                            groups.isEmpty() && loading -> StyleSheetHint("正在获取风格分类…")
                            groups.isEmpty() -> StyleSheetHint("暂时无法获取该平台的风格分类")
                            else -> groups.forEachIndexed { index, group ->
                                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(line)))
                                Spacer(Modifier.height(16.dp))
                                Text(group.name, color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Spacer(Modifier.height(10.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    group.tags.forEach { tag ->
                                        StyleChip(tag, tag in selected, dimmed = tag !in selected && selected.size >= StylePreferenceStore.MAX_TAGS, line = line) { if (!saving) onToggle(tag) }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(line)))
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        StyleSheetButton("清空", primary = false, line = line, modifier = Modifier.weight(1f), onClick = { if (!saving) onClear() })
                        Spacer(Modifier.width(10.dp))
                        StyleSheetButton(if (saving) "更新中…" else "完成", primary = true, line = line, modifier = Modifier.weight(2f), onClick = { if (!saving) onConfirm() })
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun StyleSheetHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 28.dp))
}

@Composable
private fun StyleChip(label: String, selected: Boolean, dimmed: Boolean, line: Color, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val shape = RoundedCornerShape(999.dp)
    Box(
        // r658b #6：风格分类标签恢复 1.0.655 样式（34dp 胶囊），不再撑到 48dp。
        Modifier.height(34.dp).clip(shape)
            .background(if (selected) accent else xyz.cncnlp.soundwave.ui.common.frostedChipFill(colors.surface))
            .border(1.dp, if (selected) accent else line, shape)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.Checkbox }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (selected) Color.White else colors.onSurface.copy(alpha = if (dimmed) 0.42f else 1f),
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1
        )
    }
}

@Composable
private fun StyleSheetButton(label: String, primary: Boolean, line: Color, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier.height(48.dp).clip(shape)
            .background(if (primary) accent else xyz.cncnlp.soundwave.ui.common.frostedChipFill(colors.surface))
            .border(1.dp, if (primary) accent else line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (primary) Color.White else colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1)
    }
}
