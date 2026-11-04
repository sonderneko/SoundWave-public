package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ===== 页顶栏 + 竖向音波 + 居中 Tab 行 + 歌单迷你卡（自 MainActivity 迁入；private→internal） =====

/**
 * 页头行顶部留白（用户 2026-11-04 22:44：顶栏整行上移约 6dp——由 12dp 收至 6dp，
 * 从顶栏自身留白里扣，状态栏安全区不动）。详情大页头（TodayDesignHeader）与沉浸浮动栏同值互锁，
 * 主页滚动居中小标题行亦按此几何对齐，保证标题不跳位。
 */
internal val PageHeaderTopPad = 6.dp

@Composable
internal fun AppPageHeader(
    title: String,
    loggedIn: Boolean,
    avatarPath: String = "",
    onProfileClick: () -> Unit = {},
    onAvatarLongClick: () -> Unit = {},
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showAvatar: Boolean = false,
    subtitle: String = "",
    startPadding: androidx.compose.ui.unit.Dp = 16.dp,
    /** 头像直径；首页传入与平台切换按钮同尺寸(40dp)。 */
    avatarSizeDp: Float = LayoutTokens.AvatarDp,
    /** 标题字号；首页「首页/排行榜/歌单」用更大的设计稿字号。 */
    titleSizeSp: Int = LayoutTokens.PageTitleSp,
    /** 首页标题使用设计稿衬线字体。 */
    serifTitle: Boolean = false,
    /** 设计稿 v2 页头（首页/今日推荐）：31sp 衬线 600、42dp 行高，左右 14dp，上距 8dp。 */
    designTitle: Boolean = false,
    /**
     * accept6-topbars（用户 2026-11-04 22:40）：主页滚动联动——非空时顶栏带随页列表滚动磨砂化、
     * 大标题交叉淡出并淡入同几何居中小标题（见 MainTabTopChrome.kt）；为空时行为与旧版完全一致。
     */
    chromeState: MainTabTopChromeState? = null,
    chromeGlass: xyz.cncnlp.soundwave.ui.common.LocalGlassState? = null
) {
    // J 波（截图 1000075125 实证）：页头本体保持无渐变，但必须有不透明底——此前纯透明 +
    // 右上圆钮为半透明磨砂，列表滚动到页头下沿时被裁的半行会从标题区透出/压在钮后。
    // 补主题 background 不透明底（页头在 Column 流内，静止时观感不变；滚动时标题区成完整遮挡）。
    Box(
        Modifier.fillMaxWidth()
            .then(if (chromeState == null) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
    ) {
        if (chromeState != null) MainTabChromeBackdrop(chromeState, chromeGlass)
        Row(
            if (designTitle) Modifier.fillMaxWidth().statusBarsPadding().padding(start = startPadding, end = 14.dp, top = PageHeaderTopPad).height(42.dp)
            else Modifier.fillMaxWidth().statusBarsPadding().height(LayoutTokens.TopBarH.dp)
                .padding(start = startPadding, end = 16.dp)
                // accept6-topbars（用户 2026-11-04 22:44）：非设计分支同样整行上移 6dp（当前无调用点，组件内保持一致）。
                .offset(y = (-6).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading.invoke(this)
                Spacer(Modifier.width(6.dp))
            }
            Column(
                Modifier.weight(1f).then(
                    if (chromeState != null) Modifier.graphicsLayer { alpha = 1f - chromeState.titleProgress.value } else Modifier
                ),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (designTitle) 31.sp else titleSizeSp.sp,
                    letterSpacing = if (designTitle) (-0.02).em else androidx.compose.ui.unit.TextUnit.Unspecified,
                    lineHeight = if (designTitle) 42.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    // r657 修复：页头中文衬线粗体（Noto Serif SC Bold，原型 31sp）；系统 Serif 在真机上落到细 Regular。
                    fontFamily = if (serifTitle || designTitle) xyz.cncnlp.soundwave.ui.common.DesignFonts.SerifCJK else androidx.compose.ui.text.font.FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (designTitle) Modifier.offset(y = (-4).dp) else Modifier
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing.invoke(this)
            }
            if (showAvatar) {
                Spacer(Modifier.width(if (designTitle) 9.dp else 8.dp))
                UserAvatarBadge(
                    loggedIn = loggedIn,
                    avatarPath = avatarPath,
                    onClick = onProfileClick,
                    onLongClick = onAvatarLongClick,
                    sizeDp = avatarSizeDp
                )
            }
        }
        if (chromeState != null) MainTabChromeCenteredTitle(chromeState, title)
    }
}

@Composable
internal fun PlatformSwitchButton(
    contentDescription: String,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(42.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.MusicNote, contentDescription, tint = primary, modifier = Modifier.size(23.dp))
    }
}

