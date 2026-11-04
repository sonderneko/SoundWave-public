package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import xyz.cncnlp.soundwave.migration.ProfileCatalog
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileOwner
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory

/** Namespace and mutation boundary used by production selectors and their existing editors.
 * Device namespaces remain explicitly legacy: permissions/SAF/output cannot follow a profile.
 * No editor may survive a changed identity. Catalog metadata never goes through this boundary.
 */
internal object ContextPreferences {
    internal fun profileNamespace(name: String, id: ProfileId): String? = when (name) {
        "soundwave" -> id.preferencesName
        "soundwave_scripts", "soundwave_userdata", "listen_stats_v1" -> id.preferencesName
        "smart_source_v1" -> "smart_source_profile_${id.value}"
        "soundwave_sort", "soundwave_list_scroll", "list_scroll_v1", "soundwave_script_probe", "soundwave_playlist_check", "pl_import_check_v1", "home_page_view_state_v1" -> "${name}_profile_${id.value}"
        else -> null
    }

    fun open(context: Context, namespace: String, mode: Int,
             owner: ProfileContext = ProfileWorkIdentity.runtime()): SharedPreferences {
        val app = context.applicationContext
        check(ProfileWorkIdentity.isWork(owner) && ProfileCatalog.isCurrent(app, owner)) { "资料身份无效或已过期" }
        val target = when (val identity = owner.owner) {
            ProfileOwner.LegacyUnassigned -> namespace
            is ProfileOwner.RecordedProfile -> profileNamespace(namespace, identity.id)
                ?: error("此设备资源没有资料命名空间")
        }
        val preferences = app.getSharedPreferences(target, mode)
        return object : SharedPreferences by preferences {
            private fun requireOwner() {
                check(ProfileWorkIdentity.isWork(owner) && ProfileCatalog.isCurrent(app, owner)) { "资料已变化；未读取" }
            }
            override fun getAll(): MutableMap<String, *> { requireOwner(); return preferences.all }
            override fun contains(key: String): Boolean { requireOwner(); return preferences.contains(key) }
            override fun getString(key: String, defValue: String?): String? { requireOwner(); return preferences.getString(key, defValue) }
            override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
                requireOwner()
                return preferences.getStringSet(key, defValues)?.toMutableSet()
            }
            override fun getInt(key: String, defValue: Int): Int { requireOwner(); return preferences.getInt(key, defValue) }
            override fun getLong(key: String, defValue: Long): Long { requireOwner(); return preferences.getLong(key, defValue) }
            override fun getFloat(key: String, defValue: Float): Float { requireOwner(); return preferences.getFloat(key, defValue) }
            override fun getBoolean(key: String, defValue: Boolean): Boolean { requireOwner(); return preferences.getBoolean(key, defValue) }
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                    override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                    override fun putFloat(key: String, value: Float): SharedPreferences.Editor { editor.putFloat(key, value); return this }
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                    override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                    private fun validate(): Boolean = ProfileWorkIdentity.isWork(owner) && ProfileCatalog.isCurrent(app, owner) && RuntimeProvenanceInventory.invalidateBeforeMutation(app)
                    override fun commit(): Boolean = ProfileWorkIdentity.commitWork(owner) { validate() && editor.commit() } == true
                    override fun apply() {
                        check(ProfileWorkIdentity.commitWork(owner) {
                            check(validate()) { "资料已变化；修改未应用" }
                            editor.apply()
                            true
                        } == true) { "资料已变化；修改未应用" }
                    }
                }
            }
        }
    }
}
