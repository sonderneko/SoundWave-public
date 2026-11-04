package xyz.cncnlp.soundwave.ui.player

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34])
class Batch2CoverToneTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val original=CoverToneCache.loader
    @Before fun reset(){CoverToneCache.clearForTest()}
    @After fun restore(){CoverToneCache.loader=original;CoverToneCache.clearForTest()}
    private fun art()=ColorDrawable(0xff538d70.toInt())
    @Test fun sameKeyConcurrentCallersLoadAndComputeOnce()=runBlocking {
        val loads=AtomicInteger();val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        CoverToneCache.loader={_,_->loads.incrementAndGet();entered.complete(Unit);gate.await();art()}
        val requests=(1..12).map { async {CoverToneCache.tonesFor(context,"fixture-same")} }
        entered.await();delay(100);gate.complete(Unit)
        val values=requests.awaitAll();assertTrue(values.all{it!=null&&it==values.first()});assertEquals(1,loads.get());assertEquals(1,CoverToneCache.computations)
    }
    @Test fun cancellationFromLoaderPropagatesRatherThanBecomingMiss()=runBlocking {
        CoverToneCache.loader={_,_->throw CancellationException("fixture-cancel")}
        var caught=false
        try{CoverToneCache.tonesFor(context,"fixture-cancel")}catch(_:CancellationException){caught=true}
        assertTrue("cancellation must propagate",caught)
    }
    @Test fun cancelledWaiterDoesNotCancelOwnerOrOtherWaiters()=runBlocking {
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();val loads=AtomicInteger()
        CoverToneCache.loader={_,_->loads.incrementAndGet();entered.complete(Unit);gate.await();art()}
        val owner=async{CoverToneCache.tonesFor(context,"fixture-waiter")};entered.await()
        val waiter=async{CoverToneCache.tonesFor(context,"fixture-waiter")};yield();waiter.cancelAndJoin()
        val other=async{CoverToneCache.tonesFor(context,"fixture-waiter")};yield();gate.complete(Unit)
        assertNotNull(owner.await());assertNotNull(other.await());assertEquals(1,loads.get())
    }
    @Test fun cancelledOwnerReleasesWaiterWithMissAndAllowsRetry()=runBlocking {
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        CoverToneCache.loader={_,_->entered.complete(Unit);gate.await();art()}
        val owner=async{CoverToneCache.tonesFor(context,"fixture-owner")};entered.await()
        val waiter=async{CoverToneCache.tonesFor(context,"fixture-owner")};yield();owner.cancelAndJoin()
        assertNull(withTimeout(2000){waiter.await()})
        CoverToneCache.loader={_,_->art()};assertNotNull(CoverToneCache.tonesFor(context,"fixture-owner"))
    }
    @Test fun ordinaryFailureAndNullDoNotPoisonSubsequentRequest()=runBlocking {
        CoverToneCache.loader={_,_->throw java.io.IOException("fixture")};assertNull(CoverToneCache.tonesFor(context,"fixture-retry"))
        CoverToneCache.loader={_,_->null};assertNull(CoverToneCache.tonesFor(context,"fixture-retry"))
        CoverToneCache.loader={_,_->art()};assertNotNull(CoverToneCache.tonesFor(context,"fixture-retry"))
    }
    @Test fun differentKeysRemainConcurrentAndCachedReadsSkipLoader()=runBlocking {
        val loads=AtomicInteger();val both=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        CoverToneCache.loader={_,_->if(loads.incrementAndGet()==2)both.complete(Unit);gate.await();art()}
        val a=async{CoverToneCache.tonesFor(context,"fixture-a")};val b=async{CoverToneCache.tonesFor(context,"fixture-b")}
        withTimeout(2000){both.await()};gate.complete(Unit);assertNotNull(a.await());assertNotNull(b.await())
        assertNotNull(CoverToneCache.tonesFor(context,"fixture-a"));assertEquals(2,loads.get())
    }
}
