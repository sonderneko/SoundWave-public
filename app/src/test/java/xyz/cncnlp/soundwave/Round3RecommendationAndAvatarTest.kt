package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round3RecommendationAndAvatarTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clearCache() { ctx.getSharedPreferences("artist-avatar-cache-v1", 0).edit().clear().commit() }
    private fun artist(id:String="123", name:String="林", source:String="wy", cover:String="https://example.com/artist.jpg") = Song(singer="", id=id, name=name, source=source, cover=cover)
    @Test fun exactProviderArtistEntityOnly() {
        assertEquals("123", ArtistAvatarRepository.match("wy", "林", listOf(artist()))?.artistId)
        assertNull(ArtistAvatarRepository.match("kg", "林", listOf(artist())))
        assertNull(ArtistAvatarRepository.match("wy", "林", listOf(artist(name="林乐队"))))
        assertNull(ArtistAvatarRepository.match("wy", "林", listOf(artist(), artist(id="456"))))
        assertNull(ArtistAvatarRepository.match("wy", "林/张", listOf(artist(name="林/张"))))
        assertNull(ArtistAvatarRepository.match("wy", "林", listOf(artist(cover="file:///tmp/a.jpg"))))
        assertNull(ArtistAvatarRepository.match("wy", "林", listOf(artist(cover="https://user:pass@example.com/a.jpg"))))
    }
    @Test fun positiveCacheRetainsIdAndAvoidsSecondSearch() = runBlocking {
        var calls=0
        val search: suspend (String,String)->List<Song> = { _,_-> calls++; listOf(artist()) }
        val a=ArtistAvatarRepository.load(ctx,"wy","林",1000L,search)
        val b=ArtistAvatarRepository.load(ctx,"wy","林",1001L,search)
        assertEquals(a,b); assertEquals("123",b?.artistId); assertEquals(1,calls)
    }
    @Test fun failureIsPlaceholderAndNegativeCacheExpires() = runBlocking {
        var calls=0
        val search: suspend (String,String)->List<Song> = { _,_-> calls++; error("offline") }
        assertNull(ArtistAvatarRepository.load(ctx,"wy","林",1000L,search))
        assertNull(ArtistAvatarRepository.load(ctx,"wy","林",1001L,search)); assertEquals(1,calls)
        assertNull(ArtistAvatarRepository.load(ctx,"wy","林",601001L,search)); assertEquals(2,calls)
    }
    @Test fun concurrentSameArtistSearchIsCoalesced() = runBlocking {
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val search: suspend (String,String)->List<Song> = { _,_-> calls.incrementAndGet(); delay(50); listOf(artist()) }
        val rows=(1..6).map { async { ArtistAvatarRepository.load(ctx,"wy","林",1000L,search) } }.awaitAll()
        assertEquals(1,calls.get()); assertTrue(rows.all { it?.artistId == "123" })
    }
    @Test fun categoryFeaturedAreTopThreeAndNeverRepeatedBelow() {
        fun pl(id:String, plays:Long)=Playlist(id=id,name=id,source="wy",remoteId=id,playCount=plays)
        val rows=listOf(pl("a",1),pl("b",5),pl("c",3),pl("d",4),pl("b",5))
        val top=PlaylistPlazaSelection.featured(rows)
        assertEquals(listOf("b","d","c"),top.map { it.id })
        assertEquals(listOf("a"),PlaylistPlazaSelection.remainder(rows,top).map { it.id })
    }
}
