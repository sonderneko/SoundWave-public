package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.cncnlp.soundwave.source.direct.parseKwSearch
import xyz.cncnlp.soundwave.source.direct.parseWySearch

/**
 * ACCEPT6（qqslow）：跨源候选池实证回归——样本形态取自 2026-11-04 真机
 * 诊断当晚的实拉接口应答（《青花瓷》/《反方向的钟》）：
 * - kw 池（r.s 单引号 JSON）：正版候选真实存在且字段完整，解析+严配
 *   必须放行；带 &nbsp; 实体的 Live 版必须被版本门拒（不是标题门误杀）。
 * - wy 池（cloudsearch）：受限出口下返回的是翻唱/剪辑 UGC 池，正版
 *   条目缺席——全部拒绝是匹配器的正确行为（拒因应为 title/artist）；
 *   同一解析器喂入正版条目时必须放行，证明拒因在池不在比对。
 * 匹配严格度不动：本测试钉的是「解析保真 + 严配对正版放行」。
 */
class CrossSourceFixtureTest {

    private fun txSong(name: String, singer: String, album: String, durationMs: Long) = Song(
        id = "tx-$name", name = name, singer = singer, source = "tx",
        album = album, duration = durationMs
    )

    // r.s 原始形态：单引号伪 JSON（parseKwJson 负责转义），名字带 &nbsp; 实体。
    private val kwPayload = """
        {'abslist':[
          {'SONGNAME':'反方向的钟','ARTIST':'周杰伦','ALBUM':'Jay','DURATION':'258','DC_TARGETID':'31546639','ARTISTID':'1563'},
          {'SONGNAME':'反方向的钟&nbsp;(Live)','ARTIST':'周杰伦','ALBUM':'','DURATION':'104','DC_TARGETID':'900001','ARTISTID':'1563'},
          {'SONGNAME':'青花瓷','ARTIST':'周杰伦','ALBUM':'我很忙','DURATION':'239','DC_TARGETID':'900002','ARTISTID':'1563'},
          {'SONGNAME':'反方向的钟','ARTIST':'周男孩','ALBUM':'','DURATION':'19','DC_TARGETID':'900003','ARTISTID':'0'}
        ]}
    """.trimIndent()

    @Test
    fun kwGenuineCandidatesPassStrictMatcher() {
        val songs = parseKwSearch(kwPayload, "反方向的钟 周杰伦", 30)
        assertEquals(4, songs.size)
        val fan = txSong("反方向的钟", "周杰伦", "Jay", 258_000L)
        assertNull(SongMatcher.rejectReason(fan, songs.first { it.name == "反方向的钟" && it.singer == "周杰伦" }))
        val qing = txSong("青花瓷", "周杰伦", "我很忙", 239_000L)
        assertNull(SongMatcher.rejectReason(qing, songs.first { it.name == "青花瓷" }))
    }

    @Test
    fun kwLiveVersionRejectedByVersionGateNotTitle() {
        val songs = parseKwSearch(kwPayload, "反方向的钟 周杰伦", 30)
        val fan = txSong("反方向的钟", "周杰伦", "Jay", 258_000L)
        val live = songs.first { it.duration == 104_000L }
        // &nbsp; 实体经 cleanSpace 解码、括号经 normalize 剥离后标题本应相等；
        // 拒因必须是 version（Live 标记），证明标题链路没有系统性误杀。
        assertEquals("version", SongMatcher.rejectReason(fan, live))
    }

    @Test
    fun kwImpostorSingerRejectedByArtistGate() {
        val songs = parseKwSearch(kwPayload, "反方向的钟 周杰伦", 30)
        val fan = txSong("反方向的钟", "周杰伦", "Jay", 258_000L)
        assertEquals("artist", SongMatcher.rejectReason(fan, songs.first { it.singer == "周男孩" }))
    }

    // cloudsearch 受限池形态：正版缺席，只有翻唱/剪辑/同名异人。
    private val wyPollutedPayload = """
        {"result":{"songCount":3,"songs":[
          {"id":"3381543730","name":"青花瓷（正式版）","ar":[{"id":"0","name":"周杰伦."},{"id":"0","name":"街道办GDC/欧阳耀莹."}],"al":{"name":"","picUrl":""},"dt":95037},
          {"id":"3440443771","name":"青花瓷","ar":[{"id":"0","name":"Jay"}],"al":{"name":"","picUrl":""},"dt":201973},
          {"id":"3368267047","name":"青花瓷 (原唱：周杰倫)","ar":[{"id":"0","name":"Hyperentropist"}],"al":{"name":"","picUrl":""},"dt":248318}
        ]}}
    """.trimIndent()

    @Test
    fun wyPollutedPoolIsCorrectlyRejectedWholesale() {
        val songs = parseWySearch(wyPollutedPayload, "青花瓷 周杰伦", 30)
        assertEquals(3, songs.size)
        val qing = txSong("青花瓷", "周杰伦", "我很忙", 239_000L)
        // 全灭且拒因只能是 title/artist（池里没有正版），与真机两轮分布一致。
        songs.forEach { cand ->
            val reason = SongMatcher.rejectReason(qing, cand)
            assertNotNull(reason)
            assertEquals("artist", reason)
        }
    }

    @Test
    fun wyGenuineEntryPassesWhenPresent() {
        val payload = """
            {"result":{"songCount":1,"songs":[
              {"id":"185811","name":"青花瓷","ar":[{"id":"6452","name":"周杰伦"}],"al":{"name":"我很忙","picUrl":""},"dt":239000}
            ]}}
        """.trimIndent()
        val songs = parseWySearch(payload, "青花瓷 周杰伦", 30)
        assertEquals(1, songs.size)
        val qing = txSong("青花瓷", "周杰伦", "我很忙", 239_000L)
        assertNull(SongMatcher.rejectReason(qing, songs.single()))
    }
}
