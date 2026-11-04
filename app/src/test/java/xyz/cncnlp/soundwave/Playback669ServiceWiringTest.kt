package xyz.cncnlp.soundwave

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Executes real Service lifecycle + local media prepare + StateFlow + controller + FullPlayer.
 * Robolectric framework shadows are not an Android system/device or audible decoder test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xhdpi")
class Playback669ServiceWiringTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private lateinit var svc:ServiceController<PlayerService>
 private lateinit var runtime:ScriptRuntime
 private lateinit var controller:PlaybackController
 private val job=SupervisorJob()
 private val scope=CoroutineScope(job+Dispatchers.Main.immediate)
 private val a=Song("service-local-a","Service track A","Fixture artist","local",duration=1000)
 private val b=a.copy(id="service-local-b",name="Service track B")
 private class FakeEqualizer : android.media.audiofx.Equalizer(0,17) {
  var releaseCalls=0
  override fun release(){releaseCalls++}
 }
 private fun fakeEqualizer():FakeEqualizer {
  val type=Class.forName("sun.misc.Unsafe")
  val unsafe=type.getDeclaredField("theUnsafe").apply{isAccessible=true}.get(null)
  return type.getMethod("allocateInstance",Class::class.java).invoke(unsafe,FakeEqualizer::class.java) as FakeEqualizer
 }
 @Before fun setup(){
  val ctx=rule.activity
  LocalStore.prefs(ctx).edit().clear().commit()
  ctx.getSharedPreferences("soundwave_playback_v1",Context.MODE_PRIVATE).edit().clear().commit()
  runtime=ScriptRuntime(ctx)
  controller=PlaybackController(ctx,scope,runtime,LocalStore.prefs(ctx),{emptyList()},{LocalStore.UserBundle()},{"128k"},{"embedded"},{})
  svc=Robolectric.buildService(PlayerService::class.java).create()
 }
 @After fun close(){job.cancel();runtime.destroy();svc.destroy();PlayerService.resetSimpleCacheForTests()}
 private fun wav():String {
  val data=ByteArray(16000)
  val bytes=ByteBuffer.allocate(44+data.size).order(ByteOrder.LITTLE_ENDIAN)
  bytes.put("RIFF".toByteArray()).putInt(36+data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size).put(data)
  val f=File(rule.activity.cacheDir,"service-fixture.wav");f.writeBytes(bytes.array());return f.toURI().toString()
 }
 private fun play(song:Song){
  rule.runOnIdle {PlayerService.beginPlaybackIntent();PlayerService.setQueue(listOf(a,b),if(song==a)0 else 1);PlayerService.playUrl(rule.activity,wav(),song);PlayerService.setMode(PlayMode.SEQUENCE)}
  shadowOf(Looper.getMainLooper()).idle()
 }
 @Test fun actualLocalServicePublicationHydratesControllerAndVisiblePlayer(){
  play(a)
  assertEquals("real Service commit must expose the local track",a.key(),PlayerService.snapshot().song?.key())
  rule.setContent {
   LaunchedEffect(controller){PlayerService.playback.collect{controller.onSnapshot(it)}}
   val song by controller.currentSongState
   SoundwaveDesignTheme(isDark=false){
    FullPlayer(song=song,queueSnapshot=controller.queue,queueCurrentIndexState=controller.queueCurrentIndexState,playing=controller.playing,
     progressState=controller.progressState,currentTimeState=controller.currentTimeState,duration=controller.duration,resolvingState=controller.resolvingState,statusState=controller.statusState,
     lyrics=emptyList(),lyricIdx=-1,showLyrics=false,liked=false,playMode=controller.playMode,sleepRemain=0L,playerLayout=PLAYER_LAYOUT_COVER_ONLY,dismissOffsetHolder=remember { createPlayerDismissOffsetHolderForTest() },
     onBack={},onQueueItem={_,_,_->},onToggleLyrics={},onSeek={},onPrev={},onToggle={},onNext={},onLike={},onMode={},onSleep={},onDownload={})
   }
  }
  rule.waitForIdle()
  rule.onAllNodesWithText(a.name).assertAny(hasText(a.name))
  rule.onNodeWithText("选择歌曲开始播放").assertDoesNotExist()
  assertEquals(a.key(),controller.currentSong?.key());assertEquals(PlayerService.snapshot().isPlaying,controller.playing)
  play(b);rule.waitForIdle()
  rule.onAllNodesWithText(b.name).assertAny(hasText(b.name))
  assertEquals(b.key(),controller.currentSong?.key());assertEquals(1,controller.queueCurrentIndex)
  rule.runOnIdle {controller.currentSong=null;controller.reconcileForPlayerEntry()}
  rule.waitForIdle();rule.onAllNodesWithText(b.name).assertAny(hasText(b.name))
  assertEquals(b.key(),controller.currentSong?.key())
 }
 @Test fun currentControllerKeepsPendingSelectionWhileOldServicePublishes(){
  play(a);controller.reconcileForPlayerEntry();assertEquals(a.key(),controller.currentSong?.key())
  controller.currentSong=b;controller.queue=listOf(b);controller.resolving=true
  PlayerService.setMode(PlayMode.SHUFFLE)
  val oldSnapshot=PlayerService.snapshot().copy(isPlaying=true,positionMs=2500L,durationMs=8000L)
  controller.onSnapshot(oldSnapshot)
  assertEquals(b.key(),controller.currentSong?.key());assertEquals(listOf(b),controller.queue)
  assertTrue(controller.playing)
  assertEquals(2.5f,controller.currentTime,0.0001f);assertEquals(8f,controller.duration,0.0001f)
  assertEquals(0.3125f,controller.progress,0.0001f);assertEquals(PlayMode.SHUFFLE,controller.playMode)
  controller.resolving=false;play(b);controller.onSnapshot(PlayerService.snapshot())
  assertEquals(b.key(),controller.currentSong?.key());assertEquals(listOf(a,b),controller.queue)
 }
 @Test fun equalizerIsReleasedWhenTheOwningServiceIsDestroyed(){
  val equalizer=fakeEqualizer()
  val field=PlayerService::class.java.getDeclaredField("equalizer").apply{isAccessible=true}
  field.set(null,equalizer)
  assertEquals(0,equalizer.releaseCalls)

  svc.destroy()

  assertNull(field.get(null))
  assertEquals(1,equalizer.releaseCalls)
 }

 @Test fun explicitPlaybackCancellationInvalidatesTheControllerTransaction(){
  controller.currentSong=a
  controller.resolving=true
  controller.status="智能取链…"
  val revision=controller.queueRevision
  val transaction=controller.playbackTransactionGate.begin(a.key(),revision,runtime.ownerContext)

  controller.cancelPendingPlayback()

  assertTrue(controller.queueRevision>revision)
  assertFalse(controller.playbackTransactionGate.isCurrent(transaction,a.key(),controller.queueRevision))
  assertFalse(controller.resolving)
  assertEquals("",controller.status)
 }

 @Test fun recreatedControllerRestoresRealServiceWithoutRestartingTrack(){
  play(a);val before=PlayerService.snapshot()
  controller.reconcileForPlayerEntry()
  val second=PlaybackController(rule.activity,scope,runtime,LocalStore.prefs(rule.activity),{emptyList()},{LocalStore.UserBundle()},{"128k"},{"embedded"},{})
  second.reconcileForPlayerEntry()
  assertEquals(before.song?.key(),second.currentSong?.key())
  assertEquals(before.generation,PlayerService.snapshot().generation)
  assertEquals(before.queue,second.queue)
 }
}
