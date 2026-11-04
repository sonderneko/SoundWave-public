package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.sync.*

/** Only genuine local edits publish here. Remote merges deliberately never publish. */
internal object AccountOwnedDataChanges {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    fun subscribe(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
    fun localChange(accountKey: String) { listeners.forEach { listener -> runCatching { listener(accountKey) } } }
}

/** One JSON value is committed synchronously, including the pre-PUT uncertainty marker. */
internal class AndroidWebDavAutoSyncStore(context: Context) : WebDavAutoSyncStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("webdav_auto_sync_v1", Context.MODE_PRIVATE)
    override fun read(): WebDavAutoSyncState? = try {
        val raw = prefs.getString("state", null)
        if (raw == null) WebDavAutoSyncState.DEFAULT else {
            val json = JSONObject(raw)
            val binding = json.optJSONObject("binding")?.let {
                WebDavAutoSyncBinding.bind(
                    it.getString("account"), it.getString("endpoint"), it.getString("username"),
                    httpExceptions = WebDavHttpExceptionStore.load(appContext))
            }
            WebDavAutoSyncState(binding, json.getBoolean("manualReady"), json.getBoolean("enabled"), json.getBoolean("uncertain"))
        }
    } catch (_: Exception) { WebDavAutoSyncState.DEFAULT }
    override fun writeAtomically(state: WebDavAutoSyncState): Boolean = try {
        val json = JSONObject().put("manualReady", state.manualReady).put("enabled", state.enabled).put("uncertain", state.uncertain)
        state.binding?.let {
            json.put("binding", JSONObject().put("account", it.accountKey).put("endpoint", it.endpoint).put("username", it.username))
        }
        prefs.edit().putString("state", json.toString()).commit()
    } catch (_: Exception) { false }
}

/** Never resurrect a previous process's grant after an unconfirmed revoke/storage failure.
 * Manual readiness and uncertainty survive; automatic enablement is explicitly session-scoped.
 */
internal class SessionWebDavAutoSyncStore(private val durable: WebDavAutoSyncStore) : WebDavAutoSyncStore {
    override fun read(): WebDavAutoSyncState? = durable.read()?.copy(enabled = false)
    override fun writeAtomically(state: WebDavAutoSyncState): Boolean = durable.writeAtomically(state)
}

