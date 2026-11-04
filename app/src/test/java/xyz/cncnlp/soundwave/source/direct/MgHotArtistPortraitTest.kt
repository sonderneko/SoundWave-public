package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import xyz.cncnlp.soundwave.Song

class MgHotArtistPortraitTest {
    @Test fun onlyVerifiedMiguSongsSupplyResourceIds() {
        assertEquals(listOf("mg-song"), mgHotArtistResourceIds(listOf(
            Song("other", "", "", "wy"), Song("unknown", "", ""),
            Song("mg-song", "", "", "mg"), Song("mg-song", "", "", "mg")
        ), 12))
    }
    @Test fun realResourceUsesSingerPortraitNotAlbumArtwork() {
        val raw = javaClass.getResource("/mg-hot-artist-resource.json")!!.readText()
        val artist = parseMgHotArtists(raw, setOf("3790007"), 12).single()
        assertEquals("112", artist.id)
        assertEquals("周杰伦", artist.name)
        assertTrue(artist.avatar.contains("/00/5l/4k/"))
        assertFalse(artist.avatar.contains("/00/4t/9y/"))
    }
    @Test fun sameNameDifferentIdsNeverSharePortraits() {
        val root = JSONObject(javaClass.getResource("/mg-hot-artist-resource.json")!!.readText())
        val item = root.getJSONArray("resource").getJSONObject(0)
        item.getJSONArray("artists").put(JSONObject().put("id", "999").put("name", "周杰伦"))
        val artists = parseMgHotArtists(root.toString(), setOf("3790007"), 12)
        assertEquals(2, artists.size)
        assertTrue(artists.single { it.id == "999" }.avatar.isBlank())
        assertTrue(artists.single { it.id == "112" }.avatar.isNotBlank())
    }
    @Test fun missingSingerPortraitNeverUsesAlbumImage() {
        val root = JSONObject(javaClass.getResource("/mg-hot-artist-resource.json")!!.readText())
        root.getJSONArray("resource").getJSONObject(0).remove("singerImg")
        assertTrue(parseMgHotArtists(root.toString(), setOf("3790007"), 12).single().avatar.isBlank())
    }
    @Test fun unrelatedResourceCannotContributePortraits() {
        val raw = javaClass.getResource("/mg-hot-artist-resource.json")!!.readText()
        assertTrue(parseMgHotArtists(raw, setOf("other"), 12).isEmpty())
    }
}
