package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.editorialCardShadow
import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import xyz.cncnlp.soundwave.ui.common.headerControlShadow
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.particleBurst
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import xyz.cncnlp.soundwave.ui.common.cssShadow
import xyz.cncnlp.soundwave.ui.common.swMarquee
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

/**
 * 封面取色：从本地缓存封面采样平均色（数字专辑/新碟首发背景随封面取色）。
 * 每调用方按卡片 id 独立 remember，避免上一首取色串到下一首。
 */
internal suspend fun coverToneColor(context: Context, cover: String): Color? = withContext(Dispatchers.IO) {
    if (cover.isBlank()) return@withContext null
    val local = runCatching { CacheManager.ensureCover(context, cover) }.getOrNull() ?: return@withContext null
    runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        BitmapFactory.decodeFile(local, options)?.let { bmp ->
            try {
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                val sx = (bmp.width / 10).coerceAtLeast(1)
                val sy = (bmp.height / 10).coerceAtLeast(1)
                for (y in 0 until bmp.height step sy) {
                    for (x in 0 until bmp.width step sx) {
                        val p = bmp.getPixel(x, y)
                        r += android.graphics.Color.red(p)
                        g += android.graphics.Color.green(p)
                        b += android.graphics.Color.blue(p)
                        n++
                    }
                }
                if (n == 0L) null else Color(red = r / n / 255f, green = g / n / 255f, blue = b / n / 255f)
            } finally { bmp.recycle() }
        }
    }.getOrNull()
}

/** Pause decorative timers while the window is unfocused, the route is hidden or motion is reduced. */
@Composable
internal fun homeCarouselActive(visible: Boolean): Boolean {
    val view = LocalView.current
    var windowFocused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { windowFocused = it }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
    return visible && windowFocused && !MotionPolicy.rememberReduceMotion()
}

/** Does not consume the gesture: the child still receives clicks and pager swipes. */
internal fun Modifier.homeCarouselTouch(onHeld: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onHeld(true)
        try { waitForUpOrCancellation() } finally { onHeld(false) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomePagerAutoAdvance(pager: PagerState, active: Boolean, held: Boolean, interval: Long = 3800L) {
    // r675（SW-08-03）：系统 / 应用减少动画时仍按间隔翻页，但直接切页不做滑动动画（与三宫格 tween(0) 一致）。
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val hovered by pager.interactionSource.collectIsHoveredAsState()
    val focused by pager.interactionSource.collectIsFocusedAsState()
    // Restart a full interval after every manual settlement or interaction, not on a global clock.
    // 不以 isScrollInProgress 作为 key：否则自动切页开始滚动的瞬间协程即被重启并取消动画，卡在半页、无法循环。
    LaunchedEffect(active, held, dragged, hovered, focused, pager.settledPage, pager.pageCount, reduceMotion) {
        if (active && !held && !dragged && !hovered && !focused && pager.pageCount > 1) {
            while (true) {
                delay(interval)
                if (pager.isScrollInProgress) continue
                val next = (pager.currentPage + 1) % pager.pageCount
                if (reduceMotion) pager.scrollToPage(next)
                else pager.animateScrollToPage(next, animationSpec = tween(620, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        }
    }
}

/* ───────────────────────── 设计稿 v2 首页（layer.css 实测值，412dp 宽视口） ───────────────────────── */

/** 首页/今日推荐内容底部安全区：底栏宿主固定 148dp（迷你条 + 导航），再留 16dp 呼吸。 */
internal val HomeDesignBottomInset: Dp = (PLAYER_DOCK_HOST_HEIGHT_DP + 12).dp

private val HeroInk = Color(0xFF111511)
private val HeroPaper = Color(0xFFF8F8F2)
private val CardShadow = Color(0xFF131915)

/** 页头平台切换 .platform-quick：42dp 白色圆、1dp 描边、柔和投影，下拉箭头。 */
@Composable
internal fun HomePlatformButton(
    /** r658b #19：原型搜索结果页平台按钮用 ic('filter')（三条递减横线），其余页 ic('chevd')。 */
    filterGlyph: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(42.dp)
            .headerControlShadow(CircleShape) // r674：页顶右上角平台按钮统一外侧投影
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (filterGlyph) Icon(PlatformFilterGlyph, "切换平台", tint = colors.onSurface, modifier = Modifier.size(20.dp))
        else Icon(androidx.compose.material.icons.Icons.Outlined.KeyboardArrowDown, "切换平台", tint = colors.onSurface, modifier = Modifier.size(20.dp))
    }
}

/**
 * 平台切换（首页 / 排行榜 / 歌单广场 / 音乐库 / 搜索页统一）。
 * r658b #19：按原型 pick 抽屉（眉题 + 标题 + 说明 + 选项卡网格）替代原先锚定在按钮下方的小弹层；签名保留以免改动调用方。
 */
@Composable
internal fun DesignPlatformPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<Pair<String, String>>,
    current: String,
    title: String = "切换音源",
    eyebrow: String = "Music source",
    subtitle: String = "",
    columns: Int = 1,
    /** 选项说明（原型 OPTS.o 第二列）；默认当前项为「当前」。 */
    optionSub: (String) -> String = { key -> if (key == current) "当前" else "" },
    /** 选项主标题；默认原样使用 options 的文案。 */
    optionTitle: (String, String) -> String = { _, label -> label },
    onPick: (String) -> Unit
) {
    if (!expanded) return
    xyz.cncnlp.soundwave.ui.common.DesignPlatformPickSheet(
        eyebrow = eyebrow, title = title, subtitle = subtitle,
        options = options.map { (k, v) -> xyz.cncnlp.soundwave.ui.common.PlatformPickOption(k, optionTitle(k, v), optionSub(k)) },
        current = current, columns = columns, onDismiss = onDismiss, onPick = onPick
    )
}

/** 区块标题行 .row-head：20sp/700 标题 + 右侧 DM Mono 11sp 链接。上 32dp、下 15dp。 */
@Composable
internal fun HomeSectionHeader(
    title: String,
    link: String = "",
    linkSizeSp: Float = 11f,
    modifier: Modifier = Modifier,
    topGap: Dp = 32.dp,
    onTitle: (() -> Unit)? = null,
    onLink: (() -> Unit)? = null,
    /** r658b 第 3 项（用户 10-19）：数字专辑 / 新碟首发 / 热门歌手 右侧英文用珊瑚红。 */
    linkAccent: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        // 1.0.655：全局区块间距略收紧（上距 -6dp、下距 15→12dp）。
        modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = (topGap - 6.dp).coerceAtLeast(0.dp), bottom = 12.dp).height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, color = colors.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            fontFamily = DesignFonts.Manrope, letterSpacing = (-0.03).em, maxLines = 1,
            modifier = Modifier.weight(1f).then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier)
        )
        if (link.isNotBlank()) Text(
            // r658c #7（用户 10-19）：所有区块右侧英文统一黑色（跟随主题前景色）、11sp、Medium；不再按区块区分珊瑚红 / 字号。
            link, color = colors.onBackground, fontSize = 11.sp, fontFamily = DesignFonts.DmMono,
            fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.then(if (onLink != null) Modifier.clickable(onClick = onLink) else Modifier).padding(vertical = 6.dp)
        )
    }
}

