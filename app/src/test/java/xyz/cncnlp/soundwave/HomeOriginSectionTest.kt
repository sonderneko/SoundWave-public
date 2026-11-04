package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ACCEPT6-HOMEORIGIN：首页分区落点索引（与 HomePage "hot" 分支 LazyColumn item 序对齐）
 * + 分区滚动请求的单次消费语义。
 */
class HomeOriginSectionTest {

    @Before fun resetRequest() = HomeSectionScrollRequest.reset()

    // item 序：0 hero、1 甄选头、2 甄选内容、3 热门歌曲头、(提示行)、热门内容、特色轮播、(新歌速递头…)
    @Test
    fun `today hero is the first item`() {
        assertEquals(0, homeFeedSectionItemIndex(HomePlaySections.TODAY, hotHintVisible = false, newSongsVisible = false))
        assertEquals(0, homeFeedSectionItemIndex(HomePlaySections.TODAY, hotHintVisible = true, newSongsVisible = true))
    }

    @Test
    fun `hot songs header index is stable regardless of the hint row`() {
        assertEquals(3, homeFeedSectionItemIndex(HomePlaySections.HOT_SONGS, hotHintVisible = false, newSongsVisible = false))
        assertEquals(3, homeFeedSectionItemIndex(HomePlaySections.HOT_SONGS, hotHintVisible = true, newSongsVisible = true))
    }

    @Test
    fun `new songs header follows hot content and the feature carousel`() {
        assertEquals(6, homeFeedSectionItemIndex(HomePlaySections.NEW_SONGS, hotHintVisible = false, newSongsVisible = true))
        assertEquals(7, homeFeedSectionItemIndex(HomePlaySections.NEW_SONGS, hotHintVisible = true, newSongsVisible = true))
    }

    @Test
    fun `absent new songs section and unknown sections have no landing`() {
        assertEquals(-1, homeFeedSectionItemIndex(HomePlaySections.NEW_SONGS, hotHintVisible = false, newSongsVisible = false))
        assertEquals(-1, homeFeedSectionItemIndex("nope", hotHintVisible = true, newSongsVisible = true))
        assertEquals(-1, homeFeedSectionItemIndex("", hotHintVisible = false, newSongsVisible = true))
    }

    @Test
    fun `scroll request is pending until consumed and only by its own key`() {
        assertNull(HomeSectionScrollRequest.pending)
        HomeSectionScrollRequest.request(HomePlaySections.HOT_SONGS)
        assertEquals(HomePlaySections.HOT_SONGS, HomeSectionScrollRequest.pending)
        // 消费别的键不清当前请求（防并发请求互相吞掉）。
        HomeSectionScrollRequest.consume(HomePlaySections.NEW_SONGS)
        assertEquals(HomePlaySections.HOT_SONGS, HomeSectionScrollRequest.pending)
        HomeSectionScrollRequest.consume(HomePlaySections.HOT_SONGS)
        assertNull(HomeSectionScrollRequest.pending)
    }

    @Test
    fun `scroll request rejects unknown sections`() {
        HomeSectionScrollRequest.request("nope")
        assertNull(HomeSectionScrollRequest.pending)
    }
}
