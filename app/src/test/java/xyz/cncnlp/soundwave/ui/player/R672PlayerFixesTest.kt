package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r672：播放器页六项修复的纯 JVM / 源码契约（真机观感另需设备验收）。 */
class R672PlayerFixesTest {
    private fun file(rel: String): File = listOf("src/main/java/xyz/cncnlp/soundwave/$rel", "app/src/main/java/xyz/cncnlp/soundwave/$rel")
        .map(::File).firstOrNull { it.exists() } ?: File("app/src/main/java/xyz/cncnlp/soundwave/$rel")
    private fun src(rel: String): String = file(rel).readText()

    @Test
    fun defaultMorphKeepsOneOpaqueSharedCoverAtBothEnds() {
        for (i in 0..100) {
            val p = i / 100f
            assertEquals(1f, playerFlyingCoverAlpha(p), 0f)
            assertEquals(0f, playerMiniCoverAlpha(p, morphReady = true), 0f)
        }
        assertEquals(1f, playerMiniCoverAlpha(1f, morphReady = false), 0f)
    }

    @Test
    fun defaultMorphUsesAnInterruptibleCriticallyDampedSpring() {
        val spec = playerCoverSharedSpec(reduce = false)
        assertTrue(spec is SpringSpec<Float>)
        spec as SpringSpec<Float>
        assertEquals(1f, spec.dampingRatio, 0f)
        assertEquals(PLAYER_COVER_SHARED_VISIBILITY_THRESHOLD, spec.visibilityThreshold!!, 0f)
        assertTrue(spec.visibilityThreshold!! <= 0.001f)
        val reduced = playerCoverSharedSpec(reduce = true)
        assertTrue(reduced is TweenSpec<Float>)
        assertEquals(PLAYER_COVER_SHARED_REDUCED_MS, (reduced as TweenSpec<Float>).durationMillis)
    }

    @Test
    fun standardImmersiveCoverIsOnePersistentLayerFadedToTransparent() {
        val route = src("ui/player/DefaultPlayerRoute.kt")
        assertTrue(route.contains("if (coverOnlyImmersive) StandardImmersiveCoverLayer(state, morph, lyricsOpen, Modifier.zIndex(-1f))"))
        assertTrue(route.contains("playerHeroFadeTo("))
        val main = src("ui/player/DefaultPlayerMainPage.kt")
        assertTrue(main.contains("coverOnlyImmersive && morph == null && immersiveSlotBottomPx > 0"))
        val lyrics = src("ui/player/DefaultPlayerLyricsPage.kt")
        assertTrue(lyrics.contains("coverOnlyImmersive && LocalPlayerCoverMorph.current == null"))
        // No opaque page-colored band at the cover bottom (the seam), and not the reverted Offscreen + DstIn pattern.
        for (rel in listOf("ui/player/DefaultPlayerRoute.kt", "ui/player/DefaultPlayerMainPage.kt", "ui/player/DefaultPlayerLyricsHero.kt")) {
            val text = src(rel)
            assertFalse("$rel must not fade the cover into an opaque page color", text.contains("1f to colors.background"))
            assertFalse("$rel must not use CompositingStrategy.Offscreen on the cover", text.contains("CompositingStrategy.Offscreen"))
        }
        assertEquals(0.40f, STANDARD_IMMERSIVE_FADE_FRACTION, 0f)
    }

    @Test
    fun lyricsPageTitleUsesTheMainPageTypography() {
        val lyrics = src("ui/player/DefaultPlayerLyricsPage.kt")
        val main = src("ui/player/DefaultPlayerMainPage.kt")
        assertTrue(main.contains("DefaultPlayerSongTitle(song.name") && lyrics.contains("DefaultPlayerSongTitle(song.name"))
        assertTrue(main.contains("DefaultPlayerArtistLine(") && lyrics.contains("DefaultPlayerArtistLine("))
        val title = main.substring(main.indexOf("internal fun DefaultPlayerSongTitle"))
        assertTrue(title.substring(0, title.indexOf("\n}")).contains("fontFamily = DesignFonts.SerifCJK"))
        assertFalse(lyrics.contains("fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow"))
    }

    @Test
    fun appleUsesTheSharedQueueSheetAndTheOldQueueIsGone() {
        val full = src("FullPlayer.kt")
        assertTrue(full.contains("DefaultPlayerQueuePage(defaultPlayerState, defaultPlayerActions, queueListState, enterScope = this)"))
        assertFalse(full.contains("PlayerQueueRouteContent("))
        assertFalse(full.contains("PlayerQueueRouteHeader("))
        for (gone in listOf("ui/player/FullPlayerQueueRoute.kt", "ui/player/PlayerQueuePanel.kt", "ui/player/PlayerQueueSurface.kt")) {
            assertFalse("$gone must be deleted", file(gone).exists())
        }
        assertTrue(full.contains("keepSubmodeUnderQueue = presentationMode == \"apple\""))
    }

    @Test
    fun appleLyricsHeroAndPageFadeAreWired() {
        val full = src("FullPlayer.kt")
        assertTrue(full.contains("playerShowsImmersiveCoverHero(layout, queuePage = false) || (appleMode && pageLyrics)"))
        assertTrue(full.contains("applePageAlpha.animateTo(0f, tween(PLAYER_PAGE_EXIT_MS")) // r674：常量化（140ms）并扩展到标准样式
        assertTrue(full.contains("val a = applePageAlpha.value") && full.contains("alpha = a")) // r674：淡入淡出 + 位移
    }
}
