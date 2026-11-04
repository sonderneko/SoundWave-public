#!/usr/bin/env python3
"""Compile actual resolve/resolveResult methods with controlled provider seams (no Android)."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]; BASE=ROOT/'app/src/main/java/xyz/cncnlp/soundwave'
s=(BASE/'UrlResolver.kt').read_text()
# Keep every production statement in resolve; only dependencies are supplied by the harness.
resolve=s[s.index('    suspend fun resolve('):s.index('    /** 用于取链失败界面')]
# Tests inject a short budget by changing only its literal, not budget placement/control flow.
resolve=resolve.replace('26_000L','160L')
lanes=s[s.index('    private val playResolveLane'):s.index('    private suspend fun resolveResultInternal(')]
lanes=lanes.replace('26_000L','160L').replace('BACKGROUND_ADMISSION_DELAY_MS = 180L','BACKGROUND_ADMISSION_DELAY_MS = 5L')
work=Path('/tmp/r657-resolver-budget-jvm');work.mkdir(exist_ok=True)
# Reuse project-independent Kotlin files, generating data/control seams below.
pre='''package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.direct.*
data class Song(val id:String="id",val source:String="kg",val name:String="song",val singer:String="artist"){fun key()=source+":"+id}
data class Script(val id:String="s",val name:String="s",val enabled:Boolean=true,val code:String="code")
data class ResolveTicket(val songKey:String="kg:id",val purpose:Purpose=Purpose.PLAY,val ownerContext:xyz.cncnlp.soundwave.migration.ProfileContext=xyz.cncnlp.soundwave.migration.ProfileContext()) { enum class Purpose {PLAY,DOWNLOAD,PREFETCH,PROBE} }
enum class ResolveFailure {NONE,TIMEOUT,STALE_REQUEST,NO_PLAYABLE_URL,NO_ENABLED_SCRIPT,NETWORK_ERROR,POLICY_BLOCKED,MEDIA_UNVERIFIED,CANDIDATE_NOT_MATCHED}
data class ResolveResult(val ticket:ResolveTicket,val elapsedMs:Long=0,val failure:ResolveFailure=ResolveFailure.NONE,val attempts:List<ResolveAttempt> = emptyList())
data class ResolveAttempt(val stage:String,val platform:String,val scriptId:String,val quality:String,val elapsedMs:Long,val outcome:Outcome,val reasonCode:Reason,val reason:String){enum class Outcome{HIT,SKIPPED,EMPTY,ERROR,TIMEOUT};enum class Reason{NONE,MEDIA_UNVERIFIED,NO_URL,NO_ENABLED_SCRIPT,SCRIPT_CODE_EMPTY,SCRIPT_DISABLED,SCRIPT_ERROR,SCRIPT_COOLDOWN,CANDIDATE_NOT_MATCHED,GLOBAL_TIMEOUT}}
data class TrustedMediaCacheIdentity(val owner:String,val candidate:String,val platform:String,val actualQuality:String,val script:String,val url:String,val contentEvidence:String) {
 fun isComplete()=listOf(owner,candidate,platform,actualQuality,script,url,contentEvidence).all{it.isNotBlank()}
 companion object {
  fun requestScope(parts:List<String>)=parts.joinToString("|")
  fun contentEvidence(status:Int,mime:String,bytes:Long,format:String,extra:String)="$status:$mime:$bytes:$format:$extra"
  fun canReuse(stored:TrustedMediaCacheIdentity,current:TrustedMediaCacheIdentity)=stored.isComplete()&&current.isComplete()&&stored.copy(url=current.url)==current
 }
}
object LocalPlayback { fun isLocalSong(song:Song)=false }
object ApiClient { fun officialUrlCancellable(vararg x:String):String="" }
object CacheManager{fun recordUrlCacheHit(songKey:String){};fun recordUrlCacheMiss(songKey:String){}}
object SmartSourceStore {
 const val STAGE_ORIGIN="origin";const val STAGE_QUALITY="quality";const val STAGE_CROSS_SCRIPT="scripts";const val STAGE_CROSS_PLAT="plat"
 val DEFAULT_STAGES=listOf(STAGE_ORIGIN)
 data class Remembered(val id:String,val source:String,val url:String,val quality:String,val name:String,val singer:String)
 fun rememberCrossPlat()=false;fun getRemembered(k:String):Remembered?=null;fun forget(k:String){}
 fun orderedScripts(s:String,x:List<Script>)=x;fun smartEnabled()=true;fun stageOrder()=DEFAULT_STAGES
 fun fallback128k()=true;fun platformPlayQuality(s:String,q:String)=q
 fun enabledPlatformsInOrder()=listOf("kg");fun remember(k:String,r:Remembered){}
}
object ResolveFallbackPolicy {
 fun stages(smartEnabled:Boolean,forceCross:Boolean,originOnly:Boolean,configured:List<String>)=when {
  originOnly -> listOf(SmartSourceStore.STAGE_ORIGIN,SmartSourceStore.STAGE_QUALITY,SmartSourceStore.STAGE_CROSS_SCRIPT)
  !smartEnabled -> listOf(SmartSourceStore.STAGE_ORIGIN,SmartSourceStore.STAGE_QUALITY)
  forceCross -> listOf(SmartSourceStore.STAGE_CROSS_SCRIPT,SmartSourceStore.STAGE_CROSS_PLAT)
  else -> configured
 }
 fun scriptLimit(smartEnabled:Boolean)=if(smartEnabled) 4 else 1
}
object Qualities{fun cascade(q:String)=listOf(q)}
object SongMatcher{fun rejectReason(a:Song,b:Song):String?=null;fun rank(a:Song,b:List<Song>,limit:Int)=b}
class ScriptRuntime{val ownerContext=xyz.cncnlp.soundwave.migration.ProfileContext();suspend fun getMusicUrl(a:String,b:String,c:JSONObject,d:String,e:String,purpose:ResolveTicket.Purpose,scriptMeta:String):String {delay(60);return ""};fun lastFailureReason(s:String)="";fun cancelSpeculativeUrlRequests(){}}
fun scriptMetaJson(s:Script)="{}"
object Resolver {
 const val URL_CACHE_TTL_MS=60000L
 const val SRC_PRIORITY_DUMMY=""
 val SRC_PRIORITY=emptyMap<String,List<String>>()
 data class CachedUrl(val url:String,val actualQuality:String,val scriptId:String,val candidateKey:String,val platform:String="kg",val contentEvidence:String="evidence",val createdAt:Long=System.currentTimeMillis(),val expiresAtEpochMs:Long=createdAt+URL_CACHE_TTL_MS)
 val urlCache=mutableMapOf<String,CachedUrl>();val cacheLock=Any()
 fun ownerKey(c:xyz.cncnlp.soundwave.migration.ProfileContext)="owner"
 fun candidateIdentity(song:Song)=song.key()
 fun cacheKey(a:Song,b:String,c:xyz.cncnlp.soundwave.migration.ProfileContext,d:List<Script>)="key"
 fun qualitySatisfies(a:String,b:String)=true;fun rememberAttempts(a:String,b:List<ResolveAttempt>,c:xyz.cncnlp.soundwave.migration.ProfileContext){}
 fun isScriptBad(a:String,b:String,c:xyz.cncnlp.soundwave.migration.ProfileContext)=false
 fun markWinningScript(a:String,b:String,c:xyz.cncnlp.soundwave.migration.ProfileContext){}
 fun trimCacheLocked(){};fun buildMusicInfo(s:Song)=JSONObject()
 fun canAttemptAnonymousResolutionBeforeScripts(a:String,b:String,c:Boolean,d:Boolean)=true
 data class Media(val playable:Boolean=false,val reason:String="",val httpStatus:Int=200,val mime:String="audio/mpeg",val bytes:Long=1,val formatHint:String="mp3")
 suspend fun validateProbeMedia(a:String,b:String,c:String,d:Long,purpose:ResolveTicket.Purpose)=Media()
'''
post=lanes+'''
 private suspend fun resolveResultInternal(ticket:ResolveTicket,song:Song,scripts:List<Script>,runtime:ScriptRuntime,preferQuality:String,skipScripts:Set<String>,forceCross:Boolean,originOnly:Boolean,requestStillCurrent:()->Boolean,onProgress:((String)->Unit)?):ResolveResult {
 delay(5);return ResolveResult(ticket,failure=if(requestStillCurrent())ResolveFailure.NONE else ResolveFailure.STALE_REQUEST)
 }
}
fun main()=runBlocking {
 val start=System.nanoTime()
 Resolver.resolve(Song(),listOf(Script()),ScriptRuntime())
 val ms=(System.nanoTime()-start)/1_000_000
 check(ms<300){"Full chain exceeded 160ms test budget: ${ms}ms (official stage excluded)"}
 println("PASS actual resolve full-chain deadline: ${ms}ms")
 val job=launch{Resolver.resolve(Song(),listOf(Script()),ScriptRuntime())}
 delay(20);job.cancelAndJoin();check(xyz.cncnlp.soundwave.source.direct.calls==2){"Cancellation swallowed, another provider was started"}
 println("PASS actual resolve caller cancellation propagates without fallback")
 // Hold lane before admission: total budget includes queue wait, not only scripts.
 val lane=Resolver.javaClass.getDeclaredField("playResolveLane").apply{isAccessible=true}.get(Resolver) as Mutex
 lane.lock()
 val sharedLaneStart=System.nanoTime()
 val queued=async{Resolver.resolveResult(ResolveTicket(),Song(),listOf(Script()),ScriptRuntime())}
 val blockedResult=queued.await()
 val sharedLaneMs=(System.nanoTime()-sharedLaneStart)/1_000_000
 check(blockedResult.failure==ResolveFailure.TIMEOUT){"shared lane did not reproduce the old PLAY queue timeout"}
 check(sharedLaneMs in 100..300){"unexpected simulated shared-lane delay: ${sharedLaneMs}ms"}
 lane.unlock()
 println("MEASURE old shared PLAY lane held: timeout after ${sharedLaneMs}ms")
 // A background purpose may hold its own lane, but must not queue a new PLAY.
 val playMeasurements=mutableListOf<String>()
 for ((purpose, laneName) in listOf(ResolveTicket.Purpose.PREFETCH to "prefetchResolveLane", ResolveTicket.Purpose.PROBE to "probeResolveLane")) {
  val backgroundLane=Resolver.javaClass.getDeclaredField(laneName).apply{isAccessible=true}.get(Resolver) as Mutex
  backgroundLane.lock()
  val background=async{Resolver.resolveResult(ResolveTicket(purpose=purpose),Song(),listOf(Script()),ScriptRuntime())}
  try {
   delay(20)
   val playStart=System.nanoTime()
   val foreground=withTimeout(120){Resolver.resolveResult(ResolveTicket(),Song(),listOf(Script()),ScriptRuntime())}
   val playMs=(System.nanoTime()-playStart)/1_000_000
   check(foreground.failure==ResolveFailure.NONE && playMs<120){"PLAY waited for $purpose lane: ${playMs}ms"}
   playMeasurements += "$purpose=${playMs}ms"
  } finally { backgroundLane.unlock() }
  check(background.await().failure==ResolveFailure.STALE_REQUEST){"PLAY did not stale the queued $purpose result"}
 }
 println("MEASURE new purpose lanes: PLAY while background held: ${playMeasurements.joinToString()}")
 println("PASS actual PLAY enters while PREFETCH/PROBE lanes are held; stale background results are discarded")
 // DOWNLOAD ignores PLAY epoch advancement, even while it is delayed in admission.
 val dl=async{Resolver.resolveResult(ResolveTicket(purpose=ResolveTicket.Purpose.DOWNLOAD),Song(),listOf(Script()),ScriptRuntime())}
 delay(20)
 Resolver.resolveResult(ResolveTicket(),Song(),listOf(Script()),ScriptRuntime())
 check(dl.await().failure==ResolveFailure.NONE){"DOWNLOAD incorrectly invalidated by PLAY epoch"}
 println("PASS actual resolveResult queue budget and DOWNLOAD/PLAY epoch isolation")
 // Exercise actual resolve() with both purposes; the fake direct provider inspects
 // the purpose context installed by the production ResolveBlockingStage wrapper.
 xyz.cncnlp.soundwave.source.direct.observedPurposes.clear()
 Resolver.resolve(Song(),listOf(Script()),ScriptRuntime(),purpose=ResolveTicket.Purpose.PLAY)
 Resolver.resolve(Song(id="download"),listOf(Script()),ScriptRuntime(),purpose=ResolveTicket.Purpose.DOWNLOAD)
 check(xyz.cncnlp.soundwave.source.direct.observedPurposes.takeLast(2)==listOf("PLAY","DOWNLOAD")) {
  "resolve() did not pass purpose into provider stage: ${xyz.cncnlp.soundwave.source.direct.observedPurposes}"
 }
 println("PASS actual resolve propagates PLAY/DOWNLOAD purpose to provider stage")
}
'''
(work/'Harness.kt').write_text(pre+resolve+post)
(work/'Owner.kt').write_text('package xyz.cncnlp.soundwave.migration\nclass ProfileContext{fun matches(x:ProfileContext)=true}\nobject ProfileWorkIdentity{fun runtime()=ProfileContext();fun isWork(x:ProfileContext)=true}')
(work/'Providers.kt').write_text('''package xyz.cncnlp.soundwave.source.direct
import kotlinx.coroutines.*
var calls=0
val observedPurposes=mutableListOf<String>()
suspend fun kugouDirectResolveUrl(x:String):String?{
 calls++
 val purpose=currentCoroutineContext().fold(null as String?){ found, element ->
  if(element.javaClass.simpleName!="PurposeContext") found else {
   val field=element.javaClass.getDeclaredField("purpose").apply{isAccessible=true}
   field.get(element).toString()
  }
 }
 synchronized(observedPurposes){observedPurposes+=purpose?:"<missing>"}
 delay(360);return null
}
suspend fun miguDirectResolveUrl(x:String):String?=null
suspend fun kuwoDirectResolveUrl(x:String,q:String):String?=null
suspend fun wangyiDirectResolveUrl(x:String):String?=null
''')
(work/'Registry.kt').write_text('''package xyz.cncnlp.soundwave.source
import xyz.cncnlp.soundwave.Song
object MusicSourceRegistry{val platformIds=emptyList<String>();fun platformSource(s:String)=this;suspend fun searchServer(s:String)=emptyList<Song>()}
''')
# legacy synchronous official API, never used after the first provider exhausts test budget
preextra=''
# Add ApiClient search signature needed by production fallback.
f=work/'Harness.kt';f.write_text(f.read_text().replace('fun officialUrlCancellable(vararg x:String):String=""','fun searchCancellable(a:String,b:String)=emptyList<Song>(); fun officialUrlCancellable(vararg x:String):String=""'))
lib=Path('/tmp/r657-security-jvm');cp=':'.join(map(str,list(lib.glob('*.jar'))+[Path('/tmp/r657-migu-jvm/coroutines.jar')]))
files=list(work.glob('*.kt'))
helper=BASE/'ResolveBudget.kt'
if helper.exists():files.append(helper)
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(work/'classes'),*map(str,files)],capture_output=True,text=True);print(r.stdout+r.stderr,end='');assert r.returncode==0
r=subprocess.run(['java','-cp',str(work/'classes')+':'+cp,'xyz.cncnlp.soundwave.HarnessKt'],capture_output=True,text=True,timeout=10);print(r.stdout+r.stderr,end='');raise SystemExit(r.returncode)
