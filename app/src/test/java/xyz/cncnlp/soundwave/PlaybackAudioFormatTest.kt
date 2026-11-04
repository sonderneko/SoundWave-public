package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.cncnlp.soundwave.playback.PlaybackAudioFormat
import xyz.cncnlp.soundwave.playback.playbackAudioFormatLabel

class PlaybackAudioFormatTest {
    @Test
    fun `renderer FLAC format displays codec and sample rate only`() {
        val format = PlaybackAudioFormat.fromMedia3("audio/flac", null, 192_000, 2)

        assertEquals("FLAC", format?.codec)
        assertEquals("FLAC · 192 kHz", playbackAudioFormatLabel(format))
    }

    @Test
    fun `AAC codec string and non-integer kHz sample rate are formatted accurately`() {
        val format = PlaybackAudioFormat.fromMedia3(null, "mp4a.40.2", 44_100, 1)

        assertEquals("AAC · 44.1 kHz", playbackAudioFormatLabel(format))
    }

    @Test
    fun `unknown fields are omitted and no preferred quality is invented`() {
        val format = PlaybackAudioFormat.fromMedia3("audio/x-unknown", null, -1, -1)

        assertNull(format)
        assertEquals("播放格式信息暂不可用", playbackAudioFormatLabel(format))
    }

    @Test
    fun `partial renderer metadata remains useful without fabricating codec or channels`() {
        val format = PlaybackAudioFormat.fromMedia3("audio/x-unknown", null, 48_000, -1)

        assertEquals("48 kHz", playbackAudioFormatLabel(format))
    }

    @Test
    fun `channel count alone is not presented as an audio quality label`() {
        val format = PlaybackAudioFormat.fromMedia3("audio/x-unknown", "vendor-codec-999", 0, 6)

        assertEquals("播放格式信息暂不可用", playbackAudioFormatLabel(format))
    }
}
