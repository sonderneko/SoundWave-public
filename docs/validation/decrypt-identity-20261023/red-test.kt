package xyz.cncnlp.soundwave

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileContext

/** Entirely synthetic ciphertext and decryptor; no account, server or real media access. */
@RunWith(RobolectricTestRunner::class)
class PlaybackDecryptIdentityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun result(): ResolveResult = ResolveResult(
        ticket = ResolveTicket(songKey = "wy:synthetic-${UUID.randomUUID()}", ownerContext = ProfileContext.legacy(1)),
        url = "https://media.invalid/object.mflac?token=SECRET_ONE",
        quality = "flac", platform = "tx", scriptId = "synthetic-script", candidateKey = "candidate-one"
    )
    private suspend fun prepare(result: ResolveResult, cipher: String, calls: MutableList<String> = mutableListOf()): MediaCacheLease =
        requireNotNull(MediaDecryptCache.prepareResolvedWithLease(
            context, result.url, result, "mflac", KeyedOperationLock(),
            { _, out -> calls += "download"; out.writeText(cipher); true },
            { input, output, _ -> calls += "decrypt"; output.writeText("plain:${input.readText()}"); "flac" }
        ))

    @Test fun `account generation switch cannot reuse same song and quality`() = runBlocking {
        val a = result()
        val b = a.copy(ticket = a.ticket.copy(ownerContext = ProfileContext.legacy(2)))
        val first = prepare(a, "same")
        val second = prepare(b, "same")
        try { assertNotEquals(first.path, second.path) } finally { first.close(); second.close() }
    }

    @Test fun `platform candidate script and actual quality each isolate plaintext`() = runBlocking {
        val a = result()
        val variants = listOf(a, a.copy(platform = "kg"), a.copy(candidateKey = "candidate-two"),
            a.copy(scriptId = "another-script"), a.copy(quality = "320k"))
        val leases = variants.map { prepare(it, "same") }
        try { assertEquals(variants.size, leases.map { it.path }.distinct().size) }
        finally { leases.forEach { it.close() } }
    }

    @Test fun `same song quality and URL with changed equal-size ciphertext cannot hit old plaintext`() = runBlocking {
        val a = result()
        val first = prepare(a, "AAAA")
        val second = prepare(a, "BBBB")
        try {
            assertNotEquals(first.path, second.path)
            assertEquals("plain:AAAA", first.file.readText())
            assertEquals("plain:BBBB", second.file.readText())
        } finally { first.close(); second.close() }
    }

    @Test fun `rotated signed URL reuses only byte-identical content without exposing credentials`() = runBlocking {
        val a = result()
        val calls = mutableListOf<String>()
        val first = prepare(a, "AAAA", calls)
        val second = prepare(a.copy(url = "https://other.invalid/rotated.mflac?token=SECRET_TWO"), "AAAA", calls)
        try {
            assertEquals(first.path, second.path)
            assertEquals(2, calls.count { it == "download" })
            assertEquals(1, calls.count { it == "decrypt" })
            assertTrue(first.file.name.matches(Regex("dec_[0-9a-f]{64}\\.flac")))
            assertFalse(first.path.contains("SECRET"))
            assertFalse(first.path.contains("candidate"))
        } finally { first.close(); second.close() }
    }

    @Test fun `incomplete provenance never reuses a previous plaintext`() = runBlocking {
        val a = result().copy(candidateKey = "")
        val first = prepare(a, "same")
        val second = prepare(a, "same")
        try { assertNotEquals(first.path, second.path) } finally { first.close(); second.close() }
    }

    @Test fun `clear preserves every active resource and missing or empty cache rebuilds`() = runBlocking {
        PlayerService.resetSimpleCacheForTests()
        val a = result()
        val first = prepare(a, "AAAA")
        val second = prepare(a, "BBBB")
        try {
            assertEquals(2, CacheManager.clearAll(context).skippedActiveFiles)
            assertTrue(first.file.exists()); assertTrue(second.file.exists())
            val removed = first.file
            first.close()
            CacheManager.clearAll(context)
            assertFalse(removed.exists()); assertTrue(second.file.exists())
            val calls = mutableListOf<String>()
            val rebuilt = prepare(a, "AAAA", calls)
            rebuilt.close()
            assertEquals(listOf("download", "decrypt"), calls)
            File(rebuilt.path).writeBytes(byteArrayOf())
            calls.clear()
            val repaired = prepare(a, "AAAA", calls)
            try {
                assertEquals("plain:AAAA", repaired.file.readText())
                assertEquals(listOf("download", "decrypt"), calls)
            } finally { repaired.close() }
        } finally {
            first.close(); second.close()
            CacheManager.clearAll(context)
            PlayerService.resetSimpleCacheForTests()
        }
    }

    @Test fun `failed revalidation cannot fall back to old plaintext or leak staging leases`() = runBlocking {
        val a = result()
        val old = prepare(a, "AAAA")
        try {
            val failed = MediaDecryptCache.prepareResolvedWithLease(
                context, a.url, a, "mflac", KeyedOperationLock(),
                { _, out -> out.writeText("partial"); false },
                { _, _, _ -> error("must not decrypt failed download") }
            )
            failed?.close()
            assertNull(failed)
            assertTrue(old.file.exists())
            assertEquals(setOf(old.path), MediaCacheLeaseManager.leasePathsUnder(context.cacheDir))
        } finally { old.close() }
    }
}
