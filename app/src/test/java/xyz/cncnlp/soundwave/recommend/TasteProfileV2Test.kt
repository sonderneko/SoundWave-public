package xyz.cncnlp.soundwave.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.PersonalRecommendations
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import kotlin.random.Random

class TasteProfileV2Test {
    private fun song(id: String, singer: String, name: String = "Song $id", album: String = "", source: String = "wy", singerId: String = "") =
        Song(id, name, singer, source = source, album = album, singerId = singerId)

    private fun inputs(
        favorites: List<Song> = emptyList(),
        library: List<Song> = emptyList(),
        playlists: List<Playlist> = emptyList(),
        history: List<Song> = emptyList(),
        listened: List<TasteProfileV2.ListenedSong> = emptyList(),
        owned: Set<String> = emptySet(),
        dislikes: Map<String, Int> = emptyMap(),
        totalPlays: Int = 0
    ) = TasteProfileV2.Inputs(favorites, library, playlists, history, listened, emptyList(), owned, dislikes, totalPlays)

    @Test
    fun favoriteEvidenceCapsAtFortyPerArtist() {
        val profile = TasteProfileV2.build(inputs(
            favorites = (1..6).map { song("f$it", "Artist A") } + listOf(song("g1", "Artist B"))
        ))
        assertEquals(40.0, profile.affinityOf("Artist A"), 1e-9)
        assertEquals(8.0, profile.affinityOf("Artist B"), 1e-9)
    }

    @Test
    fun libraryEvidenceCapsAtTwentyFourPerArtist() {
        val profile = TasteProfileV2.build(inputs(
            library = (1..10).map { song("l$it", "Artist C") }
        ))
        assertEquals(24.0, profile.affinityOf("Artist C"), 1e-9)
    }

    @Test
    fun playlistCoOccurrenceGrantsFortyPercentOfTopCompanion() {
        val playlist = Playlist(
            id = "p1", name = "Mix", songs = mutableListOf(song("pa", "Artist A"), song("pb", "Artist B"))
        )
        val profile = TasteProfileV2.build(inputs(
            favorites = listOf(song("f1", "Artist A"), song("f2", "Artist A")),
            playlists = listOf(playlist)
        ))
        // A: favorites 16 + library 3 = 19 base. B: library 3 + bonus 0.4 * 19 = 7.6 -> 10.6.
        assertEquals(19.0, profile.affinityOf("Artist A"), 1e-9)
        assertEquals(3.0 + 0.4 * 19.0, profile.affinityOf("Artist B"), 1e-9)
    }

    @Test
    fun coOccurrenceNeedsAHighAffinityCompanion() {
        val playlist = Playlist(
            id = "p2", name = "Low", songs = mutableListOf(song("pe", "Artist E"), song("pf", "Artist F"))
        )
        val profile = TasteProfileV2.build(inputs(playlists = listOf(playlist)))
        // Both artists only have library evidence (3 < 8): no co-occurrence bonus.
        assertEquals(3.0, profile.affinityOf("Artist F"), 1e-9)
    }

    @Test
    fun artistWithTwoDislikesAndNoOtherEvidenceIsDemoted() {
        val profile = TasteProfileV2.build(inputs(
            library = listOf(song("x1", "Artist X"), song("x2", "Artist X"), song("y1", "Artist Y")),
            favorites = listOf(song("z1", "Artist Z")),
            listened = listOf(TasteProfileV2.ListenedSong("wy:w1", "W Song", "Artist W", 900.0, 3.0)),
            dislikes = mapOf(
                TasteProfileV2.artistKey("Artist X") to 2,
                TasteProfileV2.artistKey("Artist Y") to 1,
                TasteProfileV2.artistKey("Artist Z") to 2,
                TasteProfileV2.artistKey("Artist W") to 2
            )
        ))
        // X: library 6 * 0.2 = 1.2, demoted. Y: only 1 dislike -> untouched.
        // Z: favorited -> never demoted. W: has listening evidence -> never demoted.
        assertEquals(1.2, profile.affinityOf("Artist X"), 1e-9)
        assertTrue(TasteProfileV2.artistKey("Artist X") in profile.demotedArtists)
        assertEquals(3.0, profile.affinityOf("Artist Y"), 1e-9)
        assertFalse(TasteProfileV2.artistKey("Artist Y") in profile.demotedArtists)
        assertEquals(8.0, profile.affinityOf("Artist Z"), 1e-9)
        assertFalse(TasteProfileV2.artistKey("Artist Z") in profile.demotedArtists)
        assertFalse(TasteProfileV2.artistKey("Artist W") in profile.demotedArtists)
    }

