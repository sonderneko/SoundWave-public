package xyz.cncnlp.soundwave.migration

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/** Authored before owner ticket integration; JVM execution deferred by authorization. */
class RuntimeBoundaryPolicyTest {
    @Test fun identicalOwnerAndGenerationMatch() {
        assertTrue(ProfileContext.legacy(9).matches(ProfileContext.legacy(9)))
    }

    @Test fun delayedLegacyGenerationCannotCommit() {
        assertFalse(ProfileContext.legacy(8).matches(ProfileContext.legacy(9)))
    }

    @Test fun negativeWorkerGenerationIsRejected() {
        assertNull(ProfileWorkIdentity.decode("legacy-unassigned", -1L))
    }

    @Test fun unknownWorkerOwnerNeverFallsBackToLegacy() {
        assertNull(ProfileWorkIdentity.decode("unknown", 0L))
    }

    @Test fun oldWorkerWithoutOwnerStaysInOriginalLegacyQueue() {
        val old = ProfileWorkIdentity.decode(null, 0L)
        assertTrue(old?.matches(ProfileContext.legacy(0)) == true)
    }

    @Test fun malformedSnapshotIsNotTechnicalMetadata() {
        assertFalse(LegacyUnassignedSnapshot.isVerifiedEmptySnapshot("{}"))
        assertFalse(LegacyUnassignedSnapshot.isVerifiedEmptySnapshot(JSONObject().put("presentKeys", "").toString()))
    }
}
