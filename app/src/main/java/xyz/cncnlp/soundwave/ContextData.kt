package xyz.cncnlp.soundwave

import android.content.Context
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileOwner
import xyz.cncnlp.soundwave.migration.ProfileCatalog
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/** Explicit selectors; recorded owner must have provenance, no nullable-ID/global fallback. */
internal class ContextData(private val context: Context, val owner: ProfileContext,
    private val cancellationEpoch: Long? = null) {
    private fun profile(): ProfileId? {
        check(ProfileCatalog.isCurrent(context, owner)) { "资料身份无效或已过期" }
        return when (val identity = owner.owner) {
            ProfileOwner.LegacyUnassigned -> null
            is ProfileOwner.RecordedProfile -> identity.id
        }
    }
    fun load(): LocalStore.UserBundle = profile()?.let { LocalStore.load(context, it) } ?: LocalStore.load(context)
    fun history(): List<Song> = profile()?.let { LocalStore.loadHistory(context, it) } ?: LocalStore.loadHistory(context)
    private fun requireRuntimeMutation() { check(ProfileWorkIdentity.isWork(owner)) { "Inactive or expired context cannot mutate runtime data" } }
    fun save(bundle: LocalStore.UserBundle) {
        requireRuntimeMutation()
        val id = profile()
        check(xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory.invalidateBeforeMutation(context))
        check(ProfileWorkIdentity.commitWork(owner) { if (id == null) LocalStore.save(context, bundle) else LocalStore.save(context, id, bundle); true } == true)
    }
    fun saveWithHistory(bundle: LocalStore.UserBundle, history: List<Song>, accountOwner: String? = null) {
        requireRuntimeMutation()
        val id = profile()
        check(id == null) { "账号同步快照不得写入资料命名空间" }
        check(xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory.invalidateBeforeMutation(context))
        check(ProfileWorkIdentity.commitWork(owner) {
            LocalStore.saveWithIndependentHistory(context, bundle, history, accountOwner)
            true
        } == true)
    }
    fun pushHistory(song0: Song, limit: Int = Int.MAX_VALUE) {
        requireRuntimeMutation()
        val id = profile()
        // r657 第二轮：写入历史时带上当前队列的播放来源（已有来源的不覆盖）。
        val song = if (song0.playSource.isBlank()) song0.copy(playSource = PlaySourceTracker.label()) else song0
        check(ProfileWorkIdentity.commitWork(owner) { if (id == null) LocalStore.pushHistory(context, song, limit) else LocalStore.pushHistory(context, id, song, limit); true } == true)
    }
    fun removeHistory(song: Song) {
        requireRuntimeMutation()
        val id = profile()
        check(ProfileWorkIdentity.commitWork(owner) { if (id == null) LocalStore.removeHistory(context, song) else LocalStore.removeHistory(context, id, song); true } == true)
    }
    fun clearHistory() {
        requireRuntimeMutation()
        val id = profile()
        check(ProfileWorkIdentity.commitWork(owner) { if (id == null) LocalStore.clearHistory(context) else LocalStore.clearHistory(context, id); true } == true)
    }
    fun downloads(): List<DownloadItem> = profile()?.let { LocalStore.loadDownloads(context, it) } ?: LocalStore.loadDownloads(context)
    fun addDownload(item: DownloadItem) {
        requireRuntimeMutation()
        val id = profile()
        check(ProfileWorkIdentity.commitWork(owner) { if (id == null) LocalStore.addDownload(context, item) else LocalStore.addDownload(context, id, item); true } == true)
    }
    fun addDownloadDurably(item: DownloadItem): Boolean {
        val id = profile()
        // Inactive profiles do not own device artifacts and cannot execute helpers.
        if (id != null) return false
        if (cancellationEpoch != null) return ProfileWorkIdentity.commitPersistentWork(context, owner, cancellationEpoch) {
            LocalStore.addDownloadDurably(context, item)
        } == true
        return if (owner.generation == 0L) LocalStore.addDownloadDurably(context, item)
        else ProfileWorkIdentity.commit(owner) { LocalStore.addDownloadDurably(context, item) } == true
    }
    fun topSongs(limit: Int = 10): List<ListenStats.SongStat> = profile()?.let { ListenStats.topSongs(context, it, limit) } ?: ListenStats.topSongs(context, limit)
    fun topArtists(limit: Int = 10): List<ListenStats.ArtistStat> = profile()?.let { ListenStats.topArtists(context, it, limit) } ?: ListenStats.topArtists(context, limit)
    fun topPlatforms(limit: Int = 3): List<Pair<String, Long>> = profile()?.let { ListenStats.topPlatforms(context, it, limit) } ?: ListenStats.topPlatforms(context, limit)
    fun todaySec(): Long = profile()?.let { ListenStats.todaySec(context, it) } ?: ListenStats.todaySec(context)
    fun weekSec(): Long = profile()?.let { ListenStats.weekSec(context, it) } ?: ListenStats.weekSec(context)
    fun totalSec(): Long = profile()?.let { ListenStats.totalSec(context, it) } ?: ListenStats.totalSec(context)
    fun monthDailySeconds(now: Long): List<Long> = profile()?.let { ListenStats.monthDailySeconds(context, it, now) } ?: ListenStats.monthDailySeconds(context, now)
    fun monthStats(month: java.time.YearMonth, now: Long): ListenStats.MonthStats = profile()?.let { ListenStats.monthStats(context, it, month, now) } ?: ListenStats.monthStats(context, month, now)
    fun markPlay(song: Song): Boolean {
        requireRuntimeMutation()
        val id = profile()
        return ProfileWorkIdentity.commitWork(owner) { if (id == null) ListenStats.markPlay(context, song) else ListenStats.markPlay(context, id, song) } == true
    }
    fun addDuration(song: Song, sec: Long): Boolean {
        requireRuntimeMutation()
        val id = profile()
        return ProfileWorkIdentity.commitWork(owner) { if (id == null) ListenStats.addDuration(context, song, sec) else ListenStats.addDuration(context, id, song, sec) } == true
    }
    internal fun markPlay(song: Song, accountUser: String?): Boolean {
        requireRuntimeMutation()
        val id = profile()
        return ProfileWorkIdentity.commitWork(owner) {
            if (id == null) ListenStats.markPlay(context, song, accountUser) else ListenStats.markPlay(context, id, song)
        } == true
    }
    internal fun addDuration(song: Song, sec: Long, accountUser: String?): Boolean {
        requireRuntimeMutation()
        val id = profile()
        return ProfileWorkIdentity.commitWork(owner) {
            if (id == null) ListenStats.addDuration(context, song, sec, accountUser) else ListenStats.addDuration(context, id, song, sec)
        } == true
    }
    fun disliked(song: Song): Boolean = song.key() in dislikePreferences().getStringSet("dislike_keys_v1", emptySet()).orEmpty()
    private fun dislikePreferences(): android.content.SharedPreferences {
        val id = profile()
        return context.getSharedPreferences(if (id == null) "soundwave" else "soundwave_dislikes_profile_${id.value}", 0)
    }
    fun toggleDislike(song: Song): Boolean {
        requireRuntimeMutation()
        val preferences = dislikePreferences()
        val keys = preferences.getStringSet("dislike_keys_v1", emptySet()).orEmpty().toMutableSet()
        val now = if (song.key() in keys) { keys.remove(song.key()); false } else { keys.add(song.key()); true }
        check(xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory.invalidateBeforeMutation(context))
        check(ProfileWorkIdentity.commitWork(owner) { preferences.edit().putStringSet("dislike_keys_v1", keys).apply(); true } == true)
        return now
    }
    fun scripts(): List<Script> {
        val id = profile()
        return if (id == null) ScriptStorage.loadScripts(ContextPreferences.open(context, "soundwave_scripts", 0, owner))
            else ScriptStorage.loadScripts(context, id)
    }
}