    @Test
    fun statsEvidenceCapsPerArtist() {
        val listened = (1..10).map {
            TasteProfileV2.ListenedSong("wy:s$it", "S$it", "Artist S", 3600.0, 12.0)
        }
        val profile = TasteProfileV2.build(inputs(listened = listened))
        // Per-song stat score = 12 + 2 = 14, ten songs = 140 -> capped at 36.
        assertEquals(36.0, profile.affinityOf("Artist S"), 1e-9)
    }

    @Test
    fun songScoreCombinesArtistAlbumAndTrackEvidence() {
        val profile = TasteProfileV2.build(inputs(
            favorites = listOf(song("f1", "Artist A", album = "Alb")),
            listened = listOf(TasteProfileV2.ListenedSong("wy:x1", "Heard", "Artist A", 3600.0, 3.0))
        ))
        val candidate = song("x1", "Artist A", name = "Heard", album = "Alb")
        // Artist affinity = favorites 8 + stats (3 plays + 3600s -> 3 + 2 = 5) = 13.
        // songScore = artist 13 + album 4 * 0.5 + track 5 * 0.5 = 13 + 2 + 2.5 = 17.5
        assertEquals(17.5, profile.songScore(candidate), 1e-9)
        assertEquals(13.0, profile.songScore(song("zz", "Artist A", name = "Other")), 1e-9)
    }

    @Test
    fun topHeavySamplingProbabilityReachesSixtyPercent() {
        val probabilities = TasteProfileV2.samplingProbabilities(listOf(100.0) + List(9) { 1.0 })
        // (100 + 5) / (105 + 9 * 6) = 105 / 159 ~ 0.66
        assertTrue(probabilities.first() >= 0.6)
        assertEquals(1.0, probabilities.sum(), 1e-9)
    }

    @Test
    fun weightedSamplingFavorsTopArtistInPractice() {
        val items = listOf("top") + (1..9).map { "tail$it" }
        val weights = listOf(100.0) + List(9) { 1.0 }
        val random = Random(42)
        var topHits = 0
        repeat(2000) {
            if (TasteProfileV2.weightedSample(items, weights, 1, random).first() == "top") topHits++
        }
        assertTrue("top share was ${topHits / 2000.0}", topHits / 2000.0 >= 0.55)
    }

    @Test
    fun heartQueryPlanAnchorsCurrentSongArtist() {
        val taste = TasteProfileV2.build(inputs(
            favorites = (1..5).map { song("f$it", "Artist A") }
        ))
        val current = song("cur", "New Singer", name = "Current")
        val plan = TasteProfileV2.heartQueryPlan(listOf("Artist A"), taste, current, Random(7))
        // Only two candidates exist, so both are queried: the seed and the session anchor.
        assertEquals(setOf("Artist A", "New Singer"), plan.toSet())
        assertEquals(2, plan.size)
    }

    @Test
    fun heartQueryPlanWithoutTasteStillSamplesSeedsAndAnchor() {
        val plan = TasteProfileV2.heartQueryPlan(
            listOf("Seed Artist"), null, song("cur", "Anchor Artist", name = "Now"), Random(3)
        )
        assertEquals(setOf("Seed Artist", "Anchor Artist"), plan.toSet())
    }

