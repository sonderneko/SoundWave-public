package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * accept6-backnav（用户 2026-11-04 23:17）：详情导航栈回归——替代旧 detailParent 单槽。
 * 打开不同 id 的详情才压栈；同 id 刷新（加载回写/收藏切换）永不压栈；返回逐级弹栈恢复快照。
 */
class DetailNavStackTest {

    private fun frame(id: String, header: Pair<String, String> = "" to "", failed: String? = null) =
        DetailFrame(Playlist(id, "name-$id"), emptyList(), header, failed)

    @Test
    fun openFromNothingDoesNotPush() {
        assertEquals(emptyList<DetailFrame>(), DetailNavStack.pushed(emptyList(), null, "album1"))
    }

    @Test
    fun sameIdRefreshDoesNotPush() {
        val current = frame("singer_wy_1")
        assertEquals(emptyList<DetailFrame>(), DetailNavStack.pushed(emptyList(), current, "singer_wy_1"))
    }

    @Test
    fun differentIdPushesCurrentFrame() {
        val singer = frame("singer_wy_1")
        assertEquals(listOf(singer), DetailNavStack.pushed(emptyList(), singer, "album9"))
    }

    /** 三级链：歌手 → 专辑 → 歌手 → 返回逐级弹栈（旧单槽在此恢复错层）。 */
    @Test
    fun threeLevelChainPopsInLifoOrder() {
        val singer = frame("singer_wy_1")
        val album = frame("album9")
        val artist2 = frame("singer_tx_2")
        var stack = DetailNavStack.pushed(emptyList(), null, singer.detail.id)
        stack = DetailNavStack.pushed(stack, singer, album.detail.id)
        stack = DetailNavStack.pushed(stack, album, artist2.detail.id)
        assertEquals(listOf(singer, album), stack)
        assertEquals(album, DetailNavStack.backFrame(stack))
        stack = DetailNavStack.popped(stack)
        assertEquals(singer, DetailNavStack.backFrame(stack))
        stack = DetailNavStack.popped(stack)
        assertNull(DetailNavStack.backFrame(stack))
        assertEquals(emptyList<DetailFrame>(), DetailNavStack.popped(stack))
    }

    /** 快照完整性：页头覆盖与加载失败标记随帧保存/恢复（返回后父页标题与失败卡不丢）。 */
    @Test
    fun snapshotCarriesHeaderOverrideAndLoadFailure() {
        val parent = frame("fav_wy_9", header = "fav_wy_9" to "", failed = "fav_wy_9")
        val stack = DetailNavStack.pushed(emptyList(), parent, "album3")
        val restored = DetailNavStack.backFrame(stack)
        assertEquals("fav_wy_9" to "", restored?.headerOverride)
        assertEquals("fav_wy_9", restored?.loadFailedId)
        assertEquals(parent.detail, restored?.detail)
        assertEquals(parent.songs, restored?.songs)
    }

    /** 压播放器链：专辑（根帧）→ 子专辑 → 弹栈回专辑；栈空后由调用方落播放器分支。 */
    @Test
    fun overPlayerChainPopsToRootAlbumThenEmpties() {
        val albumA = frame("albumA")
        val albumB = frame("albumB")
        var stack = DetailNavStack.pushed(emptyList(), null, albumA.detail.id)
        stack = DetailNavStack.pushed(stack, albumA, albumB.detail.id)
        assertEquals(albumA, DetailNavStack.backFrame(stack))
        stack = DetailNavStack.popped(stack)
        assertNull(DetailNavStack.backFrame(stack))
    }
}
