package xyz.cncnlp.soundwave.ui.settings

import android.content.Context
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.ListenStatsSync
import xyz.cncnlp.soundwave.ListenStatsSnapshotException
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.sync.WebDavCredentialStore
import xyz.cncnlp.soundwave.sync.WebDavCredentialVault
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentials
import xyz.cncnlp.soundwave.sync.WebDavSavedSession
import xyz.cncnlp.soundwave.sync.WebDavOperations
import xyz.cncnlp.soundwave.sync.WebDavSyncRun
import xyz.cncnlp.soundwave.sync.WEB_DAV_SAVED_PASSWORD_UNAVAILABLE_MESSAGE
import xyz.cncnlp.soundwave.sync.isWebDavAuthRejection
import xyz.cncnlp.soundwave.sync.webDavSavedAccountLabel
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavDiscoveredProfile
import xyz.cncnlp.soundwave.sync.WebDavHttpExceptionStore
import xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync
import xyz.cncnlp.soundwave.sync.WebDavSyncConflictGate
import xyz.cncnlp.soundwave.sync.WebDavSyncState
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE

private data class WebDavCredentialLoad(
    val state: WebDavSavedCredentialState,
    val failed: Boolean
)

/** 生产凭据存取：沿用 WebDavCredentialStore（AndroidKeyStore 加密）。 */
private fun androidWebDavVault(context: Context): WebDavCredentialVault = object : WebDavCredentialVault {
    override fun read(): WebDavSavedCredentialState = WebDavCredentialStore.loadState(context)
    override fun save(url: String, username: String, password: String): Boolean =
        WebDavCredentialStore.save(context, url, username, password)
    override fun clear(): Boolean = WebDavCredentialStore.clear(context)
}

/**
 * ACCEPT5：账号卡「退出登录」与本面板「忘记登录信息」共用同一忘记操作——同一凭据仓、
 * 同一 [WebDavSavedSession.forget]（identityChange 串行 + clear 落盘）口径，只清本机保存的
 * 登录信息，本机与远端数据均不动。调用方仍须自行先撤销自动同步会话授权（面板走 onBindingEdited）。
 */
internal fun forgetSavedWebDavCredentials(context: Context): Boolean =
    WebDavSavedSession(androidWebDavVault(context)) { _, _, _ -> WebDavSyncResult.Failure("") }.forget()

/** 呈现入口：AUTO = 现行状态机（无凭据/编辑→表单、Ready→面板）；FORM = 强制表单对话框（账号流登录/编辑直达）。 */
internal enum class WebDavSheetEntry { AUTO, FORM }

/** 呈现形态：Sheet = 渲染表单/面板；Headless = 不渲染 sheet、仅保留冲突确认层，供账号中心行内同步借道同一管线。 */
internal enum class WebDavPresentation { Sheet, Headless }

