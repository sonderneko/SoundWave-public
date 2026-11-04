package xyz.cncnlp.soundwave

import android.content.Context

/** 本地不喜欢/屏蔽：影响心动模式与推荐过滤，不同步到公开源。 */
object DislikeStore {
    private const val PREF = "soundwave"
    private const val KEY = "dislike_keys_v1"

    fun keys(ctx: Context): Set<String> =
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).getStringSet(KEY, emptySet())?.toSet().orEmpty()

    private const val IDENTITY_KEY = "dislike_recordings_v2"
    fun identities(ctx: Context): Set<String> = ContextPreferences.open(ctx, PREF, 0,
        xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).getStringSet(IDENTITY_KEY, emptySet())?.toSet().orEmpty()

    @Synchronized fun mark(ctx: Context, song: Song) {
        val prefs = ContextPreferences.open(ctx, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val ids = identities(ctx).toMutableSet()
        PersonalRecommendations.songIdentity(song).takeIf { it.isNotBlank() }?.let(ids::add)
        prefs.edit().putStringSet(KEY, keys(ctx) + song.key()).putStringSet(IDENTITY_KEY, ids).apply()
    }

    /** A 波：撤销「不感兴趣」用——同时移除 key 与 identity 两集合，与 mark() 严格对称（toggle 只动 key 集合，不够）。 */
    @Synchronized fun unmark(ctx: Context, song: Song) {
        val prefs = ContextPreferences.open(ctx, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val ids = identities(ctx).toMutableSet()
        PersonalRecommendations.songIdentity(song).takeIf { it.isNotBlank() }?.let(ids::remove)
        prefs.edit().putStringSet(KEY, keys(ctx) - song.key()).putStringSet(IDENTITY_KEY, ids).apply()
    }

    /** WebDAV 同步应用：整节替换两集合（分节 LWW 的获胜节为准）。 */
    @Synchronized fun replaceAll(ctx: Context, keys: Set<String>, identities: Set<String>) {
        val prefs = ContextPreferences.open(ctx, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        prefs.edit().putStringSet(KEY, keys).putStringSet(IDENTITY_KEY, identities).apply()
    }

    fun isDisliked(ctx: Context, song: Song): Boolean = song.key() in keys(ctx) || PersonalRecommendations.songIdentity(song) in identities(ctx)

    fun toggle(ctx: Context, song: Song): Boolean {
        val p = xyz.cncnlp.soundwave.ContextPreferences.open(ctx, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val cur = p.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val k = song.key()
        val now = if (k in cur) {
            cur.remove(k); false
        } else {
            cur.add(k); true
        }
        p.edit().putStringSet(KEY, cur).apply()
        return now
    }

    fun filter(ctx: Context, songs: List<Song>): List<Song> {
        val bad = keys(ctx)
        val identities = identities(ctx)
        return songs.filter { it.key() !in bad && PersonalRecommendations.songIdentity(it) !in identities }
    }
}
