package xyz.cncnlp.soundwave
import org.json.JSONObject
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentials
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.normalizeWebDavBaseUrl
import xyz.cncnlp.soundwave.sync.WebDavSyncPolicy
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import xyz.cncnlp.soundwave.sync.WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE
import xyz.cncnlp.soundwave.sync.WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE
import xyz.cncnlp.soundwave.ui.settings.DEFAULT_WEBDAV_URL
import xyz.cncnlp.soundwave.ui.settings.normalizeWebDavInitialUrl
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebDavSyncTest {
    @Test
    fun defaultNutstoreWebDavEndpointIsAvailableForAnEmptyFormUrl() {
        assertEquals("https://dav.jianguoyun.com/dav/", DEFAULT_WEBDAV_URL)
        assertEquals(DEFAULT_WEBDAV_URL, normalizeWebDavInitialUrl(""))
        assertEquals("https://other.example/dav/", normalizeWebDavInitialUrl(" https://other.example/dav/ "))
    }

    @Test
    fun localOnlyPolicyAndProfileIdBlockBeforeCredentialReadsOrAnyWebDavRequest() {
        val credentialReads = java.util.concurrent.atomic.AtomicInteger()
        val requests = mutableListOf<WebDavHttpRequest>()
        val client = WebDavSyncClient(WebDavTransport { request ->
            requests += request
            WebDavHttpResponse(500)
        })
        val credentialProvider = {
            credentialReads.incrementAndGet()
            WebDavSavedCredentials("not a URL", "", "")
        }
        val profileId = ProfileId.of("webdav-local-only-test")

        assertEquals(
            WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE,
            client.testConnectionWithCredentials(credentialProvider, WebDavSyncPolicy.LOCAL_ONLY)
        )
        assertEquals(
            WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE),
            client.syncWithCredentials(
                testBundle("must-not-read-or-retry"),
                credentialProvider,
                gateStateProvider = { throw AssertionError("LOCAL_ONLY must block before gate lookup") },
                policy = WebDavSyncPolicy.LOCAL_ONLY
            )
        )
        assertEquals(
            WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE,
            client.testConnection("not a URL", "", "", WebDavSyncPolicy.LOCAL_ONLY)
        )
        assertEquals(
            WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE),
            client.sync(
                "not a URL", "", "", testBundle("must-not-read-or-retry"),
                gateStateProvider = { throw AssertionError("LOCAL_ONLY must block before gate lookup") },
                policy = WebDavSyncPolicy.LOCAL_ONLY
            )
        )
        assertEquals(
            WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE,
            client.testConnectionWithCredentials(profileId, credentialProvider)
        )
        assertEquals(
            WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE),
            client.syncWithCredentials(profileId, testBundle("must-not-read-or-retry"), credentialProvider)
        )

        // Even invalid/blank inputs cannot make a profile-bound call fall through to validation.
        assertEquals(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE, client.testConnection("not a URL", "", "", profileId))
        assertEquals(
            WebDavSyncResult.Failure(WEB_DAV_LOCAL_ONLY_BLOCKED_MESSAGE),
            client.sync("not a URL", "", "", testBundle("must-not-read-or-retry"), profileId)
        )
        assertEquals(0, credentialReads.get())
        assertTrue("LOCAL_ONLY must not issue PROPFIND, GET, PUT, or conflict retries", requests.isEmpty())
    }

    @Test
    fun unknownSnapshotBlocksBeforeCredentialReadsAndAnyWebDavRequest() {
        listOf(LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN).forEach { blockedState ->
            val remoteBefore = documentWithFavorites("remote-only")
            val prefs = FakeWebDavPreferences(
                gateState = blockedState,
                credentials = WebDavSavedCredentials("https://unused.invalid/dav", "saved-user", "saved-password")
            )
            val transport = RecordingWebDavTransport(remoteBefore)
            val client = WebDavSyncClient(transport)
            val local = testBundle("local-only")
            val localBefore = local.toCloudJson(includeAvatar = false, includeHistory = true).toString()

            val result = client.syncWithCredentials(
                local = local,
                credentialProvider = { prefs.readCredentials() },
                gateStateProvider = { prefs.readGateState() }
            )
            val appliedBundle = (result as? WebDavSyncResult.Success)?.bundle

            assertEquals(blockedState, prefs.lastGateState)
            assertEquals(1, prefs.gateReads)
            assertEquals("$blockedState must reject before credential lookup", 0, prefs.credentialReads)
            assertTrue("$blockedState must issue no WebDAV request", transport.requests.isEmpty())
            assertTrue("$blockedState must return the clear refusal", result is WebDavSyncResult.Failure)
            assertEquals(WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE, (result as WebDavSyncResult.Failure).message)
            assertNull("blocked result cannot be applied locally", appliedBundle)
            assertEquals("local data must be unchanged", localBefore, local.toCloudJson(includeAvatar = false, includeHistory = true).toString())
            assertFalse(local.favs.any { it.id == "remote-only" })
            assertEquals("remote data must be unchanged", remoteBefore, transport.remoteBody)
        }
    }

    @Test
    fun legacyUnassignedSnapshotAllowsManualWebDavMerge() {
        val remoteBefore = documentWithFavorites("remote-existing")
        val prefs = FakeWebDavPreferences(
            gateState = LegacyUnassignedSnapshot.AccountSyncGateState.LEGACY_UNASSIGNED,
            credentials = WebDavSavedCredentials("https://fake.invalid/dav", "saved-user", "saved-password")
        )
        val transport = RecordingWebDavTransport(remoteBefore)
        val local = testBundle("local-edit")

        val result = WebDavSyncClient(transport).syncWithCredentials(
            local = local,
            credentialProvider = { prefs.readCredentials() },
            gateStateProvider = { prefs.readGateState() }
        )

        assertTrue("a valid legacy snapshot is eligible only through this manual action: $result", result is WebDavSyncResult.Success)
        assertEquals(2, prefs.gateReads)
        assertEquals(1, prefs.credentialReads)
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PUT"), transport.requests.map { it.method })
        val success = result as WebDavSyncResult.Success
        assertTrue(success.bundle.favs.any { it.id == "local-edit" })
        assertTrue(success.bundle.favs.any { it.id == "remote-existing" })
        assertTrue(transport.remoteBody.orEmpty().contains("local-edit"))
        assertTrue(transport.remoteBody.orEmpty().contains("remote-existing"))
    }

    @Test
    fun webDavWriteFiltersDeviceLocalSongAndPlaylistCovers() {
        val localTrack = Song(
            id = "local-track", name = "Local", singer = "Artist", source = "local",
            cover = "content://mock-device/covers/local-track.jpg",
            resolvedUrl = "file:///mock-device/Music/local-track.mp3",
            playedAt = System.currentTimeMillis()
        )
        val playlistTrack = Song(
            id = "playlist-track", name = "Playlist track", singer = "Artist", source = "local",
            cover = "/mock-device/covers/playlist-track.jpg"
        )
        val remoteTrack = Song(
            id = "remote-track", name = "Remote", singer = "Artist", source = "wy",
            cover = "https://cdn.example/remote-cover.jpg"
        )
        val localPlaylist = Playlist(
            id = "local-playlist", name = "Local playlist",
            cover = "file:///mock-device/covers/local-playlist.jpg",
            songs = mutableListOf(playlistTrack), local = true
        )
        val local = LocalStore.UserBundle(
            favs = mutableListOf(localTrack, remoteTrack),
            playlists = mutableListOf(localPlaylist),
            history = mutableListOf(localTrack)
        )

        MockWebDavServer().use { server ->
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(
                server.url, "mock-user", "mock-password", local
            )

            assertTrue("$result", result is WebDavSyncResult.Success)
            val body = requireNotNull(server.remoteBody)
            val user = JSONObject(body).getJSONObject("user")
            val favorites = user.getJSONArray("favs")
            assertEquals("", favorites.getJSONObject(0).getString("cover"))
            assertFalse(favorites.getJSONObject(0).has("resolvedUrl"))
            assertEquals("https://cdn.example/remote-cover.jpg", favorites.getJSONObject(1).getString("cover"))
            val playlists = user.getJSONArray("pls")
            val playlist = (0 until playlists.length()).mapNotNull { playlists.optJSONObject(it) }
                .first { it.optString("id") == "local-playlist" }
            assertEquals("", playlist.getString("cv"))
            assertEquals("", playlist.getJSONArray("songs").getJSONObject(0).getString("cover"))
            assertEquals("", user.getJSONArray("history").getJSONObject(0).getString("cover"))
            assertFalse(body.contains("mock-device"))
            assertFalse(body.contains("content://"))
        }
    }

    @Test
    fun unknownProvenanceBlocksManualWebDavBeforeCredentialLookupOrTransport() {
        val prefs = FakeWebDavPreferences(
            gateState = LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            credentials = WebDavSavedCredentials("https://fake.invalid/dav", "saved-user", "saved-password")
        )
        val transport = RecordingWebDavTransport(documentWithFavorites("remote-existing"))

        val result = WebDavSyncClient(transport).syncWithCredentials(
            local = testBundle("local-edit"),
            credentialProvider = { prefs.readCredentials() },
            gateStateProvider = { prefs.readGateState() }
        )

        assertTrue("$result", result is WebDavSyncResult.Failure)
        assertEquals(1, prefs.gateReads)
        assertEquals(0, prefs.credentialReads)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun unknownDataProvenanceDoesNotBlockCredentialTestAnd401IsClassifiedAtPropfind() {
        val transport = RecordingWebDavTransport(documentWithFavorites("remote-existing"))
        val client = WebDavSyncClient(transport)
        var credentialReads = 0
        val testError = client.testConnectionWithCredentials(
            credentialProvider = {
                credentialReads++
                WebDavSavedCredentials("https://fake.invalid/dav", "saved-user", "saved-password")
            },
            gateStateProvider = { throw AssertionError("credential-only PROPFIND must not consult data provenance") }
        )

        assertNull(testError)
        assertEquals(1, credentialReads)
        // 1.0.655：连接测试也验证写权限（应用子目录内写入并删除临时探测文件），从不触碰同步文件。
        assertEquals(listOf("PROPFIND", "PROPFIND", "PUT", "DELETE"), transport.requests.map { it.method })
        assertTrue(transport.requests.filter { it.method == "PUT" || it.method == "DELETE" }
            .all { it.url.startsWith("https://fake.invalid/dav/SoundWave/soundwave-probe-") })

        MockWebDavServer(rejectAuthorization = true).use { server ->
            val error = loopbackWebDavTestClient().testConnection(
                server.url, "bad-user", "bad-password",
                gateStateProvider = { throw AssertionError("PROPFIND credential test must not consult data provenance") }
            )
            assertNotNull(error)
            assertTrue(error.orEmpty(), error.orEmpty().contains("PROPFIND") && error.orEmpty().contains("HTTP 401"))
            assertTrue(error.orEmpty().contains("尚未读取同步文件（GET）或上传（PUT）"))
            assertEquals(listOf("PROPFIND"), server.requests.map { it.method })
        }
    }

    @Test
    fun webDavDeletionTombstoneSuppressesOldRemoteHistoryAndIsUploaded() {
        val now = System.currentTimeMillis()
        val deletedPlay = Song("deleted-on-other-device", "Deleted", "Artist", "wy", playedAt = now - 2_000L)
        val eventKey = PlaybackHistoryPolicy.historyEventKey(deletedPlay)
        val remoteUser = LocalStore.UserBundle(history = mutableListOf(deletedPlay))
            .toCloudJson(includeAvatar = false)
            .apply { remove("enabledScripts"); remove("scriptOrder") }
        val remoteDocument = JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 1)
            .put("user", remoteUser)
            .toString()
        val transport = RecordingWebDavTransport(remoteDocument)
        val local = LocalStore.UserBundle(
            history = mutableListOf(deletedPlay),
            historyDeletionTombstones = linkedMapOf(eventKey to now - 500L)
        )

        val result = WebDavSyncClient(transport).sync(
            "https://fake.invalid/dav",
            "mock-user",
            "mock-password",
            local,
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        )

        assertTrue("$result", result is WebDavSyncResult.Success)
        val uploadedUser = JSONObject(requireNotNull(transport.remoteBody)).getJSONObject("user")
        assertEquals(0, uploadedUser.getJSONArray("history").length())
        assertTrue(uploadedUser.getJSONObject("_swDeletedHistoryEvents").has(eventKey))
        assertFalse((result as WebDavSyncResult.Success).bundle.history.any { it.id == deletedPlay.id })
    }

    @Test
    fun verifiedEmptySnapshotKeepsManualWebDavSyncAndRemoteMerge() {
        val remoteBefore = documentWithFavorites("remote-existing")
        val prefs = FakeWebDavPreferences(
            gateState = LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY,
            credentials = WebDavSavedCredentials("https://fake.invalid/dav", "saved-user", "saved-password")
        )
        val transport = RecordingWebDavTransport(remoteBefore)
        val local = testBundle("local-edit")

        val result = WebDavSyncClient(transport).syncWithCredentials(
            local = local,
            credentialProvider = { prefs.readCredentials() },
            gateStateProvider = { prefs.readGateState() }
        )

        assertTrue("verified-empty keeps the existing manual sync path: $result", result is WebDavSyncResult.Success)
        assertEquals(2, prefs.gateReads)
        assertEquals(1, prefs.credentialReads)
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PUT"), transport.requests.map { it.method })
        val success = result as WebDavSyncResult.Success
        assertTrue(success.bundle.favs.any { it.id == "local-edit" })
        assertTrue(success.bundle.favs.any { it.id == "remote-existing" })
        assertTrue(transport.remoteBody.orEmpty().contains("local-edit"))
        assertTrue(transport.remoteBody.orEmpty().contains("remote-existing"))
    }

    @Test
    fun independentOnlyRecentHistoryIsIncludedInWebDavUpload() {
        val playedAt = System.currentTimeMillis() - 9_000L
        val independentOnly = Song(
            id = "independent-only",
            name = "Independent Only",
            singer = "Artist",
            source = "wy",
            playedAt = playedAt
        )
        val transport = RecordingWebDavTransport(initialRemoteBody = null)

        val result = WebDavSyncClient(
            transport = transport,
            localPlaybackHistory = { listOf(independentOnly) }
        ).syncVerifiedEmpty(
            "https://fake.invalid/dav",
            "fake-user",
            "fake-password",
            testBundle("bundle-without-history")
        )

        assertTrue("$result", result is WebDavSyncResult.Success)
        val uploadedHistory = JSONObject(requireNotNull(transport.remoteBody))
            .getJSONObject("user")
            .getJSONArray("history")
        assertEquals(1, uploadedHistory.length())
        assertEquals("independent-only", uploadedHistory.getJSONObject(0).getString("id"))
        assertEquals(playedAt, uploadedHistory.getJSONObject(0).getLong("playedAt"))
    }

    @Test
    fun syncReportsFolderPropfind404AsDirectoryCheckFailure() {
        MockWebDavServer(propfindStatus = 404).use { server ->
            val local = LocalStore.UserBundle(favs = mutableListOf(Song(id = "private-local-item", name = "Private title", singer = "Private artist", source = "private source")))
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "diagnostic-user", "diagnostic-password", local)

            assertTrue(result is WebDavSyncResult.Failure)
            val error = (result as WebDavSyncResult.Failure).message
            assertTrue(error, error.contains("PROPFIND") && error.contains("HTTP 404"))
            assertTrue(error, error.contains("目录检查"))
            assertTrue(error, error.contains("目录不存在或基础路径错误"))
            assertFalse(error.contains("diagnostic-user"))
            assertFalse(error.contains("diagnostic-password"))
            assertFalse(error.contains(server.url))
            assertFalse(error.contains("private-local-item"))
            assertFalse(error.contains("Private title"))
            assertEquals(listOf("PROPFIND"), server.requests.map { it.method })
        }
    }

    @Test
    fun connectionTestDistinguishesMissingBaseDirectoryFromPermissionDenied() {
        val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        val client = loopbackWebDavTestClient()

        MockWebDavServer(propfindStatus = 404).use { server ->
            val error = client.testConnection(server.url, "mock-user", "mock-password", gateStateProvider = gate)
            assertTrue("$error", error.orEmpty().contains("目录不存在或基础路径错误"))
            assertTrue("$error", error.orEmpty().contains("PROPFIND") && error.orEmpty().contains("HTTP 404"))
            assertEquals(listOf("PROPFIND"), server.requests.map { it.method })
        }

        MockWebDavServer(propfindStatus = 403).use { server ->
            val error = client.testConnection(server.url, "mock-user", "mock-password", gateStateProvider = gate)
            assertTrue("$error", error.orEmpty().contains("权限不足") && error.orEmpty().contains("HTTP 403"))
            assertEquals(listOf("PROPFIND"), server.requests.map { it.method })
        }
    }

    @Test
    fun putPermissionFailureIsReportedWithoutChangingLocalOrMockRemoteData() {
        MockWebDavServer(putStatus = 403).use { server ->
            val local = testBundle("keep-local")
            val before = local.toCloudJson(includeAvatar = false, includeHistory = true).toString()
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "mock-user", "mock-password", local)

            assertTrue("$result", result is WebDavSyncResult.Failure)
            val error = (result as WebDavSyncResult.Failure).message
            assertTrue(error, error.contains("PUT") && error.contains("HTTP 403") && error.contains("权限不足"))
            assertEquals(before, local.toCloudJson(includeAvatar = false, includeHistory = true).toString())
            assertNull(server.remoteBody)
            assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "GET", "PUT"), server.requests.map { it.method })
        }
    }

    @Test
    fun transportExceptionIsClassifiedWithoutLeakingUrlOrCredentials() {
        val client = WebDavSyncClient(WebDavTransport {
            throw UnknownHostException("mock-private-host user=private-user password=private-password")
        })
        val error = client.testConnection(
            "https://mock-private-host/dav",
            "private-user",
            "private-password",
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        )

        assertTrue("$error", error.orEmpty().contains("无法解析服务器主机名"))
        assertFalse(error.orEmpty().contains("mock-private-host"))
        assertFalse(error.orEmpty().contains("private-user"))
        assertFalse(error.orEmpty().contains("private-password"))
    }

    @Test
    fun syncReportsPut404AsSyncFileUploadFailureAfterMissingFileGet() {
        MockWebDavServer(putStatus = 404).use { server ->
            val local = LocalStore.UserBundle(favs = mutableListOf(Song(id = "private-local-item", name = "Private title", singer = "Private artist", source = "private source")))
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "diagnostic-user", "diagnostic-password", local)

            assertTrue(result is WebDavSyncResult.Failure)
            val error = (result as WebDavSyncResult.Failure).message
            assertTrue(error, error.contains("PUT") && error.contains("HTTP 404"))
            assertTrue(error, error.contains("上传"))
            assertTrue(error, error.contains("SoundWave 同步目录存在") && error.contains("写权限"))
            assertTrue(error, error.contains("WebDAV 目录地址") && error.contains("dav.jianguoyun.com/dav/"))
            assertFalse(error.contains("diagnostic-user"))
            assertFalse(error.contains("diagnostic-password"))
            assertFalse(error.contains(server.url))
            assertFalse(error.contains("private-local-item"))
            assertFalse(error.contains("Private title"))
            assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "GET", "PUT"), server.requests.map { it.method })
        }
    }

    @Test
    fun firstSyncCreatesOneWebDavDocumentWithoutAccountCredentials() {
        MockWebDavServer().use { server ->
            val local = LocalStore.UserBundle(
                favs = mutableListOf(Song(id = "song-1", name = "Local song", singer = "Artist", source = "wy")),
                playlists = mutableListOf(Playlist("mix", "My Mix", local = true))
            )
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "webdav-user", "webdav-secret", local)

            assertTrue("$result; requests=${server.requests.map { it.method to it.path }}", result is WebDavSyncResult.Success)
            val success = result as WebDavSyncResult.Success
            assertTrue(success.remoteFileCreated)
            assertEquals(listOf("/dav/collection/SoundWave/soundwave-sync.json", "/dav/collection/soundwave-sync.json"), server.requests.filter { it.method == "GET" }.map { it.path })
            assertEquals(listOf("song-1"), success.bundle.favs.map { it.id })
            assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "GET", "PUT"), server.requests.map { it.method })
            val put = server.requests.single { it.method == "PUT" }
            assertEquals("*", put.ifNoneMatch)
            assertEquals(null, put.ifMatch)
            assertTrue(server.requests.all { it.authorization == "Basic " + Base64.getEncoder().encodeToString("webdav-user:webdav-secret".toByteArray(StandardCharsets.UTF_8)) })

            val uploaded = JSONObject(server.remoteBody ?: error("mock server did not receive PUT"))
            assertEquals("soundwave-webdav-sync", uploaded.getString("schema"))
            assertEquals(1, uploaded.getInt("version"))
            val userData = uploaded.getJSONObject("user")
            assertEquals("song-1", userData.getJSONArray("favs").getJSONObject(0).getString("id"))
            assertFalse(uploaded.toString().contains("webdav-secret"))
            assertFalse(uploaded.toString().contains("webdav-user"))
            assertFalse(userData.has("avatar"))
            assertFalse(userData.has("enabledScripts"))
            assertFalse(userData.has("scriptOrder"))
        }
    }

    @Test
    fun existingRemoteWithoutEtagIsNotOverwritten() {
        val remoteBefore = documentWithFavorites("remote-existing")
        MockWebDavServer(initialRemoteBody = remoteBefore, initialRemoteEtag = null).use { server ->
            val local = testBundle("local-edit")
            val localBefore = local.toCloudJson(includeAvatar = false, includeHistory = true).toString()
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "mock-user", "mock-password", local)

            assertTrue("$result", result is WebDavSyncResult.Failure)
            assertTrue((result as WebDavSyncResult.Failure).message.contains("ETag"))
            assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PROPFIND"), server.requests.map { it.method })
            assertEquals(remoteBefore, server.remoteBody)
            assertEquals(localBefore, local.toCloudJson(includeAvatar = false, includeHistory = true).toString())
        }
    }

    @Test
    fun existingFileUpdateUsesExactEtagReturnedByGet() {
        MockWebDavServer(
            initialRemoteBody = documentWithFavorites("remote-existing"),
            initialRemoteEtag = "\"etag-existing-17\""
        ).use { server ->
            val local = testBundle("local-edit")
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "webdav-user", "webdav-secret", local)

            assertTrue("$result; requests=${server.requests.map { it.method to it.path }}", result is WebDavSyncResult.Success)
            assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PUT"), server.requests.map { it.method })
            val put = server.requests.single { it.method == "PUT" }
            assertEquals("\"etag-existing-17\"", put.ifMatch)
            assertEquals(null, put.ifNoneMatch)
            val writtenFavorites = JSONObject(put.body).getJSONObject("user").getJSONArray("favs").toString()
            assertTrue(writtenFavorites.contains("remote-existing"))
            assertTrue(writtenFavorites.contains("local-edit"))
        }
    }

    @Test
    fun conflictRefetchWithoutEtagDoesNotRetryWrite() {
        val concurrentRemote = documentWithFavorites("remote-existing", "concurrent-edit")
        MockWebDavServer(
            initialRemoteBody = documentWithFavorites("remote-existing"),
            initialRemoteEtag = "\"etag-before-race\"",
            conflictStatus = 412,
            concurrentRemoteBody = concurrentRemote,
            refreshedRemoteEtag = null
        ).use { server ->
            val local = testBundle("local-edit")
            val localBefore = local.toCloudJson(includeAvatar = false, includeHistory = true).toString()
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "mock-user", "mock-password", local)

            assertTrue("$result", result is WebDavSyncResult.Failure)
            assertTrue((result as WebDavSyncResult.Failure).message.contains("ETag"))
            assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PUT", "GET", "PROPFIND"), server.requests.map { it.method })
            assertEquals(concurrentRemote, server.remoteBody)
            assertEquals(localBefore, local.toCloudJson(includeAvatar = false, includeHistory = true).toString())
        }
    }

    @Test
    fun conflictResponsesRefetchMergeAndRetryWithRefreshedEtag() {
        listOf(409, 412).forEach { conflictStatus ->
            MockWebDavServer(
                initialRemoteBody = documentWithFavorites("existing-remote"),
                initialRemoteEtag = "\"etag-before-race\"",
                conflictStatus = conflictStatus,
                concurrentRemoteBody = documentWithFavorites("existing-remote", "concurrent-remote-edit"),
                refreshedRemoteEtag = "\"etag-after-race\""
            ).use { server ->
                val local = testBundle("local-edit")
                val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "webdav-user", "webdav-secret", local)

                assertTrue("HTTP $conflictStatus: $result", result is WebDavSyncResult.Success)
                assertEquals(
                    if (conflictStatus == 409) listOf("PROPFIND", "PROPFIND", "GET", "PUT", "PROPFIND", "GET", "PUT") else listOf("PROPFIND", "PROPFIND", "GET", "PUT", "GET", "PUT"),
                    server.requests.map { it.method }
                )
                val puts = server.requests.filter { it.method == "PUT" }
                assertEquals(2, puts.size)
                assertEquals("\"etag-before-race\"", puts[0].ifMatch)
                assertEquals("\"etag-after-race\"", puts[1].ifMatch)
                assertEquals(listOf("GET", "GET"), server.requests.filter { it.method == "GET" }.map { it.method })

                val firstAttemptFavorites = JSONObject(puts[0].body).getJSONObject("user").getJSONArray("favs").toString()
                assertTrue(firstAttemptFavorites.contains("local-edit"))
                assertFalse(firstAttemptFavorites.contains("concurrent-remote-edit"))
                val retriedFavorites = JSONObject(puts[1].body).getJSONObject("user").getJSONArray("favs").toString()
                assertTrue(retriedFavorites.contains("local-edit"))
                assertTrue(retriedFavorites.contains("concurrent-remote-edit"))
                assertTrue(server.remoteBody.orEmpty().contains("concurrent-remote-edit"))
                assertTrue(server.remoteBody.orEmpty().contains("local-edit"))
            }
        }
    }

    @Test
    fun syncMergesRemoteFavoritesAndPreservesLocalTombstonesOrderAndPlatformPlayCount() {
        val removedFavorite = Song(id = "removed-favorite", name = "Removed", singer = "Artist", source = "wy", syncAddedAt = 100L)
        val remoteFavorite = Song(id = "remote-favorite", name = "Remote", singer = "Artist", source = "wy", syncAddedAt = 900L)
        val removedPlaylistSong = Song(id = "removed-playlist-song", name = "Old", singer = "Artist", source = "wy", syncAddedAt = 110L)
        val remotePlaylist = Playlist(
            id = "mix",
            name = "Remote mix",
            local = true,
            songs = mutableListOf(removedPlaylistSong),
            hidden = true,
            count = 1,
            playCount = 321L,
                        syncCreatedAt = 50L,
            syncOrder = 1,
            syncOrderAt = 600L
        )
        val remoteBundle = LocalStore.UserBundle(
            favs = mutableListOf(removedFavorite, remoteFavorite),
            playlists = mutableListOf(remotePlaylist)
        )
        val remoteUser = remoteBundle.toCloudJson(includeAvatar = false).apply {
            remove("enabledScripts")
            remove("scriptOrder")
        }
        val remoteDoc = JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 1)
            .put("user", remoteUser)
            .toString()
        MockWebDavServer(remoteDoc).use { server ->
            val localPlaylist = Playlist(
                id = "mix",
                name = "Local mix",
                local = true,
                songs = mutableListOf(Song(id = "local-song", name = "Local", singer = "Artist", source = "wy", syncAddedAt = 800L)),
                syncOrder = 0,
                syncOrderAt = 700L,
                            )
            val local = LocalStore.UserBundle(
                favs = mutableListOf(Song(id = "local-favorite", name = "Local", singer = "Artist", source = "wy", syncAddedAt = 800L)),
                playlists = mutableListOf(localPlaylist),
                deletedFavoriteKeys = linkedMapOf(removedFavorite.key() to 200L),
                deletedPlaylistIds = linkedMapOf("deleted-playlist" to 300L),
                deletedPlaylistSongKeys = linkedMapOf("mix" to linkedMapOf(removedPlaylistSong.key() to 250L))
            )
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "user", "password", local)

            assertTrue("$result; requests=${server.requests.map { it.method to it.path }}", result is WebDavSyncResult.Success)
            val bundle = (result as WebDavSyncResult.Success).bundle
            assertFalse(bundle.favs.any { it.key() == removedFavorite.key() })
            assertTrue(bundle.favs.any { it.id == "local-favorite" })
            assertTrue(bundle.favs.any { it.id == "remote-favorite" })
            assertEquals(200L, bundle.deletedFavoriteKeys[removedFavorite.key()])
            val mergedPlaylist = bundle.playlists.first { it.id == "mix" }
            assertEquals(listOf("local-song"), mergedPlaylist.songs.map { it.id })
            assertTrue(mergedPlaylist.hidden)
            assertEquals(321L, mergedPlaylist.playCount)
            assertEquals(0, mergedPlaylist.syncOrder)
            assertEquals(700L, mergedPlaylist.syncOrderAt)
            assertEquals(300L, bundle.deletedPlaylistIds["deleted-playlist"])
            assertEquals(250L, bundle.deletedPlaylistSongKeys["mix"]?.get(removedPlaylistSong.key()))

            val written = JSONObject(server.remoteBody ?: error("merged snapshot was not uploaded"))
                .getJSONObject("user")
            val writtenPlaylist = written.getJSONArray("pls").let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.first { it.optString("id") == "mix" }
            }
            assertEquals(321L, writtenPlaylist.getLong("playCount"))
            assertFalse(writtenPlaylist.has("userPlayCount"))
            assertEquals(0, writtenPlaylist.getInt("_swLibOrder"))
            assertEquals(700L, writtenPlaylist.getLong("_swLibOrderAt"))
            assertTrue(written.getJSONArray("favs").toString().contains("_swDeletedFavorites"))
            assertTrue(written.getJSONArray("pls").toString().contains("_swDeletedPlaylists"))
            assertTrue(written.getJSONArray("pls").toString().contains("_swDeletedPlaylistSongs"))
        }
    }

    @Test
    fun webDavRoundTripsRequestedUserDataWithoutSharingDeviceLocalUris() {
        val favorite = Song(
            id = "favorite-local",
            name = "Favorite local track",
            singer = "Artist",
            source = "local",
            cover = "https://images.example.test/favorite.jpg",
            resolvedUrl = "file:///mock-device/Music/favorite.mp3"
        )
        val localTrack = Song(
            id = "playlist-local",
            name = "Local playlist track",
            singer = "Artist",
            source = "local",
            cover = "https://images.example.test/local.jpg",
            resolvedUrl = "/mock-device/Music/playlist-track.mp3"
        )
        val historyTrack = Song("history-track", "History track", "Artist", "wy", playedAt = System.currentTimeMillis())
        val bundle = LocalStore.UserBundle(
            favs = mutableListOf(favorite),
            playlists = mutableListOf(
                Playlist(
                    id = "liked",
                    name = "我的喜欢",
                    cover = "https://images.example.test/liked-cover.jpg",
                    songs = mutableListOf(favorite),
                    local = true
                ),
                Playlist(
                    id = "local",
                    name = "本地歌单",
                    cover = "https://images.example.test/local-playlist-cover.jpg",
                    songs = mutableListOf(localTrack),
                    local = true,
                                    ),
                Playlist(
                    id = "mix",
                    name = "My mix",
                    cover = "https://images.example.test/mix-cover.jpg",
                    songs = mutableListOf(Song("mix-track", "Mix track", "Artist", "wy")),
                    local = true,
                    playCount = 321L,
                                        syncOrder = 0,
                    syncOrderAt = 900L
                )
            ),
            history = mutableListOf(historyTrack),
            avatarB64 = "mock-avatar-must-not-go-to-webdav",
            songPlayCountCounters = linkedMapOf("local:favorite-local" to linkedMapOf("mock-install" to 4L)),
            dailyListenStatsJson = ListenStatsDailyStore.record(
                "{}", favorite, "00000000-0000-0000-0000-000000000003",
                seconds = 75L, incrementCount = true, nowMs = System.currentTimeMillis()
            ),
            enabledScripts = mutableListOf("mock-private-script"),
            scriptOrder = mutableListOf("mock-private-script")
        )

        MockWebDavServer().use { server ->
            val result = loopbackWebDavTestClient().syncVerifiedEmpty(server.url, "mock-user", "mock-password", bundle)
            assertTrue("$result", result is WebDavSyncResult.Success)
            val success = result as WebDavSyncResult.Success
            val remoteUser = JSONObject(server.remoteBody ?: error("mock server did not receive PUT"))
                .getJSONObject("user")
            val favorites = remoteUser.getJSONArray("favs")
            assertEquals("favorite-local", favorites.getJSONObject(0).getString("id"))
            assertFalse(favorites.getJSONObject(0).has("resolvedUrl"))

            val playlists = remoteUser.getJSONArray("pls")
            fun playlistJson(id: String): JSONObject = (0 until playlists.length())
                .mapNotNull { playlists.optJSONObject(it) }
                .first { it.optString("id") == id }
            val liked = playlistJson("liked")
            assertEquals("https://images.example.test/liked-cover.jpg", liked.getString("cover"))
            val localPlaylist = playlistJson("local")
            assertEquals("https://images.example.test/local-playlist-cover.jpg", localPlaylist.getString("cover"))
            assertEquals(0, localPlaylist.getJSONArray("songs").length())
            assertEquals(0, localPlaylist.getInt("count"))
            val mix = playlistJson("mix")
            assertEquals(321L, mix.getLong("playCount"))
            assertFalse(mix.has("userPlayCount"))
            assertEquals(0, mix.getInt("_swLibOrder"))
            assertEquals(900L, mix.getLong("_swLibOrderAt"))
            assertEquals("history-track", remoteUser.getJSONArray("history").getJSONObject(0).getString("id"))
            assertFalse(remoteUser.has("playCountCounters"))
            val datedStats = remoteUser.getJSONObject("dailyListenStats")
            val todayStats = datedStats.getJSONObject(ListeningCalendar.dayKey(System.currentTimeMillis()))
            assertEquals(75L, todayStats.getJSONObject("songs").getJSONObject("local:favorite-local")
                .getJSONObject("devices").getJSONObject("00000000-0000-0000-0000-000000000003").getLong("sec"))
            assertFalse(remoteUser.has("avatar"))
            assertFalse(remoteUser.has("enabledScripts"))
            assertFalse(remoteUser.has("scriptOrder"))

            assertEquals("file:///mock-device/Music/favorite.mp3", success.bundle.favs.single().resolvedUrl)
            assertEquals("/mock-device/Music/playlist-track.mp3", success.bundle.playlists
                .first { it.id == "local" }.songs.single().resolvedUrl)
        }
    }

    @Test
    fun productionWebDavRequiresHttpsBeforeSendingAnyCredential() {
        val requests = mutableListOf<WebDavHttpRequest>()
        val client = WebDavSyncClient(WebDavTransport { request ->
            requests += request
            WebDavHttpResponse(207)
        })
        val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }

        assertNotNull(normalizeWebDavBaseUrl("https://dav.example/dav"))
        assertNotNull(normalizeWebDavBaseUrl("https://dav.example:65535/dav"))
        assertNull(normalizeWebDavBaseUrl("https://dav.example:0/dav"))
        assertNull(normalizeWebDavBaseUrl("https://dav.example:65536/dav"))
        assertNull(normalizeWebDavBaseUrl("http://dav.example/dav"))
        assertNull(normalizeWebDavBaseUrl("http://127.0.0.1:1234/dav"))
        assertNotNull(normalizeWebDavBaseUrl("http://127.0.0.1:1234/dav", allowLoopbackHttpForTests = true))

        val connectionError = client.testConnection("http://dav.example/dav", "user", "password")
        assertTrue(connectionError.orEmpty().contains("HTTPS"))
        val result = client.sync(
            url = "http://dav.example/dav",
            username = "user",
            password = "password",
            local = testBundle("local-edit"),
            gateStateProvider = gate
        )
        assertTrue(result is WebDavSyncResult.Failure)
        assertTrue((result as WebDavSyncResult.Failure).message.contains("HTTPS"))
        assertTrue("cleartext HTTP must not reach the transport", requests.isEmpty())
    }

    @Test
    fun invalidAddressAndAuthenticationFailureAreSafe() {
        MockWebDavServer(rejectAuthorization = true).use { server ->
            val invalid = loopbackWebDavTestClient().testConnection(
                "https://user:pass@example.test/dav", "user", "secret",
                gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
            )
            assertNotNull(invalid)
            assertTrue(server.requests.isEmpty())

            val error = loopbackWebDavTestClient().testConnection(
                server.url, "bad-user", "private-password",
                gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
            )
            assertNotNull(error)
            assertTrue("$error; requests=${server.requests.map { it.method to it.path }}", error!!.contains("登录失败"))
            assertFalse(error.contains("private-password"))
            assertFalse(error.contains("bad-user"))
            assertEquals(listOf("PROPFIND"), server.requests.map { it.method })
        }
    }

    private fun loopbackWebDavTestClient(): WebDavSyncClient =
        WebDavSyncClient(allowLoopbackHttpForTests = true)

    private class MockWebDavServer(
        initialRemoteBody: String? = null,
        private val initialRemoteEtag: String? = "\"mock-v1\"",
        private val rejectAuthorization: Boolean = false,
        private val propfindStatus: Int = 207,
        private val putStatus: Int = 201,
        private val conflictStatus: Int? = null,
        private val concurrentRemoteBody: String? = null,
        private val refreshedRemoteEtag: String? = "\"mock-v2\""
    ) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        private val executor = Executors.newSingleThreadExecutor()
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        @Volatile var remoteBody: String? = initialRemoteBody
            private set
        @Volatile private var remoteEtag: String? = initialRemoteEtag
        private var conflictInjected = false
        val url: String = "http://127.0.0.1:${server.localPort}/dav/collection"

        init {
            executor.submit {
                while (running.get()) {
                    try {
                        server.accept().use(::handle)
                    } catch (_: Exception) {
                        if (running.get()) continue
                    }
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val first = readLine(input) ?: return
            val firstParts = first.split(' ')
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val sep = line.indexOf(':')
                if (sep > 0) headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            val method = firstParts.getOrNull(0).orEmpty()
            val request = CapturedRequest(
                method,
                firstParts.getOrNull(1).orEmpty(),
                headers["authorization"],
                headers["if-none-match"],
                headers["if-match"],
                String(body, StandardCharsets.UTF_8)
            )
            requests.add(request)

            val response = when {
                rejectAuthorization -> 401 to ""
                method == "PROPFIND" -> propfindStatus to "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"/>"
                method == "GET" && remoteBody == null -> 404 to ""
                method == "GET" -> 200 to remoteBody.orEmpty()
                method == "PUT" -> {
                    if (!conflictInjected && conflictStatus in setOf(409, 412)) {
                        conflictInjected = true
                        remoteBody = concurrentRemoteBody ?: remoteBody
                        remoteEtag = refreshedRemoteEtag
                        conflictStatus!! to ""
                    } else {
                        if (putStatus in setOf(200, 201, 204)) remoteBody = request.body
                        putStatus to ""
                    }
                }
                else -> 405 to ""
            }
            val bytes = response.second.toByteArray(StandardCharsets.UTF_8)
            val writer = socket.getOutputStream()
            val etagHeader = if (method == "GET" && response.first == 200) remoteEtag?.let { "ETag: $it\r\n" }.orEmpty() else ""
            writer.write(("HTTP/1.1 ${response.first} ${reason(response.first)}\r\n" +
                "Content-Type: application/octet-stream\r\n" + etagHeader +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            writer.write(bytes)
            writer.flush()
        }

        private fun readLine(input: java.io.InputStream): String? {
            val out = ByteArrayOutputStream()
            var previous = -1
            while (true) {
                val next = input.read()
                if (next < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.UTF_8.name())
                if (previous == '\r'.code && next == '\n'.code) return out.toString(StandardCharsets.UTF_8.name())
                if (next != '\r'.code) out.write(next)
                previous = next
            }
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            207 -> "Multi-Status"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            412 -> "Precondition Failed"
            else -> "Method Not Allowed"
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun testBundle(vararg favoriteIds: String): LocalStore.UserBundle = LocalStore.UserBundle(
        favs = favoriteIds.mapIndexed { index, id ->
            Song(id = id, name = id, singer = "Artist", source = "wy", syncAddedAt = (index + 1) * 100L)
        }.toMutableList()
    )

    private fun WebDavSyncClient.syncVerifiedEmpty(
        url: String,
        username: String,
        password: String,
        local: LocalStore.UserBundle
    ): WebDavSyncResult = sync(
        url = url,
        username = username,
        password = password,
        local = local,
        gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
    )

    private class FakeWebDavPreferences(
        gateState: LegacyUnassignedSnapshot.AccountSyncGateState,
        credentials: WebDavSavedCredentials?
    ) {
        private val values = mutableMapOf<String, Any>("snapshot_gate" to gateState).apply {
            credentials?.let { put("webdav_credentials", it) }
        }
        var gateReads: Int = 0
            private set
        var credentialReads: Int = 0
            private set
        var lastGateState: LegacyUnassignedSnapshot.AccountSyncGateState = gateState
            private set

        fun readGateState(): LegacyUnassignedSnapshot.AccountSyncGateState {
            gateReads++
            lastGateState = values["snapshot_gate"] as? LegacyUnassignedSnapshot.AccountSyncGateState
                ?: LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN
            return lastGateState
        }

        fun readCredentials(): WebDavSavedCredentials? {
            credentialReads++
            return values["webdav_credentials"] as? WebDavSavedCredentials
        }
    }

    private class RecordingWebDavTransport(initialRemoteBody: String?) : WebDavTransport {
        val requests = mutableListOf<WebDavHttpRequest>()
        var remoteBody: String? = initialRemoteBody
            private set

        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
            requests += request
            return when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "GET" -> remoteBody?.let { WebDavHttpResponse(200, it, "\"fake-v1\"") }
                    ?: WebDavHttpResponse(404)
                "PUT" -> {
                    remoteBody = request.body?.toString(StandardCharsets.UTF_8)
                    WebDavHttpResponse(201)
                }
                else -> WebDavHttpResponse(405)
            }
        }
    }

    private fun documentWithFavorites(vararg favoriteIds: String): String {
        val user = testBundle(*favoriteIds).toCloudJson(includeAvatar = false).apply {
            remove("enabledScripts")
            remove("scriptOrder")
        }
        return JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 1)
            .put("user", user)
            .toString()
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val ifNoneMatch: String?,
        val ifMatch: String?,
        val body: String
    )
}
