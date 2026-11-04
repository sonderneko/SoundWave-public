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
class Round8CacheManagerIntegrationTest {
    @Test fun actualEnsureCoverCacheHitsDoNotRetainUniqueLocks() {
        val ctx=RuntimeEnvironment.getApplication() as Context
        val fileMethod=CacheManager::class.java.getDeclaredMethod("coverFile",Context::class.java,String::class.java).apply{isAccessible=true}
        val lockField=CacheManager::class.java.getDeclaredField("coverWriteLocks").apply{isAccessible=true}
        val locks=lockField.get(CacheManager) as BlockingKeyedOperationLock<*>
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        try {
            repeat(128){i->
                val url="https://cover-fixture.example/round8-$i.png"
                val file=fileMethod.invoke(CacheManager,ctx,url) as File
                file.parentFile!!.mkdirs();file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                try {assertEquals(file.absolutePath,CacheManager.ensureCover(ctx,url))}finally{file.delete()}
            }
            assertEquals(0,locks.trackedKeysForTests())
        }finally{bitmap.recycle()}
    }
}
