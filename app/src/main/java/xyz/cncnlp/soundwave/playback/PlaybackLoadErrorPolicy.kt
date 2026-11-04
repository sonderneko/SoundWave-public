package xyz.cncnlp.soundwave.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * r657：媒体加载重试策略。
 *
 * Media3 默认对任何 HTTP 状态码（含 403/404/410）都会按 0/1/2 秒再试 3 次，过期直链白白卡住 3 秒以上才报错；
 * 而对断网/切换 Wi-Fi 这类纯网络错误，3 次（约 3 秒）又太短，地铁/电梯里一抖就整首报错停播。
 * 这里：链接失效类状态码立即上报（交给上层重新取链），网络类错误放宽到 [MIN_RETRIES] 次、退避上限 5 秒。
 */
@UnstableApi
class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(PlaybackLoadRetry.MIN_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return PlaybackLoadRetry.delayMs(status, loadErrorInfo.errorCount, super.getRetryDelayMsFor(loadErrorInfo))
    }
}

object PlaybackLoadRetry {
    const val MIN_RETRIES = 6
    /** 4xx 中只有请求超时/限流值得原 URL 重试；其余（403 过期、404/410 被删、401 鉴权）立即交给上层换链。 */
    private val RETRYABLE_CLIENT_STATUS = setOf(408, 429)

    fun delayMs(responseCode: Int?, errorCount: Int, defaultDelayMs: Long): Long = when {
        responseCode != null && responseCode in 400..499 && responseCode !in RETRYABLE_CLIENT_STATUS -> C.TIME_UNSET
        responseCode != null && responseCode in RETRYABLE_CLIENT_STATUS -> (errorCount.coerceAtLeast(1) * 1_000L).coerceAtMost(4_000L)
        responseCode != null && responseCode >= 500 -> (errorCount.coerceAtLeast(1) * 1_000L).coerceAtMost(4_000L)
        else -> defaultDelayMs
    }
}
