package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEditsTest {
    @Test
    fun `move preserves current item while crossing it`() {
        val edit = QueueEdits.move(listOf("a", "b", "c", "d"), currentIndex = 2, fromIndex = 0, toIndex = 3)!!
        assertEquals(listOf("b", "c", "d", "a"), edit.items)
        assertEquals(1, edit.currentIndex)
    }

    @Test
    fun `moving current item updates its index`() {
        val edit = QueueEdits.move(listOf("a", "b", "c"), currentIndex = 1, fromIndex = 1, toIndex = 0)!!
        assertEquals(listOf("b", "a", "c"), edit.items)
        assertEquals(0, edit.currentIndex)
    }

    @Test
    fun `remove before current shifts index and current cannot be removed by default`() {
        val edit = QueueEdits.remove(listOf("a", "b", "c"), currentIndex = 2, atIndex = 0)!!
        assertEquals(listOf("b", "c"), edit.items)
        assertEquals(1, edit.currentIndex)
        assertNull(QueueEdits.remove(listOf("a", "b", "c"), currentIndex = 1, atIndex = 1))
    }

    @Test
    fun `insert restores position and shifts current only when inserted at or before it`() {
        val before = QueueEdits.insert(listOf("b", "c"), currentIndex = 1, atIndex = 0, item = "a")
        assertEquals(listOf("a", "b", "c"), before.items)
        assertEquals(2, before.currentIndex)
        val after = QueueEdits.insert(listOf("a", "b"), currentIndex = 0, atIndex = 2, item = "c")
        assertEquals(listOf("a", "b", "c"), after.items)
        assertEquals(0, after.currentIndex)
        val clamped = QueueEdits.insert(listOf("a"), currentIndex = 0, atIndex = 99, item = "b")
        assertEquals(listOf("a", "b"), clamped.items)
        val empty = QueueEdits.insert(emptyList<String>(), currentIndex = -1, atIndex = 0, item = "a")
        assertEquals(listOf("a"), empty.items)
        assertEquals(0, empty.currentIndex)
    }

    @Test
    fun `invalid indexes and no-op move do not produce edits`() {
        val items = listOf("a", "b")
        assertNull(QueueEdits.move(items, currentIndex = 0, fromIndex = 1, toIndex = 1))
        assertNull(QueueEdits.move(items, currentIndex = 0, fromIndex = -1, toIndex = 1))
        assertNull(QueueEdits.remove(items, currentIndex = 0, atIndex = 2))
    }
}
