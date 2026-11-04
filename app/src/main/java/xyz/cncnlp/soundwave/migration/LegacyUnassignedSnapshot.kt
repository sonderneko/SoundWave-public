package xyz.cncnlp.soundwave.migration

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Makes a copy-only, local snapshot of the two legacy LocalStore JSON values.
 * The snapshot is intentionally unassigned: it is never associated with an account or uploaded.
 */
object LegacyUnassignedSnapshot {
    internal const val SOURCE_PREFERENCES = "soundwave_userdata"
    internal const val SNAPSHOT_PREFERENCES = "soundwave_legacy_local"
    internal const val SNAPSHOT_KEY = "legacy_unassigned_snapshot_v1"
    internal const val SYNC_GATE_STATE_KEY = "account_data_sync_gate_v1"
    internal const val USER_DATA_KEY = "user_data_v1"
    internal const val PLAY_HISTORY_KEY = "play_history_v1"
    internal const val RESTORE_INVALIDATION_KEY = "legacy_generic_backup_restore_gate_invalidated_v1"

    private const val SNAPSHOT_SCHEMA = "soundwave-local-snapshot"
    private const val SNAPSHOT_VERSION = 1
    private const val SNAPSHOT_KIND = "legacy-unassigned"
    private const val GATE_VERIFIED_EMPTY = "verified-empty"
    private const val GATE_LEGACY_UNASSIGNED = "legacy-unassigned"
    private const val GATE_UNKNOWN = "unknown"
    private val sourceKeys = listOf(USER_DATA_KEY, PLAY_HISTORY_KEY)
    @Volatile private var capturePersistenceUnavailable = false

    enum class AccountSyncGateState {
        VERIFIED_EMPTY,
        LEGACY_UNASSIGNED,
        UNKNOWN
    }

    enum class Result {
        COPIED,
        ALREADY_COMPLETE,
        INVALID_SOURCE,
        SOURCE_READ_FAILED,
        STORAGE_READ_FAILED,
        WRITE_FAILED,
        READBACK_FAILED,
        EXISTING_SNAPSHOT_INVALID
    }

