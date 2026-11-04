package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavAvatarSync
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class WebDavAvatarSyncTest {
    private val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }

    private fun image(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, color: Int = 0xff336699.toInt()): String {
        val bitmap = Bitmap.createBitmap(12, 9, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(format, 92, it) }.toByteArray()
        bitmap.recycle()
        val mime = if (format == Bitmap.CompressFormat.JPEG) "jpeg" else "png"
        return "data:image/$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun key(name: String) = WebDavAvatarSync.stableAccountKey(name)

    private class FakeDav(var body: String? = null) : WebDavTransport {
        var legacyBody: String? = null
        var etag = 1
        var conflictBody: String? = null
        var conflictOnce = false
        var onGet: (() -> Unit)? = null
        var puts = 0
        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse = when (request.method) {
            "PROPFIND" -> WebDavHttpResponse(207)
            "MKCOL" -> WebDavHttpResponse(405)
            "GET" -> {
                onGet?.invoke()
                val current = if (request.url.contains("/SoundWave/")) body else legacyBody
                current?.let { WebDavHttpResponse(200, it, "\"v$etag\"") } ?: WebDavHttpResponse(404)
            }
            "PUT" -> {
                puts++
                if (conflictOnce) {
                    conflictOnce = false
                    body = conflictBody
                    etag++
                    WebDavHttpResponse(412)
                } else {
                    body = request.body!!.toString(StandardCharsets.UTF_8)
                    etag++
                    WebDavHttpResponse(201, etag = "\"v$etag\"")
                }
            }
            else -> WebDavHttpResponse(405)
        }
    }

    private fun sync(dav: FakeDav, avatar: WebDavAvatarSync? = null, bundle: LocalStore.UserBundle = LocalStore.UserBundle()) =
        WebDavSyncClient(dav, avatarSync = avatar).sync(
            "https://dav.example.test/dav", "dav-user", "secret", bundle, gateStateProvider = gate
        )

    private fun root(dav: FakeDav) = JSONObject(dav.body!!)

    @Test fun sameAccountRoundTripsOutsideUserBundle() {
        val dav = FakeDav()
        val localImage = image()
        val imported = mutableListOf<String>()
        val uploader = WebDavAvatarSync(key("Alice@example.test"), localImage, { true }, { imported += it })
        val first = sync(dav, uploader) as WebDavSyncResult.Success
        val doc = root(dav)
        assertEquals(localImage, doc.getJSONObject("accountAvatars").getString(key("Alice@example.test")))
        assertFalse(doc.getJSONObject("user").has("avatar"))
        assertTrue(first.bundle.avatarB64.isEmpty())
        assertEquals(localImage, first.avatarB64)
        assertTrue(imported.isEmpty())

        var applied = ""
        val downloader = WebDavAvatarSync(key("Alice@example.test"), "", { true }, { applied = it })
        val second = sync(dav, downloader) as WebDavSyncResult.Success
        assertEquals(localImage, second.avatarB64)
        assertTrue("the transport returns data but leaves UI-thread application to its caller", applied.isEmpty())
        downloader.apply(second.avatarB64)
        assertEquals(localImage, applied)
    }

    @Test fun accountsDoNotImportOrOverwriteEachOtherAndBothEntriesSurvive() {
        val dav = FakeDav()
        val avatarA = image(color = 0xffaa1100.toInt())
        val avatarB = image(Bitmap.CompressFormat.JPEG, 0xff0011aa.toInt())
        sync(dav, WebDavAvatarSync(key("A"), avatarA, { true }, {}))

        val bInitially = sync(dav, WebDavAvatarSync(key("B"), "", { true }, {})) as WebDavSyncResult.Success
        assertEquals("B cannot receive A's avatar", "", bInitially.avatarB64)
        sync(dav, WebDavAvatarSync(key("B"), avatarB, { true }, {}))
        val avatars = root(dav).getJSONObject("accountAvatars")
        assertEquals(avatarA, avatars.getString(key("A")))
        assertEquals(avatarB, avatars.getString(key("B")))

        val aRead = sync(dav, WebDavAvatarSync(key("A"), "", { true }, {})) as WebDavSyncResult.Success
        assertEquals(avatarA, aRead.avatarB64)
        assertEquals(avatarB, root(dav).getJSONObject("accountAvatars").getString(key("B")))
    }

    @Test fun caseOnlyAccountNamesNeverFallbackToOrMergeEachOthersAvatar() {
        val dav = FakeDav()
        val upper = image(color = 0xffaa1100.toInt())
        val lower = image(color = 0xff0011aa.toInt())
        sync(dav, WebDavAvatarSync(key("Alice"), upper, { true }, {}))
        val missing = sync(dav, WebDavAvatarSync(key("alice"), "", { true }, {})) as WebDavSyncResult.Success
        assertEquals("case-folded fallback would cross an unproven identity boundary", "", missing.avatarB64)
        sync(dav, WebDavAvatarSync(key("alice"), lower, { true }, {}))

        val entries = root(dav).getJSONObject("accountAvatars")
        assertEquals(2, entries.length())
        assertEquals(upper, entries.getString(key("Alice")))
        assertEquals(lower, entries.getString(key("alice")))
        listOf("Alice" to upper, "alice" to lower).forEach { (owner, expected) ->
            val result = sync(dav, WebDavAvatarSync(key(owner), "", { true }, {})) as WebDavSyncResult.Success
            assertEquals(expected, result.avatarB64)
        }
    }

    @Test fun noSessionDoesNotImportOrExportBundleAvatarAndNewDocumentHasNoAvatarField() {
        val dav = FakeDav()
        val legacyBundleAvatar = image()
        val first = sync(dav, null, LocalStore.UserBundle(avatarB64 = legacyBundleAvatar)) as WebDavSyncResult.Success
        assertEquals("", first.avatarB64)
        assertEquals("", first.bundle.avatarB64)
        assertFalse(root(dav).has("accountAvatars"))
        assertFalse(root(dav).getJSONObject("user").has("avatar"))

        val account = WebDavAvatarSync(key("A"), "", { false }, {})
        val remoteOnly = image(Bitmap.CompressFormat.JPEG)
        dav.body = root(dav).put("accountAvatars", JSONObject().put(key("A"), remoteOnly)).toString()
        val second = sync(dav, account) as WebDavSyncResult.Success
        assertEquals("", second.avatarB64)
        assertEquals(remoteOnly, root(dav).getJSONObject("accountAvatars").getString(key("A")))
    }

    @Test fun legacyUserAvatarWithoutBoundMetadataIsNeverImported() {
        val legacy = JSONObject().put("schema", "soundwave-webdav-sync").put("version", 1)
            .put("user", JSONObject().put("avatar", image())).toString()
        val dav = FakeDav(legacy)
        val result = sync(dav, WebDavAvatarSync(key("A"), "", { true }, {})) as WebDavSyncResult.Success
        assertEquals("", result.avatarB64)
        assertFalse(root(dav).has("accountAvatars"))
        assertFalse(root(dav).getJSONObject("user").has("avatar"))
    }

    @Test fun legacyRootRelocationPreservesAccountAvatarMetadata() {
        val legacyAvatar = image(Bitmap.CompressFormat.JPEG)
        val legacy = JSONObject().put("schema", "soundwave-webdav-sync").put("version", 1)
            .put("user", JSONObject()).put("accountAvatars", JSONObject().put(key("A"), legacyAvatar)).toString()
        val dav = FakeDav().apply { legacyBody = legacy }

        val result = sync(dav, WebDavAvatarSync(key("A"), "", { true }, {})) as WebDavSyncResult.Success

        assertTrue(result.migratedLegacyFile)
        assertEquals(legacyAvatar, result.avatarB64)
        assertEquals(legacyAvatar, root(dav).getJSONObject("accountAvatars").getString(key("A")))
    }

    @Test fun conflictRetryKeepsLatestOtherAccountAvatarEntries() {
        val a = image(color = 0xff111111.toInt())
        val b = image(Bitmap.CompressFormat.JPEG, 0xff222222.toInt())
        val local = image(color = 0xff333333.toInt())
        val seed = JSONObject().put("schema", "soundwave-webdav-sync").put("version", 1)
            .put("user", JSONObject()).put("accountAvatars", JSONObject().put(key("A"), a)).toString()
        val latest = JSONObject(seed).put("accountAvatars", JSONObject().put(key("A"), a).put(key("B"), b)).toString()
        val dav = FakeDav(seed).apply { conflictOnce = true; conflictBody = latest }
        val result = sync(dav, WebDavAvatarSync(key("C"), local, { true }, {})) as WebDavSyncResult.Success
        val avatars = root(dav).getJSONObject("accountAvatars")
        assertEquals(a, avatars.getString(key("A")))
        assertEquals(b, avatars.getString(key("B")))
        assertEquals(local, avatars.getString(key("C")))
        assertEquals(local, result.avatarB64)
        assertEquals(2, dav.puts)
    }

    @Test fun oversizedAccountMapSkipsExportAndRetainsAllExistingEntries() {
        val existing = JSONObject()
        repeat(128) { existing.put("opaque-$it", "preserve-$it") }
        val seed = JSONObject().put("schema", "soundwave-webdav-sync").put("version", 1)
            .put("user", JSONObject()).put("accountAvatars", existing).toString()
        val dav = FakeDav(seed)
        sync(dav, WebDavAvatarSync(key("new-account"), image(), { true }, {}))

        val after = root(dav).getJSONObject("accountAvatars")
        assertEquals(128, after.length())
        repeat(128) { assertEquals("preserve-$it", after.getString("opaque-$it")) }
        assertFalse(after.has(key("new-account")))
    }

    @Test fun expiredSessionBeforePutNeitherExportsNorReturnsAvatar() {
        val dav = FakeDav()
        var current = true
        dav.onGet = { current = false }
        val local = image()
        val result = sync(dav, WebDavAvatarSync(key("A"), local, { current }, {})) as WebDavSyncResult.Success
        assertEquals("", result.avatarB64)
        assertFalse(root(dav).has("accountAvatars"))
    }

    @Test fun malformedPathUrlOversizeAndTooLargeDimensionsAreRejected() {
        assertNull(WebDavAvatarSync.validatedImageDataUri("/tmp/avatar.jpg"))
        assertNull(WebDavAvatarSync.validatedImageDataUri("https://example.test/avatar.png"))
        assertNull(WebDavAvatarSync.validatedImageDataUri("data:image/png;base64,not-base64!"))
        val tooMuch = "data:image/png;base64," + "A".repeat(2_000_005)
        assertNull(WebDavAvatarSync.validatedImageDataUri(tooMuch))
        val largeBitmap = Bitmap.createBitmap(1025, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { largeBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        largeBitmap.recycle()
        val tooWide = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        assertNull(WebDavAvatarSync.validatedImageDataUri(tooWide))
        assertNotNull(WebDavAvatarSync.validatedImageDataUri(image()))
        assertNotNull(WebDavAvatarSync.validatedImageDataUri(image(Bitmap.CompressFormat.JPEG)))
    }

    @Test fun stableKeyTrimsButPreservesCaseAndDoesNotContainUsername() {
        assertEquals(key(" Alice "), key("Alice"))
        assertFalse(key("Alice").equals(key("alice"), ignoreCase = true))
        assertFalse(key("Alice").contains("Alice"))
    }
}
