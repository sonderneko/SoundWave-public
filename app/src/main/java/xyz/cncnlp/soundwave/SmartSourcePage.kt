@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.luminance
import xyz.cncnlp.soundwave.ui.settings.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.designLineColor

/** 智能换源设置：换源顺序 / 执行控制 / 按平台脚本链路；不从页面本身发起探测（检测报告见音源脚本页）。 */
@Composable
fun SmartSourcePage(
    scripts: List<Script>,
    onScriptsChange: (List<Script>) -> Unit = {},
    onReResolveNow: () -> Unit = {},
    onAutoConfigure: () -> Unit = {}
) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val primary = MaterialTheme.colorScheme.primary
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val error = MaterialTheme.colorScheme.error
    var tick by remember { mutableIntStateOf(0) }
    fun refresh() { tick += 1 }

    LaunchedEffect(scripts.map { it.id to (it.enabled && it.code.isNotBlank()) }) {
        try { SmartSourceStore.ensureDefaultPlatformScripts(scripts) } catch (_: Exception) {}
        refresh()
    }

    val stages = remember(tick) { SmartSourceStore.stageOrder() }
    var enabled by remember(tick) { mutableStateOf(SmartSourceStore.smartEnabled()) }
    var fallback128 by remember(tick) { mutableStateOf(SmartSourceStore.fallback128k()) }
    var rememberCross by remember(tick) { mutableStateOf(SmartSourceStore.rememberCrossPlat()) }
    val plats = remember(tick) { SmartSourceStore.platformOrder() }
    var bindPlat by remember { mutableStateOf<String?>(null) }
    var qualityPlat by remember { mutableStateOf<String?>(null) }

    val usable = scripts.filter { it.code.isNotEmpty() }
    val platformUsable = SmartSourceStore.platformEligibleScripts(scripts)

    val smartBottomInset = designPageBottomInset()
    val darkUi = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = SettingsMetrics.SubPadH.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = smartBottomInset)
    ) {
        item {
            // ACCEPT4（按 v8 稿 pSmart 状态行）：淡染圆角状态条——开 = 绿淡染 + 绿点带晕环，
            // 关 = 墨色 5% 淡染 + 灰点；标题/计数/「一键配置」回调与文案均不变（N 波平面处理按稿让位）。
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (enabled) Color(0xFF3FA35B).copy(alpha = 0.10f) else onBg.copy(alpha = 0.05f))
                    .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background((if (enabled) Color(0xFF3FA35B) else muted).copy(alpha = 0.20f)))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) Color(0xFF3FA35B) else muted))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("智能换源 · ${if (enabled) "已开启" else "已关闭"}", color = onBg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Text(
                        if (enabled) "${stages.size} 个换源阶段 · ${plats.size} 条平台链路" else "播放失败时不再自动切换，直接提示失败",
                        color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(999.dp))
                        .border(1.dp, SoundwaveDesignPalette.Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .clickable { onAutoConfigure(); refresh() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("一键配置", color = SoundwaveDesignPalette.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        item {
            // N 波（设计重做）：「换源顺序」画成一条纵向信号路径图——1dp 发丝线贯穿全部
            // 阶段节点，节点圆环（ACCEPT5 起环内为状态点、无序号），环色与行尾状态词表实时状态
            // （生效中/待命/已跳过，只读既有开关与平台启用数派生，不新增任何策略逻辑）。
            val cardInk = if (darkUi) Color(0xFFF4F4F0) else MaterialTheme.colorScheme.onSurface
            val cardSub = cardInk.copy(alpha = 0.62f)
            val enabledPlatCount = plats.count { it.enabled }
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                Text("SIGNAL PATH", color = designAccentTextColor(), fontSize = 9.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("换源顺序", color = cardInk, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp))
                Text("播放失败时沿这条路径逐级尝试，命中后保持原播放进度；长按节点行拖动调整先后。", color = cardSub, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp, end = 6.dp))
                // E7（用户裁定）：阶段排序去掉 ↑↓ 箭头，改长按阶段行本身拖拽（与音源脚本页
                // 同一范式：detectDragGesturesAfterLongPress + pitch×0.27 阈值逐步换位；
                // 行内无其他控件，不存在手势吞噬问题）。draggingStage 仅作拖动态淡染。
                var draggingStage by remember { mutableStateOf<String?>(null) }
                var stageDragAcc by remember { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    // ACCEPT4（按稿时间线）：连接线改 2dp 绿淡染（稿 .step::before 同色系）；
                    // 自首节点圆心至末节点圆心（上下让位由节点底盘遮盖收口）。
                    Box(
                        Modifier.padding(start = 13.dp).width(2.dp).fillMaxHeight()
                            .padding(vertical = 24.dp)
                            .background(Color(0xFF3FA35B).copy(alpha = 0.45f))
                    )
                    Column {
                        stages.forEachIndexed { index, id ->
                            // 状态派生（纯呈现）：关→全待命；开→首阶段生效中，其余待命；
                            // 跨平台在启用平台不足两个时无从接力，标已跳过。
                            val nodeState = when {
                                !enabled -> 0
                                id == SmartSourceStore.STAGE_ORIGIN -> 1
                                id == SmartSourceStore.STAGE_CROSS_PLAT && enabledPlatCount < 2 -> 2
                                else -> 0
                            }
                            val description = when (id) {
                                SmartSourceStore.STAGE_ORIGIN -> "同一平台主脚本重试"
                                SmartSourceStore.STAGE_QUALITY -> if (fallback128) "按音质逐档降级，含 128k 兜底" else "按音质逐档降级"
                                SmartSourceStore.STAGE_CROSS_PLAT -> if (nodeState == 2) "尝试其他平台 · 需至少两个平台启用" else "尝试其他平台"
                                SmartSourceStore.STAGE_CROSS_SCRIPT -> "按其他平台脚本链路接力"
                                else -> ""
                            }
                            // ACCEPT4（按稿）：阶段行间改发丝线分隔（稿 .step border-top），行高下限 60dp 对齐稿。
                            if (index > 0) HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                    // ACCEPT6-motiondraft（稿 .lift）：拖动中行浮起大柔影（共享 dragLift；淡染仍为下方 background 的事）。
                                    .then(xyz.cncnlp.soundwave.ui.common.dragLift(draggingStage == id, RoundedCornerShape(12.dp)))
                                    .clip(RoundedCornerShape(12.dp))
                                    // 仅拖动态保留珊瑚淡染作反馈（行本身无底，路径线由节点底盘遮盖）。
                                    .background(if (draggingStage == id) SoundwaveDesignPalette.Accent.copy(alpha = 0.08f) else Color.Transparent)
                                    .then(
                                        if (enabled) Modifier.pointerInput(id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { draggingStage = id; stageDragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragStart(pressHapticView) },
                                                onDragEnd = { draggingStage = null; stageDragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragEnd(pressHapticView) },
                                                onDragCancel = { draggingStage = null; stageDragAcc = 0f },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    stageDragAcc += dragAmount.y
                                                    val pitch = size.height + 1.dp.toPx()
                                                    val thr = pitch * 0.27f
                                                    while (stageDragAcc <= -thr) { stageDragAcc += thr; SmartSourceStore.moveStage(id, -1); refresh(); xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView) }
                                                    while (stageDragAcc >= thr) { stageDragAcc += thr; SmartSourceStore.moveStage(id, 1); refresh(); xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView) }
                                                }
                                            )
                                        } else Modifier
                                    )
                                    .padding(end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 节点：页面底色底盘遮住路径线，环色表状态，生效中另缀实心状态点。
                                Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(27.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background))
                                    Box(
                                        Modifier.size(24.dp).clip(CircleShape)
                                            .border(
                                                1.5.dp,
                                                when {
                                                    draggingStage == id -> SoundwaveDesignPalette.Accent
                                                    nodeState == 1 -> SoundwaveDesignPalette.Lime
                                                    nodeState == 2 -> cardInk.copy(alpha = 0.16f)
                                                    else -> cardInk.copy(alpha = 0.28f)
                                                },
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // V1（用户 2026-11-04 裁定）：节点不带序号——环内改状态点，环色与行尾状态词照旧表状态。
                                        Box(
                                            Modifier.size(7.dp).clip(CircleShape).background(
                                                when {
                                                    draggingStage == id -> SoundwaveDesignPalette.Accent
                                                    nodeState == 1 -> SoundwaveDesignPalette.Lime
                                                    nodeState == 2 -> cardSub.copy(alpha = 0.6f)
                                                    else -> cardInk.copy(alpha = 0.55f)
                                                }
                                            )
                                        )
                                    }
                                    if (nodeState == 1) {
                                        Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                                            Box(Modifier.size(5.5.dp).clip(CircleShape).background(SoundwaveDesignPalette.Lime))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(SmartSourceStore.STAGE_LABELS[id] ?: id, color = if (nodeState == 2) cardSub else cardInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (description.isNotEmpty()) Text(description, color = cardSub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (nodeState) { 1 -> "生效中"; 2 -> "已跳过"; else -> "待命" },
                                    color = if (nodeState == 1) Color(0xFF3FA35B) else cardSub,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.DmMono
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
            }
        }

        item {
            // r655：开关组 = 设计稿「换源执行控制」（验收 B 波起去卡片壳，轻量分组呈现）。
            val cardInk = if (darkUi) Color(0xFFF4F4F0) else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier.fillMaxWidth().smartSourceCard(darkUi)
                    .padding(horizontal = 2.dp, vertical = 6.dp)
            ) {
                Text("CONTROL", color = designAccentTextColor(), fontSize = 9.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 14.dp))
                Text("换源执行控制", color = cardInk, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp))
                Text("失败后的接力、兜底与记忆策略。", color = cardInk.copy(alpha = 0.62f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                SmartSourceToggleRow(
                    title = "启用智能换源",
                    subtitle = if (enabled) "失败时按阶段继续尝试" else "关闭后仅尝试当前平台脚本链路",
                    checked = enabled,
                    onToggle = {
                        enabled = it
                        SmartSourceStore.setSmartEnabled(it)
                    }
                )
                HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
                SmartSourceToggleRow(
                    title = "128k 音质兜底",
                    subtitle = "较高音质失败后再尝试 128k",
                    checked = fallback128,
                    enabled = enabled,
                    onToggle = {
                        fallback128 = it
                        SmartSourceStore.setFallback128k(it)
                    }
                )
                HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
                SmartSourceToggleRow(
                    title = "记住跨平台结果",
                    subtitle = "同一首歌下次优先使用成功来源",
                    checked = rememberCross,
                    onToggle = {
                        rememberCross = it
                        SmartSourceStore.setRememberCrossPlat(it)
                    }
                )
                HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
            }
        }

        item {
            // ACCEPT4（按稿）：「换源记忆」独立成组（眉题 + 标题 + 真实统计副行 + 两枚线框按钮），
            // 自执行控制组拆出；统计读 SmartSourceStore.rememberedStats（只读），回调不变。
            val cardInk = if (darkUi) Color(0xFFF4F4F0) else MaterialTheme.colorScheme.onSurface
            val cardSub = cardInk.copy(alpha = 0.62f)
            val (rememberedCount, rememberedBytes) = remember(tick) { SmartSourceStore.rememberedStats() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                Text("MEMORY", color = designAccentTextColor(), fontSize = 9.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("换源记忆", color = cardInk, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp))
                Text(
                    if (rememberedCount > 0) "已记住 $rememberedCount 首歌的成功来源 · 占用 ${if (rememberedBytes >= 1024) "${rememberedBytes / 1024} KB" else "$rememberedBytes B"}"
                    else "尚未记住任何成功来源；清空已记住结果，或立即重新解析当前曲",
                    color = cardSub, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartSourcePillButton("清空记忆", null, Modifier.weight(1f)) { SmartSourceStore.clearRemembered(); refresh() }
                    SmartSourcePillButton("立即重新解析", Icons.Outlined.Refresh, Modifier.weight(1f), onClick = onReResolveNow)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
            }
        }

        item {
            // r656：按平台脚本链路区域重做；「激活脚本」「跨脚本备用顺序」已移除，取链顺序完全由平台链路 + 平台顺序推导。
            // N 波：区头加眉题与「平台流条」——平台字标（DmMono 代码 + 中文名）以发丝线 + ›
            // 横向串联成跨平台接力路径，关闭平台整体淡化；纯呈现，顺序即 plats 当前序。
            Column(Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PLATFORM FLOW", color = designAccentTextColor(), fontSize = 9.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("按平台脚本链路", color = onBg, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp))
                Text("每个平台按链路依次尝试脚本；平台自上而下即跨平台换源顺序，本平台链路失败后按下一平台的链路接力，最后按音源脚本页顺序兜底。", color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                // ACCEPT4（按稿）：去掉 N 波的横向「平台流条」——稿中本组只有眉题/标题/说明 + 平台行本身，
                // 顺序信息由平台行序号承载，流条与之重复。
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = xyz.cncnlp.soundwave.ui.common.frostLine(outline.copy(alpha = 0.45f)))
            }
        }
        // E7：拖拽换位处理器按平台 code 从存储读当前顺序——pointerInput 按 key 保持
        // 首个组合的 lambda，必须保证它调用时读到的永远是最新顺序（不用捕获的 index 快照）。
        val movePlatformByCode: (String, Int) -> Unit = { code, delta ->
            val current = SmartSourceStore.platformOrder()
            val from = current.indexOfFirst { it.code == code }
            val target = (from + delta).coerceIn(0, current.lastIndex)
            if (from >= 0 && target != from) {
                val order = current.toMutableList()
                val moved = order.removeAt(from); order.add(target, moved)
                SmartSourceStore.savePlatformOrder(order); refresh()
            }
        }
        itemsIndexed(plats, key = { _, platform -> platform.code }) { index, platform ->
            val chain = SmartSourceStore.filterPlatformScriptOrder(SmartSourceStore.platformScriptOrder(platform.code), scripts)
            val names = scripts.associate { it.id to it.name.ifEmpty { it.id } }
            // N 波：链内脚本的健康（点/耗时）与行同读一遍现行检测报告（只读 ScriptProbeCache，
            // 与音源脚本页、链路编辑弹层同一口径）；仅供紧凑索引行呈现，不影响链路与顺序。
            val chainScripts = remember(chain.joinToString(","), scripts.map { it.id }.joinToString(",")) {
                chain.map { sid ->
                    val sc = scripts.firstOrNull { it.id == sid }
                    val health = sc?.let {
                        val report = ScriptProbeCache.latestReport(sid)
                        ScriptProbeCache.ScriptHealth.of(report?.takeIf { r -> ScriptProbeCache.isReportCurrent(r, it) })
                    } ?: ScriptProbeCache.ScriptHealth(ScriptProbeCache.ScriptHealth.State.NONE, -1L)
                    Triple(sid, names[sid] ?: sid, health)
                }
            }
            SmartSourcePlatformChainCard(
                index = index,
                platformName = smartSourcePlatformName(platform.code),
                platformEnabled = platform.enabled,
                chainNames = chain.map { names[it] ?: it },
                chainScripts = chainScripts,
                fallbackSummary = if (chain.isEmpty()) "未指定 · 依次接力：" + (SmartSourceStore.derivedFallbackChain(platform.code, scripts).take(3).joinToString(" → ") { it.name.ifEmpty { it.id } }.ifEmpty { "暂无可用脚本" }) else "",
                playQuality = SmartSourceStore.platformPlayQuality(platform.code, "跟随全局"),
                downloadQuality = SmartSourceStore.platformDownloadQuality(platform.code, "跟随全局"),
                smartEnabled = enabled,
                onToggle = {
                    platform.enabled = !platform.enabled
                    SmartSourceStore.savePlatformOrder(plats)
                    refresh()
                },
                platformKey = platform.code,
                onMove = movePlatformByCode,
                onEditChain = { bindPlat = platform.code },
                onEditQuality = { qualityPlat = platform.code }
            )
        }
        if (usable.isEmpty()) {
            item { Text("暂无脚本，请到音源脚本页导入。", color = muted, fontSize = 12.sp) }
        }
    }

    if (bindPlat != null) {
        val platform = bindPlat!!
        SmartSourceChainSheet(
            platformName = smartSourcePlatformName(platform),
            eligible = platformUsable,
            order = SmartSourceStore.filterPlatformScriptOrder(SmartSourceStore.platformScriptOrder(platform), platformUsable),
            onOrder = { next -> SmartSourceStore.savePlatformScriptOrder(platform, next); refresh() },
            onDismiss = { bindPlat = null }
        )
    }

    if (qualityPlat != null) {
        val platform = qualityPlat!!
        val platformName = smartSourcePlatformName(platform)
        var selection by remember(qualityPlat) {
            mutableStateOf(
                SmartSourceQualitySelection(
                    playbackQuality = SmartSourceStore.platformPlayQuality(platform, "320k"),
                    downloadQuality = SmartSourceStore.platformDownloadQuality(platform, "320k")
                )
            )
        }
        DesignSheetDialog(onDismiss = { qualityPlat = null }) {
            // ACCEPT4（审计 §9 B 类，随第 9 项合并处理）：取消/保存改走退场句柄——保存先落盘再退场，
            // qualityPlat=null 归退场完成后的 onDismiss（原接线同步清状态、退场动画被掐断）。
            val dismissQuality = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { qualityPlat = null }
            DesignSheetHeader("Platform quality", "$platformName 音质")
            SettingsSheetCopy("仅作用于该平台的取链；音源不支持时自动降级。")
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                SettingsSheetSection("在线播放音质")
                SettingsOptionGrid(qualityOptions().filter { it.key in SMART_SOURCE_QUALITY_OPTIONS }, selection.playbackQuality, 2) { selection = selection.selectPlayback(it) }
                SettingsSheetSection("下载音质")
                SettingsOptionGrid(qualityOptions().filter { it.key in SMART_SOURCE_QUALITY_OPTIONS }, selection.downloadQuality, 2) { selection = selection.selectDownload(it) }
            }
            SettingsSheetButtons("取消", { dismissQuality() }, "保存") {
                selection.persist(platform, SmartSourceStore::setPlatformPlayQuality, SmartSourceStore::setPlatformDownloadQuality)
                refresh()
                dismissQuality()
            }
        }
    }
}

@Composable
private fun SmartSourceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        DesignSwitchVisual(checked = checked, enabled = enabled)
    }
}

