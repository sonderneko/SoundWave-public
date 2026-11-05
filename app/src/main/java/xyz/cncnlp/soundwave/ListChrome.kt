package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.em
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

internal fun formatPlayCount(value: Long): String {
    val n = value.coerceAtLeast(0L)
    fun compact(divisor: Long, suffix: String): String {
        val v = n.toDouble() / divisor
        val raw = if (v >= 10.0) String.format(java.util.Locale.US, "%.0f", v) else String.format(java.util.Locale.US, "%.1f", v)
        return raw.trimEnd('0').trimEnd('.') + suffix
    }
    return when {
        n >= 100_000_000L -> compact(100_000_000L, "亿")
        n >= 10_000L -> compact(10_000L, "w")
        n >= 1_000L -> compact(1_000L, "k")
        else -> n.toString()
    }
}

internal fun formatVisiblePlayCount(value: Long): String? =
    value.takeIf { it > 0L }?.let(::formatPlayCount)

@Composable
internal fun FusedListCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.62f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) { content() }
}

internal enum class SpaceCompressedState { Loading, Empty, Error }

/**
 * State treatment for redesigned pages: explicit live status; the Loading rhythm bars
 * pulse on MotionTokens pacing (E4) unless decorative loops are off; colors use design
 * palette literals so root-theme hosts never leak blue/purple into state surfaces.
 */
