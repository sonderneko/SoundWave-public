package xyz.cncnlp.soundwave

internal enum class LibraryCoverMotif {
    HEART,
    PLAYLIST
}

internal enum class LibraryDefaultCoverKind(
    val gradientStart: Long,
    val gradientEnd: Long,
    val motifColor: Long,
    val motif: LibraryCoverMotif,
    val contentDescription: String
) {
    LIKED(
        gradientStart = 0xFF9C1F56,
        gradientEnd = 0xFF6E1E46,
        motifColor = 0xFFFFC7D9,
        motif = LibraryCoverMotif.HEART,
        contentDescription = "我的喜欢默认封面"
    ),
    LOCAL_PLAYLIST(
        gradientStart = 0xFF087A78,
        gradientEnd = 0xFF0B5345,
        motifColor = 0xFF168C7C,
        motif = LibraryCoverMotif.PLAYLIST,
        contentDescription = "本地歌单默认封面"
    )
}

/** 用户设置的歌单封面始终优先；缺失时从有封面的曲目中取第一张。 */
internal fun libraryCoverUrl(playlistCover: String?, songCovers: Iterable<String>): String? =
    playlistCover?.takeIf { it.isNotBlank() }
        ?: songCovers.firstOrNull { it.isNotBlank() }

internal fun libraryCoverHasImage(cover: String?): Boolean = !cover.isNullOrBlank()

internal fun libraryDefaultCoverKind(playlistId: String): LibraryDefaultCoverKind? = when (playlistId) {
    "liked" -> LibraryDefaultCoverKind.LIKED
    "local" -> LibraryDefaultCoverKind.LOCAL_PLAYLIST
    else -> null
}

internal fun libraryDefaultPlaylistIds(loggedIn: Boolean, importedIds: List<String>): List<String> =
    buildList {
        if (loggedIn) add("liked")
        addAll(importedIds)
        add("local")
    }.distinct()

/** Resolve order synchronously so the first library frame already contains its default entries. */
internal fun libraryOrderIds(defaultIds: List<String>, savedOrder: List<String>): List<String> {
    val defaults = defaultIds.distinct()
    val ordered = (savedOrder + defaults).distinct().filter { it in defaults }
    return ordered.ifEmpty { defaults }
}

internal fun libraryQuickCardsVisible(orderIds: List<String>): Boolean =
    orderIds.any { it == "liked" || it == "local" }

/**
 * ACCEPT5（用户 2026-11-04）：在线歌单长按拖动只重排「当前可见子集」——把子集新序拼回完整
 * 顺序时，子集成员按新序依次占据它们原有的位置，其余 id（收藏歌单/专辑、平台筛外歌单、
 * liked/local）的位置与相对序一律不动，即收藏不进入在线歌单的排序域。
 * 子集与完整顺序的成员数对不上（数据已变化）时原样返回，宁可不落盘也不错位。
 */
internal fun librarySpliceReorder(
    currentOrder: List<String>,
    memberIds: Set<String>,
    newSubsequence: List<String>
): List<String> {
    val sub = newSubsequence.filter { it in memberIds }
    if (sub.size != currentOrder.count { it in memberIds }) return currentOrder
    if (sub.distinct().size != sub.size) return currentOrder
    val queue = ArrayDeque(sub)
    return currentOrder.map { if (it in memberIds) queue.removeFirst() else it }
}

/**
 * ACCEPT5：长按拖动的单次增量步进（与脚本页同一范式：阈值 = 行距 pitch × 0.27，越过一步
 * 换一位，余量保留到下次增量）。返回（新序，剩余累积 px）；被拖 id 不在表中时原样返回。
 */
internal fun libraryDragAdvance(
    ids: List<String>,
    draggedId: String,
    accPx: Float,
    pitchPx: Float
): Pair<List<String>, Float> {
    if (pitchPx <= 0f) return ids to accPx
    var idx = ids.indexOf(draggedId)
    if (idx < 0) return ids to accPx
    val thr = pitchPx * 0.27f
    var list = ids
    var acc = accPx
    while (acc >= thr && idx < list.lastIndex) {
        list = list.toMutableList().apply { add(idx + 1, removeAt(idx)) }
        idx++
        acc -= thr
    }
    while (acc <= -thr && idx > 0) {
        list = list.toMutableList().apply { add(idx - 1, removeAt(idx)) }
        idx--
        acc += thr
    }
    return list to acc
}
