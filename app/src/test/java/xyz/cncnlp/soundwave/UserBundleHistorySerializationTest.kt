package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBundleHistorySerializationTest {
    private fun historySong() = Song(
        id = "history-song",
        name = "History Song",
        singer = "History Artist",
        source = "wy",
        playedAt = System.currentTimeMillis()
    )

    @Test
    fun omitsHistoryFieldWhenHistoryIsExcluded() {
        val bundle = LocalStore.UserBundle(history = mutableListOf(historySong()))

        val json = bundle.toCloudJson(includeHistory = false)

        assertFalse(json.has("history"))
    }

    @Test
    fun serializesHistoryWhenHistoryIsIncluded() {
        val song = historySong()
        val bundle = LocalStore.UserBundle(history = mutableListOf(song))

        val history = bundle.toCloudJson(includeHistory = true).getJSONArray("history")

        assertEquals(1, history.length())
        assertEquals(song.id, history.getJSONObject(0).getString("id"))
        assertEquals(song.name, history.getJSONObject(0).getString("name"))
        assertEquals(song.playedAt, history.getJSONObject(0).getLong("playedAt"))
    }
}
