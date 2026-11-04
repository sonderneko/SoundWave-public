package xyz.cncnlp.soundwave.migration

import android.content.Context
import xyz.cncnlp.soundwave.AvatarStore
import xyz.cncnlp.soundwave.LocalStore

/**
 * 账号系统去除的一次性升级迁移（幂等，可重复执行）：
 * 1. 清除本机会话数据：登录密码密文（pass/pass_owner）、登录态（logged_in）、云同步同意
 *    （account_sync_consent_user_v1）。prefs `user` 保留，语义转为本机资料名。
 * 2. 若升级时处于登出态（资料数据已封存在快照区、主命名空间无归属），把归属本机资料名
 *    的封存快照恢复进主命名空间，保证收藏/歌单/历史在去除后原样可见。
 * 3. 解除本机资料名的头像退休标记，使既有头像文件作为本机资料头像继续显示。
 * 不触碰任何服务器数据，不改变本地数据格式。
 */
object LocalProfileMigration {
    private const val APP_PREFS = "soundwave"
    private const val MIGRATION_PREFS = "sw_migrations_v1"
    private const val MIGRATION_KEY = "soundwave_account_removed_local_profile_v1"
    private const val SYNC_CONSENT_KEY = "account_sync_consent_user_v1"

    fun run(context: Context) {
        val app = context.applicationContext
        val migrations = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (migrations.getBoolean(MIGRATION_KEY, false)) return
        val prefs = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val localUser = prefs.getString("user", "").orEmpty().trim()
        // 1. 会话数据清除（commit 成功才继续，避免半迁移状态被标记完成）。
        val sessionCleared = runCatching {
            prefs.edit()
                .remove("pass")
                .remove("pass_owner")
                .remove("logged_in")
                .remove(SYNC_CONSENT_KEY)
                .commit()
        }.getOrDefault(false)
        if (!sessionCleared) return
        // 2. 登出态封存数据回归主命名空间（已登录态时主命名空间本就有归属，函数自动跳过）。
        if (localUser.isNotBlank()) {
            runCatching { LocalStore.restoreParkedScopeAsLocalProfile(app, localUser) }
            // 3. 头像退休标记解除（登出曾退休该名头像关联；字节从未删除）。
            runCatching { AvatarStore.clearRetiredUsername(app, localUser) }
        }
        migrations.edit().putBoolean(MIGRATION_KEY, true).apply()
    }
}
