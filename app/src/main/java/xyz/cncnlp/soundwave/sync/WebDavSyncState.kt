package xyz.cncnlp.soundwave.sync

import android.content.Context
import xyz.cncnlp.soundwave.ContextPreferences
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * WebDAV 同步的本地记账：上次成功同步时间 + 本机资料最后修改时间。
 * 冲突判定（用户定版口径，整库级）：远端文档 updatedAt 与本机修改时间都晚于上次同步时间
 * → 双边都改过 = 冲突，需用户确认后按较新一方同步；只有一方改过不是冲突。
 * 扩展载荷（WebDavProfileExtras）的分节 LWW 独立运作，不进此整库判定。
 */
internal object WebDavSyncState {
    private const val KEY_LAST_SYNC_AT = "webdav_last_sync_at"
    private const val KEY_LOCAL_CHANGED_AT = "webdav_local_changed_at"

    private fun prefs(context: Context) = ContextPreferences.open(
        context.applicationContext, "soundwave", 0, ProfileWorkIdentity.runtime()
    )

    fun lastSyncAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC_AT, 0L)

    fun localChangedAt(context: Context): Long = prefs(context).getLong(KEY_LOCAL_CHANGED_AT, 0L)

    /** 本机资料（歌单/收藏/历史等 bundle 数据）发生本地修改时调用。 */
    fun markLocalChanged(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LOCAL_CHANGED_AT, at).apply()
    }

    /** 同步成功并已应用结果后调用：本机与远端回到同一基线。 */
    fun recordSuccessfulSync(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC_AT, at)
            .putLong(KEY_LOCAL_CHANGED_AT, at)
            .apply()
    }

    /** 整库冲突判定：从未成功同步过（lastSyncAt == 0）不构成冲突（首次同步直接进行）。 */
    fun hasConflict(context: Context, remoteUpdatedAt: Long): Boolean {
        val last = lastSyncAt(context)
        if (last <= 0L || remoteUpdatedAt <= 0L) return false
        return remoteUpdatedAt > last && localChangedAt(context) > last
    }
}

/**
 * 冲突确认闸门（仅手动同步入口提供；自动同步不弹窗，维持既有字段级合并）。
 * confirm 返回 false = 用户取消，客户端在任何写入前中止，双方数据不动。
 * 客户端同步为阻塞式执行（WebDavOperations.blocking），confirm 在同步线程上
 * 阻塞等待 UI 决定（手动入口用闩锁衔接），不挂起协程。
 */
internal class WebDavSyncConflictGate(
    val lastSyncAt: () -> Long,
    val localChangedAt: () -> Long,
    val confirm: (localChangedAt: Long, remoteUpdatedAt: Long) -> Boolean
)
