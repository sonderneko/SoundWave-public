package xyz.cncnlp.soundwave

import okhttp3.CertificatePinner
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
import xyz.cncnlp.soundwave.source.direct.fetchDiscoverDirect
import xyz.cncnlp.soundwave.source.direct.fetchHotDirect
import xyz.cncnlp.soundwave.source.direct.fetchHotWordsDirect
import xyz.cncnlp.soundwave.source.direct.fetchRankBoardsDirect
import xyz.cncnlp.soundwave.source.direct.fetchRankDirect
import xyz.cncnlp.soundwave.source.direct.fetchPlaylistDetailDirect
import xyz.cncnlp.soundwave.source.direct.fetchPlaylistDetailPayloadDirect
import xyz.cncnlp.soundwave.source.direct.isCleartextApiAllowed
import xyz.cncnlp.soundwave.source.direct.orChain
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import xyz.cncnlp.soundwave.source.FeedListOutcome
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.captureListRequestOutcome
import xyz.cncnlp.soundwave.source.classifyListHttpResponse
import xyz.cncnlp.soundwave.source.decodeListResponseOutcome
import xyz.cncnlp.soundwave.source.legacyListValue
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.download.MediaProbeMetadata

// 网络层：登录与脚本解耦。搜索/代理必须有默认日用 HTTPS 服务，
// 不能因进程冷启动或初始化时序把实际网络错误伪装成空搜索结果。
object ApiClient {
    @Volatile private var lastAuthAt = 0L
    @Volatile private var lastAuthUser = ""
    @Volatile private var lastAuthBundle: LocalStore.UserBundle? = null

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 SoundWave/1.0.304"

    @Volatile
    var baseUrl: String = DEFAULT_SERVER_URL

