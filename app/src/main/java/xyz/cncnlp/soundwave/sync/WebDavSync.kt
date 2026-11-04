package xyz.cncnlp.soundwave.sync

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.ResolveBlockingStage
import xyz.cncnlp.soundwave.ProfileId
import xyz.cncnlp.soundwave.SecretStore
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** WebDAV settings and credentials are intentionally independent of the SoundWave account session. */
internal data class WebDavSavedCredentials(
    val url: String,
    val username: String,
    val password: String
) {
    override fun toString(): String = "WebDavSavedCredentials(redacted)"
}

/** Unbound entrypoints remain legacy-only; profiles use separately confirmed bound documents. */
internal enum class WebDavSyncPolicy {
    LEGACY_GLOBAL,
    LOCAL_ONLY
}

internal const val WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE = "本机资料仅限本地使用，不支持 WebDAV 同步。"
internal const val WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE =
    "本机数据来源未能核验，已暂停 WebDAV 数据同步；本机数据不会上传或被远端数据覆盖。"

/** No PUT has left the client when its durable authorization barrier refuses admission. */
internal class WebDavPutAuthorizationException : IllegalStateException("WebDAV upload admission denied")

internal object WebDavCredentialStore {
    private const val PREFS = "soundwave_webdav"
    private const val KEY_URL = "url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD_ENCRYPTED = "password_encrypted"

    fun load(context: Context): WebDavSavedCredentials? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val encrypted = prefs.getString(KEY_PASSWORD_ENCRYPTED, null) ?: return WebDavSavedCredentials(url, username, "")
        val password = SecretStore.decrypt(context, encrypted).orEmpty()
        return WebDavSavedCredentials(url, username, password)
    }

    /**
     * 1.0.656 #13：细分读取结果，供「已保存凭据 → 只需点同步」判定。密码读不出来时保留地址/用户名，
     * 只要求重新输入密码；KeyStore 瞬时不可用（transient）不清除密文。
     */
    fun loadState(context: Context): WebDavSavedCredentialState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return WebDavSavedCredentialState.None
        val username = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() } ?: return WebDavSavedCredentialState.None
        if (normalizeWebDavBaseUrl(url, httpExceptions = WebDavHttpExceptionStore.load(context)) == null) {
            return WebDavSavedCredentialState.None
        }
        val encrypted = prefs.getString(KEY_PASSWORD_ENCRYPTED, null).orEmpty()
        if (encrypted.isEmpty()) return WebDavSavedCredentialState.PasswordUnavailable(url, username, transient = false)
        return when (val result = SecretStore.classify(context, encrypted)) {
            is SecretStore.DecryptResult.Ok ->
                if (result.plain.isEmpty()) WebDavSavedCredentialState.PasswordUnavailable(url, username, transient = false)
                else WebDavSavedCredentialState.Ready(WebDavSavedCredentials(url, username, result.plain))
            SecretStore.DecryptResult.Unavailable -> WebDavSavedCredentialState.PasswordUnavailable(url, username, transient = true)
            // 旧明文/损坏密文都不当作可用密码使用。
            else -> WebDavSavedCredentialState.PasswordUnavailable(url, username, transient = false)
        }
    }

    /** 「忘记登录信息」：删除地址、用户名与密文（commit，确认落盘）。 */
    fun clear(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_URL).remove(KEY_USERNAME).remove(KEY_PASSWORD_ENCRYPTED).commit()

    /** Never writes the password unless AndroidKeyStore encryption succeeds. */
    fun save(context: Context, url: String, username: String, password: String): Boolean {
        val normalizedUrl = normalizeWebDavBaseUrl(url, httpExceptions = WebDavHttpExceptionStore.load(context)) ?: return false
        if (username.isBlank() || password.isBlank()) return false
        val encrypted = SecretStore.encrypt(context, password) ?: return false
        if (password.isNotEmpty() && !encrypted.startsWith("v2:")) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, normalizedUrl)
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD_ENCRYPTED, encrypted)
            .commit()
    }
}

internal data class WebDavHttpRequest(
    val method: String,
    val url: String,
    val username: String,
    val password: String,
    val body: ByteArray? = null,
    val contentType: String? = null,
    val conditionHeader: Pair<String, String>? = null,
    /** PROPFIND Depth header; null keeps the transport default ("0"). Discovery uses "1". */
    val depth: String? = null
) {
    // Diagnostic interpolation must not expose credentials, private paths or sync content.
    override fun toString(): String = "WebDavHttpRequest(redacted)"
}

internal data class WebDavHttpResponse(
    val code: Int,
    val body: String = "",
    val etag: String? = null
) {
    override fun toString(): String = "WebDavHttpResponse(code=$code, redacted)"
}

internal fun interface WebDavTransport {
    fun execute(request: WebDavHttpRequest): WebDavHttpResponse

    /** Fake/legacy transports may ignore cancellation; their late result is still rejected. */
    fun execute(request: WebDavHttpRequest, lease: WebDavOperationGate.Lease): WebDavHttpResponse {
        lease.ensureActive()
        return execute(request).also { lease.ensureActive() }
    }
}

/**
 * Normalize a WebDAV base URL. Default policy is HTTPS-only; cleartext HTTP is accepted
 * only for (a) test-only loopback, or (b) a per-server exception the user explicitly
 * confirmed after a risk warning ([WebDavHttpPolicy], keyed by scheme+host+port —
 * never a global cleartext allowance).
 */
