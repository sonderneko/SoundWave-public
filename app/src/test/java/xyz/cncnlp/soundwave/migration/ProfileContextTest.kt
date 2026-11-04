package xyz.cncnlp.soundwave.migration

import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.ProfileId

class ProfileContextTest {
    @Test fun legacyIsNeverEquivalentToAnExplicitProfile() {
        val legacy = ProfileContext.legacy(0L)
        val explicit = ProfileContext.recorded(ProfileId.of("legacy"), 0L)
        assertFalse(legacy.matches(explicit))
    }

    @Test fun oldGenerationIsRejectedEvenForSameOwner() {
        val id = ProfileId.of("local-import")
        assertFalse(ProfileContext.recorded(id, 1L).matches(ProfileContext.recorded(id, 2L)))
        assertTrue(ProfileContext.recorded(id, 1L).matches(ProfileContext.recorded(id, 1L)))
    }

    @Test fun twoProfileNamespacesNeverMatch() {
        assertFalse(ProfileContext.recorded(ProfileId.of("A"), 2L).matches(ProfileContext.recorded(ProfileId.of("B"), 2L)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeGenerationIsRejected() { ProfileContext.legacy(-1L) }
}