    /** Call before any startup sync path reads or mutates the legacy LocalStore values. */
    fun capture(context: Context): Result {
        capturePersistenceUnavailable = true
        runCatching { xyz.cncnlp.soundwave.LocalStore.flushPendingWrites() }
        val app = try {
            context.applicationContext
        } catch (_: Exception) {
            return Result.STORAGE_READ_FAILED
        }
        // Inventory evidence is separate from the two-key legacy snapshot. It cannot grant
        // ownership or consent, and failure never changes or clears the legacy source data.
        RuntimeProvenanceInventory.refreshInBackground(app)
        val result = try {
            capture(
                PreferencesSnapshotStorage(
                    source = app.getSharedPreferences(SOURCE_PREFERENCES, Context.MODE_PRIVATE),
                    destination = app.getSharedPreferences(SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
                ),
                System.currentTimeMillis()
            )
        } catch (_: Exception) {
            Result.STORAGE_READ_FAILED
        }
        persistGateState(app, result)
        return result
    }

    /** Verified-empty provenance qualifies only for the manual first-sync path, never automatic consent. */
    fun accountSyncGateState(context: Context): AccountSyncGateState {
        if (capturePersistenceUnavailable) return AccountSyncGateState.UNKNOWN
        return try {
            val app = context.applicationContext
            if (hasRestoreInvalidation(app) != false) return AccountSyncGateState.UNKNOWN
            val preferences = app.getSharedPreferences(SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
            val marker = preferences.getString(SYNC_GATE_STATE_KEY, null)
            val hasLegacyData = snapshotHasLegacyData(app)
            when {
                marker == GATE_VERIFIED_EMPTY && hasLegacyData == false -> {
                    val inventory = RuntimeProvenanceInventory.cachedState(context)
                    if (inventory.allRequiredDomainsVerifiedAbsent()) AccountSyncGateState.VERIFIED_EMPTY
                    else AccountSyncGateState.UNKNOWN
                }
                marker == GATE_LEGACY_UNASSIGNED && hasLegacyData == true -> AccountSyncGateState.LEGACY_UNASSIGNED
                else -> AccountSyncGateState.UNKNOWN
            }
        } catch (_: Exception) {
            AccountSyncGateState.UNKNOWN
        }
    }

    /** Fresh data-operation check; UI reads cannot authorize a remote transfer. */
    fun verifyDataOperation(context: Context): AccountSyncGateState {
        if (capturePersistenceUnavailable || hasRestoreInvalidation(context) != false) return AccountSyncGateState.UNKNOWN
        val legacy = snapshotHasLegacyData(context) ?: return AccountSyncGateState.UNKNOWN
        if (legacy) return AccountSyncGateState.LEGACY_UNASSIGNED
        return if (RuntimeProvenanceInventory.verifyDataOperation(context).allRequiredDomainsVerifiedAbsent())
            AccountSyncGateState.VERIFIED_EMPTY else AccountSyncGateState.UNKNOWN
    }

    /**
     * A direct user sync action can authorize a structurally valid captured snapshot even when
     * later app writes made runtime absence evidence stale. Generic backup restores and invalid
     * or missing snapshots remain fail-closed until their provenance is explicitly resolved.
     */
    fun canExplicitlyEnableAccountSync(context: Context): Boolean {
        if (capturePersistenceUnavailable) return false
        if (hasRestoreInvalidation(context) != false || snapshotHasLegacyData(context) == null) return false
        return true
    }

    /** Only explicit account/WebDAV sync entrypoints may use this relaxed, one-shot gate. */
    fun explicitManualSyncGateState(context: Context): AccountSyncGateState {
        val state = accountSyncGateState(context)
        return if (state == AccountSyncGateState.UNKNOWN && canExplicitlyEnableAccountSync(context)) {
            AccountSyncGateState.LEGACY_UNASSIGNED
        } else state
    }

    internal fun isVerifiedEmptySnapshot(raw: String): Boolean = decodeSnapshot(raw)?.presentKeys?.isEmpty() == true

    fun accountSyncGateMessage(context: Context): String = when (accountSyncGateState(context)) {
        AccountSyncGateState.VERIFIED_EMPTY -> ""
        AccountSyncGateState.LEGACY_UNASSIGNED,
        AccountSyncGateState.UNKNOWN -> "本机数据仍可正常使用；账号数据云同步已暂停，待旧数据明确关联/导入。"
    }

    private fun hasRestoreInvalidation(context: Context): Boolean? = try {
        if (xyz.cncnlp.soundwave.BackupRestoreRecovery.hasPending(context)) true else context.getSharedPreferences(SOURCE_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(RESTORE_INVALIDATION_KEY, false)
    } catch (_: Exception) {
        null
    }

    private fun persistGateState(context: Context, result: Result) {
        val marker = if (hasRestoreInvalidation(context) != false) {
            GATE_UNKNOWN
        } else {
            when (result) {
                Result.COPIED, Result.ALREADY_COMPLETE -> when (snapshotHasLegacyData(context)) {
                    false -> GATE_VERIFIED_EMPTY
                    true -> GATE_LEGACY_UNASSIGNED
                    null -> GATE_UNKNOWN
                }
                else -> GATE_UNKNOWN
            }
        }
        val persisted = try {
            context.getSharedPreferences(SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(SYNC_GATE_STATE_KEY, marker)
                .commit()
        } catch (_: Exception) {
            false
        }
        capturePersistenceUnavailable = !persisted
    }

    /** null means missing, unreadable, or structurally invalid: callers must fail closed. */
    private fun snapshotHasLegacyData(context: Context): Boolean? {
        return try {
            val raw = context.getSharedPreferences(SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
                .getString(SNAPSHOT_KEY, null) ?: return null
            val decoded = decodeSnapshot(raw) ?: return null
            decoded.presentKeys.isNotEmpty()
        } catch (_: Exception) {
            null
        }
    }

    internal fun capture(storage: SnapshotStorage, capturedAtEpochMs: Long): Result {
        val existing = try {
            storage.readSnapshot()
        } catch (_: Exception) {
            return Result.STORAGE_READ_FAILED
        }
        if (existing != null) {
            return if (decodeSnapshot(existing) != null) Result.ALREADY_COMPLETE
            else Result.EXISTING_SNAPSHOT_INVALID
        }

        val payloads = linkedMapOf<String, String>()
        for (key in sourceKeys) {
            val value = try {
                storage.readSource(key)
            } catch (_: Exception) {
                return Result.SOURCE_READ_FAILED
            }
            if (!value.present) {
                if (value.raw != null) return Result.INVALID_SOURCE
                continue
            }
            val raw = value.raw ?: return Result.INVALID_SOURCE
            if (!isValidSourcePayload(key, raw)) return Result.INVALID_SOURCE
            payloads[key] = raw
        }

        val encoded = try {
            encodeSnapshot(payloads, capturedAtEpochMs)
        } catch (_: Exception) {
            return Result.INVALID_SOURCE
        }
        val committed = try {
            storage.commitSnapshot(encoded)
        } catch (_: Exception) {
            false
        }
        if (!committed) return Result.WRITE_FAILED

        val readback = try {
            storage.readSnapshot()
        } catch (_: Exception) {
            return Result.READBACK_FAILED
        }
        val verified = readback?.let(::decodeSnapshot)
        if (readback != encoded || verified?.payloads != payloads || verified.presentKeys != payloads.keys) {
            return Result.READBACK_FAILED
        }
        return Result.COPIED
    }

    private fun encodeSnapshot(payloads: Map<String, String>, capturedAtEpochMs: Long): String {
        val encodedPayloads = JSONObject()
        payloads.forEach { (key, raw) -> encodedPayloads.put(key, raw) }
        val presentKeys = JSONArray().apply { payloads.keys.forEach(::put) }
        return JSONObject()
            .put("schema", SNAPSHOT_SCHEMA)
            .put("version", SNAPSHOT_VERSION)
            .put("kind", SNAPSHOT_KIND)
            .put("capturedAtEpochMs", capturedAtEpochMs)
            .put(
                "source",
                JSONObject()
                    .put("preferences", SOURCE_PREFERENCES)
                    .put("presentKeys", presentKeys)
                    .put("payloads", encodedPayloads)
            )
            .toString()
    }

    private data class DecodedSnapshot(
        val presentKeys: Set<String>,
        val payloads: Map<String, String>
    )

    private fun decodeSnapshot(encoded: String): DecodedSnapshot? {
        return try {
            val root = JSONObject(encoded)
            if (root.opt("schema") != SNAPSHOT_SCHEMA ||
                (root.opt("version") as? Number)?.toInt() != SNAPSHOT_VERSION ||
                root.opt("kind") != SNAPSHOT_KIND ||
                root.opt("capturedAtEpochMs") !is Number
            ) return null

            val source = root.optJSONObject("source") ?: return null
            if (source.opt("preferences") != SOURCE_PREFERENCES) return null
            val keys = source.optJSONArray("presentKeys") ?: return null
            val presentKeys = linkedSetOf<String>()
            for (index in 0 until keys.length()) {
                val key = keys.opt(index) as? String ?: return null
                if (key !in sourceKeys || !presentKeys.add(key)) return null
            }
            val payloadObject = source.optJSONObject("payloads") ?: return null
            val payloadNames = payloadObject.keys().asSequence().toSet()
            if (payloadNames != presentKeys) return null

            val payloads = linkedMapOf<String, String>()
            for (key in sourceKeys) {
                if (key !in presentKeys) continue
                val raw = payloadObject.opt(key) as? String ?: return null
                if (!isValidSourcePayload(key, raw)) return null
                payloads[key] = raw
            }
            DecodedSnapshot(presentKeys, payloads)
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidSourcePayload(key: String, raw: String): Boolean {
        return try {
            when (key) {
                USER_DATA_KEY -> JSONObject(raw)
                PLAY_HISTORY_KEY -> JSONArray(raw)
                else -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

internal data class StoredRawValue(val present: Boolean, val raw: String?)

/** Deliberately narrow storage surface: no network, account, settings, script, download, or file APIs. */
internal interface SnapshotStorage {
    fun readSource(key: String): StoredRawValue
    fun readSnapshot(): String?
    fun commitSnapshot(encoded: String): Boolean
}

private class PreferencesSnapshotStorage(
    private val source: SharedPreferences,
    private val destination: SharedPreferences
) : SnapshotStorage {
    override fun readSource(key: String): StoredRawValue {
        val present = source.contains(key)
        return StoredRawValue(present, if (present) source.getString(key, null) else null)
    }

    override fun readSnapshot(): String? = destination.getString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, null)

    override fun commitSnapshot(encoded: String): Boolean = destination.edit()
        .putString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, encoded)
        .commit()
}
