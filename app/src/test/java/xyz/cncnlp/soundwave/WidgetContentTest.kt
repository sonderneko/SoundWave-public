package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.widget.WidgetContent

/**
 * accept6-widget (WAVE-ACCEPT6-WIDGET): the widget's presentation contract —
 * live state beats the persisted checkpoint, a checkpoint is never shown as
 * playing, blank input collapses to the honest empty state, and the push gate
 * key only moves when something visible changed.
 */
class WidgetContentTest {

    @Test
    fun liveStateWinsOverCheckpoint() {
        val live = WidgetContent.live("Live Song", "Singer A", "cover://a", playing = true)
        val checkpoint = WidgetContent.checkpoint("Old Song", "Singer B", "cover://b")
        val resolved = WidgetContent.resolve(live, checkpoint)
        assertEquals("Live Song", resolved.title)
        assertTrue(resolved.playing)
    }

    @Test
    fun checkpointShowsWhenNoLiveSongAndIsAlwaysPaused() {
        val checkpoint = WidgetContent.checkpoint("Last Song", "Singer B", "cover://b")
        val resolved = WidgetContent.resolve(WidgetContent.EMPTY, checkpoint)
        assertTrue(resolved.hasSong)
        assertEquals("Last Song", resolved.title)
        assertFalse("a persisted checkpoint must never render as playing", resolved.playing)
    }

    @Test
    fun blankTitlesCollapseToEmptyState() {
        assertEquals(WidgetContent.EMPTY, WidgetContent.live("  ", "Singer", "c", playing = true))
        assertEquals(WidgetContent.EMPTY, WidgetContent.live(null, "Singer", "c", playing = false))
        assertEquals(WidgetContent.EMPTY, WidgetContent.checkpoint("", "Singer", "c"))
        assertEquals(
            WidgetContent.EMPTY,
            WidgetContent.resolve(WidgetContent.EMPTY, WidgetContent.EMPTY),
        )
    }

    @Test
    fun liveValuesAreTrimmed() {
        val content = WidgetContent.live("  Song  ", "  Singer  ", "  cover://a  ", playing = false)
        assertEquals("Song", content.title)
        assertEquals("Singer", content.artist)
        assertEquals("cover://a", content.coverUrl)
    }

    @Test
    fun compactLineMergesTitleAndArtist() {
        val both = WidgetContent.live("Song", "Singer", "", playing = false)
        assertEquals("Song · Singer", WidgetContent.compactLine(both))
        val noArtist = WidgetContent.live("Song", " ", "", playing = false)
        assertEquals("Song", WidgetContent.compactLine(noArtist))
        assertEquals("", WidgetContent.compactLine(WidgetContent.EMPTY))
    }

    @Test
    fun pushKeyMovesOnlyWithVisibleChange() {
        val base = WidgetContent.live("Song", "Singer", "cover://a", playing = false)
        assertEquals(base.pushKey, base.copy().pushKey)
        assertNotEquals(base.pushKey, base.copy(playing = true).pushKey)
        assertNotEquals(base.pushKey, base.copy(title = "Other").pushKey)
        assertNotEquals(base.pushKey, base.copy(coverUrl = "cover://b").pushKey)
        assertNotEquals(base.pushKey, WidgetContent.EMPTY.pushKey)
    }
}
