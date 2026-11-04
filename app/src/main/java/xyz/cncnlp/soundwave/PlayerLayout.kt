package xyz.cncnlp.soundwave

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign

/**
 * Player presets are independent visual presentations; the immersive cover is an upper-page hero.
 * 1.0.655：新增全新设计的「默认（default）」样式（主页/歌词页/队列均为新设计，跟随应用浅/深色主题），
 * 新安装默认使用；历史 standard / 未知值迁移到「默认」。Apple Music / 歌词样式 / 沉浸封面保持不变。
 */
const val PLAYER_LAYOUT_DEFAULT = "default"
const val PLAYER_LAYOUT_COVER_ONLY = "default_immersive_cover"

fun normalizePlayerLayout(raw: String): String = when (raw) {
    PLAYER_LAYOUT_COVER_ONLY -> PLAYER_LAYOUT_COVER_ONLY
    "photo_lyric", "immersive_cover" -> "immersive_cover" // migrate the legacy photo preset
    "lyrics" -> "lyrics"
    "apple" -> "apple"
    else -> PLAYER_LAYOUT_DEFAULT
}

fun playerLayoutLabel(raw: String): String = when (normalizePlayerLayout(raw)) {
    // accept6-uipolish2（用户 2026-11-04 22:24 裁定）：选项文案由「标准 · 仅封面沉浸」简化为「标准」，仅改文案，key/行为不变。
    PLAYER_LAYOUT_COVER_ONLY -> "标准"
    "immersive_cover" -> "沉浸封面"
    "lyrics" -> "歌词样式"
    "apple" -> "Apple Music"
    else -> "默认"
}

/** 「默认」样式：全新设计的主页/歌词页/队列，独立于其他样式的歌词视口与控制区。 */
fun playerLayoutIsDefault(raw: String): Boolean = playerLayoutSpec(raw).visualMode == PlayerVisualMode.DEFAULT

/** Standard routes and controls, with immersion restricted to the artwork slot. */
internal fun playerUsesCoverOnlyImmersion(raw: String): Boolean = normalizePlayerLayout(raw) == PLAYER_LAYOUT_COVER_ONLY

val PLAYER_LAYOUT_CHOICES = listOf(
    "default" to "默认",
    PLAYER_LAYOUT_COVER_ONLY to "标准",
    "apple" to "Apple Music"
)

/**
 * r658c #12（用户 10-19）：「歌词样式 / 沉浸封面」两种播放器样式已从可选项移除；
 * 已存储或传入的旧值统一迁移到「默认」。渲染代码保留但不再命中。
 */
internal val PLAYER_LAYOUTS_REMOVED = setOf("lyrics", "immersive_cover", "photo_lyric")

fun selectablePlayerLayout(raw: String): String =
    if (raw in PLAYER_LAYOUTS_REMOVED) PLAYER_LAYOUT_DEFAULT else normalizePlayerLayout(raw)

internal const val PLAYER_LYRICS_PREVIEW_LINES = 4
internal const val PLAYER_LYRICS_PREVIEW_CENTER_FONT_BASE_SP = 18
internal const val PLAYER_LYRICS_PREVIEW_CENTER_FONT_DELTA_SP = 2
internal const val PLAYER_LYRICS_PREVIEW_SIDE_FONT_SP = 14
internal const val PLAYER_LYRICS_PREVIEW_LINE_GAP_DP = 2
internal const val PLAYER_LYRICS_PREVIEW_TEXT_MAX_LINES = 2
internal const val PLAYER_LYRICS_STYLE_COVER_TO_LYRICS_GAP_DP = 5
internal const val PLAYER_LYRICS_STYLE_LYRICS_TO_METADATA_GAP_DP = 5
internal const val IMMERSIVE_HERO_FADE_FRACTION = 0.34f
internal const val IMMERSIVE_PLAYER_BACKDROP_BLUR_DP = 40
internal const val IMMERSIVE_PLAYER_BACKDROP_ALPHA = 0.90f
internal const val IMMERSIVE_PLAYER_BACKDROP_SCALE = 1.20f
internal const val PLAYER_SHEET_ENTER_DURATION_MS = 360
// SW-12-06（G 波，用户批准）：退场 ≤ 进场——常量由 420 收敛到 360，与可见退场 tween（已用进场时长）对齐；
// 宿主卸载延迟 = 本常量 + HOST_EXIT_TAIL(200)，仍留足尾差。
internal const val PLAYER_SHEET_EXIT_DURATION_MS = 360
internal const val PLAYER_SHEET_HOST_EXIT_TAIL_MS = 200L
internal const val PLAYER_DOCK_HOST_HEIGHT_DP = 150

