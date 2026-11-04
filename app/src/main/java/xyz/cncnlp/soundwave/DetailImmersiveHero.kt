package xyz.cncnlp.soundwave

import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import xyz.cncnlp.soundwave.ui.common.frostedCircleBackdrop
import xyz.cncnlp.soundwave.ui.common.localGlassSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs

/**
 * r657 第三轮 b：详情「沉浸」样式（用户参考 1000065908：Apple Music 专辑页）。
 * - 封面满幅铺到屏幕顶部（含状态栏区域），无圆角、无卡片边距；
 * - 封面底部渐隐到从封面取得的色带 [immersiveDetailBandColor]，标题 / 元信息 / 按钮坐在色带上，
 *   色带再在按钮下方渐隐到页面背景，曲目列表（无编号）照常接在下面；
 * - 返回 / 更多由 [ImmersiveDetailFloatingBar] 浮在封面上（半透明圆钮），滚过封面后让位给折叠磨砂顶栏。
 */
@Composable
internal fun ImmersiveDetailHero(
    title: String,
    subtitle: String,
    meta: List<String>,
    cover: String,
    coverModel: Any?,
    tint: Color,
    favorited: Boolean,
    showFavorite: Boolean,
    playEnabled: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onDownload: (() -> Unit)?,
    onMore: () -> Unit,
    /** r658c #6（用户 10-19）：歌手页把「收藏」换成同款白底黑字「随机播放」胶囊。 */
    onShuffle: (() -> Unit)? = null,
    actionTopGap: androidx.compose.ui.unit.Dp = 10.dp
) {
    // Fade completely into the actual page above the title, with no separate colour band.
    val band = androidx.compose.material3.MaterialTheme.colorScheme.background
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
    val actionGlass = xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState()
    val coverHeight = immersiveDetailCoverHeightDp(LocalConfiguration.current.screenWidthDp).dp - 16.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((-12).dp)) {
        Box(Modifier.fillMaxWidth().height(coverHeight).background(band)) {
            DetailCoverImage(cover, coverModel, "$title 封面", Modifier.fillMaxSize())
            // 状态栏 / 浮动按钮下的轻压暗，保证白色图标在亮封面上可读。
            Box(Modifier.fillMaxWidth().height(128.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent))))
            // 底部渐隐到取色带（多段缓动，不画硬边）。
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*immersiveDetailCoverFadeStops(band))))
        }
        Column(
            Modifier.fillMaxWidth()
                // r658b #8（用户 10-19）：色带不再渐变到不透明纸色，而是自身透明度缓降到 0，
                // 露出页面真实底色——浅色 / 夜间 / 各主题以及「透明磨砂背景」（页底为封面模糊）下都自然过渡，无色阶断层。
                .background(band)
                .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title, color = ink, fontSize = if (title.length > 12) 24.sp else 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold,
                fontFamily = DesignFonts.Manrope, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            // One information line: author · track count · platform. The type label is not repeated.
            val infoLine = (listOf(subtitle) + meta).filter { it.isNotBlank() }.joinToString(" · ")
            if (infoLine.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(infoLine, color = ink.copy(alpha = 0.72f), fontSize = 14.sp, lineHeight = 20.sp,
                    fontFamily = DesignFonts.Manrope, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(actionTopGap))
        }
        // r658f（用户 10-20：封面色带过渡到大标题附近即可）：实色色带只托住标题 / 元信息；按钮所在区域开始渐隐，
        // 一直淡到按钮下方 56dp 处为透明（总高度不变，曲目列表位置不变），不再整块色带一直铺到「曲目列表」。
        Box(Modifier.fillMaxWidth()) {
            // The action backdrop is a sibling of the pills, never their own blur source.
            Box(Modifier.matchParentSize().localGlassSource(actionGlass).background(
                Brush.verticalGradient(*immersiveDetailBandFadeStops(band))))
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
            // r658b 第 1 项（用户 10-19，1000073542）：封面下去掉「下载 / ⋯」圆钮（⋯ 已在右上浮动栏）；
            // 收藏改为「播放全部」右侧的白底黑字胶囊（同高 46dp）。
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                DesignPlayPill("播放全部", 15.sp, playEnabled, 15.dp, onPlayAll, startPad = 22.dp, endPad = 26.dp, height = 46.dp, gap = 8.dp)
                if (onShuffle != null) {
                    Spacer(Modifier.width(12.dp))
                    ImmersiveShufflePill(playEnabled, onShuffle, glass = actionGlass)
                } else if (showFavorite) {
                    Spacer(Modifier.width(12.dp))
                    ImmersiveFavoritePill(favorited, onToggleFavorite, glass = actionGlass)
                }
            }
            }
        }
    }
}