/** .hero：384x430 / r22 全幅封面 + 底部墨色渐变；Daily signal 眉题、Newsreader 双行标题、珊瑚色胶囊按钮。 */
@Composable
internal fun HomeDailyHero(
    songs: List<Song>,
    loading: Boolean = false,
    /** r658b #10：「今日推荐」胶囊直接播放（并打开播放页）；为空时退回进入详情。 */
    onPlayAll: (() -> Unit)? = null,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val cover = songs.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty()
    val accent = SoundwaveDesignPalette.Accent
    // r657 第三轮：原型 pHome .hero——r28、0 18 40 rgba(19,25,21,.22)。
    val shape = RoundedCornerShape(28.dp)
    // 「今日推荐」按钮发光：珊瑚色柔光呼吸（减少动效时保持静态光晕）。
    val reduceGlow = MotionPolicy.rememberReduceMotion()
    // r656-G：呼吸 3 次后停在静态光晕——不再无限动画（首页空闲时每帧重组整张英雄卡、阻止 UI 测试进入空闲）。
    val glowAnim = remember { androidx.compose.animation.core.Animatable(0.62f) }
    LaunchedEffect(reduceGlow) {
        if (reduceGlow) { glowAnim.snapTo(0.62f); return@LaunchedEffect }
        repeat(3) {
            glowAnim.animateTo(0.86f, tween(1600))
            glowAnim.animateTo(0.48f, tween(1600))
        }
        glowAnim.animateTo(0.62f, tween(800))
    }
    val glowPulse = glowAnim.value
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(430.dp)
            .cssShadow(shape, CardShadow.copy(alpha = 0.22f), 40.dp, 18.dp)
            .clip(shape)
            .background(HeroInk)
            // r658b #10（原型 .hero data-go=detail）：点封面进入今日推荐详情。
            .clickable(onClick = onOpen)
    ) {
        if (cover.isNotBlank()) {
            AsyncImage(
                CacheManager.coverModel(context, cover) ?: cover, null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3F38), HeroInk))))
        }
        // r657 第三轮：原型 .hero::before——rgba(6,8,12) 0@25% → .55@62% → .86@100%（上部封面不再整体压暗）。
        Box(Modifier.fillMaxSize().background(todayRecommendationReadabilityScrim()))
        // r657：原型顶部两枚玻璃 chip——左「✦ 星期 · 月.日」，右「N 首 · M MIN」（曲目数从眉题移到这里）。
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val today = remember { java.time.LocalDate.now() }
            HeroGlassChip {
                Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Sparkle, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    today.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).uppercase() +
                        " · %d.%02d".format(today.monthValue, today.dayOfMonth),
                    color = Color.White, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em, maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            if (songs.isNotEmpty()) HeroGlassChip {
                val minutes = (songs.sumOf { it.duration.coerceAtLeast(0L) } / 60_000L).toInt()
                Text(
                    if (minutes > 0) "${songs.size} 首 · $minutes MIN" else "${songs.size} 首",
                    color = Color.White, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em, maxLines = 1
                )
            }
        }
        // 原型：内容 left/right/bottom 22；眉题白 72%、10/12、.16em；标题 Newsreader 600 46/44、-.05em（斜体行 500、#FF7A63）。
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 22.dp)) {
            Text(
                (if (loading && songs.isEmpty()) "Daily signal · loading" else "Daily signal · made for you").uppercase(),
                color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontFamily = DesignFonts.DmMono,
                fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1, lineHeight = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                buildAnnotatedString {
                    append("Your day,\n")
                    withStyle(SpanStyle(color = Color(0xFFFF7A63), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)) { append("in motion.") }
                },
                color = Color.White, fontSize = 46.sp, lineHeight = 44.sp, fontFamily = DesignFonts.Newsreader,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.05).em, maxLines = 2, softWrap = false,
                style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "今日推荐 · 根据你的收藏与最近收听生成", color = Color.White.copy(alpha = 0.78f), fontSize = 12.5.sp, lineHeight = 17.sp,
                fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    // 原型 .pill：44dp、左 16 右 20、间距 7、15dp 实心三角、Manrope 800 14sp；0 10 22 rgba(255,92,69,.42) + 呼吸光晕。
                    Modifier.height(44.dp)
                        .accentGlow(alpha = glowPulse, blur = 18.dp, offsetY = 6.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFFFF654F), Color(0xFFF04F3A)))) // 第 5 轮 #1：去掉中心径向高光（「日」字不再过亮）
                        .pressScale(PressMotion.SCALE_ICON)
                        // r658b #10（原型 .pill data-x=playall）：点按钮直接播放今日推荐并打开播放页。
                        .clickable(enabled = songs.isNotEmpty(), interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPlayAll ?: onOpen)
                        .padding(start = 16.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(DesignChromeGlyphs.PlaySolid, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("今日推荐", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1)
                }
                if (songs.isEmpty() && !loading) {
                    Spacer(Modifier.width(14.dp))
                    Text("暂无推荐曲目", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                }
            }
        }
    }
}

