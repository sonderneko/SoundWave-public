#!/usr/bin/env python3
"""Actual PlatformHttp + scoped ApiClient transport on real loopback OkHttp sockets; no SDK."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'app/src/main/java/xyz/cncnlp/soundwave'
w=Path('/tmp/r657-official-transport-jvm');w.mkdir(exist_ok=True)
(w/'Seams.kt').write_text('''package xyz.cncnlp.soundwave
object AppHttp { val calls=java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>(); val shared=okhttp3.OkHttpClient.Builder().eventListener(object:okhttp3.EventListener(){override fun callStart(call:okhttp3.Call){calls.add(call)}}).build() }
''')
policy=(BASE/'source/direct/HttpPolicy.kt').read_text()
# Keep the actual production interceptor/policy implementation, allowing only this
# loopback harness's dynamically allocated HTTP port as a test-only exception.
policy=policy.replace('    return when (scheme) {','    if (scheme == "http" && host == "127.0.0.1" && port in 1..65535) return true\n    return when (scheme) {')
(w/'Policy.kt').write_text(policy)
(w/'Test.kt').write_text('''package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import xyz.cncnlp.soundwave.source.direct.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.*
fun main()=runBlocking {
 val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 val bodyStarted=CountDownLatch(1);val headStarted=CountDownLatch(1);val release=CountDownLatch(1)
 server.executor=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
 server.createContext("/body"){e->e.sendResponseHeaders(200,100);e.responseBody.write(byteArrayOf(1));e.responseBody.flush();bodyStarted.countDown();release.await();e.close()}
 server.createContext("/headers"){e->headStarted.countDown();release.await();e.close()}
 server.createContext("/ok"){e->e.sendResponseHeaders(200,2);e.responseBody.use{it.write("ok".toByteArray())}}
 server.createContext("/fail"){e->e.sendResponseHeaders(503,-1);e.close()}
 server.start();val base="http://127.0.0.1:${server.address.port}"
 try {
 for ((path,started) in listOf("body" to bodyStarted,"headers" to headStarted)) {
  var fallback=false
  val play=launch(Dispatchers.IO){platformHttpGet("$base/$path",base);fallback=true}
  withContext(Dispatchers.IO){check(started.await(2,TimeUnit.SECONDS))}
  val owned=AppHttp.calls.last()
  val download=async(Dispatchers.IO){platformHttpGet("$base/ok",base)}
  val t=System.nanoTime();play.cancelAndJoin()
  check(owned.isCanceled()){ "actual $path Call was not cancelled" }
  check((System.nanoTime()-t)/1_000_000<500){"$path cancellation waited for socket timeout"}
  check(!fallback){"cancelled $path fell back"};check(download.await()=="ok")
  println("PASS actual PlatformHttp $path cancellation; no fallback; sibling download survives")
 }
 check(platformHttpGet("$base/ok",base)=="ok")
 check(platformHttpGet("$base/fail",base)=="")
 check(platformHttpPostJson("$base/ok","{}",base)=="ok")
 println("PASS ordinary GET/POST success and HTTP failure sentinel")
 val timeoutCount=AppHttp.calls.size
 try{withTimeout(100){platformHttpGet("$base/body",base)};error("deadline ignored")}catch(_:TimeoutCancellationException){}
 check(AppHttp.calls.size==timeoutCount+1 && AppHttp.calls.last().isCanceled())
 println("PASS actual coroutine deadline cancels native Call")
 // Exact production ApiClient methods, including retry loops and catch boundaries.
 val routes=listOf("/music/search","/music/playlist")
 var mode="body";var arrived=CountDownLatch(1);val hits=java.util.concurrent.atomic.AtomicInteger()
 routes.forEach { path -> server.createContext(path){e->
  hits.incrementAndGet()
  when(mode){
   "headers"->{arrived.countDown();release.await();e.close()}
   "body"->{e.sendResponseHeaders(200,100);e.responseBody.write(byteArrayOf(1));e.responseBody.flush();arrived.countDown();release.await();e.close()}
   "fail"->{e.sendResponseHeaders(503,-1);e.close()}
   else->{val b="""{"url":"https://media.example/song","list":[{"id":"1"}]}""".toByteArray();e.sendResponseHeaders(200,b.size.toLong());e.responseBody.use{it.write(b)}}
  }
 }}
 ApiClient.baseUrl=base
 for (m in listOf("headers","body")) for (op in 0..2) {
  mode=m;arrived=CountDownLatch(1);val before=hits.get();var returned=false
  val job=launch(Dispatchers.IO){when(op){0->ApiClient.searchCancellable("query","wy");1->ApiClient.searchPlaylistsCancellable("query","wy",30);else->ApiClient.searchPlaylistsCategoryOutcomeCancellable("query","wy",30)};returned=true}
  withContext(Dispatchers.IO){check(arrived.await(3,TimeUnit.SECONDS))}
  val call=AppHttp.calls.last();val t=System.nanoTime();job.cancelAndJoin()
  check(call.isCanceled());check((System.nanoTime()-t)/1_000_000<500);check(!returned)
  delay(30);check(hits.get()==before+1){"cancelled operation retried/fell back"}
 }
 println("PASS actual ApiClient search/playlist/category stalled headers and body; no retry/fallback")
 mode="ok"
 check(ApiClient.searchCancellable("query","wy").size==1)
 check(ApiClient.searchPlaylistsCancellable("query","wy",30).size==1)
 check(ApiClient.search("query","wy").size==1)
 mode="fail";check(ApiClient.searchCancellable("query","wy").isEmpty())
 println("PASS actual ApiClient success/failure and legacy synchronous consumers")
 val cancelledJob=Job();cancelledJob.cancel();val pre=OfficialCallScope(cancelledJob)
 val rejected=AppHttp.shared.newCall(okhttp3.Request.Builder().url("$base/ok").build())
 try{pre.execute(rejected){it.code};error("pre-cancel accepted")}catch(_:kotlinx.coroutines.CancellationException){}
 check(rejected.isCanceled() && !rejected.isExecuted());pre.close()
 val completedJob=Job();val done=OfficialCallScope(completedJob)
 val completedCall=AppHttp.shared.newCall(okhttp3.Request.Builder().url("$base/ok").build())
 check(done.execute(completedCall){it.body!!.string()}=="ok");completedJob.cancel();check(!completedCall.isCanceled());done.close()
 val raceJob=Job();val race=OfficialCallScope(raceJob);val reading=CountDownLatch(1);val finish=CountDownLatch(1)
 val racing=AppHttp.shared.newCall(okhttp3.Request.Builder().url("$base/ok").build())
 val worker=async(Dispatchers.IO){try{race.execute(racing){it.body!!.string();reading.countDown();finish.await();"stale"};false}catch(_:kotlinx.coroutines.CancellationException){true}}
 withContext(Dispatchers.IO){check(reading.await(2,TimeUnit.SECONDS))};raceJob.cancel();finish.countDown();check(worker.await());check(racing.isCanceled());race.close()
 println("PASS pre-registration cancel, completed unregister, cancellation-at-consumer-completion gate")
 mode="ok";val adapterHits=hits.get()
 val good=Adapter(directSearch={_,_,_->listOf(Song("direct",cover="cover"))})
 check(good.search("x",1,30).single().id=="direct" && hits.get()==adapterHits)
 check(Adapter(directSearch={_,_,_->throw java.io.IOException("ordinary failure")}).search("x",1,30).size==1)
 val beforeCancel=hits.get()
 try{Adapter(directSearch={_,_,_->throw kotlinx.coroutines.CancellationException("stop")}).search("x",1,30);error("swallowed")}catch(_:kotlinx.coroutines.CancellationException){}
 try{Adapter(directPlaylistSearch={_,_,_->throw kotlinx.coroutines.CancellationException("stop")}).searchPlaylistsPage("x","wy",1,30);error("swallowed")}catch(_:kotlinx.coroutines.CancellationException){}
 check(hits.get()==beforeCancel)
 println("PASS actual adapter local-first success, ordinary failure fallback, cancellation never fallback")
 // Real metadata caches with only endpoint URL injected; actual transport is unchanged.
 for (paid in listOf(false,true)) {
  val entered=CountDownLatch(1);var requests=0
  val mg=MiguMetadataCache(fetch={requests++;entered.countDown();platformHttpGet("$base/body",base)})
  val kg=PaidAlbumMetadataCache(fetchPage={_,_->requests++;entered.countDown();platformHttpGet("$base/body",base)})
  val count=AppHttp.calls.size
  val job=launch(Dispatchers.IO){if(paid)kg.apply(Song(source="kg",albumId="1",hash="A".repeat(32)),org.json.JSONObject()) else mg.prepare(Song(id="1",source="mg"))}
  withContext(Dispatchers.IO){check(entered.await(2,TimeUnit.SECONDS))}
  // Wait until the new real Call has started, not merely the fetch lambda.
  withTimeout(2000){while(AppHttp.calls.size==count)delay(1)}
  val call=AppHttp.calls.last();delay(20);val t=System.nanoTime();job.cancelAndJoin()
  check(call.isCanceled());check((System.nanoTime()-t)/1_000_000<500);check(requests==1)
 }
 println("PASS actual Migu/PaidAlbum metadata caches cancel their real body-reading Calls")
 } finally {release.countDown();server.stop(0);AppHttp.shared.dispatcher.executorService.shutdownNow();AppHttp.shared.connectionPool.evictAll()}
}
''')
lib=Path('/tmp/r657-security-jvm');cp=':'.join(map(str,list(lib.glob('*.jar'))+[Path('/tmp/r657-migu-jvm/coroutines.jar')]))
catalog=(BASE/'PlaylistCatalogApi.kt').read_text()
catalog=catalog.replace('import xyz.cncnlp.soundwave.source.FeedListOutcome\n','').replace('import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome\n','')
(w/'PlaylistCatalogApi.kt').write_text(catalog)
sources=[w/'Test.kt',w/'Seams.kt',w/'Policy.kt',BASE/'ApiResponseReader.kt',BASE/'source/direct/PlatformHttp.kt', BASE/'source/direct/PlatformTransportOutcome.kt', w/'PlaylistCatalogApi.kt', BASE/'MiguScriptMetadata.kt', BASE/'PaidAlbumScriptMetadata.kt']
api=(BASE/'ApiClient.kt').read_text()
# Exact production transport/retry/search bodies; parsing models are explicit seams.
# (ACCEPT5: officialUrl* bodies are gone with the deleted /music/url fallback;
# the extraction span below starts at searchPlaylists instead.)
def section(start,end):
    return api[api.index(start):api.index(end,api.index(start))]
api_parts='\n'.join([
    section('    fun get(pathOrUrl:', '    fun postJson('),
    section('    private const val MAX_TEXT_RESPONSE_BYTES', '    private fun systemSslFactory'),
    section('    fun search(keyword:', '    /**\n     * 首页发现位'),
    section('    fun searchPlaylists(keyword:', '    /**\n     * 首页甄选歌单'),
    section('    internal fun playlistCatalog(', '    /** Compatibility adapters'),
])
(w/'PlaylistPayloadFixture.kt').write_text('''package xyz.cncnlp.soundwave
import org.json.JSONObject
import xyz.cncnlp.soundwave.source.direct.PlatformTransportOutcome
internal object PlaylistCatalogPayloads {
 fun legacy(raw:String,source:String):List<Playlist> = if(JSONObject(raw).optJSONArray("list")?.length()==1) listOf(Playlist("1")) else emptyList()
 fun validated(raw:String,source:String,checkNestedFailure:Boolean):PlatformTransportOutcome<List<Playlist>> = PlatformTransportOutcome.Success(legacy(raw,source))
}
''')
(w/'Api.kt').write_text('''package xyz.cncnlp.soundwave
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.zip.GZIPInputStream
import org.json.*
fun isCleartextApiAllowed(url:String)=url.startsWith("http://127.0.0.1:")
data class Song(val id:String="",val name:String="",val singer:String="",val album:String="",val cover:String="",val source:String="wy",val miguSongId:String="",val copyrightId:String="",val albumId:String="",val hash:String="",val kgAlbumMetadata:KgAlbumMetadata?=null)
data class Playlist(val id:String="")
sealed class PlaylistSearchOutcome {data class Success(val playlists:List<Playlist>):PlaylistSearchOutcome();object Failure:PlaylistSearchOutcome()}
sealed class FeedListOutcome<out T> {data class Success<T>(val data:T):FeedListOutcome<T>();object Failure:FeedListOutcome<Nothing>()}
fun classifyListHttpResponse(code:Int,text:String):FeedListOutcome<String> = if(code in 200..299 && text.isNotBlank()) FeedListOutcome.Success(text) else FeedListOutcome.Failure
object ApiClient {
 var baseUrl="";val UA="test";val JSON_MT="application/json".toMediaType();val client=AppHttp.shared;val resolveClient=client
 fun abs(path:String)=baseUrl+path
 fun parseSongList(a:JSONArray?,s:String)= if(a==null) emptyList() else (0 until a.length()).map{Song(id=a.getJSONObject(it).optString("id"),name="track")}
 fun parsePlaylistsPayload(raw:String,source:String):List<Playlist> = if(JSONObject(raw).optJSONArray("list")?.length()==1) listOf(Playlist("1")) else emptyList()
 fun parseCategoryPlaylistPayload(raw:String,source:String):PlaylistSearchOutcome = PlaylistSearchOutcome.Success(parsePlaylistsPayload(raw,source))
'''+api_parts+'\n}')
sources.extend([w/'Api.kt',w/'PlaylistPayloadFixture.kt'])
adapter=(BASE/'source/PlatformSourceAdapter.kt').read_text()
song_search=adapter[adapter.index('    override suspend fun search('):adapter.index('    // Keep legacy server-only')].replace('override suspend','suspend')
playlist_search=adapter[adapter.index('    override suspend fun searchPlaylistsPage('):adapter.index('    /** Category-only')].replace('override suspend','suspend')
(w/'Adapter.kt').write_text('''package xyz.cncnlp.soundwave
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
class Adapter(val directSearch:(suspend(String,Int,Int)->List<Song>)?=null,val directPlaylistSearch:(suspend(String,Int,Int)->List<Playlist>)?=null){
 val api=ApiClient; val platform="wy"
'''+song_search+playlist_search+'\n}')
sources.append(w/'Adapter.kt')
helper=BASE/'OfficialCallScope.kt'
if helper.exists():sources.append(helper)
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(w/'classes'),*map(str,sources)],capture_output=True,text=True)
print(r.stdout+r.stderr,end='');assert r.returncode==0
r=subprocess.run(['java','-cp',str(w/'classes')+':'+cp,'xyz.cncnlp.soundwave.TestKt'],capture_output=True,text=True,timeout=20)
print(r.stdout+r.stderr,end='');raise SystemExit(r.returncode)
