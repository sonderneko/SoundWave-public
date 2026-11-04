package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migrateLegacyKuwoRankId
import xyz.cncnlp.soundwave.source.direct.KW_BANGS
import xyz.cncnlp.soundwave.source.direct.MG_RANKS
import xyz.cncnlp.soundwave.source.direct.parseKwBangPage
import xyz.cncnlp.soundwave.source.direct.parseKwRankCatalog
import xyz.cncnlp.soundwave.source.direct.miguColumnIdForRankId
import xyz.cncnlp.soundwave.source.direct.parseMgRankCatalog
import xyz.cncnlp.soundwave.source.direct.parseMgRankDetail
import xyz.cncnlp.soundwave.source.direct.parseMgSearch
import xyz.cncnlp.soundwave.source.direct.parseTxToplistDetail

/** Cover/rank regression cases based on live provider metadata; never substitutes another song's cover. */
class CoverPipelineTest {

    private fun song(cover: String, id: String = "1", name: String = "n") =
        Song(id = id, name = name, singer = "s", source = "kw", cover = cover)

    @Test
    fun `kuwo euro board preserves returned first track cover url`() {
        val firstTrackCover = "https://img1.kuwo.cn/star/albumcover/500/s4s95/35/836441677.png"
        val board = RankBoard(
            name = "酷我欧美榜", source = "kw",
            songs = listOf(song(firstTrackCover, "15213376", "That Girl"), song("later-track.jpg", "2")),
            cover = "official-board.jpg",
        )
        assertEquals(listOf(firstTrackCover, "later-track.jpg"), rankCardCoverPool(board.songs, board.source, board.name))
        assertEquals(firstTrackCover, board.previewCover)
    }

    @Test
    fun `missing first track cover falls back to chart metadata not a different song`() {
        val board = RankBoard(
            name = "酷我欧美榜", source = "kw",
            songs = listOf(song("", "15213376", "That Girl"), song("later-track.jpg", "2")),
            cover = "official-board.jpg",
        )
        assertEquals(listOf("", "later-track.jpg"), rankCardCoverPool(board.songs, board.source, board.name))
        assertEquals("official-board.jpg", board.previewCover)
    }

    @Test
    fun `kuwo live catalog maps sourceid not menu id and includes missing official charts`() {
        val raw = """{"code":200,"data":[{"name":"官方","list":[{"sourceid":"16","id":"489927","name":"酷我热歌榜","pic":"https://zimg.kuwo.cn/bang/hot.png"},{"sourceid":"158","id":"490022","name":"短视频酷我排行榜","pic":"https://zimg.kuwo.cn/bang/short.png"}]},{"name":"语言","list":[{"sourceid":"104","id":"509833","name":"酷我华语榜","pic":"https://zimg.kuwo.cn/bang/cn.png"},{"sourceid":"22","id":"509835","name":"酷我欧美榜","pic":"https://zimg.kuwo.cn/bang/eu.png"}]}]}"""
        val parsed = parseKwRankCatalog(raw)
        assertEquals(4, parsed.size)
        assertEquals("16", parsed.first().bangId)
        assertEquals("kw_104", parsed.single { it.name == "酷我华语榜" }.id)
        assertEquals("104", parsed.single { it.name == "酷我华语榜" }.bangId)
        assertEquals("kw_22", parsed.single { it.name == "酷我欧美榜" }.id)
        assertEquals("短视频酷我排行榜", parsed.single { it.bangId == "158" }.name)
    }

    @Test
    fun `kuwo official fallback ids match live menu`() {
        assertTrue(KW_BANGS.any { it.bangId == "104" && it.name == "酷我华语榜" })
        assertTrue(KW_BANGS.any { it.bangId == "22" && it.name == "酷我欧美榜" })
        assertTrue(KW_BANGS.any { it.bangId == "158" && it.name == "短视频酷我排行榜" })
        assertTrue(KW_BANGS.any { it.bangId == "12" && it.name.contains("Billboard") })
        assertTrue(KW_BANGS.any { it.bangId == "16" && it.name == "酷我热歌榜" })
        assertTrue(KW_BANGS.any { it.bangId == "17" && it.name == "酷我新歌榜" })
        val liveMiguColumns = setOf("83048887", "83049014", "83048867", "83049056", "76557745", "83176390", "27553319", "27186466", "27553408")
        assertTrue(liveMiguColumns.all { id -> MG_RANKS.any { it.columnId == id } })
        val verifiedMiguSupplements = setOf("75959118", "23189399", "23189800")
        assertTrue(verifiedMiguSupplements.all { id -> MG_RANKS.any { it.columnId == id } })
        assertTrue(MG_RANKS.any { it.id == "mg_trend" && it.columnId == "75959118" })
        assertTrue(MG_RANKS.any { it.id == "mg_guofeng" && it.columnId == "83176390" && it.name.contains("国风热歌榜") })
    }

