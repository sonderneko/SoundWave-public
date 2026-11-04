package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.*
import org.junit.Test

class HotArtistPortraitTest {
    @Test fun kugouResolvesPortraitSizeBeforeImageModel() {
        val raw = """{"data":{"info":[{"singername":"Artist","singerid":7,"imgurl":"http://singerimg.kugou.com/uploadpic/softhead/{size}/real.jpg"}]}}"""
        assertEquals("https://singerimg.kugou.com/uploadpic/softhead/240/real.jpg", parseKgHotArtists(raw, 12).single().avatar)
    }
}
