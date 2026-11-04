package xyz.cncnlp.soundwave.download

import xyz.cncnlp.soundwave.DownloadItem

/** Canonical task states while preserving the strings already stored in downloads_v1. */
internal enum class DownloadTaskState(
    val persistedValue: String,
    val active: Boolean = false,
    val terminal: Boolean = false
) {
    QUEUED("queued", active = true),
    RESOLVING("authorizing", active = true),
    DOWNLOADING("downloading", active = true),
    VERIFYING("verifying", active = true),
    RETRY_WAIT("retry_wait", active = true),
    PAUSED("paused", terminal = true),
    COMPLETED("done", terminal = true),
    FAILED("failed", terminal = true),
    CANCELLED("cancelled", terminal = true);

    companion object {
        fun fromPersisted(value: String?): DownloadTaskState? = when (value?.trim()?.lowercase()) {
            "queued" -> QUEUED
            "authorizing", "resolving" -> RESOLVING
            "downloading" -> DOWNLOADING
            "verifying" -> VERIFYING
            "retry_wait" -> RETRY_WAIT
            "paused" -> PAUSED
            "done", "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            else -> null
        }
    }
}

/**
 * One transition policy shared by LocalStore, WorkManager recovery, and the list projection.
 * Legacy aliases are read as canonical states; writes continue using the existing persisted value.
 */
internal object DownloadTaskStateMachine {
    val activePersistedValues: Set<String> = setOf(
        DownloadTaskState.QUEUED.persistedValue,
        DownloadTaskState.RESOLVING.persistedValue,
        "resolving",
        DownloadTaskState.DOWNLOADING.persistedValue,
        DownloadTaskState.VERIFYING.persistedValue,
        DownloadTaskState.RETRY_WAIT.persistedValue
    )

    private val allowedTransitions: Map<DownloadTaskState, Set<DownloadTaskState>> = mapOf(
        DownloadTaskState.QUEUED to setOf(
            DownloadTaskState.RESOLVING,
            DownloadTaskState.FAILED,
            DownloadTaskState.PAUSED,
            DownloadTaskState.CANCELLED
        ),
        DownloadTaskState.RESOLVING to setOf(
            DownloadTaskState.DOWNLOADING,
            DownloadTaskState.RETRY_WAIT,
            DownloadTaskState.FAILED,
            DownloadTaskState.PAUSED,
            DownloadTaskState.CANCELLED
        ),
        DownloadTaskState.DOWNLOADING to setOf(
            DownloadTaskState.VERIFYING,
            DownloadTaskState.RETRY_WAIT,
            DownloadTaskState.FAILED,
            DownloadTaskState.PAUSED,
            DownloadTaskState.CANCELLED
        ),
        DownloadTaskState.VERIFYING to setOf(
            DownloadTaskState.COMPLETED,
            DownloadTaskState.FAILED,
            DownloadTaskState.PAUSED,
            DownloadTaskState.CANCELLED
        ),
        DownloadTaskState.RETRY_WAIT to setOf(
            DownloadTaskState.QUEUED,
            DownloadTaskState.RESOLVING,
            DownloadTaskState.FAILED,
            DownloadTaskState.PAUSED,
            DownloadTaskState.CANCELLED
        ),
        DownloadTaskState.PAUSED to setOf(DownloadTaskState.QUEUED, DownloadTaskState.CANCELLED),
        DownloadTaskState.COMPLETED to setOf(DownloadTaskState.QUEUED, DownloadTaskState.RESOLVING),
        DownloadTaskState.FAILED to setOf(DownloadTaskState.QUEUED, DownloadTaskState.RESOLVING, DownloadTaskState.CANCELLED),
        DownloadTaskState.CANCELLED to setOf(DownloadTaskState.QUEUED, DownloadTaskState.RESOLVING)
    )

    fun canTransition(from: String?, to: String): Boolean {
        val next = DownloadTaskState.fromPersisted(to)
        if (next == null) return from == to // unknown legacy values may be preserved, never newly introduced
        val current = DownloadTaskState.fromPersisted(from)
        if (current == null) return from.isNullOrBlank() || next in setOf(DownloadTaskState.QUEUED, DownloadTaskState.FAILED, DownloadTaskState.CANCELLED)
        return current == next || next in allowedTransitions[current].orEmpty()
    }

    fun isActive(value: String?): Boolean = DownloadTaskState.fromPersisted(value)?.active == true
}

/** A Worker may report success only when its returned completion and durable row agree. */
internal object DownloadCompletionPolicy {
    fun matches(returned: DownloadItem?, persisted: DownloadItem?, taskId: String): Boolean {
        if (returned == null || persisted == null || taskId.isBlank()) return false
        if (returned.taskId != taskId || persisted.taskId != taskId) return false
        if (DownloadTaskState.fromPersisted(returned.status) != DownloadTaskState.COMPLETED ||
            DownloadTaskState.fromPersisted(persisted.status) != DownloadTaskState.COMPLETED
        ) return false
        if (returned.path.isBlank() || returned.path.startsWith("pending:") || returned.size <= 256L) return false
        return persisted.path == returned.path && persisted.size == returned.size
    }
}


internal data class DownloadNotificationPresentation(
    val title: String,
    val ongoing: Boolean,
    val indeterminate: Boolean
)

internal object DownloadNotificationPolicy {
    private const val NOTIFICATION_ID_BASE = 4107
    private const val NOTIFICATION_ID_SPAN = 1_000_000

    fun presentation(status: String): DownloadNotificationPresentation = when (DownloadTaskState.fromPersisted(status)) {
        DownloadTaskState.QUEUED -> DownloadNotificationPresentation("等待下载", ongoing = false, indeterminate = true)
        DownloadTaskState.RESOLVING -> DownloadNotificationPresentation("正在取链", ongoing = true, indeterminate = true)
        DownloadTaskState.DOWNLOADING -> DownloadNotificationPresentation("正在下载", ongoing = true, indeterminate = false)
        DownloadTaskState.VERIFYING -> DownloadNotificationPresentation("正在校验", ongoing = true, indeterminate = false)
        DownloadTaskState.RETRY_WAIT -> DownloadNotificationPresentation("等待重试", ongoing = false, indeterminate = true)
        DownloadTaskState.PAUSED -> DownloadNotificationPresentation("已暂停", ongoing = false, indeterminate = false)
        DownloadTaskState.COMPLETED -> DownloadNotificationPresentation("下载完成", ongoing = false, indeterminate = false)
        DownloadTaskState.FAILED -> DownloadNotificationPresentation("下载失败", ongoing = false, indeterminate = false)
        DownloadTaskState.CANCELLED -> DownloadNotificationPresentation("已取消", ongoing = false, indeterminate = false)
        null -> DownloadNotificationPresentation("准备下载", ongoing = false, indeterminate = true)
    }

    /** Stable per-task ID prevents concurrent Workers from overwriting one another's progress. */
    fun notificationId(taskId: String): Int {
        val positiveHash = taskId.hashCode() and Int.MAX_VALUE
        return NOTIFICATION_ID_BASE + (positiveHash % NOTIFICATION_ID_SPAN)
    }
}
