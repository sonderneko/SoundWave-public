package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.HOME_CONTENT_BADGE_STYLE

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeContentBadgeStyleTest {
    @Test
    fun allHomeDiscoverKindsShareTheWhiteShortenedBadgeAndSpacing() {
        val style = HOME_CONTENT_BADGE_STYLE
        assertEquals(Color.White, style.background)
        assertEquals(Color.Black, style.foreground)
        assertEquals(2f / 3f, style.backgroundHeightScale, 0.001f)
        assertEquals(0.36f, style.cornerRadiusHeightFraction, 0.001f)
        assertEquals(5.dp, style.horizontalPadding)
        assertEquals(2.dp, style.verticalOutset)
    }
}
