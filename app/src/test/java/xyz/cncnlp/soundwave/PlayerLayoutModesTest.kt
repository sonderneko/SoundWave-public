package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.player.playerOutputRouteMonitoringEnabled
import xyz.cncnlp.soundwave.ui.player.playerUnderlayShouldDraw

class PlayerLayoutModesTest {

    @Test
    fun selectorOffersNewDefaultFirstThenAppleLyricsAndImmersiveCover() {
        // 1.0.655：新增全新设计的「默认（default）」样式并作为新安装默认值；历史 standard / 未知值迁移到「默认」。
        assertEquals(listOf("default", "default_immersive_cover", "apple"), PLAYER_LAYOUT_CHOICES.map { it.first })
        assertEquals("default", PLAYER_LAYOUT_DEFAULT)
        assertEquals("默认", playerLayoutLabel("default"))
        assertEquals("默认", playerLayoutLabel("standard"))
        assertEquals("Apple Music", playerLayoutLabel("apple"))
        assertEquals("歌词样式", playerLayoutLabel("lyrics"))
        assertEquals("沉浸封面", playerLayoutLabel("immersive_cover"))
        assertEquals("default", normalizePlayerLayout("unknown-layout"))
        assertEquals("default", normalizePlayerLayout("standard"))
        assertEquals("apple", normalizePlayerLayout("apple"))
        assertEquals(PlayerVisualMode.DEFAULT, playerLayoutSpec("standard").visualMode)
        assertEquals(PlayerVisualMode.APPLE, playerLayoutSpec("apple").visualMode)
        assertTrue(playerLayoutIsDefault("default"))
        assertFalse(playerLayoutIsDefault("apple"))
        // 「默认」不是沉浸预设：歌词页由 showLyrics 驱动，且不使用沉浸封面背板。
        assertFalse(playerUsesImmersiveCoverBackdrop("default"))
        assertTrue(playerShowsLyricsPage("default", immersiveLyricsMode = false, showLyrics = true, dismissed = false))
        assertFalse(playerShowsLyricsPage("default", immersiveLyricsMode = false, showLyrics = false, dismissed = false))
    }

    @Test
    fun coverOnlyImmersionPersistsWithoutReplacingStandardRoutesOrControls() {
        val key = PLAYER_LAYOUT_COVER_ONLY
        assertEquals("default_immersive_cover", key)
        assertEquals(key, normalizePlayerLayout(key))
        // accept6-uipolish2（用户 2026-11-04 规格变更）：标签简化为「标准」，断言随规格等严更新。
        assertEquals("标准", playerLayoutLabel(key))
        assertTrue(PLAYER_LAYOUT_CHOICES.any { it.first == key })
        assertTrue(playerLayoutIsDefault(key))
        assertTrue(playerUsesCoverOnlyImmersion(key))
        assertFalse(playerUsesCoverOnlyImmersion("default"))
        assertFalse(playerUsesCoverOnlyImmersion("immersive_cover"))
        val standard = playerLayoutSpec("default")
        val coverOnly = playerLayoutSpec(key)
        assertEquals(standard, coverOnly.copy(key = standard.key, artworkPresentation = standard.artworkPresentation))
        assertEquals(PlayerArtworkPresentation.TOP_HERO, coverOnly.artworkPresentation)
        assertFalse(playerUsesImmersiveCoverBackdrop(key))
        assertFalse(playerLayoutImmersive(key))
        assertTrue(playerShowsLyricsPage(key, immersiveLyricsMode = false, showLyrics = true, dismissed = false))
        assertTrue(playerShowsDeviceAndQueueActions(key, lyricsPage = false, queuePage = false))
    }

    @Test
    fun immersiveCoverIsAnUpperPageHeroAndNotAFullBleedOrFloatingSquareCard() {
        assertEquals("immersive_cover", normalizePlayerLayout("photo_lyric"))
        assertEquals("沉浸封面", playerLayoutLabel("photo_lyric"))
        val cover = playerLayoutSpec("immersive_cover")
        assertEquals(PlayerVisualMode.IMMERSIVE_COVER, cover.visualMode)
        assertEquals(PlayerArtworkPresentation.TOP_HERO, cover.artworkPresentation)
        assertEquals(1f, cover.coverFraction, 0.001f)
        assertFalse(cover.showLyrics)
        assertFalse(cover.immersive)
        assertEquals(PlayerArtworkPresentation.SQUARE_CARD, playerLayoutSpec("apple").artworkPresentation)
        assertEquals(PlayerArtworkPresentation.SQUARE_CARD, playerLayoutSpec("standard").artworkPresentation)
        assertEquals(0.78f, playerLayoutSpec("apple").coverFraction, 0.001f)
        assertEquals(false, playerLayoutUsesFullBleedCoverPage("immersive_cover", lyricPage = false, queuePage = false))
        assertEquals(false, playerLayoutUsesFullBleedCoverPage("immersive_cover", lyricPage = true, queuePage = false))
        assertEquals(false, playerLayoutUsesFullBleedCoverPage("immersive_cover", lyricPage = false, queuePage = true))
        assertEquals(false, playerLayoutUsesFullBleedCoverPage("apple", lyricPage = false, queuePage = false))
        assertEquals(448.16f, immersiveCoverHeroHeightDp(393), 0.01f)
        assertEquals(388f, immersiveCoverHeroHeightDp(320), 0.01f)
        assertEquals(0.34f, IMMERSIVE_HERO_FADE_FRACTION, 0.001f)
    }

