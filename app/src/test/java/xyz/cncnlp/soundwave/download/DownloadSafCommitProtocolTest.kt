package xyz.cncnlp.soundwave.download

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSafCommitProtocolTest {
    private class FakeProvider {
        val files = linkedMapOf<String, ByteArray>()
        val events = mutableListOf<String>()
        var recordUri: String? = null
        var oldDeleteSucceeds = true

        fun createUniqueTarget(): String {
            events += "create-new"
            val uri = "content://fake/new-${files.size}"
            files[uri] = byteArrayOf()
            return uri
        }

        fun persist(uri: String, succeeds: Boolean = true): Boolean {
            events += "persist-record"
            if (!succeeds) return false
            recordUri = uri
            return true
        }

        fun delete(uri: String): Boolean {
            events += "delete:$uri"
            return if (uri == "content://fake/old" && !oldDeleteSucceeds) false else files.remove(uri) != null
        }
    }

    @Test
    fun `successful replacement writes and commits unique target before deleting previous document`() {
        val provider = FakeProvider()
        provider.files["content://fake/old"] = "old-audio".toByteArray()

        val result = DownloadSafCommitProtocol.commit(
            createUniqueTarget = provider::createUniqueTarget,
            copyAndVerify = { uri, checkpoint ->
                provider.events += "copy-and-verify"
                checkpoint()
                provider.files[uri] = "new-audio".toByteArray()
                provider.files[uri]!!.size.toLong()
            },
            ensureActive = {},
            persist = { uri, _ -> provider.persist(uri) },
            deleteNewTarget = provider::delete,
            deletePreviousTarget = { provider.delete("content://fake/old") }
        )

        assertEquals("content://fake/new-1", result.target)
        assertEquals("new-audio", String(provider.files[result.target]!!))
        assertEquals(result.target, provider.recordUri)
        assertTrue(result.previousDeleted)
        assertFalse(provider.files.containsKey("content://fake/old"))
        assertEquals(
            listOf("create-new", "copy-and-verify", "persist-record", "delete:content://fake/old"),
            provider.events
        )
    }

    @Test
    fun `cancellation during copy deletes only the new partial and preserves previous document`() {
        val provider = FakeProvider()
        provider.files["content://fake/old"] = "old-audio".toByteArray()
        var checks = 0

        try {
            DownloadSafCommitProtocol.commit(
                createUniqueTarget = provider::createUniqueTarget,
                copyAndVerify = { uri, checkpoint ->
                    checkpoint()
                    provider.files[uri] = "partial".toByteArray()
                    checkpoint()
                    error("copy must stop at the cancelled checkpoint")
                },
                ensureActive = {
                    checks++
                    if (checks == 3) throw CancellationException("cancelled before commit")
                },
                persist = { uri, _ -> provider.persist(uri) },
                deleteNewTarget = provider::delete,
                deletePreviousTarget = { provider.delete("content://fake/old") }
            )
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the protocol removes only the incomplete target it created.
        }

        assertFalse(provider.files.containsKey("content://fake/new-1"))
        assertTrue(provider.files.containsKey("content://fake/old"))
        assertEquals(null, provider.recordUri)
        assertFalse(provider.events.any { it == "delete:content://fake/old" })
    }

    @Test
    fun `copy failure removes partial target and never attempts to replace old document`() {
        val provider = FakeProvider()
        provider.files["content://fake/old"] = "old-audio".toByteArray()

        try {
            DownloadSafCommitProtocol.commit(
                createUniqueTarget = provider::createUniqueTarget,
                copyAndVerify = { uri, _ ->
                    provider.files[uri] = "partial".toByteArray()
                    throw IOException("provider write failed")
                },
                ensureActive = {},
                persist = { uri, _ -> provider.persist(uri) },
                deleteNewTarget = provider::delete,
                deletePreviousTarget = { provider.delete("content://fake/old") }
            )
            throw AssertionError("expected copy failure")
        } catch (expected: IOException) {
            assertEquals("provider write failed", expected.message)
        }

        assertFalse(provider.files.containsKey("content://fake/new-1"))
        assertTrue(provider.files.containsKey("content://fake/old"))
        assertEquals(null, provider.recordUri)
        assertFalse(provider.events.any { it == "delete:content://fake/old" })
    }

    @Test
    fun `cancellation immediately before metadata commit removes new target and preserves previous document`() {
        val provider = FakeProvider()
        provider.files["content://fake/old"] = "old-audio".toByteArray()
        var checks = 0

        try {
            DownloadSafCommitProtocol.commit(
                createUniqueTarget = provider::createUniqueTarget,
                copyAndVerify = { uri, _ ->
                    provider.files[uri] = "complete-new-audio".toByteArray()
                    provider.files[uri]!!.size.toLong()
                },
                ensureActive = {
                    checks++
                    if (checks == 2) throw CancellationException("cancelled before metadata commit")
                },
                persist = { uri, _ -> provider.persist(uri) },
                deleteNewTarget = provider::delete,
                deletePreviousTarget = { provider.delete("content://fake/old") }
            )
            throw AssertionError("expected cancellation before metadata commit")
        } catch (_: CancellationException) {
            // Expected: the final cancellation checkpoint is before persistence and old deletion.
        }

        assertFalse(provider.files.containsKey("content://fake/new-1"))
        assertTrue(provider.files.containsKey("content://fake/old"))
        assertEquals(null, provider.recordUri)
        assertFalse(provider.events.any { it == "delete:content://fake/old" })
    }

    @Test
    fun `uncertain metadata persistence preserves both complete targets`() {
        val provider = FakeProvider()
        provider.files["content://fake/old"] = "old-audio".toByteArray()

        try {
            DownloadSafCommitProtocol.commit(
                createUniqueTarget = provider::createUniqueTarget,
                copyAndVerify = { uri, _ ->
                    provider.files[uri] = "new-audio".toByteArray()
                    provider.files[uri]!!.size.toLong()
                },
                ensureActive = {},
                persist = { uri, _ -> provider.persist(uri, succeeds = false) },
                deleteNewTarget = provider::delete,
                deletePreviousTarget = { provider.delete("content://fake/old") }
            )
            throw AssertionError("expected uncertain metadata failure")
        } catch (_: DownloadSafCommitProtocol.MetadataCommitException) {
            // A failed commit can leave in-memory preferences changed; keep both documents.
        }

        assertTrue(provider.files.containsKey("content://fake/new-1"))
        assertTrue(provider.files.containsKey("content://fake/old"))
        assertEquals(null, provider.recordUri)
        assertFalse(provider.events.any { it.startsWith("delete:") })
    }

    @Test
    fun `old document delete failure is reported while new committed document remains usable`() {
        val provider = FakeProvider()
        provider.files["content://fake/old"] = "old-audio".toByteArray()
        provider.oldDeleteSucceeds = false

        val result = DownloadSafCommitProtocol.commit(
            createUniqueTarget = provider::createUniqueTarget,
            copyAndVerify = { uri, _ ->
                provider.files[uri] = "new-audio".toByteArray()
                provider.files[uri]!!.size.toLong()
            },
            ensureActive = {},
            persist = { uri, _ -> provider.persist(uri) },
            deleteNewTarget = provider::delete,
            deletePreviousTarget = { provider.delete("content://fake/old") }
        )

        assertEquals(result.target, provider.recordUri)
        assertTrue(provider.files.containsKey("content://fake/new-1"))
        assertTrue(provider.files.containsKey("content://fake/old"))
        assertFalse(result.previousDeleted)
    }
}
