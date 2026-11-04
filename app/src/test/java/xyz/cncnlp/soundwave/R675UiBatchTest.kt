package xyz.cncnlp.soundwave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.common.HeaderControlShadowDarkAlpha
import xyz.cncnlp.soundwave.ui.common.HeaderControlShadowLightAlpha
import xyz.cncnlp.soundwave.ui.common.FloatingControlShadowDarkAlpha
import xyz.cncnlp.soundwave.ui.common.FloatingControlShadowLightAlpha

class R675UiBatchTest {
    private fun src(name: String): String =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }.walkTopDown().first { it.name == name }.readText()

    @Test
    fun headerControlShadowIsLighterThanInitialR675() {
        assertTrue(HeaderControlShadowDarkAlpha < 0.32f && HeaderControlShadowLightAlpha < 0.10f)
        assertEquals(0.20f, HeaderControlShadowDarkAlpha, 0f)
        assertEquals(0.06f, HeaderControlShadowLightAlpha, 0f)
        val shadow = src("SectionCardShadow.kt")
        assertTrue(shadow.contains("12.dp, 3.dp, excludeInterior = true"))
    }

    @Test
    fun floatingControlsUseIndependentLighterShadowOutsideClip() {
        assertEquals(0.24f, FloatingControlShadowDarkAlpha, 0f)
        assertEquals(0.08f, FloatingControlShadowLightAlpha, 0f)
        assertTrue(FloatingControlShadowDarkAlpha < 0.60f && FloatingControlShadowLightAlpha < 0.24f)
        val detail = src("DetailDesignPages.kt")
        val fab = detail.substring(detail.indexOf("internal fun DetailAccentFab("), detail.indexOf("internal fun DetailCompactSearchField("))
        assertTrue(fab.contains(".floatingControlShadow(CircleShape)\n            .clip(CircleShape)"))
        assertTrue(!fab.contains(".homeCoverShadow("))
        assertTrue(fab.contains("Modifier.size(40.dp)") && fab.contains(".clickable(onClick = onClick)"))
        val shadows = src("SectionCardShadow.kt")
        assertTrue(shadows.contains("if (dark) FloatingControlShadowDarkAlpha else FloatingControlShadowLightAlpha), 18.dp, 5.dp"))
        // 封面及整卡仍保留原参数；按钮淡化不能波及这些视觉层级。
        // SW-08-01（G 波改钉，规格变更）：封面阴影参数已 token 化为 ShadowScale——接线在 SectionCardShadow
        // 钉 token 引用（全文字面量比对不变），数值改在 DesignScale 定义处钉字面量，合计严格度不降。
        assertTrue(shadows.contains("if (dark) ShadowScale.CoverAlphaDark else ShadowScale.CoverAlphaLight), ShadowScale.CoverBlur, ShadowScale.CoverOffsetY"))
        val shadowScale = src("DesignScale.kt")
        assertTrue(shadowScale.contains("const val CoverAlphaLight = 0.24f") && shadowScale.contains("const val CoverAlphaDark = 0.60f"))
        assertTrue(shadowScale.contains("val CoverBlur: Dp = 18.dp") && shadowScale.contains("val CoverOffsetY: Dp = 5.dp"))
        assertTrue(shadows.contains("if (dark) 0.58f else 0.23f), 20.dp, 5.dp"))
        assertTrue(src("HomePage.kt").contains("DetailAccentFab("))
        assertTrue(src("LibraryPage.kt").contains("DetailAccentFab("))
    }

    @Test
    fun featureCarouselCardsShareDigitalAlbumEditorialShadowOutsideClip() {
        val home = src("HomeDesignSections.kt")
        val carousel = home.substring(home.indexOf("internal fun HomeFeatureCarousel("), home.indexOf("private fun HomeFeatureCoverStack("))
        assertTrue(carousel.contains(".editorialCardShadow(shape)\n                        .clip(shape).background(fill)"))
        val digital = home.substring(home.indexOf("internal fun HomeDigitalAlbumCard("))
        assertTrue(digital.substring(0, 900).contains(".editorialCardShadow(shape)"))
        // 外层容器不裁切（无 clip / clipToBounds），阴影不会被截断。
        val host = carousel.substring(carousel.indexOf("BoxWithConstraints("), carousel.indexOf("val slotWidth"))
        assertTrue(!host.contains("clip") && !carousel.contains("clipToBounds"))
    }

    @Test
    fun digitalAlbumCarouselIsManualAnimatedPager() {
        val page = src("HomePage.kt")
        assertTrue(page.contains("item { HomeDigitalAlbumCarousel(digitalCards, platform, carouselActive, onOpenDiscover) }"))
        val fn = page.substring(page.indexOf("private fun HomeDigitalAlbumCarousel("))
        // E18（用户裁定）：数字专辑去掉自动滚动——不得再接 HomePagerAutoAdvance；吸附走令牌规格 + 邻页预组合。
        assertTrue(!fn.contains("HomePagerAutoAdvance("))
        assertTrue(fn.contains("HorizontalPager(state = digitalPager, pageSize = PageSize.Fixed(digitalWidth)"))
        assertTrue(fn.contains("PagerDefaults.flingBehavior(") && fn.contains("snapPositionalThreshold = 0.3f") && fn.contains("beyondViewportPageCount = 1"))
        assertTrue(fn.contains("currentPageOffsetFraction") && fn.contains("scaleX = sc"))
        assertTrue(!fn.contains("LazyRow("))
        val home = src("HomeDesignSections.kt")
        val auto = home.substring(home.indexOf("internal fun HomePagerAutoAdvance("), home.indexOf("/* ─────"))
        assertTrue(auto.contains("MotionPolicy.rememberReduceMotion()") && auto.contains("if (reduceMotion) pager.scrollToPage(next)"))
        assertTrue(auto.contains("collectIsDraggedAsState") && auto.contains("reduceMotion) {"))
        assertEquals(260f, homeDigitalAlbumCardWidth(300).value, 0f)
        assertEquals(330f, homeDigitalAlbumCardWidth(500).value, 0f)
    }
}
