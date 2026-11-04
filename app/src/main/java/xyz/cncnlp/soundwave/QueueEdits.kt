package xyz.cncnlp.soundwave

/** Queue edits are computed against one service snapshot before the controller commits them. */
internal data class QueueEdit<T>(val items: List<T>, val currentIndex: Int)

internal object QueueEdits {
    fun <T> move(items: List<T>, currentIndex: Int, fromIndex: Int, toIndex: Int): QueueEdit<T>? {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return null
        val moved = items.toMutableList()
        val item = moved.removeAt(fromIndex)
        moved.add(toIndex.coerceIn(0, moved.size), item)
        val nextCurrent = when {
            currentIndex !in items.indices -> currentIndex
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        return QueueEdit(moved.toList(), nextCurrent)
    }

    /** Undo partner of [remove]: restores an item at its previous position. */
    fun <T> insert(items: List<T>, currentIndex: Int, atIndex: Int, item: T): QueueEdit<T> {
        if (items.isEmpty()) return QueueEdit(listOf(item), 0)
        val at = atIndex.coerceIn(0, items.size)
        val next = items.toMutableList().also { it.add(at, item) }
        val nextCurrent = when {
            currentIndex !in items.indices -> currentIndex
            at <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        return QueueEdit(next.toList(), nextCurrent)
    }

    fun <T> remove(
        items: List<T>,
        currentIndex: Int,
        atIndex: Int,
        protectCurrent: Boolean = true
    ): QueueEdit<T>? {
        if (atIndex !in items.indices) return null
        if (protectCurrent && atIndex == currentIndex) return null
        val remaining = items.toMutableList().also { it.removeAt(atIndex) }
        val nextCurrent = when {
            currentIndex !in items.indices -> currentIndex
            atIndex < currentIndex -> currentIndex - 1
            atIndex == currentIndex -> currentIndex.coerceAtMost(remaining.lastIndex)
            else -> currentIndex
        }
        return QueueEdit(remaining.toList(), nextCurrent)
    }
}