/** 英雄卡顶部玻璃 chip：墨色 35% 底 + 1dp 纸色 22% 描边。 */
@Composable
private fun HeroGlassChip(content: @Composable RowScope.() -> Unit) {
    // 原型 .chipg：22dp、左 6 右 8、rgba(18,20,18,.32) + 白 22% 内描边。
    Row(
        Modifier.height(22.dp).clip(CircleShape).background(Color(0xFF121412).copy(alpha = 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

/** 设计稿 .cover-play：无底色纯白圆角三角 + 投影，仅在悬停/按压/长按封面时淡入。 */
@Composable
internal fun BoxScope.CoverRevealPlay(appear: Float, description: String, size: Dp = 30.dp, padding: Dp = 9.dp, onClick: () -> Unit) {
    // r657 修复（审计第 12 / 21 项）：宫格播放钮按下 0.92 弹簧缩放，点按迸发 8 颗珊瑚粒子（简洁动效时不迸发）。
    val burst = xyz.cncnlp.soundwave.ui.common.rememberParticleBurstState()
    if (appear <= 0.01f) return
    Box(
        Modifier.align(Alignment.BottomEnd).padding(padding).size(size + 8.dp)
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 6.dp.toPx() }
            .particleBurst(burst, xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent, Color.White, count = 8, radius = 30.dp, particleSize = 2.5.dp, behind = false)
            .pressScale(PressMotion.SCALE_ICON)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { burst.burst(); onClick() }
            .semantics { contentDescription = description; role = androidx.compose.ui.semantics.Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            DesignGlyphs.PlayTriangleSoft, null, tint = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.size(size).offset(y = 1.5.dp).blur(3.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
        )
        Icon(DesignGlyphs.PlayTriangleSoft, null, tint = Color.White, modifier = Modifier.size(size))
    }
}

/** .album-card：123dp 封面（r18、柔和投影）+ 12sp/750 标题 + 11.5sp 副标题（标题略大、副标题略小，长文本跑马灯）。 */
@Composable
internal fun HomeCuratedCard(
    title: String,
    subtitle: String,
    cover: @Composable (Modifier) -> Unit,
    /** 封面区附加修饰（悬停/按压检测等），不消费点击。 */
    coverModifier: Modifier = Modifier,
    /** 设计稿 .home-playlist-strip：一屏 3 张 = (屏宽 - 28 - 2×8)/3。 */
    cardWidth: Dp = 123.dp,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(cardWidth)) {
        Box(
            Modifier.size(cardWidth)
                .homeCoverShadow(shape)
                .clip(shape).background(colors.surfaceVariant)
                .then(coverModifier)
        ) {
            cover(Modifier.fillMaxSize())
            overlay()
        }
        Text(
            title, color = colors.onBackground, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.ExtraBold,
            fontFamily = DesignFonts.Manrope, maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 7.dp).fillMaxWidth().swMarquee()
        )
        if (subtitle.isNotBlank()) Text(
            subtitle, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.5.sp,
            fontFamily = DesignFonts.Manrope, maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 3.dp).fillMaxWidth().swMarquee()
        )
    }
}

/** 仅投影真实已解析音质；未知值不造 HQ / SQ 标签。 */
internal fun homeVerifiedQualityBadge(actualQuality: String): String = when (actualQuality.trim().lowercase(java.util.Locale.ROOT)) {
    "128k" -> "128K"
    "320k" -> "HQ"
    "flac", "lossless" -> "SQ"
    "flac24bit", "hires", "hi-res" -> "HI-RES"
    "master" -> "MASTER"
    else -> ""
}

/** .mix-strip：每页 5 行（70dp/行、行距 4dp），页宽 = 屏宽 - 66dp，页距 10dp。 */
@Composable
internal fun HomeHotSongsPager(
    songs: List<Song>,
    platform: String,
    nowPlayingKey: String,
    isPlayingNow: Boolean,
    onTogglePlayback: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pages = songs.take(10).chunked(5)
    // 1.0.655：第 1 页右侧露出第 2 页开头（左 14dp / 右 34dp 内容内距 + 10dp 页距 ⇒ 露出约 24dp）；
    // 两页尺寸相同、停靠位置相同（左缘 14dp）；没有第 3 页，第 2 页右侧不再露出任何内容。
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pages.size })
    androidx.compose.foundation.pager.HorizontalPager(
        state = pager,
        pageSize = androidx.compose.foundation.pager.PageSize.Fill,
        contentPadding = PaddingValues(start = 14.dp, end = if (pages.size > 1) 34.dp else 14.dp),
        pageSpacing = 10.dp,
        key = { index -> "home_hot_page_$index" },
        modifier = Modifier.fillMaxWidth()
    ) { pageIndex ->
        val pageSongs = pages[pageIndex]
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pageSongs.forEach { song ->
                    val cover = normalizeCoverUrl(song.cover)
                    val coverModel = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
                    Row(
                        Modifier.fillMaxWidth().height(70.dp).clickable { onPlay(song, songs) }.padding(start = 2.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(58.dp).homeCoverShadow(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            // 统一封面占位垫底：未加载 / 失败 / 无封面同款，图片成功后盖住。
                            DefaultLandscapeCover(Modifier.fillMaxSize())
                            if (cover.isNotBlank()) {
                                var coverRetry by remember(cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                                androidx.compose.runtime.key(coverRetry) {
                                    // ACCEPT4：封面到达逐图淡入（稿加载语言），失败重试一次的口径不变。
                                    FadingCoverImage(coverModel, null, Modifier.fillMaxSize(),
                                        onError = { if (coverRetry == 0) coverRetry = 1 })
                                }
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
                            Text(
                                song.name, color = if (song.key() == nowPlayingKey) colors.primary else colors.onBackground,
                                fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                                letterSpacing = (-0.015).em, maxLines = 1, modifier = Modifier.fillMaxWidth().swMarquee()
                            )
                            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                // r657 第三轮第 22 项：歌手名左侧按平台返回的真实最高音质显示 .badge.q（HR / 无损 / MASTER；有损不显示）。
                                val qBadge = xyz.cncnlp.soundwave.songQualityBadgeLabel(song)
                                if (qBadge.isNotEmpty()) { xyz.cncnlp.soundwave.QualityBadge(qBadge); Spacer(Modifier.width(6.dp)) }
                                Text(
                                    song.singer.uppercase(), color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 14.sp,
                                    fontFamily = DesignFonts.DmMono, maxLines = 1,
                                    modifier = Modifier.weight(1f, fill = false).swMarquee()
                                )
                                Text(" · ", color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 14.sp, fontFamily = DesignFonts.Manrope, maxLines = 1)
                                Text(
                                    platformName(song.source.ifBlank { platform }), color = colors.onSurfaceVariant,
                                    fontSize = 11.5.sp, lineHeight = 14.sp, fontFamily = DesignFonts.DmMono, maxLines = 1
                                )
                            }
                        }
                        val isCurrentPlaying = song.key() == nowPlayingKey && isPlayingNow
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .clickable { if (song.key() == nowPlayingKey) onTogglePlayback() else onPlay(song, songs) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isCurrentPlaying) Icons.Filled.Pause else DesignChromeGlyphs.PlaySolid,
                                if (isCurrentPlaying) "暂停：${song.name}" else "播放：${song.name}",
                                tint = colors.onBackground, modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
    }
}

/** 歌单广场 / 排行榜 / 心动模式三宫格（.feature-switch-head + .feature-carousel）。 */
@Composable
internal fun HomeFeatureCarousel(
    active: Boolean,
    currentSong: Song?,
    playlistCover: String,
    chartCover: String,
    onPlaylists: () -> Unit,
    onCharts: () -> Unit,
    onHeart: () -> Unit,
    /** 心动模式播放中：中心播放按钮切换为暂停态并高亮。 */
    heartActive: Boolean = false,
    /** 歌单广场/排行榜卡右侧两张错叠封面；缺第二张时只显示一张。 */
    playlistCoverBack: String = "",
    chartCoverBack: String = "",
    /** 心动模式卡封面：无当前曲目时用推荐种子（我的喜欢/播放历史/随机）的封面。 */
    heartCover: String = "",
    /** 无当前曲目时心动模式仍可启动（由 onHeart 决定推荐种子）。 */
    heartAvailable: Boolean = currentSong != null
) {
    var selected by remember { mutableIntStateOf(0) }
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    var held by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val names = listOf("歌单广场", "排行榜", "心动模式")
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    LaunchedEffect(active, selected, held, dragging, hovered, focused, pressed) {
        if (active && !held && !dragging && !hovered && !focused && !pressed) {
            delay(4200L)
            selected = (selected + 1) % 3
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            // 自动/手动切换时标题与序号淡入淡出（与卡片滑动同步）。
            AnimatedContent(selected, transitionSpec = { fadeIn(tween(if (reduceMotion) 0 else 260)) togetherWith fadeOut(tween(if (reduceMotion) 0 else 200)) }, label = "featureTitle") { sel ->
                Text(names[sel], color = colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, letterSpacing = (-0.03).em, maxLines = 1)
            }
            // ACCEPT5（用户 2026-11-04）：计数 + 圆点组成右侧组整体锚定行尾——计数不再紧随标题
            // （旧实现带 weight 紧贴标题，起点随标题字数 3/4 字漂移）。计数为 DmMono 定宽文本、
            // 圆点组定宽，故该组位置与标题宽度无关，切换时纹丝不动。
            Spacer(Modifier.weight(1f))
            Text("0${selected + 1} / 03 · SWIPE", color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em, maxLines = 1)
            Spacer(Modifier.width(11.dp))
            repeat(3) { index ->
                if (index > 0) Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(if (selected == index) 26.dp else 8.dp, 8.dp).clip(CircleShape)
                        .background(if (selected == index) SoundwaveDesignPalette.Accent else colors.onBackground.copy(alpha = 0.18f))
                        .clickable { selected = index }
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, start = 14.dp, end = 14.dp).height(148.dp).hoverable(interactions).focusable(interactionSource = interactions)
            .homeCarouselTouch { held = it }.pointerInput(Unit) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f; dragging = true },
                    onDragCancel = { dragging = false },
                    onDragEnd = { if (kotlin.math.abs(distance) > 32.dp.toPx()) selected = (selected + if (distance < 0) 1 else 2) % 3; dragging = false },
                    onHorizontalDrag = { change, amount -> distance += amount; change.consume() }
                )
            }, contentAlignment = Alignment.Center) {
            val slotWidth = maxWidth * 0.76f
            // 当前卡最后绘制，保证点击命中在最前；两侧卡缩放 0.68、半透明，中心偏移 ±23.2% 容器宽。
            // 设计稿：下一张在左、上一张在右（01 歌单广场时左侧露出 排行榜、右侧露出 心动模式）。
            // key(index)：每张卡保持自己的动画状态，切换时从原位置滑动/缩放/淡入到新位置（此前按位置复用状态会直接跳变）。
            listOf((selected + 2) % 3, (selected + 1) % 3, selected).forEach { index -> key(index) {
                val center = index == selected
                val side = if (index == (selected + 1) % 3) -1f else 1f
                val shift by animateFloatAsState(if (center) 0f else side * 0.232f, tween(if (reduceMotion) 0 else 420), label = "featureSlot")
                val scale by animateFloatAsState(if (center) 1f else 0.68f, tween(if (reduceMotion) 0 else 420), label = "featureScale")
                val fade by animateFloatAsState(if (center) 1f else 0.62f, tween(if (reduceMotion) 0 else 420), label = "featureFade")
                val fill = when (index) { 0 -> Color(0xFFB64935); 1 -> Color(0xFF503A28); else -> Color(0xFF54423A) }
                val shape = RoundedCornerShape(if (index == 2) 16.dp else 15.dp)
                Box(
                    Modifier.width(slotWidth).height(128.dp).zIndex(if (center) 2f else 1f - kotlin.math.abs(shift))
                        .graphicsLayer { translationX = maxWidth.toPx() * shift; scaleX = scale; scaleY = scale; alpha = fade }
                        // r675（用户 10-22：歌单广场 / 排行榜 / 心动模式大卡加阴影）：与首页数字专辑整卡同款外缘阴影（SW-08-10），
                        // 画在 clip 之外；外层 BoxWithConstraints / Column 均不裁切，阴影随卡缩放淡入淡出。
                        .editorialCardShadow(shape)
                        .clip(shape).background(fill)
                        .clickable(interactionSource = interactions, indication = null, enabled = index != 2 || heartAvailable) {
                            if (!center) selected = index else when (index) { 0 -> onPlaylists(); 1 -> onCharts(); else -> onHeart() }
                        }
                ) {
                    // 文案列宽 = 卡宽 - 右侧封面区，保证 "BASED ON YOUR CURRENT TRACK" 完整显示（不截断）。
                    val copyWidth = slotWidth - (if (index == 2) 99.dp else 138.dp) - 4.dp
                    Column(Modifier.fillMaxHeight().padding(start = 10.dp, top = 11.dp, bottom = 11.dp).width(copyWidth), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            when (index) { 0 -> "CURATED COLLECTIONS"; 1 -> "UPDATED DAILY"; else -> "BASED ON YOUR CURRENT TRACK" },
                            color = Color.White.copy(alpha = 0.66f), fontSize = 8.sp, lineHeight = 10.sp,
                            fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 2
                        )
                        Text(
                            names[index], color = Color.White.copy(alpha = if (index == 2) 0.72f else 0.62f), fontFamily = DesignFonts.Newsreader,
                            fontWeight = FontWeight.Medium, fontSize = if (index == 2) 18.sp else 19.sp, lineHeight = 22.sp, maxLines = 1
                        )
                    }
                    val cover = when (index) { 0 -> playlistCover; 1 -> chartCover; else -> currentSong?.cover.orEmpty().ifBlank { heartCover } }
                    if (index != 2) {
                        val back = when (index) { 0 -> playlistCoverBack; else -> chartCoverBack }.takeIf { it.isNotBlank() && it != cover }.orEmpty()
                        if (cover.isNotBlank()) HomeFeatureCoverStack(front = cover, back = back, modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 10.dp))
                    } else if (cover.isNotBlank()) {
                        HomeHeartCoverStage(
                            // r658b #22：心动模式卡未切到中心时，播放键不可点（点卡片只切换到该页）。
                            cover = cover, heartOn = heartActive, enabled = heartAvailable && center, onToggle = onHeart,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 17.6.dp, end = 15.dp)
                        )
                    }
                }
            } }
        }
    }
}