internal fun normalizeWebDavBaseUrl(
    raw: String,
    allowLoopbackHttpForTests: Boolean = false,
    httpExceptions: Set<String> = emptySet()
): String? = try {
    val value = raw.trim()
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    val port = uri.port
    val invalidPort = port != -1 && port !in 1..65535
    val testLoopbackHttp = allowLoopbackHttpForTests && scheme == "http" &&
        (host?.equals("localhost", ignoreCase = true) == true || host == "127.0.0.1" || host == "::1")
    val httpExceptionAllowed = scheme == "http" && WebDavHttpPolicy.exceptionApplies(value, httpExceptions)
    if ((scheme != "https" && !testLoopbackHttp && !httpExceptionAllowed) || uri.isOpaque || host.isNullOrBlank() ||
        invalidPort || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
    ) null else uri.toASCIIString().trimEnd('/').takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** 应用在 WebDAV 上的专属子目录名（坚果云等不允许在 /dav/ 根建文件）。 */
internal const val WEB_DAV_APP_DIRECTORY = "SoundWave"

/** 已规范化（无尾斜杠）的基础地址 → 集合 URL（统一带一个尾斜杠，避免服务器 301 跳转）。 */
internal fun collectionUrl(normalizedBase: String): String = normalizedBase.trimEnd('/') + "/"

/**
 * 同步文件所在目录（带尾斜杠）。用户若已直接把地址填到 .../SoundWave，则不再嵌套一层。
 */
internal fun webDavAppDirectoryUrl(normalizedBase: String): String {
    val base = normalizedBase.trimEnd('/')
    val last = base.substringAfterLast('/')
    return if (last.equals(WEB_DAV_APP_DIRECTORY, ignoreCase = true)) "$base/" else "$base/$WEB_DAV_APP_DIRECTORY/"
}

/** 旧版（1.0.654 及以前）同步文件所在目录；与应用目录相同时为 null（无需迁移读取）。 */
internal fun webDavLegacyDirectoryUrl(normalizedBase: String): String? {
    val legacy = collectionUrl(normalizedBase)
    return legacy.takeIf { it != webDavAppDirectoryUrl(normalizedBase) }
}

private fun webDavHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Credentials must never follow a redirect to a different host or scheme.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

internal class OkHttpWebDavTransport(
    private val callFactory: okhttp3.Call.Factory = webDavHttpClient()
) : WebDavTransport {
    override fun execute(request: WebDavHttpRequest): WebDavHttpResponse =
        execute(request, WebDavOperations.currentLease())

    override fun execute(request: WebDavHttpRequest, lease: WebDavOperationGate.Lease): WebDavHttpResponse {
        val builder = Request.Builder()
            .url(request.url)
            .header("Authorization", Credentials.basic(request.username, request.password, StandardCharsets.UTF_8))
        request.contentType?.let { builder.header("Content-Type", it) }
        request.conditionHeader?.let { (name, value) -> builder.header(name, value) }
        val requestBody = request.body?.toRequestBody(
            (request.contentType ?: "application/octet-stream").toMediaType()
        )
        val okhttpRequest = when (request.method) {
            "PROPFIND" -> builder.header("Depth", request.depth ?: "0")
                .method("PROPFIND", requestBody ?: ByteArray(0).toRequestBody("application/xml".toMediaType()))
                .build()
            "GET" -> builder.get().build()
            "PUT" -> {
                val body = requestBody ?: ByteArray(0).toRequestBody("application/json".toMediaType())
                // A received upload may lose its response. Never let OkHttp replay it on
                // transport failure or 503/408 follow-up: only the sync layer may retry
                // an explicit conflict after a fresh read and merge.
                builder.put(object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                }).build()
            }
            // 坚果云等服务不允许在 /dav/ 根直接建文件，需先 MKCOL 应用子目录。
            "MKCOL" -> builder.method("MKCOL", null).build()
            "DELETE" -> builder.delete().build()
            else -> throw IllegalArgumentException("Unsupported WebDAV method")
        }
        val call = callFactory.newCall(okhttpRequest)
        // ACCEPT6-MGCLOSE：lease 的 onCancel 回调在 lease 被取消的线程同步执行
        // （可为主线程侧的身份切换/取消动作），关连接投递 IO scope，不内联关。
        val unregister = lease.onCancel { ResolveBlockingStage.cancelCallAsync(call) }
        try {
            lease.ensureActive()
            return call.execute().use { response ->
                val output = ByteArrayOutputStream()
                response.body?.byteStream()?.use { input ->
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) {
                            throw IOException("WebDAV response exceeded the safe size limit")
                        }
                        output.write(chunk, 0, count)
                    }
                }
                val bytes = output.toByteArray()
                WebDavHttpResponse(
                    code = response.code,
                    body = String(bytes, StandardCharsets.UTF_8),
                    etag = response.header("ETag")
                )
            }.also { lease.ensureActive() }
        } catch (failure: Exception) {
            lease.ensureActive()
            throw failure
        } finally {
            unregister()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
    }
}

/**
 * 云端资料发现结果（新设备找回既有资料用）：名字已用用户刚填写的 WebDAV 密码解密成功，
 * 明文仅存在于内存与 UI 呈现，不落盘、不进诊断。ownerKey 为文档归属键（swown2_ 哈希）。
 */
internal data class WebDavDiscoveredProfile(
    val profileName: String,
    val ownerKey: String,
    val updatedAt: Long,
    val playlistCount: Int
) {
    override fun toString(): String =
        "WebDavDiscoveredProfile(ownerKey=$ownerKey, updatedAt=$updatedAt, playlistCount=$playlistCount, redacted)"
}

internal sealed class WebDavSyncResult {
    data class Success(
        val bundle: LocalStore.UserBundle,
        val remoteFileCreated: Boolean,
        val etag: String? = null,
        /** 本次读取并合并了旧版（基础目录根下）的同步文件；旧文件保留不删除。 */
        val migratedLegacyFile: Boolean = false,
        /** Authenticated account avatar travels beside, never inside, the shared user bundle. */
        val avatarB64: String = "",
        /** 合并后的扩展载荷；调用方在本机 bundle 应用后、会话仍有效时应用。非账户路径恒为 null。 */
        val profileExtras: JSONObject? = null
    ) : WebDavSyncResult() {
        override fun toString(): String =
            "WebDavSyncResult.Success(remoteFileCreated=$remoteFileCreated, migratedLegacyFile=$migratedLegacyFile, redacted)"
    }
    data class Failure(
        val message: String,
        val remoteStateUncertain: Boolean = false,
        val retryable: Boolean = false
    ) : WebDavSyncResult()
}

/**
 * Syncs one versioned JSON document in the selected WebDAV collection. It uses the existing
 * user-bundle serializer/merge policy so favorites, playlist tombstones, ordering and dated song
 * listening statistics keep their established semantics. Account credentials and account-only script
 * metadata are never included; avatars use a separate account-bound metadata map.
 */
