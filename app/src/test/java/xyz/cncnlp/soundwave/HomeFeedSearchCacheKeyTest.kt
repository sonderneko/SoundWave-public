package xyz.cncnlp.soundwave

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeFeedSearchCacheKeyTest {
    @Test
    fun distinctSearchesWithTheSameLegacyHashKeepSeparatePersistentResults() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("sw_feed_cache_v4", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        try {
            val firstKey = HomeFeedCache.searchKey("áà", "song", "wy", cross = false)
            val secondKey = HomeFeedCache.searchKey("àÿ", "song", "wy", cross = false)
            assertNotEquals(firstKey, secondKey)
            assertEquals("fixture must reproduce the old collision", firstKey.hashCode(), secondKey.hashCode())

            val firstSong = Song(id = "first", name = "First result", singer = "Artist", source = "wy")
            val secondSong = Song(id = "second", name = "Second result", singer = "Artist", source = "wy")
            HomeFeedCache.saveSearch(context, firstKey, listOf(firstSong), emptyList())
            HomeFeedCache.saveSearch(context, secondKey, listOf(secondSong), emptyList())

            assertEquals(firstSong, HomeFeedCache.loadSearch(context, firstKey)?.first?.single())
            assertEquals(secondSong, HomeFeedCache.loadSearch(context, secondKey)?.first?.single())

            val persistedKeys = preferences.all.keys
            assertEquals(2, persistedKeys.size)
            assertTrue(persistedKeys.all { it.matches(Regex("search_v2_[0-9a-f]{64}")) })
            assertFalse(persistedKeys.any { it.contains("áà") || it.contains("àÿ") })
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
