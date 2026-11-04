package xyz.cncnlp.soundwave.migration

import android.content.Context
import java.io.File

/**
 * Readonly inventory of legacy locations, followed by a metadata-only catalog commit/readback.
 * No stored absence is reused: scan() inspects current locations every time. Existing roots
 * count actual children, not empty container roots; denied/incomplete inspection is UNKNOWN.
 * This is evidence, not an ownership, activation, migration or remote-sync consent API.
 */
internal object RuntimeProvenanceInventory {
    const val EPOCH_KEY = "provenance_invalidation_epoch_v1"
    private const val PREF = "soundwave_provenance_catalog_v1"
    private const val KEY = "catalog_v1"
    private val lock = Any()
    private val scanLock = Any()
    @Volatile private var cached: Result = Result(null, null)

    /** UI observation never grants upload permission; only a fresh IO boundary scan can do that. */
    fun cachedState(context: Context): Result {
        val state = cached
        return if (state.currentEpoch == readEpoch(context) && !state.allRequiredDomainsVerifiedAbsent()) state
            else Result(null, readEpoch(context))
    }

    /** Explicit data-operation boundary. Never scan on the Android main thread. */
    fun verifyDataOperation(context: Context): Result {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return Result(null, null)
        return scan(context)
    }

    fun refreshInBackground(context: Context) {
        val app = context.applicationContext
        java.util.concurrent.Executors.newSingleThreadExecutor().also { executor ->
            executor.execute { try { scan(app) } finally { executor.shutdown() } }
        }
    }

    /** Invalidate BEFORE mutation. apply updates in-process prefs immediately, without main-thread fsync.
     * Saved absence is NEVER authorization after restart; data operations always inspect actual storage. */
    fun invalidateBeforeMutation(context: Context): Boolean = synchronized(lock) {
        cached = Result(null, null)
        try {
            val preferences = context.applicationContext.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
            val epoch = readEpoch(context) ?: return@synchronized false
            if (epoch == Long.MAX_VALUE) return@synchronized false
            preferences.edit().putLong(EPOCH_KEY, epoch + 1L).apply()
            true
        } catch (_: Exception) { false }
    }

    internal data class Result(val catalog: ProvenanceCatalog?, val currentEpoch: Long?) {
        fun allRequiredDomainsVerifiedAbsent(): Boolean =
            currentEpoch != null && catalog?.allRequiredDomainsVerifiedAbsent(currentEpoch) == true
    }

    fun readEpoch(context: Context): Long? = try {
        context.applicationContext.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
            .getLong(EPOCH_KEY, 0L).takeIf { it >= 0L }
    } catch (_: Exception) { null }

    fun scan(context: Context): Result = synchronized(scanLock) {
        runCatching { xyz.cncnlp.soundwave.LocalStore.flushPendingWrites() }
        try {
            val app = context.applicationContext
            val epoch = readEpoch(app) ?: return@synchronized Result(null, null)
            val inspector = AndroidInventoryInspector(app)
            val catalog = ProvenanceScanner(inspector::inspect).scan(epoch, System.currentTimeMillis())
            if (readEpoch(app) != epoch) return@synchronized Result(null, readEpoch(app))
            val encoded = ProvenanceCatalogCodec.encode(catalog)
            val preferences = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (!preferences.edit().putString(KEY, encoded).commit()) return@synchronized Result(null, epoch)
            val readback = preferences.getString(KEY, null) ?: return@synchronized Result(null, epoch)
            if (readback != encoded || readEpoch(app) != epoch) return@synchronized Result(null, readEpoch(app))
            Result(ProvenanceCatalogCodec.decode(readback), epoch).also { cached = it }
        } catch (_: Exception) {
            Result(null, null)
        }
    }
}

private class AndroidInventoryInspector(private val context: Context) {
    private val TECHNICAL_KEYS = mapOf(
        "soundwave_userdata" to setOf(RuntimeProvenanceInventory.EPOCH_KEY, LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY),
        "soundwave_legacy_local" to setOf(LegacyUnassignedSnapshot.SYNC_GATE_STATE_KEY),
        "soundwave_provenance_catalog_v1" to setOf("catalog_v1")
    )
    private val preferenceRoot: File
        get() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun preferenceFiles(): List<File> {
        val root = preferenceRoot
        if (!root.exists()) return emptyList()
        require(root.isDirectory && root.canRead())
        return root.listFiles()?.toList() ?: error("preference enumeration failed")
    }

