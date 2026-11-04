package xyz.cncnlp.soundwave.ui.common

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r658c D：公共底部抽屉按原型 .sheet 贴屏幕底边（bottom:0、只顶部圆角、无底边线、translateY(100%) 滑入）。 */
class DesignSheetBottomEdgeGuardTest {
    @Test
    fun sheetSitsFlushOnScreenBottomEdge() {
        val s = listOf("src/main/java/xyz/cncnlp/soundwave/ui/common/DesignSheet.kt", "app/src/main/java/xyz/cncnlp/soundwave/ui/common/DesignSheet.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(s.contains("AppOverlayDialog(onDismissRequest = close)"))
        assertTrue(s.contains("applyDesignSheetWindow(w)"))
        assertTrue(s.contains("w.setGravity(android.view.Gravity.BOTTOM)"))
        assertTrue(s.contains("fitInsetsTypes = 0"))
        assertTrue(s.contains("FLAG_LAYOUT_NO_LIMITS"))
        assertTrue(s.contains("translationY = (1f - enter.value) * size.height"))
        assertTrue(s.contains("SheetBorderShape"))
        assertTrue(s.contains(".navigationBarsPadding()"))
        assertFalse(s.contains("BOTTOM_BLEED"))
        assertFalse(s.contains("40.dp.toPx()"))
    }
}