    @Test
    fun immersivePlayerBackdropUsesSameCoverAcrossFullScreenWithProgressiveTintAndContrastScrim() {
        assertEquals(40, IMMERSIVE_PLAYER_BACKDROP_BLUR_DP)
        assertEquals(0.90f, IMMERSIVE_PLAYER_BACKDROP_ALPHA, 0.001f)
        assertEquals(1.20f, IMMERSIVE_PLAYER_BACKDROP_SCALE, 0.001f)
        val positions = listOf(0f, 0.22f, 0.48f, 0.74f, 1f)
        val tint = positions.map(::immersivePlayerBackdropTintAlpha)
        val scrim = positions.map { position -> immersivePlayerBackdropScrimAlpha(position) }
        listOf(0.04f, 0.045f, 0.05f, 0.06f, 0.065f).zip(tint).forEach { (expected, actual) ->
            assertEquals(expected, actual, 0.001f)
        }
        listOf(0.035f, 0.04f, 0.05f, 0.06f, 0.065f).zip(scrim).forEach { (expected, actual) ->
            assertEquals(expected, actual, 0.001f)
        }
        assertTrue(tint.zipWithNext().all { (before, after) -> after >= before })
        assertTrue(scrim.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(0.04f, immersivePlayerBackdropTintAlpha(-1f), 0.001f)
        assertEquals(0.065f, immersivePlayerBackdropScrimAlpha(2f), 0.001f)
        assertEquals(0f, immersivePlayerArtworkScrimBoost(0.10f), 0.001f)
        assertTrue(immersivePlayerArtworkScrimBoost(0.92f) > immersivePlayerArtworkScrimBoost(0.10f))
        assertEquals(0.135f, immersivePlayerBackdropScrimAlpha(1f, 1f), 0.001f)
        assertTrue(immersivePlayerBackdropScrimAlpha(1f, 0.10f) < immersivePlayerBackdropScrimAlpha(1f, 0.92f))
    }

    @Test
    fun playerTransitionBackdropMovesInsideOnlyTheImmersiveCoverSheet() {
        assertTrue(playerTransitionBackdropMovesWithSheet("immersive_cover"))
        assertFalse(playerTransitionBackdropMovesWithSheet("apple"))
        assertFalse(playerTransitionBackdropMovesWithSheet("lyrics"))
        assertEquals(1f, ThemeTokens.PlayerBackground.alpha, 0.001f)
        assertFalse(playerOverlayVisible(currentState = false, targetState = false))
        assertTrue(playerOverlayVisible(currentState = false, targetState = true))
        assertTrue(playerOverlayVisible(currentState = true, targetState = true))
        assertTrue(playerOverlayVisible(currentState = true, targetState = false))
    }

    @Test
    fun immersiveCoverKeepsItsSourceRouteThroughEntryGestureAndExit() {
        assertTrue(playerDetailUnderlayShouldDraw("immersive_cover", currentState = false, targetState = false))
        assertTrue(playerDetailUnderlayShouldDraw("immersive_cover", currentState = false, targetState = true))
        assertFalse(playerDetailUnderlayShouldDraw("immersive_cover", currentState = true, targetState = true))
        assertTrue(playerDetailUnderlayShouldDraw("immersive_cover", currentState = true, targetState = true, dismissOffsetPx = 1f))
        assertTrue(playerDetailUnderlayShouldDraw("immersive_cover", currentState = true, targetState = false))
        assertTrue(playerDetailUnderlayShouldDraw("apple", currentState = true, targetState = true))
        assertTrue(playerDetailUnderlayShouldDraw("lyrics", currentState = true, targetState = true))
        assertTrue(playerImmersiveTransitionSurfaceVisible("immersive_cover", currentState = false, targetState = true))
        assertTrue(playerImmersiveTransitionSurfaceVisible("immersive_cover", currentState = true, targetState = false))
        assertFalse(playerImmersiveTransitionSurfaceVisible("immersive_cover", currentState = false, targetState = false))
        assertFalse(playerImmersiveTransitionSurfaceVisible("apple", currentState = false, targetState = true))
    }

    @Test
    fun playerSheetUsesCoordinatedVerticalSlideDurationsAndStableDockMeasurement() {
        assertEquals(360, PLAYER_SHEET_ENTER_DURATION_MS)
        // SW-12-06（G 波规格变更）：退场时长不得大于进场——420 → 360，断言由「进场 < 退场」翻转为「退场 ≤ 进场」。
        assertEquals(360, PLAYER_SHEET_EXIT_DURATION_MS)
        assertEquals(200L, PLAYER_SHEET_HOST_EXIT_TAIL_MS)
        assertTrue(PLAYER_SHEET_EXIT_DURATION_MS <= PLAYER_SHEET_ENTER_DURATION_MS)
        assertEquals(150, PLAYER_DOCK_HOST_HEIGHT_DP)
    }

    @Test
    fun allLayoutsDrawTheSourceDuringRouteTransitionsAndOnlyDuringInteractiveDismissAtRest() {
        assertTrue(playerUnderlayShouldDraw("apple", currentState = false, targetState = false))
        assertTrue(playerUnderlayShouldDraw("apple", currentState = false, targetState = true))
        assertTrue(playerUnderlayShouldDraw("apple", currentState = true, targetState = false))
        assertFalse(playerUnderlayShouldDraw("apple", currentState = true, targetState = true))
        assertTrue(playerUnderlayShouldDraw("apple", currentState = true, targetState = true, dismissOffsetPx = 1f))
        assertTrue(playerUnderlayShouldDraw("immersive_cover", currentState = false, targetState = false))
        assertTrue(playerUnderlayShouldDraw("immersive_cover", currentState = false, targetState = true))
        assertTrue(playerUnderlayShouldDraw("immersive_cover", currentState = true, targetState = false))
        assertFalse(playerUnderlayShouldDraw("immersive_cover", currentState = true, targetState = true))
        assertTrue(playerUnderlayShouldDraw("immersive_cover", currentState = true, targetState = true, dismissOffsetPx = 1f))
    }

    @Test
    fun dragExitConsumesOnlyTheRemainingDistanceAndClampsToTheHostHeight() {
        assertEquals(1000, playerDismissExitOffsetPx(1000, 0f))
        assertEquals(750, playerDismissExitOffsetPx(1000, 250f))
        assertEquals(0, playerDismissExitOffsetPx(1000, 1500f))
        assertEquals(0, playerDismissExitOffsetPx(-100, 10f))
        assertTrue(playerDismissGestureRevealsSource(true, true, 1f))
        assertFalse(playerDismissGestureRevealsSource(false, true, 1f))
        assertFalse(playerDismissGestureRevealsSource(true, true, 0f))
    }

    @Test
    fun dismissPanelAndSourceRevealShareTheSameMeasuredSurfaceOffset() {
        listOf(
            Triple(-24f, 800f, 0f),
            Triple(0f, 800f, 0f),
            Triple(275f, 800f, 275f),
            Triple(800f, 800f, 800f),
            Triple(920f, 800f, 800f),
            Triple(12f, 0f, 0f)
        ).forEach { (dragOffsetPx, measuredHeightPx, expectedSurfaceOffsetPx) ->
            assertEquals(
                expectedSurfaceOffsetPx,
                playerDismissSurfaceOffsetPx(dragOffsetPx, measuredHeightPx),
                0.001f
            )
        }
        assertEquals(0, playerDismissExitOffsetPx(800, 920f))
    }

    @Test
    fun statusBarAppearanceFollowsTheSourceOnlyAfterItsInsetIsExposed() {
        assertFalse(playerDismissStatusBarShouldExpose(0f, 24f))
        assertFalse(playerDismissStatusBarShouldExpose(23f, 24f))
        assertTrue(playerDismissStatusBarShouldExpose(24f, 24f))
        assertTrue(playerDismissStatusBarShouldExpose(48f, 24f))
        assertFalse(playerDismissStatusBarShouldExpose(48f, 0f))
    }

    @Test
    fun outputDeviceRouteMonitoringStartsOnlyWhenTheSheetIsVisible() {
        assertFalse(playerOutputRouteMonitoringEnabled(outputDeviceSheetVisible = false))
        assertTrue(playerOutputRouteMonitoringEnabled(outputDeviceSheetVisible = true))
    }

    @Test
    fun immersiveHeroFadeScalesWithCanvasAndReachesTransparencyWithoutAHardCutoff() {
        val fadeFraction = IMMERSIVE_HERO_FADE_FRACTION
        val heightsPx = listOf(180f, 448f, 1080f)
        val normalizedPositions = listOf(0f, 0.24f, 1f - fadeFraction, 1f - fadeFraction / 2f, 0.86f, 1f)
        heightsPx.forEach { height ->
            val alphas = normalizedPositions.map { position ->
                immersivePlayerHeroArtworkAlpha(position * height, height)
            }
            assertTrue(alphas.zipWithNext().all { (before, after) -> after <= before })
            assertEquals(1f, alphas[normalizedPositions.indexOf(1f - fadeFraction)], 0.001f)
            assertEquals(0.5f, immersivePlayerHeroArtworkAlpha((1f - fadeFraction / 2f) * height, height), 0.001f)
            assertEquals(0f, alphas.last(), 0.001f)
        }
        val shortFade = immersivePlayerHeroArtworkAlpha(0.8f * 180f, 180f, fadeFraction = 0.36f)
        val longFade = immersivePlayerHeroArtworkAlpha(0.8f * 180f, 180f, fadeFraction = 0.68f)
        assertTrue(kotlin.math.abs(shortFade - longFade) > 0.1f)
    }

    @Test
    fun immersiveBackdropScrimTracksArtworkBrightnessWithoutFlatteningEveryCoverToOneMask() {
        val positions = listOf(0f, 0.22f, 0.48f, 0.74f, 1f)
        val luminances = listOf(0.08f, 0.42f, 0.92f)
        val profiles = luminances.map { luminance ->
            positions.map { position -> immersivePlayerBackdropScrimAlpha(position, luminance) }
        }
        profiles.forEach { profile ->
            assertTrue(profile.all { it in 0f..0.16f })
            assertTrue(profile.zipWithNext().all { (before, after) -> after >= before })
        }
        assertTrue(profiles[0].zip(profiles[1]).all { (dark, middle) -> dark <= middle })
        assertTrue(profiles[1].zip(profiles[2]).all { (middle, bright) -> middle <= bright })
        assertTrue(profiles[2].last() - profiles[0].last() > 0.05f)
    }

    @Test
    fun immersiveCoverUsesCurrentTrackBackdropAndKeepsHeroVisibleOnLyricsButNotQueue() {
        assertTrue(playerUsesImmersiveCoverBackdrop("immersive_cover"))
        assertTrue(playerTransitionBackdropMovesWithSheet("immersive_cover"))
        assertFalse(playerTransitionBackdropMovesWithSheet("apple"))
        assertFalse(playerTransitionBackdropMovesWithSheet("lyrics"))
        assertFalse(playerUsesImmersiveCoverBackdrop("apple"))
        assertEquals("current-cover.jpg", playerImmersiveBackdropArtwork("immersive_cover", "current-cover.jpg"))
        assertNull(playerImmersiveBackdropArtwork("lyrics", "current-cover.jpg"))
        assertNull(playerImmersiveBackdropArtwork("immersive_cover", ""))
        assertTrue(playerShowsImmersiveCoverHero("immersive_cover", queuePage = false))
        assertFalse(playerShowsImmersiveCoverHero("immersive_cover", queuePage = true))
        assertFalse(playerShowsImmersiveCoverHero("apple", queuePage = false))
    }

    @Test
    fun immersiveCoverDeviceAndQueueActionsFollowRouteMatrix() {
        for (lyricsPage in listOf(false, true)) {
            for (queuePage in listOf(false, true)) {
                val expected = queuePage || !lyricsPage
                assertEquals(
                    "lyricsPage=$lyricsPage queuePage=$queuePage",
                    expected,
                    playerShowsDeviceAndQueueActions(
                        "immersive_cover",
                        lyricsPage = lyricsPage,
                        queuePage = queuePage
                    )
                )
            }
        }
        listOf("lyrics", "apple").forEach { layout ->
            assertTrue(playerShowsDeviceAndQueueActions(layout, lyricsPage = false, queuePage = false))
            assertTrue(playerShowsDeviceAndQueueActions(layout, lyricsPage = true, queuePage = false))
        }
    }

    @Test
    fun onlyDedicatedImmersiveLyricsPageHidesDeviceAndQueueActions() {
        assertFalse(playerShowsDeviceAndQueueActions("immersive_cover", lyricsPage = true, queuePage = false))
        assertTrue(playerShowsDeviceAndQueueActions("immersive_cover", lyricsPage = false, queuePage = false))
        assertTrue(playerShowsDeviceAndQueueActions("immersive_cover", lyricsPage = true, queuePage = true))
        assertTrue(playerShowsDeviceAndQueueActions("immersive_cover", lyricsPage = false, queuePage = true))
    }

    @Test
    fun fullLyricsPagesUseTheWholeScrollableTimelineWhilePreviewsKeepTheFiveSentenceWindow() {
        assertEquals(0..11, playerLyricsViewportIndices(12, 6, fullLyricsPage = true))
        assertEquals(4..8, playerLyricsViewportIndices(12, 6, fullLyricsPage = false))
        assertEquals(0..2, playerLyricsViewportIndices(3, 2, fullLyricsPage = false))
        assertEquals(0 until 0, playerLyricsViewportIndices(0, 0, fullLyricsPage = true))
        assertEquals(PLAYER_FULLSCREEN_LYRICS_WINDOW_SIZE, playerLyricsViewportIndices(12, 6, fullLyricsPage = false).count())
    }

    @Test
    fun lyricsEntryChromeDoesNotFlashOnlyForPlayingImmersiveCover() {
        assertFalse(playerChromeVisibleAfterLyricsEntry("immersive_cover", playing = true))
        assertTrue(playerChromeVisibleAfterLyricsEntry("immersive_cover", playing = false))
        assertTrue(playerChromeVisibleAfterLyricsEntry("apple", playing = true))
        assertTrue(playerChromeVisibleAfterLyricsEntry("lyrics", playing = true))
    }

    @Test
    fun fullLyricsPagesRemovePerSentenceLineCapsAndPreserveImmersiveCoverControlGap() {
        assertEquals(8, playerLyricsTextMaxLines(fullLyricsPage = false, standardMaxLines = 8))
        assertEquals(4, playerLyricsTextMaxLines(fullLyricsPage = false, standardMaxLines = 4))
        assertEquals(Int.MAX_VALUE, playerLyricsTextMaxLines(fullLyricsPage = true, standardMaxLines = 8))
        assertEquals(8f, IMMERSIVE_LYRICS_CONTROLS_GAP_DP, 0.001f)
    }

    @Test
    fun immersiveCoverCentersTheMeasuredActiveLineAndUsesMeasuredTailSpace() {
        assertEquals(85, immersiveLyricsActiveCenterOffsetPx(240, 70))
        assertEquals(-85, immersiveLyricsActiveScrollOffsetPx(240, 70, currentIndex = 3))
        assertEquals(-85, playerLyricsCenteredScrollOffsetPx(240, 70))
        assertEquals(0, playerLyricsCenteredScrollOffsetPx(64, 70))
        assertEquals(0, playerLyricsCenteredScrollOffsetPx(-1, 70))
        assertEquals(0, immersiveLyricsActiveScrollOffsetPx(240, 70, currentIndex = 0))
        assertEquals(85, immersiveLyricsTopContentPaddingPx(240, 70, currentIndex = 0))
        assertEquals(0, immersiveLyricsTopContentPaddingPx(240, 70, currentIndex = 3))
        assertEquals(85, immersiveLyricsEndContentPaddingPx(240, 70))
        assertEquals(0, immersiveLyricsEndContentPaddingPx(64, 70))
        assertEquals(0, immersiveLyricsEndContentPaddingPx(0, 24))
    }

    @Test
    fun wordKaraokeAndWordLiftAreIndependentAndLineTimedLyricsStillHighlight() {
        assertTrue(playerShouldRenderWordKaraoke(true, true, false)) // 原行级时间进度回退
        assertTrue(playerShouldRenderWordKaraoke(true, true, true))
        assertFalse(playerShouldRenderWordKaraoke(false, true, true))
        assertFalse(playerShouldRenderWordKaraoke(true, false, true))
        assertTrue(playerShouldLiftCurrentWords(enabled = true, hasWordTimeline = true, systemReduceMotion = false))
        assertFalse(playerShouldLiftCurrentWords(enabled = false, hasWordTimeline = true, systemReduceMotion = false))
        assertFalse(playerShouldLiftCurrentWords(enabled = true, hasWordTimeline = false, systemReduceMotion = false))
        assertFalse(playerShouldLiftCurrentWords(enabled = true, hasWordTimeline = true, systemReduceMotion = true))
    }

    @Test
    fun lyricSeekDoesNotRevealAutoHiddenChromeButOrdinaryPauseStillDoes() {
        assertFalse(playerChromeShouldRevealOnPause(lyricSeekSuppressionActive = true))
        assertTrue(playerChromeShouldRevealOnPause(lyricSeekSuppressionActive = false))
    }

    @Test
    fun cachedTimedLyricsUseBinarySearchWithoutInventingAnIndexForUntimedRows() {
        val timed = listOf(2 to 100L, 5 to 400L, 8 to 900L)
        assertEquals(-1, playerActiveTimedLyricIndex(emptyList(), 500L))
        assertEquals(-1, playerActiveTimedLyricIndex(timed, 99L))
        assertEquals(2, playerActiveTimedLyricIndex(timed, 100L))
        assertEquals(5, playerActiveTimedLyricIndex(timed, 899L))
        assertEquals(8, playerActiveTimedLyricIndex(timed, 900L))
        assertEquals(8, playerActiveTimedLyricIndex(timed, Long.MAX_VALUE))
    }

    @Test
    fun wordLiftIsBoundedAndTrackChangeResetsOnlyItsAutoFollow() {
        assertEquals(0f, karaokeWordLiftDp(0f), 0.001f)
        assertEquals(1.25f, karaokeWordLiftDp(0.5f), 0.001f)
        assertEquals(KARAOKE_WORD_LIFT_MAX_DP, karaokeWordLiftDp(1f), 0.001f)
        assertEquals(180, KARAOKE_WORD_ANIMATION_MS)
        assertEquals("track-a", playerImmersiveLyricsScrollTrackKey(true, "track-a"))
        assertEquals("track-b", playerImmersiveLyricsScrollTrackKey(true, "track-b"))
        assertEquals("", playerImmersiveLyricsScrollTrackKey(false, "track-a"))
    }

    @Test
    fun immersiveLyricsVisibleWindowUsesMeasuredHeightAndKeepsTheActiveLineAmongNeighbors() {
        assertEquals(2, immersiveLyricsVisibleRowCount(137.84f, 60f))
        assertEquals(3, immersiveLyricsVisibleRowCount(240.02f, 70f))
        assertEquals(5, immersiveLyricsVisibleRowCount(300f, 56f))
        assertEquals(3, immersiveLyricsVisibleRowCount(200f, 0f))
        assertEquals(4, immersiveLyricsVisibleRowCount(256f, 64f))
        assertEquals(0 until 0, immersiveLyricsVisibleWindowIndices(0, -1, 240f, 70f))
        assertEquals(0..1, immersiveLyricsVisibleWindowIndices(2, 1, 240f, 70f))
        assertEquals(0..2, immersiveLyricsVisibleWindowIndices(12, 0, 240f, 70f))
        assertEquals(4..6, immersiveLyricsVisibleWindowIndices(12, 5, 240f, 70f))
        assertEquals(7..11, immersiveLyricsVisibleWindowIndices(12, 11, 300f, 56f))
    }

    @Test
    fun standardLyricsAnchorUsesMeasuredViewportAndKeepsFirstAndLastLinesReachable() {
        assertEquals(85, playerLyricsActiveLineTopOffsetPx(240, 70))
        assertEquals(-85, playerLyricsActiveScrollOffsetPx(240, 70, currentIndex = 3))
        assertEquals(0, playerLyricsActiveScrollOffsetPx(240, 70, currentIndex = 0))
        assertEquals(85, playerLyricsFirstLineTopPaddingPx(240, 70, currentIndex = 0))
        assertEquals(0, playerLyricsFirstLineTopPaddingPx(240, 70, currentIndex = 1))
        assertEquals(85, playerLyricsLastLineBottomPaddingPx(240, 70, currentIndex = 9, lyricCount = 10))
        assertEquals(0, playerLyricsLastLineBottomPaddingPx(240, 70, currentIndex = 8, lyricCount = 10))
        assertEquals(20, playerLyricsActiveLineTopOffsetPx(240, 200))
        assertEquals(0, playerLyricsActiveLineTopOffsetPx(0, 0))
    }

    @Test
    fun immersiveLyricsMetadataStartsAtCoverBoundaryAndShortScreensKeepLyricsViewport() {
        val heroHeight = immersiveCoverHeroHeightDp(393)
        assertEquals(448.16f, heroHeight, 0.01f)
        assertEquals(immersiveCoverHeroHeightDp(393), heroHeight, 0.001f)
        val statusBarInset = 24f
        val normalSpacer = immersiveLyricsHeaderSpacerDp(heroHeight, statusBarInset)
        assertEquals(
            heroHeight - IMMERSIVE_LYRICS_METADATA_ABOVE_HERO_DP,
            statusBarInset + IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP + normalSpacer + IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP / 2f,
            0.01f
        )
        assertEquals(
            IMMERSIVE_LYRICS_BOUNDARY_GAP_DP,
            IMMERSIVE_LYRICS_HEADER_TO_LYRICS_GAP_DP + IMMERSIVE_LYRICS_CONTENT_TOP_PADDING_DP,
            0.01f
        )

        val constrainedSpacer = immersiveLyricsHeaderSpacerDp(
            heroHeightDp = heroHeight,
            statusBarInsetDp = statusBarInset,
            contentHeightDp = 760f,
            controlsHeightDp = 150f
        )
        assertEquals(261f, constrainedSpacer, 0.01f)
        val remainingLyricsViewport = 760f - constrainedSpacer - IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP -
            IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP - IMMERSIVE_LYRICS_BOUNDARY_GAP_DP -
            IMMERSIVE_LYRICS_CONTROLS_GAP_DP - 150f
        assertEquals(256f, remainingLyricsViewport, 0.01f)

        val shortSpacer = immersiveLyricsHeaderSpacerDp(
            heroHeightDp = heroHeight,
            statusBarInsetDp = statusBarInset,
            contentHeightDp = 600f,
            controlsHeightDp = 150f
        )
        assertEquals(101f, shortSpacer, 0.01f)
        assertEquals(
            IMMERSIVE_LYRICS_MIN_VIEWPORT_DP,
            600f - shortSpacer - IMMERSIVE_LYRICS_HANDLE_ROW_HEIGHT_DP - IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP -
                IMMERSIVE_LYRICS_BOUNDARY_GAP_DP - IMMERSIVE_LYRICS_CONTROLS_GAP_DP - 150f,
            0.01f
        )
    }

    @Test
    fun lyricsStyleIsSeparateAndShowsAtMostFourTwoLineSentences() {
        assertEquals(PlayerVisualMode.APPLE, playerLayoutSpec("apple").visualMode)
        assertEquals(PlayerVisualMode.LYRICS_STYLE, playerLayoutSpec("lyrics").visualMode)
        assertEquals(PlayerArtworkPresentation.SQUARE_CARD, playerLayoutSpec("lyrics").artworkPresentation)
        assertEquals(4, PLAYER_LYRICS_PREVIEW_LINES)
        assertEquals(2, PLAYER_LYRICS_PREVIEW_TEXT_MAX_LINES)
        assertEquals(20, playerLyricsPreviewFontSp(isCurrent = true))
        assertEquals(14, playerLyricsPreviewFontSp(isCurrent = false))
        assertEquals(2, PLAYER_LYRICS_PREVIEW_LINE_GAP_DP)
        assertEquals(5, PLAYER_LYRICS_STYLE_COVER_TO_LYRICS_GAP_DP)
        assertEquals(5, PLAYER_LYRICS_STYLE_LYRICS_TO_METADATA_GAP_DP)
        assertEquals(listOf(2, 3, 4, 5), playerLyricsPreviewIndices(8, 4))
        assertEquals(4, playerLyricsPreviewIndices(20, 10).size)
        assertEquals(2, PLAYER_LYRICS_PREVIEW_TEXT_MAX_LINES) // one sentence wraps within its own keyed item
        assertEquals(0, playerLyricsPreviewStartIndex(0, 0))
        assertEquals(0, playerLyricsPreviewStartIndex(3, 1))
        assertEquals(2, playerLyricsPreviewStartIndex(8, 4))
        assertEquals(4, playerLyricsPreviewStartIndex(8, 7))
        assertEquals(5, PLAYER_FULLSCREEN_LYRICS_WINDOW_SIZE)
        assertEquals(3, playerLyricsViewportStartIndex(10, 5))
        assertEquals(0, playerLyricsViewportStartIndex(10, 0))
        assertEquals(5, playerLyricsViewportStartIndex(10, 9))
        assertEquals(0, playerLyricsViewportStartIndex(3, 2))
        assertEquals(0, playerLyricsViewportStartIndex(10, -1))
        assertEquals(150, playerLyricsPreviewMaxHeightDp(800))
        assertEquals(88, playerLyricsPreviewMaxHeightDp(720))
        assertEquals(88, playerLyricsPreviewMaxHeightDp(600))
    }

    @Test
    fun immersiveLyricsHaveTheirOwnLeftAlignedPageAndNormalLyricsRemainCentered() {
        assertTrue(playerShowsLyricsPage("immersive_cover", immersiveLyricsMode = true, showLyrics = false, dismissed = false))
        assertFalse(playerShowsLyricsPage("immersive_cover", immersiveLyricsMode = false, showLyrics = true, dismissed = false))
        assertTrue(playerShowsLyricsPage("lyrics", immersiveLyricsMode = true, showLyrics = false, dismissed = false))
        assertTrue(playerShowsLyricsPage("apple", immersiveLyricsMode = false, showLyrics = true, dismissed = false))
        assertFalse(playerShowsLyricsPage("immersive_cover", immersiveLyricsMode = true, showLyrics = false, dismissed = true))
        assertEquals(androidx.compose.ui.Alignment.Start, playerFullscreenLyricsHorizontalAlignment(true))
        assertEquals(androidx.compose.ui.text.style.TextAlign.Start, playerFullscreenLyricsTextAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.CenterHorizontally, playerFullscreenLyricsHorizontalAlignment(false))
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, playerFullscreenLyricsTextAlignment(false))
        assertEquals(4, PLAYER_LYRICS_PREVIEW_LINES)
        assertEquals(5, PLAYER_LYRICS_STYLE_COVER_TO_LYRICS_GAP_DP)
        assertEquals(5, PLAYER_LYRICS_STYLE_LYRICS_TO_METADATA_GAP_DP)
        assertEquals(PlayerVisualMode.LYRICS_STYLE, playerLayoutSpec("lyrics").visualMode)
    }

