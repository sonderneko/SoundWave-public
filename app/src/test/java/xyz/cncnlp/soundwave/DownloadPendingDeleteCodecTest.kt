package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验收二轮 E2：延迟删除注册表的编解码往返——批次快照（含 SAF 副本标记、片段映射、
 * 记录全字段）落盘后必须无损还原，否则撤销恢复会丢记录或丢文件映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadPendingDeleteCodecTest {

    private fun item(path: String, taskId: String) = DownloadItem(
        song = Song(id = "42", name = "待删歌曲", singer = "歌手", source = "wy", hash = "h42"),
        path = path,
        size = 12_345L,
        time = 1_699_999_999_000L,
        status = "done",
        progress = 1f,
        error = "",
        quality = "flac",
        platform = "wy",
        scriptId = "script-1",
        resolveFailure = "",
        taskId = taskId
    )

    @Test
    fun registryRoundTripsBatchesEntriesAndStagedFiles() {
        val batches = listOf(
            DownloadPendingDelete.PendingBatch(
                id = "bm3test1abc",
                deadlineMs = 1_700_000_010_000L,
                entries = listOf(
                    DownloadPendingDelete.PendingEntry(
                        item = item("/music/SoundWave/a.flac", "task-a"),
                        deleteFiles = true,
                        staged = listOf(
                            DownloadPendingDelete.StagedFile("/music/SoundWave/a.flac", "f0_main"),
                            DownloadPendingDelete.StagedFile("/music/SoundWave/a-task-a.flac.part", "f1_part"),
                            DownloadPendingDelete.StagedFile("content://media/external/audio/9", "f2_saf", safCopy = true)
                        )
                    ),
                    DownloadPendingDelete.PendingEntry(
                        item = item("content://media/external/audio/10", "task-b"),
                        deleteFiles = false,
                        staged = emptyList()
                    )
                )
            )
        )
        val decoded = DownloadPendingDelete.decodeBatches(DownloadPendingDelete.encodeBatches(batches))
        assertEquals(batches, decoded)
    }

    @Test
    fun registryDecodingToleratesGarbageAndEmptyInput() {
        assertTrue(DownloadPendingDelete.decodeBatches("").isEmpty())
        assertTrue(DownloadPendingDelete.decodeBatches("not json").isEmpty())
        assertTrue(DownloadPendingDelete.decodeBatches("[]").isEmpty())
        assertTrue(DownloadPendingDelete.decodeBatches("[{\"id\":\"\"}]").isEmpty())
    }

    @Test
    fun commitWindowIsTenSecondsCoveringTheUndoCapsule() {
        // 胶囊沿用 AppNotice 既有 4s 撤销规格；内部提交窗口必须不短于胶囊，
        // 且不新造规格（10s 只是暂存保留期）。
        assertTrue(DownloadPendingDelete.WINDOW_MS >= AppNoticeUndoDurationForTest)
        assertEquals(10_000L, DownloadPendingDelete.WINDOW_MS)
    }

    private companion object {
        const val AppNoticeUndoDurationForTest = 4_000L
    }
}
