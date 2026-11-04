package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.Tab
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs

/**
 * 样式预览卡的保真微缩渲染器（J 波立保真、N 波立舞台、本波 v3 立「统一标尺」）。
 *
 * v3 针对用户再次点名「太丑」重做视觉语言（结构/选项/回调/选中逻辑不在此文件）：
 * - 旧实现差在：几何全凭手感（封面 44/38/19dp、圆角 7/6/3dp 各行其是）；封面是橙褐绿
 *   浑浊渐变 + 黑胶环 + 音符 + 白条四层拼贴，微缩下只剩噪点；舞台顶部的白色高光条
 *   读作故障线；文字条宽度/透明度无层级节奏。
 * - v3 语言：单一舞台（r12、墨 9% 发丝边、黑系柔投影分深浅主题两档、无高光条）；
 *   单一封面（琥珀→珊瑚深→梅紫斜向渐变 + 右上斜阳圆盘 + 底部渐暗 + 白 14% 内描边，
 *   titled 时左下排版块），全六种微缩共用同一张「专辑」；圆角/间距按真实规格比例收敛
 *   （封面 r = 边长×≈0.15、胶囊全圆、轨道 2.5dp/粗轨 4dp），文字条只分标题 82–95% /
 *   副题 42–60% 两档透明度。
 * - 顺带修正 v2 的四处保真错误：① 播放器「标准·仅封面沉浸」原画成已下线的全屏沉浸
 *   （满幅封面 + 白控件），实为默认页骨架 + 整宽 Hero（高≈宽×1.12、底 42% 渐隐到页底）
 *   + 墨色标题/珊瑚控件；② 详情「沉浸封面」原画白字压黑渐变，实为封面渐隐到页面底色、
 *   标题/信息/按钮全是墨色坐 band 上、其下接歌曲列表；③ 详情「默认」原把封面铺满舞台，
 *   实为左右各 14dp 内嵌的 r28 英雄卡（卡内渐变 0/0.40 透 → 0.70 黑.55 → 底黑.86）；
 *   ④ 底栏迷你条播放键原画珊瑚线框圈，实为墨盘纸色三角 + 珊瑚进度弧环。
 * Apple 微缩自带深色背板（#101613 + 封面色晕），不随应用主题变浅——与本体一致。
 */

@Composable
private fun MiniBar(w: Dp, h: Dp, c: Color) {
    Box(Modifier.width(w).height(h).clip(RoundedCornerShape(999.dp)).background(c))
}

/** v3 单一舞台：r12 圆角、主题底、墨 9% 发丝边、黑系柔投影（深色主题加深）；去掉 v2 顶部白高光条。
 * bleed=true 时内容自带满幅表面（Apple 深背板 / 详情沉浸封面），框线改为叠加描边以免被内容盖住。 */
@Composable
private fun MiniStage(modifier: Modifier = Modifier, height: Dp, bleed: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val dark = settingsIsDark()
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().height(height)
            .shadow(
                5.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.32f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.16f)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .then(if (bleed) Modifier else Modifier.border(1.dp, line, shape))
    ) {
        content()
        if (bleed) Box(Modifier.matchParentSize().border(1.dp, line, shape))
    }
}

/** v3 单一封面：琥珀 → 珊瑚深 → 梅紫斜向渐变 + 右上斜阳圆盘（白 13%）+ 底部渐暗 +
 * 白 14% 内描边；titled 时左下排版块（珊瑚短线 + 标题白条 + 歌手淡白条）。
 * glyphSize 仅作斜阳圆盘的尺度参照（v2 的黑胶环/音符字形已退役）。游离封面的投影由调用方经 modifier 传入。 */
