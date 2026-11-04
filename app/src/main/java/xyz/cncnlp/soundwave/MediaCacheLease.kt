package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/** A reference-counted claim that prevents cache cleanup from unlinking one media file. */
class MediaCacheLease internal constructor(
    val file: File,
    private val key: String,
    private val pathOverride: String? = null
) : AutoCloseable {
    val path: String get() = pathOverride ?: file.absolutePath
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) MediaCacheLeaseManager.release(key)
    }
}

internal data class CachePathDeletion(
    val deletedFiles: Int,
    val skippedLeasePaths: Set<String>,
    val failedPaths: List<File>
)

/** Serializes lease registration with every cache unlink, including recursive directory removal. */
internal object MediaCacheLeaseManager {
    private val lock = Any()
    private val leaseCounts = mutableMapOf<String, Int>()

    /** Reserve a path before creating a staging file, while ensuring its parent cannot vanish. */
    fun reserveInDirectory(directory: File, file: File): MediaCacheLease? = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs()) return@synchronized null
        if (!directory.isDirectory) return@synchronized null
        newLease(file)
    }

    /** Atomically verify a completed file and acquire it before returning the path to a caller. */
    fun acquireExisting(file: File): MediaCacheLease? = synchronized(lock) {
        if (!file.isFile || file.length() <= 0L) return@synchronized null
        newLease(file)
    }

    /** Claim a local playback resource before handing it to ExoPlayer. */
    fun acquireForPlayback(path: String): MediaCacheLease? {
        val uri = try { Uri.parse(path) } catch (_: Exception) { return null }
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let { acquireExisting(File(it)) }
            "content" -> synchronized(lock) { newLease(File(path), contentKey(path), path) }
            null -> if (path.startsWith("/") && !path.contains("://")) acquireExisting(File(path)) else null
            else -> null
        }
    }

    fun requiresPlaybackLease(path: String): Boolean {
        val uri = try { Uri.parse(path) } catch (_: Exception) { return false }
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file", "content" -> true
            null -> path.startsWith("/") && !path.contains("://")
            else -> false
        }
    }

    /** The trim path calls this for each victim; false means an owner currently holds the file. */
    fun deleteIfUnleased(file: File): Boolean = synchronized(lock) {
        if (leasePathsUnderLocked(keyOf(file)).isNotEmpty()) return@synchronized false
        !file.exists() || file.delete()
    }

    /** Serialize provider deletion against content-URI playback lease acquisition. */
    fun deleteContentUriIfUnleased(uri: String, delete: () -> Boolean): Boolean = synchronized(lock) {
        if (leasePathsUnderLocked(contentKey(uri)).isNotEmpty()) return@synchronized false
        try { delete() } catch (_: Exception) { false }
    }

    /** Delete unowned entries but leave every leased file and its required parent directories. */
    fun deleteRecursively(file: File): CachePathDeletion {
        val deleted = intArrayOf(0)
        val skipped = linkedSetOf<String>()
        val failed = mutableListOf<File>()
        deleteNode(file, deleted, skipped, failed)
        return CachePathDeletion(deleted[0], skipped, failed)
    }

    /** Includes reserved paths that have not reached disk yet, so clearAll can report them. */
    fun leasePathsUnder(directory: File): Set<String> = synchronized(lock) {
        leasePathsUnderLocked(keyOf(directory)).toSet()
    }

    private fun newLease(file: File): MediaCacheLease = newLease(File(file.absolutePath), keyOf(file))

    private fun newLease(file: File, key: String, pathOverride: String? = null): MediaCacheLease {
        leaseCounts[key] = (leaseCounts[key] ?: 0) + 1
        return MediaCacheLease(File(file.path), key, pathOverride)
    }

    internal fun release(key: String) = synchronized(lock) {
        val count = leaseCounts[key] ?: return@synchronized
        if (count <= 1) leaseCounts.remove(key) else leaseCounts[key] = count - 1
    }

    private fun deleteNode(file: File, deleted: IntArray, skipped: MutableSet<String>, failed: MutableList<File>) {
        val symbolicLink = try { Files.isSymbolicLink(file.toPath()) } catch (_: Exception) { false }
        if (file.isDirectory && !symbolicLink) {
            val children = file.listFiles()
            if (children == null) {
                if (file.exists()) failed += file
                return
            }
            children.forEach { deleteNode(it, deleted, skipped, failed) }
        }

        synchronized(lock) {
            val active = leasePathsUnderLocked(keyOf(file))
            if (active.isNotEmpty()) {
                skipped += active
                return
            }
            if (!file.exists() && !symbolicLink) return
            val isFile = file.isFile || symbolicLink
            if (file.delete()) {
                if (isFile) deleted[0]++
            } else if (file.exists() || symbolicLink) {
                failed += file
            }
        }
    }

    private fun leasePathsUnderLocked(root: String): List<String> {
        val prefix = root + File.separator
        return leaseCounts.keys.filter { it == root || it.startsWith(prefix) }
    }

    private fun keyOf(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absoluteFile.normalize().path
    }

    private fun contentKey(uri: String): String = "uri:${Uri.parse(uri).normalizeScheme()}"
}

/** Delete only local media after confirming no active player holds its resource lease. */
internal fun deleteLocalMediaResource(context: Context, path: String): Boolean {
    return try {
        when {
            path.startsWith("content://", ignoreCase = true) -> {
                val uri = Uri.parse(path)
                MediaCacheLeaseManager.deleteContentUriIfUnleased(path) {
                    context.contentResolver.delete(uri, null, null) > 0 ||
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.exists() == false
                }
            }
            path.startsWith("file://", ignoreCase = true) -> {
                val filePath = Uri.parse(path).path ?: return false
                MediaCacheLeaseManager.deleteIfUnleased(File(filePath))
            }
            path.startsWith("/") && !path.contains("://") ->
                MediaCacheLeaseManager.deleteIfUnleased(File(path))
            else -> false
        }
    } catch (_: Exception) { false }
}
