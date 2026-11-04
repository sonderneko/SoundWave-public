package xyz.cncnlp.soundwave

import java.io.IOException
import java.net.SocketTimeoutException
import java.io.InterruptedIOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.source.direct.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Batch4CatalogBoundaryTest {
 private fun failure(r:PlatformTransportOutcome<*>)=(r as PlatformTransportOutcome.Failure).error
 private fun api(exchange:(String)->Pair<Int,String>)=PlaylistCatalogApi(PlaylistCatalogTextClient({"https://catalog.invalid$it"},exchange))
 @Test fun transportAndPayloadFailureKindsRemainDistinctAndRedacted() {
  val secret="https://user:pass@private.invalid/?token=secret Cookie=oops"
  for((exchange,kind) in listOf<Pair<(String)->Pair<Int,String>,PlatformTransportErrorKind>>(
   ({ _: String -> throw IOException(secret)}) to PlatformTransportErrorKind.NETWORK,
   ({ _: String -> throw SocketTimeoutException(secret)}) to PlatformTransportErrorKind.TIMEOUT,
   ({ _: String -> throw InterruptedIOException(secret)}) to PlatformTransportErrorKind.TIMEOUT,
   ({ _: String -> 200 to "<html>$secret</html>"}) to PlatformTransportErrorKind.PARSE,
   ({ _: String -> 200 to "{\"ok\":false,\"message\":\"$secret\",\"list\":[]}"}) to PlatformTransportErrorKind.REMOTE_FAILURE)) {
    val r=api(exchange).recommend("wy",9);assertEquals(kind,failure(r).kind);assertNull(failure(r).detail);assertFalse(r.toString().contains("secret"));assertFalse(r.toString().contains("private.invalid"))
  }
 }
 @Test fun httpStatusRetainedWithoutResponseBody() { val r=api{503 to "password private"}.searchCategory("q","wy",9);assertEquals(PlatformTransportErrorKind.HTTP_STATUS,failure(r).kind);assertEquals(503,failure(r).httpStatus);assertFalse(r.toString().contains("private")) }
 @Test fun validEmptyDiffersFromEmptyBodyAndMissingEnvelope() { val good=api{200 to "{\"list\":[]}"}.searchCategory("q","wy",3) as PlatformTransportOutcome.Success;assertTrue(good.value.isEmpty());for(raw in listOf("","{}"," ")) assertEquals(PlatformTransportErrorKind.PARSE,failure(api{200 to raw}.recommend("wy",3)).kind) }
 @Test fun policyRejectionNeverCallsExchange() {var n=0; val c=PlaylistCatalogTextClient({"http://blocked.invalid/a?token=secret"},{n++;200 to "{}"});val r=c.get("/ignored");assertEquals(0,n);assertEquals(PlatformTransportErrorKind.POLICY,failure(r).kind);assertFalse(r.toString().contains("secret")) }
 @Test fun cancellationIdentityEscapesWithoutRetry() {val expected=CancellationException("owner");var n=0;try {api{n++;throw expected}.recommend("wy",3);fail("swallowed")}catch(actual:CancellationException){assertSame(expected,actual)};assertEquals(1,n) }
 @Test fun retryCountAndDelayRemainBoundedAndSuccessStopsRetry() {var n=0;var delays=0;val c=PlaylistCatalogTextClient({"https://catalog.invalid"},{n++;503 to "error"},pause={delays++});assertEquals(503,failure(c.get("x")).httpStatus);assertEquals(2,n);assertEquals(2,delays);n=0;val c2=PlaylistCatalogTextClient({"https://catalog.invalid"},{n++;if(n==1)503 to "bad" else 200 to "ok"});assertEquals("ok",(c2.get("x") as PlatformTransportOutcome.Success).value);assertEquals(2,n) }
 @Test fun sourceAndPathContractsStaySpecific() {val paths=mutableListOf<String>();val a=api{paths+=it;200 to "{\"list\":[]}"};a.searchCategory(" ","tx &",999);a.searchLegacy("q","",999);a.recommend("kw",51);assertTrue(paths[0].contains("keyword=%E6%8E%A8%E8%8D%90"));assertTrue(paths[0].contains("&source=tx+%26&src=tx+%26&limit=200"));assertTrue(paths[1].endsWith("keyword=q&limit=999"));assertTrue(paths[2].endsWith("action=recommend&source=kw&src=kw&limit=51")) }
 @Test fun categoryRootAndRecommendNestedFailureCompatibility() {val payload="{\"data\":{\"success\":false,\"list\":[]}}";assertTrue(api{200 to payload}.searchCategory("q","wy",3) is PlatformTransportOutcome.Success);assertEquals(PlatformTransportErrorKind.REMOTE_FAILURE,failure(api{200 to payload}.recommend("wy",3)).kind) }
 @Test fun sourceAliasesRemainPlatformAware() {val raw="""{"records":[{"id":"a","source":"qq","name":"A"},{"id":"b","src":"wy"},{"id":"c"}]}"""; val rows=(api{200 to raw}.searchCategory("q","tx",5) as PlatformTransportOutcome.Success).value;assertEquals(listOf("a","c"),rows.map{it.id});assertTrue(rows.all{it.source=="tx"}) }
}

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Batch4CatalogWiringTest {
 private fun response(r:Request,code:Int=200,raw:String="{\"list\":[]}")=Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("controlled").body(raw.toResponseBody("application/json".toMediaType())).build()
 private fun http(block:(Interceptor.Chain)->Response)=OkHttpClient.Builder().addInterceptor{block(it)}.build()
 @Test fun productionFactoryBuildsManagedHeadersAndActualFamilyRequest() {var request:Request?=null;val client=http{request=it.request();response(it.request(),raw="""{"list":[{"id":"p","src":"wy","name":"P"}]}""")};val result=ApiClient.playlistCatalog(http=client,pause={}).searchCategory("one & two","wy",12) as PlatformTransportOutcome.Success;assertEquals("p",result.value.single().id);val r=request!!;assertEquals("GET",r.method);assertEquals("one & two",r.url.queryParameter("keyword"));assertEquals("wy",r.url.queryParameter("source"));assertEquals("wy",r.url.queryParameter("src"));assertEquals("12",r.url.queryParameter("limit"));assertEquals("application/json",r.header("Accept"));assertTrue(r.header("User-Agent")!!.contains("SoundWave"));assertEquals(ApiClient.baseUrl,r.header("Origin"));assertNull(r.header("Authorization")) }
 @Test fun productionFactoryRetainsActualHttpAndTimeoutDistinction() {for((client,kind) in listOf(http{response(it.request(),429,"credential=secret")} to PlatformTransportErrorKind.HTTP_STATUS,http{throw SocketTimeoutException("https://secret.invalid?pass=hidden")} to PlatformTransportErrorKind.TIMEOUT)){val result=ApiClient.playlistCatalog(http=client,pause={}).recommend("tx",8) as PlatformTransportOutcome.Failure;assertEquals(kind,result.error.kind);assertFalse(result.toString().contains("secret"))} }
 @Test fun productionFactoryChecksPolicyBeforeClientAndKeepsLegacyBoundary() {val old=ApiClient.baseUrl;var n=0;try{ApiClient.baseUrl="http://blocked.invalid";val result=ApiClient.playlistCatalog(http=http{n++;error("must not call")},pause={}).searchCategory("q","wy",3);assertEquals(PlatformTransportErrorKind.POLICY,(result as PlatformTransportOutcome.Failure).error.kind);assertEquals(xyz.cncnlp.soundwave.source.PlaylistSearchOutcome.Failure,result.asCategoryOutcome());assertEquals(0,n)}finally{ApiClient.baseUrl=old} }
 @Test fun actualOwnerCancellationEscapesBeforeFallbackRetry() {val job=Job();val scope=OfficialCallScope(job);var n=0;try{val c=http{n++;job.cancel();throw IOException("socket cancelled")};try{ApiClient.playlistCatalog(scope,c,{}).recommend("wy",5);fail("swallowed owner")}catch(_:CancellationException){};assertEquals(1,n)}finally{scope.close()} }
 @Test fun gzipAndResponseLimitStillUseTheExistingBoundedReader() {val bytes=java.io.ByteArrayOutputStream().also{b->java.util.zip.GZIPOutputStream(b).use{it.write("{\"list\":[]}".toByteArray())}}.toByteArray();val c=http{response(it.request()).newBuilder().header("Content-Encoding","gzip").body(bytes.toResponseBody(null)).build()};assertTrue(ApiClient.playlistCatalog(http=c,pause={}).recommend("wy",5) is PlatformTransportOutcome.Success);var n=0;val tooLarge=http{n++;response(it.request(),raw="x".repeat(2*1024*1024+1))};val r=ApiClient.playlistCatalog(http=tooLarge,pause={}).recommend("wy",5);assertEquals(PlatformTransportErrorKind.NETWORK,(r as PlatformTransportOutcome.Failure).error.kind);assertEquals(2,n) }
}
