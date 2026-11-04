package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertTrue
import org.junit.Test

/** accept6-singercover：歌手页页头封面的尺寸口径——
 * kw 优先 pic500 大档（页头按屏宽×1.5 请求，pic300 必糊），无 pic500 时回落 pic300。 */
class ArtistDetailCoverTest {

    @Test fun kwArtistCoverPrefersPic500OverPic300() {
        val info = """{"code":200,"msg":"success","data":{"id":336,"name":"周杰伦","pic500":"https://img1.kuwo.cn/star/starheads/500/2.jpg","pic300":"https://img1.kuwo.cn/star/starheads/300/1.jpg"}}"""
        val songs = """{"code":200,"msg":"success","data":{"total":"1","list":[
          {"rid":228908,"musicrid":"MUSIC_228908","name":"青花瓷","artist":"周杰伦","artistid":336,"album":"我很忙","albumid":"1294","albumpic":"star/albumcover/1.jpg","duration":239,"formats":"MP3128|MP3H|ALFLAC"}
        ]}}"""
        val detail = checkNotNull(parseKwArtistDetail(info, songs, "336", "周杰伦"))
        assertTrue(detail.cover.contains("/starheads/500/"))
    }

    @Test fun kwArtistCoverFallsBackToPic300WhenNoPic500() {
        val info = """{"code":200,"msg":"success","data":{"id":336,"name":"周杰伦","pic300":"https://img1.kuwo.cn/star/starheads/300/1.jpg"}}"""
        val songs = """{"code":200,"msg":"success","data":{"total":"1","list":[
          {"rid":228908,"musicrid":"MUSIC_228908","name":"青花瓷","artist":"周杰伦","artistid":336,"album":"我很忙","albumid":"1294","albumpic":"star/albumcover/1.jpg","duration":239,"formats":"MP3128|MP3H|ALFLAC"}
        ]}}"""
        val detail = checkNotNull(parseKwArtistDetail(info, songs, "336", "周杰伦"))
        assertTrue(detail.cover.contains("/starheads/300/"))
    }

}