@Composable
internal fun SpaceCompressedStateCard(
    title: String,
    message: String = "",
    status: SpaceCompressedState = SpaceCompressedState.Empty,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: androidx.compose.ui.Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val stateLabel = when (status) {
        SpaceCompressedState.Loading -> "正在加载"
        SpaceCompressedState.Empty -> "暂无内容"
        SpaceCompressedState.Error -> "暂时不可用"
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(
                if (status == SpaceCompressedState.Empty) Modifier
                else Modifier.clip(shape).background(colors.surface).border(1.dp, colors.outline.copy(alpha = 0.62f), shape)
            )
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = stateLabel
            }
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 状态徽（验收 B 波）：Empty = 珊瑚渐变圆盘 + 白色音符；Error = 危险色圆盘 + 白色 !；
        // Loading = 强调浅底圆盘，保留下方三段节奏条并换成强调色系。
        when (status) {
            SpaceCompressedState.Error -> Box(
                Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(SoundwaveDanger),
                contentAlignment = Alignment.Center
            ) {
                Text("!", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            SpaceCompressedState.Loading -> Box(
                // E4（验收二轮）：Loading 徽底色原走主题 primaryContainer——根主题未定义该
                // 角色、落 Material 默认薰衣草紫（用户截图实证的蓝紫泄漏）。改设计调色板
                // AccentSoft 字面（明暗随背景亮度切换），音符用 Accent，与 Empty 徽同系。
                Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (colors.background.luminance() < 0.5f) SoundwaveDesignPalette.NightAccentSoft
                        else SoundwaveDesignPalette.AccentSoft
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.MusicNote, null,
                    tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(24.dp)
                )
            }
            SpaceCompressedState.Empty -> Box(
                // J 波：按设计稿 .v7s-state——48dp 中性圆（ink 6% 底）+ muted 字形 24dp；
                // 原 B 波的 56dp 珊瑚渐变圆盘 + 白色音符与稿子对不上，用户点名。
                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.MusicNote, null,
                    tint = if (colors.background.luminance() < 0.5f) SoundwaveDesignPalette.NightMuted else SoundwaveDesignPalette.Muted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (status == SpaceCompressedState.Loading) {
            // E4（验收二轮）：三段节奏条原为完全静止 + 主题蓝（根主题下）——用户点名
            // 「未加载动画不对」。改 Accent 色 + 按 MotionTokens.Duration.Stagger 节奏的
            // 错峰呼吸（中间条相位偏移半周期）；减少动效/装饰循环关闭时回到静态原值。
            val reduceBars = xyz.cncnlp.soundwave.ui.common.LocalReduceMotion.current
                ?: MotionPolicy.rememberReduceMotion()
            val barsAnimate = MotionPolicy.allowDecorativeLoops(reduceBars)
            val barsTransition = rememberInfiniteTransition(label = "stateRhythmBars")
            // 两相位无条件注册（避免条件调用动画 API），取值时再按开关/序号选择。
            val barPhaseA by barsTransition.animateFloat(
                initialValue = 0.72f, targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(xyz.cncnlp.soundwave.ui.motion.MotionTokens.Duration.Stagger, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "stateRhythmBarA"
            )
            val barPhaseB by barsTransition.animateFloat(
                initialValue = 0.35f, targetValue = 0.72f,
                animationSpec = infiniteRepeatable(
                    animation = tween(xyz.cncnlp.soundwave.ui.motion.MotionTokens.Duration.Stagger, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "stateRhythmBarB"
            )
            Row(
                Modifier.padding(top = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(26.dp, 12.dp, 26.dp).forEachIndexed { index, width ->
                    val barAlpha = when {
                        !barsAnimate -> if (index == 1) 0.35f else 0.72f
                        index == 1 -> barPhaseB
                        else -> barPhaseA
                    }
                    Box(
                        Modifier.width(width).height(4.dp).clip(RoundedCornerShape(99.dp))
                            .background(SoundwaveDesignPalette.Accent.copy(alpha = barAlpha))
                    )
                }
            }
        }
        Text(
            title,
            color = colors.onSurface,
            fontSize = if (status == SpaceCompressedState.Empty) 15.sp else 17.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
            modifier = Modifier.padding(top = if (status == SpaceCompressedState.Loading) 9.dp else 12.dp)
        )
        if (message.isNotBlank()) {
            Text(
                message,
                color = colors.onSurfaceVariant,
                fontSize = if (status == SpaceCompressedState.Empty) 13.sp else 12.sp,
                lineHeight = if (status == SpaceCompressedState.Empty) 19.sp else 18.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        if ((actionLabel != null && onAction != null) || (secondaryActionLabel != null && onSecondaryAction != null)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (actionLabel != null && onAction != null) {
                    // 主操作：Empty 状态按设计稿 .v7s-state 用 surface 描边胶囊 44dp；其余状态
                    // 保持强调色实心胶囊（与下载弹层主按钮同语言，Accent 字面避免根主题变蓝）。
                    val emptyOutline = status == SpaceCompressedState.Empty
                    Row(
                        Modifier.height(if (emptyOutline) 44.dp else 42.dp).clip(RoundedCornerShape(999.dp))
                            .then(
                                if (emptyOutline) Modifier.border(1.dp, xyz.cncnlp.soundwave.ui.common.designLineColor(), RoundedCornerShape(999.dp))
                                else Modifier.background(SoundwaveDesignPalette.Accent)
                            )
                            .pressScale(PressMotion.SCALE_ROW)
                            .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onAction)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(actionLabel, color = if (emptyOutline) colors.onSurface else Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    Row(
                        Modifier.height(42.dp).clip(RoundedCornerShape(999.dp))
                            .border(1.dp, xyz.cncnlp.soundwave.ui.common.designLineColor(), RoundedCornerShape(999.dp))
                            .pressScale(PressMotion.SCALE_ROW)
                            .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onSecondaryAction)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(secondaryActionLabel, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 音乐库「还没有导入歌单」空态（ACCEPT4 按 v8 稿 .v8empty + 用户补发截图逐元素对齐）：
 * 无边框无卡；浮动艺术 148×104 = 后片（78dp r16、−9°、Surface2 渐变、5.5s 浮动）+ 前片（78dp r16、
 * +5°、顶部珊瑚晕染 26% + 底部两条灰色文字线、同周期错相浮动）+ 右侧探出黑胶唱片（70dp、沟纹、
 * 9s/圈旋转）+ 右下珊瑚圆「+」徽（30dp、纸色 3dp 环）。标题 22sp 衬线粗体、正文 13sp muted（≤290dp）、
 * 双按钮（珊瑚实心主钮 + 描边幽灵次钮，40dp 高）、底部 mono 平台注脚行。
 * 「新建歌单」与区头「歌单操作」菜单入口并存（用户 2026-11-04 裁定：按截图双按钮落地，菜单入口保留）。
 */
@Composable
internal fun LibraryPlaylistsEmptyState(
    title: String,
    message: String,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSecondary: (() -> Unit)? = null,
    hint: String? = null,
    modifier: androidx.compose.ui.Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val muted = if (dark) SoundwaveDesignPalette.NightMuted else SoundwaveDesignPalette.Muted
    val line = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyPlaylistArt()
        Text(
            title, color = colors.onSurface, fontSize = 22.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.SerifCJK,
            letterSpacing = (-0.03).em, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        if (message.isNotBlank()) {
            Text(
                message, color = muted, fontSize = 13.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Medium, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 290.dp)
            )
        }
        Row(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmptyStateButton(primaryLabel, primaryIcon, solid = true, onClick = onPrimary)
            if (secondaryLabel != null && onSecondary != null) {
                EmptyStateButton(secondaryLabel, secondaryIcon, solid = false, onClick = onSecondary)
            }
        }
        if (!hint.isNullOrBlank()) {
            Text(
                hint, color = muted.copy(alpha = 0.8f), fontSize = 10.5.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.Medium, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
                letterSpacing = 0.06.em, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

/** 空态双按钮（稿 .v8btn：40dp 高胶囊、700 13.5sp；实心 = 珊瑚底白字，幽灵 = 描边墨字）。 */
@Composable
private fun EmptyStateButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    solid: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    val dark = colors.background.luminance() < 0.5f
    val line = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    Row(
        Modifier.height(40.dp).pressScale(PressMotion.SCALE_ROW).clip(shape)
            .then(if (solid) Modifier.background(SoundwaveDesignPalette.Accent) else Modifier.border(1.dp, line, shape))
            .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = androidx.compose.ui.semantics.Role.Button }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, tint = if (solid) Color.White else colors.onSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (solid) Color.White else colors.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1)
    }
}

/**
 * 空态浮动艺术（稿 .v8e-art，148×104）：两张交叠圆角方片 + 右侧探出的黑胶唱片 + 右下珊瑚「+」徽。
 * 方片 5.5s 浮动（translateY 0→−4dp 正弦，前片错相 −2s）；唱片 9s/圈匀速旋转（走共享 VinylDiscSpinner）。
 */
@Composable
private fun EmptyPlaylistArt() {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val surface = if (dark) SoundwaveDesignPalette.NightSurface else SoundwaveDesignPalette.Surface
    val surface2 = if (dark) SoundwaveDesignPalette.NightSurface2 else SoundwaveDesignPalette.Surface2
    val paper = if (dark) SoundwaveDesignPalette.NightPaper else SoundwaveDesignPalette.Paper
    val accent = SoundwaveDesignPalette.Accent
    val ink = if (dark) SoundwaveDesignPalette.NightInk else SoundwaveDesignPalette.Ink
    val shadowInk = Color(0xFF131915).copy(alpha = 0.12f)
    val sheetShape = RoundedCornerShape(16.dp)
    val reduce = MotionPolicy.rememberReduceMotion()
    val loops = MotionPolicy.allowDecorativeLoops(reduce)
    val floatPhase = if (loops) {
        val transition = rememberInfiniteTransition(label = "emptyArtFloat")
        val p by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5500, easing = LinearEasing), RepeatMode.Restart),
            label = "emptyArtFloatPhase"
        )
        p
    } else 0f
    fun floatYDp(delaySec: Float): Float {
        if (!loops) return 0f
        val p = (floatPhase + delaySec / 5.5f) % 1f
        return (-4.0 * kotlin.math.sin(kotlin.math.PI * p)).toFloat()
    }
    Box(Modifier.size(148.dp, 104.dp)) {
        // 后片：left 12 / top 10、−9°、Surface2→纸面渐变。
        Box(
            Modifier.offset(x = 12.dp, y = (10f + floatYDp(0f)).dp).size(78.dp)
                .graphicsLayer { rotationZ = -9f }
                .shadow(13.dp, sheetShape, ambientColor = shadowInk, spotColor = shadowInk)
                .clip(sheetShape)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(surface2, androidx.compose.ui.graphics.lerp(surface2, paper, 0.4f))))
                .border(1.dp, ink.copy(alpha = 0.06f), sheetShape)
        )
        // 右侧探出的唱片：left 78 / top 14、70dp（z 在后片之上、前片之下）。
        Box(
            Modifier.offset(x = 78.dp, y = 14.dp).size(70.dp).zIndex(1f)
                .shadow(11.dp, androidx.compose.foundation.shape.CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
        ) {
            xyz.cncnlp.soundwave.ui.common.VinylDiscSpinner(70.dp, labelFraction = 0.198f, showHighlight = false, spinMs = 9000)
        }
        // 前片：left 44 / top 4、+5°、表面渐变 + 顶部珊瑚晕染 + 底部两条文字线。
        Box(
            Modifier.offset(x = 44.dp, y = (4f + floatYDp(2f)).dp).size(78.dp).zIndex(2f)
                .graphicsLayer { rotationZ = 5f }
                .shadow(13.dp, sheetShape, ambientColor = shadowInk, spotColor = shadowInk)
                .clip(sheetShape)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(surface, surface2)))
                .border(1.dp, ink.copy(alpha = 0.06f), sheetShape)
                .drawWithContent {
                    drawContent()
                    val c = Offset(size.width * 0.2f, size.height * 0.1f)
                    val r = size.width * 0.78f
                    drawCircle(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.26f), Color.Transparent), center = c, radius = r
                        ),
                        radius = r, center = c
                    )
                }
        ) {
            // 文字线：底线（左右 12、底 12、墨 12%）+ 上线（左右 13、底 21、墨 8%）。
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 13.dp, end = 13.dp, bottom = 21.dp)
                    .fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(ink.copy(alpha = 0.08f))
            )
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(ink.copy(alpha = 0.12f))
            )
        }
        // 右下珊瑚「+」徽：right 6 / bottom 2、30dp、纸色 3dp 环 + 珊瑚辉光影。
        Box(
            Modifier.offset(x = 112.dp, y = 72.dp).size(30.dp).zIndex(3f)
                .shadow(7.dp, androidx.compose.foundation.shape.CircleShape, ambientColor = accent.copy(alpha = 0.4f), spotColor = accent.copy(alpha = 0.4f))
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(accent)
                .border(3.dp, paper, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Plus, null,
                tint = Color.White, modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
internal fun RankCard(label: String, board: RankBoard, onOpen: () -> Unit) {
    val src = board.source.ifEmpty { label }
    val titleColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(start = 14.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
            Box(
                Modifier
                    .size(64.5.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                val cover = normalizeCoverUrl(board.cover.ifEmpty { board.songs.firstOrNull()?.cover.orEmpty() })
                // ACCEPT5 收编：黑胶母题占位垫底（此前 RankDefaultCover 无底线稿柱状图，与 OfficialRankCard 的横版占位两套语言）。
                DefaultCoverPlaceholder(Modifier.fillMaxSize())
                if (cover.isNotEmpty()) {
                    AsyncImage(
                        model = CacheManager.coverModel(LocalContext.current, cover) ?: cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .height(64.5.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    board.name,
                    color = titleColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.padding(top = 2.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        board.songCount().takeIf { it > 0 }?.let { "${it}首" } ?: "点按查看",
                        color = secondaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(y = (-2).dp),
                        maxLines = 1
                    )
                    Text(
                        " · ${platformName(src).ifEmpty { label }}",
                        color = secondaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(y = 3.dp),
                        maxLines = 1
                    )
                }
            }
        }
}

@Composable
internal fun NowPlayingBars(active: Boolean) {
    // r656-G：减少动效从主题读取（每行不再各自注册系统设置监听）；只有真正播放时才创建无限动画；
    // 柱高只在 Canvas 绘制阶段读取 → 播放中每帧只重绘这 18dp，不再让整行（封面/文字）每帧重组。
    val reduceMotion = xyz.cncnlp.soundwave.ui.common.LocalReduceMotion.current ?: MotionPolicy.rememberReduceMotion()
    val animate = MotionPolicy.allowDecorative(active, visible = xyz.cncnlp.soundwave.LocalDecorativeActive.current, reduce = reduceMotion)
    val heights: List<androidx.compose.runtime.State<Float>> = if (animate) {
        val t = rememberInfiniteTransition(label = "npb")
        listOf(
            t.animateFloat(0.22f, 1f, infiniteRepeatable(tween(240, easing = LinearEasing), RepeatMode.Reverse), label = "ba"),
            t.animateFloat(0.48f, 0.95f, infiniteRepeatable(tween(320, easing = LinearEasing), RepeatMode.Reverse), label = "bb"),
            t.animateFloat(0.18f, 1f, infiniteRepeatable(tween(180, easing = LinearEasing), RepeatMode.Reverse), label = "bc"),
            t.animateFloat(0.40f, 0.88f, infiniteRepeatable(tween(280, easing = LinearEasing), RepeatMode.Reverse), label = "bd"),
            t.animateFloat(0.30f, 0.78f, infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse), label = "be")
        )
    } else NowPlayingBarsIdle
    Canvas(Modifier.height(18.dp).width(18.dp)) {
        val gap = 1.6.dp.toPx()
        val barW = (size.width - gap * 4) / 5f
        val radius = androidx.compose.ui.geometry.CornerRadius(0.8.dp.toPx())
        heights.forEachIndexed { i, state ->
            val h = size.height * state.value.coerceIn(0f, 1f)
            drawRoundRect(
                Color.White,
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = radius
            )
        }
    }
}

private val NowPlayingBarsIdle: List<androidx.compose.runtime.State<Float>> =
    listOf(0.28f, 0.46f, 0.34f, 0.52f, 0.30f).map { androidx.compose.runtime.mutableFloatStateOf(it) }

@Composable
internal fun EmptyHint(msg: String, color: Color = Muted) {
    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = color, fontSize = 13.sp)
    }
}

@Composable
internal fun SearchLoadingHint() {
    // 验收 B 波：搜索主加载态改走通用骨架体系（与封面占位同色温/圆角/微光），不再用 Material 转圈。
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        xyz.cncnlp.soundwave.ui.common.SkeletonRows(
            count = 3,
            coverSize = 52.dp,
            coverShape = RoundedCornerShape(12.dp),
            titleFraction = 0.62f,
            subtitleFraction = 0.38f
        )
        Text(
            "搜索中…", color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally)
        )
    }
}

/** 搜索失败提示：明确错误原因 + 重试按钮；禁止把网络失败显示成“无结果”。 */
@Composable
internal fun SearchErrorHint(error: String, onRetry: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(error, color = Muted, fontSize = 13.sp)
        Text(
            "重试",
            color = primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onRetry).padding(8.dp)
        )
    }
}

/**
 * r657 第三轮第 30 项（用户 10-18，参考 1000073453）：歌手名左侧的小律动——3 根 2.5dp 珊瑚色圆头竖条，
 * 高 12dp、间距 2dp；三根不同周期/相位平滑起伏（FastOutSlowIn 往返），暂停或简洁动效时静止在低位。
 * 柱高只在 Canvas 绘制阶段读取，播放中每帧只重绘这 12dp，不触发整行重组。
 */
@Composable
internal fun NowPlayingEqBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = SoundwaveDesignPalette.Accent) {
    val reduceMotion = xyz.cncnlp.soundwave.ui.common.LocalReduceMotion.current ?: MotionPolicy.rememberReduceMotion()
    val animate = MotionPolicy.allowDecorative(playing, visible = xyz.cncnlp.soundwave.LocalDecorativeActive.current, reduce = reduceMotion)
    val heights: List<androidx.compose.runtime.State<Float>> = if (animate) {
        val t = rememberInfiniteTransition(label = "npeq")
        val ease = androidx.compose.animation.core.FastOutSlowInEasing
        listOf(
            t.animateFloat(0.30f, 0.80f, infiniteRepeatable(tween(460, easing = ease), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(0)), label = "ea"),
            t.animateFloat(0.45f, 1.00f, infiniteRepeatable(tween(380, easing = ease), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(170)), label = "eb"),
            t.animateFloat(0.25f, 0.70f, infiniteRepeatable(tween(540, easing = ease), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(320)), label = "ec")
        )
    } else NowPlayingEqIdle
    Canvas(modifier.height(12.dp).width(11.5.dp)) {
        val barW = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val radius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
        heights.forEachIndexed { i, state ->
            val h = (size.height * state.value.coerceIn(0f, 1f)).coerceAtLeast(barW)
            drawRoundRect(color, topLeft = Offset(i * (barW + gap), size.height - h), size = androidx.compose.ui.geometry.Size(barW, h), cornerRadius = radius)
        }
    }
}

/** 暂停时的低位静止高度（高低错落）。 */
private val NowPlayingEqIdle: List<androidx.compose.runtime.State<Float>> =
    listOf(0.32f, 0.5f, 0.24f).map { androidx.compose.runtime.mutableFloatStateOf(it) }

/** 原型 .badge.q：16dp 高、r5、左右 5dp、9sp 700，珊瑚字 + 浅珊瑚底（--soft）。用于正在播放行 / 热门歌曲的音质标识。 */
@Composable
internal fun QualityBadge(label: String, modifier: Modifier = Modifier, fg: Color = SoundwaveDesignPalette.Accent, bg: Color = designSoftColor()) {
    androidx.compose.foundation.layout.Box(
        modifier.height(ThemeTokens.TrackBadge.Height).clip(androidx.compose.foundation.shape.RoundedCornerShape(ThemeTokens.TrackBadge.Radius)).background(bg).padding(horizontal = ThemeTokens.TrackBadge.HorizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(label, color = fg, fontSize = ThemeTokens.TrackBadge.FontSize, lineHeight = ThemeTokens.TrackBadge.LineHeight, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/**
 * 音质 → 设计稿标识文案（原型 .badge.q 只给「无损」「HR」）：Hi-Res / 24bit = HR，FLAC = 无损，
 * Master / 杜比另给 MASTER / ATMOS；有损音质（320K 及以下）或未知时返回空串 = 不显示标识。
 */
internal fun designQualityBadgeLabel(quality: String): String = when (quality.trim().lowercase(java.util.Locale.ROOT)) {
    "master" -> "MASTER"
    "atmos", "dolby", "atmos_plus" -> "ATMOS"
    "hires", "hi-res", "flac24bit", "hr" -> "HR"
    "flac", "lossless", "sq" -> "无损"
    else -> ""
}


/** r658b 第 2 项：曲目行音质标识——已解析的实际音质优先，否则用平台列表返回的最高可用音质。 */
internal fun songQualityBadgeLabel(song: Song): String = designQualityBadgeLabel(song.actualQuality.ifBlank { song.maxQuality })

/** 原型 --soft：日间 #FFE4DF / 夜间 #4A2823（.badge.q 底色）。 */
@Composable
internal fun designSoftColor(): Color =
    ThemeTokens.TrackBadge.qualityFill(androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f)

/** .badge.b「NEW」：E8 后改暖琥珀字 + 琥珀软底（日 #F7E8C3 / 夜 #3A2E14），令牌见 TrackBadge。 */
@Composable
internal fun NewReleaseBadge(modifier: Modifier = Modifier) {
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
    QualityBadge("NEW", modifier, fg = ThemeTokens.TrackBadge.NewInk, bg = ThemeTokens.TrackBadge.newFill(dark))
}

/** 第 23 项 NEW 判定窗口：平台发布时间距今不超过 14 天。 */
internal const val NEW_RELEASE_WINDOW_DAYS = 14

/**
 * r657 第三轮第 23 项：新歌速递 NEW 标识规则——平台条目自带新歌标记（如酷我 isnew=1），
 * 或平台给出的发布时间在最近 [NEW_RELEASE_WINDOW_DAYS] 天内（不晚于现在 + 1 天容差）。没有平台数据时不显示。
 */
internal fun isRecentRelease(song: Song, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (song.platformNew) return true
    val t = song.publishTime
    if (t <= 0L) return false
    val day = 86_400_000L
    return t <= nowMs + day && nowMs - t <= NEW_RELEASE_WINDOW_DAYS * day
}
