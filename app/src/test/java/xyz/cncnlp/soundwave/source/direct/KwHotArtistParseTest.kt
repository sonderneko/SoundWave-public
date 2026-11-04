package xyz.cncnlp.soundwave.source.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r657 P0-3：酷我热门歌手必须走 https，并能解析歌手名含单引号的伪 JSON。 */
class KwHotArtistParseTest {

    @Test
    fun kwHotArtistRequestUsesHttpsAndPassesCleartextPolicy() {
        val url = kwHotArtistUrl(12)
        assertTrue(url.startsWith("https://artistlistinfo.kuwo.cn/"))
        assertTrue(url.contains("rn=12"))
        assertTrue(isCleartextApiAllowed(url))
        assertTrue(KW_HOT_ARTIST_REFERER.startsWith("https://"))
        // 旧的 http 地址会被 HttpPolicy fail-closed 拒绝，这正是“恒为空”的根因。
        assertTrue(!isCleartextApiAllowed(url.replaceFirst("https://", "http://")))
    }

    @Test
    fun parsesPlainKuwoPseudoJson() {
        val raw = "{'total':'2','artistlist':[{'id':'336','name':'周杰伦','pic':'120/s4s56/58/291211030.jpg'},{'id':'1892','name':'王杰','pic':''}]}"
        val list = parseKwHotArtists(raw, 12)
        assertEquals(listOf("周杰伦", "王杰"), list.map { it.name })
        assertEquals("https://img1.kuwo.cn/star/starheads/120/s4s56/58/291211030.jpg", list[0].avatar)
        assertEquals("", list[1].avatar)
        assertEquals("336", list[0].id)
    }

    @Test
    fun parsesArtistNamesContainingApostrophes() {
        val raw = "{'artistlist':[{'id':'1','name':'Guns N' Roses','pic':''},{'id':'2','name':'Rock 'n' Roll Band' ,'pic':''},{'id':'3','name':'Say \"Hi\"','pic':''}]}"
        val list = parseKwHotArtists(raw, 12)
        assertEquals(listOf("Guns N' Roses", "Rock 'n' Roll Band", "Say \"Hi\""), list.map { it.name })
    }

    @Test
    fun strictJsonAndHtmlEntitiesStillWork() {
        val strict = "{\"artistlist\":[{\"id\":\"9\",\"name\":\"Guns N&apos; Roses\",\"pic\":\"https://x/y.jpg\"}]}"
        val list = parseKwHotArtists(strict, 12)
        assertEquals("Guns N' Roses", list.single().name)
        assertEquals("https://x/y.jpg", list.single().avatar)
    }

    @Test
    fun converterKeepsStructureAndRespectsLimit() {
        val converted = kwPseudoJsonToJson("{'a':'it's','b':[ 'x' , 'y' ],'n':1}")
        val obj = JSONObject(converted)
        assertEquals("it's", obj.getString("a"))
        assertEquals(2, obj.getJSONArray("b").length())
        assertEquals(1, obj.getInt("n"))
        val many = (1..20).joinToString(",", prefix = "{'artistlist':[", postfix = "]}") { "{'id':'$it','name':'A$it','pic':''}" }
        assertEquals(5, parseKwHotArtists(many, 5).size)
        assertTrue(parseKwHotArtists("not json at all", 5).isEmpty())
        assertTrue(parseKwHotArtists("", 5).isEmpty())
    }
}
