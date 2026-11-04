package xyz.cncnlp.soundwave
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.source.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Batch4PlaylistBaselineTest {
 @Test fun categoryEmptyIsAuthoritative() { val r=ApiClient.parseCategoryPlaylistPayload("""{"data":{"list":[]}}""","wy"); assertTrue(r is PlaylistSearchOutcome.Success && r.playlists.isEmpty()) }
 @Test fun categoryMalformedFails() { assertEquals(PlaylistSearchOutcome.Failure,ApiClient.parseCategoryPlaylistPayload("<html>bad</html>","wy")) }
 @Test fun explicitFailureRejected() { assertEquals(PlaylistSearchOutcome.Failure,ApiClient.parseCategoryPlaylistPayload("""{"ok":false,"list":[]}""","wy")); assertEquals(FeedListOutcome.Failure,ApiClient.parseRecommendPlaylistsPayloadOutcome("""{"data":{"success":false,"list":[]}}""","wy")) }
 @Test fun aliasesMetadataAndSourceFilteringPreserved() { val r=ApiClient.parseCategoryPlaylistPayload("""{"records":[{"pid":"a","nm":"A &amp; B","platform":"wy","trackCount":7,"playcount":23},{"pid":"b","platform":"tx"},{"id":"c"}]}""","wy") as PlaylistSearchOutcome.Success; assertEquals(listOf("a","c"),r.playlists.map{it.id}); assertEquals("wy",r.playlists[1].source); assertEquals(7,r.playlists[0].count); assertEquals(23L,r.playlists[0].playCount); assertTrue(r.playlists[0].songs.isEmpty()) }
 @Test fun recommendEmptyAndEnvelopeVariants() { for(raw in listOf("{\"result\":[]}","{\"data\":{\"records\":[]}}")) { val r=ApiClient.parseRecommendPlaylistsPayloadOutcome(raw,"tx"); assertTrue(r is FeedListOutcome.Success && r.data.isEmpty()) } }
 @Test fun missingArrayIsNotEmptySuccess() { assertEquals(FeedListOutcome.Failure,ApiClient.parseRecommendPlaylistsPayloadOutcome("{}","wy")); assertEquals(PlaylistSearchOutcome.Failure,ApiClient.parseCategoryPlaylistPayload("{}","wy")) }
 @Test fun legacySearchParserRetainsItsOldEnvelopeContract() { val m=ApiClient::class.java.getDeclaredMethod("parsePlaylistsPayload",String::class.java,String::class.java).apply{isAccessible=true}; val rows=m.invoke(ApiClient,"""{"ok":false,"list":[{"id":"p","src":"wy"}]}""","wy") as List<*>; assertEquals(1,rows.size) }
}