    @Test
    fun immersiveCoverLyricsUsesDedicatedWeightedViewportAndDetailMiniEntryKeepsTheSamePlayerRoute() {
        assertTrue(playerUsesDedicatedImmersiveCoverLyricsViewport("immersive_cover", lyricsPage = true, queuePage = false))
        assertFalse(playerUsesDedicatedImmersiveCoverLyricsViewport("immersive_cover", lyricsPage = false, queuePage = false))
        assertFalse(playerUsesDedicatedImmersiveCoverLyricsViewport("immersive_cover", lyricsPage = true, queuePage = true))
        assertFalse(playerUsesDedicatedImmersiveCoverLyricsViewport("apple", lyricsPage = true, queuePage = false))
        assertFalse(playerUsesDedicatedImmersiveCoverLyricsViewport("lyrics", lyricsPage = true, queuePage = false))
        assertEquals(363.16f, immersiveCoverLyricsHeaderSpacerDp(448.16f, 24f), 0.01f)
        assertEquals(720, immersiveCoverArtworkRequestSizePx(0))
        assertEquals(1280, immersiveCoverArtworkRequestSizePx(1280))
        assertEquals(2048, immersiveCoverArtworkRequestSizePx(4096))

        // The detail mini click enters the same vertical player sheet; no style-specific route or playback restart is introduced.
        assertTrue(playerOverlayVisible(currentState = false, targetState = true))
        assertTrue(playerTransitionBackdropMovesWithSheet("immersive_cover"))
        assertFalse(playerTransitionBackdropMovesWithSheet("apple"))
    }

