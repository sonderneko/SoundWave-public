package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ACCEPT5 队列来源同步：纯逻辑（标记对齐 + 来源实例移除）的口径钉死。 */
class QueueOriginSyncTest {

    private fun song(id: String) = Song(id, id, "歌手", "wy")
    private fun songs(vararg ids: String) = ids.map(::song)
    private fun keys(items: List<Song>) = items.map { it.key() }

    // ---------- reconcileEntryFlags ----------

    @Test
    fun reconcileFollowsEntriesAcrossReorder() {
        val flags = QueueOriginSync.reconcileEntryFlags(
            oldKeys = keys(songs("a", "b", "c")),
            oldFlags = listOf(true, false, true),
            newKeys = keys(songs("c", "a", "b"))
        )
        assertEquals(listOf(true, true, false), flags)
    }

    @Test
    fun reconcileMarksAppendedEntryAsManual() {
        val flags = QueueOriginSync.reconcileEntryFlags(
            oldKeys = keys(songs("a", "b")),
            oldFlags = listOf(true, true),
            newKeys = keys(songs("a", "b", "c"))
        )
        assertEquals(listOf(true, true, false), flags)
    }

    @Test
    fun reconcilePairsDuplicateOccurrencesInOrder() {
        // 同 key 两份：第一份来源、第二份手动；重排后按出现次序各自继承。
        val flags = QueueOriginSync.reconcileEntryFlags(
            oldKeys = keys(songs("a", "a", "b")),
            oldFlags = listOf(true, false, true),
            newKeys = keys(songs("b", "a", "a"))
        )
        assertEquals(listOf(true, true, false), flags)
    }

    @Test
    fun reconcileExtraDuplicateOccurrenceIsManual() {
        val flags = QueueOriginSync.reconcileEntryFlags(
            oldKeys = keys(songs("a")),
            oldFlags = listOf(true),
            newKeys = keys(songs("a", "a"))
        )
        assertEquals(listOf(true, false), flags)
    }

    @Test
    fun reconcileToleratesShortOldFlags() {
        val flags = QueueOriginSync.reconcileEntryFlags(
            oldKeys = keys(songs("a", "b")),
            oldFlags = listOf(true),
            newKeys = keys(songs("a", "b"))
        )
        assertEquals(listOf(true, false), flags)
    }

    // ---------- removeOriginInstances ----------

    @Test
    fun removalDropsOriginInstancesAndShiftsCurrentIndex() {
        val items = songs("a", "b", "c", "d")
        val result = QueueOriginSync.removeOriginInstances(
            items, listOf(true, true, true, true), protectedIndex = 3, removedKeys = setOf(song("a").key(), song("c").key())
        )!!
        assertEquals(keys(songs("b", "d")), keys(result.items))
        assertEquals(1, result.currentIndex)
        assertEquals(2, result.removedCount)
        assertEquals(listOf(true, true), result.flags)
    }

    @Test
    fun removalProtectsActiveEntryEvenWhenItsKeyWasRemoved() {
        val items = songs("a", "b")
        val result = QueueOriginSync.removeOriginInstances(
            items, listOf(true, true), protectedIndex = 0, removedKeys = setOf(song("a").key(), song("b").key())
        )!!
        assertEquals(keys(songs("a")), keys(result.items))
        assertEquals(0, result.currentIndex)
        assertEquals(1, result.removedCount)
    }

    @Test
    fun removalKeepsManualDuplicatesOfRemovedSong() {
        // 同曲两实例：来源份移除，手动「下一首播放」份（flag=false）保留。
        val items = songs("a", "a", "b")
        val result = QueueOriginSync.removeOriginInstances(
            items, listOf(true, false, true), protectedIndex = 2, removedKeys = setOf(song("a").key())
        )!!
        assertEquals(keys(songs("a", "b")), keys(result.items))
        assertEquals(listOf(false, true), result.flags)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun removalBeforeCurrentRecomputesIndex() {
        val items = songs("a", "b", "c")
        val result = QueueOriginSync.removeOriginInstances(
            items, listOf(true, true, true), protectedIndex = 2, removedKeys = setOf(song("a").key(), song("b").key())
        )!!
        assertEquals(keys(songs("c")), keys(result.items))
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun removalIsNoOpWhenNothingMatches() {
        val items = songs("a", "b")
        assertNull(
            QueueOriginSync.removeOriginInstances(
                items, listOf(true, true), protectedIndex = 0, removedKeys = setOf(song("z").key())
            )
        )
        // 全部是手动条目时同样不动。
        assertNull(
            QueueOriginSync.removeOriginInstances(
                items, listOf(false, false), protectedIndex = 0, removedKeys = setOf(song("a").key())
            )
        )
        assertNull(
            QueueOriginSync.removeOriginInstances(
                items, listOf(true, true), protectedIndex = 0, removedKeys = emptySet()
            )
        )
    }

    @Test
    fun removalWithMissingFlagsTreatsEntriesAsManual() {
        // 标记缺失（旧断点/异常对齐）时保守：不误删。
        val items = songs("a", "b")
        assertNull(
            QueueOriginSync.removeOriginInstances(
                items, emptyList(), protectedIndex = 1, removedKeys = setOf(song("a").key())
            )
        )
    }

    // ---------- QueueOrigin ----------

    @Test
    fun originTypeDerivedFromPlaylistId() {
        assertEquals(QueueOrigin.Type.LIKED, QueueOrigin.fromPlaylistId("liked").type)
        assertEquals(QueueOrigin.Type.LOCAL, QueueOrigin.fromPlaylistId("local").type)
        assertEquals(QueueOrigin.Type.PLAYLIST, QueueOrigin.fromPlaylistId("pl_123").type)
        assertTrue(QueueOrigin.fromPlaylistId("liked").playlistId == "liked")
    }
}
