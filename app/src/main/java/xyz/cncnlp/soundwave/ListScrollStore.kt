package xyz.cncnlp.soundwave

import android.content.Context

/** 歌单/列表滚动位置记忆（洛雪 isSaveScrollLocation）。 */
object ListScrollStore {
    private const val PREF = "list_scroll_v1"
    private val legacyOwner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()

    fun save(ctx: Context, listId: String, index: Int, offset: Int = 0, owner: xyz.cncnlp.soundwave.migration.ProfileContext = legacyOwner) {
        if (listId.isBlank()) return
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx, PREF, 0, owner).edit()
            .putInt("i_$listId", index.coerceAtLeast(0))
            .putInt("o_$listId", offset)
            .apply()
    }

    fun index(ctx: Context, listId: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = legacyOwner): Int =
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx, PREF, 0, owner).getInt("i_$listId", 0)

    fun offset(ctx: Context, listId: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = legacyOwner): Int =
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx, PREF, 0, owner).getInt("o_$listId", 0)
}