internal class WebDavSyncClient(
    private val transport: WebDavTransport = OkHttpWebDavTransport(),
    private val localPlaybackHistory: () -> List<Song> = { emptyList() },
    /** Test-only escape hatch for the loopback mock server; never enabled by app code. */
    private val allowLoopbackHttpForTests: Boolean = false,
    private val avatarSync: WebDavAvatarSync? = null,
    /** Hashed, exact-account identity for the explicit account-owned v2 manual path. */
    private val accountDataKey: String? = null,
    /** Must remain true for the current account session throughout the complete operation. */
    private val accountDataCurrent: () -> Boolean = { false },
    /** 扩展载荷（脚本/策略/设置/不喜欢）能力；仅账户文档路径使用。 */
    private val profileExtrasSync: WebDavProfileExtrasSync? = null,
    /** 冲突确认闸门；仅手动同步入口提供，自动同步传 null（维持字段级合并不打扰）。 */
    private val conflictGate: WebDavSyncConflictGate? = null,
    /** 用户已按服务器确认的 http 例外键集合（[WebDavHttpPolicy]）；默认空 = HTTPS-only。 */
    private val httpExceptions: Set<String> = emptySet(),
    /** 本机资料名明文（仅内存）：写入账户文档时经 [WebDavProfileNameCrypto] 加密，永不明文落盘/上网。 */
    private val profileName: String? = null,
    /** Automatic callers durably mark uncertainty before any account-owned PUT may leave. */
    private val beforeAccountPut: (() -> Unit)? = null,
    private val onAccountPutResponse: ((Int) -> Unit)? = null
) {
    fun testConnectionWithCredentials(
        credentialProvider: () -> WebDavSavedCredentials?,
        policy: WebDavSyncPolicy = WebDavSyncPolicy.LEGACY_GLOBAL,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState = {
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN
        }
    ): String? = WebDavOperations.blocking {
        testConnectionWithCredentialsLocked(credentialProvider, policy, gateStateProvider)
    }

    private fun testConnectionWithCredentialsLocked(
        credentialProvider: () -> WebDavSavedCredentials?,
        policy: WebDavSyncPolicy,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState
    ): String? {
        if (policy == WebDavSyncPolicy.LOCAL_ONLY) return WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE
        val credentials = try {
            credentialProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return "读取本机 WebDAV 登录信息失败；请重新填写用户名和密码。"
        }
            ?: return "请填写 WebDAV 用户名和密码"
        return testConnection(credentials.url, credentials.username, credentials.password, policy, gateStateProvider)
    }

    @Suppress("UNUSED_PARAMETER")
    fun testConnectionWithCredentials(
        profileId: ProfileId,
        credentialProvider: () -> WebDavSavedCredentials?
    ): String = WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE

    @Suppress("UNUSED_PARAMETER")
    fun testConnection(url: String, username: String, password: String, profileId: ProfileId): String =
        WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE

    fun testConnection(
        url: String,
        username: String,
        password: String,
        policy: WebDavSyncPolicy = WebDavSyncPolicy.LEGACY_GLOBAL,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState = {
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN
        }
    ): String? = WebDavOperations.blocking {
        testConnectionLocked(url, username, password, policy, gateStateProvider)
    }

    private fun testConnectionLocked(
        url: String,
        username: String,
        password: String,
        policy: WebDavSyncPolicy,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState
    ): String? {
        if (policy == WebDavSyncPolicy.LOCAL_ONLY) return WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE
        val base = normalizeWebDavBaseUrl(url, allowLoopbackHttpForTests, httpExceptions)
            ?: return "请输入有效的 WebDAV 地址（仅支持 HTTPS，或已确认风险例外的 http 地址）"
        if (username.isBlank() || password.isBlank()) return "请填写 WebDAV 用户名和密码"
        return try {
            val response = execute(propfind(collectionUrl(base), username, password))
            if (response.code !in setOf(200, 207)) return connectionFailure(response.code, phase = "PROPFIND")
            val appDir = webDavAppDirectoryUrl(base)
            ensureCollection(base, appDir, username, password)?.let { return it }
            // 连接测试同时验证写权限：在应用子目录写入并删除一个临时探测文件。
            val probeUrl = appDir + "soundwave-probe-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) + ".txt"
            val put = execute(
                WebDavHttpRequest(
                    method = "PUT",
                    url = probeUrl,
                    username = username,
                    password = password,
                    body = "SoundWave WebDAV write probe".toByteArray(StandardCharsets.UTF_8),
                    contentType = "text/plain; charset=utf-8"
                )
            )
            if (put.code !in setOf(200, 201, 204)) return connectionFailure(put.code, phase = "PUT_PROBE")
            // 删除失败只会残留一个很小的临时文件，不影响同步，故不视为连接失败。
            try {
                execute(WebDavHttpRequest("DELETE", probeUrl, username, password))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Ordinary cleanup failure remains best-effort; cancellation is not success.
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: WebDavTransportFailure) {
            transportFailureMessage(failure)
        } catch (_: Exception) {
            "WebDAV 目录检查流程异常；未执行同步写入，请重新检查地址和设置。"
        }
    }

    /**
     * 云端资料发现（只读）：列举同步目录中的账户文档（soundwave-account-v2-*.json），
     * 逐个读取并用 [password] 解密其中的加密资料名。解密成功才算发现——密码不对、
     * 旧载荷无加密名字段、文档格式不符时一律静默跳过，按「未发现」处理（不报错）。
     * 旧载荷（无 profileNameEnc）不在此列：仍走「先设相同资料名」的老路。
     */
    fun discoverProfiles(url: String, username: String, password: String): List<WebDavDiscoveredProfile> =
        WebDavOperations.blocking { discoverProfilesLocked(url, username, password) }

    private fun discoverProfilesLocked(url: String, username: String, password: String): List<WebDavDiscoveredProfile> {
        val base = normalizeWebDavBaseUrl(url, allowLoopbackHttpForTests, httpExceptions) ?: return emptyList()
        if (username.isBlank() || password.isBlank()) return emptyList()
        return try {
            val directories = listOfNotNull(webDavAppDirectoryUrl(base), webDavLegacyDirectoryUrl(base)).distinct()
            val found = LinkedHashMap<String, WebDavDiscoveredProfile>()
            for (dir in directories) {
                val listing = try {
                    execute(
                        WebDavHttpRequest(
                            method = "PROPFIND",
                            url = dir,
                            username = username,
                            password = password,
                            body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/></d:prop></d:propfind>"
                                .toByteArray(StandardCharsets.UTF_8),
                            contentType = "application/xml; charset=utf-8",
                            depth = "1"
                        )
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                if (listing.code !in setOf(200, 207)) continue
                for (fileName in parseWebDavAccountFileNames(listing.body)) {
                    if (fileName in found) continue
                    val profile = readDiscoveredProfile(dir + fileName, fileName, username, password) ?: continue
                    found[fileName] = profile
                    if (found.size >= MAX_DISCOVERED_PROFILES) break
                }
                if (found.size >= MAX_DISCOVERED_PROFILES) break
            }
            found.values.sortedByDescending { it.updatedAt }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 读取单个候选文档并解密资料名；任何不符/失败返回 null（静默跳过）。 */
    private fun readDiscoveredProfile(
        fileUrl: String, fileName: String, username: String, password: String
    ): WebDavDiscoveredProfile? {
        val expectedOwner = "swown2_" + fileName.removePrefix("soundwave-account-v2-").removeSuffix(".json")
        val response = try {
            execute(WebDavHttpRequest("GET", fileUrl, username, password))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (response.code != 200) return null
        return try {
            val root = JSONObject(response.body)
            if (root.optString("schema") != SCHEMA || root.optInt("version", 0) != ACCOUNT_FORMAT_VERSION ||
                root.optString("owner") != expectedOwner
            ) return null
            val enc = root.optString("profileNameEnc").takeIf { it.isNotBlank() } ?: return null
            val name = WebDavProfileNameCrypto.decrypt(enc, password) ?: return null
            WebDavDiscoveredProfile(
                profileName = name,
                ownerKey = expectedOwner,
                updatedAt = root.optLong("updatedAt", 0L),
                playlistCount = root.optJSONObject("user")?.optJSONArray("pls")?.length() ?: 0
            )
        } catch (_: Exception) {
            null
        }
    }

    fun syncWithCredentials(
        local: LocalStore.UserBundle,
        credentialProvider: () -> WebDavSavedCredentials?,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState,
        policy: WebDavSyncPolicy = WebDavSyncPolicy.LEGACY_GLOBAL
    ): WebDavSyncResult = WebDavOperations.blocking {
        syncWithCredentialsLocked(local, credentialProvider, gateStateProvider, policy)
    }

    private fun syncWithCredentialsLocked(
        local: LocalStore.UserBundle,
        credentialProvider: () -> WebDavSavedCredentials?,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState,
        policy: WebDavSyncPolicy
    ): WebDavSyncResult {
        if (policy == WebDavSyncPolicy.LOCAL_ONLY) {
            return WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE)
        }
        if (accountDataKey != null) {
            if (!validAccountDataKey()) return accountDataFailure()
            if (!isAccountDataCurrent()) return accountDataFailure()
        } else {
            legacyDataSyncGateFailure(gateStateProvider)?.let { return it }
        }
        val credentials = try {
            credentialProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return WebDavSyncResult.Failure("读取本机 WebDAV 登录信息失败；请重新填写用户名和密码。")
        }
            ?: return WebDavSyncResult.Failure("请填写 WebDAV 用户名和密码")
        return sync(credentials.url, credentials.username, credentials.password, local, gateStateProvider)
    }

    @Suppress("UNUSED_PARAMETER")
    fun syncWithCredentials(
        profileId: ProfileId,
        local: LocalStore.UserBundle,
        credentialProvider: () -> WebDavSavedCredentials?
    ): WebDavSyncResult = WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE)

    @Suppress("UNUSED_PARAMETER")
    fun sync(
        url: String,
        username: String,
        password: String,
        local: LocalStore.UserBundle,
        profileId: ProfileId
    ): WebDavSyncResult = WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE)

    fun sync(
        url: String,
        username: String,
        password: String,
        local: LocalStore.UserBundle,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState,
        policy: WebDavSyncPolicy = WebDavSyncPolicy.LEGACY_GLOBAL,
        documentId: String? = null,
        profileAuthorization: (() -> Boolean)? = null,
        profileHistory: (() -> List<xyz.cncnlp.soundwave.Song>)? = null
    ): WebDavSyncResult = WebDavOperations.blocking {
        syncLocked(url, username, password, local, gateStateProvider, policy, documentId, profileAuthorization, profileHistory)
    }

    private fun syncLocked(
        url: String,
        username: String,
        password: String,
        local: LocalStore.UserBundle,
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState,
        policy: WebDavSyncPolicy,
        documentId: String?,
        profileAuthorization: (() -> Boolean)?,
        profileHistory: (() -> List<Song>)?
    ): WebDavSyncResult {
        if (policy == WebDavSyncPolicy.LOCAL_ONLY) {
            return WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE)
        }
        if (accountDataKey != null) {
            if (!validAccountDataKey() || !isAccountDataCurrent()) return accountDataFailure()
        }
        if (documentId != null && !Regex("[a-f0-9]{32}").matches(documentId)) return WebDavSyncResult.Failure("远端资料 ID 无效")
        if (profileAuthorization != null) {
            val authorized = try {
                profileAuthorization()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!authorized) return WebDavSyncResult.Failure("资料未授权")
        } else if (accountDataKey == null) legacyDataSyncGateFailure(gateStateProvider)?.let { return it }
        val base = normalizeWebDavBaseUrl(url, allowLoopbackHttpForTests, httpExceptions)
            ?: return WebDavSyncResult.Failure("请输入有效的 WebDAV 地址（仅支持 HTTPS，或已确认风险例外的 http 地址）")
        if (username.isBlank() || password.isBlank()) {
            return WebDavSyncResult.Failure("请填写 WebDAV 用户名和密码")
        }
        try {
            val connection = execute(propfind(collectionUrl(base), username, password))
            if (connection.code !in setOf(200, 207)) {
                return WebDavSyncResult.Failure(connectionFailure(connection.code, phase = "PROPFIND"))
            }
            // 同步文件放在应用子目录 <base>/SoundWave/ 中：坚果云等服务禁止在 /dav/ 根创建文件（PUT 404），
            // 目录必须先存在（MKCOL）。旧版直接写在 <base>/ 下的文件只读迁移，不删除。
            val appDir = webDavAppDirectoryUrl(base)
            val legacyDir = webDavLegacyDirectoryUrl(base)
            val fileName = when {
                accountDataKey != null -> "soundwave-account-v2-${accountDataKey.removePrefix("swown2_")}.json"
                documentId == null -> SYNC_FILE
                else -> "soundwave-profile-$documentId.json"
            }
            val fileUrl = appDir + fileName
            if (profileAuthorization != null && !profileAuthorization()) return WebDavSyncResult.Failure("资料授权已变化")
            ensureCollection(base, appDir, username, password)?.let { return WebDavSyncResult.Failure(it) }
            if (profileAuthorization != null && !profileAuthorization()) return WebDavSyncResult.Failure("资料授权已变化")
            val initialRemote = readRemoteForWrite(fileUrl, username, password, documentId, accountDataKey)
            if (initialRemote is RemoteRead.Failure) return WebDavSyncResult.Failure(initialRemote.message)
            var legacyRemote: LocalStore.UserBundle? = null
            var legacyAvatars: JSONObject? = null
            // Account v2 is a fresh, owner-verified sidecar. Never inspect the shared v1 file.
            if (accountDataKey == null && initialRemote == RemoteRead.Missing && legacyDir != null) {
                when (val legacy = readRemote(legacyDir + fileName, username, password, documentId)) {
                    RemoteRead.Missing -> Unit
                    is RemoteRead.Present -> {
                        legacyRemote = legacy.bundle
                        legacyAvatars = legacy.accountAvatars
                    }
                    is RemoteRead.Failure -> return WebDavSyncResult.Failure("读取旧版同步文件失败：" + legacy.message)
                }
            }

            // 冲突闸门（用户定版）：双边自上次同步后都改过 → 先经用户确认，按较新一方整库同步；
            // 取消则在任何写入前中止。判定在首次远端读取后、任何 PUT 之前完成。
            var conflictWinnerLocal: Boolean? = null
            val gate = conflictGate
            if (accountDataKey != null && gate != null && initialRemote is RemoteRead.Present) {
                val remoteUpdatedAt = initialRemote.documentUpdatedAt
                val lastSync = gate.lastSyncAt()
                val localChanged = gate.localChangedAt()
                if (lastSync > 0L && remoteUpdatedAt > lastSync && localChanged > lastSync) {
                    if (!gate.confirm(localChanged, remoteUpdatedAt)) {
                        return WebDavSyncResult.Failure("同步已取消，本机与远端均未变更")
                    }
                    conflictWinnerLocal = localChanged >= remoteUpdatedAt
                }
            }
            var remoteExtras = (initialRemote as? RemoteRead.Present)?.profileExtras
            var mergedExtras: JSONObject? = if (accountDataKey != null) {
                profileExtrasSync?.let { WebDavProfileExtras.merge(it.capture(), remoteExtras) }
            } else null

            if (accountDataKey != null && !isAccountDataCurrent()) return accountDataFailure()
            var localSnapshot = snapshotWithCurrentLocalHistory(local, profileHistory, accountDataKey)
            val remote = (initialRemote as? RemoteRead.Present)?.bundle?.let { accountOwnedBundle(it, accountDataKey) } ?: legacyRemote
            var merged = when (conflictWinnerLocal) {
                true -> localSnapshot
                false -> remote ?: localSnapshot
                else -> if (remote == null) localSnapshot else mergeWebDavBundle(localSnapshot, remote)
            }
            if (accountDataKey != null) merged = accountOwnedBundle(merged, accountDataKey)
            var remoteAvatars = (initialRemote as? RemoteRead.Present)?.accountAvatars ?: legacyAvatars
            var mergedAvatars = mergeAccountAvatars(remoteAvatars, allowLocalExport = avatarSessionCurrent())
            var condition = when (val state = initialRemote) {
                RemoteRead.Missing -> "If-None-Match" to "*"
                is RemoteRead.Present -> {
                    val etag = state.etag
                        ?: return WebDavSyncResult.Failure("远端文件未提供 ETag；为避免覆盖并发更改，本次未写入。")
                    "If-Match" to etag
                }
                is RemoteRead.Failure -> null
            }
            if (profileAuthorization != null && !profileAuthorization()) return WebDavSyncResult.Failure("资料授权已变化")
            if (accountDataKey != null && !isAccountDataCurrent()) return accountDataFailure()
            var response = writeRemote(fileUrl, username, password, merged, condition, documentId, mergedAvatars, remoteAvatars, accountDataKey, mergedExtras)
            if (response.code == 409 || response.code == 412) {
                // 412：其他设备在我们 GET 之后写入（或 If-None-Match 时文件已被创建）；
                // 409：WebDAV 语义下多为父目录缺失/被移动。先重新确保目录，再重读合并并重试一次；绝不盲目覆盖。
                if (response.code == 409) {
                    ensureCollection(base, appDir, username, password)?.let { return WebDavSyncResult.Failure(it) }
                }
                if (profileAuthorization != null && !profileAuthorization()) return WebDavSyncResult.Failure("资料授权已变化")
                if (accountDataKey != null && !isAccountDataCurrent()) return accountDataFailure()
                val latest = readRemoteForWrite(fileUrl, username, password, documentId, accountDataKey)
                if (accountDataKey != null && !isAccountDataCurrent()) return accountDataFailure()
                localSnapshot = snapshotWithCurrentLocalHistory(local, profileHistory, accountDataKey)
                when (latest) {
                    is RemoteRead.Present -> {
                        remoteExtras = latest.profileExtras
                        if (accountDataKey != null) {
                            mergedExtras = profileExtrasSync?.let { WebDavProfileExtras.merge(it.capture(), remoteExtras) }
                        }
                        merged = when (conflictWinnerLocal) {
                            true -> localSnapshot
                            false -> accountOwnedBundle(latest.bundle, accountDataKey)
                            else -> mergeWebDavBundle(localSnapshot, accountOwnedBundle(latest.bundle, accountDataKey))
                        }
                        if (accountDataKey != null) merged = accountOwnedBundle(merged, accountDataKey)
                        remoteAvatars = latest.accountAvatars
                        mergedAvatars = mergeAccountAvatars(remoteAvatars, allowLocalExport = avatarSessionCurrent())
                        val etag = latest.etag
                            ?: return WebDavSyncResult.Failure("冲突后的远端文件未提供 ETag；为避免覆盖并发更改，本次未重试写入。")
                        condition = "If-Match" to etag
                    }
                    RemoteRead.Missing -> {
                        if (response.code == 412) return WebDavSyncResult.Failure("同步时远端文件发生变化，请稍后重试")
                        merged = when (conflictWinnerLocal) {
                            true -> localSnapshot
                            false -> remote ?: localSnapshot
                            else -> if (remote == null) localSnapshot else mergeWebDavBundle(localSnapshot, remote)
                        }
                        mergedAvatars = mergeAccountAvatars(remoteAvatars, allowLocalExport = avatarSessionCurrent())
                        condition = "If-None-Match" to "*"
                    }
                    is RemoteRead.Failure -> return WebDavSyncResult.Failure(latest.message)
                }
                if (profileAuthorization != null && !profileAuthorization()) return WebDavSyncResult.Failure("资料授权已变化")
                if (accountDataKey != null && !isAccountDataCurrent()) return accountDataFailure()
                response = writeRemote(fileUrl, username, password, merged, condition, documentId, mergedAvatars, remoteAvatars, accountDataKey, mergedExtras)
            }
            if (response.code !in setOf(200, 201, 204)) {
                return WebDavSyncResult.Failure(connectionFailure(response.code, syncing = true, phase = "PUT"))
            }
            if (accountDataKey != null && !isAccountDataCurrent()) return accountDataFailure()
            val avatarResult = if (avatarSessionCurrent()) selectedAvatar(remoteAvatars) else ""
            return WebDavSyncResult.Success(
                merged.copy(avatarB64 = "").let { if (accountDataKey != null) accountOwnedBundle(it, accountDataKey) else it },
                remoteFileCreated = initialRemote == RemoteRead.Missing,
                etag = response.etag,
                avatarB64 = if (avatarSessionCurrent()) avatarResult else "",
                migratedLegacyFile = legacyRemote != null,
                profileExtras = if (accountDataKey != null) mergedExtras else null
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AccountDataExpired) {
            return accountDataFailure()
        } catch (_: WebDavPutAuthorizationException) {
            return WebDavSyncResult.Failure("无法安全保存自动同步授权状态；未继续上传，请检查本机存储。")
        } catch (failure: WebDavTransportFailure) {
            // Never include exception text, request headers, URL, username or password in diagnostics.
            return WebDavSyncResult.Failure(
                transportFailureMessage(failure),
                remoteStateUncertain = failure.method == "PUT",
                retryable = failure.method != "PUT"
            )
        } catch (_: Exception) {
            // A failure during PUT may happen after the server accepted the request; never imply that retry is certainly safe.
            return WebDavSyncResult.Failure("WebDAV 同步流程异常；远端结果未确认。请先检查远端同步文件，再决定是否重试。")
        }
    }

    fun syncBoundProfile(context: Context, token: xyz.cncnlp.soundwave.migration.ProfileContext): WebDavSyncResult =
        WebDavOperations.blocking { syncBoundProfileLocked(context, token) }

    private fun syncBoundProfileLocked(context: Context, token: xyz.cncnlp.soundwave.migration.ProfileContext): WebDavSyncResult {
        val owner = token.owner as? xyz.cncnlp.soundwave.migration.ProfileOwner.RecordedProfile
            ?: return WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE)
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.remoteReady(token))
            return WebDavSyncResult.Failure("资料运行边界尚未验收；未读凭据、未请求")
        val binding = ProfileRemoteBindings.load(context, token)
            ?: return WebDavSyncResult.Failure("未明确授权该资料的远端绑定")
        val local = LocalStore.load(context, owner.id)
        val result = sync(binding.credentials.url, binding.credentials.username, binding.credentials.password, local,
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN },
            documentId = binding.documentId,
            profileAuthorization = { ProfileRemoteBindings.load(context, token) == binding },
            profileHistory = { LocalStore.loadHistory(context, owner.id) })
        if (result is WebDavSyncResult.Success) {
            return WebDavOperations.currentLease().commit {
                applyBoundProfileResult(context, token, binding, result)
            }
        }
        return result
    }

    private fun applyBoundProfileResult(
        context: Context,
        token: xyz.cncnlp.soundwave.migration.ProfileContext,
        binding: ProfileRemoteBinding,
        result: WebDavSyncResult.Success
    ): WebDavSyncResult {
        if (!xyz.cncnlp.soundwave.migration.ProfileCatalog.isCurrent(context, token) ||
            ProfileRemoteBindings.load(context, token) != binding) return WebDavSyncResult.Failure("资料或绑定已变化；未应用远端结果")
        if (!ProfileRemoteBindings.recordSuccessfulPut(context, token, binding, result.etag)) return WebDavSyncResult.Failure("远端已写入；同步回执未确认，请保留本机数据后重试")
        if (!LocalStore.applyInactiveProfileSync(context, token, result.bundle)) return WebDavSyncResult.Failure("资料回写未确认")
        return result
    }

    private fun legacyDataSyncGateFailure(
        gateStateProvider: () -> LegacyUnassignedSnapshot.AccountSyncGateState
    ): WebDavSyncResult.Failure? {
        val state = try {
            gateStateProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return WebDavSyncResult.Failure("本机数据来源校验失败；已阻止 WebDAV 访问。")
        }
        return if (state == LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN) {
            WebDavSyncResult.Failure(WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE)
        } else null
    }

    private fun avatarSessionCurrent(): Boolean = try {
        avatarSync != null && avatarSync.accountKey.matches(Regex("swav1_[a-f0-9]{64}")) && avatarSync.isCurrent()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun selectedAvatar(remote: JSONObject?): String {
        if (!avatarSessionCurrent()) return ""
        val sync = avatarSync ?: return ""
        val local = WebDavAvatarSync.validatedImageDataUri(sync.avatarB64)
        if (local != null) return local
        val value = remote?.opt(sync.accountKey) as? String
        return value?.let(WebDavAvatarSync::validatedImageDataUri).orEmpty()
    }

    /** Preserve every remote entry verbatim; if a map is already oversized, do not add/replace entries. */
    private fun mergeAccountAvatars(remote: JSONObject?, allowLocalExport: Boolean): JSONObject? {
        val result = remote?.let { JSONObject(it.toString()) }
        val sync = avatarSync
        if (!allowLocalExport || sync == null || !avatarSessionCurrent()) return result
        val image = WebDavAvatarSync.validatedImageDataUri(sync.avatarB64) ?: return result
        val key = sync.accountKey
        if (!key.matches(Regex("swav1_[a-f0-9]{64}"))) return result
        val map = result ?: JSONObject()
        if (map.length() > MAX_AVATAR_ACCOUNT_ENTRIES || map.toString().length > MAX_AVATAR_MAP_CHARS) return result
        if (!map.has(key) && map.length() >= MAX_AVATAR_ACCOUNT_ENTRIES) return result
        val replaced = JSONObject(map.toString()).put(key, image)
        if (replaced.toString().length > MAX_AVATAR_MAP_CHARS) return result
        return replaced
    }

    private fun accountOwnedBundle(bundle: LocalStore.UserBundle, owner: String?): LocalStore.UserBundle {
        if (owner == null) return bundle
        val stats = if (bundle.dailyListenStatsOwnerV2 == owner) bundle.dailyListenStatsJson else "{}"
        return bundle.copy(dailyListenStatsJson = stats, dailyListenStatsOwnerV2 = owner)
    }

    private fun validAccountDataKey(): Boolean = accountDataKey?.matches(ACCOUNT_DATA_KEY_PATTERN) == true

    private fun isAccountDataCurrent(): Boolean {
        if (accountDataKey == null) return true
        WebDavOperations.currentLease().ensureActive()
        val current = try { accountDataCurrent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        WebDavOperations.currentLease().ensureActive()
        return current
    }

    private fun accountDataFailure() = WebDavSyncResult.Failure("当前资料已变化或数据边界无效；未继续 WebDAV 同步。")

    private class AccountDataExpired : IllegalStateException()

    private fun snapshotWithCurrentLocalHistory(
        local: LocalStore.UserBundle,
        profileHistory: (() -> List<xyz.cncnlp.soundwave.Song>)? = null,
        accountOwner: String? = null
    ): LocalStore.UserBundle {
        if (accountOwner != null && !isAccountDataCurrent()) throw AccountDataExpired()
        val source = if (accountOwner != null) accountOwnedBundle(local, accountOwner) else local
        val snapshot = source.syncSnapshot().let { if (accountOwner != null) accountOwnedBundle(it, accountOwner) else it }
        snapshot.reconcileSyncTombstones()
        val history = WebDavHistoryReconciler.reconcile(
            independentHistory = profileHistory?.invoke() ?: localPlaybackHistory(),
            localBundleHistory = snapshot.history,
            webDavHistory = emptyList(),
            deletedHistoryEventKeys = snapshot.historyDeletionTombstones.keys
        )
        return snapshot.copy(history = history.toMutableList())
    }

    private fun mergeWebDavBundle(
        localSnapshot: LocalStore.UserBundle,
        remote: LocalStore.UserBundle
    ): LocalStore.UserBundle {
        val merged = CloudUserMerge.merge(localSnapshot, remote)
        val history = WebDavHistoryReconciler.reconcile(
            independentHistory = localSnapshot.history,
            localBundleHistory = emptyList(),
            webDavHistory = remote.history,
            deletedHistoryEventKeys = merged.historyDeletionTombstones.keys
        )
        return merged.copy(history = history.toMutableList())
    }

    /**
     * 确保 [dirUrl]（带尾斜杠）存在：PROPFIND 200/207 视为已存在；404 时 MKCOL 创建，
     * MKCOL 405 视为已存在（坚果云对已有目录返回 405），409 表示父目录缺失则逐级向上创建（不越过 [base]）。
     * 返回 null 表示目录可用，否则为用户可读的失败原因。
     */
    private fun ensureCollection(base: String, dirUrl: String, username: String, password: String, depth: Int = 0): String? {
        val baseCollection = collectionUrl(base)
        if (dirUrl == baseCollection) return null
        val probe = execute(propfind(dirUrl, username, password))
        when (probe.code) {
            200, 207 -> return null
            404 -> Unit
            else -> return connectionFailure(probe.code, phase = "PROPFIND_DIR")
        }
        var created = execute(WebDavHttpRequest("MKCOL", dirUrl, username, password))
        if (created.code == 409 && depth < MAX_MKCOL_DEPTH) {
            val parent = dirUrl.trimEnd('/').substringBeforeLast('/') + "/"
            if (parent.length >= baseCollection.length && parent.startsWith(baseCollection)) {
                ensureCollection(base, parent, username, password, depth + 1)?.let { return it }
                created = execute(WebDavHttpRequest("MKCOL", dirUrl, username, password))
            }
        }
        return when (created.code) {
            200, 201, 204 -> null
            // 405 Method Not Allowed：目录已存在（并发创建或 PROPFIND 不可见）。
            405 -> null
            else -> connectionFailure(created.code, phase = "MKCOL")
        }
    }

    /** GET 未返回 ETag 时（部分 alist/通用服务），用 PROPFIND getetag 补取；仍无则返回 null。 */
    private fun propfindEtag(fileUrl: String, username: String, password: String): String? {
        val response = try {
            execute(
                WebDavHttpRequest(
                    method = "PROPFIND",
                    url = fileUrl,
                    username = username,
                    password = password,
                    body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/></d:prop></d:propfind>"
                        .toByteArray(StandardCharsets.UTF_8),
                    contentType = "application/xml; charset=utf-8"
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (response.code !in setOf(200, 207)) return null
        return parseWebDavEtag(response.body)
    }

    /**
     * A later PROPFIND tag is not the version of an earlier GET body. Re-read under that
     * tag before merging, otherwise a concurrent edit could be overwritten with stale data.
     * Legacy read-only migration does not need a write version and still uses readRemote.
     */
    private fun readRemoteForWrite(
        fileUrl: String, username: String, password: String, documentId: String?, accountOwner: String? = null
    ): RemoteRead {
        val initial = readRemote(fileUrl, username, password, documentId, accountOwner = accountOwner)
        if (initial !is RemoteRead.Present || initial.etag != null) return initial
        val etag = propfindEtag(fileUrl, username, password)
            ?: return RemoteRead.Failure("远端文件未提供 ETag；为避免覆盖并发更改，本次未写入。")
        return when (val pinned = readRemote(fileUrl, username, password, documentId, etag, accountOwner)) {
            is RemoteRead.Present -> {
                if (pinned.etag != null && pinned.etag != etag) {
                    RemoteRead.Failure("远端文件版本不一致；为避免覆盖并发更改，本次未写入。")
                } else pinned.copy(etag = etag)
            }
            RemoteRead.Missing -> RemoteRead.Failure("同步时远端文件发生变化；本次未写入，请稍后重试。")
            is RemoteRead.Failure -> pinned
        }
    }

    private fun readRemote(
        fileUrl: String,
        username: String,
        password: String,
        documentId: String? = null,
        matchingEtag: String? = null,
        accountOwner: String? = null
    ): RemoteRead {
        val response = execute(WebDavHttpRequest("GET", fileUrl, username, password,
            conditionHeader = matchingEtag?.let { "If-Match" to it }))
        if (response.code == 404) return RemoteRead.Missing
        if (response.code != 200) return RemoteRead.Failure(connectionFailure(response.code, syncing = true, phase = "GET"))
        return try {
            val root = JSONObject(response.body)
            val expectedVersion = if (accountOwner != null) ACCOUNT_FORMAT_VERSION else FORMAT_VERSION
            if (root.optString("schema") != SCHEMA || root.optInt("version", 0) != expectedVersion ||
                (accountOwner != null && root.optString("owner") != accountOwner) ||
                (documentId != null && root.optString("documentId") != documentId)) {
                RemoteRead.Failure("远端同步文件格式不受支持；本机数据未更改")
            } else {
                val user = root.optJSONObject("user")
                    ?: return RemoteRead.Failure("远端同步文件无效；本机数据未更改")
                if (root.has("accountAvatars") && root.optJSONObject("accountAvatars") == null) {
                    return RemoteRead.Failure("远端同步文件头像元数据无效；本机数据未更改")
                }
                val avatars = root.optJSONObject("accountAvatars")?.let { JSONObject(it.toString()) }
                // Shared user.avatar is legacy/unbound input and must never be trusted.
                user.remove("avatar")
                RemoteRead.Present(
                    LocalStore.UserBundle.fromCloud(user, allowLocalUris = false), response.etag, avatars,
                    documentUpdatedAt = root.optLong("updatedAt", 0L),
                    profileExtras = root.optJSONObject("profileExtras")?.let { JSONObject(it.toString()) },
                    profileNameEnc = root.optString("profileNameEnc").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            RemoteRead.Failure("远端同步文件损坏或 JSON 格式无效；本机数据未更改")
        }
    }

    private fun writeRemote(
        fileUrl: String,
        username: String,
        password: String,
        bundle: LocalStore.UserBundle,
        condition: Pair<String, String>?,
        documentId: String? = null,
        accountAvatars: JSONObject? = null,
        remoteAvatars: JSONObject? = null,
        accountOwner: String? = null,
        profileExtras: JSONObject? = null
    ): WebDavHttpResponse {
        if (accountOwner != null && !isAccountDataCurrent()) throw AccountDataExpired()
        val userData = bundle.syncSnapshot().toCloudJson(
            includeAvatar = false,
            includeHistory = true,
            historyLimit = xyz.cncnlp.soundwave.PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT,
            includeDailyListenStats = true,
            includeLocalUris = false
        ).apply {
            remove("enabledScripts")
            remove("scriptOrder")
        }
        val payload = JSONObject()
            .put("schema", SCHEMA)
            .put("version", if (accountOwner != null) ACCOUNT_FORMAT_VERSION else FORMAT_VERSION)
            .put("updatedAt", System.currentTimeMillis())
            .apply { accountOwner?.let { put("owner", it) } }
            .apply { if (accountOwner != null) profileExtras?.let { put("profileExtras", it) } }
            .apply {
                // 资料名仅以加密形式写入（密钥由本次 WebDAV 密码派生）；加密不可用时
                // 省略该字段——旧设备/旧载荷继续走「先设同名」的老路，不阻塞同步。
                if (accountOwner != null) {
                    profileName?.takeIf { it.isNotBlank() }
                        ?.let { WebDavProfileNameCrypto.encrypt(it, password) }
                        ?.let { put("profileNameEnc", it) }
                }
            }
            .put("user", userData)
            .apply {
                // Session expiration immediately before PUT must not publish this session's local avatar.
                val safeAvatars = if (avatarSessionCurrent()) accountAvatars else remoteAvatars
                safeAvatars?.let { put("accountAvatars", JSONObject(it.toString())) }
            }
            .apply { documentId?.let { put("documentId", it) } }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_RESPONSE_BYTES) throw IOException("WebDAV document exceeded the safe size limit")
        return execute(
            WebDavHttpRequest(
                method = "PUT",
                url = fileUrl,
                username = username,
                password = password,
                body = payload,
                contentType = "application/json; charset=utf-8",
                conditionHeader = condition
            )
        )
    }

    private class WebDavTransportFailure(val method: String, cause: Exception) : IOException(cause)

    private fun execute(request: WebDavHttpRequest): WebDavHttpResponse = try {
        val lease = WebDavOperations.currentLease()
        lease.ensureActive()
        if (accountDataKey != null && !isAccountDataCurrent()) throw AccountDataExpired()
        if (request.method == "PUT" && accountDataKey != null) {
            beforeAccountPut?.invoke()
            lease.ensureActive()
            if (!isAccountDataCurrent()) throw AccountDataExpired()
        }
        transport.execute(request, lease).also {
            lease.ensureActive()
            if (accountDataKey != null && !isAccountDataCurrent()) throw AccountDataExpired()
            if (request.method == "PUT" && accountDataKey != null) onAccountPutResponse?.invoke(it.code)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AccountDataExpired) {
        throw failure
    } catch (failure: WebDavPutAuthorizationException) {
        throw failure
    } catch (failure: Exception) {
        WebDavOperations.currentLease().ensureActive()
        throw WebDavTransportFailure(request.method, failure)
    }

    private fun transportFailureMessage(failure: WebDavTransportFailure): String {
        val phase = when (failure.method) {
            "PROPFIND" -> "目录检查"
            "GET" -> "读取同步文件"
            "PUT" -> "上传同步文件"
            "MKCOL" -> "创建同步目录"
            "DELETE" -> "清理写入测试文件"
            else -> "WebDAV 请求"
        }
        var root: Throwable = failure
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val reason = when (root) {
            is UnknownHostException -> "无法解析服务器主机名，请检查地址和网络。"
            is ConnectException -> "无法连接服务器端口，请检查服务状态和网络。"
            is SocketTimeoutException -> "请求超时，请检查网络和服务器状态。"
            is SSLHandshakeException -> "HTTPS 证书或 TLS 握手失败，请检查证书和系统时间。"
            is SSLException -> "HTTPS/TLS 连接失败，请检查服务器证书配置。"
            is IOException -> "网络传输失败，请检查网络连接。"
            else -> "请求未能完成，请检查地址和服务状态。"
        }
        val outcome = if (failure.method == "PUT") {
            "上传响应未确认，远端可能已接收；请先检查远端同步文件，再决定是否重试。"
        } else {
            "本次请求未确认远端写入。"
        }
        return "WebDAV $phase 失败：$reason$outcome"
    }

    private fun propfind(base: String, username: String, password: String) = WebDavHttpRequest(
        method = "PROPFIND",
        url = base,
        username = username,
        password = password,
        body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
            .toByteArray(StandardCharsets.UTF_8),
        contentType = "application/xml; charset=utf-8"
    )

    private fun connectionFailure(code: Int, syncing: Boolean = false, phase: String? = null): String {
        val label = when (phase) {
            "PROPFIND" -> "目录检查（PROPFIND）"
            "PROPFIND_DIR" -> "同步目录检查（PROPFIND $WEB_DAV_APP_DIRECTORY/）"
            "MKCOL" -> "创建同步目录（MKCOL $WEB_DAV_APP_DIRECTORY/）"
            "PUT_PROBE" -> "写入测试（PUT）"
            "GET" -> "读取同步文件（GET）"
            "PUT" -> "上传同步文件（PUT）"
            else -> if (syncing) "同步请求" else "WebDAV 请求"
        }
        return when (code) {
            401 -> if (phase == "PROPFIND") {
                "$label 登录失败（认证错误，HTTP 401）：服务器拒绝了 WebDAV 凭据；请检查用户名/密码或应用专用密码（坚果云需在“第三方应用管理”生成应用密码）。当前仅到目录检查，尚未读取同步文件（GET）或上传（PUT）。"
            } else {
                "$label 登录失败（认证错误，HTTP 401）：服务器拒绝了 WebDAV 凭据；请检查用户名/密码或应用专用密码。"
            }
            403 -> "$label 权限不足（HTTP 403），请确认该账户有目录读取、创建子目录及文件写入权限。"
            in 300..399 -> "$label 被服务器要求跳转（HTTP $code）；为保护登录信息，已停止请求。请填写最终的 WebDAV 目录地址。"
            404 -> when (phase) {
                "PROPFIND" -> "WebDAV 目录检查失败（PROPFIND，HTTP 404）：目录不存在或基础路径错误；请检查服务器上的目录路径。"
                "GET" -> "远端同步文件不存在（GET，HTTP 404）；在目录已验证的前提下可按首次同步处理。"
                "MKCOL" -> "$label 失败（HTTP 404）：服务器不允许在该地址下创建目录；请确认填写的是 WebDAV 目录地址（坚果云为 https://dav.jianguoyun.com/dav/）。"
                "PUT", "PUT_PROBE" -> "$label 被拒绝（HTTP 404）：已确认 $WEB_DAV_APP_DIRECTORY 同步目录存在，但服务器仍拒绝创建文件；请确认填写的是 WebDAV 目录地址（不是网页入口，坚果云为 https://dav.jianguoyun.com/dav/），且账户对该目录有写权限。"
                else -> "$label 返回 HTTP 404；请检查 WebDAV 目录路径。"
            }
            405, 501 -> "$label 不受服务器支持（HTTP $code）；请确认该地址支持 WebDAV 方法。"
            409 -> when (phase) {
                "MKCOL" -> "$label 失败（HTTP 409）：上级目录不存在或无法创建；请检查 WebDAV 基础目录地址。"
                "PUT", "PUT_PROBE" -> "$label 被拒绝（HTTP 409）：同步目录不存在、已被移动或与其他设备的更改冲突；未确认写入，请稍后重新同步。"
                else -> "$label 检测到远端冲突（HTTP 409）；未确认写入，请重新读取后再同步。"
            }
            412 -> "$label 检测到远端冲突（HTTP 412）：远端文件已被其他设备修改；本次未覆盖，请稍后重新同步以合并。"
            423 -> "$label 失败（HTTP 423），远端文件或目录当前被锁定（可能正被其他设备或客户端占用）；请稍后重试。"
            429, 503 -> "$label 失败（HTTP $code）：服务器暂时限流或繁忙（坚果云免费账户有请求频率限制）；请稍后重试。"
            507 -> "$label 失败（HTTP 507），远端存储空间不足。"
            else -> "$label 失败（HTTP $code）；请检查服务器响应和账户权限。"
        }
    }

    private sealed class RemoteRead {
        object Missing : RemoteRead()
        data class Present(
            val bundle: LocalStore.UserBundle,
            val etag: String?,
            val accountAvatars: JSONObject? = null,
            /** 远端文档 updatedAt（冲突判定用）；旧文档缺省为 0。 */
            val documentUpdatedAt: Long = 0L,
            /** 远端扩展载荷（profileExtras）；旧文档无此键时为 null，向后兼容。 */
            val profileExtras: JSONObject? = null,
            /** 远端加密资料名（profileNameEnc）；旧文档无此键时为 null，旧版 app 忽略该未知键。 */
            val profileNameEnc: String? = null
        ) : RemoteRead()
        data class Failure(val message: String) : RemoteRead()
    }

    private companion object {
        const val SCHEMA = "soundwave-webdav-sync"
        const val FORMAT_VERSION = 1
        const val ACCOUNT_FORMAT_VERSION = 2
        val ACCOUNT_DATA_KEY_PATTERN = Regex("swown2_[a-f0-9]{64}")
        const val SYNC_FILE = "soundwave-sync.json"
        const val MAX_MKCOL_DEPTH = 4
        const val MAX_DISCOVERED_PROFILES = 8
        const val MAX_AVATAR_ACCOUNT_ENTRIES = 128
        const val MAX_AVATAR_MAP_CHARS = 4 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
    }
}

private val ACCOUNT_DOCUMENT_FILE_PATTERN = Regex("soundwave-account-v2-[a-f0-9]{64}\\.json")

/**
 * 从 PROPFIND（Depth:1）multistatus 中提取账户文档文件名（soundwave-account-v2-<64 位哈希>.json）。
 * href 可能是绝对路径或完整 URL、可能带百分号编码；只取最后一段并按模式匹配，去重保序。
 * 集合自身（目录）的 href 以斜杠结尾，天然不匹配文件模式。
 */
internal fun parseWebDavAccountFileNames(multistatus: String): List<String> {
    val hrefPattern = Regex("<(?:[A-Za-z0-9_-]+:)?href(?:\\s[^>]*)?>([^<]*)</(?:[A-Za-z0-9_-]+:)?href>", RegexOption.IGNORE_CASE)
    val names = LinkedHashSet<String>()
    hrefPattern.findAll(multistatus).forEach { match ->
        val decoded = percentDecodeWebDavHref(match.groupValues[1].trim())
        val last = decoded.substringAfterLast('/')
        if (ACCOUNT_DOCUMENT_FILE_PATTERN.matches(last)) names.add(last)
    }
    return names.toList()
}

private fun percentDecodeWebDavHref(value: String): String {
    if ('%' !in value) return value
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                i += 3
                continue
            }
        }
        bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
        i++
    }
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
}

/** 从 PROPFIND multistatus 中取出 getetag（兼容任意命名空间前缀与 &quot; 转义）。 */
internal fun parseWebDavEtag(multistatus: String): String? {
    val match = Regex("<(?:[A-Za-z0-9_-]+:)?getetag(?:\\s[^>]*)?>([^<]*)</(?:[A-Za-z0-9_-]+:)?getetag>", RegexOption.IGNORE_CASE)
        .find(multistatus) ?: return null
    return match.groupValues[1].trim()
        .replace("&quot;", "\"").replace("&#34;", "\"").replace("&amp;", "&")
        .takeIf { it.isNotEmpty() }
}