    @Test
    fun brightInkAndSurfaceAreRestrictedToImmersiveCoverLyricsHeader() {
        assertFalse(playerUsesBrightImmersiveCoverLyricsHeader("immersive_cover", lyricsPage = false, queuePage = false, artworkLuminance = 0.92f))
        assertFalse(playerUsesBrightImmersiveCoverLyricsHeader("immersive_cover", lyricsPage = true, queuePage = true, artworkLuminance = 0.92f))
        assertFalse(playerUsesBrightImmersiveCoverLyricsHeader("apple", lyricsPage = true, queuePage = false, artworkLuminance = 0.92f))
        assertFalse(playerUsesBrightImmersiveCoverLyricsHeader("lyrics", lyricsPage = true, queuePage = false, artworkLuminance = 0.92f))
        assertTrue(playerUsesBrightImmersiveCoverLyricsHeader("immersive_cover", lyricsPage = true, queuePage = false, artworkLuminance = 0.92f))
        assertFalse(immersiveCoverLyricsHeaderUsesLightSurface(0.18f))
        assertFalse(immersiveCoverLyricsHeaderUsesLightSurface(0.61f))
        assertEquals(Color.White, immersiveCoverLyricsHeaderPrimaryColor(0.18f))
        assertEquals(Color.White.copy(alpha = 0.72f), immersiveCoverLyricsHeaderSecondaryColor(0.18f))

        val brightSurface = immersiveCoverLyricsHeaderSurfaceColor(0.92f)
        val primary = immersiveCoverLyricsHeaderPrimaryColor(0.92f)
        val secondary = immersiveCoverLyricsHeaderSecondaryColor(0.92f)
        listOf(Color.Black, Color(0xFF858A91.toInt()), Color.White).forEach { artwork ->
            val visibleSurface = composite(brightSurface, artwork)
            assertTrue(contrastRatio(primary, visibleSurface) >= 4.5f)
            assertTrue(contrastRatio(secondary, visibleSurface) >= 4.5f)
        }
        assertEquals(Color.Transparent, immersiveCoverLyricsHeaderSurfaceColor(0.18f))
    }

