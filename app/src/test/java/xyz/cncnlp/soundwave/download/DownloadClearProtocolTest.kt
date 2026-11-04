package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadClearProtocolTest {
    private data class Artifact(val name: String)

    @Test
    fun `clear cancels active work then deletes completed artifacts before clearing records`() {
        val fence = DownloadGenerationFence()
        var generation = 0L
        val activeTasks = mutableSetOf("download-a", "download-b")
        val records = mutableListOf(Artifact("completed-a"), Artifact("completed-b"))
        val deleted = mutableListOf<String>()
        val events = mutableListOf<String>()

        val cleared = DownloadClearProtocol.clear(
            fence = fence,
            readGeneration = { generation },
            persistGeneration = { next -> generation = next; events += "generation"; true },
            cancelAll = { events += "cancel"; activeTasks.clear() },
            loadItems = { events += "snapshot"; records.toList() },
            deleteArtifact = { item -> events += "delete:${item.name}"; deleted += item.name },
            clearRecords = { events += "clear-records"; records.clear() },
            removeFiles = true
        )

        assertNotNull(cleared)
        assertTrue("all active tasks must receive cancellation", activeTasks.isEmpty())
        assertEquals(listOf("completed-a", "completed-b"), deleted)
        assertTrue(records.isEmpty())
        assertEquals(
            listOf("generation", "cancel", "snapshot", "delete:completed-a", "delete:completed-b", "clear-records"),
            events
        )
    }

    @Test
    fun `clear without file deletion preserves completed artifacts but removes records`() {
        val fence = DownloadGenerationFence()
        var generation = 4L
        val records = mutableListOf(Artifact("keep-this-file"))
        val deleted = mutableListOf<String>()

        val cleared = DownloadClearProtocol.clear(
            fence = fence,
            readGeneration = { generation },
            persistGeneration = { generation = it; true },
            cancelAll = {},
            loadItems = { records.toList() },
            deleteArtifact = { deleted += it.name },
            clearRecords = { records.clear() },
            removeFiles = false
        )

        assertNotNull(cleared)
        assertEquals(5L, generation)
        assertTrue(deleted.isEmpty())
        assertTrue(records.isEmpty())
    }

    @Test
    fun `failed generation persistence aborts before cancellation or deletion`() {
        val fence = DownloadGenerationFence()
        var cancelled = false
        var deleted = false
        var recordsCleared = false

        val result = DownloadClearProtocol.clear(
            fence = fence,
            readGeneration = { 0L },
            persistGeneration = { false },
            cancelAll = { cancelled = true },
            loadItems = { listOf(Artifact("file")) },
            deleteArtifact = { deleted = true },
            clearRecords = { recordsCleared = true },
            removeFiles = true
        )

        assertNull(result)
        assertFalse(cancelled)
        assertFalse(deleted)
        assertFalse(recordsCleared)
    }

    @Test
    fun `a final result that wins the race is included in the subsequent clear snapshot`() {
        val fence = DownloadGenerationFence()
        var generation = 0L
        val records = Collections.synchronizedList(mutableListOf<String>())
        val files = Collections.synchronizedList(mutableListOf<String>())
        val events = Collections.synchronizedList(mutableListOf<String>())
        val workerEnteredCommit = CountDownLatch(1)
        val releaseWorkerCommit = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val oldGeneration = fence.atCurrentGeneration({ generation }) { it }

        try {
            val worker = executor.submit<Boolean> {
                fence.commitIfCurrent({ generation }, oldGeneration) {
                    workerEnteredCommit.countDown()
                    check(releaseWorkerCommit.await(5, TimeUnit.SECONDS))
                    events += "worker-commit"
                    files += "completed-file"
                    records += "completed-record"
                    true
                } ?: false
            }
            assertTrue(workerEnteredCommit.await(5, TimeUnit.SECONDS))

            val clear = executor.submit<List<String>?> {
                clearStarted.countDown()
                DownloadClearProtocol.clear(
                    fence = fence,
                    readGeneration = { generation },
                    persistGeneration = { generation = it; events += "generation"; true },
                    cancelAll = { events += "cancel" },
                    loadItems = { records.toList().also { events += "snapshot" } },
                    deleteArtifact = { item -> files.remove("completed-file"); events += "delete:$item" },
                    clearRecords = { records.clear(); events += "clear-records" },
                    removeFiles = true
                )
            }
            assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
            releaseWorkerCommit.countDown()

            assertTrue(worker.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("completed-record"), clear.get(5, TimeUnit.SECONDS))
            assertTrue(records.isEmpty())
            assertTrue("the completed file must be removed after the winning commit", files.isEmpty())
            assertTrue(events.indexOf("worker-commit") < events.indexOf("generation"))
            assertTrue(events.indexOf("generation") < events.indexOf("delete:completed-record"))
            assertTrue(events.indexOf("delete:completed-record") < events.indexOf("clear-records"))
        } finally {
            releaseWorkerCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a worker trying to commit during cancellation or after clear is rejected`() {
        val fence = DownloadGenerationFence()
        var generation = 0L
        val oldGeneration = fence.atCurrentGeneration({ generation }) { it }
        var committed = false

        val result = DownloadClearProtocol.clear(
            fence = fence,
            readGeneration = { generation },
            persistGeneration = { generation = it; true },
            cancelAll = {
                assertNull(fence.commitIfCurrent({ generation }, oldGeneration) { committed = true })
            },
            loadItems = { emptyList<Unit>() },
            deleteArtifact = {},
            clearRecords = {},
            removeFiles = false
        )

        assertNotNull(result)
        assertFalse(committed)
        assertNull(fence.commitIfCurrent({ generation }, oldGeneration) { committed = true })
        assertFalse(committed)
    }
}
