#!/usr/bin/env python3
"""Compile exact production Runtime cancellation/nativeFetch methods with explicit Android/network seams."""
from pathlib import Path
import subprocess, os
ROOT=Path(__file__).resolve().parents[1]; BASE=ROOT/'app/src/main/java/xyz/cncnlp/soundwave'
s=(BASE/'ScriptRuntime.kt').read_text()
def method(marker):
 start=s.index(marker); brace=s.index('{',s.index('): String',start)) if marker=='    suspend fun getMusicUrl(' else s.index('{',start); depth=1; i=brace+1
 while depth:
  if s[i]=='{':depth+=1
  elif s[i]=='}':depth-=1
  i+=1
 return s[start:i].replace('16_000L','100L')
methods='\n'.join(method(x) for x in ['    private fun cancelUrlRequests(', '    private fun completeAndRemove(', '        fun nativeCancel(', '        fun nativeFetch(', '    suspend fun getMusicUrl(', '    private fun scriptUrlRequestKey(', '    private suspend fun getMusicUrlUncoalesced('])
short_budget_method=method('    suspend fun getMusicUrl(').replace('fun getMusicUrl(', 'fun getMusicUrlShortBudget(').replace('ScriptExecutionLimits.SCRIPT_URL_CALL_TOTAL_MS','100L')
methods+='\n'+short_budget_method
# Include the actual child registry/cleanup methods once added, without a test-only implementation.
for name in ['    private fun releaseRequest(', '    private fun forgetFetch(']:
 if name in s: methods+='\n'+method(name)
