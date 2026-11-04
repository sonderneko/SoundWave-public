package xyz.cncnlp.soundwave.migration

import xyz.cncnlp.soundwave.ProfileId

/**
 * Pure model for a future startup reader. VERIFIED may only be supplied after the persisted
 * record/directory has been read and checked; this type performs no IO and is not wired to app
 * startup, ProfileCatalog, active pointers, or profile activation.
 */
internal enum class PersistedProfileStateStatus {
    VERIFIED,
    MISSING,
    CORRUPT,
    UNKNOWN
}

internal enum class PersistedProfileDirectoryStatus {
    NOT_APPLICABLE,
    VERIFIED,
    MISSING,
    CORRUPT,
    UNKNOWN
}

/** Explicit origin evidence; an account/login identity is intentionally not a profile source. */
internal enum class ProfileSessionSource {
    LEGACY_UNASSIGNED,
    LOCAL_BACKUP_IMPORT,
    SERVER_SNAPSHOT_IMPORT
}

internal sealed class PersistedProfileOwnerEvidence {
    data object LegacyUnassigned : PersistedProfileOwnerEvidence()
    data class RecordedProfile(val id: ProfileId, val source: ProfileSessionSource) : PersistedProfileOwnerEvidence()
}

/** Detached result of reading persisted startup evidence; nullable fields model absent/incomplete data. */
internal data class ProfileSessionStartupEvidence(
    val schemaVersion: Int?,
    val stateStatus: PersistedProfileStateStatus,
    val directoryStatus: PersistedProfileDirectoryStatus,
    val owner: PersistedProfileOwnerEvidence?,
    val generation: Long?,
    val directoryProfileId: ProfileId?
)

internal enum class ProfileSessionStartupFailure {
    STATE_MISSING,
    STATE_CORRUPT,
    STATE_UNVERIFIED,
    UNSUPPORTED_STATE_VERSION,
    OWNER_UNSPECIFIED,
    GENERATION_MISSING,
    GENERATION_INVALID,
    PROFILE_DIRECTORY_MISSING,
    PROFILE_DIRECTORY_CORRUPT,
    PROFILE_DIRECTORY_UNVERIFIED,
    PROFILE_DIRECTORY_OWNER_MISMATCH,
    LEGACY_DIRECTORY_UNEXPECTED,
    RECORDED_PROFILE_SOURCE_UNSPECIFIED
}

internal sealed class ProfileSessionStartupDecision {
    data class Ready(val context: ProfileContext, val source: ProfileSessionSource) : ProfileSessionStartupDecision()
    data class FailClosed(val reason: ProfileSessionStartupFailure) : ProfileSessionStartupDecision()
}

/**
 * Validates already-read evidence without choosing an owner. Missing, damaged, stale-version,
 * or ambiguous evidence never falls back to legacy or to the first available profile.
 */
internal object ProfileSessionStartupGate {
    const val SCHEMA_VERSION = 1

    fun validate(evidence: ProfileSessionStartupEvidence): ProfileSessionStartupDecision {
        when (evidence.stateStatus) {
            PersistedProfileStateStatus.MISSING -> return fail(ProfileSessionStartupFailure.STATE_MISSING)
            PersistedProfileStateStatus.CORRUPT -> return fail(ProfileSessionStartupFailure.STATE_CORRUPT)
            PersistedProfileStateStatus.UNKNOWN -> return fail(ProfileSessionStartupFailure.STATE_UNVERIFIED)
            PersistedProfileStateStatus.VERIFIED -> Unit
        }
        if (evidence.schemaVersion != SCHEMA_VERSION) {
            return fail(ProfileSessionStartupFailure.UNSUPPORTED_STATE_VERSION)
        }
        val generation = evidence.generation ?: return fail(ProfileSessionStartupFailure.GENERATION_MISSING)
        if (generation < 0L) return fail(ProfileSessionStartupFailure.GENERATION_INVALID)
        val owner = evidence.owner ?: return fail(ProfileSessionStartupFailure.OWNER_UNSPECIFIED)

        return when (owner) {
            PersistedProfileOwnerEvidence.LegacyUnassigned -> {
                if (evidence.directoryStatus != PersistedProfileDirectoryStatus.NOT_APPLICABLE ||
                    evidence.directoryProfileId != null
                ) return fail(ProfileSessionStartupFailure.LEGACY_DIRECTORY_UNEXPECTED)
                ready(ProfileContext.legacy(generation), ProfileSessionSource.LEGACY_UNASSIGNED)
            }
            is PersistedProfileOwnerEvidence.RecordedProfile -> {
                when (evidence.directoryStatus) {
                    PersistedProfileDirectoryStatus.MISSING ->
                        return fail(ProfileSessionStartupFailure.PROFILE_DIRECTORY_MISSING)
                    PersistedProfileDirectoryStatus.CORRUPT ->
                        return fail(ProfileSessionStartupFailure.PROFILE_DIRECTORY_CORRUPT)
                    PersistedProfileDirectoryStatus.VERIFIED -> Unit
                    PersistedProfileDirectoryStatus.NOT_APPLICABLE,
                    PersistedProfileDirectoryStatus.UNKNOWN ->
                        return fail(ProfileSessionStartupFailure.PROFILE_DIRECTORY_UNVERIFIED)
                }
                if (evidence.directoryProfileId != owner.id) {
                    return fail(ProfileSessionStartupFailure.PROFILE_DIRECTORY_OWNER_MISMATCH)
                }
                if (owner.source == ProfileSessionSource.LEGACY_UNASSIGNED) {
                    return fail(ProfileSessionStartupFailure.RECORDED_PROFILE_SOURCE_UNSPECIFIED)
                }
                ready(ProfileContext.recorded(owner.id, generation), owner.source)
            }
        }
    }

    private fun ready(context: ProfileContext, source: ProfileSessionSource) =
        ProfileSessionStartupDecision.Ready(context, source)

    private fun fail(reason: ProfileSessionStartupFailure) =
        ProfileSessionStartupDecision.FailClosed(reason)
}
