package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KW/MG artist-detail direct parsers.
 * MG fixtures are trimmed from live anonymous captures (2026-11-04, singerId=112):
 * bmw/singer/song view-tree + resourceinfo type 2002. KW fixtures are synthetic
 * contract cases built to the music-get (winterssy/luoqiz) type definitions for the
 * www artist endpoints, whose anonymous reachability is egress-dependent (see
 * WAVE-ACCEPT5-KWMGSINGER ledger); the parser must reject non-200 envelopes so the
 * /music/singer fallback stays in charge whenever the direct route is refused.
 */
class KwMgArtistDirectParseTest {
    private fun fixture(name: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream("kwmgsinger/$name"))
        .bufferedReader().use { it.readText() }

    @Test fun mgBmwSingerSongsParseIdentityCoverAndQuality() {
        val songs = parseMgBmwSingerSongs(fixture("mg-bmw-singer-song.json"))
        assertEquals(2, songs.size)
        val first = songs.first()
        assertEquals("600929000000096577", first.id)
        assertEquals("60054704965", first.copyrightId)
        assertEquals("1140505222", first.miguSongId)
        assertEquals("圣诞星（feat. 杨瑞代）", first.name)
        assertEquals("周杰伦", first.singer)
        assertEquals("112", first.singerId)
        assertEquals("圣诞星", first.album)
        assertEquals(182_000L, first.duration)
        assertTrue(first.cover.startsWith("https://d.musicapp.migu.cn/data/oss/resource/"))
        assertEquals("hires", first.maxQuality) // ZQ24 present in audioFormats.
    }

    @Test fun mgArtistDetailMergesPagesAndUsesResourceinfoMeta() {
        val page = fixture("mg-bmw-singer-song.json")
        val detail = checkNotNull(parseMgArtistDetail(fixture("mg-resourceinfo-singer.json"), page, page, "112", "兜底名"))
        assertEquals("singer_mg_112", detail.id)
        assertEquals("周杰伦", detail.name)
        assertEquals("mg", detail.source)
        assertEquals("112", detail.remoteId)
        assertEquals(2, detail.songs.size) // identical page 2 dedupes away
        assertTrue(detail.cover.contains("e7632d663a6846e1b3d357b35052150f")) // largest imgSizeType (03)
        assertTrue(detail.description.startsWith("周杰伦（Jay Chou）"))
    }

    @Test fun mgArtistDetailRejectsErrorEnvelopeAndEmptySongs() {
        assertTrue(parseMgBmwSingerSongs("""{"code":"870001","info":"内部错误"}""").isEmpty())
        assertNull(parseMgArtistDetail("""{"code":"000000","resource":[]}""", """{"code":"870001"}""", "", "112", "某歌手"))
    }

    @Test fun kwArtistDetailParsesMusicGetEnvelope() {
        val info = """{"code":200,"msg":"success","data":{"id":336,"name":"周杰伦","pic300":"https://img1.kuwo.cn/star/starheads/300/1.jpg"}}"""
        val songs = """{"code":200,"msg":"success","data":{"total":"2","list":[
          {"rid":228908,"musicrid":"MUSIC_228908","name":"青花瓷","artist":"周杰伦","artistid":336,"album":"我很忙","albumid":"1294","albumpic":"star/albumcover/1.jpg","duration":239,"formats":"MP3128|MP3H|ALFLAC"},
          {"rid":228909,"name":"稻香","artist":"周杰伦","artistid":336,"album":"魔杰座","albumid":"1295","songTimeMinutes":"3:43"}
        ]}}"""
        val detail = checkNotNull(parseKwArtistDetail(info, songs, "336", "兜底名"))
        assertEquals("singer_kw_336", detail.id)
        assertEquals("周杰伦", detail.name)
        assertEquals("kw", detail.source)
        assertTrue(detail.cover.contains("300/1.jpg"))
        assertEquals(2, detail.songs.size)
        val first = detail.songs.first()
        assertEquals("228908", first.id)
        assertEquals("青花瓷", first.name)
        assertEquals("周杰伦", first.singer)
        assertEquals("336", first.singerId)
        assertEquals("我很忙", first.album)
        assertEquals("1294", first.albumId)
        assertEquals(239_000L, first.duration)
        assertEquals("flac", first.maxQuality)
        assertEquals(223_000L, detail.songs[1].duration) // songTimeMinutes fallback
    }

    @Test fun kwArtistDetailRejectsIllegalGatewayBody() {
        // The www gateway's refusal body has no `code:200` envelope: must parse to
        // null so ApiClient.singerSongs falls back to /music/singer.
        val illegal = """{"success":false,"message":"The request is illegal!","now":"2026-11-04T05:27:53.267Z"}"""
        assertNull(parseKwArtistDetail(illegal, illegal, "336", "周杰伦"))
        assertNull(parseKwArtistDetail("", "", "336", "周杰伦"))
    }
}