work=Path('/tmp/r657-runtime-cancel-jvm');work.mkdir(exist_ok=True)
source='''package xyz.cncnlp.soundwave
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.json.JSONObject
import xyz.cncnlp.soundwave.script.ScriptEvaluationLanes
import xyz.cncnlp.soundwave.script.ScriptExecutionLimits
import xyz.cncnlp.soundwave.script.SingleFlightNegativeCache
private data class FetchResult(val code:Int,val body:String)
private data class PendingUrlRequest(val capability:String,val deferred:CompletableDeferred<String>,val purpose:Purpose,val ownerJob:Job?)
private typealias Purpose = ResolveTicket.Purpose
private object ResolveTicket { enum class Purpose { PLAY, DOWNLOAD, PREFETCH, PROBE } }
private class FakeCall { var cancelled=false;fun cancel(){cancelled=true} }
private class Runtime {
 val pendingUrl=ConcurrentHashMap<String,PendingUrlRequest>()
 val activeCalls=ConcurrentHashMap<String,FakeCall>()
 val requestLock=Any()
 val fetchParents=ConcurrentHashMap<String,String>()
 val responseHeadersByCapability=ConcurrentHashMap<String,String>()
 val lastFetchFailure=ConcurrentHashMap<String,String>()
 val capToScript=ConcurrentHashMap<String,String>()
 val lastFailureByScript=ConcurrentHashMap<String,String>()
 private data class ScriptUrlCallResult(val url:String,val failureCategory:String,val cacheableFailure:Boolean=true)
	private val urlCallFlights=SingleFlightNegativeCache<String,ScriptUrlCallResult>(60000L,128,{it.url.isBlank() && it.failureCategory.isBlank() && it.cacheableFailure})
 val synchronousHandlers=ConcurrentHashMap.newKeySet<String>()
 val fetchOwner=Any()
 val fetchScheduler=ScriptRequestScheduler.processWide
 val evalScripts=java.util.Collections.synchronizedList(mutableListOf<String>())
 fun requestHealthRecovery(purpose:Purpose) {}
 fun maybeRebuildWhenIdle() {}
 val ready=CompletableDeferred(true)
 val evaluationLanes=ScriptEvaluationLanes()
 val main=Poster()
 val webView:WV?=WV()
 class Poster { fun post(block:()->Unit){block()} }
 class WV { @Volatile var calls=0;val submitted=java.util.Collections.synchronizedList(mutableListOf<String>());fun evaluateJavascript(js:String,cb:Any?){calls++;submitted+=js} }
 fun ensureStarted(){}
 fun newCapability()=java.util.UUID.randomUUID().toString()
 fun slotId(id:String,code:String)=id
 fun buildJs(vararg args:Any)=args[0].toString()
 fun ownerCurrent()=true
 fun evalJs(js:String){evalScripts+=js}
 fun dispatchFetchResult(c:String,code:Int,text:String,headers:String="{}"){}
 fun doFetch(c:String,u:String,m:String,h:String?,b:String?,control:ScriptRequestScheduler.RequestControl):FetchResult {
   val call=FakeCall();activeCalls[c]=call;control.installCancellation(call){call.cancel()}
   while(!call.cancelled) Thread.sleep(1)
   return FetchResult(0,"")
 }
'''+methods+'''
 fun onResult(cap:String,url:String)=completeAndRemove(cap,url)
 fun onFailure(cap:String,category:String){lastFetchFailure[cap]=category;completeAndRemove(cap,"")}
 fun cancelPlay()=cancelUrlRequests{it.purpose==Purpose.PLAY}
 fun add(cap:String,p:Purpose){pendingUrl[cap]=PendingUrlRequest(cap,CompletableDeferred(),p,Job())}
}
fun main()=runBlocking {
 val r=Runtime();r.add("play",Purpose.PLAY);r.add("download",Purpose.DOWNLOAD)
 r.nativeFetch("https://example.com","GET",null,null,"p-child"'''+(',"play"' if 'requestCapability: String?' in method('        fun nativeFetch(') else '')+''')
 r.nativeFetch("https://example.com","GET",null,null,"d-child"'''+(',"download"' if 'requestCapability: String?' in method('        fun nativeFetch(') else '')+''')
 val until=System.nanoTime()+2_000_000_000L
 while(r.activeCalls.size<2 && System.nanoTime()<until) Thread.sleep(1)
 check(r.activeCalls.size==2)
 val play=r.activeCalls["p-child"]!!;val download=r.activeCalls["d-child"]!!
 r.cancelPlay()
 check(play.cancelled){"PLAY cancellation did not reach child network Call"}
 check(!download.cancelled){"DOWNLOAD was globally cancelled"}
 check(r.pendingUrl.containsKey("download"))
 r.nativeCancel("d-child");check(download.cancelled)
 println("PASS actual Kotlin Runtime PLAY child cancellation / DOWNLOAD survival / JS nativeCancel")
 val parent=coroutineContext[Job]!!
 val r2=Runtime()
 val old=launch{r2.getMusicUrl("id","code",JSONObject(),"kg","320k",Purpose.PLAY)}
 yield()
 val next=async{r2.getMusicUrl("id","code",JSONObject(),"kg","320k",Purpose.DOWNLOAD)}
 yield()
 r2.cancelPlay()
 old.join()
 check(parent.isActive){"Runtime cancelled caller parent Job"}
 check(!next.isCancelled){"Queued download cancelled by PLAY"}
 val dl=r2.pendingUrl.entries.single{it.value.purpose==Purpose.DOWNLOAD}
 r2.onResult(dl.key,"https://example.com/download.mp3")
	check(next.await().endsWith("download.mp3"))
	check(r2.pendingUrl.isEmpty())
	println("PASS actual getMusicUrl queued DOWNLOAD survives PLAY cancellation; caller parent survives; pending state drains")
	val r4=Runtime()
	val otherA=async{r4.getMusicUrl("script-a","code",JSONObject(),"kg","320k",Purpose.PREFETCH)}
	withTimeout(1000L){while(r4.webView!!.calls<1) delay(1)}
	val otherB=async{r4.getMusicUrl("script-b","code",JSONObject(),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(r4.webView!!.calls<2) delay(1)}
	val otherCaps=r4.webView!!.submitted.toList()
	check(otherCaps.size==2){"different script slots serialized through Promise wait"}
	r4.onResult(otherCaps[1],"https://example.com/b.mp3")
	r4.onResult(otherCaps[0],"https://example.com/a.mp3")
	check(otherA.await().endsWith("a.mp3") && otherB.await().endsWith("b.mp3"))
	println("PASS different script slots submit while the first Promise remains pending")
	val r5=Runtime()
	val sameA=async{r5.getMusicUrl("same-slot","code",JSONObject(),"kg","320k",Purpose.PREFETCH)}
	withTimeout(1000L){while(r5.webView!!.calls<1) delay(1)}
	val sameB=async{r5.getMusicUrl("same-slot","code",JSONObject(),"kg","320k",Purpose.DOWNLOAD)}
	withTimeout(1000L){while(r5.pendingUrl.size<2) delay(1)}
	delay(10)
	check(r5.webView!!.calls==1){"same script slot was evaluated concurrently"}
	r5.onResult(r5.webView!!.submitted.single(),"https://example.com/first.mp3")
	check(sameA.await().endsWith("first.mp3"))
	withTimeout(1000L){while(r5.webView!!.calls<2) delay(1)}
	val secondCap=r5.webView!!.submitted.last()
	r5.onResult(secondCap,"https://example.com/second.mp3")
	check(sameB.await().endsWith("second.mp3"))
	check(r5.evalScripts.count{it.contains("__swCancelRequest")}>=2){"completed callbacks did not close both JS capability scopes"}
	println("PASS same script slot remains single-flight until its prior Promise is cleaned")
 val r3=Runtime()
 val timeout=async{r3.getMusicUrl("id","code",JSONObject(),"kg","320k",Purpose.PLAY)}
 yield()
 val waiting=async{r3.getMusicUrl("id","code",JSONObject(),"kg","320k",Purpose.DOWNLOAD)}
 check(timeout.await().isEmpty())
 yield()
 check(!waiting.isCancelled){"PLAY watchdog destroyed queued download"}
 val waitingCap=r3.pendingUrl.entries.single().key
 r3.onResult(waitingCap,"https://example.com/survives.mp3")
	check(waiting.await().endsWith("survives.mp3"))
	println("PASS actual getMusicUrl local watchdog does not rebuild/cancel queued DOWNLOAD")
	val duplicateRuntime=Runtime()
	val duplicateA=async{duplicateRuntime.getMusicUrl("same-script","code",JSONObject().put("id","same-song"),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(duplicateRuntime.webView!!.calls<1) delay(1)}
	val duplicateB=async{duplicateRuntime.getMusicUrl("same-script","code",JSONObject().put("id","same-song"),"kg","320k",Purpose.PLAY)}
	delay(20)
	check(duplicateRuntime.webView!!.calls==1 && duplicateRuntime.pendingUrl.size==1){"identical song/quality requests were not coalesced"}
	duplicateRuntime.onResult(duplicateRuntime.webView!!.submitted.single(),"https://example.com/shared.mp3")
	check(duplicateA.await().endsWith("shared.mp3") && duplicateB.await().endsWith("shared.mp3"))
	println("PASS identical song/quality/purpose requests share one production Runtime call")
	val distinctRuntime=Runtime()
	val songA=async{distinctRuntime.getMusicUrl("same-script","code",JSONObject().put("id","song-a"),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(distinctRuntime.webView!!.calls<1) delay(1)}
	val songB=async{distinctRuntime.getMusicUrl("same-script","code",JSONObject().put("id","song-b"),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(distinctRuntime.pendingUrl.size<2) delay(1)}
	distinctRuntime.onResult(distinctRuntime.webView!!.submitted.first(),"https://example.com/song-a.mp3")
	check(songA.await().endsWith("song-a.mp3"))
	withTimeout(1000L){while(distinctRuntime.webView!!.calls<2) delay(1)}
	distinctRuntime.onResult(distinctRuntime.webView!!.submitted.last(),"https://example.com/song-b.mp3")
	check(songB.await().endsWith("song-b.mp3"))
	println("PASS different song identities never share a cached or in-flight result")
	val budgetRuntime=Runtime()
	val held=CompletableDeferred<Unit>()
	val entered=CompletableDeferred<Unit>()
	val holder=launch{budgetRuntime.evaluationLanes.withSlot("budget-script"){entered.complete(Unit);held.await()}}
	entered.await()
	val budgeted=async{budgetRuntime.getMusicUrlShortBudget("budget-script","code",JSONObject(),"kg","320k",Purpose.PLAY)}
	check(budgeted.await().isEmpty())
	check(budgetRuntime.lastFailureByScript["budget-script"]=="timeout"){"slot queue wait escaped the total script-call budget"}
	check(budgetRuntime.pendingUrl.isEmpty()){"timed out queued request retained its capability"}
	held.complete(Unit);holder.join()
	println("PASS total getMusicUrl budget includes script-slot admission and drains queued cancellation")
	val retryRuntime=Runtime()
	val failed=async{retryRuntime.getMusicUrl("retry-script","code",JSONObject().put("id","same-song"),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(retryRuntime.webView!!.calls<1) delay(1)}
	retryRuntime.onFailure(retryRuntime.webView!!.submitted.single(),"network")
	check(failed.await().isEmpty())
	val retry=async{retryRuntime.getMusicUrl("retry-script","code",JSONObject().put("id","same-song"),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(retryRuntime.webView!!.calls<2) delay(1)}
	retryRuntime.onResult(retryRuntime.webView!!.submitted.last(),"https://example.com/recovered.mp3")
	check(retry.await().endsWith("recovered.mp3"))
	println("PASS classified network failures are not memoized and the same request retries immediately")
	val semanticEmptyRuntime=Runtime()
	val semanticEmpty=async{semanticEmptyRuntime.getMusicUrl("empty-script","code",JSONObject(),"kg","320k",Purpose.PLAY)}
	withTimeout(1000L){while(semanticEmptyRuntime.webView!!.calls<1) delay(1)}
	semanticEmptyRuntime.onResult(semanticEmptyRuntime.webView!!.submitted.single(),"")
	check(semanticEmpty.await().isEmpty())
	check(semanticEmptyRuntime.getMusicUrl("empty-script","code",JSONObject(),"kg","320k",Purpose.PLAY).isEmpty())
	check(semanticEmptyRuntime.webView!!.calls==1){"semantic no-URL result should remain briefly memoized"}
	println("PASS genuine semantic empty result remains within the bounded negative cache")
	val oversizedRuntime=Runtime()
	check(oversizedRuntime.getMusicUrl("large-script","x".repeat(ScriptExecutionLimits.MAX_SCRIPT_CODE_BYTES+1),JSONObject(),"kg","320k",Purpose.PLAY).isEmpty())
	check(oversizedRuntime.webView!!.calls==0 && oversizedRuntime.pendingUrl.isEmpty()){"oversized script reached WebView"}
	println("PASS oversized script is rejected before WebView submission")
}
'''
(work/'Harness.kt').write_text(source)
lib=Path('/tmp/r657-security-jvm'); jars=list(lib.glob('*.jar'));cor=Path('/tmp/r657-migu-jvm/coroutines.jar');cp=':'.join(map(str,jars+[cor]))
cmd=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(work/'classes'),str(work/'Harness.kt'),str(BASE/'ScriptRequestScheduler.kt'),str(BASE/'script/ScriptEvaluationLanes.kt'),str(BASE/'script/ScriptExecutionLimits.kt'),str(BASE/'script/SingleFlightNegativeCache.kt')]
r=subprocess.run(cmd,capture_output=True,text=True); print(r.stdout+r.stderr,end='');assert r.returncode==0
r=subprocess.run(['java','-cp',str(work/'classes')+':'+cp,'xyz.cncnlp.soundwave.HarnessKt'],capture_output=True,text=True,timeout=10);print(r.stdout+r.stderr,end='');raise SystemExit(r.returncode)
