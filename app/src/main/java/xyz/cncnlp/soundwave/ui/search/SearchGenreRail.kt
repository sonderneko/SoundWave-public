package xyz.cncnlp.soundwave.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import xyz.cncnlp.soundwave.R
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.MotionPolicy
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 搜索页「探索声音」9 个分类：中文名（即搜索关键词）、英文小字、主色、封面图样。 */
internal data class SearchGenre(val name: String, val english: String, val tone: Color, val deep: Color, val art: DrawScope.() -> Unit)

private val Paper = Color(0xFFF6F7F0)

/**
 * r654：探索声音卡背景改用画作（设计稿 4 幅 + 公有领域名画 5 幅，来源见 docs/NOTICE-search-genre-artwork.md）。
 * 程序绘制图样仅在缺图时兜底。
 */
internal fun searchGenreArtwork(name: String): Int? = when (name) {
    "纯音乐" -> R.drawable.search_genre_pure
    "抖音" -> R.drawable.search_genre_douyin
    "国风" -> R.drawable.search_genre_guofeng
    "古典" -> R.drawable.search_genre_classical
    "欧美" -> R.drawable.search_genre_western
    "华语" -> R.drawable.search_genre_mandopop
    "日语" -> R.drawable.search_genre_japanese
    "摇滚" -> R.drawable.search_genre_rock
    "氛围" -> R.drawable.search_genre_ambient
    else -> null
}

