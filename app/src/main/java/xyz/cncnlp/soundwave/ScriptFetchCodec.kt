package xyz.cncnlp.soundwave

import org.json.JSONObject
import okhttp3.Headers
import xyz.cncnlp.soundwave.script.ScriptExecutionLimits

/** Stateless wire formatting only. WebView, scheduling, cancellation and policy ownership stay in ScriptRuntime. */
internal object ScriptFetchCodec {
    fun readLimited(stream: java.io.InputStream): String {
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream(16 * 1024)
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > ScriptExecutionLimits.MAX_RESPONSE_BYTES) throw java.io.IOException("脚本响应超过 2MB")
                out.write(buffer, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    fun requestHeaders(headersJson: String?): Map<String, String> = try {
            val hs = JSONObject(headersJson ?: "{}")
            val rawHeaders = linkedMapOf<String, String>()
            val it = hs.keys()
            while (it.hasNext()) {
                val key = it.next()
                rawHeaders[key] = hs.optString(key)
            }
            ScriptNetworkPolicy.filterHeaders(rawHeaders)
        } catch (_: Exception) { emptyMap() }

    fun responseHeaders(headers: Headers): String = JSONObject().apply {
            for ((name, value) in headers) {
                if (ScriptNetworkPolicy.isResponseHeaderAllowed(name)) put(name, value)
            }
        }.toString()

    fun callback(capability: String, code: Int, text: String, headersJson: String): String {
        val safeBody = JSONObject.quote(text)
        val safeCapability = JSONObject.quote(capability)
        val safeHeaders = JSONObject.quote(headersJson)
        return "try{window.__swDispatch($safeCapability,$code,$safeBody,$safeHeaders);}catch(e){}"
    }
}
