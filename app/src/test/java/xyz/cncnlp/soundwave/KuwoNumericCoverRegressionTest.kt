package xyz.cncnlp.soundwave

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class KuwoNumericCoverRegressionTest {
    private val actual = "https://img1.kuwo.cn/star/albumcover/120/72/30/1353970321.jpg"
    private val high = "https://img1.kuwo.cn/star/albumcover/1080/72/30/1353970321.jpg"
    @Test fun numericSearchCoverUsesVerifiedSameArtworkHighResolution() {
        assertEquals(high, normalizeCoverUrl(actual))
        assertEquals(high, normalizeCoverUrl(actual, 1080))
        assertEquals(high+"?cache=1", normalizeCoverUrl(actual+"?cache=1"))
        assertEquals(high, normalizeCoverUrl(high))
    }
    @Test fun unknownLegacyAndOtherHostsAreNotRewritten() {
        val legacy=actual.replace("/120/", "/95/")
        assertEquals(legacy, normalizeCoverUrl(legacy))
        val other=actual.replace("img1.kuwo.cn", "images.example")
        assertEquals(other, normalizeCoverUrl(other))
    }
    @Test fun actualCoverModelBypassesOld120pxDiskFile() {
        val ctx=RuntimeEnvironment.getApplication() as Context
        val method=CacheManager::class.java.getDeclaredMethod("coverFile",Context::class.java,String::class.java).apply{isAccessible=true}
        val old=method.invoke(CacheManager,ctx,actual) as File
        val fresh=method.invoke(CacheManager,ctx,high) as File
        val bitmap=Bitmap.createBitmap(120,120,Bitmap.Config.ARGB_8888)
        try {
            fresh.delete();old.parentFile!!.mkdirs();old.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            assertNotEquals(old.absolutePath, fresh.absolutePath)
            assertEquals(high,CacheManager.coverModel(ctx,actual,targetSizePx=1080))
            assertEquals(high,CacheManager.coverModel(ctx,actual))
        }finally{old.delete();fresh.delete();bitmap.recycle()}
    }
    @Test fun realSearchResponseFieldsReachHighResolutionCacheModel() {
        // Reduced fixture from the current public response; these are the observed ID and artwork.
        val raw="""{"abslist":[{"MUSICRID":"MUSIC_208068689","NAME":"人世间","ARTIST":"雷佳","DURATION":"253","ALBUM":"人世间 电视剧原声带","web_albumpic_short":"120/72/30/1353970321.jpg"}]}"""
        val song=xyz.cncnlp.soundwave.source.direct.parseKwSearch(raw,"人世间",20).single()
        assertEquals(actual,song.cover)
        assertEquals(high,CacheManager.coverModel(RuntimeEnvironment.getApplication(),song.cover,targetSizePx=1080))
    }
    @Test fun newAlbumParserDoesNotTruncateValidRowsToOneOrRelabelDigital() {
        // Synthetic parser fixture only: not evidence of additional live catalogue availability.
        val raw="""{"code":200,"data":{"albumList":[{"albumid":"fixture-1","album":"first","pic":"https://img4.kuwo.cn/star/albumcover/300/19/18/3563632431.jpg"},{"albumid":"fixture-2","album":"second"},{"albumid":"fixture-1","album":"duplicate"}]}}"""
        val cards=xyz.cncnlp.soundwave.source.direct.parseKwNewAlbums(raw,30)
        assertEquals(listOf("fixture-1","fixture-2"),cards.map{it.id})
        assertTrue(cards.all{it.kind=="new_album" && it.source=="kw"})
        assertEquals(1,xyz.cncnlp.soundwave.source.direct.parseKwNewAlbums(raw,1).size)
    }

}
