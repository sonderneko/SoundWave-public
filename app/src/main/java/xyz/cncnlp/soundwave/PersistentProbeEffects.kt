package xyz.cncnlp.soundwave

import android.content.Context
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/** Both empty-script and normal worker summaries publish under the captured persistent epoch.
 * Notification (when present) is part of that same permit: revocation cannot slip between
 * summary publication and notification submission. This does not retract already posted effects
 * or promise disk fsync from SharedPreferences.apply(). Not a fence for all probe network/cache IO.
 */
internal object PersistentProbeEffects {
    fun commitSummary(context: Context, owner: ProfileContext, epoch: Long, text: String,
                      afterSummary: () -> Unit = {}): Boolean =
        ProfileWorkIdentity.commitPersistentWork(context, owner, epoch) {
            ContextPreferences.open(context, "soundwave", Context.MODE_PRIVATE, owner)
                .edit().putString("pending_script_probe_done", text).apply()
            afterSummary()
            true
        } == true
}
