package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.common.GLASS_FALLBACK_ALPHA
import xyz.cncnlp.soundwave.ui.common.GLASS_TINT_ALPHA
import xyz.cncnlp.soundwave.ui.common.detailHeaderScrollProgress
import xyz.cncnlp.soundwave.ui.common.dockGlassPlan
import xyz.cncnlp.soundwave.ui.common.sheetGlassPlan
import xyz.cncnlp.soundwave.ui.common.sheetHeaderGlassProgress
import xyz.cncnlp.soundwave.ui.motion.DetailPush
import xyz.cncnlp.soundwave.ui.player.defaultPlayerTime

/** r657 修复：360 / 411dp 自适应、磨砂降级与动效纯函数。 */
class R657FixHelpersTest {
    @Test fun dockGlassDefaultsSolidAndFallsBackOnOldApis() {
        assertEquals(false to 1f, dockGlassPlan(dockFrosted = false, sdk = 34))
        assertEquals(false to 1f, dockGlassPlan(dockFrosted = false, sdk = 26))
        assertEquals(true to GLASS_TINT_ALPHA, dockGlassPlan(dockFrosted = true, sdk = 34))
        assertEquals(false to GLASS_FALLBACK_ALPHA, dockGlassPlan(dockFrosted = true, sdk = 30))
        assertTrue(GLASS_FALLBACK_ALPHA >= 0.94f)
    }

    @Test fun sheetGlassPlanBlursOnApi32AndFallsBackNearOpaqueBelow() {
        // ACCEPT6 dialogfrost：表磨砂常开——API ≥ 32 真模糊 + 罩 0.82；更低版本近不透明近似。
        assertEquals(true to ThemeTokens.SheetFrost.GlassTintAlpha, sheetGlassPlan(sdk = 34))
        assertEquals(true to ThemeTokens.SheetFrost.GlassTintAlpha, sheetGlassPlan(sdk = 32))
        assertEquals(false to GLASS_FALLBACK_ALPHA, sheetGlassPlan(sdk = 31))
        assertEquals(false to GLASS_FALLBACK_ALPHA, sheetGlassPlan(sdk = 26))
        assertTrue(ThemeTokens.SheetFrost.GlassTintAlpha < GLASS_FALLBACK_ALPHA)
    }

    @Test fun sheetHeaderGlassProgressRampsAndClamps() {
        // pinned 顶栏带：静止全透（无缝）、24dp 滚动内线性到满、超出钳制。
        assertEquals(0f, sheetHeaderGlassProgress(0, 96f), 0.001f)
        assertEquals(0.5f, sheetHeaderGlassProgress(48, 96f), 0.001f)
        assertEquals(1f, sheetHeaderGlassProgress(96, 96f), 0.001f)
        assertEquals(1f, sheetHeaderGlassProgress(400, 96f), 0.001f)
        assertEquals(0f, sheetHeaderGlassProgress(-10, 96f), 0.001f)
        assertEquals(1f, sheetHeaderGlassProgress(5, 0f), 0.001f)
        assertEquals(0f, sheetHeaderGlassProgress(0, 0f), 0.001f)
    }

    @Test fun favoritesSleeveFitsAt360And411() {
        // 360dp 屏：卡片外距 14×2、内距 18+16 → 内宽 298dp；411dp → 349dp。
        for (inner in listOf(298f, 349f)) {
            val l = sleeveLayout(inner)
            assertTrue("art ≥150 at $inner", l.artW >= 150f)
            assertTrue("title fits ($inner): ${l.titleSp}", l.titleSp * 4.4f <= l.textW)
            if (!l.buttonsBelow) assertTrue("buttons fit inline at $inner", l.textW >= 15 + 14 + 6 + 26 + 15 + 8 + 40)
        }
        assertFalse(sleeveLayout(349f).buttonsBelow)
        // 大字体：按钮行折到底部，而不是被挤扁。
        assertTrue(sleeveLayout(298f, fontScale = 1.3f).buttonsBelow)
    }

