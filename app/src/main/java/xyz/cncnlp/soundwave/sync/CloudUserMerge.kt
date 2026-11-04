package xyz.cncnlp.soundwave.sync

import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.PlaybackHistoryPolicy
import xyz.cncnlp.soundwave.ListenStatsDailyStore
import xyz.cncnlp.soundwave.playlistDisplayCover
import xyz.cncnlp.soundwave.cloudSafeCover

/** One merge policy for startup, explicit login, and later account refreshes. */
internal object CloudUserMerge {
    fun merge(
        local: LocalStore.UserBundle,
        cloud: LocalStore.UserBundle,
        nowMs: Long = System.currentTimeMillis()
    ): LocalStore.UserBundle {
        val byId = linkedMapOf<String, Playlist>()
        cloud.playlists.filterNot(::isEphemeral).forEach { byId[it.id] = it }
        local.playlists.filterNot(::isEphemeral).forEach { lp ->
            val c = byId[lp.id]
            if (c == null) {
                byId[lp.id] = lp
            } else {
                val songs = mergeSongs(c.songs, lp.songs, preserveKnownDuration = lp.id.startsWith("local_"))
                val identity = lp.copy(source = lp.source.ifEmpty { c.source }, local = lp.local && c.local)
                val localCoverIsGenerated = lp.cover.isNotBlank() &&
                    lp.songs.firstOrNull { it.cover.isNotBlank() }?.cover == lp.cover
                val cover = when {
                    localCoverIsGenerated && c.cover.isNotBlank() -> c.cover
                    lp.cover.isNotBlank() -> lp.cover
                    else -> c.cover.ifEmpty { playlistDisplayCover(identity, songs) }
                }
                val cloudCover = when {
                    !localCoverIsGenerated && cloudSafeCover(lp.cover, includeLocalUris = false).isNotBlank() -> lp.cover
                    cloudSafeCover(c.cover, includeLocalUris = false).isNotBlank() -> c.cover
                    cloudSafeCover(lp.cover, includeLocalUris = false).isNotBlank() -> lp.cover
                    cloudSafeCover(lp.syncCover, includeLocalUris = false).isNotBlank() -> lp.syncCover
                    cloudSafeCover(c.syncCover, includeLocalUris = false).isNotBlank() -> c.syncCover
                    else -> ""
                }
                val defaults = setOf("我的喜欢", "本地歌单", "默认歌单", "歌单")
                val name = when {
                    c.name.isNotBlank() && c.name !in defaults -> c.name
                    lp.name.isNotBlank() -> lp.name
                    else -> c.name.ifEmpty { lp.name }
                }
                val order = mergeOrderMetadata(c, lp)
                val favOrder = mergeFavOrderMetadata(c, lp)
                byId[lp.id] = c.copy(
                    name = name,
                    songs = songs.toMutableList(),
                    cover = cover,
                    syncCover = cloudCover,
                    source = c.source.ifEmpty { lp.source },
                    local = lp.local && c.local,
                    hidden = c.hidden || lp.hidden,
                    playCount = maxOf(c.playCount, lp.playCount),
                    syncCreatedAt = maxOf(c.syncCreatedAt, lp.syncCreatedAt),
                    syncOrder = order.first,
                    syncOrderAt = order.second,
                    favSyncOrder = favOrder.first,
                    favSyncOrderAt = favOrder.second
                )
            }
        }

        val merged = LocalStore.UserBundle(
            favs = mergeSongs(cloud.favs, local.favs).toMutableList(),
            playlists = byId.values.toMutableList(),
            enabledScripts = (cloud.enabledScripts + local.enabledScripts).distinct().toMutableList(),
            scriptOrder = if (cloud.scriptOrder.isNotEmpty()) cloud.scriptOrder.toMutableList() else local.scriptOrder.toMutableList(),
            avatarB64 = cloud.avatarB64.ifEmpty { local.avatarB64 },
            history = PlaybackHistoryPolicy.retainForCloudSync(
                PlaybackHistoryPolicy.withoutDeletedHistory(
                    local.history + cloud.history,
                    PlaybackHistoryPolicy.mergeHistoryDeletionTombstones(
                        cloud.historyDeletionTombstones,
                        local.historyDeletionTombstones,
                        nowMs
                    ),
                    nowMs
                ),
                nowMs
            ).toMutableList(),
            // Undated pre-version counters cannot be classified as recent; retain local legacy data only.
            songPlayCountCounters = local.songPlayCountCounters.mapValuesTo(linkedMapOf()) { it.value.toMutableMap() },
            dailyListenStatsOwnerV2 = local.dailyListenStatsOwnerV2 ?: cloud.dailyListenStatsOwnerV2,
            dailyListenStatsJson = when {
                local.dailyListenStatsOwnerV2 != null -> if (local.dailyListenStatsOwnerV2 == cloud.dailyListenStatsOwnerV2) {
                    ListenStatsDailyStore.mergeLocalAndCloud(local.dailyListenStatsJson, cloud.dailyListenStatsJson, nowMs)
                } else local.dailyListenStatsJson
                cloud.dailyListenStatsOwnerV2 != null -> cloud.dailyListenStatsJson
                else -> ListenStatsDailyStore.mergeLocalAndCloud(local.dailyListenStatsJson, cloud.dailyListenStatsJson, nowMs)
            },
            todayStylePreferencesJson = xyz.cncnlp.soundwave.recommend.StylePreferenceSync.merge(
                local.todayStylePreferencesJson,
                cloud.todayStylePreferencesJson
            ),
            deletedFavoriteKeys = mergeTimestamps(cloud.deletedFavoriteKeys, local.deletedFavoriteKeys),
            deletedPlaylistIds = mergeTimestamps(cloud.deletedPlaylistIds, local.deletedPlaylistIds),
            deletedPlaylistSongKeys = mergeNestedTimestamps(cloud.deletedPlaylistSongKeys, local.deletedPlaylistSongKeys),
            historyDeletionTombstones = PlaybackHistoryPolicy.mergeHistoryDeletionTombstones(
                cloud.historyDeletionTombstones,
                local.historyDeletionTombstones,
                nowMs
            )
        )
        merged.reconcileSyncTombstones(nowMs)
        merged.ensureDefaults()
        return merged
    }

