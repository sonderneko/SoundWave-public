package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ACCEPT4（审计 §11）：本地歌单删除写真源 + 排除记录——移除后详情真源不再含该曲目、
 * 扫描结果被排除记录过滤（重扫不复活）、撤销恢复解除排除并把曲目并回。
 * 单方法走完生命周期：LocalLibraryStore 的内存缓存是进程级静态量，多方法间不隔离。
 */
@RunWith(RobolectricTestRunner::class)
class LocalLibraryExclusionTest {

    private fun song(id: String) = Song(id = id, name = "歌$id", singer = "本地歌手", source = "local")

    @Test
    fun removeExcludeFilterRestoreLifecycle() {
        val context = RuntimeEnvironment.getApplication()
        val a = song("a1"); val b = song("b2"); val c = song("c3")
        LocalLibraryStore.save(context, listOf(a, b, c))
        assertEquals(listOf("local:a1", "local:b2", "local:c3"), LocalLibraryStore.songs(context).map { it.key() })

        // 删除 b：真源即刻不含 b，且落排除记录。
        val afterRemove = LocalLibraryStore.removeSongs(context, setOf(b.key()))
        assertEquals(listOf("local:a1", "local:c3"), afterRemove.map { it.key() })
        assertEquals(afterRemove.map { it.key() }, LocalLibraryStore.songs(context).map { it.key() })
        assertTrue(b.key() in LocalLibraryStore.excludedKeys(context))

        // 重扫带回 b（文件还在）：排除记录过滤，不复活。
        val rescanned = LocalLibraryStore.filterExcluded(context, listOf(a, b, c))
        assertEquals(listOf("local:a1", "local:c3"), rescanned.map { it.key() })

        // 撤销恢复：解除排除并把 b 并回真源。
        val restored = LocalLibraryStore.restoreSongs(context, listOf(a, b, c))
        assertEquals(setOf("local:a1", "local:b2", "local:c3"), restored.map { it.key() }.toSet())
        assertTrue(LocalLibraryStore.excludedKeys(context).isEmpty())
        // 恢复后再扫：b 不再被过滤。
        assertEquals(3, LocalLibraryStore.filterExcluded(context, listOf(a, b, c)).size)
    }
}