/**
 * 验收 B 波（用户指示）：智能换源页去掉整块大卡片底色 —— 本修饰符保留为空操作，
 * 各分组改走「分组标题 + 行 + 细分隔线」的轻量排版（分隔线在各分组末尾单独添加）。
 * 签名不变，三处既有调用点（换源顺序 / 执行控制 / 平台链路）与全部点击、开关、重排逻辑不动。
 */
@Composable
private fun Modifier.smartSourceCard(dark: Boolean): Modifier = this

private fun smartSourcePlatformName(code: String): String = when (code) {
    "wy" -> "网易云"
    "kw" -> "酷我"
    "kg" -> "酷狗"
    "tx" -> "QQ"
    "mg" -> "咪咕"
    else -> code
}


/** r656：线框胶囊按钮（换源记忆的「清空记忆 / 立即重新解析」）。 */
@Composable
private fun SmartSourcePillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape).background(xyz.cncnlp.soundwave.ui.common.frostedChipFill(Color.Transparent)).border(1.dp, designLineColor(), shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1)
    }
}

@Composable
private fun SmartSourcePlatformChainCard(
    index: Int,
    platformName: String,
    platformEnabled: Boolean,
    chainNames: List<String>,
    chainScripts: List<Triple<String, String, ScriptProbeCache.ScriptHealth>>,
    fallbackSummary: String,
    playQuality: String,
    downloadQuality: String,
    smartEnabled: Boolean,
    onToggle: () -> Unit,
    onEditChain: () -> Unit,
    onEditQuality: () -> Unit,
    platformKey: String,
    onMove: (String, Int) -> Unit
) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val ink = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    var dragAcc by remember { mutableFloatStateOf(0f) }
    val stateText = when {
        chainNames.isEmpty() -> "未指定脚本 · 按音源脚本顺序兜底"
        else -> "链路接力"
    }
    Column(
        Modifier.fillMaxWidth()
            .then(
                if (smartEnabled) Modifier.pointerInput(platformKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragStart(pressHapticView) },
                        onDragEnd = { dragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragEnd(pressHapticView) },
                        onDragCancel = { dragAcc = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAcc += dragAmount.y
                            val pitch = size.height.toFloat()
                            val thr = pitch * 0.27f
                            while (dragAcc <= -thr) { dragAcc += thr; onMove(platformKey, -1); xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView) }
                            while (dragAcc >= thr) { dragAcc -= thr; onMove(platformKey, 1); xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView) }
                        }
                    )
                } else Modifier
            )
            .padding(start = 2.dp, end = 2.dp, top = 14.dp, bottom = 0.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // N 波：平台名 15sp ExtraBold 作行锚点。ACCEPT5（V1）：行首 %02d 序号删除，只留平台本体。
            Column(Modifier.weight(1f)) {
                Text(platformName, color = if (platformEnabled) ink else ink.copy(alpha = 0.45f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateText, color = sub, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            // 既有开关控件，原样保留（46×26 胶囊，语义同平台启用开关）。
            Box(
                Modifier.size(width = 46.dp, height = 26.dp).clip(CircleShape)
                    .background(if (platformEnabled) SoundwaveDesignPalette.Accent else ink.copy(alpha = 0.16f))
                    .clickable(enabled = smartEnabled) { onToggle() }
                    .padding(horizontal = 3.dp),
                contentAlignment = if (platformEnabled) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White).shadow(2.dp, CircleShape))
            }
        }
        // ACCEPT4（按稿 .chs 链路胶囊行）：链内脚本压成一行摘要——健康点 + 脚本名以 › 串联（横向可滚）；
        // 健康色口径与原索引行一致（可用绿 / 已停服红 / 未通过琥珀 / 未检测淡灰）。空链显示兜底摘要。
        if (chainScripts.isEmpty()) {
            Text(fallbackSummary, color = sub, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        } else {
            Row(
                Modifier.padding(start = 12.dp, top = 8.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                chainScripts.forEachIndexed { i, (_, scriptName, health) ->
                    if (i > 0) Text("›", color = sub.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 7.dp))
                    Box(
                        Modifier.size(6.dp).clip(CircleShape).background(
                            when (health.state) {
                                ScriptProbeCache.ScriptHealth.State.USABLE -> Color(0xFF3FA35B)
                                ScriptProbeCache.ScriptHealth.State.STOPPED -> Color(0xFFD23C2E)
                                ScriptProbeCache.ScriptHealth.State.FAILED -> Color(0xFFE6953B)
                                // ACCEPT5：最近一轮检测被跳过/中断——有定论但无结论，琥珀（同未通过族）。
                                ScriptProbeCache.ScriptHealth.State.SKIPPED -> Color(0xFFE6953B)
                                ScriptProbeCache.ScriptHealth.State.NONE -> sub.copy(alpha = 0.35f)
                            }
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(scriptName, color = ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (health.state == ScriptProbeCache.ScriptHealth.State.USABLE && health.avgMs > 0L) {
                        Spacer(Modifier.width(5.dp))
                        Text(ScriptProbeCache.ScriptHealth.formatAvgMs(health.avgMs), color = sub, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, maxLines = 1)
                    }
                    if (health.state == ScriptProbeCache.ScriptHealth.State.STOPPED) {
                        Spacer(Modifier.width(5.dp))
                        Text("已停服", color = Color(0xFFD23C2E), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        // ACCEPT4（按稿 .chb4）：行下两枚文字入口并排——编辑链路 / 音质（值 = 播放·下载当前档位标签），回调不变。
        Row(Modifier.padding(start = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmartSourceCardAction(
                title = "编辑链路",
                value = if (chainNames.isEmpty()) "选择脚本" else chainNames.size.toString(),
                enabled = smartEnabled,
                onClick = onEditChain
            )
            Spacer(Modifier.width(18.dp))
            SmartSourceCardAction(
                title = "音质",
                value = "播放 ${smartQualityLabel(playQuality)} · 下载 ${smartQualityLabel(downloadQuality)}",
                enabled = smartEnabled,
                onClick = onEditQuality
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = designLineColor().copy(alpha = 0.7f))
    }
}

/** ACCEPT4：音质键 → 展示标签（「跟随全局」原样；其余走 qualityOptions 标签表）。 */
private fun smartQualityLabel(value: String): String =
    if (value == "跟随全局" || value.isBlank()) "跟随全局"
    else qualityOptions().firstOrNull { it.key == value }?.label ?: value

@Composable
private fun SmartSourceCardAction(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = ink, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(value, color = sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(DesignLineGlyphs.Chevron, null, tint = sub, modifier = Modifier.size(12.dp))
    }
}

/** r656：平台脚本链路编辑抽屉 —— 点选加入/移出链路；已选脚本显示序号并可上下调整先后。 */
@Composable
private fun SmartSourceChainSheet(
    platformName: String,
    eligible: List<Script>,
    order: List<String>,
    onOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    DesignSheetDialog(onDismiss = onDismiss) {
        // ACCEPT4（同 §9 模式）：「完成」改走退场句柄，不直调 onDismiss 同步卸载。
        val pressHapticView = androidx.compose.ui.platform.LocalView.current
        val dismissChain = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        DesignSheetHeader("Script chain", "${platformName}脚本链路")
        SettingsSheetCopy("播放${platformName}歌曲时按序号依次尝试；点选加入或移出，长按已选脚本拖动调整先后。未选择时按其他平台链路接力。")
        Spacer(Modifier.height(14.dp))
        val accent = SoundwaveDesignPalette.Accent
        val ink = MaterialTheme.colorScheme.onSurface
        val sorted = order.mapNotNull { id -> eligible.firstOrNull { it.id == id } } + eligible.filter { it.id !in order }
        // E7：链路顺序改长按已选脚本行拖拽（去 ↑↓ 箭头）。列表改 LazyColumn 以获得与
        // 音源脚本页一致的稳定 key 拖拽语义；点选 toggle 降为子 Row（范式同脚本页的开关行，
        // 长按拖拽与点按共存）；order/onOrder 经 rememberUpdatedState 读取，防 pointerInput
        // 按 key 保持首个 lambda 时读到旧顺序。
        val currentOrder by rememberUpdatedState(order)
        val currentOnOrder by rememberUpdatedState(onOrder)
        var draggingScriptId by remember { mutableStateOf<String?>(null) }
        var sheetDragAcc by remember { mutableFloatStateOf(0f) }
        if (eligible.isEmpty()) {
            Text("请先在音源脚本页导入并启用有代码的脚本", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(sorted, key = { _, script -> script.id }) { _, script ->
                    val pos = order.indexOf(script.id)
                    val on = pos >= 0
                    SettingsSheetLine()
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (draggingScriptId == script.id) accent.copy(alpha = 0.06f) else Color.Transparent)
                            .then(
                                if (on) Modifier.pointerInput(script.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingScriptId = script.id; sheetDragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragStart(pressHapticView) },
                                        onDragEnd = { draggingScriptId = null; sheetDragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragEnd(pressHapticView) },
                                        onDragCancel = { draggingScriptId = null; sheetDragAcc = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            sheetDragAcc += dragAmount.y
                                            val pitch = size.height + 2.dp.toPx()
                                            val thr = pitch * 0.27f
                                            while (sheetDragAcc <= -thr) {
                                                sheetDragAcc += thr
                                                val cur = currentOrder.indexOf(script.id)
                                                if (cur > 0) { currentOnOrder(currentOrder.toMutableList().apply { val m = removeAt(cur); add(cur - 1, m) }); xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView) }
                                            }
                                            while (sheetDragAcc >= thr) {
                                                sheetDragAcc -= thr
                                                val cur = currentOrder.indexOf(script.id)
                                                if (cur >= 0 && cur < currentOrder.lastIndex) { currentOnOrder(currentOrder.toMutableList().apply { val m = removeAt(cur); add(cur + 1, m) }); xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView) }
                                            }
                                        }
                                    )
                                } else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(1f).heightIn(min = 56.dp)
                                .toggleable(value = on, role = Role.Checkbox) {
                                    onOrder(if (on) order - script.id else order + script.id)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(if (on) accent else Color.Transparent)
                                    .border(1.dp, if (on) accent else designLineColor(), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (on) Text("${pos + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.DmMono)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(script.name.ifEmpty { script.id }, color = ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            // J-健康度（仅显示）：链路排序时可参考各脚本最近检测健康（可用·平均耗时 /
                            // 已停服 / 检测未通过）；只读 ScriptProbeCache 现行报告，不改策略与顺序逻辑。
                            val chainHealth = remember(script.id) {
                                val report = ScriptProbeCache.latestReport(script.id)
                                ScriptProbeCache.ScriptHealth.of(report?.takeIf { ScriptProbeCache.isReportCurrent(it, script) })
                            }
                            if (chainHealth.state != ScriptProbeCache.ScriptHealth.State.NONE) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    chainHealth.label,
                                    color = when (chainHealth.state) {
                                        ScriptProbeCache.ScriptHealth.State.USABLE -> Color(0xFF3FA35B)
                                        ScriptProbeCache.ScriptHealth.State.STOPPED -> Color(0xFFD23C2E)
                                        else -> Color(0xFFE6953B)
                                    },
                                    fontSize = 11.sp, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        SettingsSheetButtons("清除链路", { onOrder(emptyList()) }, "完成") { dismissChain() }
    }
}