    @Test
    fun `old stored kuwo selections migrate to their corrected official ids`() {
        assertEquals("kw_16", migrateLegacyKuwoRankId("kw_17"))
        assertEquals("kw_17", migrateLegacyKuwoRankId("kw_16"))
        assertEquals("kw_104", migrateLegacyKuwoRankId("kw_158"))
        assertEquals("kw_22", migrateLegacyKuwoRankId("kw_12"))
        assertEquals("kw_64", migrateLegacyKuwoRankId("kw_22"))
        assertEquals("kw_145", migrateLegacyKuwoRankId("kw_184"))
        assertEquals("kw_16", migrateLegacyKuwoRankId("kw_145"))
    }

    @Test
    fun `current kuwo id schema does not remigrate swapped ids`() {
        assertEquals("kw_16", migrateLegacyKuwoRankId("kw_16", KW_RANK_ID_SCHEMA_VERSION))
        assertEquals("kw_17", migrateLegacyKuwoRankId("kw_17", KW_RANK_ID_SCHEMA_VERSION))
        assertEquals("kw_104", migrateLegacyKuwoRankId("kw_104", KW_RANK_ID_SCHEMA_VERSION))
    }

    @Test
    fun `kuwo chinese chart parses official 104 first track cover`() {
        val raw = """{"data":{"name":"酷我华语榜","img":"https://img4.kuwo.cn/star/upload/12/12/1539055460189_.png","num":"100","musicList":[{"rid":624683929,"musicrid":"MUSIC_624683929","name":"山风山风等等我","artist":"万海东","album":"山风","pic":"https://img2.kuwo.cn/star/albumcover/500/s4s81/95/2497366108.jpg","albumpic":"https://img2.kuwo.cn/star/albumcover/500/s4s81/95/2497366108.jpg"}]}}"""
        val parsed = parseKwBangPage(raw)
        assertEquals("酷我华语榜", parsed.name)
        assertEquals(100, parsed.total)
        assertEquals("624683929", parsed.songs.single().id)
        assertEquals("山风山风等等我", parsed.songs.single().name)
        assertEquals("https://img1.kuwo.cn/star/albumcover/1080/s4s81/95/2497366108.jpg", parsed.songs.single().cover) // r658c D#5：img2 不可达→img1 并升 1080
    }

    @Test
    fun `kuwo euro chart upgrades actual That Girl track to verified same-resource 1080 cover`() {
        val raw = """{"data":{"name":"酷我欧美榜","img":"https://img3.kuwo.cn/star/upload/6/6/1539055330182_.png","num":"100","musicList":[{"rid":15213376,"musicrid":"MUSIC_15213376","name":"That Girl","artist":"Olly Murs","album":"24 HRS","pic":"https://img1.kuwo.cn/star/albumcover/500/s4s95/35/836441677.png","albumpic":"https://img1.kuwo.cn/star/albumcover/500/s4s95/35/836441677.png"}]}}"""
        val parsed = parseKwBangPage(raw)
        assertEquals("酷我欧美榜", parsed.name)
        assertEquals(15213376L.toString(), parsed.songs.single().id)
        assertEquals("That Girl", parsed.songs.single().name)
        assertEquals("https://img1.kuwo.cn/star/albumcover/1080/s4s95/35/836441677.png", parsed.songs.single().cover)
    }

    @Test
    fun `short video chart 158 preserves Shanghai 1943 true track cover`() {
        val raw = """{"data":{"name":"短视频酷我排行榜","img":"https://kwimg2.kuwo.cn/star/upload/69/77/1782993869979_.png","num":"100","musicList":[{"rid":666053,"musicrid":"MUSIC_666053","name":"上海一九四三","artist":"周杰伦","album":"范特西","pic":"https://img4.kuwo.cn/star/albumcover/500/s4s36/70/1529234453.jpg","albumpic":"https://img4.kuwo.cn/star/albumcover/500/s4s36/70/1529234453.jpg"}]}}"""
        val parsed = parseKwBangPage(raw)
        assertEquals("短视频酷我排行榜", parsed.name)
        assertEquals("666053", parsed.songs.single().id)
        assertEquals("上海一九四三", parsed.songs.single().name)
        assertEquals("周杰伦", parsed.songs.single().singer)
        assertEquals("https://img4.kuwo.cn/star/albumcover/1080/s4s36/70/1529234453.jpg", parsed.songs.single().cover) // r658c D#5：img4 同路径 1080 已实测
    }