/** .browse-art 128x104：两张 89dp 封面 -7°/+7° 错叠，阴影 0 12px 28px rgba(0,0,0,.24)。
 * 前卡 3dp rgba(255,255,255,.35) 描边位于图片外圈（CSS border 不覆盖图片内容）→ 透出后方卡面/后卡，呈磨砂半透明边。
 * （按需求移除右上角 "01" 序号。）
 */
@Composable
private fun HomeFeatureCoverStack(front: String, back: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(width = 128.dp, height = 104.dp)) {
        if (back.isNotBlank()) {
            AsyncImage(
                CacheManager.coverModel(context, back) ?: back, null,
                Modifier.offset(x = 3.dp, y = (-3).dp).size(89.dp)
                    .graphicsLayer { rotationZ = -7f }
                    .homeCoverShadow(shape)
                    .clip(shape),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier.offset(x = if (back.isNotBlank()) 27.dp else 20.dp, y = if (back.isNotBlank()) 17.dp else 8.dp).size(89.dp)
                .graphicsLayer { rotationZ = if (back.isNotBlank()) 7f else 0f }
                .homeCoverShadow(shape)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.35f))
                .padding(3.dp)
        ) {
            // ACCEPT5 收编：前层加黑胶母题垫底——front 为空/加载/失败时不再是白 35% 空框。
            DefaultCoverPlaceholder(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)))
            AsyncImage(
                CacheManager.coverModel(context, front) ?: front, null,
                Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** .new-song-row：79dp 行、底部 1dp 分隔线；58dp 封面、15sp 歌名、11.5sp 歌手 · 平台（长文本跑马灯），序号 DM Mono 12sp 位于最右。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeNewSongRow(
    song: Song,
    index: Int,
    platformLabel: String,
    nowPlaying: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val cover = normalizeCoverUrl(song.cover)
    val coverModel = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(78.dp).combinedClickable(onClick = onClick).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(58.dp).homeCoverShadow(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                // J 波：统一 DefaultCover 垫底 + 失败自动重试一次（原实现无垫底，加载中/失败都是空白方块）。
                DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 20)
                if (cover.isNotBlank()) {
                    var coverRetry by remember(cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                    androidx.compose.runtime.key(coverRetry) {
                        AsyncImage(coverModel, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            onError = { if (coverRetry == 0) coverRetry = 1 })
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    song.name, color = if (nowPlaying) colors.primary else colors.onBackground,
                    fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                    letterSpacing = (-0.015).em, maxLines = 1, modifier = Modifier.fillMaxWidth().swMarquee()
                )
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    // r657 第三轮第 23 项：只有平台数据判定为新歌（isRecentRelease）才在歌手名左侧显示蓝色 NEW（原型 .badge.b）。
                    if (xyz.cncnlp.soundwave.isRecentRelease(song)) { xyz.cncnlp.soundwave.NewReleaseBadge(); Spacer(Modifier.width(6.dp)) }
                    Text(
                        song.singer, color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 14.sp, fontFamily = DesignFonts.Manrope,
                        maxLines = 1, modifier = Modifier.weight(1f, fill = false).swMarquee()
                    )
                    if (platformLabel.isNotBlank()) {
                        Text("  ·  ", color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 1)
                        Text(platformLabel, color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 14.sp, fontFamily = DesignFonts.Manrope, maxLines = 1)
                    }
                }
            }
            Text(
                "%02d".format(index), color = colors.onSurfaceVariant, fontSize = 12.sp,
                fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium,
                modifier = Modifier.width(38.dp).wrapContentWidth(Alignment.CenterHorizontally)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(colors.outline)))
    }
}