    fun inspect(location: String): InventoryPresence = when {
        location == "workmanager/download-work" -> {
            // WorkManager DB existence is conservative evidence, without querying/cancelling work.
            // WAL/SHM/journal also prevent an incomplete DB view from being called absent.
            val database = context.getDatabasePath("androidx.work.workdb")
            val parent = database.parentFile ?: error("missing database root")
            if (!parent.exists()) InventoryPresence.ABSENT else {
                require(parent.isDirectory && parent.canRead())
                val files = parent.listFiles() ?: error("database enumeration failed")
                if (files.any { it.name.startsWith(database.name) }) InventoryPresence.PRESENT else InventoryPresence.ABSENT
            }
        }
        location == "saf/download-targets" -> {
            val permissions = context.contentResolver.persistedUriPermissions
            val preferences = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
            if (permissions.isNotEmpty() || preferences.contains("download_tree_uri")) InventoryPresence.PRESENT
            else InventoryPresence.ABSENT
        }
        location == "shared-prefs/unclassified/*" -> {
            val selectors = ProvenanceDomain.values().flatMap { it.requiredLocations }
                .filter { !it.startsWith("files/") && !it.startsWith("external-files/") &&
                    !it.startsWith("cache/") && !it.startsWith("shared-prefs/") &&
                    !it.startsWith("workmanager/") && !it.startsWith("saf/") }
                .map { it.substringBefore('/') }
            val unclassified = preferenceFiles().filter { file ->
                val name = file.name.removeSuffix(".xml")
                file.name.endsWith(".bak") || (selectors.none { matches(it, name) } && !isTechnicalCatalog(name))
            }
            if (unclassified.isNotEmpty()) InventoryPresence.PRESENT else InventoryPresence.ABSENT
        }
        location.startsWith("files/") -> filePresence(context.filesDir, location.removePrefix("files/"))
        location.startsWith("cache/") -> filePresence(context.cacheDir, location.removePrefix("cache/"))
        location.startsWith("external-files/") -> {
            // Unavailable removable storage is not absence.
            val root = context.getExternalFilesDir(null) ?: error("external storage unavailable")
            filePresence(root, location.removePrefix("external-files/"))
        }
        else -> preferencePresence(location)
    }

    private fun isTechnicalCatalog(name: String): Boolean {
        if (name != "soundwave_provenance_catalog_v1") return false
        val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        if (values.keys != setOf("catalog_v1")) return false
        val raw = values["catalog_v1"] as? String ?: return false
        return ProvenanceCatalogCodec.decode(raw) != null
    }

    private fun preferencePresence(location: String): InventoryPresence {
        val namespace = location.substringBefore('/')
        val key = location.substringAfter('/')
        if ('*' in namespace) {
            val files = preferenceFiles().filter { matches(namespace, it.name.removeSuffix(".xml")) }
            // File exists even if its parsed preferences are empty or a damaged orphan namespace.
            return if (files.isNotEmpty()) InventoryPresence.PRESENT else InventoryPresence.ABSENT
        }
        val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        return if (key == "*") {
            val meaningful = preferences.all.filterKeys { it !in TECHNICAL_KEYS[namespace].orEmpty() }.toMutableMap()
            if (namespace == "soundwave_legacy_local") {
                val snapshot = meaningful[LegacyUnassignedSnapshot.SNAPSHOT_KEY] as? String
                if (snapshot != null && LegacyUnassignedSnapshot.isVerifiedEmptySnapshot(snapshot)) {
                    meaningful.remove(LegacyUnassignedSnapshot.SNAPSHOT_KEY)
                }
            }
            if (meaningful.isNotEmpty() || preferenceFiles().any { it.name == "$namespace.xml.bak" }) {
                InventoryPresence.PRESENT
            } else InventoryPresence.ABSENT
        } else {
            if (preferences.contains(key)) InventoryPresence.PRESENT else InventoryPresence.ABSENT
        }
    }

    private fun filePresence(root: File, suffix: String): InventoryPresence {
        val relative = suffix.removeSuffix("/**")
        if (relative == "**") return childrenPresence(root)
        if ('*' in relative) {
            if (!root.exists()) return InventoryPresence.ABSENT
            require(root.isDirectory && root.canRead())
            val files = root.listFiles() ?: error("file enumeration failed")
            return if (files.any { matches(relative, it.name) }) InventoryPresence.PRESENT else InventoryPresence.ABSENT
        }
        val target = File(root, relative)
        return if (suffix.endsWith("/**")) childrenPresence(target) else checkedPresence(target)
    }

    private fun childrenPresence(root: File): InventoryPresence {
        if (!root.exists()) return checkedPresence(root)
        require(root.isDirectory && root.canRead() && root.canExecute())
        val children = root.listFiles() ?: error("directory enumeration failed")
        // An empty container is not user data. Every actual child (including unknown metadata)
        // remains conservative evidence; no blanket cache/files exclusion.
        return if (children.isEmpty()) InventoryPresence.ABSENT else InventoryPresence.PRESENT
    }

    private fun checkedPresence(file: File): InventoryPresence {
        if (!file.exists()) {
            // A denied ancestor can make exists() return false, which is not reliable absence.
            var parent = file.parentFile
            while (parent != null && !parent.exists()) parent = parent.parentFile
            require(parent != null && parent.canRead() && parent.canExecute())
            return InventoryPresence.ABSENT
        }
        require(file.canRead())
        if (file.isDirectory) require(file.listFiles() != null)
        return InventoryPresence.PRESENT
    }

    private fun matches(pattern: String, value: String): Boolean =
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(value)
}
