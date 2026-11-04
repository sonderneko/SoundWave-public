package xyz.cncnlp.soundwave.migration

import android.content.Context
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.ProfileId

/** Validates recorded identity from copy-only import registry, never discovers an active profile. */
internal object ProfileCatalog {
    fun inactive(context: Context, id: ProfileId): ProfileContext? {
        return try {
            if (xyz.cncnlp.soundwave.InactiveRollbackJournal.contains(context, id)) return null
            if (!xyz.cncnlp.soundwave.InactiveImportJournal.isAvailable(context, id) ||
                !xyz.cncnlp.soundwave.SelectedDomainCopy.available(context, id)) return null
            val metadata = LocalStore.importedLocalBackupProfiles(context)[id]
            // Local-backup metadata without its registry entry is an interrupted/orphan copy.
            if (metadata == null && LocalStore.importedLocalBackupMetadata(context, id) != null) return null
            val snapshot = if (metadata == null) LocalStore.importedSnapshotMetadata(context, id) else null
            if (metadata == null && snapshot == null) return null
            if (metadata != null && metadata != LocalStore.importedLocalBackupMetadata(context, id)) return null
            // Exact preview verification validates independent history and core payloads.
            LocalStore.readUnactivatedProfilePreview(context, id)
            ProfileContext.recorded(id, java.time.Instant.parse(metadata?.importedAt ?: snapshot!!.importedAt).toEpochMilli())
        } catch (_: Exception) { null }
    }

    fun isCurrent(context: Context, token: ProfileContext): Boolean = when (val owner = token.owner) {
        ProfileOwner.LegacyUnassigned -> ProfileWorkIdentity.isWork(token)
        is ProfileOwner.RecordedProfile -> inactive(context, owner.id)?.matches(token) == true
    }
}
