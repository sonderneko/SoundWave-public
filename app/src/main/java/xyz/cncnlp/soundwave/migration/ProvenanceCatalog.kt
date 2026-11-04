package xyz.cncnlp.soundwave.migration

import java.util.Collections

/** Evidence only: absence is neither ownership nor user consent to activate or synchronize. */
internal enum class ProvenanceState {
    NOT_SCANNED, UNKNOWN, PRESENT_UNASSIGNED, VERIFIED_ABSENT, IMPORTED_LOCAL_NOT_ACTIVE
}

internal enum class InventorySource { UNKNOWN, LEGACY_SCAN, LOCAL_IMPORT }

/** PRESENT includes an existing empty key/file; UNREADABLE includes denied directory/URI access. */
internal enum class InventoryPresence { ABSENT, PRESENT, UNREADABLE }

/**
 * Versioned scan manifests, not scanners. A namespace/root selector covers ALL its children,
 * including unknown keys/files. See docs/architecture-provenance-catalog.md for scope.
 * Add/change locations only with a catalog/scanner version bump and a fresh complete scan.
 */
internal enum class ProvenanceDomain(vararg locations: String) {
    CORE_BUNDLE("soundwave_userdata/user_data_v1"),
    PLAY_HISTORY("soundwave_userdata/play_history_v1"),
    DOWNLOAD_RECORDS("soundwave_userdata/downloads_v1"),
    LIBRARY_ORDER("soundwave/lib_order_v1"),
    LISTEN_STATS("listen_stats_v1/*"),
    DISLIKES("soundwave/dislike_keys_v1"),
    SETTINGS_AND_SESSION("soundwave/*", "soundwave_sort/*", "soundwave_prefs/*", "soundwave_webdav/*"),
    SMART_SOURCE("smart_source_v1/*", "smart_source_profile_*/*"),
    SCRIPTS("soundwave_scripts/*"),
    SCRIPT_PROBES("script_probe_cache_v3/*", "script_probe_cache_v4/*"),
    PLAYBACK("soundwave_playback_v1/*", "soundwave_playback_profile_*/*"),
    DOWNLOAD_RESOURCES("soundwave_download_scheduler/*", "workmanager/download-work",
        "external-files/Music/SoundWave/**", "files/music/SoundWave/**", "saf/download-targets"),
    AVATARS("avatar_map/*", "files/user_avatar*.jpg", "files/avatars/**"),
    BROWSE_AND_DIAGNOSTICS("sw_feed_cache_v4/*", "home_page_view_state_v1/*", "home_feed_refresh_v1/*", "list_scroll_v1/*",
        "pl_import_check_v1/*", "playback_diag_v1/*", "personal_recommendation_state_v1/*"),
    LOCAL_IMPORTS_AND_SNAPSHOTS("soundwave_legacy_local/*", "soundwave_local_backup_profile_index_v1/*",
        "soundwave_userdata_profile_*/*", "soundwave_userdata/*", "files/staged/snapshot-vault/**"),
    LOCAL_FILES("files/**", "cache/**", "external-files/**"),
    UNCLASSIFIED_PREFERENCES("shared-prefs/unclassified/*");

    val scannerVersion: Int = 2
    val requiredLocations: Set<String> = Collections.unmodifiableSet(LinkedHashSet(locations.toList()))
}

/** No mutable data-class copy API: both input and exposed collections are immutable snapshots. */
internal class DomainInventory(
    val domain: ProvenanceDomain,
    val scannerVersion: Int,
    val scannedAtMillis: Long?,
    val scanSource: InventorySource,
    val invalidationEpoch: Long,
    entries: Map<String, InventoryPresence>,
    val scanComplete: Boolean,
    val importComplete: Boolean,
    val readSucceeded: Boolean,
    val writeSucceeded: Boolean,
    val invalidationReason: String? = null
) {
    val entries: Map<String, InventoryPresence> = Collections.unmodifiableMap(LinkedHashMap(entries))
}

/**
 * Detached, pure catalog. No persistence, scanner, clock, profile activation or gate integration.
 * Callers must supply the current invalidation epoch independently of this saved evidence;
 * restoring/importing or any data mutation must advance it before older absence can be reused.
 * readSucceeded/writeSucceeded describe confirmed catalog load/commit, not an attempted write.
 */
internal class ProvenanceCatalog(
    val catalogVersion: Int,
    val invalidationEpoch: Long,
    inventories: Map<ProvenanceDomain, DomainInventory>,
    val readSucceeded: Boolean,
    val writeSucceeded: Boolean
) {
    val inventories: Map<ProvenanceDomain, DomainInventory> =
        Collections.unmodifiableMap(LinkedHashMap(inventories))

    fun state(domain: ProvenanceDomain, currentInvalidationEpoch: Long): ProvenanceState {
        if (catalogVersion != VERSION || !readSucceeded || !writeSucceeded ||
            currentInvalidationEpoch < 0 || invalidationEpoch != currentInvalidationEpoch
        ) return ProvenanceState.UNKNOWN
        val inventory = inventories[domain] ?: return ProvenanceState.NOT_SCANNED
        if (inventory.domain != domain || inventory.scannerVersion != domain.scannerVersion ||
            inventory.invalidationEpoch != currentInvalidationEpoch ||
            !inventory.readSucceeded || !inventory.writeSucceeded || inventory.invalidationReason != null
        ) return ProvenanceState.UNKNOWN
        val scannedAt = inventory.scannedAtMillis ?: return ProvenanceState.NOT_SCANNED
        if (scannedAt < 0 || !inventory.scanComplete || !inventory.importComplete ||
            inventory.scanSource == InventorySource.UNKNOWN ||
            inventory.entries.keys != domain.requiredLocations ||
            inventory.entries.values.any { it == InventoryPresence.UNREADABLE }
        ) return ProvenanceState.UNKNOWN
        // Even a complete empty local import is not evidence of global legacy absence.
        if (inventory.scanSource == InventorySource.LOCAL_IMPORT) {
            return ProvenanceState.IMPORTED_LOCAL_NOT_ACTIVE
        }
        return if (inventory.entries.values.any { it == InventoryPresence.PRESENT }) {
            ProvenanceState.PRESENT_UNASSIGNED
        } else {
            ProvenanceState.VERIFIED_ABSENT
        }
    }

    /** Quantifies over the fixed required domains, never just the supplied records or a subset. */
    fun allRequiredDomainsVerifiedAbsent(currentInvalidationEpoch: Long): Boolean =
        ProvenanceDomain.values().all { state(it, currentInvalidationEpoch) == ProvenanceState.VERIFIED_ABSENT }

    companion object {
        const val VERSION = 2
    }
}