@Composable
fun PageTopBar(
    title: String,
    loggedIn: Boolean,
    user: String,
    onLogin: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
    titleSize: Int = 20,
    showTitle: Boolean = true,
    avatarPath: String = "",
    onGlyphClick: (() -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    // 蓝白顶部柔光；固定行高和命中区不变。
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to primary.copy(alpha = 0.22f),
                            0.40f to primary.copy(alpha = 0.10f),
                            1.0f to Color.Transparent
                        )
                    )
                )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(LayoutTokens.TopBarH.dp)
                // 24dp 图案置于 42dp 命中区：start=7dp 时图案左缘正好是 16dp。
                .padding(start = 5.dp, end = (LayoutTokens.TopVisualEdge + 2).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左：42dp 固定操作区（品牌/菜单 glyph）
            Box(
                Modifier.size(42.dp).then(if (onGlyphClick != null) Modifier.clickable { onGlyphClick.invoke() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                UnifiedTopGlyph(MaterialTheme.colorScheme.onBackground)
            }
            // 中：页面身份标题，视觉居中于左右等宽操作区之间
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (showTitle && title.isNotBlank()) Text(
                    title,
                    color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f) else titleColor,
                    fontSize = titleSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 右：42dp 固定操作区（trailing + 头像）
            Box(Modifier.size(42.dp), contentAlignment = Alignment.CenterEnd) {
                trailing?.invoke()
            }
            UserAvatarBadge(loggedIn = loggedIn, avatarPath = avatarPath, onClick = onLogin, sizeDp = LayoutTokens.AvatarDp)
        }
    }
}

@Composable
internal fun VerticalWaveGlyph(tint: Color) {
    // 精致竖向音波：双层光晕 + 圆角柱 + 中心高亮
    Canvas(Modifier.size(width = 33.dp, height = 27.dp)) {
        val w = size.width
        val h = size.height
        val bars = 5
        val gap = w / (bars * 2.15f)
        val bw = gap * 1.05f
        val ratios = floatArrayOf(0.38f, 0.68f, 1f, 0.58f, 0.34f)
        for (i in 0 until bars) {
            val bh = h * ratios[i]
            val left = gap * 0.55f + i * (bw + gap)
            val top = (h - bh) / 2f
            // soft glow
            drawRoundRect(
                color = tint.copy(alpha = 0.18f),
                topLeft = Offset(left - 1.2f, top - 1.2f),
                size = androidx.compose.ui.geometry.Size(bw + 2.4f, bh + 2.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius((bw + 2.4f) / 2f)
            )
            drawRoundRect(
                color = tint.copy(alpha = 0.92f),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(bw, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2f)
            )
            // highlight strip
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(left + bw * 0.22f, top + bh * 0.12f),
                size = androidx.compose.ui.geometry.Size(bw * 0.28f, bh * 0.55f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.14f)
            )
        }
    }
}


@Composable
internal fun CenteredTabRow(
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    colored: Boolean = false,
    fontSize: Int = 12,
    withBg: Boolean = true
) {
    // 缩小 1/3 + 选中边缘光影
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (k, l) ->
            val on = selected == k
            val color = when {
                on && colored -> Color.White
                on -> Color.White
                colored -> platformColor(k).copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f)
            }
            val mod = Modifier
                .clip(RoundedCornerShape(999.dp))
                .then(
                    if (on) Modifier
                        .shadow(8.dp, RoundedCornerShape(999.dp), ambientColor = ThemeTokens.AccentCoral.copy(alpha = 0.45f), spotColor = Accent2.copy(alpha = 0.35f))
                        .background(Brush.horizontalGradient(listOf(ThemeTokens.AccentCoral, Accent2)))
                    else Modifier
                )
                .clickable { onSelect(k) }
                .padding(horizontal = 8.dp, vertical = 5.dp)
            Text(l, color = color, fontSize = fontSize.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = mod)
        }
    }
}

@Composable
internal fun PlaylistMiniCard(pl: Playlist, onOpen: (Playlist) -> Unit) {
    val cover = playlistDisplayCover(pl)
    val ctx = LocalContext.current
    val coverModel = remember(cover) { CacheManager.coverModel(ctx, cover) ?: cover }
    Column(Modifier.width(68.dp).padding(end = 8.dp).clickable { onOpen(pl) }) {
        Box(Modifier.size(53.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是列表图标、加载时透明无底）。
            DefaultCoverPlaceholder(Modifier.fillMaxSize())
            if (cover.isNotEmpty()) AsyncImage(model = coverModel, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(4.dp))
        Text(pl.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
