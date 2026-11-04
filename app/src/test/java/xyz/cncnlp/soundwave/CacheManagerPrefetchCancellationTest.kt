package xyz.cncnlp.soundwave

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import xyz.cncnlp.soundwave.prefetch.SharedPrefetchWorkPool

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheManagerPrefetchCancellationTest {
    @Test
    fun `leaving home cancels fake cover downloads and releases shared admission`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = CountDownLatch(2)
        val interrupted = CountDownLatch(2)
        CacheManager.coverPrefetchActionForTest = { _, _ ->
            started.countDown()
            try {
                CountDownLatch(1).await()
            } catch (cancelled: InterruptedException) {
                interrupted.countDown()
                Thread.currentThread().interrupt()
                throw cancelled
            }
        }
        val urls = (1..40).map { "https://fake.invalid/cover-$it.jpg" }
        val pageEffect = launch { CacheManager.ensureCovers(context, urls) }

        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            val saturated = SharedPrefetchWorkPool.snapshot()
            assertTrue("cover warming is limited to its reserved UI lane", saturated.uiRunning <= 2)
            assertTrue("active work is globally bounded", saturated.running <= 3)
            assertTrue("shared admitted work is bounded", saturated.admitted <= 16)
            assertTrue("UI work cannot consume playback-reserved admission", saturated.uiAdmitted <= 12)

            pageEffect.cancelAndJoin()
            assertTrue("cancellation interrupts active fake downloads", interrupted.await(5, TimeUnit.SECONDS))
            assertTrue("page cancellation returns every shared slot", SharedPrefetchWorkPool.snapshot().admitted == 0)
        } finally {
            pageEffect.cancelAndJoin()
            CacheManager.coverPrefetchActionForTest = null
        }
    }
}
