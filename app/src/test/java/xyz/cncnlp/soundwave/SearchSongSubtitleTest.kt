package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSongSubtitleTest {
    @Test
    fun mixedSearchShowsPlatformRightOfSinger() {
        assertEquals("周杰伦 · QQ · 叶惠美", searchSongSubtitle("周杰伦", "tx", "叶惠美", showPlatformName = true))
        assertEquals("林俊杰 · 网易云 · 第二天堂", searchSongSubtitle("林俊杰", "wy", "第二天堂", showPlatformName = true))
    }

    @Test
    fun singlePlatformKeepsLegacySubtitle() {
        assertEquals("周杰伦 · 叶惠美", searchSongSubtitle("周杰伦", "tx", "叶惠美", showPlatformName = false))
        assertEquals("周杰伦", searchSongSubtitle("周杰伦", "tx", "", showPlatformName = false))
    }

    @Test
    fun blankSingerFallsBackToPlatformFirst() {
        assertEquals("酷狗 · 叶惠美", searchSongSubtitle("", "kg", "叶惠美", showPlatformName = false))
        assertEquals("酷狗 · 叶惠美", searchSongSubtitle("", "kg", "叶惠美", showPlatformName = true))
    }

    @Test
    fun bestMatchSubtitleFollowsSameRule() {
        assertEquals("周杰伦 · QQ · 4:30", searchBestMatchSubtitle("周杰伦", "tx", "4:30", showPlatformName = true))
        assertEquals("周杰伦 · 4:30", searchBestMatchSubtitle("周杰伦", "tx", "4:30", showPlatformName = false))
        assertEquals("咪咕 · 4:30", searchBestMatchSubtitle("", "mg", "4:30", showPlatformName = false))
    }
}
