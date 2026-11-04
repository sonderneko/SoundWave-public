package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory

/** Explicit core/history projection plus separately selected, non-executable auxiliary domains. */
internal class PreparedLegacyCopy internal constructor(
    val prepared: PreparedLocalProfileBackup,
    internal val rawBundle: String?,
    internal val rawHistory: String?,
    internal val epoch: Long,
    internal val domains: Map<LocalCopyDomain, Map<String, Any>>,
    internal val sources: Map<LocalCopyDomain, Map<String, Any>>
)

internal object LegacyCopyMigration {
    fun preview(context: Context, selected: Set<LocalCopyDomain> = emptySet()): PreparedLegacyCopy {
        val prefs = LocalStore.prefs(context)
        val epoch = RuntimeProvenanceInventory.readEpoch(context) ?: error("来源版本不可读")
        val rawBundle = prefs.getString("user_data_v1", null)
        val rawHistory = prefs.getString("play_history_v1", null)
        val sources = selected.associateWith { domain ->
            SelectedDomainCopy.project(domain, context.getSharedPreferences(domain.source, 0).all)
        }
        require(rawBundle != null || rawHistory != null) { "没有可复制的收藏、歌单或历史" }
        val bundle = rawBundle?.let(::JSONObject) ?: LocalStore.UserBundle().also { it.ensureDefaults() }.toCloudJson()
        // This is a documented projection, NOT a copy of all preferences or ownership assignment.
        bundle.put("enabledScripts", JSONArray()).put("scriptOrder", JSONArray()).remove("avatar")
        val history = rawHistory?.let(::JSONArray) ?: JSONArray()
        val prepared = LocalProfileBackupImporter.parse(InactiveProfileBackupSerializer.serialize(bundle, history))
        check(RuntimeProvenanceInventory.readEpoch(context) == epoch &&
            prefs.getString("user_data_v1", null) == rawBundle && prefs.getString("play_history_v1", null) == rawHistory) {
            "预览期间数据变化，请重新预览"
        }
        sources.forEach { (domain, expected) ->
            check(SelectedDomainCopy.project(domain, context.getSharedPreferences(domain.source, 0).all) == expected) {
                "所选数据预览期间变化，请重新预览"
            }
        }
        return PreparedLegacyCopy(prepared, rawBundle, rawHistory, epoch, sources, sources)
    }

    /** Called only by the independent confirmation button. Source data is never edited/deleted. */
    fun copyConfirmed(context: Context, id: ProfileId, selection: PreparedLegacyCopy) {
        val prefs = LocalStore.prefs(context)
        check(RuntimeProvenanceInventory.readEpoch(context) == selection.epoch &&
            prefs.getString("user_data_v1", null) == selection.rawBundle &&
            prefs.getString("play_history_v1", null) == selection.rawHistory) { "数据已变化；未复制，请重新预览" }
        selection.sources.forEach { (domain, expected) ->
            check(SelectedDomainCopy.project(domain, context.getSharedPreferences(domain.source, 0).all) == expected) {
                "所选数据域已变化；未复制"
            }
        }
        LocalStore.importLocalBackupToUnactivatedProfile(context, id, selection.prepared,
            auxiliaryDomains = selection.domains)
    }
}
