package xyz.cncnlp.soundwave

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeHotChartSelectionTest {
    @Test
    fun `selects only exact current-platform hot chart`() {
        val network = RankBoard(name = "网络热歌榜", source = "wy", id = "network")
        val qq = RankBoard(name = "QQ热歌榜", source = "tx", id = "tx_hot")
        val netease = RankBoard(name = "热歌榜", source = "wy", id = "3778678")

        assertSame(netease, selectHomeHotBoard("wy", listOf(network, qq, netease)))
        assertSame(qq, selectHomeHotBoard("QQ", listOf(network, qq, netease)))
        assertNull(selectHomeHotBoard("wy", listOf(network, qq)))
    }

    @Test
    fun `selects Kugou live official TOP500 chart by its real source and id`() {
        val top500 = RankBoard(name = "TOP500", source = "kg", id = "8888")
        val otherPlatform = RankBoard(name = "TOP500", source = "tx", id = "8888")

        assertSame(top500, selectHomeHotBoard("kg", listOf(otherPlatform, top500)))
        assertNull(selectHomeHotBoard("kg", listOf(otherPlatform)))
    }

    @Test
    fun `does not substitute a similarly named chart from another platform`() {
        val migu = RankBoard(name = "全网热歌榜", source = "mg", id = "83048887")
        assertNull(selectHomeHotBoard("kg", listOf(migu)))
    }
}
