package xyz.cncnlp.soundwave.cache

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicCacheFileTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test
    fun failedStagingWritePreservesThePreviousCompleteCacheFile() {
        val target = temporaryFolder.newFile("lyric.lrc").apply { writeText("previous-complete") }

        try {
            AtomicCacheFile.replace(target) { staging ->
                staging.writeText("partial-new-value")
                throw IOException("injected failure before commit")
            }
            throw AssertionError("expected staging failure")
        } catch (_: IOException) {
            // The old target must remain visible and no staging file may survive.
        }

        assertEquals("previous-complete", target.readText())
        assertEquals(listOf("lyric.lrc"), temporaryFolder.root.list()!!.toList())
    }

    @Test
    fun successfulReplacementCommitsTheWholeNewValueAndRemovesStaging() {
        val target = temporaryFolder.newFile("lyric.lrc").apply { writeText("old") }

        AtomicCacheFile.replace(target) { staging -> staging.writeText("complete-new-value") }

        assertEquals("complete-new-value", target.readText())
        assertTrue(target.isFile)
        assertFalse(temporaryFolder.root.list()!!.any { it.endsWith(".part") })
    }
}
