package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResumePolicyTest {
    private val remote = DownloadResumePolicy.Fingerprint(10_000_000L, "\"etag-a\"", "a".repeat(64))

    @Test
    fun resumesOnlyWhenTheStoredFingerprintMatchesTheRemoteFile() {
        assertEquals(4_000_000L, DownloadResumePolicy.resumeOffset(4_000_000L, remote, remote))
    }

    @Test
    fun partFromAnotherSourceOrQualityIsNeverAppendedTo() {
        val otherFile = remote.copy(validator = "\"etag-b\"")
        assertEquals(0L, DownloadResumePolicy.resumeOffset(4_000_000L, otherFile, remote))
        val otherLength = remote.copy(total = 9_000_000L)
        assertEquals(0L, DownloadResumePolicy.resumeOffset(4_000_000L, otherLength, remote))
    }

    @Test
    fun legacyPartWithoutMetadataOrWithoutValidatorRestartsFromZero() {
        assertEquals(0L, DownloadResumePolicy.resumeOffset(4_000_000L, null, remote))
        val noValidator = remote.copy(validator = "")
        assertEquals(0L, DownloadResumePolicy.resumeOffset(4_000_000L, noValidator, noValidator))
    }

    @Test
    fun completeOrOversizedPartRestarts() {
        assertEquals(0L, DownloadResumePolicy.resumeOffset(10_000_000L, remote, remote))
        assertEquals(0L, DownloadResumePolicy.resumeOffset(0L, remote, remote))
    }

    @Test
    fun fingerprintRoundTripsAndRejectsGarbage() {
        val fp = DownloadResumePolicy.Fingerprint(123L, "W/\"x\"", "a".repeat(64))
        assertEquals(fp, DownloadResumePolicy.decode(DownloadResumePolicy.encode(fp)))
        assertNull(DownloadResumePolicy.decode("not-a-number\nx"))
        assertNull(DownloadResumePolicy.decode(""))
    }

    @Test
    fun deadlineScalesWithFileSizeWithinBounds() {
        assertEquals(DownloadResumePolicy.MIN_DEADLINE_MS, DownloadResumePolicy.deadlineMs(-1L))
        assertEquals(DownloadResumePolicy.MIN_DEADLINE_MS, DownloadResumePolicy.deadlineMs(4L * 1024 * 1024))
        // 40 MiB flac @32 KiB/s ≈ 1280 s
        assertEquals(1_280_000L, DownloadResumePolicy.deadlineMs(40L * 1024 * 1024))
        assertEquals(DownloadResumePolicy.MAX_DEADLINE_MS, DownloadResumePolicy.deadlineMs(900L * 1024 * 1024))
    }
}