internal fun playerOverlayVisible(currentState: Boolean, targetState: Boolean): Boolean = currentState || targetState
/** The moving immersive sheet is the sole owner of full-screen artwork while entering, exiting, and while open. */
internal fun playerImmersiveTransitionSurfaceVisible(rawLayout: String, currentState: Boolean, targetState: Boolean): Boolean =
    playerUsesImmersiveCoverBackdrop(rawLayout) && playerOverlayVisible(currentState, targetState)

/** Shared between the player sheet and route host so a released drag continues from the exact finger position. */
internal class PlayerDismissOffsetHolder {
    val state = mutableFloatStateOf(0f)
    private var statusBarExposed = false

    /** Read only when the host creates its exit target; avoids making route composition observe every drag pixel. */
    @Volatile
    var latestOffsetPx: Float = 0f
        private set

    fun update(offsetPx: Float) {
        val safeOffset = offsetPx.coerceAtLeast(0f)
        latestOffsetPx = safeOffset
        state.floatValue = safeOffset
    }

    fun reset() {
        update(0f)
        statusBarExposed = false
    }

    fun updateStatusBarExposure(offsetPx: Float, insetPx: Float, onExposureChanged: (Boolean) -> Unit) {
        val exposed = playerDismissStatusBarShouldExpose(offsetPx, insetPx)
        if (statusBarExposed == exposed) return
        statusBarExposed = exposed
        onExposureChanged(exposed)
    }
}

internal fun playerDismissExitOffsetPx(fullHeightPx: Int, dismissOffsetPx: Float): Int {
    val height = fullHeightPx.coerceAtLeast(0)
    val offset = playerDismissSurfaceOffsetPx(dismissOffsetPx, height.toFloat())
    return (height - offset).toInt()
}

/** Clamp the shared panel translation and source-page reveal to their measured surface bounds. */
internal fun playerDismissSurfaceOffsetPx(offsetPx: Float, surfaceHeightPx: Float): Float =
    offsetPx.coerceIn(0f, surfaceHeightPx.coerceAtLeast(0f))

/**
 * Keep the source route painted during entry/exit and only while the settled player is interactively dismissed.
 * At rest the full-screen player keeps its existing single-surface rendering cost.
 */
internal fun playerRouteUnderlayShouldDraw(
    rawLayout: String,
    currentState: Boolean,
    targetState: Boolean,
    dismissOffsetPx: Float = 0f
): Boolean = if (playerUsesImmersiveCoverBackdrop(rawLayout)) {
    !playerOverlayVisible(currentState, targetState) || currentState != targetState || dismissOffsetPx > 0f
} else {
    !(currentState && targetState) || dismissOffsetPx > 0f
}

internal fun playerDismissGestureRevealsSource(
    currentState: Boolean,
    targetState: Boolean,
    dismissOffsetPx: Float
): Boolean = currentState && targetState && dismissOffsetPx > 0f

internal fun playerDismissStatusBarShouldExpose(offsetPx: Float, statusBarInsetPx: Float): Boolean =
    statusBarInsetPx > 0f && offsetPx >= statusBarInsetPx

/** Preserve the detail route under the moving sheet; ordinary player behavior remains unchanged at rest. */
internal fun playerDetailUnderlayShouldDraw(
    rawLayout: String,
    currentState: Boolean,
    targetState: Boolean,
    dismissOffsetPx: Float = 0f
): Boolean = if (playerUsesImmersiveCoverBackdrop(rawLayout)) {
    playerRouteUnderlayShouldDraw(rawLayout, currentState, targetState, dismissOffsetPx)
} else true

internal fun playerTransitionBackdropMovesWithSheet(rawLayout: String): Boolean =
    playerUsesImmersiveCoverBackdrop(rawLayout)

internal const val IMMERSIVE_LYRICS_BASE_SPACER_DP = 31f
internal const val IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP = 17f
internal const val IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP = 58f
internal const val IMMERSIVE_LYRICS_METADATA_ABOVE_HERO_DP = 15f
internal const val IMMERSIVE_LYRICS_HEADER_TO_LYRICS_GAP_DP = 4f
internal const val IMMERSIVE_LYRICS_CONTENT_TOP_PADDING_DP = 6f
internal const val IMMERSIVE_LYRICS_BOUNDARY_GAP_DP =
    IMMERSIVE_LYRICS_HEADER_TO_LYRICS_GAP_DP + IMMERSIVE_LYRICS_CONTENT_TOP_PADDING_DP