    // F-08 证书固定：仅默认服务端生效。
    // - pin 按 host（lp.cncnlp.xyz）精确匹配，OkHttp 只对命中 host 做 pin 校验：
    //   自填服务器（F-11）、平台/脚本 CDN 等其他域一律不受影响，运行时切换 baseUrl 无需重建 client。
    // - 轮换策略：pin1 = 当前叶子证书 SPKI；pin2 = 签发中间证书 SPKI（备用预留位，
    //   叶子证书周期性轮换后仍可通过校验）。服务端/中间证书轮换时需同步更新此处，
    //   否则默认服务端 HTTPS 将握手失败。
    private val defaultServerPinner = CertificatePinner.Builder()
        .add("lp.cncnlp.xyz", "sha256/inLU3HLc/R1k9//4H9zclblmkXWe5jyA3C6IhBnDsWY=")
        .add("lp.cncnlp.xyz", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
        .build()

    // 仅默认服务器（或默认态空 baseUrl）启用 pinning；自填服务器跳过。
    private fun pinningEnabledForCurrentBase(): Boolean {
        val base = baseUrl.trim()
        if (base.isEmpty()) return true
        return base.trimEnd('/').equals(DEFAULT_SERVER_URL, ignoreCase = true)
    }

    // 兼容部分机型 TLS 握手被提前关闭：HTTP/1.1 + 兼容 TLS + 登录短连接
    private val client = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        // Never permit an HTTPS request to downgrade to HTTP.
        .followSslRedirects(false)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .apply { if (pinningEnabledForCurrentBase()) certificatePinner(defaultServerPinner) }
        .build()
    private val authClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .build()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    private val authLock = Any()

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

    
    /** 原始 HTTP GET（脚本链接导入用，不拼 base） */
    fun httpGetRaw(url: String): String {
        return try {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "SoundWave/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.byteStream()?.let { readLimited(it) }.orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun get(pathOrUrl: String, responseLimit: Int = MAX_TEXT_RESPONSE_BYTES): String {
        return when (val result = getTextOutcome(pathOrUrl, responseLimit)) {
            is FeedListOutcome.Success -> result.data
            FeedListOutcome.Failure -> ""
        }
    }

    /** Same URL construction, GET headers, retry count and delay as the legacy get(). */
    private fun getTextOutcome(
        pathOrUrl: String,
        responseLimit: Int = MAX_TEXT_RESPONSE_BYTES
    ): FeedListOutcome<String> {
        val url = abs(pathOrUrl)
        if (url.isEmpty()) return FeedListOutcome.Failure
        repeat(2) {
            val (code, text) = httpText("GET", url, null, responseLimit)
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
                resp.code to (resp.body?.byteStream()?.let { readLimited(it) } ?: "")
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }
    }

    // 所有文本响应设硬上限：错误代理或无限响应不能在后台线程把 StringBuilder 撑到 OOM。
    private const val MAX_TEXT_RESPONSE_BYTES = 2 * 1024 * 1024
    // 歌单详情（上千首）是合法 JSON，比普通 API 大；仍有限制，避免错误流撑爆内存。
    private const val MAX_PLAYLIST_DETAIL_BYTES = 8 * 1024 * 1024

    private fun readLimitedBytes(stream: java.io.InputStream, limit: Int = MAX_TEXT_RESPONSE_BYTES): ByteArray {
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(16 * 1024, limit))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw java.io.IOException("响应过大（超过 ${limit / 1024}KB）")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun readLimited(stream: java.io.InputStream, limit: Int = MAX_TEXT_RESPONSE_BYTES): String =
        readLimitedBytes(stream, limit).toString(Charsets.UTF_8)

    private fun httpText(method: String, url: String, body: String?, responseLimit: Int = MAX_TEXT_RESPONSE_BYTES): Pair<Int, String> {
        // 明文收紧（HttpPolicy）：托管 API 通道（get/postJson 均汇聚于此）一律 https，
        // 仅白名单主机（kg mobilecdn）可走 http；媒体播放 URL 走 playableUrl/headContentLength，不受此限。
        if (!isCleartextApiAllowed(url)) return -1 to ""
        return try {
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
            client.newCall(b.build()).execute().use { resp ->
                // OkHttp 会根据透明 gzip 支持自动注入并解压 Content-Encoding；
                // 手工设置 Accept-Encoding 会关闭透明解压，必须在这里明确处理 gzip。
                // 某些网关会实际返回 br/deflate 却错误标 gzip，避免再解一次后把搜索吞成空列表。
                val rawBytes = resp.body?.byteStream()?.let { readLimitedBytes(it, responseLimit) } ?: ByteArray(0)
                val enc = resp.header("Content-Encoding")?.lowercase().orEmpty()
                val raw = when {
                    enc.contains("gzip") && rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte() ->
                        readLimited(GZIPInputStream(rawBytes.inputStream()), responseLimit)
                    else -> rawBytes.toString(Charsets.UTF_8)
                }
                resp.code to raw
            }
        } catch (e: Exception) {
            -1 to (e.javaClass.simpleName + ":" + (e.message ?: "")).take(180)
        }
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
        var lastErr = ""
        // 普通接口保留重试；登录接口由 postJsonAuth 统一退避，避免一次点击内多层重试打出 502/频控。
        repeat(maxAttempts.coerceIn(1, 3)) { attempt ->
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 18000
                    readTimeout = 30000
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    useCaches = false
                    instanceFollowRedirects = true
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
                val text = stream?.let { readLimited(it) } ?: ""
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

    fun search(keyword: String, source: String, type: String = "song"): List<Song> {
        val s = java.net.URLEncoder.encode(keyword, "UTF-8")
        val t = type.ifBlank { "song" }
        var raw = get("/music/search?keyword=$s&source=$source&type=$t")
        if (raw.isEmpty()) raw = get("/music/search?keyword=$s&source=$source")
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
        runCatching { fetchDiscoverDirect(source.ifBlank { "wy" }, limit) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return FeedListOutcome.Success(it) }
        return discoverServerOutcome(source, limit)
    }

    /** The existing /music/discover server fallback with valid-empty status retained. */
    private fun discoverServerOutcome(source: String = "wy", limit: Int = 18): FeedListOutcome<List<DiscoverCard>> {
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
        runCatching { fetchAlbumDetailDirect(source, id) }.getOrNull()?.let { return it }
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
                "/music/album?albumId=$q&source=$src",
                "/music/album/detail?id=$q&source=$src"
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
     * 歌手详情（批26）：服务端 /music/singer 聚合五平台官方歌手歌曲接口（不依赖登录态），
     * 返回 {name, cover, brief, list}。客户端详情页复用 plDetail/LibraryPage 渲染；id 用
     * "singer_{src}_{artistId}" 前缀避免与歌单/榜单 id 冲突。singerName 为搜索结果的歌手名
     * 提示（酷我/咪咕等平台仅靠 id 无法反查名字，需 name 兜底匹配）。
     */
    suspend fun singerSongs(artistId: String, singerName: String, source: String = "wy"): Playlist? {
        if (artistId.isBlank()) return null
        val src = source.ifBlank { "wy" }
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
        runCatching { fetchHotDirect(source) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return hotServer(source)
    }

    /** 批16 前的原服务端实现（/music/hot 聚合），原样保留作回落路径。 */
    private fun hotServer(source: String): List<Song> {
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
        runCatching { fetchHotWordsDirect(source.ifBlank { "wy" }, limit) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return FeedListOutcome.Success(it) }
        return hotKeywordsServerOutcome(source, limit)
    }

    /** The existing /music/hotword server fallback with valid-empty status retained. */
    private fun hotKeywordsServerOutcome(source: String, limit: Int = 9): FeedListOutcome<List<String>> {
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
        runCatching { fetchRankBoardsDirect(source) }.getOrNull()
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
                    o.has("list") && o.opt("list") is JSONArray -> parseSongList(o.optJSONArray("list"), src)
                    o.has("songs") -> parseSongList(o.optJSONArray("songs"), src)
                    o.has("id") && o.has("name") && (o.has("singer") || o.has("artist")) && !o.has("cover") ->
                        listOf(parseSong(o, src))
                    else -> emptyList()
                }
                // 元数据榜（有 id/name/cover）也保留，点进去再拉详情
                val cnt = o.optInt("count", o.optInt("trackCount", o.optInt("total", songs.size)))
                if (name.isNotEmpty()) out.add(RankBoard(name = name, source = src, songs = songs, id = id, cover = cover, count = cnt))
            }
            val filtered = if (source.isNotEmpty()) out.filter { it.source.equals(source, true) } else out
            filtered
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRankDetailPayload(raw: String, src: String): List<Song> {
        return try {
            val d = JSONObject(raw)
            val payload = d.optJSONObject("data") ?: d.optJSONObject("rank") ?: d.optJSONObject("playlist") ?: d
            var list = parseSongList(
                payload.optJSONArray("list") ?: payload.optJSONArray("songs") ?: payload.optJSONArray("tracks") ?:
                    d.optJSONArray("list") ?: d.optJSONArray("songs") ?: d.optJSONArray("data"),
                src
            )
            if (list.isEmpty()) {
                d.optJSONObject("data")?.let { data ->
                    list = parseSongList(data.optJSONArray("list") ?: data.optJSONArray("songs") ?: data.optJSONArray("tracks"), src)
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
        runCatching { fetchRankDirect(source.ifEmpty { "wy" }, id) }.getOrNull()
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
                "/music/rank/detail?id=$q&src=$s",
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

    // action: login | register — 与脚本无关；登录走 jdk 优先，避免 OkHttp 在部分机型 POST 被掐
    fun auth(user: String, pass: String, action: String = "login"): AuthOutcome {
        return synchronized(authLock) { try {
            if (baseUrl.isEmpty()) return AuthOutcome.Fail(AuthOutcome.Kind.NETWORK, "服务未就绪，请稍后重试")
            if (user.trim().length < 3) return AuthOutcome.Fail(AuthOutcome.Kind.AUTH_REJECTED, "用户名需 3-40 字符")
            if (pass.isEmpty()) return AuthOutcome.Fail(AuthOutcome.Kind.AUTH_REJECTED, "请输入密码")
            if (action == "register" && pass.length < 10) return AuthOutcome.Fail(AuthOutcome.Kind.AUTH_REJECTED, "注册密码至少 10 位")
            val payload = JSONObject().apply {
                put("name", user.trim())
                put("password", pass)
                put("action", action)
            }
            val raw = postJsonAuth("/api/user/login", payload)
            if (raw.isEmpty()) return AuthOutcome.Fail(AuthOutcome.Kind.NETWORK, "无响应，检查服务地址/网络 base=$baseUrl")
            val d = try {
                JSONObject(raw)
            } catch (_: Exception) {
                val snip = raw.take(60).replace("\n", " ")
                return AuthOutcome.Fail(AuthOutcome.Kind.PARSE, "响应不是 JSON（$snip）base=$baseUrl")
            }
            if (!d.optBoolean("ok", false)) {
                val serverMsg = orChain(d, "msg", "error", "message")
                val code = d.optInt("code", d.optInt("status", -1))
                val detail = buildString {
                    append(if (serverMsg.isNotEmpty()) serverMsg else "登录失败")
                    if (code >= 0) append(" · code=").append(code)
                    val snip = raw.replace("\n", " ").take(120)
                    if (serverMsg.isEmpty() || serverMsg == "登录失败" || serverMsg.length <= 4) {
                        append(" · raw=").append(snip)
                    }
                    append(" · base=").append(baseUrl)
                    append(" · action=").append(action)
                }
                return AuthOutcome.Fail(AuthOutcome.kindOf(code, serverMsg), detail)
            }
            val uname = d.optString("user").ifEmpty { user.trim() }
            val data = d.optJSONObject("data")
            val bundle = data?.let { LocalStore.UserBundle.fromCloud(it) }
            lastAuthAt = System.currentTimeMillis()
            lastAuthUser = uname
            lastAuthBundle = bundle
            AuthOutcome.Ok(
                user = uname,
                data = bundle,
                action = action
            )
        } catch (e: Exception) {
            AuthOutcome.Fail(
                AuthOutcome.Kind.NETWORK,
                "异常: ${e.javaClass.simpleName}: ${e.message?.take(120)} · base=$baseUrl · action=$action"
            )
        }}
    }

    // 账号改名/注销属于非幂等操作：单次 HTTPS POST 并保留真实 HTTP 状态码，
    // 不走 postJson 的多路重试，避免响应丢失时重复执行改名或删除。
    private fun accountMutationPost(path: String, body: JSONObject): Pair<Int, String> {
        val url = abs(path)
        if (url.isEmpty()) return -1 to JSONObject().put("ok", false).put("msg", "服务未就绪，请稍后重试").toString()
        return httpText("POST", url, body.toString())
    }

    internal fun renameAccount(name: String, password: String, newName: String): AccountMutationOutcome =
        AccountMutationClient.rename(name, password, newName, AccountMutationTransport(::accountMutationPost))

    internal fun deleteAccount(name: String, password: String): AccountMutationOutcome =
        AccountMutationClient.delete(name, password, AccountDeleteConfirmation.CONFIRMED, AccountMutationTransport(::accountMutationPost))

    /** 改名后让短时认证数据缓存跟随同一账号的新用户名，保留完整云端合并包。 */
    fun noteAccountRenamed(oldName: String, newName: String) = synchronized(authLock) {
        if (lastAuthUser == oldName.trim()) lastAuthUser = newName.trim()
    }

    /** 注销成功后禁止后续读取已删除账号的短时认证缓存。 */
    fun clearDeletedAccountCache(name: String) = synchronized(authLock) {
        if (lastAuthUser == name.trim()) {
            lastAuthUser = ""
            lastAuthBundle = null
            lastAuthAt = 0L
        }
    }

    fun loadUserData(user: String, pass: String): LocalStore.UserBundle? {
        // 启动/同步阶段禁止再次打 login；登录成功时 auth() 已返回云端 data。
        // 这里仅消费最近一次登录缓存，避免一次登录后又追加第二次 /api/user/login 造成真机 502/频控。
        val now = System.currentTimeMillis()
        return if (user.trim() == lastAuthUser && lastAuthBundle != null && now - lastAuthAt < 5 * 60_000L) lastAuthBundle else null
    }

    fun saveUserData(user: String, pass: String, bundle: LocalStore.UserBundle): SaveOutcome {
        return try {
            fun post(data: org.json.JSONObject): Pair<Boolean, String> {
                val raw = postJson("/api/user/save", JSONObject().apply {
                    put("name", user)
                    put("password", pass)
                    put("data", data)
                })
                if (raw.isEmpty()) return false to "服务没有响应"
                val obj = JSONObject(raw)
                return obj.optBoolean("ok", false) to obj.optString("msg")
            }
            var data = bundle.toCloudJson()
            var (ok, msg) = post(data)
            // 头像过大或编码异常不应阻断“删除收藏歌单”等其他真实云同步。
            if (!ok && data.has("avatar")) {
                data.remove("avatar")
                val retry = post(data)
                ok = retry.first
                msg = retry.second
            }
            // 收藏远程歌单常带整表歌曲，体积过大时服务端会 413/502；压缩后再试。
            if (!ok) {
                data = bundle.toCloudJson(includeAvatar = false, remotePlaylistSongs = false)
                val retry = post(data)
                ok = retry.first
                msg = retry.second
            }
            if (!ok) {
                data = bundle.toCloudJson(includeAvatar = false, includeHistory = false, remotePlaylistSongs = false)
                val retry = post(data)
                ok = retry.first
                msg = retry.second
            }
            lastSaveError = if (ok) "" else msg.ifBlank { "保存失败" }
            if (ok) {
                lastAuthBundle = bundle
                lastAuthUser = user.trim()
                lastAuthAt = System.currentTimeMillis()
                return SaveOutcome.Ok
            }
            SaveOutcome.Fail(
                AuthOutcome.kindOf(-1, msg, default = AuthOutcome.Kind.AUTH_REJECTED),
                msg.ifBlank { "保存失败" }
            )
        } catch (e: Exception) {
            lastSaveError = e.message ?: e.javaClass.simpleName
            SaveOutcome.Fail(AuthOutcome.Kind.NETWORK, lastSaveError)
        }
    }

    @Volatile var lastSaveError: String = ""

    fun loadUserPlaylists(user: String, pass: String): List<Playlist> {
        return loadUserData(user, pass)?.playlists ?: emptyList()
    }

    // 云端脚本需登录；内置用 ScriptStorage.loadBuiltinScripts
    fun loadServerScripts(user: String, pass: String): List<Script> {
        return try {
            val raw = postJson("/music/script", JSONObject().apply {
                put("name", user); put("password", pass); put("action", "list")
            })
            if (raw.isEmpty()) return emptyList()
            val d = JSONObject(raw)
            if (!d.optBoolean("ok", false)) return emptyList()
            val arr = d.optJSONArray("list") ?: return emptyList()
            val out = ArrayList<Script>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = orChain(o, "id", "filename")
                var code = o.optString("code")
                if (code.isEmpty() && id.isNotEmpty()) {
                    val g = postJson("/music/script", JSONObject().apply {
                        put("name", user); put("password", pass)
                        put("action", "get"); put("id", id)
                    })
                    try { code = JSONObject(g).optString("code") } catch (_: Exception) {}
                }
                out.add(
                    Script(
                        id = id,
                        name = o.optString("name"),
                        description = o.optString("description"),
                        version = o.optString("version"),
                        author = o.optString("author"),
                        builtin = false,
                        code = code,
                        enabled = true
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 登录专用：短连接 + 502 单次退避重试，避免三连打爆网关
    private fun postJsonAuth(pathOrUrl: String, body: JSONObject): String {
        val url = abs(pathOrUrl)
        if (url.isEmpty()) {
            return JSONObject().put("ok", false).put("msg", "未设置服务地址").toString()
        }
        val payload = body.toString()
        val requestId = "sw-" + UUID.randomUUID().toString().take(8)
        var lastNetworkError = ""
        fun enrichJson(code: Int, text: String): String {
            return try {
                val o = JSONObject(text)
                if (!o.optBoolean("ok", true) || o.has("type") || code in listOf(502, 503, 504)) {
                    val msg0 = orChain(o, "msg", "message").ifEmpty { "登录失败" }
                    val raw = text.take(180).replace("\n", " ")
                    val msg = if (code in listOf(502, 503, 504) || raw.contains("cloudflare", true) || raw.contains("Bad gateway", true)) {
                        "登录失败 · code=$code · raw=$raw · base=$baseUrl · action=login"
                    } else if (msg0 == "登录失败" || msg0.isEmpty()) {
                        "登录失败 · code=$code · raw=$raw · base=$baseUrl · action=login"
                    } else msg0
                    o.put("ok", false)
                    o.put("msg", msg)
                    o.put("code", code)
                }
                o.toString()
            } catch (_: Exception) {
                text
            }
        }
        fun pack(code: Int, text: String): String? {
            if (text.isEmpty()) return null
            val trim = text.trimStart()
            if (trim.startsWith("{") || trim.startsWith("[")) return enrichJson(code, text)
            val plain = trim.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            val hint = when {
                code in listOf(502, 503, 504) || plain.contains("Bad Gateway", true) ->
                    "服务网关错误($code)，请稍后重试"
                code == 401 -> "用户名或密码错误"
                else -> "响应异常($code): $plain"
            }
            return JSONObject().put("ok", false).put("msg", "$hint · code=$code · base=$baseUrl · action=login")
                .put("code", code).put("raw", plain.take(120)).toString()
        }
        fun authOkHttp(): Pair<Int, String> {
            return try {
                val b = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Connection", "close")
                    .header("Cache-Control", "no-cache")
                    .header("X-SW-Request-Id", requestId)
                if (baseUrl.isNotEmpty()) {
                    b.header("Origin", baseUrl)
                    b.header("Referer", "$baseUrl/")
                }
                b.post(payload.toRequestBody(JSON_MT))
                authClient.newCall(b.build()).execute().use { resp ->
                    resp.code to (resp.body?.byteStream()?.let { readLimited(it) } ?: "")
                }
            } catch (e: Exception) {
                -1 to (e.javaClass.simpleName + ":" + (e.message ?: "")).take(180)
            }
        }
        fun once(): Pair<Int, String> {
            val (c1, t1) = jdkPost(url, payload, maxAttempts = 1, requestId = requestId)
            if (c1 == 429 || t1.contains("频繁")) return c1 to t1
            if (c1 in listOf(200, 400, 401, 409) && t1.isNotEmpty()) return c1 to t1
            if (c1 in listOf(502, 503, 504) || c1 < 0) {
                try { Thread.sleep(900) } catch (_: Exception) {}
                val (c2, t2) = jdkPost(url, payload, maxAttempts = 1, requestId = requestId)
                if (c2 in listOf(200, 400, 401, 409) && t2.isNotEmpty()) return c2 to t2
                if (c2 > 0 && t2.isNotEmpty()) return c2 to t2
            }
            val (c3, t3) = authOkHttp()
            if (c3 > 0 && t3.isNotEmpty()) return c3 to t3
            if (c1 > 0) return c1 to t1
            return c3 to t3
        }
        val (c, txt) = once()
        if (c < 0) lastNetworkError = txt
        if (c == 429 || txt.contains("频繁")) {
            return JSONObject().put("ok", false).put("msg", "登录过于频繁，请稍后再试").toString()
        }
        pack(c, txt)?.let { return it }
        val detail = lastNetworkError.ifEmpty { "SSL/网络握手失败" }
        return JSONObject().put("ok", false)
            .put("msg", "网络异常: 连接失败 code=-1（$detail）base=$baseUrl · action=login")
            .toString()
    }

    fun reportBug(error: String, model: String, deviceId: String, appVersion: String = "1.0.28"): Boolean {
        return try {
            val raw = postJson("/music/bug", JSONObject().apply {
                put("error", error.take(12000))
                put("model", model.take(120))
                put("deviceId", deviceId.take(120))
                put("appVersion", appVersion)
                put("ts", System.currentTimeMillis())
            })
            if (raw.isEmpty()) return false
            JSONObject(raw).optBoolean("ok", false)
        } catch (_: Exception) {
            false
        }
    }

    /** 服务端官方匿名取链：失败返回空串，由调用方继续走公开脚本解析。 */
    fun officialUrl(source: String, songId: String, br: String, keyword: String = ""): String {
        return try {
            val q = StringBuilder("/music/url?source=${java.net.URLEncoder.encode(source, "UTF-8")}&id=${java.net.URLEncoder.encode(songId, "UTF-8")}&br=${java.net.URLEncoder.encode(br, "UTF-8")}")
            if (keyword.isNotBlank()) q.append("&keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
            val raw = get(q.toString())
            JSONObject(raw).optString("url", "")
        } catch (_: Exception) { "" }
    }

    fun searchPlaylists(keyword: String, source: String = "", limit: Int = 100): List<Playlist> {
        return try {
            val q = java.net.URLEncoder.encode(keyword.ifBlank { "推荐" }, "UTF-8")
            val srcQ = if (source.isNotEmpty()) "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}" else ""
            val raw = get("/music/playlist?action=search&keyword=$q$srcQ&limit=$limit")
            return parsePlaylistsPayload(raw, source)
        } catch (_: Exception) {
            // 网关偶发 200+HTML/残缺 JSON 时降级为空；异常外抛会经 await() 取消
            // refreshHomeFeed 的并行事务，连带清空 discover 横幅与甄选歌单等其他分区。
            emptyList()
        }
    }

    /** Category-page-only variant that distinguishes a valid empty list from transport or payload failure. */
    internal fun searchPlaylistsCategoryOutcome(keyword: String, source: String, limit: Int): PlaylistSearchOutcome {
        return try {
            val q = java.net.URLEncoder.encode(keyword.ifBlank { "推荐" }, "UTF-8")
            val srcQ = if (source.isNotEmpty()) "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}" else ""
            val raw = get("/music/playlist?action=search&keyword=$q$srcQ&limit=${limit.coerceIn(1, 200)}")
            parseCategoryPlaylistPayload(raw, source)
        } catch (_: Exception) {
            PlaylistSearchOutcome.Failure
        }
    }

    /**
     * 首页甄选歌单（批24b）：官方广场源（tx/wy 有真广场，标题不再清一色"xx推荐"）；
     * 空结果由调用方回落 searchPlaylists（kg/kw/mg 无广场接口走搜索兜底）。
     */
    fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =
        recommendPlaylistsOutcome(source, limit).legacyListValue()

    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */
    internal fun recommendPlaylistsOutcome(
        source: String,
        limit: Int = 50
    ): FeedListOutcome<List<Playlist>> = captureListRequestOutcome {
        val srcQ = "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}"
        val response = getTextOutcome("/music/playlist?action=recommend$srcQ&limit=$limit")
        decodeListResponseOutcome(response) { raw ->
            parseRecommendPlaylistsPayloadOutcome(raw, source)
        }
    }

    /** searchPlaylists/recommendPlaylists 共用的歌单载荷解析（兼容 data/list/records 多外层与多字段名）。 */
    private fun parsePlaylistsPayload(raw: String, source: String): List<Playlist> {
        if (raw.isEmpty()) return emptyList()
        return try {
            val d = JSONObject(raw)
            val data = d.optJSONObject("data") ?: d.optJSONObject("result")
            val arr = d.optJSONArray("list") ?: d.optJSONArray("playlists") ?: d.optJSONArray("result") ?: d.optJSONArray("records")
                ?: data?.optJSONArray("list") ?: data?.optJSONArray("playlists") ?: data?.optJSONArray("result") ?: data?.optJSONArray("records") ?: return emptyList()
            val out = ArrayList<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = orChain(o, "id", "pid", "playlistId", "playlist_id", "mid", "rid")
                if (id.isEmpty()) continue
                val src = canonicalSourceId(orChain(o, "src", "source", "platform").ifEmpty { source })
                val requestedSource = canonicalSourceId(source)
                // 指定平台时只接受同源实体；空来源仅在请求本身已明确平台时补位。
                if (requestedSource.isNotEmpty() && src != requestedSource) continue
                val playlistText: (String) -> String = ::decodePlaylistMetadata
                out.add(
                    Playlist(
                        id = id,
                        name = playlistText(orChain(o, "name", "nm", "title", "playlistName")).ifEmpty { "歌单" },
                        cover = normalizeCoverUrl(orChain(o, "cover", "pic", "cv", "picUrl", "img")),
                        songs = mutableListOf(),
                        source = src,
                        author = playlistText(orChain(o, "creator", "author")),
                        description = playlistText(orChain(o, "description", "desc")),
                        count = o.optInt("count", o.optInt("trackCount", 0)),
                        playCount = o.optLong("playCount", o.optLong("playcount", o.optLong("play_count", o.optLong("playNum", 0L))))
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Validate the existing playlist envelope before reusing its parser, preserving success-empty semantics. */
    internal fun parseCategoryPlaylistPayload(raw: String, source: String): PlaylistSearchOutcome {
        if (raw.isBlank()) return PlaylistSearchOutcome.Failure
        return try {
            val root = JSONObject(raw)
            if ((root.has("ok") && !root.optBoolean("ok")) ||
                (root.has("success") && !root.optBoolean("success"))) {
                return PlaylistSearchOutcome.Failure
            }
            val data = root.optJSONObject("data") ?: root.optJSONObject("result")
            val hasPlaylistArray = root.optJSONArray("list") != null ||
                root.optJSONArray("playlists") != null || root.optJSONArray("result") != null ||
                root.optJSONArray("records") != null || data?.optJSONArray("list") != null ||
                data?.optJSONArray("playlists") != null || data?.optJSONArray("result") != null ||
                data?.optJSONArray("records") != null
            if (!hasPlaylistArray) PlaylistSearchOutcome.Failure
            else PlaylistSearchOutcome.Success(parsePlaylistsPayload(raw, source))
        } catch (_: Exception) {
            PlaylistSearchOutcome.Failure
        }
    }

    /** Keep the legacy playlist envelope/parser but distinguish valid empty data from request or JSON failure. */
    internal fun parseRecommendPlaylistsPayloadOutcome(
        raw: String,
        source: String
    ): FeedListOutcome<List<Playlist>> {
        if (raw.isBlank()) return FeedListOutcome.Failure
        return try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: root.optJSONObject("result")
            val explicitlyFailed = listOfNotNull(root, data).any { payload ->
                (payload.has("ok") && !payload.optBoolean("ok")) ||
                    (payload.has("success") && !payload.optBoolean("success"))
            }
            if (explicitlyFailed) return FeedListOutcome.Failure
            val hasPlaylistArray = root.optJSONArray("list") != null ||
                root.optJSONArray("playlists") != null || root.optJSONArray("result") != null ||
                root.optJSONArray("records") != null || data?.optJSONArray("list") != null ||
                data?.optJSONArray("playlists") != null || data?.optJSONArray("result") != null ||
                data?.optJSONArray("records") != null
            if (!hasPlaylistArray) FeedListOutcome.Failure
            else FeedListOutcome.Success(parsePlaylistsPayload(raw, source))
        } catch (_: Exception) {
            FeedListOutcome.Failure
        }
    }

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
        runCatching { fetchPlaylistDetailDirect(source, id.removePrefix("reco_").trim()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return playlistDetailServer(id, source)
    }

    /** Same-provider metadata plus its actual song list for a detail page; no cross-source cover or text fallback. */
    suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist? {
        val rawId = id.removePrefix("reco_").trim()
        if (rawId.isEmpty()) return null
        val direct = runCatching { fetchPlaylistDetailPayloadDirect(source, rawId) }.getOrNull()
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
                "/music/playlist/detail?id=$q&source=$src&limit=$MAX_DETAIL_SONGS",
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

    /** 批17：playlistDetail 直连化后同步 suspend（实现不变；唯一调用点 MainActivity:3256 已在协程内）。 */
    suspend fun importPlaylist(raw: String): Playlist? {
        return try {
            val q = raw.trim()
            if (q.isEmpty()) return null
            val src = when {
                q.contains("netease", true) || q.contains("music.163") -> "wy"
                q.contains("kugou", true) -> "kg"
                q.contains("kuwo", true) -> "kw"
                q.contains("y.qq") || q.contains("qq.com") -> "tx"
                q.contains("migu", true) -> "mg"
                else -> "wy"
            }
            // 优先 action=import（服务端多格式解析）
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            var rawDetail = get("/music/playlist?action=import&text=$enc")
            if (rawDetail.isEmpty()) {
                val idGuess = Regex("""[?&]id=([^&]+)""").find(q)?.groupValues?.getOrNull(1)
                    ?: Regex("""(\d{6,})""").find(q)?.groupValues?.getOrNull(1)
                    ?: q
                rawDetail = get("/music/playlist?action=detail&id=" + java.net.URLEncoder.encode(idGuess, "UTF-8") + "&source=$src")
            }
            if (rawDetail.isEmpty()) return null
            val d = JSONObject(rawDetail)
            // import 返回 ok=false
            if (d.has("ok") && !d.optBoolean("ok", true)) return null
            val name = orChain(d, "name", "nm").ifEmpty { "导入歌单" }
            val author = d.optString("creator").ifBlank { d.optString("author") }.ifBlank {
                d.optJSONObject("creator")?.optString("nickname").orEmpty()
            }.ifBlank {
                d.optJSONObject("author")?.optString("name").orEmpty()
            }
            var cover = normalizeCoverUrl(orChain(d, "cover", "pic"))
            val type = d.optString("type")
            val realSrc = when {
                type.contains("netease", true) -> "wy"
                else -> src
            }
            var songs = parseSongList(d.optJSONArray("list") ?: d.optJSONArray("songs"), realSrc)
            if (songs.isEmpty()) {
                val id = d.optString("id").ifEmpty {
                    Regex("""(\d{6,})""").find(q)?.groupValues?.getOrNull(1).orEmpty()
                }
                // 只在真实来源明确时重试详情；绝不回退网易云补数据。
                if (id.isNotEmpty() && realSrc.isNotBlank()) songs = MusicSourceRegistry.platformSource(realSrc).playlistDetail(id)
            }
            if (songs.isEmpty()) return null
            if (cover.isEmpty() && realSrc !in Platforms.ALL) cover = songs.firstOrNull()?.cover.orEmpty()
            val id = d.optString("id").ifEmpty {
                songs.firstOrNull()?.id?.let { "imp_$it" } ?: ("imp_" + System.currentTimeMillis())
            }
            Playlist(
                id = id,
                name = name.ifEmpty { "导入歌单" },
                cover = cover,
                songs = songs.toMutableList(),
                local = false,
                source = realSrc,
                // 在线导入必须保留上游实际作者；缺字段由详情 UI 标为在线歌单，不伪造未知作者。
                author = author
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 平台歌单分类（两级：大类→子分类）。返回 null 表示服务端无分类。
     * 批33b：服务端 plcats 现返回 {groups:[{name,items}...], list:[...]} 真实两级结构
     * （tx 语种/流派/主题/心情/场景…、kg 主题/语种/风格/年代/心情、kw 主题/心情/场景…）。
     */
    data class CatGroups(val groups: List<Pair<String, List<String>>>)

    fun plCatGroups(source: String = "wy"): CatGroups? {
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

    /** 扁平分类（旧接口兼容；新 UI 优先用 plCatGroups 两级结构）。 */
    fun plCats(source: String = "wy"): List<String> {
        return try {
            val raw = get("/music/plcats?source=${java.net.URLEncoder.encode(source, "UTF-8")}")
            if (raw.isEmpty()) return emptyList()
            val arr = JSONObject(raw).optJSONArray("list") ?: return emptyList()
            buildList { for (i in 0 until arr.length()) { val c = arr.optString(i).trim(); if (c.isNotEmpty()) add(c) } }.distinct()
        } catch (_: Exception) { emptyList() }
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