/** 新歌速递列表外壳：左右 14dp，顶部 1dp 分隔线（.new-song-list border-top）。 */
@Composable
internal fun HomeNewSongList(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(MaterialTheme.colorScheme.outline)))
        content()
    }
}

/** 唱片：深色圆盘 + 细纹路 + 中心标签，用于数字专辑/新碟首发封面后方露出的唱片。 */
@Composable
private fun DesignVinyl(modifier: Modifier, label: Color) {
    Box(
        modifier.clip(CircleShape).background(Color(0xFF171515)).drawBehind {
            val r = size.minDimension / 2f
            for (i in 1..6) drawCircle(Color.White.copy(alpha = 0.05f), radius = r * (0.42f + i * 0.09f), style = Stroke(width = 1f))
            // 1.0.655：设计稿 .digital-cover-stage:after 的 inset 34px #111 / 37px #444 —— 纹路区与标签区之间一道
            // 半透明浅灰弧环（参考 1000072874，封面右缘外可见的透明弧线），内侧为无纹路的深色圈。
            drawCircle(Color(0xFF111111), radius = r * 0.43f)
            drawCircle(Color.White.copy(alpha = 0.24f), radius = r * 0.455f, style = Stroke(width = r * 0.05f))
            drawCircle(label, radius = r * 0.24f)
            drawCircle(Color(0xFF171515), radius = r * 0.05f)
        }
    )
}