internal const val IMMERSIVE_LYRICS_MIN_VIEWPORT_DP = 256f
internal const val IMMERSIVE_LYRICS_CONTROLS_GAP_DP = 8f
internal const val IMMERSIVE_LYRICS_ESTIMATED_ROW_HEIGHT_DP = 64f
internal const val PLAYER_FULLSCREEN_LYRICS_NEIGHBOR_SENTENCES = 2
internal const val PLAYER_FULLSCREEN_LYRICS_WINDOW_SIZE = PLAYER_FULLSCREEN_LYRICS_NEIGHBOR_SENTENCES * 2 + 1
internal const val IMMERSIVE_COVER_ARTWORK_REQUEST_MIN_PX = 720
internal const val IMMERSIVE_COVER_ARTWORK_REQUEST_MAX_PX = 2048
internal const val IMMERSIVE_COVER_LYRICS_HEADER_LIGHT_THRESHOLD = 0.62f
internal const val IMMERSIVE_COVER_LYRICS_HEADER_SURFACE_ALPHA = 0.86f

internal fun immersiveCoverArtworkRequestSizePx(heroSizePx: Int): Int =
    heroSizePx.coerceIn(IMMERSIVE_COVER_ARTWORK_REQUEST_MIN_PX, IMMERSIVE_COVER_ARTWORK_REQUEST_MAX_PX)

internal fun immersiveCoverLyricsHeaderUsesLightSurface(artworkLuminance: Float): Boolean =
    artworkLuminance.coerceIn(0f, 1f) >= IMMERSIVE_COVER_LYRICS_HEADER_LIGHT_THRESHOLD

internal fun immersiveCoverLyricsHeaderSurfaceColor(artworkLuminance: Float): Color =
    if (immersiveCoverLyricsHeaderUsesLightSurface(artworkLuminance)) {
        Color.White.copy(alpha = IMMERSIVE_COVER_LYRICS_HEADER_SURFACE_ALPHA)
    } else Color.Transparent

internal fun immersiveCoverLyricsHeaderPrimaryColor(artworkLuminance: Float): Color =
    if (immersiveCoverLyricsHeaderUsesLightSurface(artworkLuminance)) Color(0xFF15191F.toInt()) else Color.White

internal fun immersiveCoverLyricsHeaderSecondaryColor(artworkLuminance: Float): Color =
    if (immersiveCoverLyricsHeaderUsesLightSurface(artworkLuminance)) Color(0xFF48515D.toInt())
    else Color.White.copy(alpha = 0.72f)

private fun gradientValueAt(position: Float, start: Float, end: Float, startValue: Float, endValue: Float): Float {
    val fraction = ((position.coerceIn(0f, 1f) - start) / (end - start)).coerceIn(0f, 1f)
    return startValue + (endValue - startValue) * fraction
}

/** A normalized smooth fade that reaches zero at the hero's measured lower edge on any canvas size. */
internal fun immersivePlayerHeroArtworkAlpha(positionPx: Float, canvasHeightPx: Float, fadeFraction: Float = IMMERSIVE_HERO_FADE_FRACTION): Float {
    val height = canvasHeightPx.coerceAtLeast(1f)
    val position = (positionPx / height).coerceIn(0f, 1f)
    val fadeStart = 1f - fadeFraction.coerceIn(0.05f, 0.95f)
    val progress = ((position - fadeStart) / (1f - fadeStart)).coerceIn(0f, 1f)
    val eased = progress * progress * (3f - 2f * progress)
    return 1f - eased
}

/** Shared full-screen tint/scrim ramps keep the blurred artwork continuous below the bounded hero. */
internal fun immersivePlayerBackdropTintAlpha(position: Float): Float = when {
    position <= 0.22f -> gradientValueAt(position, 0f, 0.22f, 0.04f, 0.045f)
    position <= 0.48f -> gradientValueAt(position, 0.22f, 0.48f, 0.045f, 0.05f)
    position <= 0.74f -> gradientValueAt(position, 0.48f, 0.74f, 0.05f, 0.06f)
    else -> gradientValueAt(position, 0.74f, 1f, 0.06f, 0.065f)
}

