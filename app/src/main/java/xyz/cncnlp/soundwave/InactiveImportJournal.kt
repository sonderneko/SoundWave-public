package xyz.cncnlp.soundwave

import android.content.Context

/** Separate durable journal: an interrupted copy is quarantined, never auto-activated or deleted.
 * Old already-registered imports without a journal retain compatibility. New imports create
 * PREPARED before writing any payload; only verified payload/index may become COMPLETE.
 */
internal object InactiveImportJournal {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("soundwave_inactive_import_journal", 0)
    fun prepare(context: Context, id: ProfileId): Boolean {
        val p = prefs(context)
        if (p.contains(id.value)) return false
        return p.edit().putString(id.value, "PREPARED:v1").commit() && p.getString(id.value, null) == "PREPARED:v1"
    }
    fun complete(context: Context, id: ProfileId): Boolean {
        val p = prefs(context)
        if (p.getString(id.value, null) != "PREPARED:v1") return false
        return p.edit().putString(id.value, "COMPLETE:v1").commit() && p.getString(id.value, null) == "COMPLETE:v1"
    }
    fun isAvailable(context: Context, id: ProfileId): Boolean = try {
        val p = prefs(context)
        !p.contains(id.value) || p.getString(id.value, null) == "COMPLETE:v1"
    } catch (_: Exception) { false }
    fun rolledBack(context: Context, id: ProfileId): Boolean {
        val p = prefs(context)
        return p.edit().putString(id.value, "ROLLED_BACK:v1").commit() && p.getString(id.value, null) == "ROLLED_BACK:v1"
    }
}
