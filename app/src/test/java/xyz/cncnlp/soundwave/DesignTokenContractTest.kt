package xyz.cncnlp.soundwave

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.common.frostedSheetFill
import xyz.cncnlp.soundwave.ui.common.frostedSheetBlurDp
import xyz.cncnlp.soundwave.ui.common.SheetMotion

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DesignTokenContractTest {
    @Test fun compactActionsKeepAcceptedMetrics() {
        assertEquals(98.dp,ThemeTokens.LyricAction.MinHeight)
        assertEquals(28.dp,ThemeTokens.LyricAction.IconContainer)
        assertEquals(22.dp,ThemeTokens.LyricAction.IconSize)
        assertEquals(18.dp,ThemeTokens.LyricAction.Radius)
        assertEquals(14.sp,ThemeTokens.LyricAction.TitleSize)
        assertEquals(0.975f,ThemeTokens.LyricAction.PressedScale,0f)
        assertEquals(140,ThemeTokens.LyricAction.PressMs)
    }
    @Test fun actionColorsUseCurrentSchemeNotLegacyBlue() {
        for(colors in listOf(lightColorScheme(primary=Color.Magenta),darkColorScheme(primary=Color.Green))) {
            assertEquals(colors.primary,ThemeTokens.LyricAction.ink(colors,true))
            assertEquals(colors.onSurface,ThemeTokens.LyricAction.ink(colors,false))
            assertEquals(colors.primary.copy(alpha=0.085f),ThemeTokens.LyricAction.fill(colors,true))
            assertEquals(colors.background,ThemeTokens.LyricAction.fill(colors,false))
            assertEquals(colors.outlineVariant.copy(alpha=0.25f),ThemeTokens.LyricAction.border(colors,false))
        }
    }
    @Test fun sheetMotionAliasesKeepExternalContract() {
        assertEquals(420,SheetMotion.ENTER_MS);assertEquals(220,SheetMotion.EXIT_MS)
        assertEquals(0.38f,SheetMotion.SCRIM_ALPHA,0f)
        assertSame(ThemeTokens.Sheet.Emphasized,SheetMotion.Emphasized)
    }
    @Test fun sheetFrostRetainsExplicitSwitchContract() {
        assertEquals(Color.Red,frostedSheetFill(Color.Red,false))
        assertEquals(Color.Red.copy(alpha=0.96f),frostedSheetFill(Color.Red,true))
        assertEquals(36,frostedSheetBlurDp(true));assertEquals(3,frostedSheetBlurDp(false))
    }
}
