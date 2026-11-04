package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RankDirectFetchTest {

    @Test
    fun `normalizeRankSource aliases five platforms and rejects unknown`() {
        assertEquals("wy", normalizeRankSource("wy"))
        assertEquals("wy", normalizeRankSource("netease"))
        assertEquals("kw", normalizeRankSource("Kuwo"))
        assertEquals("kg", normalizeRankSource("kg"))
        assertEquals("tx", normalizeRankSource("QQ"))
        assertEquals("mg", normalizeRankSource("Migu"))
        assertNull(normalizeRankSource(""))
        assertNull(normalizeRankSource("script:lolx"))
    }

    @Test
    fun `parseWyToplistMerge appends unknown official boards and skips known`() {
        val raw = """{"list":[
            {"id":"19723756","name":"飙升榜"},
            {"id":"888","name":"网络热歌榜","coverImgUrl":"http://p1.music.126.net/a.jpg"},
            {"id":"","name":"无id"},
            {"name":"无id2"}
        ]}""".trimIndent()
        val out = parseWyToplistMerge(raw, setOf("19723756"))
        assertEquals(1, out.size)
        assertEquals("888", out[0].id)
        assertEquals("网络热歌榜", out[0].name)
        assertEquals("http://p1.music.126.net/a.jpg", out[0].cover)
        assertEquals(0, parseWyToplistMerge("", setOf()).size)
        assertEquals(0, parseWyToplistMerge("not-json", setOf()).size)
    }

    @Test
    fun `parseWyPlaylistDetail maps artists album picUrl and collects trackIds`() {
        val raw = """{"result":{
            "name":"热歌榜",
            "coverImgUrl":"http://p2.music.126.net/c.jpg?param=100y100",
            "trackIds":[{"id":"9"},{"id":"8"},{"id":"7"}],
            "tracks":[
                {"id":"9","name":"歌九","artists":[{"name":"甲"}],"album":{"name":"专九","picUrl":"http://p1.music.126.net/p9.jpg"}},
                {"id":"8","name":"歌八","ar":[{"name":"乙"}],"al":{"name":"专八","picUrl":"http://p2.music.126.net/p8.jpg"}},
                {"name":"无id"},
                {"id":"7","name":"歌七"}
            ]}}""".trimIndent()
        val detail = parseWyPlaylistDetail(raw, 3)
        assertEquals("http://p2.music.126.net/c.jpg?param=100y100", detail.cover)
        assertEquals(listOf("9", "8", "7"), detail.trackIds)
        assertEquals(2, detail.songs.size)
        assertEquals("9", detail.songs[0].id)
        assertEquals("甲", detail.songs[0].singer)
        assertEquals("专九", detail.songs[0].album)
        assertEquals("https://p1.music.126.net/p9.jpg?param=1080y1080", detail.songs[0].cover)
        assertEquals("乙", detail.songs[1].singer)
        assertEquals("wy", detail.songs[1].source)
    }

    @Test
    fun `parseWyPlaylistDetail retains and decodes complete source metadata`() {
        val raw = """{"result":{
            "name":"Q&amp;#160;收藏",
            "coverImgUrl":"http://p2.music.126.net/c.jpg?param=100y100",
            "creator":{"nickname":"作者&nbsp;甲"},
            "description":"第一段<br>第二段 &amp;amp; 尾段",
            "trackCount":128,
            "trackIds":[],"tracks":[]
        }}""".trimIndent()
        val detail = parseWyPlaylistDetail(raw, 20)
        assertEquals("Q 收藏", detail.name)
        assertEquals("作者 甲", detail.author)
        assertEquals("第一段\n第二段 & 尾段", detail.description)
        assertEquals(128, detail.count)
    }

    @Test
    fun `parseWySongDetail maps song detail batch`() {
        val raw = """{"songs":[{"id":"7","name":"歌七","artists":[{"name":"丙"}],"album":{"name":"专七","picUrl":"http://p3.music.126.net/p7.jpg"}}]}"""
        val songs = parseWySongDetail(raw)
        assertEquals(1, songs.size)
        assertEquals("7", songs[0].id)
        assertEquals("丙", songs[0].singer)
        assertEquals("https://p3.music.126.net/p7.jpg?param=1080y1080", songs[0].cover)
    }

    @Test
    fun `parseKwBangPage strips MUSIC rid reads num and preserves source cover sizes`() {
        val raw = """{"code":200,"data":{
            "name":"酷我热歌榜",
            "img":"http://img4.kuwo.cn/star/upload/x.png",
            "num":"300",
            "musicList":[
                {"name":"风吹过千山万水","artist":"恰恰恰恰恰","musicrid":"MUSIC_634582257","pic":"http://img3.kuwo.cn/star/albumcover/500/a/1.jpg","album":"风吹过千山万水","albumid":"104718265","duration":198},
                {"name":"rid剥离","rid":"77","albumpic":"//img2.kuwo.cn/star/albumcover/120/95/2.jpg"},
                {"name":"无rid"}
            ]}}""".trimIndent()
        val page = parseKwBangPage(raw)
        assertEquals("酷我热歌榜", page.name)
        assertEquals("https://img4.kuwo.cn/star/upload/x.png", page.cover)
        assertEquals(300, page.total)
        assertEquals(2, page.songs.size)
        assertEquals("634582257", page.songs[0].id)
        assertEquals("恰恰恰恰恰", page.songs[0].singer)
        assertEquals("https://img3.kuwo.cn/star/albumcover/500/a/1.jpg", page.songs[0].cover)
        assertEquals(198000L, page.songs[0].duration)
        // bang 条目的 albumid 此前未读，空封面条目够不到专辑封面回补资格；无此字段仍为空。
        assertEquals("104718265", page.songs[0].albumId)
        assertEquals("77", page.songs[1].id)
        assertEquals("", page.songs[1].albumId)
        assertEquals("https://img2.kuwo.cn/star/albumcover/120/95/2.jpg", page.songs[1].cover)
    }

    @Test
    fun `parseTxToplistDetail joins singers builds albummid cover and id chain`() {
        val raw = """{"topinfo":{"ListName":"巅峰榜·热歌"},"songlist":[
            {"data":{"songname":"我不难过","songmid":"001fsNdn1zuZnA","albummid":"004VSvF52mQoQp","albumname":"未完成","albumid":585,"singer":[{"name":"孙燕姿"}]}},
            {"data":{"songname":"无mid"}},
            {"data":{"songname":"songid链","songid":"12345"}}
        ]}""".trimIndent()
        val songs = parseTxToplistDetail(raw, 80)
        assertEquals(2, songs.size)
        assertEquals("001fsNdn1zuZnA", songs[0].id)
        assertEquals("孙燕姿", songs[0].singer)
        assertEquals("未完成", songs[0].album)
        assertEquals("585", songs[0].albumId)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000004VSvF52mQoQp.jpg", songs[0].cover)
        assertEquals("12345", songs[1].id)
        assertEquals("", songs[1].cover)
    }

    @Test
    fun `parseMgBoard prefers contentId filters coverBad and picks largest img`() {
        val raw = """{"code":"000000","columnInfo":{"columnTitle":"尖叫新歌榜","contents":[
            {"objectInfo":{"songName":"完整歌","singer":"甲","album":"专","contentId":"111","songId":"999","albumImgs":[{"imgSizeType":"200","img":"https://img1.migu.cn/a_02.jpg"},{"imgSizeType":"400","img":"https://img1.migu.cn/b_03.jpg"}]}},
            {"objectInfo":{"songName":"铃声版DJ","contentId":"222"}},
            {"objectInfo":{"songName":"只有songId","songId":"333"}},
            {"objectInfo":{"name":"名字fallback","contentId":"444"}}
        ]}}""".trimIndent()
        val songs = parseMgBoard(raw, 100)
        assertEquals(3, songs.size)
        assertEquals("111", songs[0].id)
        assertEquals("甲", songs[0].singer)
        assertEquals("专", songs[0].album)
        assertEquals("https://img1.migu.cn/b_03.jpg", songs[0].cover)
        assertEquals("333", songs[1].id)
        assertEquals("444", songs[2].id)
        assertEquals(0, parseMgBoard("{}", 10).size)
        assertEquals(0, parseMgBoard("""{"code":"-1"}""", 10).size)
        assertEquals(0, parseMgBoard("""{"code":"000000","columnInfo":{"columnTitle":"x"}}""", 10).size)
    }

    @Test
    fun `parseTxToplistCatalog keeps real official top ids names and covers`() {
        val raw = """{"code":0,"data":{"topList":[
            {"id":26,"topTitle":"巅峰榜·热歌","picUrl":"https://y.gtimg.cn/music/photo_new/hot.jpg","songList":[{}]},
            {"topid":"201","title":"MV 榜","cover":"https://y.gtimg.cn/music/photo_new/mv.jpg"},
            {"id":26,"topTitle":"重复 ID"},
            {"id":"abc","topTitle":"无效 ID"},
            {"id":202,"topTitle":""}
        ]}}""".trimIndent()
        val boards = parseTxToplistCatalog(raw)
        assertEquals(2, boards.size)
        assertEquals("26", boards[0].topid)
        assertEquals("巅峰榜·热歌", boards[0].name)
        assertEquals("https://y.gtimg.cn/music/photo_new/hot.jpg", boards[0].cover)
        assertEquals("201", boards[1].topid)
        assertEquals("MV 榜", boards[1].name)
        assertEquals(0, parseTxToplistCatalog("not-json").size)
    }

    @Test
    fun `migu catalog retains all registered official boards including slow previews`() {
        val ids = MG_RANKS.map { it.id }.toSet()
        assertEquals(
            setOf("mg_83048887", "mg_83049014", "mg_83048867", "mg_83049056", "mg_76557745", "mg_new", "mg_hot", "mg_original", "mg_trend", "mg_mainland", "mg_hktw", "mg_guofeng"),
            ids,
        )
    }

    @Test
    fun `parseKgRankCatalog keeps actual rank ids and official totals only`() {
        val raw = """{"status":1,"data":{"total":3,"info":[
            {"rankid":8888,"rankname":"TOP500","ranktype":2,"rank_cid":129300,"isvol":1,"imgurl":"https://img.kugou.com/top.jpg","extra":{"resp":{"all_total":500}}},
            {"rankid":51341,"rankname":"民谣榜","ranktype":1,"rank_cid":128521,"isvol":1,"imgurl":"https://img.kugou.com/folk.jpg","extra":{"resp":{"all_total":100}}},
            {"rankid":8888,"rankname":"重复项","ranktype":2,"isvol":1},
            {"rankid":"","rankname":"无真实 ID","ranktype":2,"isvol":1},
            {"rankid":991,"rankname":"无榜型","isvol":1}
        ]}}""".trimIndent()
        val rows = parseKgRankCatalog("<!--KG_TAG_RES_START-->$raw<!--KG_TAG_RES_END-->")
        assertEquals(2, rows.size)
        assertEquals("8888", rows[0].id)
        assertEquals("TOP500", rows[0].name)
        assertEquals(2, rows[0].rankType)
        assertEquals(500, rows[0].count)
        assertEquals("129300", rows[0].currentVolumeId)
        assertEquals(true, rows[0].hasVolumes)
        assertEquals("https://img.kugou.com/top.jpg", rows[0].cover)
        assertEquals("51341", rows[1].id)
        assertEquals(100, rows[1].count)
        assertEquals(0, parseKgRankCatalog("not-json").size)
    }

    @Test
    fun `parseKgLatestVolumeId selects newest official volume`() {
        val raw = """{"data":{"info":[{"year":2026,"vols":[
            {"volid":129300,"voltitle":"273期"},{"volid":129149,"voltitle":"272期"}
        ]},{"year":2025,"vols":[{"volid":90000}]}]}}""".trimIndent()
        assertEquals("129300", parseKgLatestVolumeId("<!--KG_TAG_RES_START-->$raw<!--KG_TAG_RES_END-->"))
        assertEquals("", parseKgLatestVolumeId("{}"))
    }

    @Test
    fun `parseKgRankSongs maps actual hash artists cover album and duration`() {
        val raw = """{"data":{"total":500,"info":[
            {"songname":"异想天开","filename":"肖战 - 异想天开","authors":[{"author_name":"肖战"}],"hash":"f957c93d8ea8d3ef2335ff72bb5e1585","album_id":"209339223","duration":197,"album_sizable_cover":"https://img.kugou.com/cover.jpg"},
            {"songname":"无 hash"},
            {"songname":"第二首","filename":"郁可唯 - 第二首","hash":"ABC123","trans_param":{"union_cover":"https://img.kugou.com/second.jpg"},"duration_high":225}
        ]}}""".trimIndent()
        val songs = parseKgRankSongs("<!--KG_TAG_RES_START-->$raw<!--KG_TAG_RES_END-->", 10)
        assertEquals(2, songs.size)
        assertEquals("F957C93D8EA8D3EF2335FF72BB5E1585", songs[0].id)
        assertEquals("肖战", songs[0].singer)
        assertEquals("209339223", songs[0].albumId)
        assertEquals(197000L, songs[0].duration)
        assertEquals("kg", songs[0].source)
        assertEquals("郁可唯", songs[1].singer)
        assertEquals(225000L, songs[1].duration)
        assertEquals(0, parseKgRankSongs("{}", 10).size)
    }
}
