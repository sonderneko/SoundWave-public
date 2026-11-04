package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricCacheIdentityTest {
    @Test
    fun idlessSongsThatCollideInPlaybackKeyRemainDistinctInLyricCache() {
        val spaced = Song(id = "", name = "A B", singer = "Artist", source = "wy", duration = 180_000L)
        val compact = spaced.copy(name = "AB")

        assertEquals("the global playback identity intentionally remains unchanged", spaced.key(), compact.key())
        assertNotEquals(lyricCacheKey(spaced), lyricCacheKey(compact))
    }

    @Test
    fun availableProviderIdentifiersDisambiguateIdlessLyricCacheEntries() {
        val base = Song(id = "", name = "Song", singer = "Artist", source = "tx", duration = 200_000L)
        assertNotEquals(lyricCacheKey(base), lyricCacheKey(base.copy(mediaMid = "mid-a")))
        assertNotEquals(lyricCacheKey(base), lyricCacheKey(base.copy(copyrightId = "copyright-b")))
        assertNotEquals(lyricCacheKey(base), lyricCacheKey(base.copy(hash = "hash-c")))
    }

    @Test
    fun providerIdAndLyricFormatArePartOfStableIdentity() {
        val song = Song(id = "provider-1", name = "Song", singer = "Artist", source = "tx")
        assertEquals(lyricCacheKey(song), lyricCacheKey(song.copy(name = "Renamed")))
        assertNotEquals(lyricCacheKey(song), lyricCacheKey(song.copy(source = "wy")))
        assertNotEquals(lyricCacheKey(song), lyricCacheKey(song, "v2yrc"))
        assertEquals(lyricCacheKey(song, "v2yrc"), lyricCacheKey(song, "v2yrc"))
    }

    @Test
    fun fullLongKeysAndPreviouslySanitizedKeysMapToDistinctVersionedFiles() {
        val common = "x".repeat(100)
        val longA = common + "a-tail"
        val longB = common + "b-tail"
        val punctuationA = "source:song"
        val punctuationB = "source_song"

        assertNotEquals(lyricCacheFileName(longA), lyricCacheFileName(longB))
        assertNotEquals(lyricCacheFileName(punctuationA), lyricCacheFileName(punctuationB))
        assertEquals(lyricCacheFileName(longA), lyricCacheFileName(longA))
        assertTrue(lyricCacheFileName(longA).startsWith("lyric-v3-"))
    }
}
