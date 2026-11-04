package xyz.cncnlp.soundwave

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptIntervalSheetInputTest {
    @Test
    fun initialNumericValueKeepsCaretAtTheEnd() {
        val input = TextFieldValue("90", selection = TextRange(2))

        val filtered = filterIntervalInput(input)

        assertEquals("90", filtered.text)
        assertEquals(TextRange(2), filtered.selection)
    }

    @Test
    fun digitFilteringPreservesCaretInsteadOfResettingItToTheStart() {
        val input = TextFieldValue("9x05", selection = TextRange(4))

        val filtered = filterIntervalInput(input)

        assertEquals("905", filtered.text)
        assertEquals(TextRange(3), filtered.selection)
    }

    @Test
    fun deliberateMidNumberCaretPositionIsPreserved() {
        val input = TextFieldValue("905", selection = TextRange(1))

        val filtered = filterIntervalInput(input)

        assertEquals("905", filtered.text)
        assertEquals(TextRange(1), filtered.selection)
    }
}