@Composable
fun MiniCoverArt(modifier: Modifier = Modifier, radius: Dp = 6.dp, glyphSize: Dp = 16.dp, titled: Boolean = false) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.clip(shape).background(
            Brush.linearGradient(
                listOf(Color(0xFFF0A35E), Color(0xFFD95A41), Color(0xFF4A2C44))
            )
        )
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = glyphSize * 0.38f, y = -glyphSize * 0.34f)
                .size(glyphSize * 1.55f).clip(CircleShape).background(Color.White.copy(alpha = 0.13f))
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.26f)
                    )
                )
            )
        )
        if (titled) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 5.dp, end = 5.dp)) {
                Box(Modifier.width(9.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(SoundwaveDesignPalette.Accent))
                Spacer(Modifier.height(2.5.dp))
                MiniBar(22.dp, 3.5.dp, Color.White.copy(alpha = 0.92f))
                Spacer(Modifier.height(2.dp))
                MiniBar(14.dp, 2.5.dp, Color.White.copy(alpha = 0.55f))
            }
        }
        Box(Modifier.matchParentSize().border(0.75.dp, Color.White.copy(alpha = 0.14f), shape))
    }
}

@Composable
private fun MiniProgress(fill: Float, track: Color, fillColor: Color, height: Dp, knob: Dp?, modifier: Modifier = Modifier, halo: Color? = null) {
    // 轨道单独裁形，拇指可溢出轨道高度（与真实进度条一致），故外层不 clip。
    Box(modifier.height(knob?.let { it + 3.5.dp } ?: height)) {
        Box(
            Modifier.fillMaxWidth().height(height).align(Alignment.Center).clip(RoundedCornerShape(999.dp)).background(track)
        ) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(fillColor))
        }
        if (knob != null) Box(Modifier.fillMaxWidth(fill).fillMaxHeight()) {
            Box(Modifier.align(Alignment.CenterEnd).size(knob + 3.5.dp), contentAlignment = Alignment.Center) {
                if (halo != null) Box(Modifier.fillMaxSize().clip(CircleShape).background(halo))
                Box(Modifier.size(knob).clip(CircleShape).background(fillColor))
            }
        }
    }
}

/** 默认系播放器控制行（真实结构：模式珊瑚 / 上一首 / 珊瑚实心圆播放键（带珊瑚柔光）/ 下一首 / 设备弱化），SpaceBetween。 */
@Composable
private fun MiniPlayerTransport(playSize: Dp, ink: Color, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Icon(DefaultPlayerGlyphs.Shuffle, null, tint = accent, modifier = Modifier.size(8.dp))
        Icon(DefaultPlayerGlyphs.Prev, null, tint = ink, modifier = Modifier.size(11.dp))
        Box(
            Modifier.size(playSize)
                .shadow(
                    4.dp, CircleShape, clip = false,
                    ambientColor = accent.copy(alpha = 0.45f),
                    spotColor = accent.copy(alpha = 0.55f)
                )
                .clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(DefaultPlayerGlyphs.Play, null, tint = Color.White, modifier = Modifier.size(playSize * 0.5f).offset(x = 0.5.dp))
        }
        Icon(DefaultPlayerGlyphs.Next, null, tint = ink, modifier = Modifier.size(11.dp))
        Icon(DefaultPlayerGlyphs.Device, null, tint = ink.copy(alpha = 0.45f), modifier = Modifier.size(8.dp))
    }
}

/** 默认系标题行：衬线标题条 + 歌手条（左列）+ 喜欢/更多（右双钮），默认与仅封面沉浸共用。 */
@Composable
private fun MiniPlayerTitleRow(ink: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MiniBar(29.dp, 3.5.dp, ink.copy(alpha = 0.82f))
            Spacer(Modifier.height(2.dp))
            MiniBar(18.dp, 2.5.dp, ink.copy(alpha = 0.45f))
        }
        Icon(DefaultPlayerGlyphs.Heart, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(8.dp))
        Spacer(Modifier.width(4.dp))
        Icon(DefaultPlayerGlyphs.More, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(8.dp))
    }
}

