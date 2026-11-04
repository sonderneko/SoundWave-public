@file:Suppress("UNUSED_PARAMETER")
package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import xyz.cncnlp.soundwave.migration.*
object Dispatchers { val IO = kotlinx.coroutines.Dispatchers.Unconfined }
object Looper { fun myLooper() = 1; fun getMainLooper() = 1 }
class TestHandler { val pending=java.util.ArrayDeque<()->Unit>(); fun post(block:()->Unit) { pending.add(block) }; fun drain(){while(pending.isNotEmpty()) pending.removeFirst()()} }
class TestPlayer { var playing=false; var playable=false; var position=42L; fun play(){playing=true}; fun pause(){playing=false}; fun seekTo(ms:Long){position=ms} }
enum class PlayMode { SEQUENCE, LOOP_ONE }
class KgAlbumMetadata
object BuildConfig { const val DEBUG=false }
object PlaybackStore {
 data class State(val song:Song, val queue:List<Song> = listOf(song), val index:Int=0, val positionMs:Long=7000)
 var loadAction:suspend ()->State? = { error("load fixture required") }
 suspend fun load(context:Any, owner:Any)=loadAction()
 fun setExplicitResume(context:Any, explicit:Boolean, owner:Any){}
}
object LocalFirst { var action:suspend (Song)->String?={null}; suspend fun findLocal(s:Song,c:Any)=action(s) }
class Context { companion object { const val MODE_PRIVATE=0 } }
object ContextPreferences { fun open(c:Any,n:String,m:Int,o:Any)=Any() }
data class Script(val enabled:Boolean=true,val code:String="script")
object ScriptStorage { fun loadScripts(p:Any)=listOf(Script()) }
class Runtime { fun setProxyBase(b:String){}; fun init(){} }
object ScriptRuntimeHolder { fun get(c:Any,o:Any)=Runtime() }
object ApiClient { const val baseUrl="fixture"; fun playableUrl(u:String)=u }
object SmartSourceStore { fun platformPlayQuality(s:String,q:String)=q }
object PlaylistImportChecker { fun shouldForceCross(k:String)=false; fun notePlayOutcome(songKey:String, originSource:String, servedPlatform:String, wasForceCross:Boolean){} }
data class ResolveTicket(val songKey:String, val purpose:Purpose, val ownerContext:ProfileContext=ProfileWorkIdentity.runtime()) { enum class Purpose { PLAY } }
object UrlResolver {
 enum class Failure { NONE, FAILED }
 data class Result(val url:String="fixture",val isSuccess:Boolean=true,val elapsedMs:Long=1,val fromCache:Boolean=false,val scriptId:String="",val platform:String="wy",val quality:String="320k",val failure:Failure=Failure.NONE)
 var action:suspend (Song)->Result = { error("resolver fixture required") }
 suspend fun resolveResult(ticket:ResolveTicket,song:Song,scripts:List<Script>,runtime:Runtime,preferQuality:String,skipScripts:Set<String> = emptySet(),forceCross:Boolean=false, requestStillCurrent:()->Boolean)=action(song)
}
object PlaybackDiagnostics { fun markResolved(s:Song,e:Long,c:Boolean,i:String,p:String,q:String,purpose:String){}; fun markFailure(s:Song,f:String){} }
class PlayerService(val recoveryScope:CoroutineScope) {
 companion object {
  val playbackIntent=AtomicInteger(); val playGen=AtomicInteger()
  var interruptedIntent=-1
  val serviceOwner=ProfileWorkIdentity.runtime()
  var instance:PlayerService?=null
  var currentSong:Song?=null; var queue=emptyList<Song>(); var queueIndex=0
  var positionMs=0L; var durationMs=0L; var isPlayingFlag=false
  var recoveryInFlight=false; var recoveryExplicitResume=false; var recoveryStatePrepared=false; var recoveryGen=0
  var onPlaybackInterrupted:(()->Unit)?=null; var onPlayState:((Boolean)->Unit)?=null
  var onNeedReplay:((Int)->Unit)?=null; var onNeedNext:(()->Unit)?=null; var onPreparedNext:(()->Unit)?=null
  var onSleepTick:((Long)->Unit)?=null; var onError:((PlaybackFailure)->Unit)?=null
  val mainHandler=TestHandler(); val player=TestPlayer(); val audioFocusController:Focus?=Focus()
  class Focus { fun onUserPause(){} }
  data class PlaybackFailure(val songKey:String,val generation:Int,val message:String="")
  var playMode=PlayMode.LOOP_ONE; var sleepStopAfterCurrent=false; var sleepDeadlineElapsed=0L
  var activePlaybackToken="track#1"; var endedHandledToken=""; var naturalEndHoldToken=""
  val recoveryPolicy=xyz.cncnlp.soundwave.playback.PlaybackRecoveryPolicy()
  val media=mutableListOf<String>()
  fun setQueue(q:List<Song>,i:Int){queue=q;queueIndex=i}
  fun hasPlayableMedia()=player.playable
  fun clearPlayerMedia(p:TestPlayer){p.playable=false;p.playing=false}
  fun hasPreparedNext()=false; fun playNextPrepared()=false
  fun playUrl(context:Any,url:String,song:Song,startMs:Long,sourcePlatform:String,intent:Int){if(isPlaybackIntentCurrent(intent)){media+=url;player.playable=true;player.play();currentSong=song}}
  // TRANSPORT
 }
 val applicationContext=Any()
 fun releaseTransitionWake(){}; fun updateSessionAndNotify(){}; fun requestAudioFocus(){}; fun holdTransitionWake(){}
 fun resolveQueueFallback(delta:Int){error("unexpected fallback")}; fun stopAfterRepeatedFailures(){error("unexpected stop")}; fun showServiceToast(m:String){}
 suspend fun loadEnabledScripts()=listOf(Script())
 // RECOVERY
}
