package xyz.cncnlp.soundwave.migration

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.security.KeyStore

/**
 * One-time removal of credentials left by the retired third-party music-account feature.
 * SoundWave's own login, cloud-sync credentials, playlists, favorites and history are untouched.
 */
object LegacyPlatformAuthCleanup {
    private const val LEGACY_PREFS = "sw_plat_acct_v1"
    private const val LEGACY_KEY_ALIAS = "soundwave.platform.cookies.v2"
    private const val APP_PREFS = "soundwave"
    private const val PROVIDER_CAPS_KEY = "provider_capabilities_v1"
    private const val MIGRATION_PREFS = "sw_migrations_v1"
    private const val MIGRATION_KEY = "third_party_music_auth_removed_v1"

    private data class CookieScope(val url: String, val registrableDomain: String)

    // These are only the WebView hosts used by the retired login flow; no unrelated site cookies are cleared.
    private val cookieScopes = listOf(
        CookieScope("https://music.163.com/", ".163.com"),
        CookieScope("https://interface.music.163.com/", ".163.com"),
        CookieScope("https://st.music.163.com/", ".163.com"),
        CookieScope("https://music.126.net/", ".126.net"),
        CookieScope("https://y.qq.com/", ".qq.com"),
        CookieScope("https://c.y.qq.com/", ".qq.com"),
        CookieScope("https://i.y.qq.com/", ".qq.com"),
        CookieScope("https://xui.ptlogin2.qq.com/", ".qq.com"),
        CookieScope("https://ssl.ptlogin2.qq.com/", ".qq.com"),
        CookieScope("https://graph.qq.com/", ".qq.com"),
        CookieScope("https://www.kuwo.cn/", ".kuwo.cn"),
        CookieScope("https://m.kuwo.cn/", ".kuwo.cn"),
        CookieScope("https://kuwo.cn/", ".kuwo.cn"),
        CookieScope("https://passport.kuwo.cn/", ".kuwo.cn"),
        CookieScope("https://www.kugou.com/", ".kugou.com"),
        CookieScope("https://m.kugou.com/", ".kugou.com"),
        CookieScope("https://h5.kugou.com/", ".kugou.com"),
        CookieScope("https://passport.kugou.com/", ".kugou.com"),
        CookieScope("https://music.migu.cn/", ".migu.cn"),
        CookieScope("https://m.music.migu.cn/", ".migu.cn"),
        CookieScope("https://passport.migu.cn/", ".migu.cn"),
        CookieScope("https://login.migu.cn/", ".migu.cn"),
    )

    fun clearLegacyThirdPartyMusicAuth(context: Context) {
        val app = context.applicationContext
        val migrations = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (migrations.getBoolean(MIGRATION_KEY, false)) return

        var complete = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()

        try {
            val prefs = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(PROVIDER_CAPS_KEY, null)
            if (!raw.isNullOrBlank()) {
                val capabilities = JSONObject(raw)
                val keys = capabilities.keys()
                while (keys.hasNext()) {
                    capabilities.optJSONObject(keys.next())?.let { entry ->
                        entry.remove("accountProbe")
                        entry.remove("userPlaylists")
                    }
                }
                if (!prefs.edit().putString(PROVIDER_CAPS_KEY, capabilities.toString()).commit()) {
                    complete = false
                }
            }
        } catch (_: Exception) {
            complete = false
        }

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_KEY_ALIAS)
        } catch (_: Exception) {
            complete = false
        }

        try {
            val manager = CookieManager.getInstance()
            cookieScopes.forEach { scope ->
                val names = manager.getCookie(scope.url).orEmpty()
                    .split(';')
                    .mapNotNull { part -> part.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                    .distinct()
                names.forEach { name ->
                    manager.setCookie(scope.url, "$name=; Max-Age=0; Path=/")
                    manager.setCookie(scope.url, "$name=; Max-Age=0; Path=/; Domain=${scope.registrableDomain}")
                }
            }
            manager.flush()
        } catch (_: Exception) {
            complete = false
        }

        if (complete) {
            migrations.edit().putBoolean(MIGRATION_KEY, true).commit()
        }
    }
}
