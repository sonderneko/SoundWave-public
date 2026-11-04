"""1.0.657 reviewed ApiClient change (fix/r657-playback): /music/url anonymous resolution uses a bounded
single-attempt client (resolveClient, 12 s) instead of the generic 60 s x 2 get(). Baseline-comparison guards
project the current file back to its pre-1.0.657 text with this exact, reviewed inverse."""
import json

R657_PLAYBACK_API_PAIRS = json.loads('[["        .protocols(listOf(Protocol.HTTP_1_1))\\n        .build()\\n    /**\\n     * r657：服务端匿名取链（/music/url）专用短超时。它排在脚本之前、且不在 Resolver 的 26 秒总预算内，\\n     * 旧实现走通用 get()：60 秒 callTimeout × 2 次重试，服务端跨平台兜底一慢就能把播放取链锁住近 2 分钟，\\n     * 自然播完后的下一首迟迟不出声。这里单次、最多 12 秒，超时直接交给脚本链。\\n     */\\n    private val resolveClient = client.newBuilder()\\n        .connectTimeout(5, TimeUnit.SECONDS)\\n        .readTimeout(10, TimeUnit.SECONDS)\\n        .callTimeout(12, TimeUnit.SECONDS)\\n", "        .protocols(listOf(Protocol.HTTP_1_1))\\n"], ["\\n    private fun httpText(\\n        method: String,\\n        url: String,\\n        body: String?,\\n        responseLimit: Int = MAX_TEXT_RESPONSE_BYTES,\\n        http: OkHttpClient = client\\n    ): Pair<Int, String> {\\n", "\\n    private fun httpText(method: String, url: String, body: String?, responseLimit: Int = MAX_TEXT_RESPONSE_BYTES): Pair<Int, String> {\\n"], ["            }\\n            http.newCall(b.build()).execute().use { resp ->\\n", "            }\\n            client.newCall(b.build()).execute().use { resp ->\\n"], ["            if (keyword.isNotBlank()) q.append(\\"&keyword=\\").append(java.net.URLEncoder.encode(keyword, \\"UTF-8\\"))\\n            val url = abs(q.toString())\\n            if (url.isEmpty()) return \\"\\"\\n            val (code, raw) = httpText(\\"GET\\", url, null, http = resolveClient)\\n            if (code !in 200..299) return \\"\\"\\n", "            if (keyword.isNotBlank()) q.append(\\"&keyword=\\").append(java.net.URLEncoder.encode(keyword, \\"UTF-8\\"))\\n            val raw = get(q.toString())\\n"]]')


def project_r657_playback_api(text):
    for new, old in R657_PLAYBACK_API_PAIRS:
        if new in text:
            text = text.replace(new, old, 1)
    return text
