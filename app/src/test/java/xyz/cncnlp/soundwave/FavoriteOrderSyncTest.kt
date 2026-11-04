package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏内部排序（用户 2026-11-05 纠正口径）：收藏不掺和导入在线/新建两个池子的排序，
 * 但收藏本身内部可排序——FavoriteOrderSync 用独立的 favSyncOrder 戳记对，与池子的
 * syncOrder 互不读写。本测试锁死这条隔离线与戳记语义。
 */
class FavoriteOrderSyncTest {

    private fun pl(id: String, fav: Int? = null, favAt: Long = 0L, pool: Int? = null, poolAt: Long = 0L) =
        Playlist(id, id, favSyncOrder = fav, favSyncOrderAt = favAt, syncOrder = pool, syncOrderAt = poolAt)

    @Test fun displayFallsBackToCandidateOrderWithoutStamps() {
        val candidates = listOf(pl("a"), pl("b"), pl("c"))
        assertEquals(listOf("a", "b", "c"), FavoriteOrderSync.displayOrder(candidates, emptyList()))
    }

    @Test fun displayUsesLegacyListBeforeCandidatesWhenNoStamps() {
        val candidates = listOf(pl("a"), pl("b"), pl("c"))
        assertEquals(listOf("c", "a", "b"), FavoriteOrderSync.displayOrder(candidates, listOf("c", "a")))
    }

    @Test fun displayLatestRevisionWinsAndNewEntriesFollow() {
        val candidates = listOf(
            pl("a", fav = 1, favAt = 100L),
            pl("b", fav = 0, favAt = 100L),
            pl("c"),
            pl("d", fav = 0, favAt = 50L) // older revision loses to the latest one
        )
        assertEquals(listOf("b", "a", "c", "d"), FavoriteOrderSync.displayOrder(candidates, listOf("d", "c")))
    }

    @Test fun withOrderStampsOnlyMembersAndLeavesPoolStampsUntouched() {
        val playlists = listOf(
            pl("pool1", pool = 0, poolAt = 7L),
            pl("fav1"),
            pl("fav2"),
            pl("other-fav-group") // member of the OTHER favorites group: not in memberIds here
        )
        val result = FavoriteOrderSync.withOrder(
            playlists, memberIds = setOf("fav1", "fav2"),
            requestedOrder = listOf("fav2", "fav1"), nowMs = 1_000L
        )
        val byId = result.associateBy { it.id }
        assertEquals(0, byId.getValue("fav2").favSyncOrder)
        assertEquals(1, byId.getValue("fav1").favSyncOrder)
        assertEquals(1_000L, byId.getValue("fav1").favSyncOrderAt)
        // Non-members: favorites stamps untouched.
        assertNull(byId.getValue("pool1").favSyncOrder)
        assertNull(byId.getValue("other-fav-group").favSyncOrder)
        // Pool stamps never move as a side effect of a favorites reorder — either direction.
        assertEquals(0, byId.getValue("pool1").syncOrder)
        assertEquals(7L, byId.getValue("pool1").syncOrderAt)
        assertNull(byId.getValue("fav1").syncOrder)
        assertNull(byId.getValue("fav2").syncOrder)
    }

    @Test fun withOrderRevisionAlwaysAdvances() {
        val playlists = listOf(pl("fav1", fav = 0, favAt = 5_000L), pl("fav2", fav = 1, favAt = 5_000L))
        val result = FavoriteOrderSync.withOrder(
            playlists, memberIds = setOf("fav1", "fav2"),
            requestedOrder = listOf("fav2", "fav1"), nowMs = 10L // clock behind the stored revision
        )
        assertTrue(result.all { it.favSyncOrderAt == 5_001L })
        assertEquals(listOf("fav2", "fav1"), FavoriteOrderSync.displayOrder(result, emptyList()))
    }

    @Test fun withOrderKeepsUnrequestedMembersAtTailInExistingOrder() {
        val playlists = listOf(pl("fav1"), pl("fav2"), pl("fav3"))
        val result = FavoriteOrderSync.withOrder(
            playlists, memberIds = setOf("fav1", "fav2", "fav3"),
            requestedOrder = listOf("fav3"), nowMs = 42L
        )
        assertEquals(listOf("fav3", "fav1", "fav2"), FavoriteOrderSync.displayOrder(result, emptyList()))
    }

    @Test fun emptyGroupIsANoOp() {
        val playlists = listOf(pl("a"))
        assertEquals(playlists, FavoriteOrderSync.withOrder(playlists, emptySet(), listOf("a")))
        assertEquals(emptyList<String>(), FavoriteOrderSync.displayOrder(emptyList(), listOf("a")))
    }
}
