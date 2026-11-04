package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScriptRuntimeWebViewIsolationTest {
    @Test
    fun scriptRuntimeAndProbeRuntimeUseIsolatedBoundedDispatchers() {
        val context = RuntimeEnvironment.getApplication() as Context
        val runtime = ScriptRuntime(context)
        val probe = runtime.forkForProbe()
        fun clientOf(instance: ScriptRuntime) = ScriptRuntime::class.java.getDeclaredField("http")
            .apply { isAccessible = true }.get(instance) as okhttp3.OkHttpClient
        try {
            val regularClient = clientOf(runtime)
            val probeClient = clientOf(probe)
            assertSame(AppHttp.scriptDispatcher, regularClient.dispatcher)
            assertNotSame(AppHttp.shared.dispatcher, regularClient.dispatcher)
            assertNotSame(AppHttp.scriptDispatcher, probeClient.dispatcher)
            assertEquals(1, probeClient.dispatcher.maxRequests)
            assertEquals(1, probeClient.dispatcher.maxRequestsPerHost)
        } finally {
            probe.destroy()
            assertTrue(clientOf(probe).dispatcher.executorService.isShutdown)
            runtime.destroy()
            assertFalse(AppHttp.scriptDispatcher.executorService.isShutdown)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun hiddenRuntimeWebViewBlocksNetworkButKeepsJavaScriptEnabled() {
        val runtime = ScriptRuntime(RuntimeEnvironment.getApplication() as Context)
        try {
            runtime.init()
            shadowOf(Looper.getMainLooper()).idle()

            val field = ScriptRuntime::class.java.getDeclaredField("webView").apply { isAccessible = true }
            val webView = field.get(runtime) as? WebView
            assertNotNull("ScriptRuntime should create its isolated WebView", webView)
            assertTrue(webView!!.settings.blockNetworkLoads)
            assertTrue(webView.settings.javaScriptEnabled)
            assertFalse(webView.settings.allowFileAccess)

            val html = scriptRuntimeBootstrapHtml()
            assertTrue(html.contains(SCRIPT_RUNTIME_CSP))
            assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf("<script>"))
        } finally {
            runtime.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun clientRejectsMainFrameAndFrameNavigationsAndInterceptsHttpResources() {
        val client = ScriptRuntimeWebViewClient()
        val https = webRequest("https://untrusted.example/path")
        val http = webRequest("http://untrusted.example/path")

        assertTrue(client.shouldOverrideUrlLoading(null, https))
        assertTrue(client.shouldOverrideUrlLoading(null, http))
        assertTrue(client.shouldOverrideUrlLoading(null, "https://untrusted.example/form"))
        assertEquals(403, client.shouldInterceptRequest(null, https)?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(null, "http://untrusted.example/image")?.statusCode)
        assertEquals(null, client.shouldInterceptRequest(null, webRequest("data:text/plain,local")))
    }

    @Test
    fun cspBlocksBrowserEgressWhileAllowingRuntimeInlineAndEvalKernel() {
        val requiredDirectives = listOf(
            "default-src 'none'",
            "connect-src 'none'",
            "frame-src 'none'",
            "form-action 'none'",
            "script-src 'unsafe-inline' 'unsafe-eval'"
        )
        requiredDirectives.forEach { assertTrue("CSP missing $it", SCRIPT_RUNTIME_CSP.contains(it)) }

        val runtime = ScriptRuntime(RuntimeEnvironment.getApplication() as Context)
        val bootJs = ScriptRuntime::class.java.getDeclaredMethod("runtimeBootJs").apply {
            isAccessible = true
        }.invoke(runtime) as String
        assertTrue(bootJs.contains("window.__swBridge = window.NativeBridge"))
        assertTrue(bootJs.contains("nativeFetch.apply(window.__swBridge, arguments)"))
        assertTrue(bootJs.contains("self.request=function(url, options, cb)"))
        assertTrue(bootJs.contains("window.__swHandlerStart(CAP)"))
        assertTrue(bootJs.contains("window.__swHandlerReturned(CAP)"))
        assertTrue(bootJs.contains("finally{delete __swResponseHeaders[cid];}"))

        val bridge = ScriptRuntime::class.java.declaredClasses.single { it.simpleName == "Bridge" }
        val nativeFetch = bridge.getDeclaredMethod(
            "nativeFetch", String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java
        )
        assertTrue(nativeFetch.isAnnotationPresent(JavascriptInterface::class.java))
    }

    private fun webRequest(url: String): WebResourceRequest =
        Proxy.newProxyInstance(
            ScriptRuntimeWebViewIsolationTest::class.java.classLoader,
            arrayOf(WebResourceRequest::class.java)
        ) { _, method, _ ->
            if (method.name == "getUrl") Uri.parse(url) else null
        } as WebResourceRequest
}