    @Test
    fun rankForTodayDemotesOwnedBelowFresh() {
        val ownedSong = song("o1", "Artist B", name = "Owned Hit")
        val freshSong = song("n1", "Artist A", name = "Fresh Find")
        val taste = TasteProfileV2.build(inputs(
            favorites = listOf(song("f1", "Artist A"), song("f2", "Artist B"), song("f3", "Artist B")),
            owned = setOf(PersonalRecommendations.songIdentity(ownedSong))
        ))
        // Raw scores: B artist 16 > A artist 8, but owned * 0.35 = 5.6 < 8 -> fresh first.
        val ranked = TasteProfileV2.rankForToday(listOf(ownedSong, freshSong), taste, PersonalRecommendations::songIdentity)
        assertEquals(listOf(freshSong, ownedSong), ranked)
    }

    @Test
    fun ownedRateStaysLowWhenFreshSurplusExists() {
        val owned = (1..10).map { song("o$it", "Artist A", name = "Owned $it") }
        val fresh = (1..10).map { song("n$it", "Artist A", name = "Fresh $it") }
        val taste = TasteProfileV2.build(inputs(
            favorites = listOf(song("f1", "Artist A")),
            owned = owned.mapTo(mutableSetOf()) { PersonalRecommendations.songIdentity(it) }
        ))
        val picked = TasteProfileV2.rankForToday(owned + fresh, taste, PersonalRecommendations::songIdentity).take(10)
        val ownedPicked = picked.count { PersonalRecommendations.songIdentity(it) in taste.ownedIdentities }
        assertTrue("owned picked $ownedPicked", ownedPicked <= 2)
    }

    @Test
    fun albumCapKeepsThreePerAlbumInFrontAndBackfills() {
        val sameAlbum = (1..5).map { song("a$it", "Artist A", name = "Track $it", album = "Same") }
        val others = (1..3).map { song("b$it", "Artist B", name = "Other $it", album = "Other $it") }
        val shaped = TasteProfileV2.enforceAlbumCap(sameAlbum + others)
        assertEquals(8, shaped.size)
        assertEquals(3, shaped.take(6).count { it.album == "Same" })
        assertEquals(5, shaped.count { it.album == "Same" })
        assertEquals(listOf("a4", "a5"), shaped.takeLast(2).map { it.id })
    }

    @Test
    fun pickSimilarChoosesHighestScoredSameArtistCandidate() {
        val item = song("me", "Artist A", name = "My Song")
        val sameRecording = song("other-id", "Artist A", name = "My Song")
        val otherArtist = song("no", "Artist C", name = "Unrelated")
        val plain = song("p1", "Artist A", name = "Plain")
        val collab = song("c1", "Artist A / Artist B", name = "Collab")
        val queued = song("q1", "Artist A", name = "Queued")
        val taste = TasteProfileV2.build(inputs(
            favorites = listOf(song("f1", "Artist A")) + (1..4).map { song("fb$it", "Artist B") }
        ))
        val picked = TasteProfileV2.pickSimilar(
            item, listOf(sameRecording, otherArtist, plain, collab, queued), setOf(queued.key()), taste
        )
        // Collab scores 8 + 32 = 40 and shares Artist A; same-recording/other-artist/queued are ineligible.
        assertEquals(collab, picked)
    }

    @Test
    fun pickSimilarReturnsNullWhenNothingEligible() {
        val item = song("me", "Artist A", name = "My Song")
        assertEquals(null, TasteProfileV2.pickSimilar(item, listOf(song("x", "Artist C", name = "Nope")), emptySet(), null))
        assertEquals(null, TasteProfileV2.pickSimilar(item, emptyList(), emptySet(), null))
    }

    @Test
    fun artistCatalogCollectsSingerIdsPerSource() {
        val profile = TasteProfileV2.build(inputs(
            favorites = listOf(song("f1", "Artist A", singerId = "wy-a"), song("f2", "Artist A", source = "kg", singerId = "kg-a"))
        ))
        val entries = profile.artistCatalog[TasteProfileV2.artistKey("Artist A")].orEmpty()
        assertEquals(setOf("wy" to "wy-a", "kg" to "kg-a"), entries.map { it.source to it.singerId }.toSet())
    }
}
