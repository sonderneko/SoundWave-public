package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverIdentityTest {
    @Test
    fun featuredPlaylistKeyIncludesProviderAndRemoteIdentityButIgnoresMutablePresentation() {
        val original = Playlist(id = "42", name = "旧标题", source = "tx", remoteId = "remote-42", cover = "old.jpg")
        val updated = original.copy(name = "新标题", cover = "new.jpg")
        assertEquals(featuredPlaylistStableKey(original, "wy"), featuredPlaylistStableKey(updated, "wy"))
        assertEquals(featuredPlaylistStableKey(original, "wy"), featuredPlaylistStableKey(updated.copy(id = "local-alias"), "wy"))
        assertNotEquals(
            featuredPlaylistStableKey(original, "wy"),
            featuredPlaylistStableKey(original.copy(source = "wy"), "wy")
        )
        assertNotEquals(
            featuredPlaylistStableKey(original, "wy"),
            featuredPlaylistStableKey(original.copy(remoteId = "other-42"), "wy")
        )
    }

    @Test
    fun perCardCoverStateCannotBeReusedAcrossItemsSourcesUrisOrSizes() {
        val url = "https://p.qpic.cn/music_cover/opaque-id/300"
        val same = coverRequestStateKey("tx", "playlist-1", url, 256)
        assertEquals(same, coverRequestStateKey("qq", "playlist-1", url, 256))
        assertNotEquals(same, coverRequestStateKey("wy", "playlist-1", url, 256))
        assertNotEquals(same, coverRequestStateKey("tx", "playlist-2", url, 256))
        assertNotEquals(same, coverRequestStateKey("tx", "playlist-1", url, 512))
        assertNotEquals(same, coverRequestStateKey("tx", "playlist-1", "https://p.qpic.cn/music_cover/another-id/300", 256))
    }

    @Test
    fun coverDecodeCacheKeyUsesNormalizedResourceAndTargetPixelSize() {
        val small = featuredCoverMemoryCacheKey("http://p.qpic.cn/music_cover/opaque-id/300", 256)
        val normalizedSame = featuredCoverMemoryCacheKey("https://p.qpic.cn/music_cover/opaque-id/600", 256)
        val large = featuredCoverMemoryCacheKey("https://p.qpic.cn/music_cover/opaque-id/600", 512)
        val other = featuredCoverMemoryCacheKey("https://p.qpic.cn/music_cover/other-id/600", 256)
        assertEquals(small, normalizedSame)
        assertNotEquals(small, large)
        assertNotEquals(small, other)
    }

    @Test
    fun remotePlaylistWithoutOwnCoverDoesNotMasqueradeFirstTrackArtwork() {
        val firstTrack = Song(id = "song-1", name = "曲目", singer = "歌手", source = "tx", cover = "https://example.test/track.jpg")
        val qqPlaylist = Playlist(id = "playlist-1", name = "歌单", source = "tx", local = false)
        val unknownSourcePlaylist = qqPlaylist.copy(source = "", id = "remote-unlabeled")
        assertEquals("", playlistDisplayCover(qqPlaylist, listOf(firstTrack)))
        assertEquals("", playlistDisplayCover(unknownSourcePlaylist, listOf(firstTrack)))
        assertEquals(firstTrack.cover, playlistDisplayCover(qqPlaylist.copy(id = "rank_board"), listOf(firstTrack)))
    }
}
