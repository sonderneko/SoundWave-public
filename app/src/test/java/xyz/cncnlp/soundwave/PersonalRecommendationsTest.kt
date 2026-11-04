package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRecommendationsTest {
    @Test
    fun profileUsesRecentPlaysFavoritesAndListeningStatsWithoutInventedGenres() {
        val now = 2_000_000_000_000L
        val recent = Song("r1", "Recent Track", "Recent Artist", source = "wy", album = "Recent Album", playedAt = now - 60_000L)
        val old = Song("o1", "Old Track", "Old Artist", source = "wy", album = "Old Album", playedAt = now - 31L * 24 * 60 * 60 * 1000)
        val liked = Song("f1", "Loved Track", "Loved Artist", source = "kg", album = "Loved Album")

        val profile = PersonalRecommendations.buildProfile(
            history = listOf(recent, old),
            likedSongs = listOf(liked),
            listenedArtists = listOf("Long-Listen Artist" to 90_000L),
            nowMs = now
        )

        assertTrue(profile.queries.any { it.term == "Recent Artist" })
        assertTrue(profile.queries.any { it.term == "Loved Artist" })
        assertTrue(profile.queries.any { it.term == "Long-Listen Artist" })
        assertTrue(PersonalRecommendations.songIdentity(recent) in profile.excludedSongKeys)
        assertTrue(PersonalRecommendations.songIdentity(liked) in profile.excludedSongKeys)
        assertFalse(PersonalRecommendations.songIdentity(old) in profile.excludedSongKeys)
        assertTrue(profile.signature.isNotBlank())
        assertTrue(profile.activitySignature.isNotBlank())
    }

    @Test
    fun songIdentityDeduplicatesSameTitleAndArtistsAcrossProviders() {
        val netease = Song("100", "Track Name", "Artist A / Artist B", source = "wy")
        val kugou = Song("different-provider-id", "Track Name", "Artist B, Artist A", source = "kg")

        assertEquals(
            PersonalRecommendations.songIdentity(netease),
            PersonalRecommendations.songIdentity(kugou)
        )
    }

    @Test
    fun stableTasteSignatureDoesNotChangeWhenOnlyObservedWeightsShift() {
        val song = Song("1", "Track", "Same Artist", source = "wy", album = "Album")
        val first = PersonalRecommendations.buildProfile(listOf(song), emptyList(), emptyList(), nowMs = 10_000L)
        val sameTaste = PersonalRecommendations.buildProfile(listOf(song), emptyList(), listOf("Same Artist" to 5L), nowMs = 10_000L)

        assertEquals(first.signature, sameTaste.signature)
        assertTrue(first.activitySignature != sameTaste.activitySignature)
    }

    @Test
    fun playlistExposureScopeIsStablePerOwnerAndNeverStoresOwnerText() {
        val alice = PersonalRecommendations.buildProfile(emptyList(), emptyList(), profileOwner = "alice@example.com")
        val aliceAgain = PersonalRecommendations.buildProfile(emptyList(), emptyList(), profileOwner = "ALICE@example.com")
        val bob = PersonalRecommendations.buildProfile(emptyList(), emptyList(), profileOwner = "bob@example.com")
        assertEquals(alice.exposureScope, aliceAgain.exposureScope)
        assertTrue(alice.exposureScope != bob.exposureScope)
        assertFalse(alice.exposureScope.contains("alice", ignoreCase = true))
    }

    @Test
    fun listeningStatsAddRealPlayedTrackAndArtistQueriesAndAffectActivitySignature() {
        val history = listOf(Song("h1", "Recent", "History Artist", source = "wy"))
        val firstStat = ListenStats.SongStat("stat-key", "Tracked Title", "Stats Artist", "", 7_200L, 9)
        val profile = PersonalRecommendations.buildProfile(
            history = history,
            likedSongs = emptyList(),
            listenedSongs = listOf(firstStat)
        )
        val changedCount = PersonalRecommendations.buildProfile(
            history = history,
            likedSongs = emptyList(),
            listenedSongs = listOf(firstStat.copy(cnt = 10))
        )
        assertTrue(profile.queries.any { it.kind == PersonalRecommendations.QueryKind.TRACK && it.term == "Tracked Title" })
        assertTrue(profile.queries.any { it.kind == PersonalRecommendations.QueryKind.ARTIST && it.term == "Stats Artist" })
        assertTrue(profile.activitySignature != changedCount.activitySignature)
    }

    @Test
    fun recommendationArtistDiversificationAllowsAtMostTwoPerArtistInFirstPass() {
        val songs = listOf(
            Song("a1", "A1", "Artist A", source = "wy"),
            Song("a2", "A2", "Artist A", source = "wy"),
            Song("a3", "A3", "Artist A", source = "wy"),
            Song("b1", "B1", "Artist B", source = "wy"),
            Song("c1", "C1", "Artist C", source = "wy")
        )
        val selected = PersonalRecommendations.diversifyArtists(songs, 3)
        assertEquals(listOf("a1", "a2", "b1"), selected.map { it.id })
    }

    @Test
    fun searchQueriesKeepArtistAlbumAndPlayedTrackSignals() {
        val queries = buildList {
            repeat(8) { add(PersonalRecommendations.TasteQuery("Artist $it", 20 - it, PersonalRecommendations.QueryKind.ARTIST)) }
            repeat(4) { add(PersonalRecommendations.TasteQuery("Album $it", 10 - it, PersonalRecommendations.QueryKind.ALBUM)) }
            repeat(4) { add(PersonalRecommendations.TasteQuery("Played Track $it", 30 - it, PersonalRecommendations.QueryKind.TRACK)) }
        }
        val selected = PersonalRecommendations.selectQueries(queries, 8)
        assertEquals(4, selected.count { it.kind == PersonalRecommendations.QueryKind.ARTIST })
        assertEquals(2, selected.count { it.kind == PersonalRecommendations.QueryKind.ALBUM })
        assertEquals(2, selected.count { it.kind == PersonalRecommendations.QueryKind.TRACK })
    }

    @Test
    fun seenPlaylistWindowMovesReexposedIdsToRecentEndAndStaysBounded() {
        val merged = PersonalRecommendations.mergeSeenKeys(
            existing = listOf("a", "b", "c"),
            added = listOf("a", "d"),
            limit = 3
        )
        assertEquals(listOf("c", "a", "d"), merged.toList())
    }
}