    @Test
    fun `migu official rank index parses live groups names ids and true covers`() {
        val raw = """{"code":"000000","data":{"contents":[{"view":"ZJ-RANK-SPECIAL","style":"咪咕特色榜","contents":[{"rankId":"83048887","rankName":"全网热歌榜","imageUrl":"https://d.musicapp.migu.cn/data/oss/column/00/1w/yi/2981744541a345958d285c75e8fa907c.webp"}]},{"view":"ZJ-RANK-NORMAL","style":"咪咕尖叫榜","contents":[{"rankId":"83176390","rankName":"国风热歌榜","imageUrl":"https://d.musicapp.migu.cn/data/oss/resource/00/60/39/1729bf4f457d4b71bf83f5646172fbfc.webp"},{"rankId":"27553319","rankName":"新歌榜","imageUrl":"https://d.musicapp.migu.cn/data/oss/resource/00/60/3v/7160a8a79cfe48629f01ae1011d259a4.webp"}]}]}}"""
        val boards = parseMgRankCatalog(raw)
        assertEquals(listOf("全网热歌榜", "国风热歌榜", "新歌榜"), boards.map { it.name })
        assertEquals("mg_83048887", boards[0].id)
        assertEquals("mg_guofeng", boards[1].id)
        assertEquals("83176390", boards[1].columnId)
        assertEquals("https://d.musicapp.migu.cn/data/oss/resource/00/60/39/1729bf4f457d4b71bf83f5646172fbfc.webp", boards[1].cover)
        assertEquals("83048887", miguColumnIdForRankId(boards[0].id))
        assertEquals("83176390", miguColumnIdForRankId("mg_guofeng"))
        assertEquals("75959118", miguColumnIdForRankId("mg_trend"))
        assertEquals("99999999", miguColumnIdForRankId("mg_99999999"))
    }

    @Test
    fun `migu parser keeps live title count content id and largest real cover`() {
        val raw = """{"code":"000000","columnInfo":{"columnTitle":"音乐风向榜","columnId":"75959118","contentsCount":100,"contents":[{"objectInfo":{"contentId":"600930000002808896","songId":"1144055009","songName":"潮","singer":"任嘉伦|欧豪","album":"潮","copyrightId":"60004000676","albumImgs":[{"imgSizeType":"01","img":"https://d.musicapp.migu.cn/data/small.webp"},{"imgSizeType":"03","img":"https://d.musicapp.migu.cn/data/large.webp"},{"imgSizeType":"02","img":"https://d.musicapp.migu.cn/data/medium.webp"}]}}]}}"""
        val parsed = parseMgRankDetail(raw, limit = 8)
        assertEquals("音乐风向榜", parsed.title)
        assertEquals(100, parsed.total)
        assertEquals(1, parsed.songs.size)
        assertEquals("600930000002808896", parsed.songs.single().id)
        assertEquals("https://d.musicapp.migu.cn/data/large.webp", parsed.songs.single().cover)
    }

    @Test
    fun `migu 83176390 uses provider title Guofeng hot songs and actual first cover`() {
        val raw = """{"code":"000000","columnInfo":{"columnTitle":"国风热歌榜","columnId":"83176390","contentsCount":100,"contents":[{"objectInfo":{"contentId":"600930000002838040","songId":"1144068946","songName":"月亮桥","singer":"周深","albumImgs":[{"imgSizeType":"01","img":"https://d.musicapp.migu.cn/data/small.webp"},{"imgSizeType":"03","img":"https://d.musicapp.migu.cn/data/guofeng.webp"}]}}]}}"""
        val parsed = parseMgRankDetail(raw, limit = 8)
        assertEquals("国风热歌榜", parsed.title)
        assertEquals(100, parsed.total)
        assertEquals("600930000002838040", parsed.songs.single().id)
        assertEquals("月亮桥", parsed.songs.single().name)
        assertEquals("https://d.musicapp.migu.cn/data/guofeng.webp", parsed.songs.single().cover)
    }

