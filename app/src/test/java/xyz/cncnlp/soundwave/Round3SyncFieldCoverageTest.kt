package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real existing wire projection, not the un-deployed v2 reference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round3SyncFieldCoverageTest {
    private fun song(id: String) = Song(id=id,name=id,singer="artist",source="wy",cover="https://example.com/$id.jpg")
    @Test fun customAndImportedPlaylistsKeepSongsCoverOrderWhileScanLibraryDoesNot() {
        val bundle=LocalStore.UserBundle(
            favs=mutableListOf(song("favorite")),
            playlists=mutableListOf(
                Playlist(id="mine",name="mine",local=true,songs=mutableListOf(song("custom")),cover="https://example.com/custom.jpg",syncOrder=2,syncOrderAt=20),
                Playlist(id="remote",name="remote",local=false,source="wy",remoteId="45",songs=mutableListOf(song("imported")),favorited=true),
                Playlist(id="local",name="scan",local=true,songs=mutableListOf(song("device")))
            ),avatarB64="fixture-avatar")
        val j=bundle.toCloudJson(includeLocalUris=false,compact=true)
        val rows=j.getJSONArray("pls")
        fun row(id:String)=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optString("id")==id }
        assertEquals(1,j.getJSONArray("favs").length())
        assertEquals(1,row("mine").getJSONArray("songs").length())
        assertEquals("https://example.com/custom.jpg",row("mine").getString("cv"))
        assertEquals(2,row("mine").getInt("_swLibOrder"))
        assertEquals(1,row("remote").getJSONArray("songs").length())
        assertEquals("45",row("remote").getString("remoteId"))
        assertTrue(row("remote").getBoolean("favorited"))
        assertEquals(0,row("local").getJSONArray("songs").length())
        assertEquals(0,row("liked").getJSONArray("songs").length())
        assertEquals("fixture-avatar",j.getString("avatar"))
    }
    @Test fun deviceUriIsNotLeakedAndLifetimeCountersAreNotClaimedSynced() {
        val s=song("uri").copy(cover="file:///storage/private-cover",resolvedUrl="content://media/private")
        val bundle=LocalStore.UserBundle(favs=mutableListOf(s),songPlayCountCounters=linkedMapOf("wy:uri" to linkedMapOf("installation" to 99L)))
        val j=bundle.toCloudJson(includeLocalUris=false,includePlayCountCounters=false)
        assertFalse(j.toString().contains("file:///")); assertFalse(j.toString().contains("content://"))
        assertFalse(j.has("playCountCounters"))
    }
    @Test fun existingHistoryProjectionIsBoundedRatherThanFullLifetimeHistory() {
        val now=System.currentTimeMillis()
        val bundle=LocalStore.UserBundle(history=(1..400).map { song("$it").copy(playedAt=now-it) }.toMutableList())
        val j=bundle.toCloudJson(historyLimit=PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT,includeLocalUris=false)
        assertEquals(300,j.getJSONArray("history").length())
    }
}
