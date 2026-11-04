package xyz.cncnlp.soundwave.source.direct
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.normalizeCoverUrl
class Round6CatalogTest {
 private fun fixture(n:String)=javaClass.getResource("/round6/$n.json")!!.readText()
 @Test fun currentRealResponsesKeepSeparateAlbumSections() {
  val kg=parseKgNewAlbums(fixture("kg-new-current"),12)
  val qq=parseTxNewAlbums(fixture("qq-new"),12)
  val mg=parseMgDigitalAlbums(fixture("mg-h5-digital-current"),12)
  // accept6-kwfix: kw 的新碟参与已按用户预授权摘除（disc/shelves 货架长期只有
  // 1 张特辑、无替代端点）；kw 不再出现在新碟断言里，删除契约见
  // scripts/kw-disc-shelves-removed-guard.sh。
  assertTrue(kg.size>=5); assertTrue(qq.size>=5);assertTrue(mg.size>=5)
  assertTrue(kg.all{it.kind=="new_album" && it.source=="kg"})
  assertTrue(qq.all{it.kind=="new_album" && it.source=="tx"})
  assertTrue(mg.all{it.kind=="digital" && it.source=="mg" && it.id.startsWith("600")})
  val digital=parseKgDigitalAlbums(javaClass.getResource("/round5/kg-digital.json")!!.readText(),12)
  val normalized=normalizeDiscoverCards(digital+kg,"kg",12)
  assertEquals(digital.size,normalized.count{it.kind=="digital"})
  assertEquals(kg.size,normalized.count{it.kind=="new_album"})
  val cover="http://img4.kuwo.cn/star/albumcover/300/96/88/48263887.jpg"
  assertEquals("https://img4.kuwo.cn/star/albumcover/1080/96/88/48263887.jpg",normalizeCoverUrl(cover))
 }
 @Test fun errorsNeverBecomeAlbums() {
  assertTrue(parseMgDigitalAlbums("{\"code\":\"200000\",\"info\":\"no template\"}",12).isEmpty())
  assertTrue(parseKgNewAlbums("Access Deny",12).isEmpty())
  assertTrue(parseTxNewAlbums("{\"code\":500}",12).isEmpty())
 }
}