    @Test
    fun `qq parser rejects zero id rows but preserves real playable id rows`() {
        val zeroOnly = """{"songlist":[{"data":{"songid":0,"songname":"无可播放 ID","albummid":"album0","singer":[{"name":"歌手"}]}}]}"""
        assertTrue(parseTxToplistDetail(zeroOnly, limit = 10).isEmpty())
        val playable = """{"songlist":[{"data":{"songid":123456,"songmid":"M000000000000","songname":"真实歌曲","albummid":"album1","singer":[{"name":"歌手"}]}}]}"""
        val songs = parseTxToplistDetail(playable, limit = 10)
        assertEquals(1, songs.size)
        assertEquals("M000000000000", songs.single().id)
        assertEquals("真实歌曲", songs.single().name)
    }

    @Test
    fun `other boards keep first cover and chart cover fallback`() {
        assertEquals("a.jpg", RankBoard(name = "酷我热歌榜", source = "kw", songs = listOf(song("a.jpg"), song("b.jpg"))).previewCover)
        assertEquals("w.jpg", RankBoard(name = "欧美热歌榜", source = "wy", songs = listOf(song("w.jpg"))).previewCover)
        assertEquals("board.jpg", RankBoard(name = "酷我欧美榜", source = "kw", cover = "board.jpg").previewCover)
    }

    @Test
    fun `netease playlist cover discards legacy 140px transform chain`() {
        val raw = "http://p1.music.126.net/opaque/109951168690718998.jpg?imageView=1&thumbnail=800y800&enlarge=1%7CimageView=1&thumbnail=140y140&"
        assertEquals(
            "https://p1.music.126.net/opaque/109951168690718998.jpg?param=1080y1080",
            normalizeCoverUrl(raw),
        )
    }

    @Test
    fun `normalize upgrades only live-verified cover sizes and preserves unknown legacy paths`() {
        assertEquals("https://p1.music.126.net/a.jpg?param=1080y1080", normalizeCoverUrl("http://p1.music.126.net/a.jpg?param=200y200"))
        assertEquals("https://p1.music.126.net/b.jpg?param=1080y1080", normalizeCoverUrl("http://p1.music.126.net/b.jpg"))
        assertEquals("https://img.migu.cn/a_02.jpg", normalizeCoverUrl("https://img.migu.cn/a_02.jpg"))
        assertEquals("https://imgcache.qq.com/music/photo_new/T002R800x800M000X.jpg", normalizeCoverUrl("http://imgcache.qq.com/music/photo_new/T002R300x300M000X.jpg"))
        assertEquals("https://img1.kuwo.cn/star/albumcover/400/95/6604395.jpg", normalizeCoverUrl("http://img1.kuwo.cn/star/albumcover/400/95/6604395.jpg"))
        val liveKuwoAlbum = "http://img1.kuwo.cn/star/albumcover/120/s3s94/93/211513640.jpg"
        assertEquals(
            "https://img1.kuwo.cn/star/albumcover/1080/s3s94/93/211513640.jpg",
            normalizeCoverUrl(liveKuwoAlbum)
        )
        assertEquals(
            "https://img1.kuwo.cn/star/albumcover/500/s3s94/93/211513640.jpg",
            normalizeCoverUrl(liveKuwoAlbum, targetSizePx = 324)
        )
        val liveKwcdnPlaylist = "http://img1.kwcdn.kuwo.cn/star/albumcover/320/s4s49/71/370021123.jpg"
        assertEquals(
            "http://img1.kwcdn.kuwo.cn/star/albumcover/1080/s4s49/71/370021123.jpg",
            normalizeCoverUrl(liveKwcdnPlaylist)
        )
        assertEquals(
            "http://img1.kwcdn.kuwo.cn/star/albumcover/500/s4s49/71/370021123.jpg",
            normalizeCoverUrl(liveKwcdnPlaylist, targetSizePx = 324)
        )
        assertEquals(
            "http://img1.kwcdn.kuwo.cn/star/userpl2015/81/23/1568684821020_182253281_500.jpg",
            normalizeCoverUrl("http://img1.kwcdn.kuwo.cn/star/userpl2015/81/23/1568684821020_182253281_240.jpg"),
        )
        assertEquals(
            "http://img1.kwcdn.kuwo.cn/star/userpl2015/81/23/1568684821020_182253281_500.jpg",
            normalizeCoverUrl("https://img1.kwcdn.kuwo.cn/star/userpl2015/81/23/1568684821020_182253281_240.jpg"),
        )
        assertEquals(
            "https://img1.kuwo.cn/star/userpl2015/5/75/1653733628864_541111305_240.jpg",
            normalizeCoverUrl("http://img1.kuwo.cn/star/userpl2015/5/75/1653733628864_541111305_240.jpg"),
        )
        assertEquals("https://img.cmvideo.cn/pics/a_02.jpg", normalizeCoverUrl("https://img.cmvideo.cn/pics/a_02.jpg"))
    }

