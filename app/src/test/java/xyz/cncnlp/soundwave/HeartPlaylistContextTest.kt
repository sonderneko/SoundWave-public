package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test

class HeartPlaylistContextTest {
    private fun song(id: String, singer: String) = Song(id, "Song $id", singer, "wy")

    @Test fun differentPlaylistSeedsSelectDifferentRealCandidates() {
        val a = Playlist("import-a", "A", source = "wy", remoteId = "101", songs = mutableListOf(song("a", "Artist A")))
        val b = Playlist("saved-b", "B", source = "kg", remoteId = "202", songs = mutableListOf(song("b", "Artist B")))
        val contexts = listOf(HeartPlaylistContext.from(a, a.songs), HeartPlaylistContext.from(b, b.songs))
        val hits = listOf(song("hit-a", "Artist A"), song("hit-b", "Artist B"), song("noise", "Unknown"))
        assertEquals(listOf("Artist A"), contexts[0].profile.queries)
        assertEquals(listOf("Artist B"), contexts[1].profile.queries)
        assertEquals(listOf(hits[0]), HeartRecommendationPolicy.candidates(contexts[0].profile, hits, emptyList(), emptyList(), "wy"))
        assertEquals(listOf(hits[1]), HeartRecommendationPolicy.candidates(contexts[1].profile, hits, emptyList(), emptyList(), "kg"))
        assertNotEquals(contexts[0].seenPreferenceKey, contexts[1].seenPreferenceKey)
    }

    @Test fun playlistsSharingAnArtistStillExcludeTheirOwnSongsNotAnotherListsSeeds() {
        val a = song("a", "Shared Artist")
        val b = song("b", "Shared Artist")
        val fresh = song("fresh", "Shared Artist")
        val pa = HeartPlaylistContext.from(Playlist("a", "A"), listOf(a)).profile
        val pb = HeartPlaylistContext.from(Playlist("b", "B"), listOf(b)).profile
        val hits = listOf(a, b, fresh)
        assertEquals(listOf(b, fresh), HeartRecommendationPolicy.candidates(pa, hits, emptyList(), emptyList(), "wy"))
        assertEquals(listOf(a, fresh), HeartRecommendationPolicy.candidates(pb, hits, emptyList(), emptyList(), "wy"))
    }

    @Test fun providerLocalAndRemoteIdentitiesSurviveWithoutNameBasedCollisions() {
        val p = Playlist("collection-1", "Original", source = "wy", remoteId = "remote-42")
        val ctx = HeartPlaylistContext.from(p, listOf(song("seed", "Artist")))
        assertEquals(heartPlaylistKey(p), ctx.key)
        assertEquals("wy", ctx.source)
        assertEquals(ctx.key, heartPlaylistKey(p.copy(name = "Renamed", cover = "new-cover")))
        assertNotEquals(ctx.key, heartPlaylistKey(p.copy(source = "kg")))
        assertNotEquals(ctx.key, heartPlaylistKey(p.copy(id = "album-collection")))
        assertNotEquals(ctx.key, heartPlaylistKey(p.copy(remoteId = "other-remote")))
        assertNotEquals(heartPlaylistKey(p.copy(id = "a|b", remoteId = "c")),
            heartPlaylistKey(p.copy(id = "a", remoteId = "b|c")))
        assertEquals(ctx.seenPreferenceKey, HeartPlaylistContext.from(p.copy(name = "New"), emptyList()).seenPreferenceKey)
    }

    @Test fun profileSnapshotsPlaylistAndNeverInventsAnEmptyPlaylistFallback() {
        val seeds = mutableListOf(song("a", "Artist A"))
        val playlist = Playlist("a", "A", songs = seeds)
        val ctx = HeartPlaylistContext.from(playlist, seeds)
        seeds.clear()
        assertEquals(listOf("Artist A"), ctx.profile.queries)
        assertTrue(HeartPlaylistContext.from(playlist, seeds).profile.seeds.isEmpty())
    }

    @Test fun playlistSearchSourcesAreRealCanonicalProvidersOnly() {
        val playlist = Playlist("mixed", "Mixed", source = "script:test")
        val songs = listOf(song("a", "A").copy(source = "local"), song("b", "B").copy(source = "tx"),
            song("c", "C").copy(source = "mg"), song("d", "D").copy(source = "qq"))
        assertEquals(listOf("tx", "mg"), HeartPlaylistContext.from(playlist, songs).searchSources)
        assertTrue(HeartPlaylistContext.from(playlist, listOf(song("e", "E").copy(source = "unknown"))).searchSources.isEmpty())
        for (source in listOf("wy", "kg", "kw", "tx", "mg")) {
            assertEquals(listOf(source), HeartPlaylistContext.from(playlist.copy(source = source), emptyList()).searchSources)
        }
    }

}