internal fun immersivePlayerArtworkScrimBoost(artworkLuminance: Float): Float =
    ((artworkLuminance.coerceIn(0f, 1f) - 0.40f).coerceAtLeast(0f) * 0.12f).coerceAtMost(0.07f)

internal fun immersivePlayerBackdropScrimAlpha(position: Float, artworkLuminance: Float = 0f): Float {
    val base = when {
        position <= 0.22f -> gradientValueAt(position, 0f, 0.22f, 0.035f, 0.04f)
        position <= 0.48f -> gradientValueAt(position, 0.22f, 0.48f, 0.04f, 0.05f)
        position <= 0.74f -> gradientValueAt(position, 0.48f, 0.74f, 0.05f, 0.06f)
        else -> gradientValueAt(position, 0.74f, 1f, 0.06f, 0.065f)
    }
    return (base + immersivePlayerArtworkScrimBoost(artworkLuminance)).coerceAtMost(0.16f)
}

/** Shared width-based hero height used by LibraryPage's Immersive detail cover and FullPlayer. */
internal fun immersiveCoverHeroHeightDp(screenWidthDp: Int): Float =
    (screenWidthDp * 1.12f).coerceIn(380f, 520f) + 8f

internal fun playerLyricsViewportStartIndex(lyricCount: Int, currentIndex: Int): Int {
    val count = lyricCount.coerceAtLeast(0)
    if (count <= PLAYER_FULLSCREEN_LYRICS_WINDOW_SIZE) return 0
    val safeCurrent = currentIndex.takeIf { it in 0 until count } ?: 0
    return (safeCurrent - PLAYER_FULLSCREEN_LYRICS_NEIGHBOR_SENTENCES)
        .coerceIn(0, count - PLAYER_FULLSCREEN_LYRICS_WINDOW_SIZE)
}

/** Full-page lyric routes lazily traverse the whole song; previews use the bounded five-line window. */
internal fun playerLyricsViewportIndices(lyricCount: Int, currentIndex: Int, fullLyricsPage: Boolean): IntRange {
    val count = lyricCount.coerceAtLeast(0)
    if (count == 0) return 0 until 0
    val start = if (fullLyricsPage) 0 else playerLyricsViewportStartIndex(count, currentIndex)
    val endExclusive = if (fullLyricsPage) count else minOf(count, start + PLAYER_FULLSCREEN_LYRICS_WINDOW_SIZE)
    return start until endExclusive
}

/** The cached input contains sorted nonnegative (lineIndex, timestampMs) pairs from the existing parser. */
internal fun playerActiveTimedLyricIndex(timedLyrics: List<Pair<Int, Long>>, currentTimeMs: Long): Int {
    var low = 0
    var high = timedLyrics.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (timedLyrics[middle].second <= currentTimeMs) low = middle + 1 else high = middle
    }
    return if (low == 0) -1 else timedLyrics[low - 1].first
}

/** Full-page lyrics show complete source text; previews keep their established line caps. */
internal fun playerLyricsTextMaxLines(fullLyricsPage: Boolean, standardMaxLines: Int): Int =
    if (fullLyricsPage) Int.MAX_VALUE else standardMaxLines

/** Use real provider word timing when present; otherwise keep the existing whole-line progress fallback. */
internal fun playerShouldRenderWordKaraoke(
    active: Boolean,
    enabled: Boolean,
    hasWordTimeline: Boolean
): Boolean = active && enabled

/** The lift is visual-only: it never gates active-word highlight or invents line-level word timestamps. */
internal fun playerShouldLiftCurrentWords(enabled: Boolean, hasWordTimeline: Boolean, systemReduceMotion: Boolean): Boolean =
    enabled && hasWordTimeline && !systemReduceMotion

internal fun playerChromeShouldRevealOnPause(lyricSeekSuppressionActive: Boolean): Boolean =
    !lyricSeekSuppressionActive

/** Keep the active full-page lyric at the measured vertical center of the viewport. */
internal fun playerLyricsActiveLineTopOffsetPx(viewportHeightPx: Int, activeLineHeightPx: Int): Int {
    val viewport = viewportHeightPx.coerceAtLeast(0)
    val lineHeight = activeLineHeightPx.coerceAtLeast(0)
    val maxTop = (viewport - lineHeight).coerceAtLeast(0)
    return maxTop / 2
}

internal fun playerLyricsActiveScrollOffsetPx(
    viewportHeightPx: Int,
    activeLineHeightPx: Int,
    currentIndex: Int
): Int = if (currentIndex > 0) -playerLyricsActiveLineTopOffsetPx(viewportHeightPx, activeLineHeightPx) else 0