    @Test
    fun immersiveDetailTextAndStatusBarFollowBackdropWhileOtherStylesKeepRootPolicy() {
        val darkText = detailAdaptiveTextForeground(Color.Black)
        val lightText = detailAdaptiveTextForeground(Color.White)
        assertEquals(Color.White, darkText)
        assertEquals(Color.Black, lightText)
        assertEquals(false, detailStatusBarLightAppearance(DetailCoverStyles.IMMERSIVE, darkText))
        assertEquals(true, detailStatusBarLightAppearance(DetailCoverStyles.IMMERSIVE, lightText))
        assertNull(detailStatusBarLightAppearance(DetailCoverStyles.DEFAULT2, Color.Black))
        assertNull(detailStatusBarLightAppearance(DetailCoverStyles.DEFAULT, Color.White))
    }

    @Test
    fun default2AndImmersiveDetailUseOneArtworkBackplateAndSoftCoverTransitionWithoutChangingClassicDefault() {
        assertTrue(detailUsesArtworkBackdrop(DetailCoverStyles.DEFAULT2))
        assertTrue(detailUsesArtworkBackdrop(DetailCoverStyles.IMMERSIVE))
        assertFalse(detailUsesArtworkBackdrop(DetailCoverStyles.DEFAULT))
        assertEquals(0.48f, detailHeroArtworkFadeFraction(DetailCoverStyles.DEFAULT2)!!, 0.001f)
        assertEquals(IMMERSIVE_HERO_FADE_FRACTION, detailHeroArtworkFadeFraction(DetailCoverStyles.IMMERSIVE)!!, 0.001f)
        assertNull(detailHeroArtworkFadeFraction(DetailCoverStyles.DEFAULT))

        val washPositions = listOf(0f, 0.22f, 0.48f, 0.74f, 1f)
        val wash = washPositions.map(::detailBackdropWashAlpha)
        assertTrue(wash.all { it in 0.025f..0.06f })
        assertTrue(wash.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(0.025f, wash.first(), 0.001f)
        assertEquals(0.06f, wash.last(), 0.001f)

        val darkCoverOverLightTheme = detailArtworkBackdropTone(Color.White, Color.Black)
        val warmBrightCoverOverDarkTheme = detailArtworkBackdropTone(Color.Black, Color(0xFFFFD700.toInt()))
        assertTrue(darkCoverOverLightTheme.luminance() < 0.18f)
        assertEquals(Color.White, detailAdaptiveTextForeground(darkCoverOverLightTheme))
        assertTrue(warmBrightCoverOverDarkTheme.luminance() > 0.50f)
        assertEquals(Color.Black, detailAdaptiveTextForeground(warmBrightCoverOverDarkTheme))

        listOf(DETAIL_DEFAULT2_HERO_FADE_FRACTION, IMMERSIVE_HERO_FADE_FRACTION).forEach { fadeFraction ->
            listOf(180f, 448f, 1080f).forEach { height ->
                assertEquals(1f, immersivePlayerHeroArtworkAlpha((1f - fadeFraction) * height, height, fadeFraction), 0.001f)
                assertEquals(0f, immersivePlayerHeroArtworkAlpha(height, height, fadeFraction), 0.001f)
            }
        }
    }

    @Test
    fun playbackModeTextAndGlyphsMatchEachMode() {
        assertEquals("顺序播放", playModeLabel(PlayMode.SEQUENCE))
        assertEquals("随机播放", playModeLabel(PlayMode.SHUFFLE))
        assertEquals("单曲循环", playModeLabel(PlayMode.LOOP_ONE))
        assertEquals("心动模式", playModeLabel(PlayMode.HEART))
        assertEquals("mode_loop", playModeGlyph(PlayMode.SEQUENCE))
        assertEquals("mode_shuffle", playModeGlyph(PlayMode.SHUFFLE))
        assertEquals("mode_one", playModeGlyph(PlayMode.LOOP_ONE))
        assertEquals("sparkles_filled", playModeGlyph(PlayMode.HEART))
        assertEquals(0, queueNextUpIndex(PlayMode.SHUFFLE, 4, 1))
        assertEquals(1, queueNextUpIndex(PlayMode.LOOP_ONE, 4, 1))
        assertEquals(0, queueNextUpIndex(PlayMode.SEQUENCE, 4, 3))
    }

    @Test
    fun playbackModeCyclesThroughEverySupportedModeAndWraps() {
        assertEquals(PlayMode.LOOP_ONE, nextPlayMode(PlayMode.SEQUENCE))
        assertEquals(PlayMode.SHUFFLE, nextPlayMode(PlayMode.LOOP_ONE))
        assertEquals(PlayMode.HEART, nextPlayMode(PlayMode.SHUFFLE))
        assertEquals(PlayMode.SEQUENCE, nextPlayMode(PlayMode.HEART))
    }

    private fun composite(foreground: Color, background: Color): Color {
        val alpha = foreground.alpha
        return Color(
            red = foreground.red * alpha + background.red * (1f - alpha),
            green = foreground.green * alpha + background.green * (1f - alpha),
            blue = foreground.blue * alpha + background.blue * (1f - alpha)
        )
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
