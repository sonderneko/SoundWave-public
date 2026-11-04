package xyz.cncnlp.soundwave.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ProfileId

class ProfileSessionStartupGateTest {
    @Test fun verifiedLegacyRemainsExplicitlyUnassigned() {
        val decision = ProfileSessionStartupGate.validate(evidence(
            owner = PersistedProfileOwnerEvidence.LegacyUnassigned,
            directoryStatus = PersistedProfileDirectoryStatus.NOT_APPLICABLE
        ))

        assertTrue(decision is ProfileSessionStartupDecision.Ready)
        decision as ProfileSessionStartupDecision.Ready
        assertEquals(ProfileOwner.LegacyUnassigned, decision.context.owner)
        assertEquals(ProfileSessionSource.LEGACY_UNASSIGNED, decision.source)
        assertEquals(12L, decision.context.generation)
    }

    @Test fun recordedProfileRequiresExplicitSourceAndMatchingVerifiedDirectory() {
        val id = ProfileId.of("startup-imported-profile")
        val decision = ProfileSessionStartupGate.validate(evidence(
            owner = PersistedProfileOwnerEvidence.RecordedProfile(
                id,
                ProfileSessionSource.LOCAL_BACKUP_IMPORT
            ),
            directoryStatus = PersistedProfileDirectoryStatus.VERIFIED,
            directoryProfileId = id
        ))

        assertTrue(decision is ProfileSessionStartupDecision.Ready)
        decision as ProfileSessionStartupDecision.Ready
        assertEquals(ProfileOwner.RecordedProfile(id), decision.context.owner)
        assertEquals(ProfileSessionSource.LOCAL_BACKUP_IMPORT, decision.source)
    }

    @Test fun missingOrCorruptPersistedStateFailsClosed() {
        listOf(
            PersistedProfileStateStatus.MISSING to ProfileSessionStartupFailure.STATE_MISSING,
            PersistedProfileStateStatus.CORRUPT to ProfileSessionStartupFailure.STATE_CORRUPT,
            PersistedProfileStateStatus.UNKNOWN to ProfileSessionStartupFailure.STATE_UNVERIFIED
        ).forEach { (status, expected) ->
            val decision = ProfileSessionStartupGate.validate(evidence(
                owner = PersistedProfileOwnerEvidence.LegacyUnassigned,
                directoryStatus = PersistedProfileDirectoryStatus.NOT_APPLICABLE,
                stateStatus = status
            ))
            assertEquals(ProfileSessionStartupDecision.FailClosed(expected), decision)
        }
    }

    @Test fun missingOrCorruptProfileDirectoryNeverFallsBackToLegacy() {
        val id = ProfileId.of("startup-directory-required")
        listOf(
            PersistedProfileDirectoryStatus.MISSING to ProfileSessionStartupFailure.PROFILE_DIRECTORY_MISSING,
            PersistedProfileDirectoryStatus.CORRUPT to ProfileSessionStartupFailure.PROFILE_DIRECTORY_CORRUPT,
            PersistedProfileDirectoryStatus.UNKNOWN to ProfileSessionStartupFailure.PROFILE_DIRECTORY_UNVERIFIED,
            PersistedProfileDirectoryStatus.NOT_APPLICABLE to ProfileSessionStartupFailure.PROFILE_DIRECTORY_UNVERIFIED
        ).forEach { (status, expected) ->
            val decision = ProfileSessionStartupGate.validate(evidence(
                owner = PersistedProfileOwnerEvidence.RecordedProfile(
                    id,
                    ProfileSessionSource.SERVER_SNAPSHOT_IMPORT
                ),
                directoryStatus = status,
                directoryProfileId = id
            ))
            assertEquals(ProfileSessionStartupDecision.FailClosed(expected), decision)
            assertFalse(decision is ProfileSessionStartupDecision.Ready)
        }
    }

    @Test fun wrongDirectoryOwnerAndUnspecifiedProfileSourceFailClosed() {
        val id = ProfileId.of("startup-owner-a")
        val other = ProfileId.of("startup-owner-b")
        val mismatch = ProfileSessionStartupGate.validate(evidence(
            owner = PersistedProfileOwnerEvidence.RecordedProfile(id, ProfileSessionSource.LOCAL_BACKUP_IMPORT),
            directoryStatus = PersistedProfileDirectoryStatus.VERIFIED,
            directoryProfileId = other
        ))
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.PROFILE_DIRECTORY_OWNER_MISMATCH),
            mismatch
        )

        val unspecifiedSource = ProfileSessionStartupGate.validate(evidence(
            owner = PersistedProfileOwnerEvidence.RecordedProfile(id, ProfileSessionSource.LEGACY_UNASSIGNED),
            directoryStatus = PersistedProfileDirectoryStatus.VERIFIED,
            directoryProfileId = id
        ))
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.RECORDED_PROFILE_SOURCE_UNSPECIFIED),
            unspecifiedSource
        )
    }

    @Test fun unknownVersionOwnerAndInvalidGenerationFailClosed() {
        val base = evidence(
            owner = PersistedProfileOwnerEvidence.LegacyUnassigned,
            directoryStatus = PersistedProfileDirectoryStatus.NOT_APPLICABLE
        )
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.UNSUPPORTED_STATE_VERSION),
            ProfileSessionStartupGate.validate(base.copy(schemaVersion = 99))
        )
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.OWNER_UNSPECIFIED),
            ProfileSessionStartupGate.validate(base.copy(owner = null))
        )
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.GENERATION_MISSING),
            ProfileSessionStartupGate.validate(base.copy(generation = null))
        )
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.GENERATION_INVALID),
            ProfileSessionStartupGate.validate(base.copy(generation = -1L))
        )
    }

    @Test fun legacyCannotCarryARecordedProfileDirectory() {
        val id = ProfileId.of("must-not-be-claimed")
        val decision = ProfileSessionStartupGate.validate(evidence(
            owner = PersistedProfileOwnerEvidence.LegacyUnassigned,
            directoryStatus = PersistedProfileDirectoryStatus.VERIFIED,
            directoryProfileId = id
        ))
        assertEquals(
            ProfileSessionStartupDecision.FailClosed(ProfileSessionStartupFailure.LEGACY_DIRECTORY_UNEXPECTED),
            decision
        )
    }

    private fun evidence(
        owner: PersistedProfileOwnerEvidence?,
        directoryStatus: PersistedProfileDirectoryStatus,
        stateStatus: PersistedProfileStateStatus = PersistedProfileStateStatus.VERIFIED,
        directoryProfileId: ProfileId? = null
    ) = ProfileSessionStartupEvidence(
        schemaVersion = ProfileSessionStartupGate.SCHEMA_VERSION,
        stateStatus = stateStatus,
        directoryStatus = directoryStatus,
        owner = owner,
        generation = 12L,
        directoryProfileId = directoryProfileId
    )
}