/**
 * .digital-album（参考 1000073088）：屏宽 80%（≤330）x116 / r14；左侧 116dp 方形封面 + 「新专辑」徽标，
 * 封面后方黑色唱片（直径 ≈ 卡高 96%，圆心在封面 83% 处）向右露出约 1/3；右侧小号歌手、大号专辑名、
 * 与歌手同字号的「N 首歌曲」，最右白色圆角播放三角。
 */
/** r675：数字专辑卡宽（屏宽 80%，260–330dp）；轮播 pageSize 与卡片共用，避免两处公式漂移。 */
internal fun homeDigitalAlbumCardWidth(screenWidthDp: Int): Dp = (screenWidthDp * 0.80f).coerceIn(260f, 330f).dp

@Composable
internal fun HomeDigitalAlbumCard(card: DiscoverCard, tone: Color?, songCount: Int = card.count, onClick: () -> Unit) {
    val context = LocalContext.current
    val bg = tone?.let { designCardTone(it) } ?: Color(0xFF963D27)
    val shape = RoundedCornerShape(14.dp)
    val cardWidth = homeDigitalAlbumCardWidth(LocalConfiguration.current.screenWidthDp)
    Box(
        Modifier.padding(vertical = 2.dp).width(cardWidth).height(116.dp)
            .editorialCardShadow(shape).background(bg, shape).clickable(onClick = onClick)
    ) {
        // 唱片：直径 110dp、上下各留 3dp，圆心 x≈97dp → 右缘露出封面外约 36dp（设计稿 1000073088 实测）。
        Box(
            Modifier.offset(x = 42.dp, y = 3.dp).size(110.dp)
                .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.4f))
        ) { DesignVinyl(Modifier.fillMaxSize(), Color(0xFF2A1F1C)) }
        Box(Modifier.size(116.dp).homeCoverShadow(shape).clip(shape).background(Color.Black.copy(alpha = 0.18f))) {
            // J 波：统一占位垫底（原为深色空洞）+ 失败重试一次。
            DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 24)
            if (card.cover.isNotBlank()) {
                var coverRetry by remember(card.cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.key(coverRetry) {
                    AsyncImage(
                        CacheManager.coverModel(context, card.cover) ?: card.cover, card.name,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        onError = { if (coverRetry == 0) coverRetry = 1 }
                    )
                }
            }
        }
        Text(
            if (songCount == 1) "新单曲" else "新专辑", color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111).copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 3.dp)
        )
        val metaSize = 11.5.sp
        Column(Modifier.align(Alignment.CenterStart).padding(start = 158.dp, end = 46.dp)) {
            Text(
                card.artist.ifBlank { "数字专辑" }, color = Color.White.copy(alpha = 0.92f), fontSize = metaSize, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                card.name, color = Color.White, fontSize = 17.sp, lineHeight = 21.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
            Text(
                if (songCount > 0) "$songCount 首歌曲" else "— 首歌曲", color = Color.White.copy(alpha = 0.88f), fontSize = metaSize, lineHeight = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, modifier = Modifier.padding(top = 6.dp)
            )
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(DesignChromeGlyphs.PlaySolid, "播放：${card.name}", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

/** .debut-card：361x150 / r20 120° 渐变，98dp 封面 + 唱片，DM Mono 序号、Newsreader 26sp 标题与 8sp 副标题。 */
@Composable
internal fun HomeDebutCard(card: DiscoverCard, index: Int, tone: Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val base = tone?.let { designCardTone(it, 0.62f) } ?: Color(0xFF63604A)
    val light = lerpColor(base, Color.White, 0.22f)
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.fillMaxSize()
            .editorialCardShadow(shape).clip(shape)
            .background(Brush.linearGradient(listOf(base, light), start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f)))
            .clickable(onClick = onClick)
    ) {
        DesignVinyl(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 12.dp).size(90.dp), base)
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 34.dp).size(98.dp)
                // box-shadow: 0 12px 24px rgba(0,0,0,.45), 0 3px 8px rgba(0,0,0,.28)
                .homeCoverShadow(RoundedCornerShape(11.dp))
                .clip(RoundedCornerShape(11.dp)).background(Color.Black.copy(alpha = 0.2f))
        ) {
            DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 22)
            if (card.cover.isNotBlank()) {
                var coverRetry by remember(card.cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.key(coverRetry) {
                    AsyncImage(CacheManager.coverModel(context, card.cover) ?: card.cover, card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        onError = { if (coverRetry == 0) coverRetry = 1 })
                }
            }
        }
        // .debut-copy：58% 宽、flex 纵向居中，padding 14/14/14/16；标签按序轮换（设计稿四张卡的四种 kicker）。
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.54f).padding(start = 16.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(
                "新碟首发 · %02d".format(index + 1), color = Color.White.copy(alpha = 0.82f), fontSize = 9.sp, lineHeight = 9.sp,
                fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.SemiBold, letterSpacing = 0.18.em, maxLines = 1
            )
            Text(
                card.name, color = Color.White, fontSize = 26.sp, lineHeight = 33.sp, /* r655：两行行距 ≈1.27× */ fontFamily = DesignFonts.Newsreader,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.055).em, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp, bottom = 1.dp)
            )
            val sub = listOf(card.artist, if (card.count > 0) "${card.count} TRACKS" else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(
                sub, color = Color.White.copy(alpha = 0.78f), fontSize = 8.sp, lineHeight = 11.sp, fontFamily = DesignFonts.Manrope,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

/** .debut-pager：7dp 圆点、选中 32dp 珊瑚胶囊，间距 7dp。 */
@Composable
internal fun HomePagerDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { dot ->
            if (dot > 0) Spacer(Modifier.width(7.dp))
            Box(
                Modifier.size(if (dot == selected) 32.dp else 7.dp, 7.dp).clip(CircleShape)
                    .background(if (dot == selected) SoundwaveDesignPalette.Accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f))
            )
        }
    }
}

