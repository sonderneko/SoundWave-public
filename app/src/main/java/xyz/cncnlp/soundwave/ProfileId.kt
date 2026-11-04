package xyz.cncnlp.soundwave

/**
 * Validated identifier for explicitly addressed local profiles.
 *
 * IDs are deliberately restricted to a short ASCII token so they cannot inject preference names,
 * paths, or other namespace components.
 */
@JvmInline
value class ProfileId private constructor(val value: String) {
    /** Separate preference file; no active-profile pointer or legacy-data association is implied. */
    internal val preferencesName: String
        get() = "soundwave_userdata_profile_$value"

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

        fun of(value: String): ProfileId {
            require(SAFE_ID.matches(value)) {
                "profileId must be a 1-64 character ASCII identifier"
            }
            return ProfileId(value)
        }
    }
}
