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
}
