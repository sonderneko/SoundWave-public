package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCoverPolicyTest {
    @Test
    fun userPlaylistCoverWinsAndMissingCoverFallsBackToFirstUsableTrackArtwork() {
        assertEquals(
            "custom-cover.webp",
            libraryCoverUrl("custom-cover.webp", listOf("album-art.jpg"))
        )
        assertEquals(
            "album-art.jpg",
            libraryCoverUrl("  ", listOf("", "  ", "album-art.jpg", "later-art.jpg"))
        )
        assertNull(libraryCoverUrl(null, emptyList()))
    }

    @Test
    fun likedAndLocalPlaylistEmptyCoversHaveDifferentPalettesAndMotifs() {
        val liked = LibraryDefaultCoverKind.LIKED
        val local = LibraryDefaultCoverKind.LOCAL_PLAYLIST

        assertNotEquals(liked.gradientStart, local.gradientStart)
        assertNotEquals(liked.gradientEnd, local.gradientEnd)
        assertNotEquals(liked.motif, local.motif)
        assertEquals(LibraryCoverMotif.HEART, liked.motif)
        assertEquals(LibraryCoverMotif.PLAYLIST, local.motif)
        assertEquals(LibraryDefaultCoverKind.LIKED, libraryDefaultCoverKind("liked"))
        assertEquals(LibraryDefaultCoverKind.LOCAL_PLAYLIST, libraryDefaultCoverKind("local"))
        assertNull(libraryDefaultCoverKind("remote_123"))
    }

    @Test
    fun firstUnauthenticatedLibraryFrameShowsLocalCardAndBlankArtworkNeedsNoImageLoad() {
        val defaultIds = libraryDefaultPlaylistIds(loggedIn = false, importedIds = emptyList())
        val firstFrameOrder = libraryOrderIds(defaultIds, savedOrder = emptyList())

        assertEquals(listOf("local"), defaultIds)
        assertEquals(listOf("local"), firstFrameOrder)
        assertTrue(libraryQuickCardsVisible(firstFrameOrder))
        assertEquals(LibraryDefaultCoverKind.LIKED, libraryDefaultCoverKind("liked"))
        assertEquals(LibraryDefaultCoverKind.LOCAL_PLAYLIST, libraryDefaultCoverKind("local"))
        assertNull(libraryCoverUrl(null, emptyList()))
        assertFalse(libraryCoverHasImage(libraryCoverUrl(null, emptyList())))
    }

    @Test
    fun savedOrderIsFilteredAndDefaultsAreAppendedDeterministically() {
        assertEquals(
            listOf("imported", "local"),
            libraryOrderIds(
                defaultIds = listOf("imported", "local"),
                savedOrder = listOf("missing", "imported", "imported")
            )
        )
        assertEquals(listOf("liked", "local"), libraryDefaultPlaylistIds(true, emptyList()))
    }

    // ACCEPT5：在线子集新序拼回完整顺序——收藏（fav）与固定项的位置与相对序不动。
    @Test
    fun spliceReorderKeepsFavoritesAndFixedSlotsInPlace() {
        val current = listOf("liked", "on1", "fav1", "on2", "fav2", "on3", "local")
        val merged = librarySpliceReorder(
            currentOrder = current,
            memberIds = setOf("on1", "on2", "on3"),
            newSubsequence = listOf("on3", "on1", "on2")
        )
        assertEquals(listOf("liked", "on3", "fav1", "on1", "fav2", "on2", "local"), merged)
    }

    // ACCEPT5：平台筛选下只拖可见子集——筛外在线歌单（off）位置不动。
    @Test
    fun spliceReorderHandlesPlatformFilteredSubset() {
        val current = listOf("a", "off", "b", "c")
        val merged = librarySpliceReorder(
            currentOrder = current,
            memberIds = setOf("a", "b", "c"),
            newSubsequence = listOf("c", "a", "b")
        )
        assertEquals(listOf("c", "off", "a", "b"), merged)
    }

    // ACCEPT5：成员数对不上（手势期间数据已变）时原样返回，宁可不落盘也不错位。
    @Test
    fun spliceReorderRejectsMismatchedSubset() {
        val current = listOf("a", "b", "c")
        assertEquals(current, librarySpliceReorder(current, setOf("a", "b"), listOf("b")))
        assertEquals(current, librarySpliceReorder(current, setOf("a", "b"), listOf("b", "b")))
        assertEquals(current, librarySpliceReorder(current, emptySet(), emptyList()))
    }

    // ACCEPT5：拖动步进——阈值 pitch×0.27 一步一换，余量保留；到边界停住。
    @Test
    fun dragAdvanceStepsByThresholdAndKeepsRemainder() {
        val ids = listOf("a", "b", "c", "d")
        val pitch = 100f // thr = 27px
        val (once, rem1) = libraryDragAdvance(ids, "a", 30f, pitch)
        assertEquals(listOf("b", "a", "c", "d"), once)
        assertEquals(3f, rem1, 0.001f)
        // 同一手势继续累积：余量 3 + 新进 60 → 再换两步到尾。
        val (thrice, rem2) = libraryDragAdvance(once, "a", rem1 + 60f, pitch)
        assertEquals(listOf("b", "c", "d", "a"), thrice)
        assertEquals(9f, rem2, 0.001f)
        // 已在末位：再往下拖不换位，余量原样带回（松手即弃）。
        val (atEnd, rem3) = libraryDragAdvance(thrice, "a", 50f, pitch)
        assertEquals(thrice, atEnd)
        assertEquals(50f, rem3, 0.001f)
        // 向上一步。
        val (up, rem4) = libraryDragAdvance(thrice, "a", -30f, pitch)
        assertEquals(listOf("b", "c", "a", "d"), up)
        assertEquals(-3f, rem4, 0.001f)
        // 未知 id / 非法 pitch 原样返回。
        assertEquals(ids to 12f, libraryDragAdvance(ids, "zzz", 12f, pitch))
        assertEquals(ids to 12f, libraryDragAdvance(ids, "a", 12f, 0f))
    }
}
