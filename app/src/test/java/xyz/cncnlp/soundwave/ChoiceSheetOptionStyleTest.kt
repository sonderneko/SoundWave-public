package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoiceSheetOptionStyleTest {
    private val optionGroups = listOf(
        USER_AUDIO_QUALITY_OPTIONS.map { it.first },
        listOf("false", "true"),
        listOf("default", "old", "name", "singer"),
        listOf("like_sel", "next", "add_sel", "download_sel", "remove_sel"),
        // 音乐库“更多”菜单已改为设计稿 v2 专用抽屉，不再经 ChoiceSheet（switch_platform/sort_playlists/import/scan/new 已移除）。
        listOf("", "wy", "tx", "kg", "kw", "mg"),
        listOf("100", "200", "500", "1024", "2048", "5120", "0")
    )

    @Test
    fun everyChoiceKeyHasAnExplicitSemanticGlyphAndColorRole() {
        optionGroups.flatten().distinct().forEach { key ->
            val style = choiceOptionStyle(key)
            assertTrue("glyph must be explicit for key '$key'", style.glyph.isNotBlank())
            assertTrue("color role must be a Material semantic role for key '$key'",
                ChoiceOptionColorRole.values().contains(style.colorRole))
        }
    }

    @Test
    fun everyChoiceGroupUsesDistinctGlyphs() {
        optionGroups.forEachIndexed { index, keys ->
            val glyphs = keys.map { choiceOptionStyle(it).glyph }
            assertEquals("choice group $index repeats an option glyph", glyphs.size, glyphs.toSet().size)
        }
    }

    @Test
    fun destructiveRemovalUsesErrorRoleAndNoGenericFallbackExists() {
        assertEquals(ChoiceOptionColorRole.ERROR, choiceOptionStyle("remove_sel").colorRole)
        assertFalse("danger styling must be reserved for removal", optionGroups.flatten()
            .filterNot { it == "remove_sel" }
            .any { choiceOptionStyle(it).colorRole == ChoiceOptionColorRole.ERROR })
        assertEquals("the explicit test inventory must cover all current ChoiceSheet keys",
            30, optionGroups.flatten().distinct().size)
    }
}
