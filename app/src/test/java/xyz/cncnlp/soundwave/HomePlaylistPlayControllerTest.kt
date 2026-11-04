package xyz.cncnlp.soundwave

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.ui.home.HomePlaylistPlayController

class HomePlaylistPlayControllerTest {
 private val song=Song("one","Song","Artist","kw")
 @Test fun metadataOnlyLoadsSameProviderAndPlaysWithoutNavigation()=runTest {
  val controller=HomePlaylistPlayController();var plays=0;var loads=0
  controller.play(Playlist("reco_123456","Test",source="kw",remoteId="654321"),"wy",
   load={source,id->assertEquals("kw",source);assertEquals("654321",id);loads++;listOf(song)},
   isCurrent={true},onPlay={s,q->assertEquals(song,s);assertEquals(listOf(song),q);plays++},onUnavailable={fail()})
  assertEquals(1,plays);assertEquals(1,loads)
 }
 @Test fun seededQueueUsesExistingPlaybackCallbackWithoutNetwork()=runTest {
  var plays=0
  HomePlaylistPlayController().play(Playlist("local","Local",songs=mutableListOf(song),local=true),"wy",
   load={_,_->error("seed must not reload")},isCurrent={true},onPlay={_,q->assertEquals(listOf(song),q);plays++},onUnavailable={fail()})
  assertEquals(1,plays)
 }
 @Test fun repeatedPendingTapLoadsAndPlaysOnce()=runTest {
  val controller=HomePlaylistPlayController();val ready=CompletableDeferred<List<Song>>();var calls=0;var plays=0
  val pl=Playlist("123456","Test",source="kw")
  suspend fun tap()=controller.play(pl,"wy",load={_,_->calls++;ready.await()},isCurrent={true},onPlay={_,_->plays++},onUnavailable={fail()})
  val first=launch{tap()};testScheduler.runCurrent();tap();ready.complete(listOf(song));first.join()
  assertEquals(1,calls);assertEquals(1,plays)
 }
 @Test fun staleOwnerAndEmptyOrFailedLoadsNeverPlay()=runTest {
  val controller=HomePlaylistPlayController();var unavailable=0;var plays=0
  val pl=Playlist("123456","Test")
  controller.play(pl,"wy",{_,_->listOf(song)},{false},{_,_->plays++},{unavailable++})
  controller.play(pl,"wy",{_,_->emptyList()},{true},{_,_->plays++},{unavailable++})
  controller.play(pl,"wy",{_,_->throw IllegalStateException("offline")},{true},{_,_->plays++},{unavailable++})
  assertEquals(0,plays);assertEquals(2,unavailable)
 }
 @Test fun newerSelectionWinsAgainstSlowOldLoad()=runTest {
  val controller=HomePlaylistPlayController();val ready=CompletableDeferred<List<Song>>();val played=mutableListOf<String>()
  val first=launch {controller.play(Playlist("old","old"),"wy",{_,_->ready.await()},{true},{s,_->played.add(s.id)},{fail()})}
  testScheduler.runCurrent()
  controller.play(Playlist("new","new",songs=mutableListOf(song.copy(id="new"))),"wy",{_,_->error("seed")},{true},{s,_->played.add(s.id)},{fail()})
  ready.complete(listOf(song));first.join();assertEquals(listOf("new"),played)
 }
}
