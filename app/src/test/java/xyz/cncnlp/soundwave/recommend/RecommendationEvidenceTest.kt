package xyz.cncnlp.soundwave.recommend

import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.*

class RecommendationEvidenceTest {
    @Test fun instrumentalRequiresTrackEvidenceNotAlbumOrUnknown() {
        assertFalse(StyleWeightedRecommendation.accepts(Song("a","Unknown","A",source="wy",album="纯音乐"),"wy",listOf("纯音乐")))
        assertTrue(StyleWeightedRecommendation.accepts(Song("a","Morning (Instrumental)","A",source="wy"),"wy",listOf("纯音乐")))
        for (conflict in listOf("Vocal","日漫","动漫","人声","karaoke")) {
            assertFalse(StyleWeightedRecommendation.accepts(Song("a","Morning (Instrumental)","A",source="wy",album=conflict),"wy",listOf("纯音乐")))
        }
    }
    @Test fun categoriesAndPlatformsCannotBorrowEvidence() {
        val rock=Song("a","摇滚现场","A",source="wy")
        assertTrue(StyleWeightedRecommendation.accepts(rock,"wy",listOf("摇滚")))
        assertFalse(StyleWeightedRecommendation.accepts(rock,"wy",listOf("民谣")))
        assertFalse(StyleWeightedRecommendation.accepts(rock,"kg",listOf("摇滚")))
    }
    @Test fun fingerprintCoversWeightOwnerExclusionTagsPlatformEnabledAndBatch() {
        val profile=PersonalRecommendations.buildProfile(emptyList(),listOf(Song("l","Like","Artist",source="wy")))
        val base=TodayRecommendationSlot.fingerprint("wy",profile,listOf("摇滚"))
        val changes=listOf(
            TodayRecommendationSlot.fingerprint("kg",profile,listOf("摇滚")),
            TodayRecommendationSlot.fingerprint("wy",profile,listOf("纯音乐")),
            TodayRecommendationSlot.fingerprint("wy",profile,listOf("摇滚"),enabled=false),
            TodayRecommendationSlot.fingerprint("wy",profile,listOf("摇滚"),batch=1),
            TodayRecommendationSlot.fingerprint("wy",profile.copy(exposureScope="other"),listOf("摇滚")),
            TodayRecommendationSlot.fingerprint("wy",profile.copy(excludedSongKeys=setOf("recent")),listOf("摇滚")),
            TodayRecommendationSlot.fingerprint("wy",profile.copy(queries=profile.queries.map {it.copy(weight=it.weight+1)}),listOf("摇滚"))
        )
        changes.forEach { assertNotEquals(base,it) }
        assertEquals(TodayRecommendationSlot.fingerprint("wy",profile,listOf("摇滚","纯音乐")),TodayRecommendationSlot.fingerprint("WY",profile,listOf("纯音乐","摇滚")))
    }
    @Test fun realLibraryAndAlbumSignalsDoNotBecomeFakeLikesOrGenres() {
        val track=Song("a","Known","Library Artist",source="wy",album="Known Album")
        val profile=PersonalRecommendations.buildProfile(emptyList(),emptyList(),librarySongs=listOf(track),favoriteAlbums=listOf(Playlist("album","Favorite Album")),favoritePlaylists=listOf(Playlist("p","纯音乐",songs=mutableListOf(track))),importedPlaylists=listOf(Playlist("i","import",songs=mutableListOf(track.copy(singer="Imported Artist")))))
        assertTrue(profile.queries.any {it.term=="Library Artist"})
        assertTrue(profile.queries.any {it.term=="Imported Artist"})
        assertTrue(profile.queries.any {it.term=="Favorite Album" && it.kind==PersonalRecommendations.QueryKind.ALBUM})
        assertFalse(profile.queries.any {it.term=="纯音乐"})
        assertFalse(PersonalRecommendations.songIdentity(track) in profile.excludedSongKeys)
    }
    @Test fun normalizedIdentityRetainsArtistAndVersion() {
        val a=Song("a","Ｔｒａｃｋ　Name","Ａ",source="wy")
        assertEquals(PersonalRecommendations.songIdentity(a),PersonalRecommendations.songIdentity(a.copy(id="b",name="track name",singer="A",source="kg")))
        assertNotEquals(PersonalRecommendations.songIdentity(a),PersonalRecommendations.songIdentity(a.copy(singer="B")))
        assertNotEquals(PersonalRecommendations.songIdentity(a),PersonalRecommendations.songIdentity(a.copy(name="Track Name (Live)")))
    }
    @Test fun categoryCandidatesUseObservedArtistAndAlbumPreference() {
        val preferred=Song("p","Morning Instrumental","Preferred Artist",source="wy")
        val unrelated=Song("u","Elsewhere Instrumental","Other Artist",source="wy")
        val profile=PersonalRecommendations.buildProfile(emptyList(),listOf(preferred.copy(id="liked",name="Older")))
        assertEquals(listOf(preferred,unrelated),PersonalRecommendations.rankCandidates(listOf(unrelated,preferred),profile))
    }
    @Test fun workerProfileRoundTripRetainsExpectedArtistAndWeight() {
        val profile=PersonalRecommendations.buildProfile(emptyList(),emptyList(),listenedSongs=listOf(ListenStats.SongStat("k","Same","Real Artist","",3600,5)))
        assertEquals(profile,TodayRecommendationScheduler.decode(TodayRecommendationScheduler.encode(profile)))
    }
}
