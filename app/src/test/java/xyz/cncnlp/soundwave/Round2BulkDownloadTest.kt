package xyz.cncnlp.soundwave

import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class Round2BulkDownloadTest {
    @Test fun bulk600PublishesOnceAndPreservesUpdatesAndRemoval() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        prefs.edit().remove("downloads_v1").commit()
        val publications = AtomicInteger()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "downloads_v1") publications.incrementAndGet() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
        val items = (0 until 600).map { i -> DownloadItem(song=Song(id="mock-$i",name="Local fixture $i",singer="Test",source="local"),path="pending:$i",size=0,time=i.toLong(),status="queued",quality="320k",taskId="fixture-$i") }
        val t = System.nanoTime()
        LocalStore.addDownloads(context, items)
        shadowOf(Looper.getMainLooper()).idle()
        val elapsed = (System.nanoTime()-t)/1_000_000
        assertEquals(600, LocalStore.loadDownloads(context).size)
        assertEquals(1, publications.get())
        LocalStore.addDownloads(context, items.map { it.copy(status="paused",error="已暂停") })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, publications.get())
        assertTrue(LocalStore.loadDownloads(context).all { it.status=="paused" })
        LocalStore.removeDownloads(context, items)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, LocalStore.loadDownloads(context).size)
        assertEquals(3, publications.get())
        println("ROUND2_BULK_600_MS=$elapsed; add/pause/remove each one record publication; no network")
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            val edit = prefs.edit()
            if (previous == null) edit.remove("downloads_v1") else edit.putString("downloads_v1", previous)
            edit.commit()
        }
    }
    @Test fun idlessSongsWithLossyPlaybackKeyRemainSeparateInDownloadStore() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        try {
            prefs.edit().remove("downloads_v1").commit()
            val spaced = Song(id="",name="A B",singer="Artist",source="wy",duration=180_000L)
            val compact = spaced.copy(name="AB")
            assertEquals(spaced.key(), compact.key())
            val spacedId = DownloadScheduler.taskId(spaced, "320k")
            val compactId = DownloadScheduler.taskId(compact, "320k")
            LocalStore.addDownloads(context, listOf(
                DownloadItem(song=spaced,path="pending:$spacedId",size=0,time=1,status="queued",quality="320k",taskId=spacedId),
                DownloadItem(song=compact,path="pending:$compactId",size=0,time=2,status="queued",quality="320k",taskId=compactId)
            ))

            assertEquals(setOf(spacedId, compactId), LocalStore.loadDownloads(context).map { it.taskId }.toSet())
            assertEquals(2, LocalStore.loadDownloads(context).size)
        } finally {
            val edit=prefs.edit()
            if (previous==null) edit.remove("downloads_v1") else edit.putString("downloads_v1",previous)
            edit.commit()
        }
    }
    @Test fun stalePauseSnapshotCannotReplaceFinalizedDownloadPath() {
        val context: Context = RuntimeEnvironment.getApplication()
        try { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) } catch (_: IllegalStateException) { }
        val song = Song(id="pause-final-fixture", name="Local fixture", singer="Test", source="local")
        val id = DownloadScheduler.taskId(song, "320k")
        val stale = DownloadItem(song=song,path="pending:$id",size=0,time=0,status="downloading",quality="320k",taskId=id)
        val finalFile = java.io.File.createTempFile("sw-download-final-", ".mp3", context.cacheDir)
        finalFile.writeBytes(byteArrayOf(1, 2, 3))
        val done = stale.copy(path=finalFile.absolutePath,size=3,status="done",progress=1f)
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        try {
            LocalStore.addDownloads(context, listOf(stale))
            val generation = DownloadScheduler.currentGeneration(context)
            val committed = DownloadScheduler.commitIfCurrent(context, generation) {
                LocalStore.addDownloads(context, listOf(done)); true
            }
            assertEquals("finalization wins the shared generation fence first", true, committed)

            val stopped = DownloadScheduler.stopBatch(context, listOf(stale), paused=true)
            val saved = LocalStore.loadDownloads(context).single { it.taskId == id }
            assertEquals("done", saved.status)
            assertEquals(finalFile.absolutePath, saved.path)
            assertEquals(finalFile.absolutePath, stopped.single().path)
        } finally {
            finalFile.delete()
            val edit=prefs.edit()
            if (previous==null) edit.remove("downloads_v1") else edit.putString("downloads_v1",previous)
            edit.commit()
        }
    }
    @Test fun stopBatchReturnsFinalPathForDeleteAfterCompletion() {
        val context: Context = RuntimeEnvironment.getApplication()
        try { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) } catch (_: IllegalStateException) { }
        val song = Song(id="delete-final-fixture", name="Local fixture", singer="Test", source="local")
        val id = DownloadScheduler.taskId(song, "320k")
        val stale = DownloadItem(song=song,path="pending:$id",size=0,time=0,status="downloading",quality="320k",taskId=id)
        val finalFile = java.io.File.createTempFile("sw-download-delete-", ".mp3", context.cacheDir)
        finalFile.writeBytes(byteArrayOf(4, 5, 6))
        val done = stale.copy(path=finalFile.absolutePath,size=3,status="done",progress=1f)
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        try {
            LocalStore.addDownloads(context, listOf(stale))
            val generation = DownloadScheduler.currentGeneration(context)
            assertEquals(true, DownloadScheduler.commitIfCurrent(context, generation) {
                LocalStore.addDownloads(context, listOf(done)); true
            })

            val stopped = DownloadScheduler.stopBatch(context, listOf(stale), paused=false)
            assertEquals(finalFile.absolutePath, stopped.single().path)
            DownloadHelper.deleteArtifact(context, stopped.single())
            assertFalse("deletion receives the authoritative completed path", finalFile.exists())
        } finally {
            finalFile.delete()
            val edit=prefs.edit()
            if (previous==null) edit.remove("downloads_v1") else edit.putString("downloads_v1",previous)
            edit.commit()
        }
    }
    @Test fun singleCancelInvalidatesLateRetryPublication() {
        val context: Context = RuntimeEnvironment.getApplication()
        try { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) } catch (_: IllegalStateException) { }
        val song = Song(id="single-cancel-fixture", name="Local fixture", singer="Test", source="local")
        val id = DownloadScheduler.taskId(song, "320k")
        val item = DownloadItem(song=song,path="pending:$id",size=0,time=0,status="downloading",quality="320k",taskId=id)
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        try {
            LocalStore.addDownloads(context, listOf(item))
            val oldEpoch = DownloadScheduler.taskEpoch(context, id)
            val generation = DownloadScheduler.currentGeneration(context)
            DownloadScheduler.cancel(context, item)
            assertEquals(oldEpoch + 1L, DownloadScheduler.taskEpoch(context, id))
            assertFalse("a cancelled worker's retry callback is stale", DownloadScheduler.update(context, item.copy(status="retry_wait"), generation, oldEpoch))
            assertEquals("cancelled", LocalStore.loadDownloads(context).single { it.taskId == id }.status)
        } finally {
            val edit=prefs.edit()
            if (previous==null) edit.remove("downloads_v1") else edit.putString("downloads_v1",previous)
            edit.commit()
        }
    }
    @Test fun cancelAfterFinalCommitPreservesCompletedRecord() {
        val context: Context = RuntimeEnvironment.getApplication()
        try { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) } catch (_: IllegalStateException) { }
        val song = Song(id="cancel-final-fixture", name="Local fixture", singer="Test", source="local")
        val id = DownloadScheduler.taskId(song, "320k")
        val stale = DownloadItem(song=song,path="pending:$id",size=0,time=0,status="downloading",quality="320k",taskId=id)
        val finalFile = java.io.File.createTempFile("sw-download-cancel-", ".mp3", context.cacheDir)
        val done = stale.copy(path=finalFile.absolutePath,size=7,status="done",progress=1f)
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        try {
            LocalStore.addDownloads(context, listOf(stale))
            val generation = DownloadScheduler.currentGeneration(context)
            assertEquals(true, DownloadScheduler.commitIfCurrent(context, generation) {
                LocalStore.addDownloads(context, listOf(done)); true
            })
            DownloadScheduler.cancel(context, stale)
            val saved = LocalStore.loadDownloads(context).single { it.taskId == id }
            assertEquals("done", saved.status)
            assertEquals(finalFile.absolutePath, saved.path)
        } finally {
            finalFile.delete()
            val edit=prefs.edit()
            if (previous==null) edit.remove("downloads_v1") else edit.putString("downloads_v1",previous)
            edit.commit()
        }
    }
    @Test fun pausedOrDeletedTaskRejectsLateWorkerPublication() {
        val context: Context = RuntimeEnvironment.getApplication()
        try { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) } catch (_: IllegalStateException) { }
        val song = Song(id="cancel-fixture", name="No network", singer="Local", source="local")
        val id = DownloadScheduler.taskId(song,"320k")
        val item = DownloadItem(song=song,path="pending:$id",size=0,time=0,status="downloading",quality="320k",taskId=id)
        val prefs = LocalStore.prefs(context)
        val previous = prefs.getString("downloads_v1", null)
        try {
            LocalStore.addDownloads(context,listOf(item))
            val old = DownloadScheduler.taskEpoch(context,id)
            DownloadScheduler.stopBatch(context,listOf(item),paused=true)
            assertEquals(old+1,DownloadScheduler.taskEpoch(context,id))
            val generation = DownloadScheduler.currentGeneration(context)
            assertFalse(DownloadScheduler.update(context,item.copy(status="failed"),generation,old))
            assertEquals("paused",LocalStore.loadDownloads(context).first { it.taskId==id }.status)
            LocalStore.removeDownloads(context,listOf(item))
            assertFalse(DownloadScheduler.update(context,item.copy(status="done"),generation,old))
            assertFalse(LocalStore.loadDownloads(context).any { it.taskId==id })
        } finally {
            val edit=prefs.edit()
            if (previous==null) edit.remove("downloads_v1") else edit.putString("downloads_v1",previous)
            edit.commit()
        }
    }
}
