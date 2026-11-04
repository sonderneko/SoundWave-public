package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {
    @Test
    fun `plain lyrics are retained without inventing a timeline`() {
        val lines = LyricParser.parse("第一行歌词\n第二行歌词")
        assertEquals(listOf("第一行歌词", "第二行歌词"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == -1L && it.words.isEmpty() })
        assertEquals(-1, LyricParser.indexAt(lines, 8_000L))
        assertTrue(LyricParser.effectiveWords(lines.first(), lines.getOrNull(1)).isEmpty())
        assertEquals(0f, LyricParser.lineProgress(lines.first(), lines.getOrNull(1), 8_000L), 0f)
    }

    @Test
    fun `timestamped lrc supports row timing without synthesized word timing`() {
        val lines = LyricParser.parse("[00:01.00]第一句\n[00:03.00]第二句")
        assertEquals(1_000L, lines.first().timeMs)
        assertEquals(0, LyricParser.indexAt(lines, 2_000L))
        assertFalse(LyricParser.effectiveWords(lines.first(), lines.getOrNull(1)).isNotEmpty())
        assertEquals(1f, LyricParser.lineProgress(lines.first(), lines.getOrNull(1), 1_000L), 0f)
    }

    @Test
    fun `provider word timestamps remain usable for karaoke progress`() {
        val lines = LyricParser.parse("[1000,900](1000,400,0)你(1400,500,0)好")
        assertTrue(lines.first().hasWordTimeline())
        assertEquals(2, LyricParser.effectiveWords(lines.first(), null).size)
        assertEquals(0.25f, LyricParser.lineProgress(lines.first(), null, 1_200L), 0.001f)
        val twoFieldYrc = LyricParser.parse("[1000,900](1000,400)你(1400,500)好").single()
        assertEquals("你好", twoFieldYrc.text)
        assertTrue(twoFieldYrc.hasWordTimeline())
    }

    @Test
    fun `qrc word timestamps are not mistaken for yrc markers`() {
        val lines = LyricParser.parse("[1000,1000]你(0,400)好(400,600)")
        val line = lines.single()
        assertEquals("你好", line.text)
        assertTrue(line.hasWordTimeline())
        val words = LyricParser.effectiveWords(line, null)
        assertEquals(listOf("你", "好"), words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L), words.map { it.startMs })
        assertEquals(0.25f, LyricParser.lineProgress(line, null, 1_200L), 0.001f)
    }

    @Test
    fun `enhanced lrc keeps source word timestamps`() {
        val lines = LyricParser.parse("[00:01.00]<00:01.00>你<00:01.40>好")
        val line = lines.single()
        assertEquals("你好", line.text)
        assertTrue(line.hasWordTimeline())
        assertEquals(listOf(1_000L, 1_400L), line.words.map { it.startMs })
        assertEquals(0.25f, LyricParser.lineProgress(line, null, 1_200L), 0.001f)
    }

    @Test
    fun `explicit romanization markers attach only to an exact main timestamp`() {
        val lines = LyricParser.parse(
            "[00:01.00]こんにちは\n" +
                "[00:01.00]romaji: konnichiwa\n" +
                "[00:02.00]さようなら\n" +
                "[00:02.50]拼音：shiyounara"
        )

        assertEquals(listOf("こんにちは", "さようなら"), lines.map { it.text })
        assertEquals(listOf("konnichiwa", ""), lines.map { it.romanization })
    }

    @Test
    fun `a latin main lyric alone does not invent a romanization track`() {
        val line = LyricParser.parse("[00:01.00]Hello world").single()
        assertEquals("Hello world", line.text)
        assertEquals("", line.romanization)
    }
}
