package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDirectFetchTest {

    @Test
    fun `parseWyAlbumDetail maps songs with al fallback and shrinks covers`() {
        val raw = """
            {"album":{"id":123,"name":"测试专辑","picUrl":"http://p1.music.126.net/album.jpg"},
             "songs":[
               {"id":"1","name":"第一首","dt":180000,"ar":[{"id":9,"name":"歌手A"}],"al":{"id":123,"name":"测试专辑","picUrl":"http://p1.music.126.net/song1.jpg"}},
               {"id":"2","name":"第二首","ar":[{"id":9,"name":"歌手B"},{"name":"歌手C"}],"al":{"picUrl":""}},
               {"name":"无id跳过"},
               {"id":"4","name":"","artists":[{"name":"老字段"}]}
             ]}
        """.trimIndent()
        val out = parseWyAlbumDetail(raw)
        assertEquals("测试专辑", out.name)
        assertTrue(out.cover.endsWith("album.jpg?param=1080y1080"))
        assertEquals(3, out.songs.size)
        assertEquals("1", out.songs[0].id)
        assertEquals("第一首", out.songs[0].name)
        assertEquals("歌手A", out.songs[0].singer)
        assertEquals("测试专辑", out.songs[0].album)
        assertTrue(out.songs[0].cover.endsWith("song1.jpg?param=1080y1080"))
        assertEquals(180000L, out.songs[0].duration)
        assertEquals("歌手B / 歌手C", out.songs[1].singer)
        assertEquals("测试专辑", out.songs[1].album)
        assertTrue(out.songs[1].cover.endsWith("album.jpg?param=1080y1080"))
        assertEquals("4", out.songs[2].id)
        assertEquals("", out.songs[2].name)
        assertEquals("老字段", out.songs[2].singer)
        assertEquals("wy", out.songs[2].source)
    }

    @Test
    fun `parseWyAlbumDetail returns empty on blank and garbage`() {
        assertEquals(0, parseWyAlbumDetail("").songs.size)
        assertEquals(0, parseWyAlbumDetail("not json").songs.size)
        assertEquals("", parseWyAlbumDetail("{}").name)
    }

    @Test
    fun `parseTxAlbumDetail maps songmid singers and albummid fallback`() {
        val raw = """
            {"data":{"name":"专辑名","list":[
              {"songmid":"MID1","songname":"第一首","singer":[{"name":"歌手A"},{"name":"歌手B"}],"albummid":"AMID1","albumname":"专辑名"},
              {"songid":"MID2","songname":"第二首","singer":[]},
              {"songname":"无mid跳过"},
              {"songmid":"","songname":"空mid跳过"}
            ]}}
        """.trimIndent()
        val out = parseTxAlbumDetail(raw, "ALBMID")
        assertEquals("专辑名", out.name)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000ALBMID.jpg", out.cover)
        assertEquals(2, out.songs.size)
        assertEquals("MID1", out.songs[0].id)
        assertEquals("第一首", out.songs[0].name)
        assertEquals("歌手A / 歌手B", out.songs[0].singer)
        assertEquals("专辑名", out.songs[0].album)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000AMID1.jpg", out.songs[0].cover)
        assertEquals("MID2", out.songs[1].id)
        assertEquals("", out.songs[1].singer)
        assertEquals("专辑名", out.songs[1].album)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000ALBMID.jpg", out.songs[1].cover)
        assertEquals("tx", out.songs[1].source)
    }

    @Test
    fun `parseTxAlbumDetail strips jsonp wrapper and fails closed`() {
        val wrapped = "callback({\"data\":{\"name\":\"X\",\"list\":[]}})"
        assertEquals("X", parseTxAlbumDetail(wrapped, "M").name)
        assertEquals(0, parseTxAlbumDetail(wrapped, "M").songs.size)
        assertEquals(0, parseTxAlbumDetail("not json", "M").songs.size)
        assertEquals("", parseTxAlbumDetail("", "M").name)
    }
}
