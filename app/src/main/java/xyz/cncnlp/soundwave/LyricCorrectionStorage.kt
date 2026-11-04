package xyz.cncnlp.soundwave

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** User-confirmed lyric overrides are kept outside the evictable network lyric cache. */
internal object LyricCorrectionStorage {
    private class FileStore(context: Context, private val owner: xyz.cncnlp.soundwave.migration.ProfileContext) : LyricCorrectionOverrideStore {
        init { check(xyz.cncnlp.soundwave.migration.ProfileCatalog.isCurrent(context, owner)) }
        private val directory = File(context.applicationContext.filesDir, when (val identity = owner.owner) {
            xyz.cncnlp.soundwave.migration.ProfileOwner.LegacyUnassigned -> "lyric_corrections"
            is xyz.cncnlp.soundwave.migration.ProfileOwner.RecordedProfile -> "profile_lyric_corrections/${identity.id.value}"
        })

        private fun file(identity: String): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return File(directory, "correction-v1-$digest.lrc")
        }

        override fun load(identity: String): String = runCatching {
            file(identity).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")

        override fun save(identity: String, lyrics: String): Boolean {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(owner) || identity.isBlank() || lyrics.isBlank()) return false
            return runCatching {
                if (!directory.isDirectory && !directory.mkdirs()) return false
                val target = file(identity)
                val temporary = File(directory, "${target.name}.tmp")
                temporary.writeText(lyrics, Charsets.UTF_8)
                if (target.exists() && !target.delete()) {
                    temporary.delete()
                    return false
                }
                if (!temporary.renameTo(target)) {
                    target.writeText(lyrics, Charsets.UTF_8)
                    temporary.delete()
                }
                target.isFile && target.length() > 0L
            }.getOrDefault(false)
        }

        override fun clear(identity: String): Boolean = runCatching {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(owner)) return false
            val target = file(identity)
            !target.exists() || target.delete()
        }.getOrDefault(false)
    }

    private fun repository(context: Context, owner: xyz.cncnlp.soundwave.migration.ProfileContext) = LyricCorrectionRepository(FileStore(context, owner))

    fun load(context: Context, song: Song, owner: xyz.cncnlp.soundwave.migration.ProfileContext = PlayerService.snapshot().ownerContext): String = repository(context, owner).load(song)

    fun confirm(context: Context, song: Song, candidate: LyricCorrectionCandidate, owner: xyz.cncnlp.soundwave.migration.ProfileContext = PlayerService.snapshot().ownerContext): Boolean =
        repository(context, owner).confirm(song, candidate)

    fun clear(context: Context, song: Song, owner: xyz.cncnlp.soundwave.migration.ProfileContext = PlayerService.snapshot().ownerContext): Boolean = repository(context, owner).clear(song)
}
