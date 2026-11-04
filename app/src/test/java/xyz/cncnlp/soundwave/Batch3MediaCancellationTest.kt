package xyz.cncnlp.soundwave

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Batch3MediaCancellationTest {
    @Test fun cancellationDuringBlockingDecryptDoesNotLeakReturnedLease() = runBlocking {
        val ctx=RuntimeEnvironment.getApplication()
        val key="batch3-${UUID.randomUUID()}"
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val dir=File(ctx.cacheDir,"media_decrypt")
        val id=MediaDecryptCache.cacheFileId(key)
        val job=launch(Dispatchers.Default) {
            MediaDecryptCache.prepareWithLease(ctx,"https://media.invalid/test.mflac",key,"mflac",KeyedOperationLock(),
                { _, out -> out.writeText("cipher"); true },
                { _, out, _ -> entered.countDown(); check(release.await(10,TimeUnit.SECONDS)); out.writeText("plain"); "flac" })?.close()
        }
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            assertEquals(2,MediaCacheLeaseManager.leasePathsUnder(dir).count{it.contains(id)})
            job.cancel(); release.countDown(); withTimeout(10_000){job.join()}
            assertTrue("cancelled return must release every staging/completed lease",MediaCacheLeaseManager.leasePathsUnder(dir).none{it.contains(id)})
            assertTrue("cancelled preparation must leave no published or partial artifact",dir.listFiles().orEmpty().none{it.name.contains(id)})
        } finally { release.countDown(); job.cancelAndJoin() }
    }
    @Test fun cancellationAtDispatcherReturnClosesPublishedLease() = runBlocking {
        val ctx=RuntimeEnvironment.getApplication()
        val key="batch3-handoff-${UUID.randomUUID()}"
        val id=MediaDecryptCache.cacheFileId(key)
        val dir=File(ctx.cacheDir,"media_decrypt")
        val queue=LinkedBlockingQueue<Runnable>()
        val dispatcher=object:CoroutineDispatcher(){
            override fun dispatch(context:CoroutineContext,block:Runnable){queue.put(block)}
        }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var callerReceivedLease=false
        val job=scope.launch {
            MediaDecryptCache.prepareWithLease(ctx,"https://media.invalid/test.mflac",key,"mflac",KeyedOperationLock(),
                { _,out->out.writeText("cipher");true },
                { _,out,_->entered.countDown();check(release.await(10,TimeUnit.SECONDS));out.writeText("plain");"flac" })?.use {
                callerReceivedLease=true
            }
        }
        try {
            checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run()
            assertTrue(entered.await(10,TimeUnit.SECONDS));release.countDown()
            // IO has published the file and enqueued the result, but the caller has
            // not resumed. Cancel exactly at withContext's prompt-return boundary.
            val resume=checkNotNull(queue.poll(10,TimeUnit.SECONDS))
            assertEquals(1,MediaCacheLeaseManager.leasePathsUnder(dir).count{it.contains(id)})
            job.cancel();resume.run();withTimeout(10_000){job.join()}
            assertFalse(callerReceivedLease)
            assertTrue(MediaCacheLeaseManager.leasePathsUnder(dir).none{it.contains(id)})
            dir.listFiles().orEmpty().filter{it.name.contains(id)}.forEach {
                assertTrue("unclaimed completed cache must become removable",MediaCacheLeaseManager.deleteIfUnleased(it))
            }
        } finally {release.countDown();scope.cancel()}
    }

}
