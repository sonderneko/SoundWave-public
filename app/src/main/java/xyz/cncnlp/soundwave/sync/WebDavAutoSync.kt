package xyz.cncnlp.soundwave.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Password-free identity for an automatic-sync authorization. All identity fields are compared exactly. */
internal data class WebDavAutoSyncBinding(
    val accountKey: String,
    val endpoint: String,
    val username: String,
) {
    init {
        require(accountKey.isNotBlank()) { "accountKey must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        // 规范形态校验：https 走标准规范化等式；http 例外端点做结构规范校验——该端点
        // 是否真有用户确认的例外，由 bind() 构建时把关、且每次请求时由客户端复检
        // （例外被撤销后自动同步请求会被规范化拒绝，不会静默继续明文同步）。
        require(normalizeWebDavBaseUrl(endpoint) == endpoint || WebDavHttpPolicy.isCanonicalHttpEndpoint(endpoint)) {
            "endpoint must be a normalized WebDAV base URL"
        }
    }

    companion object {
        /** Builds a binding using the same URL normalization used by WebDAV requests. */
        fun bind(
            accountKey: String,
            endpoint: String,
            username: String,
            httpExceptions: Set<String> = emptySet()
        ): WebDavAutoSyncBinding {
            require(accountKey.isNotBlank()) { "accountKey must not be blank" }
            require(username.isNotBlank()) { "username must not be blank" }
            val normalized = normalizeWebDavBaseUrl(endpoint, httpExceptions = httpExceptions)
                ?: throw IllegalArgumentException("endpoint must be a valid WebDAV URL (HTTPS, or HTTP with a user-confirmed exception)")
            return WebDavAutoSyncBinding(accountKey, normalized, username)
        }
    }
}

/** Complete durable state. Store implementations must replace this value atomically and durably. */
internal data class WebDavAutoSyncState(
    val binding: WebDavAutoSyncBinding? = null,
    val manualReady: Boolean = false,
    val enabled: Boolean = false,
    val uncertain: Boolean = false,
) {
    init {
        require(binding != null || (!manualReady && !enabled && !uncertain))
        require(!enabled || manualReady)
    }

    companion object {
        val DEFAULT = WebDavAutoSyncState()
    }
}

/** A failed write must leave the previously stored snapshot intact; true means the whole value is durable. */
internal interface WebDavAutoSyncStore {
    fun read(): WebDavAutoSyncState?
    fun writeAtomically(state: WebDavAutoSyncState): Boolean
}

internal sealed interface WebDavAutoSyncOutcome {
    data object Success : WebDavAutoSyncOutcome

    /** Retry only when the runner knows the failure is safe to retry (no ambiguous remote commit). */
    data object RetryableFailure : WebDavAutoSyncOutcome

    /** A definite failure that should wait for a later foreground/local-change event. */
    data object PausedFailure : WebDavAutoSyncOutcome

    /** Remote outcome is ambiguous; suspend all automatic attempts until a successful manual sync. */
    data object Uncertain : WebDavAutoSyncOutcome
}

/**
 * Pure orchestration for automatic WebDAV sync. Every public method must be called from the same
 * thread (normally the UI/main thread). The injected runner owns account-mutex/WebDavOperations
 * coordination and must make cancellation stop its work; this state machine creates no WebDAV lease.
 *
 * [backoffMs] contains retry delays, so the default performs at most four attempts (initial + three
 * safe retries). Events coalesce while a runner is active and never cancel a PUT-capable runner.
 */
internal class WebDavAutoSync(
    private val scope: CoroutineScope,
    private val store: WebDavAutoSyncStore,
    private val getCurrentBinding: () -> WebDavAutoSyncBinding?,
    private val runner: suspend (WebDavAutoSyncBinding) -> WebDavAutoSyncOutcome,
    private val onStateChanged: (WebDavAutoSyncState) -> Unit = {},
    private val debounceMs: Long = 500L,
    private val backoffMs: List<Long> = listOf(1_000L, 5_000L, 30_000L),
) {
    private var savedState: WebDavAutoSyncState = store.read() ?: WebDavAutoSyncState.DEFAULT
    private var autoJob: Job? = null
    private var generation: Long = 0L
    private var foreground = false
    private var runnerRunning = false
    private var pendingDirty = false

    init {
        require(debounceMs >= 0L) { "debounceMs must be non-negative" }
        require(backoffMs.all { it >= 0L }) { "backoffMs values must be non-negative" }
        // Reject malformed persisted state rather than allowing it to authorize background work.
        if (!savedStateIsValid(savedState)) {
            savedState = WebDavAutoSyncState.DEFAULT
        }
    }

    /** Current in-memory view; Compose-independent and updated only after durable transitions. */
    val state: WebDavAutoSyncState
        get() = savedState

    /** Verify persisted authorization against live credentials/account identity, revoking on mismatch. */
    fun refreshBinding(): Boolean {
        val expected = savedState.binding ?: return true
        if (currentBinding() == expected) return true
        revoke()
        return false
    }

    /** Foreground-resume event. The app starts backgrounded until this is explicitly called. */
    fun onForeground() {
        foreground = true
        requestAutomaticRun()
    }

    /** Local edits are remembered in the background and coalesced while a runner is active. */
    fun onLocalChanged() {
        if (!foreground) {
            pendingDirty = true
            return
        }
        requestAutomaticRun()
    }

    /** Cancel queued/executing work on backgrounding without clearing durable uncertainty. */
    fun onBackground() {
        foreground = false
        pendingDirty = false
        cancelAutomaticWork()
    }

    /** Manual sync takes priority and invalidates any queued/running automatic task. */
    fun manualStarted(): Boolean {
        pendingDirty = false
        cancelAutomaticWork()
        // Manual work may itself issue a PUT and be cancelled or lose its response. Require
        // confirmed manual completion before automatic work can resume, not merely a new event.
        if (savedState.manualReady) {
            return persistOrFailClosed(savedState.copy(uncertain = true), force = true)
        }
        // A previous failed write may have left an old grant on disk while runtime is DEFAULT.
        // Admission must really clear that old grant, not optimize away an equal in-memory state.
        return persistOrFailClosed(savedState, force = true)
    }

    /**
     * Call on the main thread immediately before an automatic PUT. The durable uncertainty marker
     * is committed before the caller may issue the request. False means fail closed: do not PUT.
     */
    fun remoteWriteStarting(): Boolean {
        if (!foreground || !runnerRunning || !savedState.enabled || !savedState.manualReady || savedState.uncertain) return false
        val binding = savedState.binding ?: return false
        if (currentBinding() != binding) {
            revoke()
            return false
        }
        return persistOrFailClosed(savedState.copy(uncertain = true))
    }

    /**
     * Call on the main thread only after a definite server response (200/201/204 or a definite
     * rejection such as 409/412). It clears the pre-PUT marker without invalidating this runner,
     * allowing a same-run conflict retry. Never call for a timeout or otherwise ambiguous response.
     */
    fun remoteWriteAcknowledged(): Boolean {
        // A result delivered after background cancellation must not erase the crash/cancel guard.
        if (!foreground || !runnerRunning) return false
        val binding = savedState.binding ?: return false
        if (currentBinding() != binding) {
            revoke()
            return false
        }
        if (!savedState.uncertain) return true
        return persistOrFailClosed(savedState.copy(uncertain = false))
    }

    /** Marks first-manual-sync readiness only if this exact identity is still current. */
    fun manualSucceeded(binding: WebDavAutoSyncBinding): Boolean {
        pendingDirty = false
        cancelAutomaticWork()
        val persistedBinding = savedState.binding
        if (persistedBinding != null && currentBinding() != persistedBinding && !revoke()) return false
        if (currentBinding() != binding) return false
        val old = savedState
        val next = if (old.binding == binding) {
            old.copy(manualReady = true, uncertain = false)
        } else {
            WebDavAutoSyncState(binding = binding, manualReady = true, enabled = false, uncertain = false)
        }
        return persistOrFailClosed(next)
    }

    /** Enables only after a successful manual sync for the current binding, and only after durable write. */
    fun enable(): Boolean {
        if (!refreshBinding()) return false
        val binding = savedState.binding ?: currentBinding() ?: return false
        if (currentBinding() != binding || !savedState.manualReady || savedState.uncertain) return false
        if (savedState.enabled) return true
        return persistOrFailClosed(savedState.copy(binding = binding, enabled = true))
    }

    /** Durable opt-out. If persistence fails, runtime state closes and all work is cancelled. */
    fun disable(): Boolean {
        pendingDirty = false
        cancelAutomaticWork()
        val next = savedState.copy(enabled = false)
        return persistOrFailClosed(next)
    }

    /** Revoke all authorization on logout, binding change, or credential deletion. */
    fun revoke(): Boolean {
        pendingDirty = false
        cancelAutomaticWork()
        return persistOrFailClosed(WebDavAutoSyncState.DEFAULT, force = true)
    }

    private fun requestAutomaticRun() {
        if (!foreground || !refreshBinding()) return
        val binding = savedState.binding ?: return
        if (!savedState.enabled || !savedState.manualReady) return
        if (currentBinding() != binding) {
            revoke()
            return
        }
        // A live run owns its retry budget. New events only record dirtiness; they never cancel it.
        if (runnerRunning) {
            pendingDirty = true
            return
        }
        if (savedState.uncertain) return

        // Only a not-yet-started debounce may be replaced by another event.
        autoJob?.cancel()
        val taskGeneration = generation
        autoJob = scope.launch {
            delay(debounceMs)
            if (!isCurrentTask(taskGeneration, binding)) return@launch
            runnerRunning = true
            pendingDirty = false
            var succeeded = false
            try {
                var retryIndex = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentTask(taskGeneration, binding)) return@launch
                    val outcome = try {
                        runner(binding)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Unclassified exceptions are not safe to retry automatically.
                        WebDavAutoSyncOutcome.PausedFailure
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentTask(taskGeneration, binding)) return@launch
                    when (outcome) {
                        WebDavAutoSyncOutcome.Success -> {
                            // Successful completion of this very run reconciles any acknowledged PUT.
                            if (savedState.uncertain && !persistOrFailClosed(savedState.copy(uncertain = false))) {
                                return@launch
                            }
                            succeeded = true
                            return@launch
                        }
                        WebDavAutoSyncOutcome.PausedFailure -> return@launch
                        WebDavAutoSyncOutcome.Uncertain -> {
                            persistOrFailClosed(savedState.copy(uncertain = true))
                            return@launch
                        }
                        WebDavAutoSyncOutcome.RetryableFailure -> {
                            // The runner must acknowledge a definite response before any retry.
                            if (savedState.uncertain || retryIndex >= backoffMs.size) return@launch
                            delay(backoffMs[retryIndex++])
                        }
                    }
                }
            } finally {
                runnerRunning = false
                autoJob = null
                val stillAuthorized = foreground && savedState.enabled && savedState.manualReady &&
                    !savedState.uncertain && savedState.binding == binding && currentBinding() == binding
                val wasInvalidatedForANewTrigger = generation != taskGeneration
                if (pendingDirty && stillAuthorized && (succeeded || wasInvalidatedForANewTrigger)) {
                    pendingDirty = false
                    requestAutomaticRun()
                }
            }
        }
    }

    private fun isCurrentTask(taskGeneration: Long, binding: WebDavAutoSyncBinding): Boolean {
        if (taskGeneration != generation) return false
        // Uncertainty is intentionally not checked here: the current run is allowed to receive its
        // definite-response callback and clear its own marker. New runs are blocked in requestAutomaticRun.
        if (!foreground || !savedState.enabled || !savedState.manualReady || savedState.binding != binding) {
            return false
        }
        if (currentBinding() != binding) {
            revoke()
            return false
        }
        return true
    }

    private fun currentBinding(): WebDavAutoSyncBinding? = try {
        getCurrentBinding()
    } catch (_: Throwable) {
        null
    }

    private fun cancelAutomaticWork() {
        generation += 1L
        autoJob?.cancel()
        autoJob = null
    }

    /** A failed durable transition is fail-closed in memory and invalidates every outstanding identity. */
    private fun persistOrFailClosed(next: WebDavAutoSyncState, force: Boolean = false): Boolean {
        if (!force && next == savedState) return true
        val durable = try {
            store.writeAtomically(next)
        } catch (_: Throwable) {
            false
        }
        if (!durable) {
            val hadWork = autoJob != null
            cancelAutomaticWork()
            val wasOpen = savedState != WebDavAutoSyncState.DEFAULT
            savedState = WebDavAutoSyncState.DEFAULT
            if (wasOpen || hadWork) onStateChanged(savedState)
            return false
        }
        savedState = next
        onStateChanged(next)
        return true
    }

    private fun savedStateIsValid(value: WebDavAutoSyncState): Boolean =
        (value.binding != null || (!value.manualReady && !value.enabled && !value.uncertain)) &&
            (!value.enabled || value.manualReady)
}
