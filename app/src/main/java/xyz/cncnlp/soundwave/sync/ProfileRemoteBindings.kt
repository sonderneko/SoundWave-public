package xyz.cncnlp.soundwave.sync

import android.content.Context
import org.json.JSONObject
import xyz.cncnlp.soundwave.ProfileId
import xyz.cncnlp.soundwave.SecretStore
import xyz.cncnlp.soundwave.migration.ProfileCatalog
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileOwner
import java.util.UUID

internal object ProfileRemoteBindingPolicy {
    fun maySync(recorded: Boolean, optedIn: Boolean, documentId: String?): Boolean =
        recorded && optedIn && documentId != null && Regex("[a-f0-9]{32}").matches(documentId)
}

internal data class ProfileRemoteBinding(val documentId: String, val credentials: WebDavSavedCredentials, val revision: String)

/** No global credential fallback. Binding is explicit; inactive import alone always stays LOCAL_ONLY. */
internal object ProfileRemoteBindings {
    private fun prefs(context: Context, id: ProfileId) = context.applicationContext.getSharedPreferences("soundwave_profile_webdav_${id.value}", 0)

    fun optIn(context: Context, token: ProfileContext, credentials: WebDavSavedCredentials, confirmed: Boolean): Boolean {
        if (!confirmed || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.remoteReady(token) || !ProfileCatalog.isCurrent(context, token)) return false
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return false
        val encrypted = SecretStore.encrypt(context, credentials.password) ?: return false
        if (!encrypted.startsWith("v2:")) return false
        val preferences = prefs(context, owner.id)
        val existing = load(context, token)
        val document = existing?.documentId ?: UUID.randomUUID().toString().replace("-", "")
        val raw = JSONObject().put("version", 1).put("optedIn", true).put("generation", token.generation)
            .put("documentId", document).put("revision", UUID.randomUUID().toString()).put("scope", "core-history").put("conflict", "merge-conditional-once")
            .put("url", credentials.url).put("username", credentials.username).put("password", encrypted).toString()
        return preferences.edit().putString("binding", raw).commit() && preferences.getString("binding", null) == raw
    }

    fun load(context: Context, token: ProfileContext): ProfileRemoteBinding? {
        // Check provenance BEFORE reading the credentials namespace, including stale generations.
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.remoteReady(token) || !ProfileCatalog.isCurrent(context, token)) return null
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return null
        return try {
            val raw = prefs(context, owner.id).getString("binding", null) ?: return null
            val root = JSONObject(raw)
            val document = root.optString("documentId")
            if (root.optInt("version") != 1 || root.optLong("generation", -1L) != token.generation ||
                root.optString("scope") != "core-history" || root.optString("conflict") != "merge-conditional-once" ||
                !ProfileRemoteBindingPolicy.maySync(true, root.optBoolean("optedIn", false), document)) return null
            val encrypted = root.getString("password")
            if (!encrypted.startsWith("v2:")) return null
            val password = SecretStore.decrypt(context, encrypted) ?: return null
            val revision = root.getString("revision")
            if (runCatching { UUID.fromString(revision).toString() == revision }.getOrDefault(false).not()) return null
            ProfileRemoteBinding(document, WebDavSavedCredentials(root.getString("url"), root.getString("username"), password), revision)
        } catch (_: Exception) { null }
    }

    fun recordSuccessfulPut(context: Context, token: ProfileContext, binding: ProfileRemoteBinding, etag: String?): Boolean {
        if (load(context, token) != binding) return false
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return false
        val p = prefs(context, owner.id)
        val state = JSONObject().put("revision", binding.revision).put("documentId", binding.documentId)
            .put("etag", etag ?: JSONObject.NULL).put("at", System.currentTimeMillis()).toString()
        return p.edit().putString("lastSuccessfulPut", state).commit() && p.getString("lastSuccessfulPut", null) == state
    }

    fun revoke(context: Context, token: ProfileContext): Boolean {
        val owner = token.owner as? ProfileOwner.RecordedProfile ?: return false
        if (!ProfileCatalog.isCurrent(context, token)) return false
        val preferences = prefs(context, owner.id)
        return preferences.edit().remove("binding").remove("lastSuccessfulPut").commit() && !preferences.contains("binding")
    }
}
