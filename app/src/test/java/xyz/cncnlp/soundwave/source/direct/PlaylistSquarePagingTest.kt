package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import xyz.cncnlp.soundwave.Song
import org.json.JSONObject

class PlaylistSquarePagingTest {
    @Test fun categoriesPreserveNativeIdsAndDigestNotNamesAsSearchTerms() {
        val tx = parseSquareCategoryRefs("tx", """{"code":0,"tags":{"code":0,"data":{"v_group":[{"group_name":"主题","v_item":[{"id":59,"name":"经典","usable":1}]}]}}}""")!!
        assertEquals(PlaylistCategoryRef("主题", "经典", "59"), tx.single())
        val kw = parseSquareCategoryRefs("kw", """{"code":200,"data":[{"name":"风格","data":[{"id":"173","name":"纯音乐","digest":"10000"},{"id":"140","name":"专区","digest":"43"}]}]}""")!!
        assertEquals("10000", kw[0].kind);assertEquals("43", kw[1].kind)
    }
    @Test fun nativeTotalsDoNotEndAfterFirstThirtyAndErrorIsNotAnEmptyPage() {
        fun raw(id: String) = """{"code":0,"playlist":{"code":0,"data":{"content":{"total_cnt":986,"v_item":[{"basic":{"tid":"$id","title":"真实分类","song_cnt":10}}]}}}}"""
        val first = parseNativePlaylistSquare("tx",raw("a"),1,30) as PlaylistSearchOutcome.Success
        val second = parseNativePlaylistSquare("tx",raw("b"),2,30) as PlaylistSearchOutcome.Success
        assertTrue(first.hasMore!!);assertTrue(second.hasMore!!);assertNotEquals(first.playlists[0].id,second.playlists[0].id)
        assertFalse((parseNativePlaylistSquare("tx",raw("z"),33,30) as PlaylistSearchOutcome.Success).hasMore!!)
        assertSame(PlaylistSearchOutcome.Failure,parseNativePlaylistSquare("tx","{\"code\":500}",2,30))
    }
    @Test fun qqAllUsesItsDistinctPayloadAndKuwoUsesTotal() {
        val tx = parseNativePlaylistSquare("tx", """{"code":0,"playlist":{"code":0,"data":{"total":11617,"v_playlist":[{"tid":"99","title":"全部","cover_url_big":"https://example.org/c.jpg","song_num":5}]}}}""",2,30) as PlaylistSearchOutcome.Success
        assertEquals("99",tx.playlists.single().id);assertTrue(tx.hasMore!!)
        val kw = parseNativePlaylistSquare("kw", """{"code":200,"data":{"total":31,"data":[{"id":"22","name":"纯音乐","total":3}]}}""",2,30,"10000") as PlaylistSearchOutcome.Success
        assertEquals("22",kw.playlists.single().id);assertFalse(kw.hasMore!!)
    }
    @Test fun secondsConvertedAtKnownBoundariesNotByMagnitudeGuess() {
        assertEquals(240000L,secondsToDurationMs(240));assertEquals(0L,secondsToDurationMs(-1));assertEquals(0L,secondsToDurationMs(Long.MAX_VALUE))
        assertEquals(180000L,Song.fromJson(JSONObject("""{"id":"1","interval":180}""")).duration)
        assertEquals(180000L,Song.fromJson(JSONObject("""{"id":"1","duration":180000,"interval":180}""")).duration)
        assertEquals(0L,Song.fromJson(JSONObject("""{"id":"1"}""")).duration)
    }
    @Test fun otherSquaresUseNativeCategoriesAndContinuation() {
        val refs = parseSquareCategoryRefs("mg", """{"code":"000000","data":[{"header":{"title":"风格"},"content":[{"texts":["流行","1000001672"]}]}]}""")!!
        assertEquals("1000001672",refs.single().id)
        val base = """{"code":"000000","data":{"nextPageUrl":"NEXT","contentItemList":[{}, {"itemList":[{"logEvent":{"contentId":"12"},"title":"流行歌单","imageUrl":"https://example.org/i.jpg"}]}]}}"""
        assertTrue((parseOtherNativePlaylistSquare("mg",base) as PlaylistSearchOutcome.Success).hasMore!!)
        assertTrue((parseOtherNativePlaylistSquare("mg",base.replace("NEXT","")) as PlaylistSearchOutcome.Success).hasMore!!)
        assertFalse((parseOtherNativePlaylistSquare("mg", """{"code":"000000","data":{"contentItemList":[]}}""") as PlaylistSearchOutcome.Success).hasMore!!)
        assertFalse((parseOtherNativePlaylistSquare("mg", """{"code":"000000","data":{"nextPageUrl":"STALE","contentItemList":[]}}""") as PlaylistSearchOutcome.Success).hasMore!!)
        assertSame(PlaylistSearchOutcome.Failure,parseOtherNativePlaylistSquare("mg", """{"code":"000000","data":{}}"""))
        assertSame(PlaylistSearchOutcome.Failure,parseOtherNativePlaylistSquare("mg", """{"code":"000000","data":{"contentItemList":[{}]}}"""))
        assertSame(PlaylistSearchOutcome.Failure,parseOtherNativePlaylistSquare("wy","{\"code\":500}"))
        assertFalse((parseOtherNativePlaylistSquare("wy","""{"code":200,"playlists":[],"more":false}""") as PlaylistSearchOutcome.Success).hasMore!!)
    }
}
