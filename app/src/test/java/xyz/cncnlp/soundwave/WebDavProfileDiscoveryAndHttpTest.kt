package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavAutoSyncBinding
import xyz.cncnlp.soundwave.sync.WebDavHttpPolicy
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavProfileNameCrypto
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import xyz.cncnlp.soundwave.sync.normalizeWebDavBaseUrl
import xyz.cncnlp.soundwave.sync.parseWebDavAccountFileNames
import java.nio.charset.StandardCharsets

/**
 * 双方案补丁（用户 2026-11-03 20:48 拍板）的纯逻辑与协议级测试：
 * 方案一 = 资料名加密进载荷（PBKDF2 + AES-GCM）+ PROPFIND 列举发现 + 解密导入信息；
 * 方案二 = http 按 (scheme+host+port) 单服务器例外，默认 HTTPS-only 不变。
 * 本机无 JDK：本文件写好待合并 CI 执行（台账 WAVE-ACCOUNT-REMOVAL §补丁登记）。
 */
class WebDavProfileDiscoveryAndHttpTest {

    // ---------- 方案一：密钥派生（RFC 7914 已知答案向量） ----------

    @Test
    fun pbkdf2MatchesRfc7914Vectors() {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        val password = "password".toByteArray(StandardCharsets.UTF_8)
        val salt = "salt".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(WebDavProfileNameCrypto.deriveKey(password, salt, 1, 32))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(WebDavProfileNameCrypto.deriveKey(password, salt, 2, 32))
        )
        // 确定性 + 盐敏感性
        assertEquals(
            hex(WebDavProfileNameCrypto.deriveKey(password, salt, 5, 32)),
            hex(WebDavProfileNameCrypto.deriveKey(password, salt, 5, 32))
        )
        assertFalse(
            hex(WebDavProfileNameCrypto.deriveKey(password, salt, 5, 32)) ==
                hex(WebDavProfileNameCrypto.deriveKey(password, "salt2".toByteArray(StandardCharsets.UTF_8), 5, 32))
        )
    }

    // ---------- 方案一：加解密往返 ----------

    @Test
    fun profileNameCryptoRoundTripsAndFailsClosed() {
        val name = "晴枫的资料"
        val password = "webdav-password-1"
        val encrypted = WebDavProfileNameCrypto.encrypt(name, password)
        assertNotNull(encrypted)
        assertFalse("密文不得包含明文名", encrypted!!.contains(name))
        assertEquals(name, WebDavProfileNameCrypto.decrypt(encrypted, password))

        // 随机盐/IV：两次加密产物不同，但都能解开
        val encrypted2 = WebDavProfileNameCrypto.encrypt(name, password)
        assertNotNull(encrypted2)
        assertFalse(encrypted == encrypted2)
        assertEquals(name, WebDavProfileNameCrypto.decrypt(encrypted2!!, password))

        // 密码不对 → null（按未发现处理，不抛异常）
        assertNull(WebDavProfileNameCrypto.decrypt(encrypted, "wrong-password"))
        // 篡改密文 → GCM 校验失败 → null
        val tampered = JSONObject(encrypted).put("ct", "AAAA" + JSONObject(encrypted).getString("ct").substring(4)).toString()
        assertNull(WebDavProfileNameCrypto.decrypt(tampered, password))
        // 垃圾输入与空密码
        assertNull(WebDavProfileNameCrypto.decrypt("not-json", password))
        assertNull(WebDavProfileNameCrypto.decrypt(encrypted, ""))
        assertNull(WebDavProfileNameCrypto.encrypt("", password))
        assertNull(WebDavProfileNameCrypto.encrypt(name, ""))
        // 迭代数被改到下限以下 → 拒绝（防降级）
        val downgraded = JSONObject(encrypted).put("iter", 100).toString()
        assertNull(WebDavProfileNameCrypto.decrypt(downgraded, password))
    }

    // ---------- 方案一：目录列举解析 ----------

    @Test
    fun parseAccountFileNamesFromMultistatus() {
        val suffixA = "a".repeat(64)
        val suffixB = "0123456789abcdef".repeat(4)
        val body = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/SoundWave/</d:href></d:response>
          <d:response><d:href>/dav/SoundWave/soundwave-account-v2-$suffixA.json</d:href></d:response>
          <d:response><d:href>https://dav.example.test/dav/SoundWave/soundwave-account-v2-$suffixB.json</d:href></d:response>
          <d:response><d:href>/dav/SoundWave/soundwave-sync.json</d:href></d:response>
          <d:response><d:href>/dav/SoundWave/soundwave-account-v2-nothex.json</d:href></d:response>
          <d:response><d:href>/dav/SoundWave/soundwave-account-v2-$suffixA.json</d:href></d:response>
        </d:multistatus>"""
        assertEquals(
            listOf("soundwave-account-v2-$suffixA.json", "soundwave-account-v2-$suffixB.json"),
            parseWebDavAccountFileNames(body)
        )
        assertEquals(emptyList<String>(), parseWebDavAccountFileNames("<d:multistatus/>"))
    }

    // ---------- 方案一：同步写入加密名 + 发现流端到端（fake 传输） ----------

    @Test
    fun syncWritesEncryptedProfileNameAndDiscoveryFindsIt() {
        val baseUrl = "https://dav.example.test/dav"
        val username = "webdav-user"
        val password = "webdav-password"
        val profileName = "晴枫的资料"
        val key = AccountDataIdentity.key(profileName)
        val fileUrl = "$baseUrl/SoundWave/soundwave-account-v2-${key.removePrefix("swown2_")}.json"

        val remote = mutableMapOf<String, String>()
        val transport = WebDavTransport { request ->
            when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "GET" -> remote[request.url]?.let { WebDavHttpResponse(200, it, "\"etag-1\"") } ?: WebDavHttpResponse(404)
                "PUT" -> {
                    remote[request.url] = request.body?.toString(StandardCharsets.UTF_8).orEmpty()
                    WebDavHttpResponse(201, etag = "\"etag-2\"")
                }
                else -> WebDavHttpResponse(405)
            }
        }
        val bundle = LocalStore.UserBundle(
            favs = mutableListOf(Song(id = "fav-1", name = "fav-1", singer = "Artist", source = "wy")),
            dailyListenStatsOwnerV2 = key
        )
        val client = WebDavSyncClient(
            transport = transport, accountDataKey = key, accountDataCurrent = { true }, profileName = profileName
        )
        val result = client.sync(baseUrl, username, password, bundle,
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY })
        assertTrue("account sync should succeed: $result", result is WebDavSyncResult.Success)

        val written = JSONObject(remote.getValue(fileUrl))
        assertFalse("资料名明文不得出现在载荷中", remote.getValue(fileUrl).contains(profileName))
        val enc = written.getString("profileNameEnc")
        assertEquals(profileName, WebDavProfileNameCrypto.decrypt(enc, password))

        // 新设备视角：仅有地址+账号密码 → 列举目录 → 解密出名字与元信息
        val listingTransport = WebDavTransport { request ->
            when {
                request.method == "PROPFIND" && request.depth == "1" -> WebDavHttpResponse(
                    207,
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/SoundWave/soundwave-account-v2-${key.removePrefix("swown2_")}.json</d:href></d:response></d:multistatus>"""
                )
                request.method == "GET" && request.url == fileUrl -> WebDavHttpResponse(200, remote.getValue(fileUrl), "\"etag-2\"")
                else -> WebDavHttpResponse(404)
            }
        }
        val discovered = WebDavSyncClient(transport = listingTransport).discoverProfiles(baseUrl, username, password)
        assertEquals(1, discovered.size)
        assertEquals(profileName, discovered.single().profileName)
        assertEquals(key, discovered.single().ownerKey)
        assertEquals(2, discovered.single().playlistCount) // pls 恒含结构歌单 liked/local

        // 密码不对 → 解密失败 → 按未发现处理（空结果，不报错）
        val undiscovered = WebDavSyncClient(transport = listingTransport).discoverProfiles(baseUrl, username, "wrong-password")
        assertTrue(undiscovered.isEmpty())
    }

    @Test
    fun discoverySkipsLegacyPayloadWithoutEncryptedName() {
        val baseUrl = "https://dav.example.test/dav"
        val key = AccountDataIdentity.key("旧资料")
        val fileName = "soundwave-account-v2-${key.removePrefix("swown2_")}.json"
        val legacyDoc = JSONObject()
            .put("schema", "soundwave-webdav-sync").put("version", 2).put("owner", key)
            .put("updatedAt", 1234L).put("user", JSONObject().put("pls", JSONArray()))
            .toString()
        val transport = WebDavTransport { request ->
            when {
                request.method == "PROPFIND" && request.depth == "1" -> WebDavHttpResponse(
                    207,
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/SoundWave/$fileName</d:href></d:response></d:multistatus>"""
                )
                request.method == "GET" -> WebDavHttpResponse(200, legacyDoc)
                else -> WebDavHttpResponse(404)
            }
        }
        val discovered = WebDavSyncClient(transport = transport).discoverProfiles(baseUrl, "u", "p")
        assertTrue("旧载荷无加密名字段时不发现，走先设同名的老路", discovered.isEmpty())

        // 目录不存在（404）同样静默为空
        val missing = WebDavSyncClient(transport = WebDavTransport { WebDavHttpResponse(404) })
            .discoverProfiles(baseUrl, "u", "p")
        assertTrue(missing.isEmpty())
    }

    // ---------- 方案二：主机公私分类 ----------

    @Test
    fun hostClassificationSeparatesPublicFromPrivateOrLocal() {
        fun cls(host: String) = WebDavHttpPolicy.hostClassOfHost(host)
        // 私有 / 本地
        listOf(
            "192.168.1.10", "10.0.0.5", "172.16.0.9", "172.31.255.1", "127.0.0.1",
            "169.254.1.1", "100.64.1.2", "100.127.0.1", "localhost", "nas.local", "example.local",
            "::1", "fe80::1", "feb2::abcd", "fd00::1", "fc12::1", "::ffff:192.168.0.8"
        ).forEach { assertEquals("host $it", WebDavHttpPolicy.HostClass.PRIVATE_OR_LOCAL, cls(it)) }
        // 公网
        listOf(
            "8.8.8.8", "1.1.1.1", "172.32.0.9", "172.15.0.1", "100.128.0.1", "11.0.0.1",
            "dav.example.com", "dav.jianguoyun.com", "2001:db8::1", "fe00::1"
        ).forEach { assertEquals("host $it", WebDavHttpPolicy.HostClass.PUBLIC, cls(it)) }
    }

    // ---------- 方案二：例外键与匹配 ----------

    @Test
    fun httpExceptionKeysArePerServerHostAndPort() {
        assertEquals("http|192.168.1.10|8080", WebDavHttpPolicy.exceptionKey("http://192.168.1.10:8080/dav"))
        assertEquals("http|nas.local|80", WebDavHttpPolicy.exceptionKey("http://nas.local/dav/"))
        assertEquals("http|nas.local|80", WebDavHttpPolicy.exceptionKey("http://NAS.local/dav"))
        assertNull("https 不需要例外", WebDavHttpPolicy.exceptionKey("https://nas.local/dav"))
        assertNull(WebDavHttpPolicy.exceptionKey("not a url"))
        assertNull("带 userinfo 的地址不生成例外键", WebDavHttpPolicy.exceptionKey("http://user@nas.local/dav"))

        val exceptions = setOf("http|192.168.1.10|8080")
        assertTrue(WebDavHttpPolicy.exceptionApplies("http://192.168.1.10:8080/dav", exceptions))
        assertFalse("端口不同不沿用", WebDavHttpPolicy.exceptionApplies("http://192.168.1.10:9090/dav", exceptions))
        assertFalse("主机不同不沿用", WebDavHttpPolicy.exceptionApplies("http://192.168.1.11:8080/dav", exceptions))
        assertFalse("未确认时不适用", WebDavHttpPolicy.exceptionApplies("http://192.168.1.10:8080/dav", emptySet()))
    }

    @Test
    fun normalizeAcceptsHttpOnlyWithMatchingException() {
        val url = "http://192.168.1.10:8080/dav/"
        val exceptions = setOf("http|192.168.1.10|8080")
        // 默认（无线外）仍拒绝——默认 HTTPS-only 不变
        assertNull(normalizeWebDavBaseUrl(url))
        assertNull(normalizeWebDavBaseUrl(url, httpExceptions = emptySet()))
        // 有匹配例外 → 放行并规范化
        assertEquals("http://192.168.1.10:8080/dav", normalizeWebDavBaseUrl(url, httpExceptions = exceptions))
        // 例外不匹配（换地址/换端口）不放行
        assertNull(normalizeWebDavBaseUrl("http://192.168.1.10:9090/dav/", httpExceptions = exceptions))
        assertNull(normalizeWebDavBaseUrl("http://example.com/dav/", httpExceptions = exceptions))
        // https 不受例外表影响
        assertEquals("https://dav.example.test/dav", normalizeWebDavBaseUrl("https://dav.example.test/dav/", httpExceptions = exceptions))
    }

    @Test
    fun httpSyncIsRefusedBeforeAnyRequestWithoutException() {
        val requests = mutableListOf<WebDavHttpRequest>()
        val client = WebDavSyncClient(WebDavTransport { request ->
            requests += request
            WebDavHttpResponse(207)
        })
        val error = client.testConnection("http://192.168.1.10:8080/dav", "user", "password")
        assertNotNull(error)
        assertTrue(error!!.contains("HTTPS"))
        assertTrue("未确认例外的 http 不得发出任何请求", requests.isEmpty())

        // 同一客户端带上例外后，校验通过并发出 PROPFIND
        val allowed = WebDavSyncClient(
            transport = WebDavTransport { request -> requests += request; WebDavHttpResponse(207) },
            httpExceptions = setOf("http|192.168.1.10|8080")
        )
        allowed.testConnection("http://192.168.1.10:8080/dav", "user", "password")
        assertTrue(requests.isNotEmpty())
    }

    @Test
    fun autoSyncBindingAcceptsHttpOnlyViaException() {
        val key = "swown2_" + "a".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            WebDavAutoSyncBinding.bind(key, "http://192.168.1.10:8080/dav", "user")
        }
        val binding = WebDavAutoSyncBinding.bind(
            key, "http://192.168.1.10:8080/dav/", "user",
            httpExceptions = setOf("http|192.168.1.10|8080")
        )
        assertEquals("http://192.168.1.10:8080/dav", binding.endpoint)
        // https 绑定行为不变
        val https = WebDavAutoSyncBinding.bind(key, "https://dav.example.test/dav/", "user")
        assertEquals("https://dav.example.test/dav", https.endpoint)
    }
}
