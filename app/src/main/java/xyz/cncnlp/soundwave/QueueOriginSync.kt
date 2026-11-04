package xyz.cncnlp.soundwave

/**
 * ACCEPT5（队列与来源歌单同步）：队列的最小来源标记与条目级来源对齐/移除计算（纯函数，可单测）。
 *
 * 背景：队列是点播时整表物化的快照，本身不带来源身份；取消喜欢/从歌单移歌只写 bundle，
 * 从不传播到队列（既有 PlaylistDetailActions 的同步被「队列与歌单整序全等」门控，真实使用中几乎不成立）。
 * 本机制：新队列建立时把来源歌单身份（id + 类型）记到队列上，并给每个条目打来源标记
 * （手动插入的条目不打），来源歌单发生移除时，只移除「带来源标记、非活动条目」的同 key 实例。
 */
data class QueueOrigin(val playlistId: String, val type: Type) {
    enum class Type { LIKED, LOCAL, PLAYLIST }

    companion object {
        /** 由歌单 id 派生类型：liked/local 是固定伪歌单，其余一律视为歌单（自有/导入同域）。 */
        fun fromPlaylistId(playlistId: String): QueueOrigin = QueueOrigin(
            playlistId = playlistId,
            type = when (playlistId) {
                "liked" -> Type.LIKED
                "local" -> Type.LOCAL
                else -> Type.PLAYLIST
            }
        )
    }
}

internal object QueueOriginSync {

    /**
     * 队列被整表替换（重排/删行/追加/恢复）时，把旧条目的来源标记对齐到新表：
     * 按 (key, 出现次序) 一一配对，配上的继承旧标记，配不上的（新插入条目）为 false。
     * 注意 insertNext 是「先删同 key 再插入」，配对会把手动插入误判为原条目——调用方须对该位置显式清标记。
     */
    fun reconcileEntryFlags(oldKeys: List<String>, oldFlags: List<Boolean>, newKeys: List<String>): List<Boolean> {
        if (newKeys.isEmpty()) return emptyList()
        val oldIndicesByKey = HashMap<String, MutableList<Int>>()
        oldKeys.forEachIndexed { index, key -> oldIndicesByKey.getOrPut(key) { mutableListOf() }.add(index) }
        val consumed = HashMap<String, Int>()
        return newKeys.map { key ->
            val occurrence = consumed.getOrDefault(key, 0)
            consumed[key] = occurrence + 1
            val candidates = oldIndicesByKey[key]
            val oldIndex = candidates?.getOrNull(occurrence)
            if (oldIndex != null) oldFlags.getOrElse(oldIndex) { false } else false
        }
    }

    data class RemovalResult(
        val items: List<Song>,
        val flags: List<Boolean>,
        val currentIndex: Int,
        val removedCount: Int
    )

    /**
     * 从来源歌单移除歌曲时，计算队列的新形态：移除「来源标记为 true 且 key ∈ [removedKeys]」的条目，
     * 但 [protectedIndex] 所指的活动条目（正在播/待播）永不移除。位置不设已播/未播分界：
     * 随机/循环遍历下任何非活动条目都仍会被够到（见 WAVE-ACCEPT5-QSYNC 台账二.4）。
     * 无可移除时返回 null。[flags] 与 [items] 不对齐时按 false 补齐（保守：宁可不移，不误删手动条目）。
     */
    fun removeOriginInstances(
        items: List<Song>,
        flags: List<Boolean>,
        protectedIndex: Int,
        removedKeys: Set<String>
    ): RemovalResult? {
        if (items.isEmpty() || removedKeys.isEmpty()) return null
        val protected = protectedIndex.takeIf { it in items.indices } ?: -1
        val nextItems = ArrayList<Song>(items.size)
        val nextFlags = ArrayList<Boolean>(items.size)
        var removed = 0
        var nextCurrent = -1
        items.forEachIndexed { index, song ->
            val fromOrigin = flags.getOrElse(index) { false }
            if (index != protected && fromOrigin && song.key() in removedKeys) {
                removed++
            } else {
                if (index == protected) nextCurrent = nextItems.size
                nextItems.add(song)
                nextFlags.add(fromOrigin)
            }
        }
        if (removed == 0) return null
        // 受保护条目不在表中（protected 越界）时，指针退回首项；表被移空且无保护条目时为 -1（调用方不会走到：队列非空时 queueIndex 必有效）。
        val current = when {
            nextCurrent >= 0 -> nextCurrent
            nextItems.isEmpty() -> -1
            else -> 0
        }
        return RemovalResult(nextItems, nextFlags, current, removed)
    }
}
