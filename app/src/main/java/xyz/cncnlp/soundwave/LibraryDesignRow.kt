package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.dragLift
import xyz.cncnlp.soundwave.ui.common.rippleGlow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import xyz.cncnlp.soundwave.ui.common.cssShadow
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs

/**
 * 设计稿 .library-item（音乐库列表行）：84dp 行高、60dp 封面圆角 14 + 柔和投影、15sp 粗标题；
 * 元信息行为「来源胶囊（DM Mono 10sp）+ 13sp 曲数」或单行 11sp 副标题；右侧 16dp chevron；行底 1dp 线（70%）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryDesignRow(
    title: String,
    cover: String,
    onLongPress: (() -> Unit)? = null,
    badge: String? = null,
    count: String = "",
    sub: String = "",
    fallbackKind: String = "playlist",
    divider: Boolean = false,
    /** r657 第二轮：共享封面过渡来源 id（歌单 id）；空 = 不参与。 */
    coverKey: String = "",
    /** ACCEPT5：本行正被长按拖动（Accent 淡染，同脚本页拖动态）。 */
    dragging: Boolean = false,
    /** ACCEPT5：长按拖动三件套（队列/脚本页同一范式）；onDrag 非空才挂手势，步进/落盘由调用方管。 */
    onDragStart: (() -> Unit)? = null,
    onDrag: ((dyPx: Float, pitchPx: Float) -> Unit)? = null,
    onDragEnd: ((cancelled: Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = if (dark) SoundwaveDesignPalette.NightMuted else Color(0xFF5F645F)
    val line = (if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line).copy(alpha = 0.7f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hapticView = androidx.compose.ui.platform.LocalView.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                // ACCEPT6-motiondraft（稿 .lift）：拖动中整行浮起大柔影（共享 dragLift；淡染仍为下方 background 的事）。
                .dragLift(dragging, RoundedCornerShape(14.dp))
                // 不裁剪整行：封面透明投影向下延伸 ~17dp，裁剪会切出一条生硬的灰边。
                .background(
                    when {
                        dragging -> SoundwaveDesignPalette.Accent.copy(alpha = 0.06f)
                        pressed -> ink.copy(alpha = 0.06f)
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(14.dp)
                )
                .rippleGlow(xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent.copy(alpha = 0.12f)) // r657 审计第 21 项（光圈只画在行矩形内，不裁剪封面投影）
                // ACCEPT5-press（稿 §4）：长按出菜单发 long 触感；带拖动的行（onDrag 非空）长按语义
                // 是「拿起」，已由下方手势的 dragStart 表达，此处不叠加，避免同一手势双震。
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = { if (onDrag == null) xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(hapticView); onLongPress?.invoke() })
                .then(
                    if (onDrag != null) Modifier.pointerInput(coverKey.ifEmpty { title }) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                xyz.cncnlp.soundwave.ui.common.AppHaptics.dragStart(hapticView)
                                onDragStart?.invoke()
                            },
                            onDragEnd = {
                                xyz.cncnlp.soundwave.ui.common.AppHaptics.dragEnd(hapticView)
                                onDragEnd?.invoke(false)
                            },
                            onDragCancel = { onDragEnd?.invoke(true) }
                        ) { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y, size.height.toFloat())
                        }
                    } else Modifier
                )
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shape = RoundedCornerShape(14.dp)
            // r657 修复：用户「在线歌单的封面应该有透明阴影」——去掉错位灰色衬卡，改为柔和透明投影（0 8 18 rgba(19,25,21,.16)）+ 1dp 白 30% 内描边。
            Box(
                Modifier.size(56.dp)
                    .then(xyz.cncnlp.soundwave.ui.motion.SharedCoverSourceModifier(coverKey))
                    .cssShadow(shape, Color(0xFF131915).copy(alpha = if (dark) 0.40f else 0.16f), 18.dp, 8.dp)
                    .clip(shape)
                    .background(if (dark) SoundwaveDesignPalette.NightSurface2 else SoundwaveDesignPalette.Surface2)
                    .border(1.dp, Color.White.copy(alpha = if (dark) 0.08f else 0.30f), shape),
                contentAlignment = Alignment.Center
            ) {
                if (cover.isNotBlank()) {
                    val ctx = LocalContext.current
                    val model = remember(cover) { CacheManager.coverModel(ctx, cover) ?: cover }
                    // J 波：非空封面统一 DefaultCover 垫底 + 失败重试一次（原裸图失败=空白方块，与无封面的线稿脸还不是同一张）。
                    xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 20)
                    var coverRetry by remember(cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                    androidx.compose.runtime.key(coverRetry) {
                        AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            onError = { if (coverRetry == 0) coverRetry = 1 })
                    }
                } else xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 20) // ACCEPT5 收编：无封面与同行有封面分支同脸（黑胶母题），此前是 LibGlyph 线稿字形
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    title, color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp,
                    fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().loopMarquee()
                )
                if (badge != null || count.isNotEmpty()) {
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (badge != null) {
                            // r657 修复：原型珊瑚标签（accent 10% 底 + accent 字 9.5sp）。
                            Box(
                                Modifier.height(17.dp).clip(RoundedCornerShape(5.dp))
                                    .background(SoundwaveDesignPalette.Accent.copy(alpha = 0.10f))
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(badge, color = designAccentTextColor(), fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (count.isNotEmpty()) Text(count, color = muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                } else if (sub.isNotEmpty()) {
                    Text(
                        sub, color = muted, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = DesignFonts.Manrope,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(DesignLineGlyphs.Chevron, null, tint = muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(2.dp))
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(line)))
    }
}
