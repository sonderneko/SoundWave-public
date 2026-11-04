package xyz.cncnlp.soundwave.sync

import org.junit.Assert.*
import org.junit.Test

class ProfileRemoteBindingPolicyTest {
    @Test fun defaultHasNoRemotePermission() {
        assertFalse(ProfileRemoteBindingPolicy.maySync(false, false, null))
    }
    @Test fun absentDataIsNotConsent() {
        assertFalse(ProfileRemoteBindingPolicy.maySync(true, false, "doc"))
    }
    @Test fun emptyDocumentIdIsRejected() {
        assertFalse(ProfileRemoteBindingPolicy.maySync(true, true, ""))
    }
    @Test fun recordedAndExplicitOptInRequired() {
        assertTrue(ProfileRemoteBindingPolicy.maySync(true, true, "0123456789abcdef0123456789abcdef"))
    }
}
