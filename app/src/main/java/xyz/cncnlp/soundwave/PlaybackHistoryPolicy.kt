package xyz.cncnlp.soundwave

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Shared age, ordering, duplicate, and cross-device delete policy for play history. */
internal object PlaybackHistoryPolicy {
    // ACCEPT5-STATSMONTH（用户 2026-11-04 14:08 定版）：本机播放历史保留 7 天 → 30 天，与 WebDAV 同步窗口一致。
    const val LOCAL_RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L
    const val CLOUD_RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L
    const val HISTORY_DELETE_TOMBSTONE_RETENTION_MS: Long = 90L * 24L * 60L * 60L * 1000L
    const val CLOUD_SYNC_LIMIT: Int = 300
    /** Backward-compatible name used by local-retention tests and callers. */
    const val RETENTION_MS: Long = LOCAL_RETENTION_MS

    fun isRetained(song: Song, nowMs: Long): Boolean =
        song.playedAt > 0L && song.playedAt <= nowMs && song.playedAt >= nowMs - LOCAL_RETENTION_MS

    fun isRetainedForCloudSync(song: Song, nowMs: Long): Boolean =
        song.playedAt > 0L && song.playedAt <= nowMs && song.playedAt >= nowMs - CLOUD_RETENTION_MS

    /** Stable opaque identity for one play event; replaying the same song creates a distinct event. */
    fun historyEventKey(song: Song): String {
        val identity = song.key() + "\u0000" + song.playedAt
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
        return "h1:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun retainHistoryDeletionTombstones(
        tombstones: Map<String, Long>,
        nowMs: Long = System.currentTimeMillis()
    ): MutableMap<String, Long> = linkedMapOf<String, Long>().apply {
        val oldest = nowMs - HISTORY_DELETE_TOMBSTONE_RETENTION_MS
        val latestAllowed = nowMs + HISTORY_DELETE_TOMBSTONE_RETENTION_MS
        tombstones.entries
            .asSequence()
            .filter { (key, deletedAt) -> isValidHistoryEventKey(key) && deletedAt in oldest..latestAllowed }
            .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .forEach { (key, deletedAt) -> put(key, deletedAt) }
    }

    fun mergeHistoryDeletionTombstones(
        first: Map<String, Long>,
        second: Map<String, Long>,
        nowMs: Long = System.currentTimeMillis()
    ): MutableMap<String, Long> {
        val merged = linkedMapOf<String, Long>()
        (first.keys + second.keys).forEach { key ->
            merged[key] = maxOf(first[key] ?: Long.MIN_VALUE, second[key] ?: Long.MIN_VALUE)
        }
        return retainHistoryDeletionTombstones(merged, nowMs)
    }

    fun withoutDeletedHistory(
        songs: List<Song>,
        tombstones: Map<String, Long>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Song> {
        if (songs.isEmpty() || tombstones.isEmpty()) return songs
        val deleted = retainHistoryDeletionTombstones(tombstones, nowMs).keys
        if (deleted.isEmpty()) return songs
        return songs.filterNot { historyEventKey(it) in deleted }
    }

    private fun isValidHistoryEventKey(key: String): Boolean =
        key.length == 67 && key.startsWith("h1:") && key.substring(3).all { it in '0'..'9' || it in 'a'..'f' }

    /** Keep the newest play per stable song identity, then return newest-first with a stable tie order. */
    fun retainRecent(
        songs: List<Song>,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = Int.MAX_VALUE
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val newestByKey = linkedMapOf<String, Song>()
        songs.forEach { song ->
            if (!isRetained(song, nowMs)) return@forEach
            val previous = newestByKey[song.key()]
            if (previous == null || song.playedAt > previous.playedAt) newestByKey[song.key()] = song
        }
        return newestByKey.values
            .sortedWith(compareByDescending<Song> { it.playedAt }.thenBy { it.key() })
            .take(limit)
    }

    /** Cloud payload policy: only valid history from the last 30 days, newest 300 unique songs. */
    fun retainForCloudSync(
        songs: List<Song>,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = CLOUD_SYNC_LIMIT
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val newestByKey = linkedMapOf<String, Song>()
        songs.forEach { song ->
            if (!isRetainedForCloudSync(song, nowMs)) return@forEach
            val previous = newestByKey[song.key()]
            if (previous == null || song.playedAt > previous.playedAt) newestByKey[song.key()] = song
        }
        return newestByKey.values
            .sortedWith(compareByDescending<Song> { it.playedAt }.thenBy { it.key() })
            .take(limit)
    }
}
