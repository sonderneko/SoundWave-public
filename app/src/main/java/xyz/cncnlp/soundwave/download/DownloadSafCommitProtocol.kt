package xyz.cncnlp.soundwave.download

import java.io.IOException

/**
 * Testable ordering boundary for replacing a SAF download without risking the previous document.
 * A metadata commit that reports failure is ambiguous: SharedPreferences may already expose the
 * new value in memory, so both the new and previous documents must be retained.
 */
internal object DownloadSafCommitProtocol {
    data class Result<T>(
        val target: T,
        val byteCount: Long,
        val previousDeleted: Boolean
    )

    class MetadataCommitException(cause: Throwable? = null) :
        IOException("下载记录未能可靠保存；新目标已保留，旧目标未删除", cause)

    fun <T> commit(
        createUniqueTarget: () -> T,
        copyAndVerify: (target: T, checkpoint: () -> Unit) -> Long,
        ensureActive: () -> Unit,
        persist: (target: T, byteCount: Long) -> Boolean,
        deleteNewTarget: (target: T) -> Unit,
        deletePreviousTarget: () -> Boolean
    ): Result<T> {
        val target = createUniqueTarget()
        var metadataCommitStarted = false
        var metadataCommitted = false
        try {
            ensureActive()
            val byteCount = copyAndVerify(target, ensureActive)
            require(byteCount >= 0L) { "复制长度无效" }
            ensureActive()

            // From this point on, a failed commit is ambiguous; do not delete a URI that the
            // preferences implementation may already have exposed to readers in this process.
            metadataCommitStarted = true
            val persisted = try {
                persist(target, byteCount)
            } catch (failure: Exception) {
                throw MetadataCommitException(failure)
            }
            if (!persisted) throw MetadataCommitException()
            metadataCommitted = true

            val previousDeleted = try {
                deletePreviousTarget()
            } catch (_: Exception) {
                false
            }
            return Result(target, byteCount, previousDeleted)
        } finally {
            if (!metadataCommitted && !metadataCommitStarted) {
                try { deleteNewTarget(target) } catch (_: Exception) {}
            }
        }
    }
}