/** 播放器样式微缩：0 默认 / 1 标准·仅封面沉浸 / 2 Apple Music。 */
@Composable
fun PlayerStyleMiniature(index: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = SoundwaveDesignPalette.Accent
    val stageBg = MaterialTheme.colorScheme.background
    val dark = settingsIsDark()
    val track = ink.copy(alpha = 0.12f)
    MiniStage(modifier = modifier, height = 96.dp, bleed = index == 2) {
        when (index) {
            1 -> {
                // 标准·仅封面沉浸：整宽 Hero 封面（无圆角）+ 顶部状态栏罩 + 白抓手；
                // Hero 底部渐隐到页面底色，其下是默认页骨架（墨标题行 / 珊瑚进度 / 珊瑚控件行）。
                MiniCoverArt(Modifier.fillMaxWidth().height(57.dp), radius = 0.dp, glyphSize = 21.dp)
                Box(
                    Modifier.fillMaxWidth().height(57.dp).background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.58f to Color.Transparent,
                                1f to stageBg
                            )
                        )
                    )
                )
                Box(
                    Modifier.fillMaxWidth().height(16.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent)))
                )
                Box(Modifier.align(Alignment.TopCenter).padding(top = 3.5.dp)) {
                    MiniBar(15.dp, 2.5.dp, Color.White.copy(alpha = 0.40f))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 9.dp, end = 9.dp, bottom = 6.dp)) {
                    MiniPlayerTitleRow(ink, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(3.5.dp))
                    MiniProgress(0.42f, track, accent, 2.5.dp, 6.dp, Modifier.fillMaxWidth(), halo = ink.copy(alpha = 0.10f))
                    Spacer(Modifier.height(4.dp))
                    MiniPlayerTransport(17.dp, ink, accent, Modifier.fillMaxWidth())
                }
            }
            2 -> {
                // Apple：本体深色背板（#101613 系 + 封面色晕 + 向下压暗），不随应用主题变浅；
                // 方封面（0.78 宽比的微缩收敛）+ 白标题行 + 粗白进度 + 大字形传输键。
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF1E2621), Color(0xFF101613), Color(0xFF0B0E0C)))
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(Color(0xFFD95A41).copy(alpha = 0.16f), Color.Transparent))
                    )
                )
                Column(Modifier.fillMaxSize().padding(start = 9.dp, end = 9.dp, top = 4.dp, bottom = 5.dp)) {
                    MiniCoverArt(
                        Modifier
                            .shadow(
                                5.dp, RoundedCornerShape(6.dp), clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.35f),
                                spotColor = Color.Black.copy(alpha = 0.45f)
                            )
                            .size(34.dp),
                        radius = 6.dp, glyphSize = 14.dp
                    )
                    Spacer(Modifier.height(4.5.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            MiniBar(30.dp, 4.dp, Color.White.copy(alpha = 0.88f))
                            Spacer(Modifier.height(2.5.dp))
                            MiniBar(19.dp, 2.5.dp, Color.White.copy(alpha = 0.50f))
                        }
                        Icon(DefaultPlayerGlyphs.Heart, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(8.dp))
                        Spacer(Modifier.width(4.dp))
                        Icon(DefaultPlayerGlyphs.More, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    MiniProgress(0.55f, Color.White.copy(alpha = 0.22f), Color.White, 4.dp, 7.5.dp, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(1.5.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        Box(Modifier.align(Alignment.CenterStart)) { MiniBar(9.dp, 2.dp, Color.White.copy(alpha = 0.45f)) }
                        Box(Modifier.align(Alignment.Center)) { MiniBar(14.dp, 2.dp, Color.White.copy(alpha = 0.65f)) }
                        Box(Modifier.align(Alignment.CenterEnd)) { MiniBar(9.dp, 2.dp, Color.White.copy(alpha = 0.45f)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(DefaultPlayerGlyphs.Prev, null, tint = Color.White, modifier = Modifier.size(11.5.dp))
                        Spacer(Modifier.width(11.dp))
                        Icon(DefaultPlayerGlyphs.Play, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(11.dp))
                        Icon(DefaultPlayerGlyphs.Next, null, tint = Color.White, modifier = Modifier.size(11.5.dp))
                    }
                }
            }
            else -> {
                // 默认：主题底 + 顶部封面色晕；居中方封面（带排版块）、衬线标题行、细进度 + 音质胶囊、五钮控制行。
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(0.62f).background(
                        Brush.radialGradient(listOf(accent.copy(alpha = if (dark) 0.18f else 0.14f), Color.Transparent))
                    )
                )
                Column(Modifier.fillMaxSize().padding(top = 2.5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MiniBar(16.dp, 2.5.dp, ink.copy(alpha = 0.16f))
                    Spacer(Modifier.height(2.5.dp))
                    MiniCoverArt(
                        Modifier
                            .shadow(
                                6.dp, RoundedCornerShape(6.dp), clip = false,
                                ambientColor = Color.Black.copy(alpha = if (dark) 0.40f else 0.20f),
                                spotColor = Color.Black.copy(alpha = if (dark) 0.50f else 0.28f)
                            )
                            .size(38.dp),
                        radius = 6.dp, glyphSize = 15.dp, titled = true
                    )
                    Spacer(Modifier.height(3.5.dp))
                    MiniPlayerTitleRow(ink, Modifier.fillMaxWidth(0.86f))
                    Spacer(Modifier.height(3.dp))
                    MiniProgress(0.42f, track, accent, 2.5.dp, 6.dp, Modifier.fillMaxWidth(0.86f), halo = ink.copy(alpha = 0.10f))
                    Row(
                        Modifier.fillMaxWidth(0.86f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniBar(8.dp, 2.dp, ink.copy(alpha = 0.55f))
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp))
                                .border(0.75.dp, ink.copy(alpha = 0.38f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        ) {
                            MiniBar(10.dp, 1.8.dp, ink.copy(alpha = 0.60f))
                        }
                        MiniBar(8.dp, 2.dp, ink.copy(alpha = 0.55f))
                    }
                    Spacer(Modifier.height(3.dp))
                    MiniPlayerTransport(18.dp, ink, accent, Modifier.fillMaxWidth(0.90f))
                }
            }
        }
    }
}

