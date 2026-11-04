package xyz.cncnlp.soundwave.sync

import java.net.URI
import java.util.concurrent.CancellationException

/**
 * 1.0.656 #13：WebDAV 第一次「登录并同步」成功后，地址/用户名/密码经现有 SecretStore（AndroidKeyStore AES-GCM）
 * 安全保存；之后打开 WebDAV 面板只需点「同步」。本文件只含与 UI 无关的判定，方便单测。
 */
internal sealed interface WebDavSavedCredentialState {
    /** 从未保存或已忘记。 */
    object None : WebDavSavedCredentialState

    /** 地址、用户名、密码均可用：可直接同步，跳过登录表单。 */
    data class Ready(val credentials: WebDavSavedCredentials) : WebDavSavedCredentialState

    /** 地址与用户名在，但密码读不出来（未加密保存/密文失效；transient=KeyStore 暂不可用）。只需重新输入密码。 */
    data class PasswordUnavailable(val url: String, val username: String, val transient: Boolean) : WebDavSavedCredentialState {
        override fun toString(): String = "PasswordUnavailable(transient=$transient, redacted)"
    }
}

/** 凭据存取边界；生产实现走 [WebDavCredentialStore]，测试用内存假实现。 */
internal interface WebDavCredentialVault {
    fun read(): WebDavSavedCredentialState
    fun save(url: String, username: String, password: String): Boolean
    fun clear(): Boolean
}

/** 一次同步的结果；[credentialsSaved] = null 表示未尝试保存（失败或使用已保存凭据）。 */
internal data class WebDavSyncRun(val result: WebDavSyncResult, val credentialsSaved: Boolean? = null)

internal const val WEB_DAV_SAVED_PASSWORD_UNAVAILABLE_MESSAGE =
    "本机保存的 WebDAV 密码暂时无法读取；请重新输入密码后同步（地址与用户名已保留）。"
internal const val WEB_DAV_NO_SAVED_CREDENTIALS_MESSAGE = "尚未保存 WebDAV 登录信息；请先填写并登录同步一次。"

internal class WebDavSavedSession(
    private val vault: WebDavCredentialVault,
    private val syncRunner: (url: String, username: String, password: String) -> WebDavSyncResult
) {
    fun state(): WebDavSavedCredentialState = try {
        vault.read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WebDavSavedCredentialState.None
    }

    fun hasSavedWebDavCredentials(): Boolean = state() is WebDavSavedCredentialState.Ready

    /** 用已保存凭据同步；没有可用凭据时不发任何请求。 */
    fun syncWithSaved(): WebDavSyncResult = WebDavOperations.blocking { syncWithSavedLocked() }

    private fun syncWithSavedLocked(): WebDavSyncResult = when (val saved = state()) {
        is WebDavSavedCredentialState.Ready ->
            syncRunner(saved.credentials.url, saved.credentials.username, saved.credentials.password)
        is WebDavSavedCredentialState.PasswordUnavailable -> WebDavSyncResult.Failure(WEB_DAV_SAVED_PASSWORD_UNAVAILABLE_MESSAGE)
        WebDavSavedCredentialState.None -> WebDavSyncResult.Failure(WEB_DAV_NO_SAVED_CREDENTIALS_MESSAGE)
    }

    /** 表单登录并同步；只有同步成功后才保存凭据（失败的密码绝不落盘）。 */
    fun loginAndSync(url: String, username: String, password: String): WebDavSyncRun =
        WebDavOperations.blocking { loginAndSyncLocked(url, username, password) }

    private fun loginAndSyncLocked(url: String, username: String, password: String): WebDavSyncRun {
        val result = syncRunner(url, username, password)
        if (result !is WebDavSyncResult.Success) return WebDavSyncRun(result)
        val saved = try {
            WebDavOperations.currentLease().commit { vault.save(url, username, password) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return WebDavSyncRun(result, saved)
    }

    /** 忘记登录信息：下次打开面板回到登录表单。 */
    fun forget(): Boolean = try {
        WebDavOperations.identityChange { vault.clear() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/** 已保存凭据被服务器拒绝（密码已改/应用密码被撤销）时应回到表单让用户重新输入。 */
internal fun isWebDavAuthRejection(result: WebDavSyncResult): Boolean =
    result is WebDavSyncResult.Failure && result.message.contains("HTTP 401")

/**
 * 面板上展示的已保存账号标签：只显示服务器主机名与打码用户名（不展示完整地址/用户名/密码）。
 * 例："dav.jianguoyun.com · ab•••@qq.com"。
 */
internal fun webDavSavedAccountLabel(url: String, username: String): String {
    val host = runCatching { URI(url.trim()).host }.getOrNull().orEmpty().ifBlank { "WebDAV" }
    return "$host · ${maskWebDavUsername(username)}"
}

internal fun maskWebDavUsername(username: String): String {
    val name = username.trim()
    if (name.isEmpty()) return "•••"
    val at = name.indexOf('@')
    val local = if (at > 0) name.substring(0, at) else name
    val domain = if (at > 0) name.substring(at) else ""
    val visible = local.take(if (local.length <= 2) 1 else 2)
    return "$visible•••$domain"
}
