package xyz.cncnlp.soundwave

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/** Private, profile-scoped SAF grants. Authorizing a tree never starts a scan. */
internal object LocalScanFolders {
    private const val KEY = "local_scan_tree_uris"
    private val localAuthorities = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents")
    private fun prefs(context: Context) = ContextPreferences.open(context, "soundwave", 0, ProfileWorkIdentity.runtime())

    fun authorized(context: Context): List<Uri> {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val stored = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        val valid = stored.filter { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                uri.scheme == "content" && uri.authority in localAuthorities &&
                    DocumentsContract.isTreeUri(uri) && raw in granted &&
                    DocumentFile.fromTreeUri(context, uri) != null
            }.getOrDefault(false)
        }.toSet()
        val stale = stored - valid
        if (stale.isNotEmpty()) {
            stale.forEach { raw -> runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            prefs(context).edit().putStringSet(KEY, valid).commit()
        }
        return valid.sorted().map(Uri::parse)
    }

    fun add(context: Context, uri: Uri): Boolean = runCatching {
        require(uri.scheme == "content")
        require(uri.authority in localAuthorities) { "仅支持本机文件夹" }
        require(DocumentsContract.isTreeUri(uri)) { "仅支持文件夹授权" }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        val values = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        values.add(uri.toString())
        prefs(context).edit().putStringSet(KEY, values).commit()
    }.getOrDefault(false)

    fun labels(context: Context): List<String> = authorized(context).map { uri ->
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull().orEmpty().ifBlank { "已授权文件夹" }
    }

    /** Bounded local-provider traversal; never infer grants from a path or accept a cloud provider. */
    fun audioDocuments(context: Context, limit: Int): List<Pair<Uri, String>> {
        val results = mutableListOf<Pair<Uri, String>>()
        val visited = mutableSetOf<String>()
        var examined = 0
        val extensions = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "ape", "wma")
        fun walk(file: DocumentFile, depth: Int) {
            if (depth > 8 || results.size >= limit || examined >= 10000 || !visited.add(file.uri.toString())) return
            examined++
            if (file.isDirectory) {
                runCatching { file.listFiles() }.getOrDefault(emptyArray()).forEach { child -> walk(child, depth + 1) }
            } else {
                val name = file.name.orEmpty()
                if (file.canRead() && (file.type?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in extensions)) {
                    results.add(file.uri to name.substringBeforeLast('.').ifBlank { "本地音频" })
                }
            }
        }
        authorized(context).forEach { uri -> runCatching { DocumentFile.fromTreeUri(context, uri)?.let { walk(it, 0) } } }
        return results
    }
}