/** 详情页样式微缩：0 默认 / 1 标准 / 2 沉浸封面。 */
@Composable
fun DetailStyleMiniature(index: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val stageBg = MaterialTheme.colorScheme.background
    val dark = settingsIsDark()
    MiniStage(modifier = modifier, height = 96.dp, bleed = index == 2) {
        when (index) {
            1 -> {
                // 标准：页面底上左置方封面（屏宽−110 的微缩收敛）、其下弱化眉题（onSurfaceVariant，非珊瑚）
                // + 大标题 + 元信息 + 操作行（珊瑚播放胶囊 + 两枚线框圆钮）。
                Column(Modifier.fillMaxSize().padding(start = 9.dp, top = 5.dp, end = 9.dp)) {
                    MiniCoverArt(
                        Modifier
                            .shadow(
                                6.dp, RoundedCornerShape(6.dp), clip = false,
                                ambientColor = Color.Black.copy(alpha = if (dark) 0.38f else 0.16f),
                                spotColor = Color.Black.copy(alpha = if (dark) 0.48f else 0.22f)
                            )
                            .size(48.dp),
                        radius = 6.dp, glyphSize = 18.dp, titled = true
                    )
                    Spacer(Modifier.height(6.dp))
                    MiniBar(15.dp, 2.5.dp, ink.copy(alpha = 0.42f))
                    Spacer(Modifier.height(3.dp))
                    MiniBar(42.dp, 5.dp, ink.copy(alpha = 0.85f))
                    Spacer(Modifier.height(2.5.dp))
                    MiniBar(26.dp, 2.5.dp, ink.copy(alpha = 0.45f))
                    Spacer(Modifier.height(5.5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.height(12.dp)
                                .shadow(
                                    3.dp, RoundedCornerShape(999.dp), clip = false,
                                    ambientColor = SoundwaveDesignPalette.Accent.copy(alpha = 0.40f),
                                    spotColor = SoundwaveDesignPalette.Accent.copy(alpha = 0.50f)
                                )
                                .clip(RoundedCornerShape(999.dp))
                                .background(SoundwaveDesignPalette.Accent)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(DefaultPlayerGlyphs.Play, null, tint = Color.White, modifier = Modifier.size(6.5.dp))
                            Spacer(Modifier.width(2.5.dp))
                            MiniBar(12.dp, 2.5.dp, Color.White.copy(alpha = 0.92f))
                        }
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.size(12.dp).clip(CircleShape).border(1.dp, ink.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(DefaultPlayerGlyphs.Download, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(6.5.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.size(12.dp).clip(CircleShape).border(1.dp, ink.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(DefaultPlayerGlyphs.More, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(6.5.dp))
                        }
                    }
                }
            }
            2 -> {
                // 沉浸封面：满幅封面（无圆角）顶部压暗、底部自 52% 起渐隐到页面底色；
                // 标题/信息/双胶囊是墨色坐 band 上（非白字压黑），其下接歌曲列表行。
                MiniCoverArt(Modifier.fillMaxWidth().height(52.dp), radius = 0.dp, glyphSize = 20.dp)
                Box(
                    Modifier.fillMaxWidth().height(52.dp).background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.52f to Color.Transparent,
                                1f to stageBg
                            )
                        )
                    )
                )
                Box(
                    Modifier.fillMaxWidth().height(18.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent)))
                )
                Column(
                    Modifier.fillMaxSize().padding(top = 44.dp, bottom = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MiniBar(36.dp, 4.5.dp, ink.copy(alpha = 0.88f))
                    Spacer(Modifier.height(2.5.dp))
                    MiniBar(24.dp, 2.5.dp, ink.copy(alpha = 0.50f))
                    Spacer(Modifier.height(4.5.dp))
                    MiniCapsuleRow(onDark = false, ink = ink)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(0.90f), verticalAlignment = Alignment.CenterVertically) {
                        MiniCoverArt(Modifier.size(9.dp), radius = 2.dp, glyphSize = 4.dp)
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            MiniBar(25.dp, 2.5.dp, ink.copy(alpha = 0.55f))
                            Spacer(Modifier.height(1.5.dp))
                            MiniBar(15.dp, 2.dp, ink.copy(alpha = 0.32f))
                        }
                        MiniBar(7.dp, 2.dp, ink.copy(alpha = 0.32f))
                    }
                }
            }
            else -> {
                // 默认：左右内嵌的英雄卡（真实 = 屏宽−28、r28、高 396）——封面铺满卡内、
                // 卡内渐变（0.40 前透 → 0.70 黑.55 → 底黑.86）、左上 chip、底部标题组 + 双胶囊。
                Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Box(
                        Modifier.fillMaxSize()
                            .shadow(
                                5.dp, RoundedCornerShape(8.dp), clip = false,
                                ambientColor = Color.Black.copy(alpha = if (dark) 0.38f else 0.16f),
                                spotColor = Color.Black.copy(alpha = if (dark) 0.48f else 0.22f)
                            )
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        MiniCoverArt(Modifier.fillMaxSize(), radius = 0.dp, glyphSize = 20.dp)
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Transparent,
                                        0.40f to Color.Transparent,
                                        0.70f to Color.Black.copy(alpha = 0.55f),
                                        1f to Color.Black.copy(alpha = 0.86f)
                                    )
                                )
                            )
                        )
                        Row(
                            Modifier.align(Alignment.TopStart).padding(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.Black.copy(alpha = 0.32f))
                                .border(0.75.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(DefaultPlayerGlyphs.Sparkle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(5.5.dp))
                            Spacer(Modifier.width(2.5.dp))
                            MiniBar(10.dp, 2.dp, Color.White.copy(alpha = 0.85f))
                        }
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp)) {
                            MiniBar(13.dp, 2.dp, Color.White.copy(alpha = 0.55f))
                            Spacer(Modifier.height(2.5.dp))
                            MiniBar(38.dp, 4.5.dp, Color.White.copy(alpha = 0.95f))
                            Spacer(Modifier.height(2.5.dp))
                            MiniBar(27.dp, 2.5.dp, Color.White.copy(alpha = 0.60f))
                            Spacer(Modifier.height(5.dp))
                            MiniCapsuleRow(onDark = true, ink = ink)
                        }
                    }
                }
            }
        }
    }
}

