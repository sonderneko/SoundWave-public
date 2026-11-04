package xyz.cncnlp.soundwave.migration

import xyz.cncnlp.soundwave.ProfileId

/** An explicit owner is not evidence that its namespace is complete or safe to activate. */
sealed class ProfileOwner {
    data object LegacyUnassigned : ProfileOwner()
    data class RecordedProfile(val id: ProfileId) : ProfileOwner()
}

/** Immutable work identity; capture before launching work, compare before applying a result. */
class ProfileContext private constructor(
    val owner: ProfileOwner,
    val generation: Long
) {
    fun matches(other: ProfileContext): Boolean = owner == other.owner && generation == other.generation

    companion object {
        fun legacy(generation: Long): ProfileContext {
            require(generation >= 0L)
            return ProfileContext(ProfileOwner.LegacyUnassigned, generation)
        }

        /** For readonly inactive-profile inspection, not production activation. */
        fun recorded(id: ProfileId, generation: Long): ProfileContext {
            require(generation >= 0L)
            return ProfileContext(ProfileOwner.RecordedProfile(id), generation)
        }
    }
}
