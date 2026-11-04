package xyz.cncnlp.soundwave.ui.player

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.MotionPolicy
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.playerActiveTimedLyricIndex
import xyz.cncnlp.soundwave.PlayerLyricsSettingsModel
import xyz.cncnlp.soundwave.ui.common.DesignFonts

/**
 * 「默认」播放器歌词页（r657 / v4）：
 * 顶部只有小白条（点按回到封面）→ 标题行 = 小封面 + 歌名 / 歌手 + 播放暂停 + 下一首（去掉 ♥ 与左右圆钮）→ 下面只有歌词
 * （进度条与底部按钮全部去掉）。歌词动画按 Apple Music：整首左对齐列表，当前行全亮、其余 34% 并按距离模糊
 * （0.75dp / 行，上限 2.6dp，仅 Android 12+），非当前行缩到 0.94（左侧为原点）；换行时列表 spring 滚动、
 * 当前行之下每行晚 42ms 形成波浪（cubic-bezier(.25,1.18,.4,1)，0.78s）；当前行逐字推进高亮（已唱全亮 / 未唱 42%）。
 * 点任意一行从该句播放；长按打开「歌词操作」（翻译 / 罗马音 / 字号 / 悬浮歌词 / 纠错）。简洁动效：无模糊、无错峰，0.2s 线性过渡。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DefaultPlayerLyricsPage(state: DefaultPlayerState, actions: DefaultPlayerActions, modifier: Modifier = Modifier, following: Boolean = true, coverOnlyImmersive: Boolean = false) {
    val colors = defaultPlayerColors()
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val artistContext = LocalContext.current
    val song = state.song
    Box(modifier.fillMaxSize()) {
    // r672：路由内由常驻沉浸封面层绘制（与主页同一张封面）；仅单独渲染时自绘。
    if (coverOnlyImmersive && LocalPlayerCoverMorph.current == null) DefaultPlayerLyricsHero(song.cover)
    Column(Modifier.fillMaxSize()) {
        DefaultPlayerGrabBar(colors, "返回封面", actions.onCloseLyrics)
        val headerSpacer = xyz.cncnlp.soundwave.immersiveCoverLyricsHeaderSpacerDp(
            xyz.cncnlp.soundwave.immersiveCoverHeroHeightDp(LocalConfiguration.current.screenWidthDp),
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
        )
        // Only the standard immersive preset reserves a hero. Default keeps the compact top row.
        Spacer(Modifier.height(if (coverOnlyImmersive) (headerSpacer + xyz.cncnlp.soundwave.IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP - 26f).coerceAtLeast(0f).dp else 12.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = xyz.cncnlp.soundwave.IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP.dp).padding(start = 22.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!coverOnlyImmersive) { DefaultPlayerArtwork(
                song.cover, "${song.name} 封面，点按返回",
                Modifier.playerCoverMiniSlot(LocalPlayerCoverMorph.current).size(52.dp).combinedClickable(onClick = actions.onCloseLyrics), 12.dp,
                targetSizePx = 156
            )
            Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                // r672（用户 10-22）：歌名 / 歌手字体与播放器主页（封面页）一致——衬线粗体歌名（Noto Serif SC）+ Manrope 歌手。
                // 2026-11-03 用户反馈歌词页歌名偏大尤其明显：字号比主页再收一档（22sp/26sp，主页 24sp/29sp），字体排版仍共用。
                DefaultPlayerSongTitle(song.name, colors, Modifier.fillMaxWidth(), fontSize = 22.sp, lineHeight = 26.sp)
                Spacer(Modifier.height(3.dp))
                DefaultPlayerArtistLine(song.singer, colors, Modifier.fillMaxWidth().clickable { xyz.cncnlp.soundwave.TrackNavigation.openArtist(artistContext, song) })
            }
            DefaultPlayerIconButton(
                if (state.playing) DefaultPlayerGlyphs.Pause else DefaultPlayerGlyphs.Play, if (state.playing) "暂停" else "播放",
                colors.ink, size = 46.dp, iconSize = 26.dp, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); actions.onToggle() }
            )
            PlayerLyricsNextButton(colors.ink, actions.onNext)
        }
        SharedPlayerLyricFlow(state, colors, Modifier.weight(1f).fillMaxWidth(), following, actions.onLyricLine, actions.onLyricLongPress, actions.onEmptyLyricsLongPress)
    }
    }
}

private val AppleLyricEasing = CubicBezierEasing(0.25f, 1.18f, 0.4f, 1f)

/** J 波：歌词空态单点定义（眉题 + 15sp 文案），默认页与非默认页共用——此前两套不一致（非默认页只有裸一行字）。
 * SW-07-10（G 波）：传入 [onLongPress] 时空态支持长按（打开歌词操作框：重新获取/纠错），并给出 11sp 提示。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerLyricsEmptyState(resolving: Boolean, accent: Color, muted: Color, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Box(
        if (onLongPress != null) modifier.combinedClickable(onClick = {}, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongPress() }) else modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DefaultPlayerEyebrow(if (resolving) "LOADING" else "NO LYRICS", accent)
            Spacer(Modifier.height(8.dp))
            Text(if (resolving) "加载歌词…" else "暂无歌词", color = muted, fontSize = 15.sp, fontFamily = DesignFonts.Manrope)
            if (onLongPress != null && !resolving) {
                Spacer(Modifier.height(6.dp))
                Text("长按重新获取", color = muted.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = DesignFonts.Manrope)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SharedPlayerLyricFlow(state: DefaultPlayerState, colors: DefaultPlayerColors, modifier: Modifier, following: Boolean, onLyricLine: (LyricLine) -> Unit, onLyricLongPress: (Int) -> Unit, onEmptyLongPress: (() -> Unit)? = null) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val lyrics = state.lyrics
    if (lyrics.isEmpty()) {
        PlayerLyricsEmptyState(state.resolving, colors.accentText, colors.muted, modifier, onEmptyLongPress)
        return
    }
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !reduceMotion
    val timed = remember(lyrics) { lyrics.mapIndexedNotNull { i, l -> l.timeMs.takeIf { it >= 0L }?.let { i to it } } }
    val active = playerActiveTimedLyricIndex(timed, state.lyricTimeMs)
    val listState = rememberLazyListState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) lastUserScroll = System.currentTimeMillis() }
    }
    BoxWithConstraints(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn
            )
        }
    ) {
        val anchor = maxHeight * 0.26f
        var firstFollow by remember(lyrics) { androidx.compose.runtime.mutableStateOf(true) }
        LaunchedEffect(active, lyrics, following) {
            if (active < 0) return@LaunchedEffect
            if (System.currentTimeMillis() - lastUserScroll < 2500L) return@LaunchedEffect
            // r658c P：歌词页常驻组合；隐藏时（主页可见）只瞬时对齐当前行、不跑滚动动画，进入时列表已就位。
            if (firstFollow || reduceMotion || !following) { listState.scrollToItem(active); firstFollow = false; return@LaunchedEffect }
            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            if (target != null) {
                // offset 0 = contentPadding 顶边（anchor）：滚动 offset 即把当前行固定在视口约 1/4 处。
                listState.animateScrollBy(target.offset.toFloat(), spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow))
            } else listState.animateScrollToItem(active)
        }
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight - anchor - 60.dp)
        ) {
            itemsIndexed(lyrics, key = { i, _ -> i }) { index, line ->
                val rowPad = PlayerLyricsSettingsModel.defaultPageRowPaddingDp(state.lyricGapDp)
                val distance = if (active < 0) 3 else kotlin.math.abs(index - active)
                val isActive = index == active
                // 当前行之下每行晚 42ms，形成波浪；简洁动效下无错峰、0.2s 线性。
                val delay = if (reduceMotion || active < 0 || index <= active) 0 else ((index - active) * 42).coerceAtMost(336)
                val spec: androidx.compose.animation.core.AnimationSpec<Float> =
                    if (reduceMotion) tween(200, easing = LinearEasing) else tween(780, delayMillis = delay, easing = AppleLyricEasing)
                val alpha by animateFloatAsState(if (isActive) 1f else 0.34f, if (reduceMotion) tween(200, easing = LinearEasing) else tween(420, delayMillis = delay), label = "lyricAlpha")
                val scale by animateFloatAsState(if (isActive) 1f else 0.94f, spec, label = "lyricScale")
                // r658c P：距离模糊跟随统一偏好（默认 2 → 每行 0.75dp、上限 2.6dp，与原型一致；0 = 关闭）。
                val blurDp = if (!blurSupported || isActive) 0f else PlayerLyricsSettingsModel.defaultPageBlurDp(distance, state.lyricBlurDp)
                Column(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLyricLongPress(index) }, onClick = { onLyricLine(line) }
                        )
                        .padding(start = 26.dp, end = 30.dp, top = rowPad.dp, bottom = rowPad.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f); this.alpha = alpha }
                        .then(if (blurDp > 0.05f) Modifier.blur(blurDp.dp) else Modifier)
                ) {
                    val fontSize = PlayerLyricsSettingsModel.defaultPageFontSp(state.lyricFontSp)
                    DefaultPlayerLyricText(line, lyrics.getOrNull(index + 1), state.lyricTimeMs, isActive,
                        state.lyricWordLiftEnabled, state.lyricWordKaraoke, reduceMotion, colors.ink, fontSize)
                    if (state.showTranslation && line.translation.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(line.translation, color = colors.muted, fontSize = (fontSize * 0.56f).sp, lineHeight = (fontSize * 0.78f).sp, fontFamily = DesignFonts.Manrope)
                    }
                }
            }
        }
    }
}

/**
 * 当前行逐字推进：有逐字时间轴时按词的起止插值；没有时按本句到下一句的时长均匀推进字符。
 * 已唱 = 主色 100%，未唱 = 42%。
 */
