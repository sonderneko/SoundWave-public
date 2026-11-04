package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class HeadlessRecoveryTest {
 private val song=Song(id="same",name="same",singer="artist",source="wy")
 private fun setup(scope:CoroutineScope):PlayerService {
  PlayerService.beginPlaybackIntent(); PlayerService.mainHandler.pending.clear()
  PlayerService.currentSong=null; PlayerService.recoveryInFlight=false; PlayerService.recoveryStatePrepared=false
  PlayerService.recoveryExplicitResume=false; PlayerService.onNeedReplay=null; PlayerService.onPlaybackInterrupted=null
  PlayerService.player.playable=false;PlayerService.player.playing=false;PlayerService.media.clear()
  PlaybackStore.loadAction={PlaybackStore.State(song)}
  return PlayerService(scope).also {PlayerService.instance=it}
 }
 @Test fun loopRunnableCannotPlayAfterPause() = runBlocking {
  setup(this);PlayerService.playMode=PlayMode.LOOP_ONE
  PlayerService.handleTrackEnded();val old=PlayerService.mainHandler.pending.removeFirst()
  PlayerService.pause();PlayerService.mainHandler.drain();old()
  assertFalse("LOOP_ONE must not replay a paused intent",PlayerService.player.playing)
 }
 @Test fun oldLoopRunnableCannotSeekOrPlayNewSelection() = runBlocking {
  setup(this);PlayerService.playMode=PlayMode.LOOP_ONE; PlayerService.player.position=42
  PlayerService.handleTrackEnded();PlayerService.beginPlaybackIntent();PlayerService.mainHandler.drain()
  assertEquals(42L,PlayerService.player.position);assertFalse(PlayerService.player.playing)
 }
 @Test fun pauseCancelsRestoreChildButNotUnrelatedScopeWork()=runBlocking {
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO);setup(owner)
  val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
  PlaybackStore.loadAction={try {entered.complete(Unit);awaitCancellation()} finally {cancelled.complete(Unit)}}
  val unrelated=owner.launch {awaitCancellation()}
  PlayerService.resume();entered.await();PlayerService.pause()
  val observed=withTimeoutOrNull(300){cancelled.await();true} ?: false
  val alive=unrelated.isActive;owner.cancel()
  assertTrue("pause cancels actual restore child",observed);assertTrue(alive)
 }
 @Test fun pauseCancelsHeadlessResolveAndExplicitResumeRestartsIt()=runBlocking {
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO);val service=setup(owner)
  val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
  var calls=0
  UrlResolver.action={calls++;if(calls==1) try {entered.complete(Unit);awaitCancellation()} finally {cancelled.complete(Unit)} else UrlResolver.Result(url="/resumed-headless")}
  service.resolveInService(song,startMs=7000,autoSkip=true);entered.await()
  PlayerService.pause();PlayerService.resume();PlayerService.mainHandler.drain()
  val stopped=withTimeoutOrNull(300){cancelled.await();true} ?: false
  val played=withTimeoutOrNull(300){while(PlayerService.media.isEmpty()) yield();true} ?: false
  owner.cancel()
  assertTrue("pause cancels resolver child",stopped)
  assertTrue("explicit resume restarts interrupted headless resolution, not empty player.play",played)
  assertEquals(listOf("/resumed-headless"),PlayerService.media)
 }
 @Test fun newHeadlessResolveSurvivesOldCompletion()=runBlocking {
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO);val service=setup(owner)
  val oldEntered=CompletableDeferred<Unit>();val releaseOld=CompletableDeferred<Unit>()
  val newEntered=CompletableDeferred<Unit>();val releaseNew=CompletableDeferred<Unit>()
  var calls=0
  UrlResolver.action={calls++;if(calls==1) try{oldEntered.complete(Unit);awaitCancellation()}finally{withContext(NonCancellable){releaseOld.await()}} else {newEntered.complete(Unit);releaseNew.await();UrlResolver.Result(url="/new-owner")}}
  service.resolveInService(song,startMs=0,autoSkip=true);oldEntered.await()
  service.resolveInService(song,startMs=0,autoSkip=true);newEntered.await()
  releaseOld.complete(Unit);repeat(5){yield()}
  val owned=PlayerService.recoveryInFlight;releaseNew.complete(Unit)
  withTimeout(1000){while(PlayerService.media.isEmpty())yield()};owner.cancel()
  assertTrue(owned);assertEquals(listOf("/new-owner"),PlayerService.media)
 }
 @Test fun rapidPauseResumeDoesNotDropNewOwnerOrLetOldFinallyClearIt() = rapidResume(false)
 @Test fun rapidPauseResumeAlsoReachesNetworkRecovery() = rapidResume(true)
 private fun rapidResume(network:Boolean)=runBlocking {
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO);setup(owner)
  val firstEntered=CompletableDeferred<Unit>();val oldFinally=CompletableDeferred<Unit>();val releaseOld=CompletableDeferred<Unit>()
  val secondEntered=CompletableDeferred<Unit>();val releaseSecond=CompletableDeferred<Unit>()
  var calls=0
  PlaybackStore.loadAction={
   calls++
   if(calls==1) try {firstEntered.complete(Unit);awaitCancellation()} finally {withContext(NonCancellable){oldFinally.complete(Unit);releaseOld.await()}}
   else {secondEntered.complete(Unit);releaseSecond.await();PlaybackStore.State(song)}
  }
  LocalFirst.action={if(network) null else "/new-local"}
  UrlResolver.action={UrlResolver.Result(url="/new-network")}
  PlayerService.resume();firstEntered.await();PlayerService.pause();PlayerService.resume()
  val started=withTimeoutOrNull(300){secondEntered.await();true} ?: false
  releaseOld.complete(Unit)
  repeat(5){yield()}
  val inFlight=PlayerService.recoveryInFlight
  releaseSecond.complete(Unit)
  if(started) withTimeout(1000){while(PlayerService.media.isEmpty()) yield()}
  owner.cancel()
  assertTrue("explicit resume is not discarded while old restore is finalizing",started)
  assertTrue("old completion cannot clear newer restore owner",inFlight)
  assertEquals(listOf(if(network) "/new-network" else "/new-local"),PlayerService.media)
 }
}