    @Test fun favoritesSubtitleShowsHours() {
        assertEquals("123 首 · 8小时12分", favoritesSubtitle(123, (8 * 60 + 12) * 60_000L))
        assertEquals("3 首 · 12分钟", favoritesSubtitle(3, 12 * 60_000L))
        assertEquals("9 首 · 2小时", favoritesSubtitle(9, 120 * 60_000L))
        assertEquals("5 首", favoritesSubtitle(5, 0))
    }

    @Test fun heroActionsCollapseShuffleBeforeSquashingHeart() {
        // 360dp：详情卡内宽 ≈ 360 − 28 − 44 = 288dp；播放全部 ≈ 122（收紧 109）、随机 ≈ 103、♥ 46 → 收紧播放胶囊即可保留「随机播放」文字。
        assertEquals(1, heroActionsMode(288f, 122f, 109f, true, 103f, 46f, true, 46f))
        assertEquals(2, heroActionsMode(250f, 122f, 109f, true, 103f, 46f, true, 46f))
        assertEquals(0, heroActionsMode(339f, 122f, 108f, true, 103f, 46f, true, 46f))
        assertEquals(0, heroActionsMode(288f, 122f, 108f, false, 103f, 46f, true, 46f))
    }

    @Test fun detailHeaderGlassProgress() {
        assertEquals(0f, detailHeaderScrollProgress(0, 0, 240f), 0f)
        assertEquals(0.5f, detailHeaderScrollProgress(0, 120, 240f), 0.001f)
        assertEquals(1f, detailHeaderScrollProgress(0, 999, 240f), 0f)
        assertEquals(1f, detailHeaderScrollProgress(2, 0, 240f), 0f)
    }

    @Test fun detailPushAndTimeFormat() {
        assertEquals(1f, DetailPush.offsetFraction(0f), 0f)
        assertEquals(0f, DetailPush.offsetFraction(1f), 0f)
        assertEquals("1:51", defaultPlayerTime(111f))
        assertEquals("0:05", defaultPlayerTime(5f))
    }

    @Test fun detailUnderlayShiftsAndDims() {
        assertEquals(0f to 0f, DetailPush.underlay(0f))
        val (shift, dim) = DetailPush.underlay(1f)
        assertEquals(-0.25f, shift, 0.0001f)
        assertEquals(0.08f, dim, 0.0001f)
        assertEquals(-0.125f, DetailPush.underlay(0.5f).first, 0.0001f)
        assertEquals(DetailPush.underlay(1f), DetailPush.underlay(3f))
        assertTrue(DetailPush.EXIT_MS < DetailPush.ENTER_MS)
    }

    @Test fun rippleGlowExpandsAndFades() {
        val (r0, a0) = xyz.cncnlp.soundwave.ui.common.RippleGlow.frame(0f)
        val (r1, a1) = xyz.cncnlp.soundwave.ui.common.RippleGlow.frame(1f)
        assertEquals(0.15f, r0, 0.0001f); assertEquals(1f, a0, 0f)
        assertEquals(1f, r1, 0.0001f); assertEquals(0f, a1, 0f)
        assertTrue(xyz.cncnlp.soundwave.ui.common.RippleGlow.frame(0.5f).first > 0.5f)
    }

    @Test fun scanSweepStaysInsideFrame() {
        for (i in 0..20) {
            val y = ScanMotion.sweepY(i / 20f)
            assertTrue(y in 0.22f..0.78f)
        }
        assertEquals(0.22f, ScanMotion.sweepY(0f), 0.0001f)
        assertEquals(0.78f, ScanMotion.sweepY(0.5f), 0.0001f)
    }

    @Test fun staggerGatePlaysEachIndexOnce() {
        val gate = xyz.cncnlp.soundwave.ui.common.StaggerGate()
        assertTrue(gate.pending(3))
        assertEquals(480, xyz.cncnlp.soundwave.ui.common.StaggerMotion.delayFor(12))
    }
}
