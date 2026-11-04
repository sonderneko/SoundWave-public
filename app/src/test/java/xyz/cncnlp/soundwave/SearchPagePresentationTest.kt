package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPagePresentationTest {
    @Test
    fun recommendationPreviewKeepsFirstThreeUniqueSongsInOrder() {
        val songs = listOf(
            Song("1", "First", "Artist A", source = "wy"),
            Song("2", "Second", "Artist B", source = "wy"),
            Song("duplicate", "First", "Artist A", source = "kg"),
            Song("3", "Third", "Artist C", source = "wy"),
            Song("4", "Fourth", "Artist D", source = "wy")
        )

        assertEquals(listOf("1", "2", "3"), searchRecommendationPreview(songs).map { it.id })
    }

    @Test
    fun recommendationPreviewReturnsAvailableSongsWhenFewerThanThree() {
        val songs = listOf(
            Song("1", "First", "Artist A", source = "wy"),
            Song("2", "Second", "Artist B", source = "wy")
        )

        assertEquals(songs, searchRecommendationPreview(songs))
        assertEquals(emptyList<Song>(), searchRecommendationPreview(emptyList()))
    }
}
