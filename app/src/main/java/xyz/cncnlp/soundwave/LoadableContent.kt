package xyz.cncnlp.soundwave

/**
 * 统一加载状态模型：首页、搜索、本地库共用。
 *
 * 明确区分五种真实状态，禁止用并列 Boolean/Toast 拼装、禁止用他平台数据冒充空态：
 * - Loading：请求进行中
 * - Available：当前平台真实数据（含 fetchedAt）
 * - Stale：有旧数据但已过期 + 最近刷新失败原因
 * - Empty：当前平台真实为空（fetchedAt 证明已请求过）
 * - Unavailable：明确失败（网络/接口/授权），带脱敏原因
 */
sealed interface LoadableContent<out T> {
    data object Loading : LoadableContent<Nothing>

    data class Available<T>(
        val value: T,
        val fetchedAt: Long = System.currentTimeMillis()
    ) : LoadableContent<T>

    data class Stale<T>(
        val value: T,
        val fetchedAt: Long,
        val error: String
    ) : LoadableContent<T>

    data class Empty(
        val fetchedAt: Long = System.currentTimeMillis()
    ) : LoadableContent<Nothing>

    data class Unavailable(
        val error: String
    ) : LoadableContent<Nothing>

    val isLoaded: Boolean
        get() = this is Available || this is Stale || this is Empty || this is Unavailable
}

/** 把资源状态压缩成 FeedEmptySlot 兼容的三元组（loading/error/emptyText），禁止把 Unavailable 压成空。 */
fun <T> LoadableContent<T>.toEmptySlot(emptyText: String): Triple<Boolean, String, String> = when (this) {
    is LoadableContent.Loading -> Triple(true, "", "")
    is LoadableContent.Available -> Triple(false, "", "")
    is LoadableContent.Stale -> Triple(false, this.error, emptyText)
    is LoadableContent.Empty -> Triple(false, "", emptyText)
    is LoadableContent.Unavailable -> Triple(false, this.error, emptyText)
}