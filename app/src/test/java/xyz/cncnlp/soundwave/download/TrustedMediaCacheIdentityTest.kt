package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedMediaCacheIdentityTest {
    private fun identity(
        owner: String = "profile-a:7",
        candidate: String = "wy:track-1|mid-a",
        platform: String = "wy",
        quality: String = "320k",
        script: String = "script-a",
        url: String = "https://cdn.example/audio.mp3?token=one",
        evidence: String = "206|audio/mpeg|123456|mp3|etag-a"
    ) = TrustedMediaCacheIdentity(
        owner = owner,
        candidate = candidate,
        platform = platform,
        actualQuality = quality,
        script = script,
        url = url,
        contentEvidence = evidence
    )

    @Test fun cacheKeyIsNotTheSongKeyAndChangesAcrossIdentityBoundaries() {
        val original = identity()
        val key = TrustedMediaCacheIdentity.cacheKey(original)
        assertTrue(key.startsWith("trusted-media-v1:"))
        assertNotEquals("wy:track-1", key)
        listOf(
            original.copy(owner = "profile-b:7"),
            original.copy(candidate = "wy:track-2|mid-b"),
            original.copy(platform = "kg"),
            original.copy(actualQuality = "128k"),
            original.copy(script = "script-b"),
            original.copy(contentEvidence = "206|audio/mpeg|123456|mp3|etag-b")
        ).forEach { changed ->
            assertNotEquals(key, TrustedMediaCacheIdentity.cacheKey(changed))
            assertFalse(TrustedMediaCacheIdentity.canReuse(original, changed))
        }
    }

    @Test fun signedUrlRotationCanReuseOnlyWhenStableContentEvidenceMatches() {
        val first = identity()
        val rotated = first.copy(url = "https://cdn.example/audio.mp3?token=rotated")
        assertTrue(TrustedMediaCacheIdentity.canReuse(first, rotated))
        assertEquals(TrustedMediaCacheIdentity.cacheKey(first), TrustedMediaCacheIdentity.cacheKey(rotated))
        assertFalse(TrustedMediaCacheIdentity.canReuse(first, rotated.copy(contentEvidence = "206|audio/mpeg|999|mp3|etag-other")))
    }

    @Test fun missingUrlOrContentEvidenceCannotAuthorizeReuse() {
        val first = identity()
        assertFalse(TrustedMediaCacheIdentity.canReuse(first.copy(url = ""), first))
        assertFalse(TrustedMediaCacheIdentity.canReuse(first, first.copy(url = "")))
        assertFalse(TrustedMediaCacheIdentity.canReuse(first.copy(contentEvidence = ""), first))
        assertFalse(TrustedMediaCacheIdentity.canReuse(first, first.copy(contentEvidence = "")))
    }

    @Test fun contentEvidenceIsDeterministicAndBindsMediaResponseFacts() {
        val evidence = TrustedMediaCacheIdentity.contentEvidence(206, "audio/mpeg", 123456L, "mp3", "etag-a")
        assertEquals("206|audio/mpeg|123456|mp3|etag-a", evidence)
        assertNotEquals(evidence, TrustedMediaCacheIdentity.contentEvidence(200, "audio/mpeg", 123456L, "mp3", "etag-a"))
        assertNotEquals(evidence, TrustedMediaCacheIdentity.contentEvidence(206, "text/html", 123456L, "mp3", "etag-a"))
    }
}
