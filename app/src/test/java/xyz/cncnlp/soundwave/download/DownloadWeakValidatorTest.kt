package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r657 可靠性 F8：弱 ETag 不能证明同一实体，不得续传；强 ETag + 同身份仍可续传。 */
class DownloadWeakValidatorTest {
    private val id = "b".repeat(64)

    @Test
    fun weakEtagNeverAuthorizesAppend() {
        val weak = DownloadResumePolicy.Fingerprint(1000L, "W/\"1\"", id)
        assertEquals(0L, DownloadResumePolicy.resumeOffset(400L, weak, weak))
        val lower = DownloadResumePolicy.Fingerprint(1000L, "w/\"1\"", id)
        assertEquals(0L, DownloadResumePolicy.resumeOffset(400L, lower, lower))
    }

    @Test
    fun strongEtagWithSameIdentityResumesAndDifferentIdentityRestarts() {
        val strong = DownloadResumePolicy.Fingerprint(1000L, "\"1\"", id)
        assertEquals(400L, DownloadResumePolicy.resumeOffset(400L, strong, strong))
        val otherSource = strong.copy(identity = "c".repeat(64))
        assertEquals(0L, DownloadResumePolicy.resumeOffset(400L, strong, otherSource))
    }

    @Test
    fun weakDetectionIgnoresLeadingSpaceOnly() {
        assertTrue(DownloadResumePolicy.isWeakEtag(" W/\"x\""))
        assertFalse(DownloadResumePolicy.isWeakEtag("\"W/x\""))
        assertFalse(DownloadResumePolicy.isWeakEtag("Tue, 01 Oct 2026 00:00:00 GMT"))
    }
}
