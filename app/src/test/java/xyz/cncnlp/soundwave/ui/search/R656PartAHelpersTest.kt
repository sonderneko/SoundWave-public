package xyz.cncnlp.soundwave.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.cncnlp.soundwave.ui.home.chartHeroUpdateLine

class R656PartAHelpersTest {
    @Test fun dropdownShowsAllHistoryWhenQueryBlank() {
        assertEquals(listOf("a", "b"), searchHistoryDropdownItems(listOf(" a ", "b", "a", ""), ""))
    }

    @Test fun dropdownFiltersByQueryAndHidesExactMatch() {
        assertEquals(listOf("Jay Chou"), searchHistoryDropdownItems(listOf("jay", "Jay Chou", "Eason"), "JAY"))
    }

    @Test fun dropdownRespectsLimit() {
        assertEquals(3, searchHistoryDropdownItems((1..20).map { "k$it" }, "", limit = 3).size)
    }

    @Test fun chartHeroUpdateLineJoinsAvailableParts() {
        assertEquals("每天更新 · 共 100 首", chartHeroUpdateLine("每天更新", 100))
        assertEquals("每天更新", chartHeroUpdateLine("每天更新", 0))
        assertEquals("共 3 首", chartHeroUpdateLine(" ", 3))
        assertEquals("", chartHeroUpdateLine("", 0))
    }
}

class R656GenreLabelTest {
    @Test fun labelFitsThreeCharsOnNarrowCard() {
        val sp = searchGenreLabelSp(60f, 3)
        org.junit.Assert.assertTrue(sp * 3 * 1.06f <= 60.01f)
        org.junit.Assert.assertEquals(14f, searchGenreLabelSp(200f, 2), 0.001f) // r657：上限 17 → 14sp
        org.junit.Assert.assertEquals(11f, searchGenreLabelSp(10f, 3), 0.001f)
    }
}
