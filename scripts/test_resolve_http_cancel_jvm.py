#!/usr/bin/env python3
"""Run actual resolveCall over two real local OkHttp sockets, stalled body cancellation."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1];BASE=ROOT/'app/src/main/java/xyz/cncnlp/soundwave';w=Path('/tmp/r657-resolve-http-jvm');w.mkdir(exist_ok=True)
resolver=(BASE/'UrlResolver.kt').read_text()
media=resolver[resolver.index('    private suspend fun validateProbeMedia('):resolver.index('    private suspend fun buildMusicInfo(')]
cache=(BASE/'ScriptProbeCache.kt').read_text()
attempt=cache[cache.index('    data class Attempt('):cache.index('        val latencyMs:')].rsplit(' {',1)[0]
probe_client=resolver[resolver.index('    private val probeMediaClient'):resolver.index('    private suspend fun validateProbeMedia(')]
models=(BASE/'ResolutionModels.kt').read_text()
purpose=models[models.index('enum class Purpose'):models.index('}',models.index('enum class Purpose'))+1]
(w/'Media.kt').write_text('package xyz.cncnlp.soundwave\nimport okhttp3.ResponseBody.Companion.toResponseBody\nimport xyz.cncnlp.soundwave.download.AudioQualityEvidence\nobject AppHttp { val shared=okhttp3.OkHttpClient() }\nclass ResolveTicket { '+purpose+' }\nobject MediaDecryptCache{fun encryptedExtOf(url:String):String?=null}\nobject MediaRequestPolicy{fun apply(b:okhttp3.Request.Builder)=b}\nobject ScriptProbeCache {enum class Outcome{OK,UNSUPPORTED,NO_MATCH,FAILED,EXCLUDED}\n'+attempt+'\n}\nobject MediaProbe {\n'+probe_client+media.replace('private suspend fun validateProbeMedia','suspend fun validateProbeMedia')+'\n}')
(w/'HttpTest.kt').write_text('''package xyz.cncnlp.soundwave
import kotlinx.coroutines.*
import okhttp3.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
fun main()=runBlocking {
 val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 val bodyStarted=CountDownLatch(1);val release=CountDownLatch(1)
 val probeStarted=CountDownLatch(1);val releaseProbe=CountDownLatch(1);val playStarted=CountDownLatch(1)
 server.executor=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
 server.createContext("/hang"){e->e.sendResponseHeaders(200,100);e.responseBody.write(byteArrayOf(1));e.responseBody.flush();bodyStarted.countDown();release.await();e.close()}
 server.createContext("/download"){e->e.sendResponseHeaders(200,2);e.responseBody.use{it.write(byteArrayOf(1,2))}}
 fun audio(e:com.sun.net.httpserver.HttpExchange){val bytes=ByteArray(2048){1};e.responseHeaders.add("Content-Type","audio/mpeg");e.responseHeaders.add("Content-Range","bytes 0-2047/2048");e.sendResponseHeaders(206,bytes.size.toLong());e.responseBody.use{it.write(bytes)}}
 server.createContext("/probe-stall"){e->probeStarted.countDown();check(releaseProbe.await(3,TimeUnit.SECONDS));audio(e)}
 server.createContext("/play-fast"){e->playStarted.countDown();audio(e)}
 server.start();val client=OkHttpClient();val base="http://127.0.0.1:${server.address.port}"
 val playCall=client.newCall(Request.Builder().url("$base/hang").build())
 val play=launch(Dispatchers.IO){resolveCall(playCall){it.body!!.bytes()}}
 withContext(Dispatchers.IO){check(bodyStarted.await(2,java.util.concurrent.TimeUnit.SECONDS))}
 val download=async(Dispatchers.IO){resolveCall(client.newCall(Request.Builder().url("$base/download").build())){it.body!!.bytes().size}}
 val t=System.nanoTime();play.cancelAndJoin()
 check(playCall.isCanceled());check((System.nanoTime()-t)/1_000_000<500)
 check(download.await()==2)
 println("PASS real OkHttp blocked response-body cancellation; concurrent download survives")
 // legacy providers may ignore interrupt; caller deadline must still return promptly
 val legacy=launch{ResolveBlockingStage.run{Thread.sleep(250);"late"}}
 delay(20);val t2=System.nanoTime();legacy.cancelAndJoin();check((System.nanoTime()-t2)/1_000_000<100)
 println("PASS legacy blocking stage caller cancellation does not wait for execute completion")
 val probe=launch(Dispatchers.IO){MediaProbe.validateProbeMedia("kg","320k","$base/hang",0,ResolveTicket.Purpose.PROBE)}
 delay(100);val t3=System.nanoTime();probe.cancelAndJoin();check((System.nanoTime()-t3)/1_000_000<500)
 check(probe.isCancelled)
 val observed=MediaProbe.validateProbeMedia("kg","128k","$base/download",0,ResolveTicket.Purpose.PROBE)
 check(observed.httpStatus==200 && observed.firstByteReceived)
 println("PASS actual validateProbeMedia cancellation + real HTTP first-byte path")
 // Keep the single-slot PROBE client stalled before response headers: foreground PLAY must use a separate dispatcher.
 val stalledProbe=async(Dispatchers.IO){MediaProbe.validateProbeMedia("kg","128k","$base/probe-stall",0,ResolveTicket.Purpose.PROBE)}
 val probeEntered=withContext(Dispatchers.IO){probeStarted.await(2,TimeUnit.SECONDS)}
 check(probeEntered){"PROBE request never reached local server"}
 val foregroundPlay=async(Dispatchers.IO){MediaProbe.validateProbeMedia("kg","128k","$base/play-fast",0,ResolveTicket.Purpose.PLAY)}
 val playReachedServer=withContext(Dispatchers.IO){playStarted.await(400,TimeUnit.MILLISECONDS)}
 val playCompletedBeforeProbeRelease=withTimeoutOrNull(1000){foregroundPlay.await();true}==true
 releaseProbe.countDown()
 val probeResult=withTimeout(2500){stalledProbe.await()}
 val playResult=withTimeout(2500){foregroundPlay.await()}
 val foregroundWasIndependent=playReachedServer && playCompletedBeforeProbeRelease
 val bothValidatedFirstByte=probeResult.firstByteReceived && playResult.firstByteReceived
 release.countDown();server.stop(0);client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();AppHttp.shared.dispatcher.executorService.shutdownNow();AppHttp.shared.connectionPool.evictAll()
 val probeClient=MediaProbe.javaClass.getDeclaredMethod("getProbeMediaClient").apply{isAccessible=true}.invoke(MediaProbe) as OkHttpClient
 probeClient.dispatcher.executorService.shutdownNow();probeClient.connectionPool.evictAll()
 check(foregroundWasIndependent){"foreground PLAY queued behind a stalled single-slot PROBE dispatcher (reached=$playReachedServer, completed=$playCompletedBeforeProbeRelease)"}
 check(bothValidatedFirstByte)
 println("PASS actual PROBE media validation cannot occupy foreground PLAY dispatcher")
}
''')
lib=Path('/tmp/r657-security-jvm');cp=':'.join(map(str,list(lib.glob('*.jar'))+[Path('/tmp/r657-migu-jvm/coroutines.jar')]))
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(w/'classes'),str(w/'HttpTest.kt'),str(w/'Media.kt'),str(BASE/'download/AudioQualityEvidence.kt'),str(BASE/'ResolveBudget.kt')],capture_output=True,text=True);print(r.stdout+r.stderr,end='');assert r.returncode==0
r=subprocess.run(['java','-cp',str(w/'classes')+':'+cp,'xyz.cncnlp.soundwave.HttpTestKt'],capture_output=True,text=True,timeout=10);print(r.stdout+r.stderr,end='');raise SystemExit(r.returncode)