internal val SearchGenres: List<SearchGenre> = listOf(
    // 纯音乐：设计稿紫蓝（#AEB8FF）+ 琴键条纹与漂浮音符
    SearchGenre("纯音乐", "INSTRUMENTAL", Color(0xFFE3B7C9), Color(0xFF7A4459)) {
        val w = size.width; val h = size.height
        val keyW = w / 7f
        for (i in 0 until 7) drawRect(Paper.copy(alpha = 0.55f), Offset(i * keyW + 1.5f, 0f), Size(keyW - 3f, h * 0.42f))
        for (i in listOf(0, 1, 3, 4, 5)) drawRect(Color(0xFF7A4459).copy(alpha = 0.55f), Offset((i + 1) * keyW - keyW * 0.28f, 0f), Size(keyW * 0.56f, h * 0.26f))
        noteGlyph(Offset(w * 0.70f, h * 0.58f), w * 0.09f, Paper.copy(alpha = 0.9f))
    },
    // 抖音：深底 + 青 / 品红错位音符（短视频热歌的故障感）
    SearchGenre("抖音", "VIRAL", Color(0xFF1B1C22), Color(0xFF0B0C10)) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFFFE2C55).copy(alpha = 0.22f), w * 0.55f, Offset(w * 0.95f, h * 0.05f))
        val c = Offset(w * 0.46f, h * 0.40f); val r = w * 0.15f
        noteGlyph(c + Offset(-3.5f, -2.5f), r, Color(0xFF25F4EE))
        noteGlyph(c + Offset(3.5f, 2.5f), r, Color(0xFFFE2C55))
        noteGlyph(c, r, Color.White)
    },
    // 国风：朱砂底 + 水墨远山与一轮红日
    SearchGenre("国风", "GUOFENG", Color(0xFFE8B98A), Color(0xFF8E3B2A)) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFFD9442F), w * 0.17f, Offset(w * 0.70f, h * 0.22f))
        fun ridge(base: Float, amp: Float, color: Color, shift: Float) {
            val p = Path().apply {
                moveTo(0f, h); lineTo(0f, base)
                var x = 0f
                while (x <= w) { lineTo(x, base - amp * (0.55f + 0.45f * sin((x / w) * 2.6f * PI.toFloat() + shift))); x += w / 24f }
                lineTo(w, h); close()
            }
            drawPath(p, color)
        }
        ridge(h * 0.50f, h * 0.16f, Color(0xFF3C4A45).copy(alpha = 0.45f), 0.6f)
        ridge(h * 0.62f, h * 0.12f, Color(0xFF1F2B27).copy(alpha = 0.65f), 2.2f)
    },
    // 古典：设计稿青绿（#86E0CA）+ 拱门柱廊与五线谱
    SearchGenre("古典", "CLASSICAL", Color(0xFF86E0CA), Color(0xFF2E7C6B)) {
        val w = size.width; val h = size.height
        for (i in 0 until 5) drawLine(Paper.copy(alpha = 0.45f), Offset(0f, h * 0.10f + i * 6f), Offset(w, h * 0.10f + i * 6f), 1.2f)
        val archW = w * 0.26f
        for (i in 0 until 3) {
            val left = w * 0.08f + i * (archW + w * 0.05f)
            drawRect(Paper.copy(alpha = 0.5f), Offset(left, h * 0.42f), Size(archW, h * 0.22f))
            drawArc(Paper.copy(alpha = 0.5f), 180f, 180f, true, Offset(left, h * 0.42f - archW / 2f), Size(archW, archW))
        }
    },
    // 欧美：设计稿珊瑚（#FF8F79）+ 放射日芒
    SearchGenre("欧美", "WESTERN POP", Color(0xFFFF8F79), Color(0xFFB2412D)) {
        val w = size.width; val h = size.height
        val c = Offset(w * 0.5f, h * 0.30f)
        for (i in 0 until 12) {
            val a = i * PI.toFloat() / 6f
            drawLine(Paper.copy(alpha = 0.40f), c, c + Offset(cos(a) * w, sin(a) * w), 6f)
        }
        drawCircle(Color(0xFFFFE07A), w * 0.17f, c)
    },
    // 华语：设计稿粉（#F6A8CF）+ 同心圆唱片
    SearchGenre("华语", "MANDOPOP", Color(0xFFF6A8CF), Color(0xFFA2467A)) {
        val w = size.width; val h = size.height
        val c = Offset(w * 0.62f, h * 0.34f)
        drawCircle(Color(0xFF2A1F2A).copy(alpha = 0.75f), w * 0.36f, c)
        for (k in 1..3) drawCircle(Paper.copy(alpha = 0.18f), w * (0.12f + k * 0.07f), c, style = Stroke(1.2f))
        drawCircle(Color(0xFFFFD978), w * 0.09f, c)
    },
    // 日语：设计稿淡紫（#CAB0FF）+ 红日与樱花瓣
    SearchGenre("日语", "J-POP", Color(0xFFCDE3B0), Color(0xFF5F7A3A)) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFFE84A5F), w * 0.22f, Offset(w * 0.40f, h * 0.30f))
        listOf(Offset(0.78f, 0.16f), Offset(0.70f, 0.48f), Offset(0.20f, 0.60f), Offset(0.88f, 0.34f)).forEachIndexed { i, o ->
            rotate(i * 40f, Offset(w * o.x, h * o.y)) {
                drawOval(Color(0xFFFFD3E1), Offset(w * o.x - 4f, h * o.y - 2.5f), Size(8f, 5f))
            }
        }
    },
    // 摇滚：设计稿琥珀（#FFD978）+ 闪电
    SearchGenre("摇滚", "ROCK", Color(0xFFFFD978), Color(0xFFB07A12)) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFF16181A).copy(alpha = 0.10f), w * 0.5f, Offset(w * 0.5f, h * 0.30f))
        val p = Path().apply {
            moveTo(w * 0.58f, h * 0.06f); lineTo(w * 0.28f, h * 0.40f); lineTo(w * 0.48f, h * 0.40f)
            lineTo(w * 0.36f, h * 0.66f); lineTo(w * 0.74f, h * 0.28f); lineTo(w * 0.53f, h * 0.28f); close()
        }
        drawPath(p, Color(0xFF16181A).copy(alpha = 0.82f))
    },
    // 氛围：设计稿天蓝（#83BBFF）+ 层叠声波
    SearchGenre("氛围", "AMBIENT", Color(0xFFB9C4B0), Color(0xFF5C6B52)) {
        val w = size.width; val h = size.height
        for (k in 0 until 4) {
            val y0 = h * (0.16f + k * 0.11f)
            val p = Path().apply {
                moveTo(0f, y0)
                var x = 0f
                while (x <= w) { lineTo(x, y0 + sin(x / w * 2f * PI.toFloat() + k) * h * 0.04f); x += w / 20f }
            }
            drawPath(p, Paper.copy(alpha = 0.62f - k * 0.1f), style = Stroke(2.2f))
        }
        drawCircle(Paper.copy(alpha = 0.85f), w * 0.07f, Offset(w * 0.78f, h * 0.10f))
    }
)