    private fun isEphemeral(playlist: Playlist): Boolean =
        playlist.id.startsWith("rank_") || playlist.id.startsWith("hot_") || playlist.id.startsWith("reco_")

    /** Favorites order metadata mirrors [mergeOrderMetadata] on its own field pair. */
    private fun mergeFavOrderMetadata(cloud: Playlist, local: Playlist): Pair<Int?, Long> {
        val winner = when {
            local.favSyncOrderAt > cloud.favSyncOrderAt -> local
            cloud.favSyncOrderAt > local.favSyncOrderAt -> cloud
            local.favSyncOrder != null && cloud.favSyncOrder == null -> local
            cloud.favSyncOrder != null && local.favSyncOrder == null -> cloud
            local.favSyncOrder != null -> local
            else -> cloud
        }
        return winner.favSyncOrder to winner.favSyncOrderAt
    }

    /** Order metadata is a per-playlist cloud field; a same-revision conflict favors local input. */
    private fun mergeOrderMetadata(cloud: Playlist, local: Playlist): Pair<Int?, Long> {
        val winner = when {
            local.syncOrderAt > cloud.syncOrderAt -> local
            cloud.syncOrderAt > local.syncOrderAt -> cloud
            local.syncOrder != null && cloud.syncOrder == null -> local
            cloud.syncOrder != null && local.syncOrder == null -> cloud
            local.syncOrder != null -> local
            else -> cloud
        }
        return winner.syncOrder to winner.syncOrderAt
    }

    /** Union song additions; for duplicate identities, keep the newest explicit add and favor local on ties. */
    private fun mergeSongs(cloud: List<Song>, local: List<Song>, preserveKnownDuration: Boolean = false): List<Song> {
        val byKey = linkedMapOf<String, Song>()
        cloud.forEach { byKey[it.key()] = it }
        local.forEach { song ->
            val old = byKey[song.key()]
            val winner = if (old == null || song.syncAddedAt >= old.syncAddedAt) song else old
            // Zero is unknown metadata, not an explicit reset of the playlist's derived total duration.
            byKey[song.key()] = if (preserveKnownDuration && winner.duration <= 0L) {
                winner.copy(duration = maxOf(song.duration, old?.duration ?: 0L).coerceAtLeast(0L))
            } else winner
        }
        return byKey.values.toList()
    }

    private fun mergeTimestamps(
        first: Map<String, Long>,
        second: Map<String, Long>
    ): MutableMap<String, Long> = linkedMapOf<String, Long>().apply {
        (first.keys + second.keys).forEach { key ->
            put(key, maxOf(first[key] ?: Long.MIN_VALUE, second[key] ?: Long.MIN_VALUE))
        }
    }

    private fun mergeNestedTimestamps(
        first: Map<String, Map<String, Long>>,
        second: Map<String, Map<String, Long>>
    ): MutableMap<String, MutableMap<String, Long>> {
        val result = linkedMapOf<String, MutableMap<String, Long>>()
        (first.keys + second.keys).forEach { playlistId ->
            val merged = mergeTimestamps(first[playlistId].orEmpty(), second[playlistId].orEmpty())
            if (merged.isNotEmpty()) result[playlistId] = merged
        }
        return result
    }
}
