package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDataUrlMemoKeysTest {
    @Test
    fun largeUnicodePayloadHasStableMemoKeyAcrossChunks() {
        val value = "data:image/jpeg;base64," + "音🎧".repeat(4096)
        val keys = CoverDataUrlMemoKeys()
        assertEquals(keys.key(value), keys.key(String(value.toCharArray())))
    }

    @Test
    fun collidingStringHashCodesProduceDistinctMemoKeys() {
        val first = "data:image/png;base64,Aa"
        val second = "data:image/png;base64,BB"
        assertEquals(first.length, second.length)
        assertEquals(first.hashCode(), second.hashCode())

        val keys = CoverDataUrlMemoKeys()
        assertNotEquals(keys.key(first), keys.key(second))
        assertEquals(keys.key(first), keys.key(String(first.toCharArray())))
        assertTrue(keys.key(first).startsWith("data#${first.length}#"))
        assertTrue(!keys.key(first).contains("base64"))
    }
}