/** r658c #6：歌手页「随机播放」白底黑字胶囊，与收藏胶囊同尺寸。 */
@Composable
internal fun ImmersiveShufflePill(enabled: Boolean, onClick: () -> Unit, height: androidx.compose.ui.unit.Dp = 46.dp,
    glass: xyz.cncnlp.soundwave.ui.common.LocalGlassState? = null) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Row(
        Modifier.height(height)
            .frostedCircleBackdrop(glass, colors.surface.copy(alpha = 0.62f), 0.86f, CircleShape)
            .border(1.dp, colors.outline.copy(alpha = 0.15f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "随机播放"; role = Role.Button }
            .padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Shuffle, null, tint = colors.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text("随机播放", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
    }
}

/** r658b：沉浸详情「收藏」白底黑字胶囊；已收藏时心形为珊瑚实心、文案「已收藏」。
 * accept6-uipolish2（用户 2026-11-04 22:21）：两态等尺寸——最小宽 112dp（「已收藏」三字自然宽
 * = 左距 18 + 图标 18 + 间距 7 + 文案 ≈45 + 右距 22 ≈ 110dp，留 2dp 余量），内容居中；
 * 字号缩放放大时两态同步按自然宽增长，仍保持等宽。高由调用点定（44/46dp），不随状态变。 */
@Composable
internal fun ImmersiveFavoritePill(favorited: Boolean, onClick: () -> Unit, height: androidx.compose.ui.unit.Dp = 46.dp,
    glass: xyz.cncnlp.soundwave.ui.common.LocalGlassState? = null) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Row(
        Modifier.height(height).widthIn(min = 112.dp)
            .pressScale(PressMotion.SCALE_DEFAULT)
            .frostedCircleBackdrop(glass, colors.surface.copy(alpha = 0.62f), 0.86f, CircleShape)
            .border(1.dp, colors.outline.copy(alpha = 0.15f), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = if (favorited) "取消收藏" else "收藏"; role = Role.Button }
            .padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(if (favorited) DesignGlyphs.HeartFilled else DesignGlyphs.HeartOutline, null, tint = if (favorited) SoundwaveDesignPalette.Accent else colors.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (favorited) "已收藏" else "收藏", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
    }
}

/**
 * 沉浸样式的顶层叠加：浮动圆钮 + 状态栏图标色。只在本作用域读取滚动进度，不会让整个详情列表重组。
 * 封面在状态栏下方：折叠页头背景过半前状态栏用白色图标（false），过半后交还根策略（null，随页面底色切深色图标）。
 * r658c U #1：浮动圆钮按 1 - heroProgress 连续淡出（与折叠页头的渐显同步交接），不再按阈值定时淡入淡出。
 */
@Composable
internal fun ImmersiveDetailChrome(
    topbarOn: State<Boolean>, onBack: () -> Unit, onMore: (() -> Unit)?,
    onStatusBarAppearance: (Boolean?) -> Unit, modifier: Modifier = Modifier,
    heroProgress: State<Float>? = null,
    glass: xyz.cncnlp.soundwave.ui.common.LocalGlassState? = null,
    title: String = "",
    onSearch: (() -> Unit)? = null
) {
    val collapsed = topbarOn.value
    SideEffect { onStatusBarAppearance(if (collapsed) null else false) }
    val fade = heroProgress
    val hidden by androidx.compose.runtime.remember(fade) { androidx.compose.runtime.derivedStateOf { (fade?.value ?: 0f) >= 0.999f } }
    if (hidden) return
    ImmersiveDetailFloatingBar(
        visible = fade != null || !collapsed, onBack = onBack, onMore = onMore, glass = glass, title = title, onSearch = onSearch,
        modifier = if (fade != null) modifier.graphicsLayer { alpha = 1f - fade.value } else modifier
    )
}

/** 浮在沉浸封面上的透明磨砂返回 / 更多圆钮；[visible] = 折叠顶栏尚未完全出现。 */
@Composable
internal fun ImmersiveDetailFloatingBar(
    visible: Boolean, onBack: () -> Unit, onMore: (() -> Unit)?, modifier: Modifier = Modifier,
    glass: xyz.cncnlp.soundwave.ui.common.LocalGlassState? = null,
    title: String = "",
    onSearch: (() -> Unit)? = null
) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // 与默认模式页头（TodayDesignHeader：状态栏下 12dp、42dp 行）同位置。
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = top + 12.dp).height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            // r658c U #3（用户 10-20）：沉浸 / 默认模式左右圆钮统一为透明磨砂圆钮（原型 .cbtn.onimg：半透明 + 14dp 背后模糊 + 白色细描边 + 白色图标），
            // 替换 r658c #5（4fa3aaa2）的不透明表面样式。
            // r658d（用户 10-20）：顶部左侧返回箭头去除（系统返回手势仍可用）；上滑后的折叠栏保留返回。
            // r658f（用户 10-20）：沉浸封面上左侧显示标题，右侧加「搜索曲目」圆钮（在「更多」左边）。
            if (title.isNotBlank()) {
                // r658f（用户 10-20：标题风格要与首页 / 详情页头一致）：同 TodayDesignHeader 标题——衬线 31sp SemiBold、
                // 字距 -0.045em、行高 42sp，封面上用白色 + 轻投影保证可读。
                Text(
                    title, color = Color.White, fontSize = 31.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = (-0.045).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 10f)),
                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp).offset(y = (-4).dp)
                )
            } else Spacer(Modifier.weight(1f))
            if (onSearch != null) {
                xyz.cncnlp.soundwave.ui.common.DesignHeaderCircleButton(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.Search, "搜索曲目", overMedia = true, glass = glass, onClick = onSearch)
                if (onMore != null) Spacer(Modifier.width(10.dp))
            }
            if (onMore != null) xyz.cncnlp.soundwave.ui.common.DesignHeaderCircleButton(androidx.compose.material.icons.Icons.Filled.MoreHoriz, "更多操作", overMedia = true, glass = glass, onClick = onMore)
        }
    }
}


