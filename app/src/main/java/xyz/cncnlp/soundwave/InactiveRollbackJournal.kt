package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONObject
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileOwner

/** Exact-owner cleanup authority, separate from catalog/runtime/remote authorization.
 * Persisted before destructive work; idempotent stages can replay after crash or failed fsync.
 */
internal object InactiveRollbackJournal {
    enum class Phase { INTENT, REGISTRY_REMOVED, AUXILIARY_CLEARED, CORE_CLEARED }
    data class Receipt(val id: ProfileId, val generation: Long, val operation: String, val phase: Phase) {
        fun token() = ProfileContext.recorded(id, generation)
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("soundwave_inactive_rollback_journal", 0)
    fun contains(context: Context, id: ProfileId): Boolean = prefs(context).contains(id.value)
    fun read(context: Context, id: ProfileId): Receipt? = runCatching {
        val raw = prefs(context).getString(id.value, null) ?: return null
        val root = JSONObject(raw)
        check(root.getInt("version") == 1 && root.getString("owner") == id.value)
        val generation = root.getLong("generation").also { check(it >= 0L) }
        val operation = root.getString("operation")
        check(java.util.UUID.fromString(operation).toString() == operation)
        Receipt(id, generation, operation, Phase.valueOf(root.getString("phase")))
    }.getOrNull()
    fun pending(context: Context): List<ProfileId> = prefs(context).all.keys.mapNotNull { raw ->
        runCatching { ProfileId.of(raw) }.getOrNull()?.takeIf { read(context, it) != null }
    }
    fun prepare(context: Context, token: ProfileContext): Receipt? {
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return null
        if (contains(context, owner.id)) return read(context, owner.id)?.takeIf { it.token().matches(token) }
        val receipt = Receipt(owner.id, token.generation, java.util.UUID.randomUUID().toString(), Phase.INTENT)
        return if (store(context, receipt)) receipt else null
    }
    private fun store(context: Context, receipt: Receipt): Boolean {
        val raw = JSONObject().put("version", 1).put("owner", receipt.id.value)
            .put("generation", receipt.generation).put("operation", receipt.operation)
            .put("phase", receipt.phase.name).toString()
        val p = prefs(context)
        return p.edit().putString(receipt.id.value, raw).commit() && p.getString(receipt.id.value, null) == raw
    }
    fun advance(context: Context, receipt: Receipt, phase: Phase): Receipt? {
        if (read(context, receipt.id) != receipt) return null
        val next = receipt.copy(phase = phase)
        return if (store(context, next)) next else null
    }
    fun finish(context: Context, receipt: Receipt): Boolean {
        if (receipt.phase != Phase.CORE_CLEARED || read(context, receipt.id) != receipt) return false
        val p = prefs(context)
        return p.edit().remove(receipt.id.value).commit() && !p.contains(receipt.id.value)
    }
}
