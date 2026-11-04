package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDecryptLimitPolicyTest {
    private val limit = DownloadDecryptLimitPolicy.MAX_DECRYPT_BYTES

    @Test
    fun `supported encrypted files at and below the limit are not rejected`() {
        assertFalse(DownloadDecryptLimitPolicy.exceedsLimit("ncm", limit))
        assertFalse(DownloadDecryptLimitPolicy.exceedsLimit("mflac", limit - 1))
    }

    @Test
    fun `supported encrypted files strictly above the limit are rejected`() {
        assertTrue(DownloadDecryptLimitPolicy.exceedsLimit("ncm", limit + 1))
        assertTrue(DownloadDecryptLimitPolicy.exceedsLimit("QMCFLAC", limit + 1))
    }

    @Test
    fun `plain or empty files are not rejected by encrypted media limit`() {
        assertFalse(DownloadDecryptLimitPolicy.exceedsLimit("flac", limit + 1))
        assertFalse(DownloadDecryptLimitPolicy.exceedsLimit("mp3", limit + 1))
        assertFalse(DownloadDecryptLimitPolicy.exceedsLimit("ncm", 0))
    }
}