/** 封面高度：宽度 × 1.06，限制在 340–520dp，窄屏不会被拉得过长。 */
// r658f（用户 10-20：封面没那么高，不用延伸那么多）：按封面真实比例 1:1，不再拉高到 1.06 倍。
internal fun immersiveDetailCoverHeightDp(screenWidthDp: Int): Int = screenWidthDp.coerceIn(320, 480)

/**
 * 封面取色 → 色带：向黑色压 38%，过亮时继续压到亮度 ≤ 0.17（低于 detailAdaptiveTextForeground 的 0.179 分界），保证白字可读且不会是刺眼的高饱和亮色；
 * 纯函数，供单测覆盖（白封面、黑封面、高饱和封面）。
 */
internal fun immersiveDetailBandColor(tint: Color): Color {
    var c = lerp(tint.copy(alpha = 1f), Color.Black, 0.38f)
    var guard = 0
    while (c.luminance() > 0.17f && guard < 12) { c = lerp(c, Color.Black, 0.2f); guard++ }
    return c
}

/** r658d：沉浸色带下方渐隐带高度。 */
// r658f（用户 10-20）：渐隐带 180→72dp，色带在按钮下方就收住，不再盖到曲目列表。
internal val ImmersiveBandFadeHeight = 72.dp

/** r658d：smootherstep（6t⁵−15t⁴+10t³），两端一阶二阶导数都为 0，渐变起止处没有可见的“折痕”。 */
internal fun smootherStep(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * x * (x * (x * 6f - 15f) + 10f) }

/** 封面底部 → 色带的缓动渐隐色标（纯函数，便于单测）。r658d：从封面 30% 高度开始、16 段 smootherstep，交界不再成线。 */
internal fun immersiveDetailCoverFadeStops(band: Color): Array<Pair<Float, Color>> {
    val start = 0.52f
    val steps = 16
    return Array(steps + 2) { k ->
        if (k == 0) 0f to band.copy(alpha = 0f)
        else { val t = (k - 1).toFloat() / steps; (start + (1f - start) * t) to band.copy(alpha = smootherStep(t)) }
    }
}

/**
 * 标题区色带 → 页面底色：只降色带透明度，最终完全透明，交给页面自身背景（纸色 / 夜色 / 磨砂）。
 * r658d（用户 10-20：交界太明显）：渐隐带 56→180dp、20 段 smootherstep，深色带到浅纸色不再出现灰缝。
 */
internal fun immersiveDetailBandFadeStops(band: Color): Array<Pair<Float, Color>> {
    val steps = 20
    return Array(steps + 1) { k -> val t = k.toFloat() / steps; t to band.copy(alpha = 1f - smootherStep(t)) }
}

