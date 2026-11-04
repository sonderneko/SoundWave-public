package xyz.cncnlp.soundwave

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PaidAlbumScriptMetadataTest {
    private fun payload(song: Song): JSONObject = runBlocking {
        val method = UrlResolver::class.java.declaredMethods.single { it.name == "buildMusicInfo" }
        method.isAccessible = true
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(UrlResolver, song, continuation)
            if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resumeWith(Result.success(result as JSONObject))
        }
    }

    @Test fun `album identity survives official parser and JSON to script payload`() {
        val raw = javaClass.getResource("/paid-wy-album.json")!!.readText()
        val song = xyz.cncnlp.soundwave.source.direct.parseWyAlbumDetail(raw).songs.single()
        val restored = Song.fromJson(song.toJson())
        assertEquals("404060139", restored.albumId)
        assertEquals("404060139", payload(restored).getString("albumId"))
    }

    @Test fun `official kg album metadata preserves distinct quality hashes and audio identity`() {
        val song = Song("0B183AB16D68361FAAAB44723C46EE1C", "Rise Without A Sound", "林俊杰", source = "kg", albumId = "210575933", hash = "0B183AB16D68361FAAAB44723C46EE1C", duration = 220000)
        val restored = Song.fromJson(song.toJson())
        val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> javaClass.getResource("/paid-kg-songs.json")!!.readText() })
        val p = runBlocking { cache.apply(restored, payload(restored.copy(albumId = "")).put("albumId", restored.albumId)) }
        assertEquals("1108659761", p.getString("songmid"))
        assertEquals("971872296", p.getString("album_audio_id"))
        assertEquals("8C6044C8A11492C391B420A5E4EA7932", p.getJSONObject("_types").getJSONObject("320k").getString("hash"))
        assertEquals("962C7904162897702DE6E49E43EA167F", p.getJSONObject("_types").getJSONObject("flac").getString("hash"))
        assertFalse(p.getJSONObject("_types").has("flac24bit"))
        assertEquals("03:40", p.getString("interval"))
        assertEquals(song.id, p.getString("id"))
    }

    @Test fun `qq album keeps album identity duration and media mid`() {
        val raw = """{"data":{"name":"Album","list":[{"songmid":"song","songname":"Name","albummid":"album","interval":220,"strMediaMid":"media"}]}}"""
        val song = xyz.cncnlp.soundwave.source.direct.parseTxAlbumDetail(raw, "fallback").songs.single()
        assertEquals("album", song.albumId)
        assertEquals(220000L, song.duration)
        assertEquals("03:40", payload(song).getString("interval"))
        assertEquals("media", payload(song).getString("strMediaMid"))
    }

    @Test fun `metadata cache coalesces consumers and expires without changing stable song`() = runBlocking {
        var calls = 0; var now = 0L
        val raw = javaClass.getResource("/paid-kg-songs.json")!!.readText()
        val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> calls++; raw }, now = { now })
        val song = Song("0B183AB16D68361FAAAB44723C46EE1C", "Track", "Singer", source = "kg", albumId = "210575933")
        val before = song.toJson().toString()
        kotlinx.coroutines.coroutineScope {
            val consumers = List(8) { async { cache.apply(song, JSONObject()) } }
            consumers.forEach { assertEquals("1108659761", it.await().getString("songmid")) }
        }
        assertEquals(1, calls)
        now = 1_800_001L
        cache.apply(song, JSONObject())
        assertEquals(2, calls)
        assertEquals(before, song.toJson().toString())
    }

    @Test fun `wrong album hash duplicate bad code and missing quality are never invented`() = runBlocking {
        val raw = javaClass.getResource("/paid-kg-songs.json")!!.readText()
        val song = Song("0B183AB16D68361FAAAB44723C46EE1C", "Track", "Singer", source = "kg", albumId = "210575933")
        val root = JSONObject(raw)
        val rows = root.getJSONObject("data").getJSONArray("info")
        val duplicate = JSONObject(raw).apply { getJSONObject("data").getJSONArray("info").put(JSONObject(rows.getJSONObject(0).toString())) }.toString()
        listOf(raw.replace("210575933", "999"), raw.replace(song.id, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), duplicate, raw.replace("\"status\":1", "\"status\":0"), "bad json").forEach { bad ->
            val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> bad })
            val info = JSONObject().put("id", song.id)
            assertEquals(info.toString(), cache.apply(song, info).toString())
        }
        var calls = 0
        val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> calls++; "bad json" })
        repeat(3) { cache.apply(song, JSONObject()) }
        assertEquals(1, calls)
    }

    @Test fun `cancelled metadata is propagated and not negatively cached`() = runBlocking {
        var calls = 0
        val raw = javaClass.getResource("/paid-kg-songs.json")!!.readText()
        val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> if (++calls == 1) throw kotlinx.coroutines.CancellationException("cancel") else raw })
        val song = Song("0B183AB16D68361FAAAB44723C46EE1C", "Track", "Singer", source = "kg", albumId = "210575933")
        try { cache.apply(song, JSONObject()); fail("must propagate cancellation") } catch (_: kotlinx.coroutines.CancellationException) {}
        assertEquals("1108659761", cache.apply(song, JSONObject()).getString("songmid"))
        assertEquals(2, calls)
    }

    @Test fun `kg album parser retains official duration through JSON`() {
        val detail = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail(javaClass.getResource("/paid-kg-album.json")!!.readText(), javaClass.getResource("/paid-kg-songs.json")!!.readText(), "210575933")
        val song = Song.fromJson(detail.songs.single().toJson())
        assertEquals(220000L, song.duration)
        assertEquals("210575933", song.albumId)
    }

    @Test fun `parsed kg metadata survives JSON and a cold cache without catalog network`() = runBlocking {
        val song = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail(javaClass.getResource("/paid-kg-album.json")!!.readText(), javaClass.getResource("/paid-kg-songs.json")!!.readText(), "210575933").songs.single()
        val json = song.toJson()
        assertTrue("official per-quality metadata must be persisted", json.has("kgAlbumMetadata"))
        val restored = Song.fromJson(JSONObject(json.toString()))
        var calls = 0
        val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> calls++; error("persisted metadata must not fetch") })
        val info = cache.apply(restored, JSONObject().put("id", restored.id))
        assertEquals(0, calls)
        assertEquals("1108659761", info.getString("songmid"))
        assertEquals("971872296", info.getString("album_audio_id"))
        assertEquals("8C6044C8A11492C391B420A5E4EA7932", info.getJSONObject("_types").getJSONObject("320k").getString("hash"))
        assertEquals(song.key(), restored.key())
        val built = payload(restored)
        assertEquals("1108659761", built.getString("songmid"))
        assertEquals("971872296", built.getString("album_audio_id"))
        assertEquals("03:40", built.getString("interval"))
    }

    @Test fun `failed catalog status cannot be persisted as trusted album metadata`() {
        val raw = JSONObject(javaClass.getResource("/paid-kg-songs.json")!!.readText()).put("status", 0).toString()
        val song = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail("{}", raw, "210575933").songs.single()
        assertFalse(song.toJson().has("kgAlbumMetadata"))
    }

    @Test fun `wrong requested album cannot persist metadata from another album`() {
        val raw = javaClass.getResource("/paid-kg-songs.json")!!.readText()
        val song = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail("{}", raw, "999").songs.single()
        assertFalse(song.toJson().has("kgAlbumMetadata"))
    }

    private fun persistedKg(): JSONObject {
        val song = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail("{}", javaClass.getResource("/paid-kg-songs.json")!!.readText(), "210575933").songs.single()
        return song.toJson()
    }

    @Test fun `foreign unknown nested secret and oversized metadata are ignored without losing song`() {
        val base = persistedKg()
        val good = base.getJSONObject("kgAlbumMetadata")
        val badValues = listOf<Any>(
            "{bad json", JSONObject.NULL,
            JSONObject(good.toString()).put("platform", "wy"),
            JSONObject(good.toString()).put("albumId", "999"),
            JSONObject(good.toString()).put("currentHash", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
            JSONObject(good.toString()).put("audioId", "https://attacker/audio"),
            JSONObject(good.toString()).put("albumAudioId", "token=secret"),
            JSONObject(good.toString()).put("url", "https://attacker/media"),
            JSONObject(good.toString()).put("cookie", "secret"),
            JSONObject(good.toString()).put("extra", "x".repeat(4097)),
            JSONObject(good.toString()).put("128k", JSONObject().put("hash", base.getString("hash")).put("bytes", 123).put("token", "secret")),
            JSONObject(good.toString()).put("version", 2)
        )
        badValues.forEach { bad ->
            val restored = Song.fromJson(JSONObject(base.toString()).put("kgAlbumMetadata", bad))
            assertNull(restored.kgAlbumMetadata)
            assertEquals(base.getString("id"), restored.id)
            assertEquals(base.getString("name"), restored.name)
            assertFalse(restored.toJson().has("kgAlbumMetadata"))
        }
    }

    @Test fun `metadata must remain bound when copied song changes platform album or hash`() = runBlocking {
        val song = Song.fromJson(persistedKg())
        listOf(song.copy(source = "wy"), song.copy(albumId = "999"), song.copy(hash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).forEach { changed ->
            assertFalse(changed.toJson().has("kgAlbumMetadata"))
            val info = PaidAlbumMetadataCache(fetchPage = { _, _ -> "{}" }).apply(changed, JSONObject())
            assertFalse(info.has("audio_id"))
        }
    }

    @Test fun `only positive integral sizes and real hashes are restored`() {
        val base = persistedKg()
        listOf<Any>(0, -1, 1.5, "1.5", "9223372036854775808", JSONObject.NULL).forEach { bytes ->
            val json = JSONObject(base.toString())
            json.getJSONObject("kgAlbumMetadata").getJSONObject("128k").put("bytes", bytes)
            assertNull(Song.fromJson(json).kgAlbumMetadata)
        }
        val json = JSONObject(base.toString())
        json.getJSONObject("kgAlbumMetadata").getJSONObject("320k").put("hash", "https://attacker")
        assertNull(Song.fromJson(json).kgAlbumMetadata)
    }

    @Test fun `catalog secrets and urls are never serialized or injected into payload`() = runBlocking {
        val root = JSONObject(javaClass.getResource("/paid-kg-songs.json")!!.readText())
        root.getJSONObject("data").getJSONArray("info").getJSONObject(0).put("cookie", "SENSITIVE").put("url", "https://attacker/media").put("token", "SENSITIVE")
        val song = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail("{}", root.toString(), "210575933").songs.single()
        assertFalse(song.toJson().toString().contains("SENSITIVE"))
        assertFalse(song.toJson().toString().contains("attacker"))
        val info = PaidAlbumMetadataCache(fetchPage = { _, _ -> error("no network") }).apply(Song.fromJson(song.toJson()), JSONObject())
        assertFalse(info.has("url")); assertFalse(info.has("cookie")); assertFalse(info.has("token"))
        assertEquals(3, info.getJSONArray("types").length())
        assertFalse(info.has("entitlement"))
    }

    @Test fun `legacy missing metadata keeps bounded recovery and successful cache hits avoid network`() = runBlocking {
        val json = persistedKg().apply { remove("kgAlbumMetadata") }
        val song = Song.fromJson(json)
        var calls = 0
        val cache = PaidAlbumMetadataCache(fetchPage = { _, _ -> calls++; javaClass.getResource("/paid-kg-songs.json")!!.readText() })
        repeat(3) { assertEquals("1108659761", cache.apply(song, JSONObject()).getString("audio_id")) }
        assertEquals(1, calls)
        var missingCalls = 0
        val missing = PaidAlbumMetadataCache(fetchPage = { _, _ ->
            missingCalls++
            val root = JSONObject(javaClass.getResource("/paid-kg-songs.json")!!.readText())
            val rows = root.getJSONObject("data").getJSONArray("info")
            val row = JSONObject(rows.getJSONObject(0).toString()).put("album_id", "999")
            root.getJSONObject("data").put("info", org.json.JSONArray().apply { repeat(30) { put(JSONObject(row.toString())) } })
            root.toString()
        })
        assertFalse(missing.apply(song, JSONObject()).has("audio_id"))
        assertEquals(4, missingCalls)
    }

    @Test fun `uppercase canonicalization and selected high hash round trip without changing stable identity`() = runBlocking {
        val base = persistedKg()
        val metadata = base.getJSONObject("kgAlbumMetadata")
        val selected = metadata.getJSONObject("320k").getString("hash").lowercase()
        base.put("hash", selected); metadata.put("currentHash", selected)
        val restored = Song.fromJson(base)
        assertNotNull(restored.kgAlbumMetadata)
        val info = PaidAlbumMetadataCache(fetchPage = { _, _ -> error("no network") }).apply(restored, JSONObject().put("id", restored.id))
        assertEquals(base.getString("id"), info.getString("id"))
        assertEquals("1108659761", info.getString("songmid"))
        assertEquals(selected.uppercase(), restored.toJson().getJSONObject("kgAlbumMetadata").getString("currentHash"))
    }

    @Test fun `duplicate album entries do not acquire persistent audio identity`() {
        val root = JSONObject(javaClass.getResource("/paid-kg-songs.json")!!.readText())
        val rows = root.getJSONObject("data").getJSONArray("info")
        rows.put(JSONObject(rows.getJSONObject(0).toString()))
        val songs = xyz.cncnlp.soundwave.source.direct.parseKgAlbumDetail("{}", root.toString(), "210575933").songs
        assertTrue(songs.isNotEmpty())
        songs.forEach { assertNull(it.kgAlbumMetadata) }
    }

    @Test fun `unknown single kg hash cannot claim all quality hashes`() {
        val p = payload(Song("ABC", "Track", "Singer", source = "kg", hash = "ABC"))
        assertFalse("a lone unknown hash is not evidence of FLAC", p.getJSONObject("_types").has("flac"))
        assertEquals("ABC", p.getString("hash"))
    }
    @Test fun `script interval uses milliseconds consistently for every song source`() {
        listOf("wy", "tx", "kw", "kg", "mg", "local").forEach { source ->
            val song = Song("duration-fixture", "Track", "Singer", source = source, duration = 220000,
                miguSongId = "duration-fixture", copyrightId = "copyright-fixture")
            assertEquals(source, "03:40", payload(song).getString("interval"))
            assertEquals(source, "", payload(song.copy(duration = 0)).getString("interval"))
        }
    }

}
