package xyz.cncnlp.soundwave

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * accept6-favempty：收藏条目的身份与 local 语义。
 * 收藏是远程书签 + 快照——条目必须能解析出回源 id，且 local 沿用来源真值，
 * 否则音乐库打开只剩空快照、详情还会被误标「本地」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistFavoriteEntryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun remoteIdPrefersStoredFieldAndFallsBackToIdParsing() {
        val stored = Playlist(id = "fav_wy_123", name = "a", source = "wy", remoteId = "987")
        assertEquals("987", playlistFavoriteRemoteId(stored))

        val legacy = Playlist(id = "fav_tx_M000abc_def", name = "b", source = "tx")
        assertEquals("M000abc_def", playlistFavoriteRemoteId(legacy))

        val notFavorite = Playlist(id = "local_1", name = "c", source = "")
        assertEquals("", playlistFavoriteRemoteId(notFavorite))
    }

    @Test
    fun remoteFavoriteEntryRequiresFavIdRemoteIdAndKnownPlatform() {
        assertTrue(
            Playlist(id = "fav_wy_123", name = "a", source = "wy", remoteId = "123")
                .isRemoteFavoriteEntry()
        )
        assertTrue(
            Playlist(id = "fav_kg_456", name = "b", source = "kg")
                .isRemoteFavoriteEntry()
        )
        // 来源缺失的老条目按仓库惯例回落 wy，仍可回源。
        assertTrue(
            Playlist(id = "fav_wy_123", name = "c", remoteId = "123")
                .isRemoteFavoriteEntry()
        )
        assertFalse(
            Playlist(id = "local_9", name = "d", source = "wy", remoteId = "9")
                .isRemoteFavoriteEntry()
        )
        assertFalse(
            Playlist(id = "fav_cloud_1", name = "e", source = "cloud", remoteId = "1")
                .isRemoteFavoriteEntry()
        )
    }

    @Test
    fun toggleKeepsRemoteIdentityAndSourceLocalTruth() {
        val remote = Playlist(
            id = "778899", name = "全球英文热歌", source = "wy",
            count = 100, local = false
        )
        var updated: MutableList<Playlist>? = null
        togglePlaylistFavorite(remote, "wy", emptyList(), { updated = it }, context)
        val entry = updated!!.single()
        assertEquals("fav_wy_778899", entry.id)
        assertEquals("778899", entry.remoteId)
        assertTrue(entry.favorited)
        // 回归点：旧版硬编码 local=true，把远程收藏冒充本地歌单。
        assertFalse(entry.local)
        assertTrue(entry.isRemoteFavoriteEntry())

        // 取消收藏后条目移除。
        togglePlaylistFavorite(remote, "wy", updated!!, { updated = it }, context)
        assertTrue(updated!!.isEmpty())
    }

    @Test
    fun togglePreservesGenuinelyLocalSourceFlag() {
        val localPl = Playlist(id = "local_42", name = "本地自建", source = "", local = true)
        var updated: MutableList<Playlist>? = null
        togglePlaylistFavorite(localPl, "wy", emptyList(), { updated = it }, context)
        assertTrue(updated!!.single().local)
    }

    /**
     * BATCH2 反馈 F3(c) 回归钉：导入在线歌单的快照残缺（标称 96 首、快照 0 首）必须
     * 判为需要回源水合——旧版音乐库 onOpen 对导入条目永不回源，详情直接假空。
     */
    @Test
    fun importedEntryWithStaleSnapshotNeedsHydration() {
        // 截图原型：网易云导入歌单，count=96 但落盘快照为空。
        val stale = Playlist(id = "5177082824", name = "午后纯音", source = "wy", remoteId = "5177082824", count = 96, local = false)
        assertTrue(stale.isRemoteImportedEntry())
        assertTrue(stale.needsRemoteHydration())

        // 快照部分残缺（少于标称）同样需要补全。
        val partial = stale.copy(songs = mutableListOf(Song(id = "1", name = "s", singer = "a", source = "wy")))
        assertTrue(partial.needsRemoteHydration())

        // 快照完整：不再触发（水合回填后自然退出该状态）。
        val full = stale.copy(count = 1, songs = mutableListOf(Song(id = "1", name = "s", singer = "a", source = "wy")))
        assertTrue(full.isRemoteImportedEntry())
        assertFalse(full.needsRemoteHydration())

        // 真空单（标称 0 且快照空）是平台侧事实，不属残缺——不触发收藏式失败卡逻辑的水合判定外延；
        // 空快照本身仍会走水合（拉回后若仍空则按空单呈现，与导入失败 return null 的口径一致）。
        val emptyClaim = Playlist(id = "123456", name = "e", source = "kg", count = 0, local = false)
        assertTrue(emptyClaim.isRemoteImportedEntry())
    }

    @Test
    fun hydrationPredicateExcludesLocalSystemAndFavoriteEntries() {
        // 自建本地歌单：local=true，永不回源。
        val mine = Playlist(id = "u_1719", name = "淼淼", source = "", local = true, count = 3)
        assertFalse(mine.isRemoteImportedEntry())
        assertFalse(mine.needsRemoteHydration())
        // 本地扫描歌单与 local_ 前缀条目排除。
        assertFalse(Playlist(id = "local", name = "本地歌曲", local = true).isRemoteImportedEntry())
        assertFalse(Playlist(id = "local_9", name = "l", source = "wy", local = false).isRemoteImportedEntry())
        // 系统条目排除。
        assertFalse(Playlist(id = "liked", name = "喜欢").isRemoteImportedEntry())
        assertFalse(Playlist(id = "history", name = "历史").isRemoteImportedEntry())
        // 收藏条目走收藏谓词，不落入导入谓词（二者由 needsRemoteHydration 统一汇合）。
        val fav = Playlist(id = "fav_wy_42", name = "f", source = "wy", remoteId = "42", count = 10)
        assertFalse(fav.isRemoteImportedEntry())
        assertTrue(fav.needsRemoteHydration())
        // 未知来源的远程形态条目不回源（避免把任意 id 当平台 id 打接口）。
        assertFalse(Playlist(id = "99887766", name = "x", source = "zz", count = 5).needsRemoteHydration())
    }
}