/** 详情页双胶囊（播放珊瑚实心带柔光 + 收藏线框）：onDark=true 时线框/字形走白色系（压封面），
 * false 时走墨色系（坐页面底 band 上，与沉浸样式本体一致）。 */
@Composable
private fun MiniCapsuleRow(onDark: Boolean, ink: Color) {
    val accent = SoundwaveDesignPalette.Accent
    val pillInk = if (onDark) Color.White else ink
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.height(11.5.dp)
                .shadow(
                    3.dp, RoundedCornerShape(999.dp), clip = false,
                    ambientColor = accent.copy(alpha = 0.40f),
                    spotColor = accent.copy(alpha = 0.50f)
                )
                .clip(RoundedCornerShape(999.dp)).background(accent).padding(horizontal = 5.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(DefaultPlayerGlyphs.Play, null, tint = Color.White, modifier = Modifier.size(6.dp))
            Spacer(Modifier.width(2.5.dp))
            MiniBar(11.dp, 2.5.dp, Color.White.copy(alpha = 0.92f))
        }
        Row(
            Modifier.height(11.5.dp).clip(RoundedCornerShape(999.dp))
                .border(1.dp, pillInk.copy(alpha = if (onDark) 0.55f else 0.35f), RoundedCornerShape(999.dp))
                .padding(horizontal = 5.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(DefaultPlayerGlyphs.Heart, null, tint = pillInk.copy(alpha = 0.9f), modifier = Modifier.size(6.dp))
            Spacer(Modifier.width(2.5.dp))
            MiniBar(11.dp, 2.5.dp, pillInk.copy(alpha = if (onDark) 0.75f else 0.55f))
        }
    }
}

