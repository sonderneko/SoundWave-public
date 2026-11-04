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
  val kw=parseKwNewAlbums(fixture("kw-shelves"),12)
  assertTrue(kg.size>=5); assertTrue(qq.size>=5);assertTrue(mg.size>=5);assertTrue(kw.isNotEmpty())
  assertTrue(kg.all{it.kind=="new_album" && it.source=="kg"})
  assertTrue(qq.all{it.kind=="new_album" && it.source=="tx"})
  assertTrue(mg.all{it.kind=="digital" && it.source=="mg" && it.id.startsWith("600")})
  assertTrue(kw.all{it.kind=="new_album" && it.source=="kw"})
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