internal fun lyricKaraokeText(line: LyricLine, nextStartMs: Long?, timeMs: Long, ink: Color): AnnotatedString {
    val text = line.text.ifBlank { "♪" }
    val sung = SpanStyle(color = ink)
    val unsung = SpanStyle(color = ink.copy(alpha = 0.42f))
    if (line.hasWordTimeline()) {
        return buildAnnotatedString {
            line.words.forEach { w ->
                val f = when {
                    timeMs >= w.endMs -> 1f
                    timeMs <= w.startMs -> 0f
                    else -> (timeMs - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1L)
                }
                val cut = (w.text.length * f).toInt().coerceIn(0, w.text.length)
                withStyle(sung) { append(w.text.substring(0, cut)) }
                withStyle(unsung) { append(w.text.substring(cut)) }
            }
        }
    }
    val start = line.timeMs.coerceAtLeast(0L)
    val end = (nextStartMs ?: (start + 4000L)).coerceAtLeast(start + 1L)
    val f = ((timeMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    val cut = (text.length * f).toInt().coerceIn(0, text.length)
    return buildAnnotatedString {
        withStyle(sung) { append(text.substring(0, cut)) }
        withStyle(unsung) { append(text.substring(cut)) }
    }
}

/** Default and standard now share the real timed-word renderer; plain LRC keeps its old fallback. */
@Composable
internal fun DefaultPlayerLyricText(line: LyricLine, next: LyricLine?, timeMs: Long, active: Boolean,
    liftEnabled: Boolean, karaokeEnabled: Boolean, reduceMotion: Boolean, ink: Color, fontSize: Int) {
    if (active && line.hasWordTimeline() && (karaokeEnabled || liftEnabled)) {
        androidx.compose.runtime.CompositionLocalProvider(xyz.cncnlp.soundwave.LocalLyricFontFamily provides DesignFonts.Manrope) {
            xyz.cncnlp.soundwave.KaraokeLine(line, next, timeMs, ink, fontSize.sp, fontSize * 1.28f,
                androidx.compose.ui.text.style.TextAlign.Start, true,
                liftWords = xyz.cncnlp.soundwave.playerShouldLiftCurrentWords(liftEnabled, true, reduceMotion),
                highlightWords = karaokeEnabled)
        }
    } else Text(
        if (active && karaokeEnabled) lyricKaraokeText(line, next?.timeMs, timeMs, ink) else AnnotatedString(line.text.ifBlank { "♪" }),
        color = ink, fontSize = fontSize.sp, lineHeight = (fontSize * 1.28f).sp,
        fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
}
