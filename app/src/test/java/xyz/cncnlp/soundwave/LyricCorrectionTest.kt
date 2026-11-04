package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricCorrectionTest {
    private class MemoryStore : LyricCorrectionOverrideStore {
        val values = mutableMapOf<String, String>()
        override fun load(identity: String): String = values[identity].orEmpty()
        override fun save(identity: String, lyrics: String): Boolean {
            values[identity] = lyrics
            return true
        }
        override fun clear(identity: String): Boolean {
            values.remove(identity)
            return true
        }
    }

    private val validLyrics = """[00:00.00]第一句歌词内容清晰
[00:05.00]第二句歌词内容清晰
[00:10.00]第三句歌词内容清晰"""

    @Test
    fun exactNativeIdsUsePlatformSemanticsAndNeverCrossUseIdentifiers() {
        val migu = Song(
            id = "migu-content-id",
            name = "歌名",
            singer = "歌手",
            source = "mg",
            copyrightId = "migu-copyright-id"
        )
        assertEquals("migu-copyright-id", lyricCorrectionNativeId(migu))
        assertNull("Migu contentId/Song.id is not a lyric copyrightId", lyricCorrectionNativeId(migu.copy(copyrightId = "")))

        val qq = Song(id = "qq-songmid", name = "歌名", singer = "歌手", source = "tx", mediaMid = "qq-file-media-mid")
        assertEquals("qq-songmid", lyricCorrectionNativeId(qq))
        assertNull("QQ mediaMid must not substitute for songmid", lyricCorrectionNativeId(qq.copy(id = "")))

        val kugouHash = "0123456789abcdef0123456789abcdef"
        assertEquals(kugouHash, lyricCorrectionNativeId(Song("other", "歌名", "歌手", source = "kg", hash = kugouHash)))
        assertNull(lyricCorrectionNativeId(Song("not-a-hash", "歌名", "歌手", source = "kg")))
        assertEquals("12345", lyricCorrectionNativeId(Song("MUSIC_12345", "歌名", "歌手", source = "kw")))
        assertEquals("12345", lyricCorrectionNativeId(Song("12345", "歌名", "歌手", source = "wy")))
    }

    @Test
    fun miguContentIdSongKeyIsSeparatedByCopyrightVariant() {
        val first = Song("shared-content", "同名歌曲", "歌手", source = "mg", copyrightId = "copyright-one")
        val otherVersion = first.copy(copyrightId = "copyright-two")
        assertEquals(first.key(), otherVersion.key())
        assertTrue(lyricCorrectionIdentity(first) != lyricCorrectionIdentity(otherVersion))
    }

    @Test
    fun missingOrUnreliableIdsFallBackToSameSourceTitleArtistAndDurationCandidates() {
        val target = Song(
            id = "",
            name = "夜曲",
            singer = "周杰伦",
            source = "tx",
            duration = 240_000L,
            mediaMid = "file-only-mid"
        )
        assertNull(lyricCorrectionNativeId(target))
        val matching = Song("other-songmid", "夜曲", "周杰伦", source = "tx", duration = 242_000L)
        val wrongVersion = matching.copy(id = "live-songmid", album = "现场版", duration = 280_000L)
        val crossPlatform = matching.copy(source = "wy", id = "123456")
        val ranked = rankTrustedLyricCorrectionSongs(target, listOf(wrongVersion, crossPlatform, matching))
        assertEquals(listOf(matching), ranked.map { it.song })
        assertTrue(ranked.single().similarity >= 0.80f)
    }

    @Test
    fun fallbackDoesNotTrustUnknownDurationsOrUnrelatedArtists() {
        val target = Song("", "夜曲", "周杰伦", source = "tx", duration = 240_000L)
        val missingDuration = Song("songmid-a", "夜曲", "周杰伦", source = "tx")
        val wrongArtist = Song("songmid-b", "夜曲", "另一位歌手", source = "tx", duration = 240_000L)
        assertTrue(rankTrustedLyricCorrectionSongs(target, listOf(missingDuration, wrongArtist)).isEmpty())

        val kuwoTarget = target.copy(source = "kw")
        val kuwoCandidate = Song("186016", "夜曲", "周杰伦", source = "kw", duration = 240L)
        assertEquals(240_000L, lyricCorrectionDurationMs(kuwoCandidate.duration))
        assertEquals(1, rankTrustedLyricCorrectionSongs(kuwoTarget, listOf(kuwoCandidate)).size)
    }

    @Test
    fun onlyExplicitlyConfirmedCandidateIsSaved() {
        val store = MemoryStore()
        val repository = LyricCorrectionRepository(store)
        val target = Song("track-1", "歌名", "歌手", source = "tx")
        val candidateSong = Song("candidate-mid", "歌名", "歌手", source = "tx", duration = 180_000L)
        val candidate = LyricCorrectionCandidate(candidateSong, validLyrics, 0.93f)

        assertEquals("search completion alone never writes", "", repository.load(target))
        assertTrue(repository.confirm(target, candidate))
        assertEquals(validLyrics, repository.load(target))
    }

    @Test
    fun cancellationAndFailedOrCrossPlatformCandidateLeaveExistingOverrideUntouched() {
        val store = MemoryStore()
        val repository = LyricCorrectionRepository(store)
        val target = Song("track-1", "歌名", "歌手", source = "tx")
        val existing = LyricCorrectionCandidate(target, validLyrics, 1f, exactNativeId = true)
        assertTrue(repository.confirm(target, existing))
        val beforeCancel = repository.load(target)

        // Cancel means no confirmation call; a failed search has no candidate to confirm.
        assertEquals(beforeCancel, repository.load(target))
        val wrongSource = LyricCorrectionCandidate(Song("12345", "歌名", "歌手", source = "wy"), "replacement", 1f)
        assertFalse(repository.confirm(target, wrongSource))
        val untrusted = LyricCorrectionCandidate(target, validLyrics, 0.2f)
        assertFalse(repository.confirm(target, untrusted))
        assertEquals(beforeCancel, repository.load(target))
    }

    @Test
    fun overridesAreIsolatedBySongKeyAndSource() {
        val store = MemoryStore()
        val repository = LyricCorrectionRepository(store)
        val first = Song("id-one", "同名", "歌手", source = "tx")
        val second = Song("id-two", "同名", "歌手", source = "tx")
        val otherSource = first.copy(source = "wy")
        val firstCandidate = LyricCorrectionCandidate(first.copy(duration = 180_000L), validLyrics, 1f, exactNativeId = true)
        val otherCandidate = LyricCorrectionCandidate(otherSource.copy(id = "123456"), "[00:00.00]另一版歌词内容足够长\n[00:04.00]另一版歌词内容足够长", 1f, exactNativeId = true)

        assertTrue(repository.confirm(first, firstCandidate))
        assertTrue(repository.confirm(otherSource, otherCandidate))
        assertEquals(validLyrics, repository.load(first))
        assertEquals("", repository.load(second))
        assertEquals(otherCandidate.lyrics, repository.load(otherSource))
        assertTrue(lyricCorrectionIdentity(first) != lyricCorrectionIdentity(otherSource))
    }

    @Test
    fun oldNetworkCacheIsLazyFallbackAndClearRestoresDefaultLyrics() {
        val store = MemoryStore()
        val repository = LyricCorrectionRepository(store)
        val song = Song("track-1", "歌名", "歌手", source = "tx")
        val correction = LyricCorrectionCandidate(song, validLyrics, 1f, exactNativeId = true)
        assertTrue(repository.confirm(song, correction))
        var oldCacheRead = false
        assertEquals(validLyrics, preferUserLyricCorrection(repository.load(song)) {
            oldCacheRead = true
            "old-network-cache"
        })
        assertFalse("old cache cannot replace correction", oldCacheRead)

        assertTrue(repository.clear(song))
        assertEquals("old-network-cache", preferUserLyricCorrection(repository.load(song)) { "old-network-cache" })
    }
}
