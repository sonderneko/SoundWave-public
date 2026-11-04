package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.common.frostedHeaderButtonSpec

/** r658c U #1/#3/#7：折叠页头按滚动偏移连续过渡；磨砂圆钮半透明。 */
class R658cUDetailChromeTest {
    @Test fun heroProgressIsContinuousWithScrollOffset() {
        assertEquals(0f, detailHeroCollapseProgress(400f, 100f, 96f, pastHero = false), 0.001f)
        assertEquals(0.5f, detailHeroCollapseProgress(148f, 100f, 96f, pastHero = false), 0.001f)
        assertEquals(1f, detailHeroCollapseProgress(90f, 100f, 96f, pastHero = false), 0.001f)
        assertEquals(1f, detailHeroCollapseProgress(null, 100f, 96f, pastHero = true), 0.001f)
        assertEquals(0f, detailHeroCollapseProgress(null, 100f, 96f, pastHero = false), 0.001f)
    }

    @Test fun frostedButtonsStayTranslucentInBothModes() {
        listOf(true, false).forEach { media ->
            val spec = frostedHeaderButtonSpec(media, dark = false)
            assertTrue(spec.tint.alpha < 1f && spec.fallbackAlpha < 1f)
        }
    }
}
