package xyz.cncnlp.soundwave

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import okhttp3.ConnectionSpec
import java.security.SecureRandom
import xyz.cncnlp.soundwave.source.direct.fetchAlbumDetailDirect
import xyz.cncnlp.soundwave.source.direct.fetchPlaylistCategoryGroupsDirect
import xyz.cncnlp.soundwave.source.direct.fetchArtistDetailDirect
import xyz.cncnlp.soundwave.source.direct.importOfficialPlaylistDirect
import xyz.cncnlp.soundwave.source.direct.fetchRecommendPlaylistsDirect
import xyz.cncnlp.soundwave.source.direct.fetchDiscoverDirect
import xyz.cncnlp.soundwave.source.direct.fetchHotDirect
import xyz.cncnlp.soundwave.source.direct.fetchHotWordsDirect
import xyz.cncnlp.soundwave.source.direct.fetchRankBoardsDirect
import xyz.cncnlp.soundwave.source.direct.fetchRankDirect
import xyz.cncnlp.soundwave.explicitRankChange
import xyz.cncnlp.soundwave.providerUpdateTime
import xyz.cncnlp.soundwave.source.direct.fetchPlaylistDetailDirect
import xyz.cncnlp.soundwave.source.direct.fetchPlaylistDetailPayloadDirect
import xyz.cncnlp.soundwave.source.direct.ApiUrlPolicyInterceptor
import xyz.cncnlp.soundwave.source.direct.isCleartextApiAllowed
import xyz.cncnlp.soundwave.source.direct.orChain
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import xyz.cncnlp.soundwave.source.FeedListOutcome
import xyz.cncnlp.soundwave.source.CatGroups
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.captureListRequestOutcome
import xyz.cncnlp.soundwave.source.classifyListHttpResponse
import xyz.cncnlp.soundwave.source.decodeListResponseOutcome
import xyz.cncnlp.soundwave.source.legacyListValue
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.download.MediaProbeMetadata

