package xyz.cncnlp.soundwave.startup

import xyz.cncnlp.soundwave.MediaCacheLeaseManager
import xyz.cncnlp.soundwave.cache.AtomicCacheFile
import java.io.File

/** Removes only abandoned staging names produced by app-managed cache and download writers. */
internal object StartupTempFileCleaner {
    private val mediaDecryptTemp = Regex(
        """^dec_(?:[A-Za-z0-9._-]{1,60}|[a-f0-9]{64})\.(?:mflac|mgg|ncm|tkm)\.(?:part|plain\.part)$"""
    )
    private val downloadPlainTemp = Regex(
        """^sw-[A-Za-z0-9_-]{1,32}-[A-Za-z0-9_-]+\.(?:mflac|mgg|ncm|tkm|flac|m4a|ape|ogg|mp3)\.plain\.part$"""
    )
    private val coverTemp = Regex("""^cover-[a-f0-9]{12}-[A-Za-z0-9]+\.part$""")

    /** Scan only the known staging directories; never recurse or remove directories. */
    fun cleanup(
        mediaDecryptDir: File,
        downloadStagingDir: File,
        coverCacheDir: File? = null,
        lyricCacheDir: File? = null
    ): Int =
        deleteMatching(mediaDecryptDir, mediaDecryptTemp) +
            deleteMatching(downloadStagingDir, downloadPlainTemp) +
            (coverCacheDir?.let { deleteMatching(it, coverTemp) } ?: 0) +
            (lyricCacheDir?.let { directory ->
                val entries = try { directory.listFiles() } catch (_: Exception) { null }.orEmpty()
                entries.count { entry ->
                    try {
                        entry.isFile && AtomicCacheFile.isStagingName(entry) && MediaCacheLeaseManager.deleteIfUnleased(entry)
                    } catch (_: Exception) { false }
                }
            } ?: 0)

    private fun deleteMatching(directory: File, pattern: Regex): Int {
        val entries = try {
            directory.listFiles()
        } catch (_: Exception) {
            null
        } ?: return 0

        return entries.count { entry ->
            try {
                entry.isFile && pattern.matches(entry.name) && MediaCacheLeaseManager.deleteIfUnleased(entry)
            } catch (_: Exception) {
                false
            }
        }
    }
}