/** Place a manually selected lyric at the measured vertical center of the viewport. */
internal fun playerLyricsCenteredScrollOffsetPx(viewportHeightPx: Int, lineHeightPx: Int): Int =
    -((viewportHeightPx.coerceAtLeast(0) - lineHeightPx.coerceAtLeast(0)).coerceAtLeast(0) / 2)

internal fun playerLyricsFirstLineTopPaddingPx(
    viewportHeightPx: Int,
    activeLineHeightPx: Int,
    currentIndex: Int
): Int = if (currentIndex == 0) playerLyricsActiveLineTopOffsetPx(viewportHeightPx, activeLineHeightPx) else 0

internal fun playerLyricsLastLineBottomPaddingPx(
    viewportHeightPx: Int,
    activeLineHeightPx: Int,
    currentIndex: Int,
    lyricCount: Int
): Int = if (lyricCount > 0 && currentIndex == lyricCount - 1) {
    (viewportHeightPx - playerLyricsActiveLineTopOffsetPx(viewportHeightPx, activeLineHeightPx) - activeLineHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
} else 0

/** Reset the auto-follow anchor on track change only for ImmersiveCover. */
internal fun playerImmersiveLyricsScrollTrackKey(immersiveCoverLyricsMode: Boolean, trackKey: String): String =
    if (immersiveCoverLyricsMode) trackKey else ""

/** Estimate how many measured lyric rows fit into the real Compose viewport after its padding. */
internal fun immersiveLyricsVisibleRowCount(viewportHeightDp: Float, activeLineHeightDp: Float): Int {
    if (viewportHeightDp <= 0f) return 1
    val rowHeight = activeLineHeightDp.takeIf { it > 0f } ?: IMMERSIVE_LYRICS_ESTIMATED_ROW_HEIGHT_DP
    return (viewportHeightDp / rowHeight.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
}

/** Anchor the active line inside the measured visible window instead of pinning it to the first row. */
internal fun immersiveLyricsViewportStartIndex(lyricCount: Int, currentIndex: Int, visibleRows: Int): Int {
    val count = lyricCount.coerceAtLeast(0)
    if (count == 0) return 0
    val rows = visibleRows.coerceAtLeast(1).coerceAtMost(count)
    val current = currentIndex.coerceIn(0, count - 1)
    val visibleBefore = minOf(2, rows / 2)
    return (current - visibleBefore).coerceIn(0, count - rows)
}

/** Pure viewport window for tests; rendering still keeps the entire song lazily scrollable. */
internal fun immersiveLyricsVisibleWindowIndices(
    lyricCount: Int,
    currentIndex: Int,
    viewportHeightDp: Float,
    activeLineHeightDp: Float
): IntRange {
    val count = lyricCount.coerceAtLeast(0)
    if (count == 0) return 0 until 0
    val visibleRows = immersiveLyricsVisibleRowCount(viewportHeightDp, activeLineHeightDp).coerceAtMost(count)
    val start = immersiveLyricsViewportStartIndex(count, currentIndex, visibleRows)
    return start until (start + visibleRows)
}

/** Center the active ImmersiveCover lyric using its measured Compose row height. */
internal fun immersiveLyricsActiveCenterOffsetPx(viewportHeightPx: Int, activeLineHeightPx: Int): Int =
    (viewportHeightPx - activeLineHeightPx).coerceAtLeast(0) / 2

/** Measured tail space lets the last ImmersiveCover lyric reach the same active-line anchor as earlier lines. */
internal fun immersiveLyricsEndContentPaddingPx(viewportHeightPx: Int, activeLineHeightPx: Int): Int =
    immersiveLyricsActiveCenterOffsetPx(viewportHeightPx, activeLineHeightPx)

/** LazyListState uses a negative scroll offset to position an existing row below the viewport top. */
internal fun immersiveLyricsActiveScrollOffsetPx(viewportHeightPx: Int, activeLineHeightPx: Int, currentIndex: Int): Int =
    if (currentIndex > 0) -immersiveLyricsActiveCenterOffsetPx(viewportHeightPx, activeLineHeightPx) else 0

/** The first lyric has no preceding row to scroll past, so center it with top content padding instead. */
internal fun immersiveLyricsTopContentPaddingPx(viewportHeightPx: Int, activeLineHeightPx: Int, currentIndex: Int): Int =
    if (currentIndex <= 0) immersiveLyricsActiveCenterOffsetPx(viewportHeightPx, activeLineHeightPx) else 0

internal fun playerLyricsPreviewMaxHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp - 650).coerceIn(88, 160)

internal fun playerLyricsPreviewFontSp(isCurrent: Boolean): Int =
    if (isCurrent) PLAYER_LYRICS_PREVIEW_CENTER_FONT_BASE_SP + PLAYER_LYRICS_PREVIEW_CENTER_FONT_DELTA_SP
    else PLAYER_LYRICS_PREVIEW_SIDE_FONT_SP

/** Keep the active sentence among the four-line preview while clamping at either end. */
internal fun playerLyricsPreviewStartIndex(lyricCount: Int, currentIndex: Int): Int {
    val count = lyricCount.coerceAtLeast(0)
    if (count == 0) return 0
    val visibleCount = minOf(count, PLAYER_LYRICS_PREVIEW_LINES)
    val safeCurrentIndex = currentIndex.coerceIn(0, count - 1)
    return (safeCurrentIndex - visibleCount / 2).coerceIn(0, count - visibleCount)
}

internal fun playerLyricsPreviewIndices(lyricCount: Int, currentIndex: Int): List<Int> {
    val count = lyricCount.coerceAtLeast(0)
    val start = playerLyricsPreviewStartIndex(count, currentIndex)
    return (start until minOf(count, start + PLAYER_LYRICS_PREVIEW_LINES)).toList()
}

enum class PlayerVisualMode { STANDARD, DEFAULT, APPLE, IMMERSIVE, IMMERSIVE_COVER, LYRICS_STYLE }
enum class PlayerArtworkPresentation { SQUARE_CARD, TOP_HERO, FULL_BLEED_BACKGROUND }

data class PlayerLayoutSpec(
    val key: String,
    val visualMode: PlayerVisualMode,
    val showLyrics: Boolean,
    val immersive: Boolean,
    val coverFraction: Float,
    val lyricsAlignment: androidx.compose.ui.Alignment.Horizontal,
    val artworkPresentation: PlayerArtworkPresentation
)

fun playerLayoutSpec(raw: String): PlayerLayoutSpec {
    return when (normalizePlayerLayout(raw)) {
        PLAYER_LAYOUT_COVER_ONLY -> PlayerLayoutSpec(
            PLAYER_LAYOUT_COVER_ONLY, PlayerVisualMode.DEFAULT, false, false, 1f,
            androidx.compose.ui.Alignment.Start, PlayerArtworkPresentation.TOP_HERO
        )
        "default" -> PlayerLayoutSpec(
            "default", PlayerVisualMode.DEFAULT, false, false, 1f,
            androidx.compose.ui.Alignment.Start, PlayerArtworkPresentation.SQUARE_CARD
        )
        "immersive_cover" -> PlayerLayoutSpec(
            "immersive_cover", PlayerVisualMode.IMMERSIVE_COVER, false, false, 1f,
            androidx.compose.ui.Alignment.Start, PlayerArtworkPresentation.TOP_HERO
        )
        "lyrics" -> PlayerLayoutSpec(
            "lyrics", PlayerVisualMode.LYRICS_STYLE, false, false, 0.78f,
            androidx.compose.ui.Alignment.CenterHorizontally, PlayerArtworkPresentation.SQUARE_CARD
        )
        else -> PlayerLayoutSpec(
            "apple", PlayerVisualMode.APPLE, false, false, 0.78f,
            androidx.compose.ui.Alignment.CenterHorizontally, PlayerArtworkPresentation.SQUARE_CARD
        )
    }
}

/** The current song's art backs every route of the immersive-cover player, including its queue. */
internal fun playerUsesImmersiveCoverBackdrop(raw: String): Boolean =
    playerLayoutSpec(raw).visualMode == PlayerVisualMode.IMMERSIVE_COVER

internal fun playerUsesDedicatedImmersiveCoverLyricsViewport(raw: String, lyricsPage: Boolean, queuePage: Boolean): Boolean =
    lyricsPage && !queuePage && playerUsesImmersiveCoverBackdrop(raw)

internal fun playerShowsDeviceAndQueueActions(
    raw: String,
    lyricsPage: Boolean,
    queuePage: Boolean
): Boolean =
    !playerUsesDedicatedImmersiveCoverLyricsViewport(raw, lyricsPage, queuePage)

internal fun playerUsesBrightImmersiveCoverLyricsHeader(
    raw: String,
    lyricsPage: Boolean,
    queuePage: Boolean,
    artworkLuminance: Float
): Boolean = playerUsesDedicatedImmersiveCoverLyricsViewport(raw, lyricsPage, queuePage) &&
    immersiveCoverLyricsHeaderUsesLightSurface(artworkLuminance)

/** The cover hero remains behind the immersive lyrics route; only the queue replaces it. */
internal fun playerShowsImmersiveCoverHero(raw: String, queuePage: Boolean): Boolean =
    playerUsesImmersiveCoverBackdrop(raw) && !queuePage

/** Route-independent source selector: callers pass the playing song, never a queue candidate. */
internal fun playerImmersiveBackdropArtwork(raw: String, currentSongCover: String): String? =
    currentSongCover.takeIf { playerUsesImmersiveCoverBackdrop(raw) && it.isNotBlank() }

/**
 * Center the title/singer row just above the hero edge while reserving a usable lyric viewport
 * above the existing bottom controls on short portrait screens.
 */
internal fun immersiveLyricsHeaderSpacerDp(
    heroHeightDp: Float,
    statusBarInsetDp: Float,
    contentHeightDp: Float = 0f,
    controlsHeightDp: Float = 0f
): Float {
    val desired = (heroHeightDp - IMMERSIVE_LYRICS_METADATA_ABOVE_HERO_DP -
        IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP / 2f - statusBarInsetDp - IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP)
        .coerceAtLeast(IMMERSIVE_LYRICS_BASE_SPACER_DP)
    if (contentHeightDp <= 0f || controlsHeightDp <= 0f) return desired
    val maximum = (contentHeightDp - IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP -
        IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP - IMMERSIVE_LYRICS_BOUNDARY_GAP_DP -
        IMMERSIVE_LYRICS_MIN_VIEWPORT_DP - IMMERSIVE_LYRICS_CONTROLS_GAP_DP - controlsHeightDp)
        .coerceAtLeast(IMMERSIVE_LYRICS_BASE_SPACER_DP)
    return minOf(desired, maximum)
}

/** Keep the ImmersiveCover header anchored to its Hero; the weighted lyric body takes every remaining pixel. */
internal fun immersiveCoverLyricsHeaderSpacerDp(heroHeightDp: Float, statusBarInsetDp: Float): Float =
    (heroHeightDp - IMMERSIVE_LYRICS_METADATA_ABOVE_HERO_DP -
        IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP / 2f - statusBarInsetDp - IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP)
        .coerceAtLeast(IMMERSIVE_LYRICS_BASE_SPACER_DP)

internal fun playerLayoutUsesFullBleedCoverPage(raw: String, lyricPage: Boolean, queuePage: Boolean): Boolean =
    playerLayoutSpec(raw).artworkPresentation == PlayerArtworkPresentation.FULL_BLEED_BACKGROUND &&
        !lyricPage && !queuePage

fun playerLayoutShowsLyrics(raw: String): Boolean = playerLayoutSpec(raw).showLyrics

fun playerLayoutImmersive(raw: String): Boolean = playerLayoutSpec(raw).immersive

/** Only lyric rows in the Lyrics Style cover preview open its existing immersive lyrics route. */
internal fun playerLyricsStylePreviewTapShouldEnterImmersive(raw: String, compactPreview: Boolean): Boolean =
    compactPreview && playerLayoutSpec(raw).visualMode == PlayerVisualMode.LYRICS_STYLE

/** Immersive Cover and Lyrics Style use this toggle for the full-screen lyrics route. */
internal fun playerShowsLyricsPage(raw: String, immersiveLyricsMode: Boolean, showLyrics: Boolean, dismissed: Boolean): Boolean {
    val spec = playerLayoutSpec(raw)
    val immersivePreset = spec.visualMode == PlayerVisualMode.IMMERSIVE_COVER || spec.visualMode == PlayerVisualMode.LYRICS_STYLE
    return (if (immersivePreset) immersiveLyricsMode else showLyrics || spec.showLyrics) && !dismissed
}

/** Do not flash the immersive-cover lyrics navigation strip on route entry during active playback. */
internal fun playerChromeVisibleAfterLyricsEntry(raw: String, playing: Boolean): Boolean =
    !playerUsesImmersiveCoverBackdrop(raw) || !playing

internal fun playerFullscreenLyricsHorizontalAlignment(immersiveLyricsMode: Boolean): Alignment.Horizontal =
    if (immersiveLyricsMode) Alignment.Start else Alignment.CenterHorizontally

internal fun playerFullscreenLyricsTextAlignment(immersiveLyricsMode: Boolean): TextAlign =
    if (immersiveLyricsMode) TextAlign.Start else TextAlign.Center

/**
 * 详情页封面样式：1.0.654 起仅「默认」（设计稿 v2 各类型头部）与「标准」（参考 1000073096：大圆角方封面 +
 * 等宽眉题 + 超大衬线标题 + 播放胶囊/收藏圆钮 + 「曲目 N 首」）。旧「默认2 / 沉浸封面」已删除，存储值迁移为默认。
 */
internal object DetailCoverStyles {
    const val DEFAULT = "default"
    const val STANDARD = "standard"
    /** 已删除的旧样式 id，仅用于迁移与旧辅助函数的兼容判断。 */
    const val DEFAULT2 = "default2"
    const val IMMERSIVE = "immersive"
    /**
     * r657 第三轮 b：新「沉浸」详情样式（封面满幅铺到状态栏、无圆角，底部取色渐隐到页面背景，浮动半透明返回 / 更多）。
     * 与已删除的旧 [IMMERSIVE]（模糊背板 + 自适应文字）不是同一实现，故用新 id，旧 detail_immersive_cover 迁移仍落到默认。
     */
    const val IMMERSIVE_HERO = "immersive_hero"

    // r658h（用户 10-20）：详情页「沉浸」封面样式去除（旧值迁移到默认）；沉浸封面只保留给今日推荐详情。
    val CHOICES = listOf(DEFAULT to "默认", STANDARD to "标准", IMMERSIVE_HERO to "沉浸封面")

    fun normalize(raw: String?): String = when (raw) {
        STANDARD -> STANDARD
        IMMERSIVE_HERO, IMMERSIVE -> IMMERSIVE_HERO
        else -> DEFAULT
    }

    fun next(raw: String): String = when (normalize(raw)) {
        DEFAULT -> STANDARD
        STANDARD -> IMMERSIVE_HERO
        else -> DEFAULT
    }

    fun label(raw: String): String = when (normalize(raw)) {
        STANDARD -> "标准"
        IMMERSIVE_HERO -> "沉浸封面"
        else -> "默认"
    }
}

internal const val DETAIL_DEFAULT2_HERO_FADE_FRACTION = 0.48f

/** Classic Default has no adaptive art wash; Default2 and Immersive share a blurred current-cover backplate. */
internal fun detailUsesArtworkBackdrop(style: String): Boolean =
    style == DetailCoverStyles.DEFAULT2 || style == DetailCoverStyles.IMMERSIVE

/** Predict the visible adaptive-detail surface from the same artwork opacity used by its backplate. */
internal fun detailArtworkBackdropTone(surfaceTone: Color, artworkTone: Color): Color {
    val artworkAlpha = IMMERSIVE_PLAYER_BACKDROP_ALPHA
    val surfaceAlpha = 1f - artworkAlpha
    return Color(
        red = surfaceTone.red * surfaceAlpha + artworkTone.red * artworkAlpha,
        green = surfaceTone.green * surfaceAlpha + artworkTone.green * artworkAlpha,
        blue = surfaceTone.blue * surfaceAlpha + artworkTone.blue * artworkAlpha
    )
}

/** Returns null for classic Default so its original square-cover treatment stays untouched. */
internal fun detailHeroArtworkFadeFraction(style: String): Float? = when (style) {
    DetailCoverStyles.DEFAULT2 -> DETAIL_DEFAULT2_HERO_FADE_FRACTION
    DetailCoverStyles.IMMERSIVE -> IMMERSIVE_HERO_FADE_FRACTION
    else -> null
}

/** Low-opacity, monotone color wash preserves detail-cover colors instead of flattening them to white. */
internal fun detailBackdropWashAlpha(position: Float): Float =
    gradientValueAt(position, 0f, 1f, 0.025f, 0.06f)

/** Same contrast threshold used by Immersive/Default2 detail text. */
internal fun detailAdaptiveTextForeground(surfaceTone: Color): Color =
    if (surfaceTone.luminance() > 0.179f) Color.Black else Color.White

/** True means Android renders dark status-bar glyphs; null preserves the root theme policy. */
internal fun detailStatusBarLightAppearance(style: String, textForeground: Color): Boolean? =
    if (style == DetailCoverStyles.IMMERSIVE) {
        textForeground == Color.Black
    } else null