/** .artist-card：96dp 宽，82dp 白色圆环（2dp）内 78dp 头像，12sp/700 名字。 */
@Composable
internal fun HomeArtistCard(name: String, avatar: String, cardWidth: androidx.compose.ui.unit.Dp = homeArtistCardWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp), onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // r657 第三轮第 21 项：原型 .hart 96dp 宽 / .hav 82dp（2dp 表面色环 + 0 8 18 rgba(19,25,21,.12)）；窄屏时按一屏 4 个等比收窄。
    val avatarSize = if (cardWidth < 82.dp) cardWidth else 82.dp
    Column(
        Modifier.width(cardWidth).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(avatarSize)
                .homeCoverShadow(CircleShape)
                .clip(CircleShape).background(colors.surface).padding(2.dp).clip(CircleShape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // ACCEPT5 收编（用户点名）：加载/失败/无头像统一黑胶母题垫底（圆形裁切内即圆形黑胶）+ 失败重试一次；
            // 此前有头像走 Artist 字形圆盘、无头像走名字首字，两张脸都不是母题。
            DefaultCoverPlaceholder(Modifier.fillMaxSize())
            if (avatar.isNotBlank()) {
                var avatarRetry by remember(avatar) { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.key(avatarRetry) {
                    AsyncImage(
                        CacheManager.coverModel(context, avatar) ?: avatar, name,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        onError = { if (avatarRetry == 0) avatarRetry = 1 }
                    )
                }
            }
        }
        Text(
            name, color = colors.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 11.dp)
        )
    }
}

