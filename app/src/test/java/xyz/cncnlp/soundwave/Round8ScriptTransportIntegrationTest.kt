package xyz.cncnlp.soundwave

import android.content.Context
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Actual Bridge.doFetch/fetchOnce executed with an OkHttp application interceptor.
 * No sockets, public platform, real proxy, or private network requests are used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Round8ScriptTransportIntegrationTest {
    private class Fixture(val respond: (Request, Fixture) -> Response) {
        val runtime=ScriptRuntime(RuntimeEnvironment.getApplication() as Context)
        val bridge=runtime.Bridge()
        val requests=mutableListOf<Request>()
        val cancelled=AtomicBoolean(false)
        var cancellation:(()->Unit)?=null
        var clears=0
        val control=ScriptRequestScheduler.RequestControl(
            {cancelled.get()}, {_, action -> if(cancelled.get()) false else {cancellation=action;true}}, {_->cancellation=null;clears++})
        init {
            val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .addInterceptor{chain->requests.add(chain.request());respond(chain.request(),this)}.build()
            ScriptRuntime::class.java.getDeclaredField("http").apply{isAccessible=true}.set(runtime,http)
        }
        fun run(url:String="https://8.8.8.8/source",method:String="GET",body:String?=null):Pair<Int,String>{
            val f=bridge.javaClass.getDeclaredMethod("doFetch",String::class.java,String::class.java,String::class.java,String::class.java,String::class.java,ScriptRequestScheduler.RequestControl::class.java).apply{isAccessible=true}
            val result=f.invoke(bridge,"round8",url,method,"{}",body,control)
            fun field(n:String)=result.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(result)
            return Pair(field("code") as Int,field("body") as String)
        }
        fun noActiveCalls(){val v=ScriptRuntime::class.java.getDeclaredField("activeCalls").apply{isAccessible=true}.get(runtime) as Map<*,*>;assertTrue(v.isEmpty());assertNull(cancellation)}
    }
    private fun response(r:Request,code:Int=200,text:String="ok",location:String?=null):Response=Response.Builder()
        .request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(text.toResponseBody())
        .apply{if(location!=null)header("Location",location)}.build()

    @Test fun privateRedirectIsRejectedWithoutSecondRequest(){
        val f=Fixture{r,_->response(r,302,"redirect","http://127.0.0.1/private")}
        val result=f.run();assertEquals(403,result.first);assertEquals(1,f.requests.size);f.noActiveCalls()
    }
    @Test fun crossOriginPost307ReturnsWithoutProxyOrCredentialReplay(){
        val f=Fixture{r,_->response(r,307,"redirect","https://1.1.1.1/other")}
        val result=f.run(method="POST",body="private-fixture");assertEquals(307,result.first);assertEquals(1,f.requests.size);f.noActiveCalls()
    }
    @Test fun networkFailureReturnsDirectFailureWithExactlyOneRequest(){
        val f=Fixture{_,_->throw IOException("controlled direct failure")}
        val result=f.run();assertEquals(0,result.first);assertEquals(1,f.requests.size);f.noActiveCalls()
    }
    @Test fun cancellationDuringFailedDirectPreventsFurtherCalls(){
        val f=Fixture{_, fixture->fixture.cancelled.set(true);fixture.cancellation!!.invoke();throw IOException("cancelled fixture")}
        assertEquals(0,f.run().first);assertEquals(1,f.requests.size);assertTrue(f.cancelled.get());f.noActiveCalls()
    }
    @Test fun cancelledBeforeAdmissionDoesNotCreateAnyCall(){
        val f=Fixture{r,_->response(r)};f.cancelled.set(true);assertEquals(0,f.run().first);assertEquals(0,f.requests.size);f.noActiveCalls()
    }
    @Test fun uppercasePrivateAndMalformedUrlsNeverReachEitherTransport(){
        val f=Fixture{r,_->response(r)}
        listOf("HTTP://127.0.0.1/a","ftp://8.8.8.8/a","https://user@8.8.8.8/a","https://8.8.8.8:70000/a").forEach{assertEquals(0,f.run(it).first)}
        assertEquals(0,f.requests.size);f.noActiveCalls()
    }
    @Test fun sameOriginRedirectRunsThroughValidatedSecondCall(){
        val f=Fixture{r,_->if(r.url.encodedPath=="/source")response(r,302,"","/next")else response(r,200,"next body")}
        val result=f.run();assertEquals(200,result.first);assertEquals("next body",result.second);assertEquals(2,f.requests.size);assertEquals(2,f.clears);f.noActiveCalls()
    }
}
