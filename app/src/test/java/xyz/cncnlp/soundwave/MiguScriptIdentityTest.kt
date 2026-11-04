package xyz.cncnlp.soundwave

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiguScriptIdentityTest {
    private val raw = """{"code":"000000","resource":[{"songId":"3790007","contentId":"600902000006889366","copyrightId":"60054701923","albumId":"8592"}]}"""
    @Test fun legacyMetadataEnrichmentIsSharedAndIdentityPreserving() = runBlocking {
        var requests = 0
        val cache = MiguMetadataCache(fetch = { requests++; raw })
        val old = Song("600902000006889366", "晴天", "周杰伦", "mg")
        repeat(3) { // playback/download/probe each get the same cached metadata
            val enriched = cache.prepare(old)
            assertEquals("3790007", enriched.miguSongId)
            assertEquals("60054701923", enriched.copyrightId)
            assertEquals("8592", enriched.albumId)
            assertEquals(old.key(), enriched.key())
        }
        assertEquals(1, requests)
    }

    @Test fun transportFailureIsNegativelyCachedWithoutInventingIds() = runBlocking {
        var requests = 0
        val cache = MiguMetadataCache(fetch = { requests++; throw java.io.IOException("offline") })
        val song = Song("content", "title", "artist", "mg", albumId = "existing", copyrightId = "rights")
        repeat(3) { assertEquals(song, cache.prepare(song)) }
        assertEquals(1, requests)
        assertEquals("", cache.prepare(song).miguSongId)
    }
    @Test fun mismatchedAmbiguousAndMalformedMetadataCannotReplaceIdentity() = runBlocking {
        val song = Song("600902000006889366", "晴天", "周杰伦", "mg", copyrightId = "other-rights")
        assertEquals(song, MiguMetadataCache(fetch = { raw }).prepare(song))
        val legacy = song.copy(copyrightId = "")
        val duplicate = JSONObject(raw).apply { val rows = getJSONArray("resource"); rows.put(rows.getJSONObject(0)) }.toString()
        for (response in listOf("not-json", "{}", raw.replace("3790007", ""), raw.replace("000000", "999999"), raw.replace("600902000006889366", "other-content"), duplicate)) {
            assertEquals(legacy, MiguMetadataCache(fetch = { response }).prepare(legacy))
        }
    }
    @Test fun cancellationPropagatesAndDoesNotPoisonCache() = runBlocking {
        var requests = 0
        val cache = MiguMetadataCache(fetch = { if (++requests == 1) throw kotlinx.coroutines.CancellationException("cancelled") else raw })
        val song = Song("600902000006889366", "晴天", "周杰伦", "mg")
        try { cache.prepare(song); fail("Cancellation swallowed") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("3790007", cache.prepare(song).miguSongId)
        assertEquals(2, requests)
    }
    @Test fun concurrentConsumersShareOneMetadataRequest() = runBlocking {
        var requests = 0
        val cache = MiguMetadataCache(fetch = { requests++; kotlinx.coroutines.delay(20); raw })
        val song = Song("600902000006889366", "晴天", "周杰伦", "mg")
        kotlinx.coroutines.coroutineScope {
            val consumers = List(8) { async { cache.prepare(song) } }
            consumers.forEach { assertEquals("3790007", it.await().miguSongId) }
        }
        assertEquals(1, requests)
    }
    @Test fun expiryAllowsRetryAndOtherProvidersNeverRequestMetadata() = runBlocking {
        var requests = 0
        var clock = 0L
        val cache = MiguMetadataCache(fetch = { requests++; "{}" }, now = { clock })
        val song = Song("content", "title", "artist", "mg")
        cache.prepare(song); cache.prepare(song); assertEquals(1, requests)
        clock = 60_001; cache.prepare(song); assertEquals(2, requests)
        cache.prepare(song.copy(source = "tx")); assertEquals(2, requests)
    }
    @Test fun capturedOfficialMetadataStillHasNoPlayableUrl() = runBlocking {
        val captured = checkNotNull(javaClass.getResourceAsStream("/migu-resourceinfo-qingtian.json")).bufferedReader().use { it.readText() }
        val song = Song("600902000006889366", "晴天", "周杰伦", "mg")
        assertEquals("3790007", MiguMetadataCache(fetch = { captured }).prepare(song).miguSongId)
        assertNull(xyz.cncnlp.soundwave.source.direct.parseMgResolve(captured))
    }
    @Test fun payloadUsesSeparateMiguIdentitiesAndRealAlbum() = runBlocking {
        val song = Song.fromJson(JSONObject("""{"id":"600902000006889366","src":"mg","name":"晴天","singer":"周杰伦","copyrightId":"60054701923","miguSongId":"3790007","albumId":"8592"}"""))
        // Exercise the exact production builder, including preparation, not a duplicate payload fixture.
        val method = UrlResolver::class.java.declaredMethods.first { it.name == "buildMusicInfo" }
        method.isAccessible = true
        val continuation = kotlin.coroutines.coroutineContext.let { context ->
            object : kotlin.coroutines.Continuation<JSONObject> {
                override val context = context
                override fun resumeWith(result: Result<JSONObject>) { result.getOrThrow() }
            }
        }
        val payload = (if (method.parameterCount == 1) method.invoke(UrlResolver, song) else method.invoke(UrlResolver, song, continuation)) as JSONObject
        assertEquals("3790007", payload.getString("songmid"))
        assertEquals("3790007", payload.getString("songId"))
        assertEquals(song.id, payload.getString("contentId"))
        assertEquals("60054701923", payload.getString("copyrightId"))
        assertEquals("8592", payload.getString("albumId"))
        assertEquals(song.toJson().getString("miguSongId"), "3790007")
        assertEquals("mg:600902000006889366", song.key())
    }
}