/** Foreground-only integration. Lock order is always account mutex, then WebDAV transaction gate. */
internal class AccountWebDavAutoSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val account: CloudSyncController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    store: WebDavAutoSyncStore = AndroidWebDavAutoSyncStore(context),
    private val transport: WebDavTransport? = null,
    // Keep credential decoding at the platform boundary; runtime tests never need AndroidKeyStore.
    private val credentialState: () -> WebDavSavedCredentialState = { WebDavCredentialStore.loadState(context) },
) {
    var state by mutableStateOf(WebDavAutoSyncState.DEFAULT)
        private set
    private val auto = WebDavAutoSync(scope, SessionWebDavAutoSyncStore(store), ::currentBinding, ::runSync, { state = it })
    private val credentialPrefs = context.applicationContext.getSharedPreferences("soundwave_webdav", Context.MODE_PRIVATE)
    private var unsubscribe: (() -> Unit)? = null
    private var credentialFingerprint: Map<String, *> = credentialPrefs.all.toMap()
    private val credentialListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch(mainDispatcher) {
            val latest = credentialPrefs.all.toMap()
            if (latest != credentialFingerprint) {
                credentialFingerprint = latest
                auto.refreshBinding()
            }
        }
    }

    init { state = auto.state }

    fun attach() {
        account.onWebDavIdentityInvalidated = { scope.launch(mainDispatcher) { auto.revoke() }; Unit }
        credentialPrefs.registerOnSharedPreferenceChangeListener(credentialListener)
        unsubscribe = AccountOwnedDataChanges.subscribe { owner ->
            scope.launch(mainDispatcher) {
                if (currentBinding()?.accountKey == owner) auto.onLocalChanged()
            }
        }
    }
    fun detach() {
        account.onWebDavIdentityInvalidated = {}
        auto.onBackground()
        unsubscribe?.invoke()
        unsubscribe = null
        credentialPrefs.unregisterOnSharedPreferenceChangeListener(credentialListener)
    }
    fun foreground() { auto.onForeground() }
    fun background() { auto.onBackground() }
    fun identityChanged() { auto.refreshBinding() }
    fun revoke() { auto.revoke() }
    fun manualStarted(): Boolean = auto.manualStarted()
    fun manualSucceeded(accountKey: String) {
        val binding = currentBinding() ?: return
        if (binding.accountKey == accountKey) auto.manualSucceeded(binding)
    }
    fun setEnabled(enabled: Boolean): Boolean = if (enabled) auto.enable() else auto.disable()
    val canEnable: Boolean get() = state.manualReady && !state.uncertain && state.binding == currentBinding()

    private fun credentials(): WebDavSavedCredentials? =
        (credentialState() as? WebDavSavedCredentialState.Ready)?.credentials

    private fun currentBinding(): WebDavAutoSyncBinding? = try {
        val capability = account.prepareWebDavAccountDataSync()
        val saved = credentials()
        // 账号去除后能力来源改为本机资料（prepareWebDavAccountDataSync 已按本地资料名签发）。
        // canExplicitlyEnableAccountSync 实为来源核验门（恢复失效/旧快照可读性），与手动 WebDAV
        // 同步共用同一判定（见 WebDavSyncOverlay），属数据来源安全而非账号身份，保留不动。
        if (capability == null || saved == null || !capability.isCurrent() ||
            !xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot.canExplicitlyEnableAccountSync(context)) null
        else WebDavAutoSyncBinding.bind(
            capability.accountKey, saved.url, saved.username,
            httpExceptions = WebDavHttpExceptionStore.load(context))
    } catch (_: Exception) { null }

    private suspend fun runSync(binding: WebDavAutoSyncBinding): WebDavAutoSyncOutcome =
        account.withWebDavAccountLock {
            val lease = WebDavOperations.capture()
            WebDavOperations.transaction(lease) {
                if (currentBinding() != binding) return@transaction WebDavAutoSyncOutcome.PausedFailure
                val capability = account.prepareWebDavAccountDataSync()
                    ?: return@transaction WebDavAutoSyncOutcome.PausedFailure
                val saved = credentials() ?: return@transaction WebDavAutoSyncOutcome.PausedFailure
                val avatar = account.prepareWebDavAvatarSync()
                val extras = account.prepareWebDavProfileExtrasSync()
                val statsOwner = ProfileWorkIdentity.runtime()
                val snapshot = ListenStatsSync.webDavSnapshot(context, account.userBundle, statsOwner).copy(
                    todayStylePreferencesJson = xyz.cncnlp.soundwave.recommend.StylePreferenceStore.snapshot(
                        context, account.userBundle.todayStylePreferencesJson)
                )
                lease.ensureActive()
                val client = WebDavSyncClient(
                    transport = transport ?: OkHttpWebDavTransport(),
                    localPlaybackHistory = { LocalStore.loadHistory(context) },
                    avatarSync = avatar,
                    profileExtrasSync = extras,
                    httpExceptions = WebDavHttpExceptionStore.load(context),
                    profileName = capability.accountUser,
                    accountDataKey = binding.accountKey,
                    accountDataCurrent = { capability.isCurrent() && currentBinding() == binding },
                    beforeAccountPut = {
                        runBlocking(mainDispatcher) {
                            lease.ensureActive()
                            if (!auto.remoteWriteStarting()) throw WebDavPutAuthorizationException()
                        }
                    },
                    onAccountPutResponse = { code ->
                        // Success remains marked uncertain until local apply also completes. Only an
                        // explicit conditional rejection may clear it for a bounded same-run retry.
                        if (code == 409 || code == 412) runBlocking(mainDispatcher) {
                            lease.ensureActive()
                            if (!auto.remoteWriteAcknowledged()) throw WebDavPutAuthorizationException()
                        }
                    }
                )
                when (val result = withContext(ioDispatcher) {
                    client.sync(saved.url, saved.username, saved.password, snapshot,
                        gateStateProvider = { xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot.accountSyncGateState(context) })
                }) {
                    is WebDavSyncResult.Success -> {
                        lease.commit {
                            check(capability.isCurrent() && currentBinding() == binding)
                            check(ProfileWorkIdentity.isRuntime(statsOwner))
                            account.applyWebDavBundleLocally(result.bundle)
                            if (avatar?.isCurrent?.invoke() == true) avatar.apply(result.avatarB64)
                            result.profileExtras?.let { merged ->
                                if (extras?.isCurrent?.invoke() == true) extras.apply(merged)
                            }
                        }
                        xyz.cncnlp.soundwave.sync.WebDavSyncState.recordSuccessfulSync(context)
                        WebDavAutoSyncOutcome.Success
                    }
                    is WebDavSyncResult.Failure -> when {
                        result.remoteStateUncertain -> WebDavAutoSyncOutcome.Uncertain
                        result.retryable -> WebDavAutoSyncOutcome.RetryableFailure
                        else -> WebDavAutoSyncOutcome.PausedFailure
                    }
                }
            }
        }
}
