package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResumeIdentityTest {
    @Test fun boundMetadataResumesOnlyForTheSameIdentity() {
        val first = DownloadResumePolicy.decode("v2\n1000\n\"shared-etag\"\n" + "a".repeat(64))
        val other = DownloadResumePolicy.decode("v2\n1000\n\"shared-etag\"\n" + "b".repeat(64))
        org.junit.Assert.assertNotNull(first)
        org.junit.Assert.assertNotNull(other)
        assertEquals(400L, DownloadResumePolicy.resumeOffset(400, first, first!!))
        assertEquals(0L, DownloadResumePolicy.resumeOffset(400, first, other!!))
        assertEquals(first, DownloadResumePolicy.decode(DownloadResumePolicy.encode(first)))
    }

    @Test fun identityDigestBindsSourceQualityCandidateScriptOwnerAndUrl() {
        val method = DownloadResumePolicy::class.java.declaredMethods.firstOrNull { it.name == "identity" }
        org.junit.Assert.assertNotNull("production identity builder is missing", method)
        val fields = arrayOf("owner", "wy:1", "kg:2", "kg", "flac", "script", "https://cdn/audio?token=secret")
        fun digest(parts: Array<String>) = method!!.invoke(DownloadResumePolicy, *parts) as String
        val original = digest(fields)
        org.junit.Assert.assertTrue(original.matches(Regex("[0-9a-f]{64}")))
        fields.indices.forEach { index ->
            val changed = fields.copyOf().also { it[index] += "different" }
            org.junit.Assert.assertNotEquals("field $index must bind identity", original, digest(changed))
        }
        org.junit.Assert.assertNotEquals(digest(arrayOf("a:b", "c", "", "", "", "", "")),
            digest(arrayOf("a", "b:c", "", "", "", "", "")))
    }

    @Test fun legacyMetadataCannotProveSourceOrQuality() {
        val legacy = DownloadResumePolicy.decode("1000\n\"shared-etag\"")
        assertEquals(0L, DownloadResumePolicy.resumeOffset(400, legacy,
            DownloadResumePolicy.Fingerprint(1000, "\"shared-etag\"")))
        assertNull(legacy)
    }
}
