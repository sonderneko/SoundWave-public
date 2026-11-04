package xyz.cncnlp.soundwave

import java.security.MessageDigest

/** Versioned pseudonym for the exact, case-sensitive authenticated login identity.
 * No credential or raw login name belongs in a portable data document.
 */
internal object AccountDataIdentity {
    private val pattern = Regex("swown2_[a-f0-9]{64}")

    fun key(user: String): String {
        require(user.isNotBlank()) { "Missing account identity" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("SoundWave/account-data/v2\u0000" + user).toByteArray(Charsets.UTF_8))
        return "swown2_" + digest.joinToString("") { "%02x".format(it) }
    }

    fun isKey(value: String?): Boolean = value != null && pattern.matches(value)

    fun ownedStats(bundle: LocalStore.UserBundle, user: String): String =
        if (bundle.dailyListenStatsOwnerV2 == key(user)) bundle.dailyListenStatsJson else "{}"
}