@Composable
internal fun WebDavSyncOverlay(
    prepareAvatarSync: () -> xyz.cncnlp.soundwave.sync.WebDavAvatarSync? = { null },
    prepareAccountDataSync: () -> xyz.cncnlp.soundwave.sync.WebDavAccountDataSync? = { null },
    prepareProfileExtrasSync: () -> WebDavProfileExtrasSync? = { null },
    onManualStarted: () -> Boolean = { true },
    onManualSucceeded: (String) -> Unit = {},
    onBindingEdited: () -> Unit = {},
    withWebDavAccountLock: suspend (suspend () -> Unit) -> Unit = { it() },
    localBundle: LocalStore.UserBundle,
    onBundleApplied: (LocalStore.UserBundle) -> Unit,
    onDismiss: () -> Unit,
    /** ACCEPT5 账号对话框：入口与呈现形态（默认 AUTO+Sheet＝现行行为，设置备份页宿主不变）。 */
    entry: WebDavSheetEntry = WebDavSheetEntry.AUTO,
    presentation: WebDavPresentation = WebDavPresentation.Sheet,
    /** Headless 下递增即以已存凭据触发一次手动同步（账号中心 WebDAV 行右区）。 */
    syncRequestKey: Int = 0,
    /** Headless 下把进行中/结果状态回喂宿主呈现（仅在至少触发过一次同步后才回喂，避免初始门禁文案噪声）。 */
    onStatusChanged: (busy: Boolean, status: String, isError: Boolean) -> Unit = { _, _, _ -> },
    /** FORM 入口且登录（无绑定→登录并同步）成功：宿主据此在表单退场后打开账号中心。 */
    onLoginCompleted: () -> Unit = {},
    /** FORM 入口且编辑保存（验通存凭据）成功：宿主据此在表单退场后回到账号中心。 */
    onCredentialsSaved: () -> Unit = {},
    /** 自动同步当前授权态（表单开关初值来源）与变更回调；开关为意图位，主钮成功后才由控制器应用。 */
    autoSyncEnabled: Boolean = false,
    onAutoSyncEnabledChange: (Boolean) -> Unit = {},
    /**
     * 云端资料导入的既有路径：以发现的名字在本机建立资料（走本地改名/首次命名的
     * 作用域建立逻辑），成功后调用方即可按同名归属键发起正常同步（恢复路径不新造）。
     * 默认不接线时返回 false，导入入口即不可用。
     */
    onImportProfileName: suspend (String) -> Boolean = { false }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestBundle = rememberUpdatedState(localBundle)
    val gateState = remember { LegacyUnassignedSnapshot.accountSyncGateState(context) }
    val gateAllowsSync = remember {
        LegacyUnassignedSnapshot.canExplicitlyEnableAccountSync(context)
    }
    val credentialLoad = remember {
        try {
            WebDavCredentialLoad(WebDavCredentialStore.loadState(context), failed = false)
        } catch (_: Exception) {
            WebDavCredentialLoad(WebDavSavedCredentialState.None, failed = true)
        }
    }
    val vault = remember { androidWebDavVault(context) }
    // 1.0.656 #13：已保存且可解密 → 跳过表单，只需点「同步」。
    var savedState by remember { mutableStateOf(credentialLoad.state) }
    var editing by remember { mutableStateOf(savedState !is WebDavSavedCredentialState.Ready) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(gateState, credentialLoad, gateAllowsSync) {
        mutableStateOf(when {
            credentialLoad.failed -> "读取本机 WebDAV 登录信息失败；请重新填写用户名和密码。"
            !gateAllowsSync -> WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE
            credentialLoad.state is WebDavSavedCredentialState.PasswordUnavailable -> WEB_DAV_SAVED_PASSWORD_UNAVAILABLE_MESSAGE
            // ACCEPT6（用户 2026-11-04 晚裁定）：UNKNOWN 态原有一整段说明文字（本机数据证据已
            // 更新；默认手动同步；自动同步授权仅本次会话有效……），在同步面板「忘记登录信息」
            // 下方整块呈现，用户点名删除——初始状态不再带说明文案，面板只呈现真实操作状态；
            // 自动同步口径在表单页眉与设置说明页仍有交代，不在此重复。
            else -> ""
        })
    }
    var statusIsError by remember(gateState, credentialLoad) {
        mutableStateOf(!gateAllowsSync || credentialLoad.failed)
    }
    val formUrl = when (val st = savedState) {
        is WebDavSavedCredentialState.Ready -> st.credentials.url
        is WebDavSavedCredentialState.PasswordUnavailable -> st.url
        WebDavSavedCredentialState.None -> ""
    }
    val formUser = when (val st = savedState) {
        is WebDavSavedCredentialState.Ready -> st.credentials.username
        is WebDavSavedCredentialState.PasswordUnavailable -> st.username
        WebDavSavedCredentialState.None -> ""
    }
    // ACCEPT5：打开时是否无绑定（登录完成回调的判定基线，运行中不随 savedState 变化）。
    val wasLoggedOut = remember { credentialLoad.state is WebDavSavedCredentialState.None }
    // 表单自动同步开关提交的意图值：主钮成功后才应用（applyPendingAutoSyncChoice）。
    var pendingAutoSyncChoice by remember { mutableStateOf<Boolean?>(null) }
    // FORM 入口主钮成功 → 置位，表单据此走句柄退场。
    var formCompleted by remember { mutableStateOf(false) }
    // FORM 入口打开瞬间的自动同步态快照（入场 revoke 之前），作表单开关初值，所见即所得。
    var formAutoSyncInitial by remember { mutableStateOf<Boolean?>(null) }
    fun applyPendingAutoSyncChoice() {
        val choice = pendingAutoSyncChoice ?: return
        pendingAutoSyncChoice = null
        if (choice != autoSyncEnabled) onAutoSyncEnabledChange(choice)
    }
    // FORM 入口（账号中心行本体/未登录点卡）：强制表单；有绑定时与面板编辑入口同口径先撤销自动同步授权。
    // 以 Sheet 呈现为前提：账号中心打开期间控制器以 Headless 挂载，陈旧的 FORM 入口不得在无头态误触发撤销。
    LaunchedEffect(entry, presentation) {
        if (entry == WebDavSheetEntry.FORM && presentation == WebDavPresentation.Sheet) {
            formAutoSyncInitial = autoSyncEnabled
            editing = true
            if (savedState !is WebDavSavedCredentialState.None) onBindingEdited()
        }
    }
    // Headless（账号中心打开期间挂载）：右区递增请求键即以已存凭据同步一次；状态在首次触发后才回喂宿主。
    // 两个 LaunchedEffect 在 runSync 声明之后挂载（本地函数不许先用后声明），见呈现分支之前。
    var headlessEngaged by remember { mutableStateOf(false) }

    // http 按服务器例外（用户拍板）：默认 HTTPS-only；例外键存共享 WebDAV 配置，
    // 授予/撤销即时刷新本状态，表单校验、连接测试与同步客户端共用同一份例外表。
    var httpExceptions by remember { mutableStateOf(WebDavHttpExceptionStore.load(context)) }
    fun refreshHttpExceptions() {
        httpExceptions = WebDavHttpExceptionStore.load(context)
    }

    // 云端资料发现（用户拍板）：本机尚无已建立资料时，用已填/已存凭据只读列举远端，
    // 解密出资料名供一键导入。发现失败/无结果一律静默，不打扰正常填写与同步。
    var discoveries by remember { mutableStateOf<List<WebDavDiscoveredProfile>>(emptyList()) }
    var importingProfile by remember { mutableStateOf(false) }
    suspend fun runDiscovery(credentials: WebDavSavedCredentials) {
        if (prepareAccountDataSync() != null) return
        val found = try {
            withContext(Dispatchers.IO) {
                WebDavOperations.run {
                    WebDavSyncClient(httpExceptions = httpExceptions)
                        .discoverProfiles(credentials.url, credentials.username, credentials.password)
                }
            }
        } catch (_: Exception) {
            null
        }
        if (!found.isNullOrEmpty()) discoveries = found
    }
    // 已保存凭据打开对话框时自动发现一次（仅无本机资料时）。
    LaunchedEffect(savedState) {
        (savedState as? WebDavSavedCredentialState.Ready)?.credentials?.let { runDiscovery(it) }
    }

    // 同步冲突确认（用户定版）：客户端在同步线程上阻塞等待，UI 在此呈现 DesignSheet 确认层。
    var conflictPrompt by remember { mutableStateOf<ConflictPrompt?>(null) }
    // ACCEPT5（退出动画普查 T24）：冲突层按钮先记抉择、走退场句柄，resolve 改由退场后的
    // onDismiss 交付（此前按钮直调 resolve、同步线程立即清 conflictPrompt、弹层瞬时消失）。
    var conflictChoice by remember { mutableStateOf<Boolean?>(null) }
    fun awaitConflictDecision(localChangedAt: Long, remoteUpdatedAt: Long): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val prompt = ConflictPrompt(localChangedAt, remoteUpdatedAt, latch)
        conflictPrompt = prompt
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (conflictPrompt === prompt) conflictPrompt = null
        return prompt.decision.get()
    }

    /** form = null：使用已保存凭据；否则表单登录，成功后才保存凭据。 */
    fun runSync(form: WebDavSavedCredentials?) {
        if (!onManualStarted()) {
            status = "无法安全保存同步状态；未请求远端，请检查本机存储后重试。"
            statusIsError = true
            return
        }
        busy = true
        status = "正在检查目录并同步当前资料的 v2 文件；旧版无归属同步文件保持不变…"
        statusIsError = false
        scope.launch {
            var remoteRequestStarted = false
            var operation: xyz.cncnlp.soundwave.sync.WebDavOperationGate.Lease? = null
            try {
                withWebDavAccountLock {
                    val lease = WebDavOperations.capture()
                    operation = lease
                    WebDavOperations.transaction(lease) {
                    val accountSync = prepareAccountDataSync()
                    if (accountSync == null || !accountSync.isCurrent()) {
                        status = "本机资料状态已变化，无法读取或同步。"
                        statusIsError = true
                        return@transaction
                    }
                    val avatarSync = prepareAvatarSync()
                    val extrasSync = prepareProfileExtrasSync()
                    val statsOwner = ProfileWorkIdentity.runtime()
                    val snapshot = ListenStatsSync.webDavSnapshot(context, latestBundle.value, statsOwner).copy(
                        todayStylePreferencesJson = xyz.cncnlp.soundwave.recommend.StylePreferenceStore.snapshot(
                            context, latestBundle.value.todayStylePreferencesJson)
                    )
                    lease.ensureActive()
                    check(accountSync.isCurrent()) { "同步会话已过期" }
                    remoteRequestStarted = true
                    val session = WebDavSavedSession(vault) { url, username, password ->
                        WebDavSyncClient(localPlaybackHistory = { LocalStore.loadHistory(context) }, avatarSync = avatarSync,
                            httpExceptions = httpExceptions, profileName = accountSync.accountUser,
                            accountDataKey = accountSync.accountKey, accountDataCurrent = accountSync.isCurrent,
                            profileExtrasSync = extrasSync,
                            conflictGate = WebDavSyncConflictGate(
                                lastSyncAt = { WebDavSyncState.lastSyncAt(context) },
                                localChangedAt = { WebDavSyncState.localChangedAt(context) },
                                confirm = { localChanged, remoteUpdated -> awaitConflictDecision(localChanged, remoteUpdated) }
                            )).sync(
                            url,
                            username,
                            password,
                            snapshot,
                            gateStateProvider = {
                                LegacyUnassignedSnapshot.explicitManualSyncGateState(context)
                            }
                        )
                    }
                    val run = withContext(Dispatchers.IO) {
                        if (form == null) WebDavSyncRun(session.syncWithSaved())
                        else session.loginAndSync(form.url, form.username, form.password)
                    }
                    when (val result = run.result) {
                        is WebDavSyncResult.Success -> {
                            try {
                                lease.commit {
                                    check(accountSync.isCurrent()) { "同步会话已过期" }
                                    check(ProfileWorkIdentity.isRuntime(statsOwner)) { "统计同步会话已过期" }
                                    onBundleApplied(result.bundle)
                                    if (avatarSync?.isCurrent?.invoke() == true) avatarSync.apply(result.avatarB64)
                                    result.profileExtras?.let { extras ->
                                        if (extrasSync?.isCurrent?.invoke() == true) extrasSync.apply(extras)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "远端已确认同步成功，但本机应用合并结果未确认；请先检查本机与远端文件，再决定是否重试。"
                                statusIsError = true
                                return@transaction
                            }
                            if (form == null || run.credentialsSaved == true) {
                                onManualSucceeded(accountSync.accountKey)
                            }
                            WebDavSyncState.recordSuccessfulSync(context)
                            var credentialWarning = if (run.credentialsSaved == false) {
                                "本机凭据未能加密保存；关闭后需重新填写密码。"
                            } else null
                            if (form != null && run.credentialsSaved == true) {
                                savedState = vault.read()
                                editing = savedState !is WebDavSavedCredentialState.Ready
                                if (editing) credentialWarning = "本机密码暂时无法重新读取；未切换为已保存登录信息，请稍后重试。"
                            }
                            if (form != null && run.credentialsSaved == true) {
                                // ACCEPT5：表单自动同步意图在登录成功后应用；FORM 入口据此退场回账号中心。
                                applyPendingAutoSyncChoice()
                                if (entry == WebDavSheetEntry.FORM) {
                                    if (wasLoggedOut) onLoginCompleted() else onCredentialsSaved()
                                    formCompleted = true
                                }
                            }
                            val completion = if (result.remoteFileCreated && result.migratedLegacyFile) {
                                "同步完成：已合并旧版数据，并在 SoundWave 目录创建当前资料独立的 v2 文件（旧文件已保留）。"
                            } else if (result.remoteFileCreated) {
                                "首次同步完成：已创建当前资料的独立 v2 文件，旧版文件保持不变。"
                            } else {
                                "WebDAV 同步完成：本机与远端数据已合并。"
                            }
                            status = listOfNotNull(completion, credentialWarning).joinToString(" ")
                            statusIsError = credentialWarning != null
                            // 页内提示：status 已由表单/面板/账号中心状态面呈现，不再重复弹 Toast。
                        }
                        is WebDavSyncResult.Failure -> {
                            status = result.message
                            statusIsError = true
                            // 已保存的密码被拒绝（已修改/应用密码被撤销）或读不出：回到表单，地址与用户名保留。
                            if (form == null && (isWebDavAuthRejection(result) || session.state() !is WebDavSavedCredentialState.Ready)) {
                                savedState = session.state()
                                editing = true
                            }
                        }
                    }
                    }
                }
            } catch (cancelled: CancellationException) {
                status = if (remoteRequestStarted) {
                    "WebDAV 同步已取消；若上传已开始，远端结果未确认。请先检查远端同步文件，再决定是否重试。"
                } else "统计快照等待已取消；未请求远端。"
                statusIsError = true
                throw cancelled
            } catch (_: ListenStatsSnapshotException) {
                status = "听歌统计写入未确认；未请求远端，本机数据已保留。"
                statusIsError = true
            } catch (_: Exception) {
                status = if (remoteRequestStarted) {
                    "WebDAV 同步流程意外中断；远端结果未确认，请先检查远端同步文件，再决定是否重试。"
                } else {
                    "读取本机同步快照失败；未请求远端，请检查本机数据后重试。"
                }
                statusIsError = true
            } finally {
                operation?.cancel()
                busy = false
            }
        }
    }

    // 连接测试（编辑模式「保存」与旧表单共用）：验通才存凭据，成功后刷新已保存态。
    fun runTestConnection(url: String, username: String, password: String) {
            busy = true
            status = "正在验证 WebDAV 登录、目录读取与写入…"
            statusIsError = false
            scope.launch {
                var operation: xyz.cncnlp.soundwave.sync.WebDavOperationGate.Lease? = null
                try {
                    val lease = WebDavOperations.capture()
                    operation = lease
                    val (error, credentialsSaved) = WebDavOperations.run(lease) {
                        val error = WebDavSyncClient(httpExceptions = httpExceptions).testConnection(
                            url,
                            username,
                            password,
                        )
                        val saved = if (error == null) {
                            try {
                                lease.commit { vault.save(url, username, password) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) { false }
                        } else false
                        error to saved
                    }
                    lease.ensureActive()
                    if (error != null) {
                        status = error
                        statusIsError = true
                    } else {
                        val saveStatus = try {
                            if (credentialsSaved) {
                                savedState = vault.read()
                                editing = savedState !is WebDavSavedCredentialState.Ready
                                if (editing) "连接成功，但未能重新读取已保存密码；请稍后重试。" else null
                            }
                            else "连接成功（可读写）；本机安全存储不可用，密码未保存；本次可继续手动同步，关闭后需重新填写。"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            "连接成功（可读写）；本机未保存 WebDAV 凭据，本次可继续手动同步，关闭后需重新填写。"
                        }
                        status = saveStatus ?: "连接成功（可读写，SoundWave 同步目录已就绪）；已安全保存登录信息。"
                        statusIsError = saveStatus != null
                        // 连接测试成功后自动发现云端资料（仅本机尚无已建立资料时，静默进行）。
                        runDiscovery(WebDavSavedCredentials(url, username, password))
                        if (credentialsSaved) {
                            // ACCEPT5：编辑保存成功 → 应用自动同步意图；FORM 入口据此退场回账号中心。
                            applyPendingAutoSyncChoice()
                            if (entry == WebDavSheetEntry.FORM) {
                                onCredentialsSaved()
                                formCompleted = true
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    status = "连接测试已取消；未执行 WebDAV 同步写入。"
                    statusIsError = true
                    throw cancelled
                } catch (_: Exception) {
                    status = "WebDAV 目录检查意外中断；未执行同步写入，请检查设置后重试。"
                    statusIsError = true
                } finally {
                    operation?.cancel()
                    busy = false
                }
            }
    }

    LaunchedEffect(syncRequestKey) {
        if (presentation == WebDavPresentation.Headless && syncRequestKey > 0 &&
            savedState is WebDavSavedCredentialState.Ready && !busy
        ) {
            headlessEngaged = true
            runSync(null)
        }
    }
    LaunchedEffect(busy, status, statusIsError) {
        if (presentation == WebDavPresentation.Headless && headlessEngaged) {
            onStatusChanged(busy, status, statusIsError)
        }
    }

    if (presentation == WebDavPresentation.Sheet) {
        // 表单活动：FORM 入口强制、编辑中、或尚无可用凭据；否则为登录后同步面板。
        val formActive = entry == WebDavSheetEntry.FORM || editing ||
            savedState !is WebDavSavedCredentialState.Ready
        if (formActive) {
            WebDavAccountFormSheet(
                editMode = savedState !is WebDavSavedCredentialState.None,
                initialUrl = formUrl,
                initialUsername = formUser,
                initialPassword = (savedState as? WebDavSavedCredentialState.Ready)?.credentials?.password ?: "",
                busy = busy,
                status = status,
                statusIsError = statusIsError,
                initialAutoSyncEnabled = if (entry == WebDavSheetEntry.FORM) {
                    formAutoSyncInitial ?: autoSyncEnabled
                } else autoSyncEnabled,
                completed = formCompleted,
                onLogin = { u, n, p, choice ->
                    pendingAutoSyncChoice = choice
                    runSync(WebDavSavedCredentials(u, n, p))
                },
                onSaveCredentials = { u, n, p, choice ->
                    pendingAutoSyncChoice = choice
                    runTestConnection(u, n, p)
                },
                onBindingEdited = onBindingEdited,
                onDismiss = { if (!busy) onDismiss() },
        httpExceptions = httpExceptions,
        onGrantHttpException = { rawUrl ->
            if (WebDavHttpExceptionStore.grant(context, rawUrl)) refreshHttpExceptions()
        },
        onRevokeHttpException = { rawUrl ->
            if (WebDavHttpExceptionStore.revoke(context, rawUrl)) refreshHttpExceptions()
        },
        discoveredProfiles = discoveries,
        importingProfile = importingProfile,
        onImportProfile = { profile ->
            if (!importingProfile && !busy) {
                importingProfile = true
                scope.launch {
                    try {
                        val established = onImportProfileName(profile.profileName)
                        if (established) {
                            discoveries = emptyList()
                            status = "已导入云端资料，开始同步…"
                            statusIsError = false
                            // 导入 = 既有恢复路径：本机资料按发现名建立后，走正常同步合并落盘。
                            runSync(null)
                        } else {
                            status = "导入未完成：本机资料建立失败，未同步；本机与远端数据均未改动。"
                            statusIsError = true
                        }
                    } finally {
                        importingProfile = false
                    }
                }
            }
        },
            )
        } else {
            WebDavSyncPanelSheet(
                savedAccountLabel = (savedState as WebDavSavedCredentialState.Ready)
                    .let { webDavSavedAccountLabel(it.credentials.url, it.credentials.username) },
                busy = busy,
                status = status,
                statusIsError = statusIsError,
                onSyncSaved = { if (!busy) runSync(null) },
                onEditCredentials = { if (!busy) { onBindingEdited(); editing = true } },
                onForgetSaved = {
                    if (!busy) {
                        onBindingEdited()
                        val forgotten = forgetSavedWebDavCredentials(context)
                        if (forgotten) {
                            savedState = WebDavSavedCredentialState.None
                            editing = true
                            status = "已忘记 WebDAV 登录信息；本机与远端数据均未改动。"
                            statusIsError = false
                        } else {
                            status = "未能清除本机保存的 WebDAV 登录信息，请重试。"
                            statusIsError = true
                        }
                    }
                },
                onDismiss = { if (!busy) onDismiss() }
            )
        }
    }
    conflictPrompt?.let { prompt ->
        val localNewer = prompt.localChangedAt >= prompt.remoteUpdatedAt
        // 交付抉择：按钮路径用按钮记录的选择，遮罩/拖拽/返回路径无记录 → false（与旧遮罩语义一致）。
        val deliverConflict: () -> Unit = {
            val choice = conflictChoice ?: false
            conflictChoice = null
            prompt.resolve(choice)
        }
        xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = deliverConflict) {
            val dismissConflict = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(deliverConflict)
            xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Sync conflict", "同步冲突")
            Text(
                "本机与 WebDAV 远端在上次同步之后都发生过修改。按最近修改时间，较新的一方将作为本次同步结果，另一方的整库数据会被覆盖（扩展设置仍按各自时间合并）。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "本机最后修改：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prompt.localChangedAt)),
                fontSize = 12.5.sp, modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "远端最后修改：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prompt.remoteUpdatedAt)),
                fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                if (localNewer) "本机较新：将以本机数据同步。" else "远端较新：将以远端数据同步。",
                color = xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent,
                fontSize = 12.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                xyz.cncnlp.soundwave.ui.settings.SettingsSheetButton(modifier = Modifier.weight(1f), onClick = { conflictChoice = false; dismissConflict() }) {
                    Text("取消同步")
                }
                xyz.cncnlp.soundwave.ui.settings.SettingsSheetButton(modifier = Modifier.weight(1.5f), primary = true, onClick = { conflictChoice = true; dismissConflict() }) {
                    Text("按较新一方同步")
                }
            }
        }
    }
}

/** 冲突确认请求（同步线程经闩锁等待 UI 决定）。 */
private class ConflictPrompt(
    val localChangedAt: Long,
    val remoteUpdatedAt: Long,
    val latch: java.util.concurrent.CountDownLatch
) {
    val decision = java.util.concurrent.atomic.AtomicBoolean(false)
    fun resolve(confirmed: Boolean) {
        decision.set(confirmed)
        latch.countDown()
    }
}
