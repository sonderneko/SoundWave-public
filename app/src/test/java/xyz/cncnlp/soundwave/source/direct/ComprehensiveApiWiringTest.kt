package xyz.cncnlp.soundwave.source.direct

import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ApiClient

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ComprehensiveApiWiringTest {
    private fun client(field:String):OkHttpClient = ApiClient::class.java.getDeclaredField(field).apply{isAccessible=true}.get(ApiClient) as OkHttpClient
    @Test fun managedAccountClientRejectsRedirectReplayAndChecksEveryExchange() {
        // authClient 已随账号系统删除；存续的受管客户端继续全量断言。
        for(field in listOf("client","resolveClient")) {
            val c=client(field)
            assertFalse(field,c.followRedirects)
            assertFalse(field,c.followSslRedirects)
            assertTrue(field,c.networkInterceptors.any {it is ApiUrlPolicyInterceptor})
        }
    }
    @Test fun rawScriptImportPreservesSeparateHostContract() {
        val c=client("scriptImportClient")
        assertTrue(c.followRedirects)
        assertFalse(c.followSslRedirects)
        assertFalse(c.networkInterceptors.any {it is ApiUrlPolicyInterceptor})
    }
    @Test fun jdkFallbackRejectsDisallowedTargetBeforeOpeningConnection() {
        val m=ApiClient::class.java.getDeclaredMethod("jdkPost",String::class.java,String::class.java,Int::class.javaPrimitiveType,String::class.java).apply{isAccessible=true}
        val result=m.invoke(ApiClient,"http://not-an-allowed-api.invalid/a","{}",1,"") as Pair<*,*>
        assertEquals(-1,result.first)
        assertEquals("Blocked API URL by transport policy",result.second)
    }
    @Test fun exchangeInterceptorBlocksInvalidApiHostBeforeProceeding() {
        val calls=AtomicInteger()
        val request=Request.Builder().url("http://blocked-api.invalid/a").build()
        val chain=Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader,arrayOf(Interceptor.Chain::class.java)) {_,m,_->
            when(m.name){"request"->request;"proceed"->{calls.incrementAndGet();error("must not proceed")};else->error(m.name)}
        } as Interceptor.Chain
        try {ApiUrlPolicyInterceptor().intercept(chain);fail("accepted rejected exchange")}
        catch(expected:java.io.IOException){assertEquals(0,calls.get())}
    }
}