/** 封面取色 → 设计稿卡片底色：压暗、限制饱和，保证白字可读。 */
internal fun designCardTone(tone: Color, darken: Float = 0.68f): Color {
    val l = tone.luminance()
    val k = if (l > 0.35f) darken * 0.85f else darken + (0.35f - l)
    val f = k.coerceIn(0.45f, 1f)
    return Color(red = tone.red * f, green = tone.green * f, blue = tone.blue * f, alpha = 1f)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    Color(red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t, alpha = 1f)

/**
 * 心动模式卡右侧：74dp 封面（阴影 0 10 24 .2）+ 倒影（74x26、上下镜像、40% 不透明、渐隐遮罩、轻微去饱和）。
 * 中心 34dp 无底色播放钮：白色实心三角（关）/ 白色双竖线（心动模式开启中），带 drop-shadow(0 2 7 rgba(0,0,0,.55))。
 */
@Composable
private fun HomeHeartCoverStage(cover: String, heartOn: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val model = CacheManager.coverModel(context, cover) ?: cover
    val coverShape = RoundedCornerShape(10.dp)
    Column(modifier.width(74.dp)) {
        Box(Modifier.size(74.dp)) {
            AsyncImage(
                model, null,
                Modifier.fillMaxSize().homeCoverShadow(coverShape).clip(coverShape),
                contentScale = ContentScale.Crop
            )
            val glyph = if (heartOn) DesignChromeGlyphs.PauseBold else DesignChromeGlyphs.PlaySolid
            Box(
                Modifier.align(Alignment.Center).size(34.dp)
                    .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggle() }
                    .semantics { contentDescription = if (heartOn) "暂停心动模式" else "启动心动模式"; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                // drop-shadow：模糊的黑色副本（API 31+ 真模糊；低版本降低不透明度避免硬边）。
                Icon(
                    glyph, null, tint = Color.Black.copy(alpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.55f else 0.22f),
                    modifier = Modifier.size(24.dp).offset(x = if (heartOn) 0.dp else 1.dp, y = 2.dp).blur(3.5.dp, BlurredEdgeTreatment.Unbounded)
                )
                Icon(glyph, null, tint = Color.White, modifier = Modifier.size(24.dp).offset(x = if (heartOn) 0.dp else 1.dp))
            }
        }
        AsyncImage(
            model, null,
            Modifier.padding(top = 3.dp).width(74.dp).height(26.dp)
                .graphicsLayer {
                    scaleY = -1f; alpha = 0.4f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .drawWithContent {
                    drawContent()
                    // mask-image: linear-gradient(to top, #000 0%, rgba(0,0,0,.72) 28%, transparent 100%)（元素自身坐标，翻转前）
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.72f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.78f) })
        )
    }
}

/**
 * r657（v4 #2）：首页「热门歌曲」右侧「TOP N」。N = 当前平台官方热歌榜长度（RankBoard.count）与 App 实际拉取上限取小
 * （RankDirectFetch：网易云热歌榜上限 1000、QQ 80、酷狗 80、酷我 100、咪咕 100）；榜单未给出长度时用已加载首数。
 */
internal fun homeHotTopCount(platform: String, boardCount: Int, loaded: Int): Int {
    val cap = when (platform) { "wy" -> 1000; "tx", "qq" -> 80; "kg" -> 80; "kw" -> 100; "mg" -> 100; else -> 100 }
    val n = if (boardCount > 0) minOf(boardCount, cap) else loaded
    return maxOf(n, loaded.coerceAtMost(cap))
}

/**
 * r657 第三轮第 21 项（用户 10-18）：首页「热门歌手」一屏完整显示 4 个头像——
 * 卡宽 = (可用宽 − 左右 14dp − 3 × 12dp 间距) / 4，上限为原型 .hart 96dp。
 */
internal fun homeArtistCardWidth(screenWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val w = (screenWidth - 28.dp - 36.dp) / 4
    return if (w > 96.dp) 96.dp else w
}


/** 原型 ic('filter')：M4.5 6.5h15 M7.5 12h9 M10.5 17.5h3（1.8 描边圆头）。 */
private val PlatformFilterGlyph: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("sw_i_filter", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M4.5 6.5h15M7.5 12h9M10.5 17.5h3"), fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        )
    }.build()
}