/** 迷你条进度环播放键（真实结构：轨道环 + 珊瑚进度弧 + 墨盘纸色三角），两样式共用。 */
@Composable
private fun MiniRingPlay(size: Dp, ink: Color, accent: Color, paper: Color) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.6.dp.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawCircle(
                color = ink.copy(alpha = 0.14f),
                radius = (this.size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = accent, startAngle = -90f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Box(Modifier.size(size * 0.62f).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
            Icon(DefaultPlayerGlyphs.Play, null, tint = paper, modifier = Modifier.size(size * 0.34f).offset(x = 0.4.dp))
        }
    }
}

/** 底栏与迷你条微缩（两选项内容/文案按用户既定，只升渲染保真度）。 */
@Composable
fun DockStyleMiniature(capsule: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = SoundwaveDesignPalette.Accent
    val barBg = MaterialTheme.colorScheme.surface
    val paper = MaterialTheme.colorScheme.background
    val dark = settingsIsDark()
    val barShadowAmbient = Color.Black.copy(alpha = if (dark) 0.30f else 0.08f)
    val barShadowSpot = Color.Black.copy(alpha = if (dark) 0.40f else 0.12f)
    MiniStage(modifier = modifier, height = 64.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 迷你条：default = r22 方卡的微缩（r7）+ 方封面；capsule = 整条全圆 + 圆封面；播放键同为进度环墨盘。
            Row(
                Modifier.fillMaxWidth().height(26.dp)
                    .shadow(
                        3.dp, RoundedCornerShape(if (capsule) 999.dp else 7.dp), clip = false,
                        ambientColor = barShadowAmbient, spotColor = barShadowSpot
                    )
                    .clip(RoundedCornerShape(if (capsule) 999.dp else 7.dp)).background(barBg)
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (capsule) {
                    MiniCoverArt(Modifier.size(18.dp), radius = 999.dp, glyphSize = 7.dp)
                } else {
                    MiniCoverArt(
                        Modifier
                            .shadow(2.dp, RoundedCornerShape(4.dp), clip = false)
                            .size(18.dp),
                        radius = 4.dp, glyphSize = 7.dp
                    )
                }
                Spacer(Modifier.width(4.5.dp))
                Column(Modifier.weight(1f)) {
                    MiniBar(26.dp, 3.dp, ink.copy(alpha = 0.78f))
                    Spacer(Modifier.height(2.dp))
                    MiniBar(16.dp, 2.2.dp, ink.copy(alpha = 0.42f))
                }
                MiniRingPlay(15.dp, ink, accent, paper)
            }
            if (!capsule) {
                // 默认通栏：surface 横条（顶发丝线）+ 四列图标/标签，选中仅珊瑚色 + 珊瑚标签条（无指示器，与本体一致）。
                Box(Modifier.fillMaxWidth().height(24.dp).background(barBg)) {
                    Box(Modifier.fillMaxWidth().height(0.75.dp).background(ink.copy(alpha = 0.07f)))
                    Row(
                        Modifier.fillMaxSize().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(Tab.HOME, Tab.PLAYLISTS, Tab.SEARCH, Tab.SETTINGS).forEachIndexed { i, tab ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                xyz.cncnlp.soundwave.NavGlyph(tab, if (i == 0) accent else ink.copy(alpha = 0.40f), Modifier.size(8.5.dp), exposeDescription = false)
                                Spacer(Modifier.height(1.5.dp))
                                MiniBar(8.dp, 1.8.dp, if (i == 0) accent else ink.copy(alpha = 0.30f))
                            }
                        }
                    }
                }
            } else {
                // 圆角胶囊：主胶囊（选中段珊瑚渐变 + 白字形）+ 独立圆搜索钮，均带柔投影、无描边（与本体一致）。
                Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).height(24.dp)
                            .shadow(
                                3.dp, RoundedCornerShape(999.dp), clip = false,
                                ambientColor = barShadowAmbient, spotColor = barShadowSpot
                            )
                            .clip(RoundedCornerShape(999.dp)).background(barBg).padding(2.5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.fillMaxHeight().clip(RoundedCornerShape(999.dp))
                                .background(Brush.horizontalGradient(listOf(accent, SoundwaveDesignPalette.AccentDeep)))
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            xyz.cncnlp.soundwave.NavGlyph(Tab.HOME, Color.White, Modifier.size(8.dp), exposeDescription = false)
                            Spacer(Modifier.width(2.5.dp))
                            MiniBar(9.dp, 2.dp, Color.White)
                        }
                        Spacer(Modifier.width(6.5.dp))
                        xyz.cncnlp.soundwave.NavGlyph(Tab.PLAYLISTS, ink.copy(alpha = 0.40f), Modifier.size(8.dp), exposeDescription = false)
                        Spacer(Modifier.width(6.5.dp))
                        xyz.cncnlp.soundwave.NavGlyph(Tab.SETTINGS, ink.copy(alpha = 0.40f), Modifier.size(8.dp), exposeDescription = false)
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier.size(24.dp)
                            .shadow(3.dp, CircleShape, clip = false, ambientColor = barShadowAmbient, spotColor = barShadowSpot)
                            .clip(CircleShape).background(barBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Search, null, tint = accent, modifier = Modifier.size(9.5.dp))
                    }
                }
            }
        }
    }
}
