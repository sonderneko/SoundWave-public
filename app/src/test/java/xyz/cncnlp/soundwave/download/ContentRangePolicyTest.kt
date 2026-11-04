package xyz.cncnlp.soundwave.download

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentRangePolicyTest {
    @Test
    fun parsesOnlySatisfiableByteRangesWithKnownNonOverflowingTotal() {
        assertEquals(ContentRangePolicy.Range(400L, 999L, 1000L), ContentRangePolicy.parse("bytes 400-999/1000"))
        assertEquals(ContentRangePolicy.Range(0L, 0L, 1L), ContentRangePolicy.parse("BYTES 0-0/1"))
        listOf(null, "", "bytes */1000", "bytes 0-9/*", "items 0-9/10", "bytes -1-9/10",
            "bytes 9-0/10", "bytes 0-10/10", "bytes 0-0/0", "bytes 0-9/10; ignored",
            "bytes 0-9/9223372036854775808").forEach { assertNull("accepted $it", ContentRangePolicy.parse(it)) }
    }

    @Test
    fun validatesOpenEndedResumeAgainstStartTotalAndDeclaredBodyLength() {
        val range = ContentRangePolicy.requireSingle("bytes 400-999/1000", 400L, 1000L, 600L)
        assertEquals(600L, range.byteCount)
        ContentRangePolicy.requireBodyLength(range, 600L)
        listOf(null, "bytes 0-599/1000", "bytes 400-999/1001", "bytes 400-998/1000").forEach {
            assertThrows(IOException::class.java) { ContentRangePolicy.requireSingle(it, 400L, 1000L, 600L) }
        }
        assertThrows(IOException::class.java) { ContentRangePolicy.requireSingle("bytes 400-999/1000", 400L, 1000L, 599L) }
        assertThrows(IOException::class.java) { ContentRangePolicy.requireBodyLength(range, 599L) }
        assertThrows(IOException::class.java) { ContentRangePolicy.requireBodyLength(range, 601L) }
    }

    @Test
    fun unsolicited206MustCoverWholeEntityFromZeroEvenWithUnknownProbeSize() {
        val range = ContentRangePolicy.requireSingle("bytes 0-999/1000", 0L, -1L, -1L)
        assertEquals(1000L, range.total)
        assertThrows(IOException::class.java) { ContentRangePolicy.requireSingle("bytes 400-999/1000", 0L, -1L, 600L) }
        assertThrows(IOException::class.java) { ContentRangePolicy.requireSingle("bytes 0-599/1000", 0L, -1L, 600L) }
    }
}
