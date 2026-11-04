package xyz.cncnlp.soundwave.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.Song

class PlatformLyricRoutingTest {
    @Test
    fun `all anonymous platform aliases resolve without accepting non platform sources`() {
        val aliases = mapOf(
            "wy" to "wy", "netease" to "wy",
            "kg" to "kg", "kugou" to "kg",
            "kw" to "kw", "kuwo" to "kw",
            "tx" to "tx", "qq" to "tx", "tencent" to "tx",
            "mg" to "mg", "migu" to "mg",
        )
        aliases.forEach { (alias, platform) ->
            assertEquals(platform, MusicSourceRegistry.lyricPlatformId(alias))
            assertEquals(platform, MusicSourceRegistry.lyricPlatformId(alias.uppercase()))
        }
        assertNull(MusicSourceRegistry.lyricPlatformId("meting"))
        assertNull(MusicSourceRegistry.lyricPlatformId("cloud"))
        assertNull(MusicSourceRegistry.lyricPlatformId(" netease "))
    }

    @Test
    fun `direct hit is attempted once and bypasses server fallback`() = runBlocking {
        val song = Song(id = "00123", name = "title", singer = "artist", source = "netease")
        var directCalls = 0
        var serverCalls = 0
        var directSong: Song? = null
        val yrc = "[0,100](0,100,0)词"

        val result = fetchPlatformLyric(
            song = song,
            direct = { request -> directCalls++; directSong = request; yrc },
            server = { serverCalls++; "" },
            pause = {},
        )

        assertEquals(yrc, result)
        assertEquals(1, directCalls)
        assertSame(song, directSong)
        assertEquals(0, serverCalls)
        assertEquals("netease:00123", song.key())
    }

    @Test
    fun `empty direct result has a strict four round server request ceiling`() = runBlocking {
        val song = Song(id = "007", name = "title", singer = "  ", source = "qq")
        var directCalls = 0
        val serverSongs = mutableListOf<Song>()
        val waits = mutableListOf<Long>()

        val result = fetchPlatformLyric(
            song = song,
            direct = { directCalls++; null },
            server = { request -> serverSongs += request; "" },
            pause = { waits += it },
        )

        assertNull(result)
        assertEquals(1, directCalls)
        assertEquals(8, serverSongs.size) // four rounds × (original + ID-present singer variant)
        assertEquals(listOf(220L, 440L, 660L), waits)
        assertTrue(serverSongs.all { it.id == "007" && it.source == "qq" && it.key() == song.key() })
        assertEquals(listOf("  ", "", "  ", "", "  ", "", "  ", ""), serverSongs.map { it.singer })
    }

    @Test
    fun `blank id skips singer variant while preserving four primary attempts`() = runBlocking {
        val song = Song(id = "", name = "title", singer = "", source = "tx")
        var calls = 0
        val result = fetchPlatformLyric(
            song = song,
            direct = { null },
            server = { request ->
                calls++
                assertEquals(song.source, request.source)
                assertEquals(song.id, request.id)
                ""
            },
            pause = {},
        )
        assertNull(result)
        assertEquals(4, calls)
    }

    @Test
    fun `successful server singer variant returns original provider text unchanged`() = runBlocking {
        val song = Song(id = "55", name = "title", singer = " ", source = "tencent")
        val requests = mutableListOf<Song>()
        val qrc = "[1000,1000]歌(0,500)词(500,500)"
        val result = fetchPlatformLyric(
            song = song,
            direct = { null },
            server = { request ->
                requests += request
                if (request.singer.isEmpty()) qrc else ""
            },
            pause = {},
        )
        assertEquals(qrc, result)
        assertEquals(2, requests.size)
        assertEquals("tencent", requests.last().source)
        assertEquals("55", requests.last().id)
        assertEquals(song.key(), requests.last().key())
    }

    @Test
    fun `direct cancellation propagates without server fallback`() = runBlocking {
        val song = Song(id = "cancel-direct", name = "title", singer = "artist", source = "wy")
        var serverCalls = 0
        var pauseCalls = 0
        var cancelled = false

        try {
            fetchPlatformLyric(
                song = song,
                direct = { throw CancellationException("lyric owner changed") },
                server = { serverCalls++; "" },
                pause = { pauseCalls++ },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, serverCalls)
        assertEquals(0, pauseCalls)
    }

    @Test
    fun `server cancellation stops retries and parameter variants`() = runBlocking {
        val song = Song(id = "cancel-server", name = "title", singer = "artist", source = "wy")
        var serverCalls = 0
        var pauseCalls = 0
        var cancelled = false

        try {
            fetchPlatformLyric(
                song = song,
                direct = { null },
                server = { serverCalls++; throw CancellationException("lyric owner changed") },
                pause = { pauseCalls++ },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(1, serverCalls)
        assertEquals(0, pauseCalls)
    }

    @Test
    fun `default fallback backoff suspends cancellably`() = runTest {
        val song = Song(id = "", name = "title", singer = "", source = "wy")
        val firstServerCall = CompletableDeferred<Unit>()
        var serverCalls = 0
        var completed = false

        val request = launch {
            fetchPlatformLyric(
                song = song,
                direct = { null },
                server = {
                    serverCalls++
                    firstServerCall.complete(Unit)
                    ""
                },
            )
            completed = true
        }

        yield()
        assertTrue(firstServerCall.isCompleted)
        assertEquals(1, serverCalls)
        assertTrue(request.isActive)
        request.cancelAndJoin()
        assertFalse(completed)
        assertEquals(1, serverCalls)
    }
}