/** 简单八分音符：符头椭圆 + 符干 + 符尾。 */
private fun DrawScope.noteGlyph(head: Offset, r: Float, color: Color) {
    drawOval(color, Offset(head.x - r, head.y - r * 0.72f), Size(r * 2f, r * 1.44f))
    val stemX = head.x + r * 0.86f
    drawRect(color, Offset(stemX - r * 0.18f, head.y - r * 3.2f), Size(r * 0.36f, r * 3.2f))
    drawRect(color, Offset(stemX, head.y - r * 3.2f), Size(r * 1.2f, r * 0.5f))
}

/**
 * 设计稿 .genre-grid：横滑分类卡，一屏恰好 4 张（卡宽 = (屏宽 − 2×14dp 页边 − 3×14dp 间距)/4，宽高比 ≈ 270:390）；
 * 奇数卡固定下沉 14dp 形成错落，并按 sin(i·1.05 − phase)·5dp 上下起伏（layer.js updateGenreWave）。
 *
 * 1.0.655 丝滑化：
 *  - 相位 = 连续滚动位置（px / 卡距）+ 空闲漂移相位，全部在 graphicsLayer 绘制阶段读取 → 滚动每帧零重组，
 *    不再用 animateFloatAsState 追赶目标值（旧实现每帧重组 + 弹簧滞后，与吸附动画互相拉扯造成顿挫）；
 *  - 位移 / 缩放 / 微旋转由同一相位 lerp 得出，连续无跳变；
 *  - 抛滑：样条衰减 + 临界阻尼弹簧吸附到卡片起点（无回弹抖动）；
 *  - r657 第二轮：取消空闲浮动，位移只随横滑（sin(i·1.05 − scrollLeft/95)·11dp + 300ms 过渡）；
 *  - 系统或应用开启减少动效时只保留固定错落。
 * 仅右上角大圆角，其余小圆角；底部深色渐变 + 白色粗体中文名 + 等宽英文小字。点击按分类名搜索。
 */
