package xyz.cncnlp.soundwave.download

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/** Stable download identity; do not use Song.key(), whose display normalization is intentionally lossy. */
internal object DownloadTaskIdentity {
    private const val PREFIX = "soundwave_download_"

    /** Full structured identity for download-record matching and queue de-duplication. */
    fun songKey(song: Song): String {
        val source = song.source.trim().lowercase(Locale.ROOT)
        val fields = if (song.id.isNotBlank()) {
            listOf("provider-id", source, song.id.trim())
        } else {
            listOf(
                "metadata", source, song.name, song.singer, song.duration.toString(), song.album,
                song.albumId, song.hash, song.copyrightId, song.mediaMid
            )
        }
        return JSONArray(fields).toString()
    }

    fun taskId(song: Song, quality: String): String {
        val identity = JSONArray(
            listOf(
                "download-task-v3",
                ProfileWorkIdentity.LEGACY_OWNER,
                songKey(song),
                quality.trim().lowercase(Locale.ROOT).ifBlank { "320k" }
            )
        ).toString()
        return "${PREFIX}v3_${sha256(identity)}"
    }

    /** Exact v2 formula, used only to allow an already-enqueued worker to finish after upgrade. */
    fun previousV2TaskId(song: Song, quality: String): String {
        val identity = JSONArray(
            listOf(
                ProfileWorkIdentity.LEGACY_OWNER,
                song.source,
                song.id,
                quality.ifBlank { "320k" }
            )
        ).toString()
        return "${PREFIX}v2_${sha256(identity)}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
