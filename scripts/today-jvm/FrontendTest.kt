package xyz.cncnlp.soundwave.recommend
import android.content.Context
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.source.*
import xyz.cncnlp.soundwave.ui.home.TodayRecommendationFrontend

class FrontendTest {
 @Test fun currentCacheIsReusedButCancellationDoesNotPublish() = runBlocking {
  val c=Context();HomePageStateStore.values.clear();HomeFeedCache.details.clear()
  val profile=PersonalRecommendations.buildProfile(emptyList(),emptyList(),profileOwner="cache-owner")
  val slot=TodayRecommendationSlot.slotKey(System.currentTimeMillis())
  val fp=StyleWeightedRecommendation.fingerprint(c,"wy",profile)
  val song=Song("cached","Instrumental","Artist",source="wy")
  HomeFeedCache.saveDetail(c,TodayRecommendationSlot.cacheId("wy"),"wy",listOf(song))
  HomePageStateStore.putStrings(c,mapOf(TodayRecommendationSlot.slotStateKey("wy") to slot,TodayRecommendationSlot.fingerprintStateKey("wy") to fp))
  assertEquals(listOf(song),TodayRecommendationFrontend.load(c,"wy",profile,slot){true})
  StylePreferenceStore.saveTags(c,"wy",listOf("纯音乐"))
  MusicSourceRegistry.providers["wy"]=object:Provider(){override suspend fun searchPlaylists(t:String,p:String,l:Int):List<Playlist>{throw CancellationException("provider cancelled")}}
  var cancelled=false
  try { TodayRecommendationFrontend.load(c,"wy",profile,slot){true} } catch (_:CancellationException){cancelled=true}
  assertTrue(cancelled)
  assertEquals("",HomePageStateStore.getString(c,TodayRecommendationSlot.fingerprintStateKey("wy")))
 }
 @Test fun publicationWritesMatchingFingerprintAndOnlyThenRecordsExposure() = runBlocking {
  val c=Context();HomePageStateStore.values.clear();HomeFeedCache.details.clear()
  val profile=PersonalRecommendations.buildProfile(emptyList(),emptyList(),profileOwner="publish-owner")
  StylePreferenceStore.saveTags(c,"wy",listOf("纯音乐"))
  val song=Song("published","Morning Instrumental","Artist",source="wy")
  MusicSourceRegistry.providers["wy"]=object:Provider(){override suspend fun searchPlaylists(t:String,p:String,l:Int)=listOf(Playlist("p","纯音乐",source="wy",songs=mutableListOf(song)))}
  val slot=TodayRecommendationSlot.slotKey(System.currentTimeMillis())
  assertEquals(listOf(song),TodayRecommendationFrontend.load(c,"wy",profile,slot){true})
  assertEquals(StyleWeightedRecommendation.fingerprint(c,"wy",profile),HomePageStateStore.getString(c,TodayRecommendationSlot.fingerprintStateKey("wy")))
  assertTrue(StyleWeightedRecommendation.generate(c,"wy",profile).isEmpty())
 }

 @Test fun emptyInvalidatesRawOldCacheAndLateResultCannotPublish() = runBlocking {
  val c=Context(); HomePageStateStore.values.clear(); HomeFeedCache.details.clear()
  StylePreferenceStore.saveTags(c,"wy",listOf("纯音乐"))
  val profile=PersonalRecommendations.buildProfile(emptyList(),emptyList(),profileOwner="alice")
  val slot=TodayRecommendationSlot.slotKey(System.currentTimeMillis())
  val old=Song("old","Vocal","A",source="wy")
  HomeFeedCache.saveDetail(c,TodayRecommendationSlot.cacheId("wy"),"wy",listOf(old))
  HomePageStateStore.putString(c,TodayRecommendationSlot.slotStateKey("wy"),slot)
  HomePageStateStore.putString(c,TodayRecommendationSlot.fingerprintStateKey("wy"),"old")
  MusicSourceRegistry.providers["wy"]=object:Provider(){override suspend fun searchPlaylists(t:String,p:String,l:Int)=emptyList<Playlist>()}
  assertEquals(emptyList<Song>(),TodayRecommendationFrontend.load(c,"wy",profile,slot){true})
  assertEquals("",HomePageStateStore.getString(c,TodayRecommendationSlot.slotStateKey("wy")))
  assertEquals("",HomePageStateStore.getString(c,TodayRecommendationSlot.fingerprintStateKey("wy")))
  assertEquals(listOf(old),HomeFeedCache.details[TodayRecommendationSlot.cacheId("wy")])
  var current=true
  MusicSourceRegistry.providers["wy"]=object:Provider(){override suspend fun searchPlaylists(t:String,p:String,l:Int):List<Playlist>{current=false;return listOf(Playlist("p","纯音乐",source="wy",songs=mutableListOf(Song("new","Instrumental","A",source="wy"))))}}
  assertNull(TodayRecommendationFrontend.load(c,"wy",profile,slot){current})
  assertEquals("",HomePageStateStore.getString(c,TodayRecommendationSlot.fingerprintStateKey("wy")))
 }
 @Test fun updateRequiresLiveOwnerProfileTagsAndPlatform() {
  val c=Context(); HomePageStateStore.values.clear()
  val a=PersonalRecommendations.buildProfile(emptyList(),emptyList(),profileOwner="alice")
  val b=PersonalRecommendations.buildProfile(emptyList(),emptyList(),profileOwner="bob")
  val u=TodayRecommendationRefresher.Update("wy",emptyList(),StyleWeightedRecommendation.fingerprint(c,"wy",a))
  assertTrue(TodayRecommendationFrontend.acceptsUpdate(c,"wy",a,u))
  assertFalse(TodayRecommendationFrontend.acceptsUpdate(c,"wy",b,u))
  assertFalse(TodayRecommendationFrontend.acceptsUpdate(c,"kg",a,u))
  StylePreferenceStore.saveTags(c,"wy",listOf("纯音乐"))
  assertFalse(TodayRecommendationFrontend.acceptsUpdate(c,"wy",a,u))
 }
}