internal suspend fun <T> runDirectFallback(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

// 网络层：搜索/浏览/取链与脚本解耦（SoundWave 云账号已下线，本层无账号接口）。搜索/代理必须有默认日用 HTTPS 服务，
// 不能因进程冷启动或初始化时序把实际网络错误伪装成空搜索结果。
object ApiClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 SoundWave/1.0.304"

    @Volatile
    var baseUrl: String = DEFAULT_SERVER_URL

    // 1.0.656：移除默认服务端证书固定（F-08 撤销）。默认服务端位于 Cloudflare 之后，边缘证书自 2026-10-25 起
    // 轮换为新密钥、签发 CA 也可能变化（LE / Google Trust Services / SSL.com），Cloudflare 不支持固定；
    // 旧的叶子 + 中间证书 pin 会让所有默认服务端请求（登录/保存/更新检查/崩溃上报/首页回落/脚本代理）握手失败。
    // 现仅依赖系统 CA 信任链 + 主机名校验，并保持仅 HTTPS、不跟随 HTTPS→HTTP 降级重定向。

    // 兼容部分机型 TLS 握手被提前关闭：HTTP/1.1 + 兼容 TLS + 登录短连接
    private val client = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        // Credential-bearing managed API requests must not be replayed across redirects.
        .followSslRedirects(false)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .addNetworkInterceptor(ApiUrlPolicyInterceptor())
        .build()
    /**
     * r657：服务端匿名取链（/music/url）专用短超时。它排在脚本之前、且不在 Resolver 的 26 秒总预算内，
     * 旧实现走通用 get()：60 秒 callTimeout × 2 次重试，服务端跨平台兜底一慢就能把播放取链锁住近 2 分钟，
     * 自然播完后的下一首迟迟不出声。这里单次、最多 12 秒，超时直接交给脚本链。
     */
    private val resolveClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    // 规范化：只留 origin，剥 music/app 等
    fun normalizeBase(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        // 常见误填
        val lower = s.lowercase()
        for (suffix in listOf(
            "/music/app", "/music/", "/music", "/app/", "/app",
            "/index.html", "/#", "/api"
        )) {
            if (lower.endsWith(suffix)) {
                s = s.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        // 再剥 path 只留 origin
        return try {
            val u = URL(s)
            val host = (u.host ?: "").lowercase()
            val port = when {
                u.port < 0 -> ""
                u.protocol == "https" && u.port == 443 -> ""
                u.protocol == "http" && u.port == 80 -> ""
                else -> ":${u.port}"
            }
            // 强制小写 host，避免 lp.cncnlp.xyZ 之类大小写导致网关异常
            "${u.protocol.lowercase()}://$host$port"
        } catch (_: Exception) {
            s.trimEnd('/').lowercase().let { x ->
                // 兜底也小写域名段
                val m = Regex("""^(https?://)([^/]+)(.*)""", RegexOption.IGNORE_CASE).find(x)
                if (m != null) m.groupValues[1].lowercase() + m.groupValues[2].lowercase() + m.groupValues[3]
                else x
            }
        }
    }

    fun setBase(url: String) {
        baseUrl = normalizeBase(url).takeIf { it.startsWith("https://") } ?: DEFAULT_SERVER_URL
    }

    
    // Script import is not a managed API. Preserve its previous redirect/HTTP-host
    // contract, response bound and TLS downgrade rejection; API policy is not a media policy.
    private val scriptImportClient = client.newBuilder()
        .followRedirects(true)
        .apply { networkInterceptors().removeAll { it is ApiUrlPolicyInterceptor } }
        .build()

    /** 原始 HTTP GET（脚本链接导入用，不拼 base） */
    fun httpGetRaw(url: String): String {
        return try {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "SoundWave/1.0")
                .build()
            scriptImportClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.byteStream()?.let { ApiResponseReader.readText(it) }.orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun get(pathOrUrl: String, responseLimit: Int = MAX_TEXT_RESPONSE_BYTES): String =
        getScoped(pathOrUrl, responseLimit)

    private fun getScoped(pathOrUrl: String, responseLimit: Int = MAX_TEXT_RESPONSE_BYTES, calls: OfficialCallScope? = null): String {
        return when (val result = getTextOutcome(pathOrUrl, responseLimit, calls)) {
            is FeedListOutcome.Success -> result.data
            FeedListOutcome.Failure -> ""
        }
    }

    /** Same URL construction, GET headers, retry count and delay as the legacy get(). */
    private fun getTextOutcome(
        pathOrUrl: String,
        responseLimit: Int = MAX_TEXT_RESPONSE_BYTES,
        calls: OfficialCallScope? = null
    ): FeedListOutcome<String> {
        val url = abs(pathOrUrl)
        if (url.isEmpty()) return FeedListOutcome.Failure
        repeat(2) {
            calls?.ensureActive()
            val (code, text) = httpText("GET", url, null, responseLimit, calls = calls)
            when (val response = classifyListHttpResponse(code, text)) {
                is FeedListOutcome.Success -> return response
                FeedListOutcome.Failure -> Unit
            }
            try { Thread.sleep(200) } catch (_: Exception) {}
        }
        return FeedListOutcome.Failure
    }

    fun postJson(pathOrUrl: String, body: JSONObject): String {
        val url = abs(pathOrUrl)
        if (url.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("msg", "未设置服务地址（我的页填写 https 根地址并保存）")
                .toString()
        }
        val payload = body.toString()
        // 最多 3 次：OkHttp → OkHttp → HttpURLConnection
        var lastErr = ""
        fun pack(code: Int, text: String): String? {
            if (text.isEmpty()) return null
            val trim = text.trimStart()
            if (trim.startsWith("{") || trim.startsWith("[")) return text
            // HTML / 502 Bad Gateway 等
            val plain = trim.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            val hint = when {
                code in listOf(502, 503, 504) || plain.contains("Bad Gateway", true) ->
                    "服务网关错误($code)，请检查网络后重试"
                code == 403 || plain.contains("Cloudflare", true) ->
                    "被拦截($code)，请换网络或稍后重试"
                else -> "响应异常($code): $plain"
            }
            return JSONObject().put("ok", false).put("msg", "$hint · base=$baseUrl").toString()
        }
        repeat(2) { attempt ->
            val (code, text) = httpText("POST", url, payload)
            pack(code, text)?.let { return it }
            lastErr = if (code < 0) "连接失败" else "HTTP $code 空响应"
            try { Thread.sleep(350L * (attempt + 1)) } catch (_: Exception) {}
        }
        val (code2, text2) = jdkPost(url, payload)
        pack(code2, text2)?.let { return it }
        return JSONObject()
            .put("ok", false)
            .put("msg", "网络异常: ${lastErr.ifEmpty { "无响应" }} code=$code2 base=$baseUrl")
            .toString()
    }

    fun nativeHttp(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): Pair<Int, String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return -1 to ""
        return try {
            val b = Request.Builder().url(url)
                .header(
                    "User-Agent",
                    headers["User-Agent"] ?: headers["user-agent"] ?: UA
                )
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) b.header(k, v) }
            val m = method.uppercase()
            if (body != null && m != "GET" && m != "HEAD") {
                val ct = headers["Content-Type"] ?: headers["content-type"] ?: "application/json; charset=utf-8"
                b.method(m, body.toRequestBody(ct.toMediaType()))
            } else {
                b.method(m, null)
            }
            client.newCall(b.build()).execute().use { resp ->
                // nativeHttp 同样属于文本响应，必须走统一上限，不能 body.string() 无界读入内存。
                resp.code to (resp.body?.byteStream()?.let { ApiResponseReader.readText(it) } ?: "")
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }
    }

    // 所有文本响应设硬上限：错误代理或无限响应不能在后台线程把 StringBuilder 撑到 OOM。
    private const val MAX_TEXT_RESPONSE_BYTES = ApiResponseReader.DEFAULT_MAX_BYTES
    // 歌单详情（上千首）是合法 JSON，比普通 API 大；仍有限制，避免错误流撑爆内存。
    private const val MAX_PLAYLIST_DETAIL_BYTES = 8 * 1024 * 1024

    private fun httpText(
        method: String,
        url: String,
        body: String?,
        responseLimit: Int = MAX_TEXT_RESPONSE_BYTES,
        http: OkHttpClient = client,
        calls: OfficialCallScope? = null
    ): Pair<Int, String> {
        if (!isCleartextApiAllowed(url)) return -1 to ""
        return try {
            httpTextOrThrow(method, url, body, responseLimit, http, calls)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            -1 to (e.javaClass.simpleName + ":" + (e.message ?: "")).take(180)
        }
    }

    /** Transport only: the catalog boundary retains error kinds before legacy String conversion. */
    private fun httpTextOrThrow(
        method: String, url: String, body: String?, responseLimit: Int,
        http: OkHttpClient, calls: OfficialCallScope?
    ): Pair<Int, String> {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Connection", "keep-alive")
        if (baseUrl.isNotEmpty()) {
            b.header("Origin", baseUrl)
            b.header("Referer", "$baseUrl/")
        }
        if (method == "POST") {
            b.post((body ?: "").toRequestBody(JSON_MT))
            b.header("Content-Type", "application/json; charset=utf-8")
        }
        val consume: (okhttp3.Response) -> Pair<Int, String> = { resp ->
            // OkHttp 会根据透明 gzip 支持自动注入并解压 Content-Encoding；
            // 手工设置 Accept-Encoding 会关闭透明解压，必须在这里明确处理 gzip。
            // 某些网关会实际返回 br/deflate 却错误标 gzip，避免再解一次后把搜索吞成空列表。
            val rawBytes = resp.body?.byteStream()?.let { ApiResponseReader.readBytes(it, responseLimit) } ?: ByteArray(0)
            val enc = resp.header("Content-Encoding")?.lowercase().orEmpty()
            val raw = when {
                enc.contains("gzip") && rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte() ->
                    ApiResponseReader.readText(GZIPInputStream(rawBytes.inputStream()), responseLimit)
                else -> rawBytes.toString(Charsets.UTF_8)
            }
            resp.code to raw
        }
        val call = http.newCall(b.build())
        return if (calls == null) call.execute().use(consume) else calls.execute(call, consume)
    }

    private fun systemSslFactory(): SSLSocketFactory {
        return try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, SecureRandom())
            ctx.socketFactory
        } catch (_: Exception) {
            HttpsURLConnection.getDefaultSSLSocketFactory()
        }
    }

    private fun jdkPost(url: String, body: String, maxAttempts: Int = 3, requestId: String = ""): Pair<Int, String> {
        if (!isCleartextApiAllowed(url)) return -1 to "Blocked API URL by transport policy"
        var lastErr = ""
        // 普通接口保留重试，避免一次点击内多层重试打出 502/频控。
        repeat(maxAttempts.coerceIn(1, 3)) { attempt ->
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 18000
                    readTimeout = 30000
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    useCaches = false
                    instanceFollowRedirects = false // Never replay account/sync request bodies across redirects.
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                    setRequestProperty("Connection", "close")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (requestId.isNotBlank()) setRequestProperty("X-SW-Request-Id", requestId)
                    if (baseUrl.isNotEmpty()) {
                        setRequestProperty("Origin", baseUrl)
                        setRequestProperty("Referer", "$baseUrl/")
                    }
                    if (this is HttpsURLConnection) {
                        sslSocketFactory = systemSslFactory()
                        hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    }
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body); it.flush() }
                val code = conn.responseCode
                val stream = try {
                    if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                } catch (_: Exception) {
                    conn.errorStream
                }
                val text = stream?.let { ApiResponseReader.readText(it) } ?: ""
                try { conn.disconnect() } catch (_: Exception) {}
                if (text.isNotEmpty() || code > 0) return code to text
                lastErr = "HTTP $code empty"
            } catch (e: Exception) {
                lastErr = (e.javaClass.simpleName + ":" + (e.message ?: "")).take(180)
                try { Thread.sleep(280L * (attempt + 1)) } catch (_: Exception) {}
            }
        }
        return -1 to lastErr
    }

    private fun abs(pathOrUrl: String): String {
        val p = pathOrUrl.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        if (baseUrl.isEmpty()) return ""
        return if (p.startsWith("/")) baseUrl + p else "$baseUrl/$p"
    }

    /** 服务端能力声明；失败/缺失返回 null，调用方保持默认保守声明。 */
    fun fetchCapabilities(): Map<String, PlatformCapability>? {
        return try {
            val raw = get("/music/capabilities")
            if (raw.isBlank()) return null
            val root = JSONObject(raw)
            val arr = root.optJSONArray("platforms") ?: root.optJSONArray("list") ?: return null
            val out = linkedMapOf<String, PlatformCapability>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = orChain(o, "source", "id")
                if (s.isBlank()) continue
                out[s] = PlatformCapability.fromJson(o).copy(source = s)
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSong(o: JSONObject, fallbackSrc: String = ""): Song = Song.fromJson(o, fallbackSrc)

    private fun parseSongList(arr: JSONArray?, fallbackSrc: String): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            try {
                val o = arr.optJSONObject(i) ?: continue
                val s = parseSong(o, fallbackSrc)
                if (s.id.isNotEmpty() && s.name.isNotEmpty()) out.add(s)
            } catch (_: Exception) {}
        }
        return out
    }

    /** Rank-context parser only: generic Song JSON intentionally does not persist chart movement. */
    private fun parseRankSongList(arr: JSONArray?, fallbackSrc: String): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            try {
                val o = arr.optJSONObject(i) ?: continue
                val song = parseSong(o, fallbackSrc).copy(rankChange = explicitRankChange(o))
                if (song.id.isNotEmpty() && song.name.isNotEmpty()) out.add(song)
            } catch (_: Exception) { }
        }
        return out
    }

    fun search(keyword: String, source: String, type: String = "song"): List<Song> =
        searchScoped(keyword, source, type)

    internal suspend fun searchCancellable(keyword: String, source: String, type: String = "song"): List<Song> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            withOfficialCallScope { searchScoped(keyword, source, type, it) }
        }

    private fun searchScoped(keyword: String, source: String, type: String, calls: OfficialCallScope? = null): List<Song> {
        val s = java.net.URLEncoder.encode(keyword, "UTF-8")
        val t = type.ifBlank { "song" }
        var raw = getScoped("/music/search?keyword=$s&source=$source&type=$t", calls = calls)
        if (raw.isEmpty()) raw = getScoped("/music/search?keyword=$s&source=$source", calls = calls)
        if (raw.isEmpty()) return emptyList()
        val d = JSONObject(raw)
        // 兼容旧服务、当前服务以及代理/聚合层常见的嵌套外壳。
        val data = d.optJSONObject("data") ?: d.optJSONObject("result")
        val list = parseSongList(
            d.optJSONArray("list")
                ?: d.optJSONArray("songs")
                ?: d.optJSONArray("result")
                ?: d.optJSONArray("records")
                ?: d.optJSONArray("data")
                ?: data?.optJSONArray("list")
                ?: data?.optJSONArray("songs")
                ?: data?.optJSONArray("result")
                ?: data?.optJSONArray("records")
                ?: data?.optJSONArray("data"),
            source
        )
        val q = keyword.trim().lowercase()
        return when (t) {
            "artist", "singer" -> {
                val hit = list.filter { it.singer.lowercase().contains(q) }
                if (hit.isNotEmpty()) hit.sortedBy { if (it.singer.lowercase() == q) 0 else 1 } else list
            }
            "album" -> {
                val hit = list.filter { it.album.lowercase().contains(q) }
                if (hit.isNotEmpty()) hit.sortedBy { if (it.album.lowercase() == q) 0 else 1 } else list
            }
            else -> list
        }
    }

    /**
     * 首页发现位。批16 直连优先：五平台发现卡片客户端直连（fetchDiscoverDirect，服务端
     * /music/discover :3041 的 1:1 移植见 FeedDirectFetch.kt），失败/空结果回落原服务端路径
     * discoverServer（原实现原样保留）——首页零回归。改为 suspend：调用方
     * （HomeFeedController:147/:201）均已在其协程内，调用点零改动。
     */
    suspend fun discover(source: String = "wy", limit: Int = 18): List<DiscoverCard> =
        discoverOutcome(source, limit).legacyListValue()

    /** Typed outcome used by the home feed; other callers keep the legacy List contract. */
    internal suspend fun discoverOutcome(
        source: String = "wy",
        limit: Int = 18
    ): FeedListOutcome<List<DiscoverCard>> {
        runDirectFallback { fetchDiscoverDirect(source.ifBlank { "wy" }, limit) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return FeedListOutcome.Success(it) }
        return discoverServerOutcome(source, limit)
    }

    /** The existing /music/discover server fallback with valid-empty status retained. */
    internal fun discoverServerOutcome(source: String = "wy", limit: Int = 18): FeedListOutcome<List<DiscoverCard>> {
        return captureListRequestOutcome {
            val requested = source.ifBlank { "wy" }
            val encoded = java.net.URLEncoder.encode(requested, "UTF-8")
            val response = getTextOutcome("/music/discover?source=$encoded&src=$encoded&limit=$limit")
            decodeListResponseOutcome(response) { raw ->
                parseDiscoverServerPayloadOutcome(raw, requested)
            }
        }
    }

    internal fun parseDiscoverServerPayloadOutcome(
        raw: String,
        requested: String
    ): FeedListOutcome<List<DiscoverCard>> {
        return try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root.optJSONObject("result")
            if (hasExplicitApiFailure(root) || (data != null && hasExplicitApiFailure(data))) {
                return FeedListOutcome.Failure
            }
            val arr = root.optJSONArray("list") ?: data?.optJSONArray("list") ?: data?.optJSONArray("result")
                ?: return FeedListOutcome.Failure
            val out = ArrayList<DiscoverCard>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                val actualSource = o.optString("src").ifBlank { o.optString("source") }
                // 上游没有明确来源，或来源与请求平台不一致时不展示；禁止默认伪标为网易云。
                if (id.isBlank() || name.isBlank() || !actualSource.equals(requested, true)) continue
                out += DiscoverCard(
                    id = id,
                    name = decodeProviderText(name),
                    cover = normalizeCoverUrl(o.optString("cover")),
                    artist = decodeProviderText(o.optString("artist")),
                    kind = o.optString("kind"),
                    title = decodeProviderText(o.optString("title")),
                    album = decodeProviderText(o.optString("album")),
                    source = actualSource,
                    songId = o.optString("songId")
                )
            }
            FeedListOutcome.Success(out)
        } catch (_: Exception) {
            FeedListOutcome.Failure
        }
    }

    /**
     * 专辑详情只接受服务端实际返回的曲目，不拿热门歌曲补造。
     * 批18 直连优先：wy/tx 专辑详情客户端直连（fetchAlbumDetailDirect，服务端 /music/album
     * :3091-3102 分派的 qq_album_detail:2360 / netease_album_detail:2387 的 1:1 移植见
     * AlbumDirectFetch.kt），失败/空曲目/未知平台（kw/kg/mg 服务端本就 404）回落原服务端
     * 多路径探测 albumDetailServer（原实现原样保留），行为零回归。改为 suspend：调用方
     * MainActivity:1618/:1795 已在 withContext(Dispatchers.IO) 协程内，调用点零改动。
     */
    suspend fun albumDetail(id: String, source: String = "wy"): Playlist? {
        if (id.isBlank()) return null
        runDirectFallback { fetchAlbumDetailDirect(source, id) }?.let { return it }
        return albumDetailServer(id, source)
    }

    /**
     * 批18 前的原服务端实现（/music/album 四路径探测），原样保留作回落路径。
     * 不同后端对参数与 JSON 外层的兼容差异在这里收敛，避免客户端误报“暂无曲目”。
     */
    private fun albumDetailServer(id: String, source: String = "wy"): Playlist? {
        return try {
            if (id.isBlank()) return null
            val q = java.net.URLEncoder.encode(id, "UTF-8")
            val src = java.net.URLEncoder.encode(source.ifBlank { "wy" }, "UTF-8")
            val paths = listOf(
                "/music/album?id=$q&source=$src",
                "/music/album?id=$q&src=$src",
                "/music/album?albumId=$q&source=$src"
            )
            for (path in paths) {
                val raw = get(path)
                if (raw.isBlank()) continue
                val d = JSONObject(raw)
                val outerData = d.optJSONObject("data")
                val payload = outerData?.optJSONObject("album") ?: outerData ?: d.optJSONObject("album") ?: d
                val realSrc = payload.optString("source").ifBlank { payload.optString("src") }.ifBlank { source.ifBlank { "wy" } }
                val arr = payload.optJSONArray("songs") ?: payload.optJSONArray("list") ?: payload.optJSONArray("tracks")
                    ?: outerData?.optJSONArray("songs") ?: outerData?.optJSONArray("list") ?: outerData?.optJSONArray("tracks")
                    ?: d.optJSONArray("songs") ?: d.optJSONArray("list") ?: d.optJSONArray("tracks")
                val songs = parseSongList(arr, realSrc)
                if (songs.isEmpty()) continue
                val realId = payload.optString("id").ifBlank { d.optString("id") }.ifBlank { id }
                return Playlist(
                    id = realId,
                    name = payload.optString("name").ifBlank { d.optString("name") }.ifBlank { "专辑" },
                    cover = normalizeCoverUrl(payload.optString("cover").ifBlank { payload.optString("pic") }.ifBlank { d.optString("cover") }),
                    songs = songs.toMutableList(),
                    source = realSrc,
                    count = payload.optInt("count", payload.optInt("trackCount", songs.size)).coerceAtLeast(songs.size),
                    remoteId = realId
                )
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 歌手详情（批26）：五平台官方歌手接口客户端直连优先（fetchArtistDetailDirect 覆盖
     * wy/tx/kg/kw/mg，不依赖登录态），直连失败/空结果回落服务端 /music/singer 聚合腿，
     * 返回 {name, cover, brief, list}。客户端详情页复用 plDetail/LibraryPage 渲染；id 用
     * "singer_{src}_{artistId}" 前缀避免与歌单/榜单 id 冲突。singerName 为搜索结果的歌手名
     * 提示（酷我/咪咕等平台仅靠 id 无法反查名字，需 name 兜底匹配）。
     */
    suspend fun singerSongs(artistId: String, singerName: String, source: String = "wy"): Playlist? {
        if (artistId.isBlank()) return null
        val src = source.ifBlank { "wy" }
        fetchArtistDetailDirect(src, artistId, singerName)?.let { return it }
        // 五平台直连（fetchArtistDetailDirect 覆盖 wy/tx/kg/kw/mg）失败/空结果时的服务端回落，五平台同口径，非 KW/MG 专属。
        return try {
            val q = java.net.URLEncoder.encode(artistId, "UTF-8")
            val nm = java.net.URLEncoder.encode(singerName, "UTF-8")
            val s = java.net.URLEncoder.encode(src, "UTF-8")
            val raw = get("/music/singer?id=$q&name=$nm&source=$s")
            if (raw.isBlank()) return null
            val d = JSONObject(raw)
            val arr = d.optJSONArray("list")
            val songs = parseSongList(arr, src)
            if (songs.isEmpty()) return null
            Playlist(
                id = "singer_${src}_${artistId}",
                name = decodeProviderText(d.optString("name")).ifBlank { singerName },
                cover = normalizeCoverUrl(d.optString("cover")),
                songs = songs.toMutableList(),
                local = false,
                source = src,
                author = decodeProviderText(d.optString("name")).ifBlank { singerName },
                description = decodeProviderText(d.optString("brief")),
                count = songs.size,
                remoteId = artistId
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 首页热门歌曲。批16 直连优先：五平台热门客户端直连（fetchHotDirect，服务端 /music/hot
     * :2945 的 1:1 移植见 FeedDirectFetch.kt；该接口返回歌曲列表），失败/空结果回落原服务端
     * 路径 hotServer（原实现原样保留）——首页零回归。改为 suspend：调用方
     * （HomeFeedController:198 / MainActivity:1727）均已在其协程内，调用点零改动。
     */
    suspend fun hot(source: String): List<Song> {
        runDirectFallback { fetchHotDirect(source) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return hotServer(source)
    }

    /** 批16 前的原服务端实现（/music/hot 聚合），原样保留作回落路径。 */
    internal fun hotServer(source: String): List<Song> {
        return try {
            val api = if (source in Platforms.ALL) source else "all"
            var raw = get("/music/hot?source=$api")
            if (raw.isEmpty()) raw = get("/music/hot?src=$api")
            if (raw.isEmpty()) return emptyList()
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root.optJSONObject("result")
            parseSongList(
                root.optJSONArray("list")
                    ?: root.optJSONArray("songs")
                    ?: data?.optJSONArray("list")
                    ?: data?.optJSONArray("songs"),
                api
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 首页热搜词。批16 直连优先：五平台热搜词客户端直连（fetchHotWordsDirect，服务端
     * /music/hotword :2929 + hot_search_words :1104 的 1:1 移植见 FeedDirectFetch.kt），
     * 失败/空结果回落原服务端路径 hotKeywordsServer（原实现原样保留）——首页零回归。
     * 改为 suspend：调用方（HomeFeedController:202）已在其协程内，调用点零改动。
     */
    suspend fun hotKeywords(source: String, limit: Int = 9): List<String> =
        hotKeywordsOutcome(source, limit).legacyListValue()

    /** Typed outcome used by the home feed; other callers keep the legacy List contract. */
    internal suspend fun hotKeywordsOutcome(source: String, limit: Int = 9): FeedListOutcome<List<String>> {
        runDirectFallback { fetchHotWordsDirect(source.ifBlank { "wy" }, limit) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return FeedListOutcome.Success(it) }
        return hotKeywordsServerOutcome(source, limit)
    }

    /** The existing /music/hotword server fallback with valid-empty status retained. */
    internal fun hotKeywordsServerOutcome(source: String, limit: Int = 9): FeedListOutcome<List<String>> {
        return captureListRequestOutcome {
            val api = if (source in Platforms.ALL) source else "wy"
            val response = getTextOutcome("/music/hotword?source=$api&limit=$limit")
            decodeListResponseOutcome(response) { raw ->
                parseHotKeywordsServerPayloadOutcome(raw, limit)
            }
        }
    }

    internal fun parseHotKeywordsServerPayloadOutcome(
        raw: String,
        limit: Int = 9
    ): FeedListOutcome<List<String>> {
        return try {
            val d = JSONObject(raw)
            if (hasExplicitApiFailure(d)) return FeedListOutcome.Failure
            val arr = d.optJSONArray("list") ?: return FeedListOutcome.Failure
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val w = arr.optString(i).trim()
                if (w.isNotEmpty() && w !in out) out.add(w)
            }
            FeedListOutcome.Success(out.take(limit))
        } catch (_: Exception) {
            FeedListOutcome.Failure
        }
    }

    private fun hasExplicitApiFailure(payload: JSONObject): Boolean =
        payload.has("ok") && !payload.optBoolean("ok", true)

    /**
     * 榜单元数据（封面+名称）；详情另调 rankDetail。可按平台过滤。
     * 批15 直连优先：五平台榜单元数据客户端直连（fetchRankBoardsDirect 按平台自派发），
     * 失败/空结果（含 src 空的跨平台聚合）回落原服务端聚合路径 rankServer——首页零回归。
     * 改为 suspend：现有调用方全部已在协程内（HomeFeedController:199 / OfficialRankCard:43 /
     * HomePage:204 / MainActivity:1723 / PlatformSourceAdapter:61 / CloudSourceAdapter:46），
     * 调用点零改动。
     */
    suspend fun rank(source: String = ""): List<RankBoard> {
        runDirectFallback { fetchRankBoardsDirect(source) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return rankServer(source)
    }

    /** 批15 前的原服务端实现（/music/rank 聚合），原样保留作回落路径。 */
    private fun rankServer(source: String): List<RankBoard> {
        return try {
            val raw = get(if (source.isNotEmpty()) "/music/rank?src=$source" else "/music/rank")
            if (raw.isEmpty()) return emptyList()
            val d = JSONObject(raw)
            val arr = d.optJSONArray("list") ?: d.optJSONObject("data")?.optJSONArray("list") ?: d.optJSONArray("ranks") ?: return emptyList()
            val out = ArrayList<RankBoard>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = orChain(o, "name", "title")
                val src = orChain(o, "src", "source").ifEmpty { source }
                val id = orChain(o, "id", "bangId", "topid")
                if (name.isBlank() || id.isBlank() || src.isBlank()) continue
                val cover = normalizeCoverUrl(orChain(o, "cover", "pic"))
                val songs = when {
                    o.has("list") && o.opt("list") is JSONArray -> parseRankSongList(o.optJSONArray("list"), src)
                    o.has("songs") -> parseRankSongList(o.optJSONArray("songs"), src)
                    o.has("id") && o.has("name") && (o.has("singer") || o.has("artist")) && !o.has("cover") ->
                        listOf(parseSong(o, src).copy(rankChange = explicitRankChange(o)))
                    else -> emptyList()
                }
                // 元数据榜（有 id/name/cover）也保留，点进去再拉详情
                val cnt = o.optInt("count", o.optInt("trackCount", o.optInt("total", songs.size)))
                if (name.isNotEmpty()) out.add(RankBoard(name = name, source = src, songs = songs, id = id, cover = cover, count = cnt, updatedAt = providerUpdateTime(o)))
            }
            val filtered = if (source.isNotEmpty()) out.filter { it.source.equals(source, true) } else out
            filtered
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseRankDetailPayload(raw: String, src: String): List<Song> {
        return try {
            val d = JSONObject(raw)
            val payload = d.optJSONObject("data") ?: d.optJSONObject("rank") ?: d.optJSONObject("playlist") ?: d
            var list = parseRankSongList(
                payload.optJSONArray("list") ?: payload.optJSONArray("songs") ?: payload.optJSONArray("tracks") ?:
                    d.optJSONArray("list") ?: d.optJSONArray("songs") ?: d.optJSONArray("data"),
                src
            )
            if (list.isEmpty()) {
                d.optJSONObject("data")?.let { data ->
                    list = parseRankSongList(data.optJSONArray("list") ?: data.optJSONArray("songs") ?: data.optJSONArray("tracks"), src)
                }
            }
            list.map { if (it.source.isEmpty()) it.copy(source = src) else it }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 榜单详情。批15 直连优先：五平台榜单详情客户端直连（fetchRankDirect），失败/空结果回落
     * rankDetailServer（原服务端多路径探测，原实现原样保留）。
     */
    suspend fun rankDetail(id: String, source: String = "wy"): List<Song> {
        if (id.isBlank()) return emptyList()
        runDirectFallback { fetchRankDirect(source.ifEmpty { "wy" }, id) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return rankDetailServer(id, source)
    }

    /** 批15 前的原服务端实现（/music/rank?id=... 多路径探测），原样保留作回落路径。 */
    private fun rankDetailServer(id: String, source: String): List<Song> {
        return try {
            if (id.isBlank()) return emptyList()
            val q = java.net.URLEncoder.encode(id, "UTF-8")
            val src = source.ifEmpty { "wy" }
            val s = java.net.URLEncoder.encode(src, "UTF-8")
            // 多路径兼容：酷我/酷狗 id 参数名不一
            val paths = listOf(
                "/music/rank?id=$q&src=$s",
                "/music/rank?id=$q&source=$s",
                "/music/rank?bangId=$q&src=$s",
                "/music/rank?topId=$q&src=$s",
                "/music/rank?topid=$q&src=$s",
                "/music/playlist?action=detail&id=$q&source=$s",
                "/music/playlist?action=detail&id=$q&src=$s"
            )
            for (path in paths) {
                // 非空 HTTP 错误壳/元数据壳不能截断候选路径；必须真正解析出歌曲才算命中。
                val songs = parseRankDetailPayload(get(path), src)
                if (songs.isNotEmpty()) return songs
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun lyric(song: Song): String {
        return try {
            val q = buildString {
                append("/music/lyric?")
                append("name=").append(java.net.URLEncoder.encode(song.name, "UTF-8"))
                append("&singer=").append(java.net.URLEncoder.encode(song.singer, "UTF-8"))
                append("&id=").append(java.net.URLEncoder.encode(song.id, "UTF-8"))
                append("&source=").append(java.net.URLEncoder.encode(song.source, "UTF-8"))
            }
            val raw = get(q)
            if (raw.isEmpty()) return ""
            val d = JSONObject(raw)
            // 优先词级：yrc/qrc；否则普通 lrc
            val direct = sequenceOf("yrc", "qrc", "lyricYrc", "lrc", "lyric")
                .map { d.optString(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (direct.isNotBlank()) direct
            else {
                val y = d.optJSONObject("yrc")?.optString("lyric").orEmpty()
                if (y.isNotBlank()) y else d.optJSONObject("lrc")?.optString("lyric").orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun searchPlaylists(keyword: String, source: String = "", limit: Int = 100): List<Playlist> =
        searchPlaylistsScoped(keyword, source, limit)

    internal suspend fun searchPlaylistsCancellable(keyword: String, source: String, limit: Int): List<Playlist> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            withOfficialCallScope { searchPlaylistsScoped(keyword, source, limit, it) }
        }

    private fun searchPlaylistsScoped(keyword: String, source: String, limit: Int, calls: OfficialCallScope? = null): List<Playlist> {
        return try {
            val q = java.net.URLEncoder.encode(keyword.ifBlank { "推荐" }, "UTF-8")
            val srcQ = if (source.isNotEmpty()) "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}" else ""
            val raw = getScoped("/music/playlist?action=search&keyword=$q$srcQ&limit=$limit", calls = calls)
            return parsePlaylistsPayload(raw, source)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Category-page-only variant that distinguishes a valid empty list from transport or payload failure. */
    internal fun searchPlaylistsCategoryOutcome(keyword: String, source: String, limit: Int): PlaylistSearchOutcome =
        searchPlaylistsCategoryOutcomeScoped(keyword, source, limit)

    internal suspend fun searchPlaylistsCategoryOutcomeCancellable(keyword: String, source: String, limit: Int): PlaylistSearchOutcome =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            withOfficialCallScope { searchPlaylistsCategoryOutcomeScoped(keyword, source, limit, it) }
        }

    private fun searchPlaylistsCategoryOutcomeScoped(keyword: String, source: String, limit: Int, calls: OfficialCallScope? = null): PlaylistSearchOutcome {
        return playlistCatalog(calls).searchCategory(keyword, source, limit).asCategoryOutcome()
    }

    /**
     * 首页甄选歌单（批24b）：官方广场源（tx/wy 有真广场，标题不再清一色"xx推荐"）；
     * 空结果由调用方回落 searchPlaylists（kg/kw/mg 无广场接口走搜索兜底）。
     */
    suspend fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =
        recommendPlaylistsOutcome(source, limit).legacyListValue()

    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */
    internal suspend fun recommendPlaylistsOutcome(
        source: String,
        limit: Int = 50
    ): FeedListOutcome<List<Playlist>> {
        fetchRecommendPlaylistsDirect(source, limit)?.let { return FeedListOutcome.Success(it) }
        return recommendPlaylistsLegacyServerOutcome(source, limit)
    }

    /** Legacy fallback retained until the independent direct/device acceptance gate passes. */
    private fun recommendPlaylistsLegacyServerOutcome(source: String, limit: Int): FeedListOutcome<List<Playlist>> =
        playlistCatalog().recommend(source, limit).asFeedOutcome()

    /** Per-operation explicit transport seam. No auth cache, playback state, or global test override. */
    internal fun playlistCatalog(
        calls: OfficialCallScope? = null,
        http: OkHttpClient = client,
        pause: () -> Unit = { try { Thread.sleep(200) } catch (_: InterruptedException) {} }
    ): PlaylistCatalogApi = PlaylistCatalogApi(PlaylistCatalogTextClient(
        absoluteUrl = ::abs,
        exchange = { url -> httpTextOrThrow("GET", url, null, MAX_TEXT_RESPONSE_BYTES, http, calls) },
        ensureActive = { calls?.ensureActive() },
        pause = pause
    ))

    /** Compatibility adapters keep the existing category/home contracts. */
    private fun parsePlaylistsPayload(raw: String, source: String): List<Playlist> =
        PlaylistCatalogPayloads.legacy(raw, source)

    internal fun parseCategoryPlaylistPayload(raw: String, source: String): PlaylistSearchOutcome {
        val hasPlaylistArray = runCatching {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root.optJSONObject("result")
            listOfNotNull(root, data).any { obj ->
                listOf("list", "playlists", "result", "records").any { obj.optJSONArray(it) != null }
            }
        }.getOrDefault(false)
        return if (!hasPlaylistArray) PlaylistSearchOutcome.Failure
        else PlaylistCatalogPayloads.validated(raw, source, checkNestedFailure = false).asCategoryOutcome()
    }

    internal fun parseRecommendPlaylistsPayloadOutcome(raw: String, source: String): FeedListOutcome<List<Playlist>> =
        PlaylistCatalogPayloads.validated(raw, source, checkNestedFailure = true).asFeedOutcome()

    private const val MAX_DETAIL_SONGS = 1_100

    /**
     * 平台真实歌单详情：兼容 source/src 与 data/list/songs/tracks 外层，绝不回退热门歌曲。
     * 批17 直连优先：五平台歌单详情客户端直连（fetchPlaylistDetailDirect 按平台自派发；wy 复用
     * 批15 wyPlaylistDetail——服务端 /music/rank 与 /music/playlist action=detail 派发同一
     * netease_playlist_detail），失败/空结果（含 wy 非数字 pid、未知平台）回落原服务端多路径探测
     * playlistDetailServer，行为零回归。改为 suspend：现有调用方全部已在协程内（MainActivity
     * :1668/:1724/:1730/:1834 均 scope.launch+withContext(Dispatchers.IO)；importPlaylist:1151 内部
     * 调用点随本方法一并 suspend，其调用点 :3256 已在 withContext(Dispatchers.IO)），调用点零改动。
     */
    suspend fun playlistDetail(id: String, source: String = "wy"): List<Song> {
        runDirectFallback { fetchPlaylistDetailDirect(source, id.removePrefix("reco_").trim()) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return playlistDetailServer(id, source)
    }

    /** Same-provider metadata plus its actual song list for a detail page; no cross-source cover or text fallback. */
    suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist? {
        val rawId = id.removePrefix("reco_").trim()
        if (rawId.isEmpty()) return null
        val direct = runDirectFallback { fetchPlaylistDetailPayloadDirect(source, rawId) }
        val songs = direct?.songs?.takeIf { it.isNotEmpty() } ?: playlistDetailServer(id, source)
        if (songs.isEmpty()) return null
        return seed.copy(
            id = rawId,
            name = direct?.name?.ifBlank { seed.name } ?: seed.name,
            cover = direct?.cover?.ifBlank { seed.cover } ?: seed.cover,
            songs = songs.toMutableList(),
            source = seed.source.ifBlank { source },
            author = direct?.author?.ifBlank { seed.author } ?: seed.author,
            description = direct?.description?.ifBlank { seed.description } ?: seed.description,
            count = maxOf(seed.count, direct?.count ?: 0, songs.size),
            remoteId = seed.remoteId.ifBlank { rawId },
        )
    }

    /** 批17 前的原服务端实现（/music/playlist?action=detail 多路径探测），原样保留作回落路径。 */
    private fun playlistDetailServer(id: String, source: String = "wy"): List<Song> {
        return try {
            val rawId = id.removePrefix("reco_").trim()
            val q = java.net.URLEncoder.encode(rawId, "UTF-8")
            val src = java.net.URLEncoder.encode(source.ifBlank { "wy" }, "UTF-8")
            if (q.isEmpty()) return emptyList()
            val paths = listOf(
                "/music/playlist?action=detail&id=$q&source=$src&limit=$MAX_DETAIL_SONGS",
                "/music/playlist?action=detail&id=$q&src=$src&limit=$MAX_DETAIL_SONGS",
                "/music/playlist?id=$q&source=$src&limit=$MAX_DETAIL_SONGS"
            )
            for (path in paths) {
                val raw = get(path, responseLimit = MAX_PLAYLIST_DETAIL_BYTES)
                if (raw.isBlank()) continue
                val d = JSONObject(raw)
                val outerData = d.optJSONObject("data")
                val candidates = listOfNotNull(
                    outerData?.optJSONObject("playlist"),
                    outerData?.optJSONObject("result"),
                    d.optJSONObject("playlist"),
                    outerData,
                    d
                )
                val list = candidates.asSequence().map { payload ->
                    parseSongList(
                        payload.optJSONArray("list") ?: payload.optJSONArray("songs") ?: payload.optJSONArray("tracks")
                            ?: payload.optJSONArray("songList") ?: payload.optJSONArray("musicList"),
                        payload.optString("source").ifBlank { payload.optString("src") }.ifBlank { source }
                    )
                }.firstOrNull { it.isNotEmpty() }.orEmpty()
                if (list.isNotEmpty()) return list
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Import only a validated entity reference. Unknown URLs/shortlinks never reach the
     * legacy text parser (which can return ok=true/type=lines with missing song IDs).
     * Details keep their existing official-direct + same-provider server fallback policy.
     */
    suspend fun importPlaylist(raw: String): Playlist? {
        // r658c D#3：分享短链先经有界白名单展开器换成可严格解析的长链（展开结果必然可解析，递归一层即止）。
        if (xyz.cncnlp.soundwave.source.direct.parseOnlineImportReference(raw) == null) {
            return xyz.cncnlp.soundwave.source.direct.OnlineImportShortLink.expand(raw)?.let { importPlaylist(it) }
        }
        val reference = xyz.cncnlp.soundwave.source.direct.parseOnlineImportReference(raw) ?: return null
        val detail = xyz.cncnlp.soundwave.source.direct.canonicalizeImportDetail(when (reference.kind) {
            xyz.cncnlp.soundwave.source.direct.OnlineImportKind.ALBUM -> albumDetail(reference.id, reference.source)
            xyz.cncnlp.soundwave.source.direct.OnlineImportKind.PLAYLIST -> {
                importOfficialPlaylistDirect(raw) ?: run {
                    val seed = Playlist(id = reference.id, remoteId = reference.id,
                        source = reference.source, name = "导入歌单", local = false)
                    playlistDetailSnapshot(reference.id, reference.source, seed)
                }
            }
        } ?: return null, reference.source)
        // A nonempty response is not success: every retained row needs an actual same-source ID.
        if (detail.source != reference.source) return null
        val songs = detail.songs.filter { song -> song.id.isNotBlank() && song.source == reference.source }
        if (songs.isEmpty()) return null
        return detail.copy(id = reference.id, remoteId = reference.id, source = reference.source,
            local = false, songs = songs.toMutableList(), count = maxOf(detail.count, songs.size))
    }

    /**
     * 平台歌单分类（两级：大类→子分类）。返回 null 表示服务端无分类。
     * 批33b：服务端 plcats 现返回 {groups:[{name,items}...], list:[...]} 真实两级结构
     * （tx 语种/流派/主题/心情/场景…、kg 主题/语种/风格/年代/心情、kw 主题/心情/场景…）。
     */
    suspend fun plCatGroups(source: String = "wy"): CatGroups? {
        fetchPlaylistCategoryGroupsDirect(source)?.takeIf { it.isNotEmpty() }?.let { return CatGroups(it) }
        return plCatGroupsLegacyServer(source)
    }

    /** Retained legacy fallback pending direct/device acceptance; not a local-only capability. */
    private fun plCatGroupsLegacyServer(source: String): CatGroups? {
        return try {
            val raw = get("/music/plcats?source=${java.net.URLEncoder.encode(source, "UTF-8")}")
            if (raw.isEmpty()) return null
            val o = JSONObject(raw)
            val gArr = o.optJSONArray("groups")
            val found = ArrayList<Pair<String, List<String>>>()
            if (gArr != null) {
                for (i in 0 until gArr.length()) {
                    val g = gArr.optJSONObject(i) ?: continue
                    val gn = g.optString("name").trim()
                    val items = g.optJSONArray("items")
                    val names = ArrayList<String>()
                    if (items != null) for (j in 0 until items.length()) {
                        val c = items.optString(j).trim()
                        if (c.isNotEmpty()) names.add(c)
                    }
                    if (gn.isNotEmpty() && names.isNotEmpty()) found.add(gn to names)
                }
            }
            if (found.isEmpty()) return null
            CatGroups(found)
        } catch (_: Exception) { null }
    }

    fun playableUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        // 外部 LX 脚本有大量历史 HTTP 音频 CDN；必须优先保留脚本实际返回的链接。
        // 线上 /audio 只在用户启用“媒体代理”时才介入：代理不可达/上游拒绝不能再把已出链统一变 502。
        if (u.startsWith("https://")) return u
        if (!u.startsWith("http://")) return ""
        return u
    }

    // HEAD 优先；无总长度时以 bytes=0-0 回退。206 的 Content-Length 仅代表 Range 分段，不代表整首文件。
    fun headContentInfo(url: String): MediaProbeMetadata {
        val client = AppHttp.shared.newBuilder()
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        fun inspect(method: String, rangeHeader: String): MediaProbeMetadata = try {
            val builder = MediaRequestPolicy.apply(Request.Builder().url(url))
            if (rangeHeader.isNotBlank()) builder.header("Range", rangeHeader)
            val request = if (method == "HEAD") builder.head().build() else builder.get().build()
            client.newCall(request).execute().use { response ->
                val contentRange = response.header("Content-Range").orEmpty()
                val size = AudioQualityEvidence.measuredSize(
                    response.code,
                    contentRange,
                    response.header("Content-Length")
                )
                MediaProbeMetadata(
                    sizeBytes = size.first,
                    sizeSource = size.second,
                    contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim(),
                    httpStatus = response.code,
                    method = method,
                    rangeHeader = rangeHeader,
                    contentRange = contentRange
                )
            }
        } catch (_: Exception) {
            MediaProbeMetadata(method = method, rangeHeader = rangeHeader)
        }
        val head = inspect("HEAD", "")
        if (head.sizeBytes > 0L) return head
        val range = inspect("GET", "bytes=0-0")
        return if (range.httpStatus > 0 || range.contentType.isNotBlank()) range else head
    }

    fun headContentLength(url: String): Long = headContentInfo(url).sizeBytes

    // 探测服务是否可达（不需登录）
    fun ping(): String {
        if (baseUrl.isEmpty()) return "未设置地址"
        val raw = get("/music/hot?source=wy")
        return when {
            raw.isEmpty() -> "连不上 $baseUrl"
            raw.contains("list") || raw.contains("ok") -> "OK $baseUrl"
            else -> "异常响应 ${raw.take(40)}"
        }
    }
}
