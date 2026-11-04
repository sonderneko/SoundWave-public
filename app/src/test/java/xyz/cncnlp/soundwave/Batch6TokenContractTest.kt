package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class Batch6TokenContractTest {
    @Test fun trackGeometryAndTypographyRetainRoles() {
        assertEquals(80.dp,ThemeTokens.TrackRow.Height);assertEquals(70.dp,ThemeTokens.TrackRow.CompactHeight)
        assertEquals(58.dp,ThemeTokens.TrackRow.Cover);assertEquals(46.dp,ThemeTokens.TrackRow.CompactCover)
        assertEquals(48.dp,ThemeTokens.TrackRow.ActionTarget);assertEquals(22.dp,ThemeTokens.TrackRow.ActionIcon)
        assertEquals(15.sp,ThemeTokens.TrackRow.TitleSize);assertEquals(13.sp,ThemeTokens.TrackRow.SlimTitleSize)
        assertEquals(13.sp,ThemeTokens.TrackRow.MetaSize);assertEquals(11.sp,ThemeTokens.TrackRow.SlimMetaSize)
    }
    @Test fun badgesRetainDarkAndLightSemanticFills() {
        assertEquals(Color(0xFFFFE4DF),ThemeTokens.TrackBadge.qualityFill(false))
        assertEquals(Color(0xFF4A2823),ThemeTokens.TrackBadge.qualityFill(true))
        // MERGE7 对齐（J5，用户裁定）：NEW 徽标恢复蓝色（内容标记例外），不再用米色填充。
        assertEquals(Color(0xFFDFE9FF),ThemeTokens.TrackBadge.newFill(false))
        assertEquals(Color(0xFF1C2A44),ThemeTokens.TrackBadge.newFill(true))
        assertEquals(16.dp,ThemeTokens.TrackBadge.Height);assertEquals(9.sp,ThemeTokens.TrackBadge.FontSize)
    }
    @Test fun navigationStylesAndReducedMotionRemainDistinct() {
        assertEquals(9.sp,ThemeTokens.Navigation.Classic.LabelSize)
        assertEquals(13.sp,ThemeTokens.Navigation.Capsule.LabelSize)
        assertEquals(520,ThemeTokens.Navigation.Motion.SwitchMs)
        assertEquals(420,ThemeTokens.Navigation.Motion.IconMs)
        assertEquals(200,ThemeTokens.Navigation.Motion.ReducedMs)
        assertEquals(160,ThemeTokens.Navigation.Motion.ReducedIconMs)
        assertEquals(0.97f,ThemeTokens.Navigation.Motion.PressedScale)
    }
}