@Composable
internal fun SearchGenreRail(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val pagePad = 14.dp
    val gap = 8.dp
    val stagger = SEARCH_GENRE_S_AMPLITUDE_DP.dp // r658b #15：S 形轨迹振幅（上下各 9dp），给上下留白
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // r657 第二轮：按原型 v2-9 移动端 .genre（一屏 4 张，间距 8dp，高 112dp，圆角 4/18/4/4）。
        val cardW = ((maxWidth - pagePad * 2 - gap * 3) / 4).coerceIn(56.dp, 140.dp)
        val cardH = 112.dp
        val cardPx = with(density) { (cardW + gap).toPx() }
        val snapProvider = remember(state) { androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(state, SnapPosition.Start) }
        val decay = androidx.compose.animation.rememberSplineBasedDecay<Float>()
        val fling = remember(snapProvider, decay) {
            androidx.compose.foundation.gestures.snapping.snapFlingBehavior(
                snapLayoutInfoProvider = snapProvider,
                decayAnimationSpec = decay,
                snapAnimationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
            )
        }
        LazyRow(
            state = state,
            // 按卡片起点吸附，停止滚动时总是完整的 4 张，不露出半张。
            flingBehavior = fling,
            modifier = Modifier.fillMaxWidth(),
            // r658b #15：整排上移 5dp（顶部留白 14 → 9dp）。
            contentPadding = PaddingValues(start = pagePad, end = pagePad, top = 9.dp + stagger, bottom = 10.dp + stagger + 6.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Top
        ) {
            items(SearchGenres.size, key = { SearchGenres[it].name }) { index ->
                val g = SearchGenres[index]
                val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 4.dp)
                val art = searchGenreArtwork(g.name)
                Box(
                    Modifier.width(cardW).height(cardH)
                        // r658b #15：S 形错落轮播——卡片高度由它在屏幕上的横向位置决定（一屏 4 张 = 一个完整正弦周期：
                        // 中 → 上 → 中 → 下），横滑时每张卡沿同一条 S 形轨迹连续起伏；只在绘制阶段读取滚动位置，不触发重组。
                        .graphicsLayer {
                            val slotX = if (reduce) index * cardPx else state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat() ?: (index * cardPx)
                            translationY = searchGenreSLiftDp(slotX / cardPx) * density.density
                        }
                        .shadow(8.dp, shape, ambientColor = Color(0xFF131915).copy(alpha = 0.12f), spotColor = Color(0xFF131915).copy(alpha = 0.18f))
                        .clip(shape)
                        .background(Brush.verticalGradient(listOf(g.tone, g.deep)))
                        .then(if (art == null) Modifier.drawBehind { g.art(this) } else Modifier)
                        .clickable { onPick(g.name) }
                        .semantics { contentDescription = "搜索${g.name}"; role = Role.Button }
                ) {
                    if (art != null) Image(painterResource(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Column(
                        Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xFF060908).copy(alpha = 0.88f)))
                            .padding(start = 9.dp, end = 6.dp, bottom = 11.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        // r656：封面文字缩小并按卡宽自适应（原 20sp 在窄卡上「纯音乐」的「乐」被截断），保证完整显示。
                        val labelSp = searchGenreLabelSp((cardW - 15.dp).value, g.name.length, androidx.compose.ui.platform.LocalDensity.current.fontScale)
                        Text(g.name, color = Color.White, fontWeight = FontWeight.Black, fontSize = labelSp.sp, lineHeight = (labelSp + 2f).sp, maxLines = 1, softWrap = false)
                        // r657 截图对照：英文副标按卡宽缩字（360dp 上 INSTRUMENTAL 曾被省略成 INSTRUMENT…），等宽字约 0.66em/字，最小 6sp。
                        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                        val englishSp = (((cardW - 15.dp).value / (g.english.length.coerceAtLeast(1) * 0.66f)) / fontScale).coerceIn(6f, 8f)
                        Text(g.english, color = Color.White.copy(alpha = 0.80f), fontSize = englishSp.sp, lineHeight = 9.sp, fontFamily = DesignFonts.DmMono, letterSpacing = 0.02.em,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * r656：探索声音卡片标题字号（sp）：若「字数 × 字宽」超出可用宽度则按比例缩小（下限 11sp），
 * 中文粗体字宽约 1.06em；fontScale 放大时同步折算，保证系统大字体下也不截断。
 * r657：默认上限 17sp → 14sp（约小 18%，用户反馈标题偏大），英文小标字号不变。
 */
internal fun searchGenreLabelSp(availableWidthDp: Float, chars: Int, fontScale: Float = 1f): Float {
    if (chars <= 0 || availableWidthDp <= 0f) return SEARCH_GENRE_LABEL_MAX_SP
    val fit = availableWidthDp / (chars * 1.06f) / fontScale.coerceAtLeast(0.5f)
    return fit.coerceAtMost(SEARCH_GENRE_LABEL_MAX_SP).coerceAtLeast(11f)
}

internal const val SEARCH_GENRE_LABEL_MAX_SP = 14f

/** r657 第二轮：原型 v2-9 updateGenreWave —— lift(dp) = sin(i·1.05 − scrollLeft/95)·11（scrollLeft 单位 dp ≈ CSS px）。 */
internal fun searchGenreLiftDp(index: Int, scrollLeftDp: Float): Float = sin(index * 1.05f - scrollLeftDp / 95f) * 11f

internal const val SEARCH_GENRE_LIFT_MS = 300

/** r658b #15：S 形轨迹振幅（dp）。 */
internal const val SEARCH_GENRE_S_AMPLITUDE_DP = 9f

/**
 * r658b #15：探索声音 S 形错落。slot = 卡片在屏幕上的位置（以卡距为单位，0 = 第一张完整卡位）；
 * 一屏 4 个卡位 = 一个正弦周期，返回 translationY(dp)：slot 0 居中、1 上浮、2 居中、3 下沉，横滑时连续变化。
 */
internal fun searchGenreSLiftDp(slot: Float): Float = -sin(slot * (PI.toFloat() / 2f)) * SEARCH_GENRE_S_AMPLITUDE_DP
internal val SearchGenreLiftEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