    @Test
    fun `normalize maps TLS-dead kuwo sycdn hosts onto the kuwo mirror`() {
        // img 字段的 sycdn 主机 HTTPS 实测 TLS 失败（curl 000），同路径在 kuwo.cn
        // 镜像（hts_img）实测 200；归一后与 hts_img 直达的结果逐字一致。
        assertEquals(
            "https://img4.kuwo.cn/star/albumcover/1080/s4s86/95/3059703046.jpg",
            normalizeCoverUrl("http://img4.sycdn.kuwo.cn/star/albumcover/240/s4s86/95/3059703046.jpg"),
        )
        assertEquals(
            "https://img4.kuwo.cn/star/albumcover/1080/s4s86/95/3059703046.jpg",
            normalizeCoverUrl("https://img4.sycdn.kuwo.cn/star/albumcover/240/s4s86/95/3059703046.jpg"),
        )
        // img3 先过 sycdn 映射、再走既有 img2/img3→img1 迁移规则。
        assertEquals(
            "https://img1.kuwo.cn/star/albumcover/1080/s4s86/95/3059703046.jpg",
            normalizeCoverUrl("http://img3.sycdn.kuwo.cn/star/albumcover/240/s4s86/95/3059703046.jpg"),
        )
    }

    @Test
    fun `cover cache signature accepts common image formats and rejects provider error bodies`() {
        assertTrue(isSupportedCoverImageSignature(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)))
        assertTrue(isSupportedCoverImageSignature(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        val webp = ByteArray(12).also {
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            "WEBP".toByteArray(Charsets.US_ASCII).copyInto(it, 8)
        }
        assertTrue(isSupportedCoverImageSignature(webp))
        assertFalse(isSupportedCoverImageSignature("<html>rate limited</html>".toByteArray()))
        assertFalse(isSupportedCoverImageSignature("{\"error\":\"not found\"}".toByteArray()))
    }

    @Test
    fun `detail hero memory cache key is isolated by target pixels and normalized url`() {
        val url = "https://p4.music.126.net/example/cover.jpg"
        val small = detailHeroCoverMemoryCacheKey(url, 256)
        val large = detailHeroCoverMemoryCacheKey(url, 512)
        val other = detailHeroCoverMemoryCacheKey("https://p4.music.126.net/example/other.jpg", 256)
        assertTrue(small.startsWith("detail-hero-v1:256x256:"))
        assertTrue(large.startsWith("detail-hero-v1:512x512:"))
        assertTrue(small != large)
        assertTrue(small != other)
    }

    @Test
    fun `qq playlist cover uses only verified same-id qpic size and preserves returned music-file url`() {
        assertEquals(
            "https://qpic.y.qq.com/music_cover/opaque-id/600",
            normalizeCoverUrl("http://qpic.y.qq.com/music_cover/opaque-id/300"),
        )
        assertEquals(
            "https://qpic.y.qq.com/music_cover/opaque-id/600?n=1",
            normalizeCoverUrl("http://qpic.y.qq.com/music_cover/opaque-id/300?n=1"),
        )
        assertEquals(
            "https://p.qpic.cn/music_cover/opaque-id/600",
            normalizeCoverUrl("http://p.qpic.cn/music_cover/opaque-id/300"),
        )
        val returned = "https://music-file.y.qq.com/songlist/u/opaque/cover_96964.jpg"
        assertEquals(returned, normalizeCoverUrl(returned))
    }

    @Test
    fun `singer avatar t001 upgraded to 800 keeping prefix`() {
        assertEquals("https://y.gtimg.cn/music/photo_new/T001R800x800M000MID_11.jpg", normalizeCoverUrl("http://y.gtimg.cn/music/photo_new/T001R150x150M000MID_11.jpg"))
        assertTrue(normalizeCoverUrl("http://y.gtimg.cn/music/photo_new/T001R500x500M000MID_1.jpg").contains("T001R800x800M"))
    }

    @Test
    fun `migu search picks largest real imgSizeType without guessing another suffix`() {
        val raw = """{"songResultData":{"result":[{"name":"晴天","singers":[{"name":"周杰伦"}],"contentId":"c1","imgItems":[{"imgSizeType":"2","img":"http://img.migu.cn/a_02.jpg"},{"imgSizeType":"3","img":"http://img.migu.cn/a_03.jpg"}]}]}}"""
        val songs = parseMgSearch(raw, "晴天")
        assertEquals(1, songs.size)
        assertEquals("https://img.migu.cn/a_03.jpg", songs[0].cover)
    }
}
