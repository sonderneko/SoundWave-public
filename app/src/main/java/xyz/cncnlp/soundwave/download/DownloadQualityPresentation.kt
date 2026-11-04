package xyz.cncnlp.soundwave.download

/** Presentation-only helpers for the download quality selector; probing and classification stay unchanged. */
internal object DownloadQualityPresentation {
    private const val MAX_WARNING_CHARS = 28

    fun trailingStatus(probe: AudioQualityProbe?): String {
        if (probe == null) return "大小未知"
        val failure = probe.failure.trim()
        if (failure.isEmpty()) return AudioQualityEvidence.formatBytes(probe.bytes)
        return when {
            "检测中" in failure -> "检测中…"
            "超时" in failure -> "取链超时"
            "媒体大小/格式响应未取得" in failure -> "大小未知"
            else -> "取链失败"
        }
    }

    fun compactDowngradeReason(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val compact = when {
            reason.contains("MP3", ignoreCase = true) -> "高音质请求仅返回 MP3 线索"
            "相同媒体链接" in reason -> "多个档位返回同一媒体链接"
            "取链返回档位低于所选" in reason -> "返回音质低于所选"
            "同曲文件大小明显低于" in reason -> "文件大小明显低于较低档"
            "按歌曲时长估算" in reason -> "按时长估算码率偏低"
            else -> reason.trim()
        }
        return if (compact.length <= MAX_WARNING_CHARS) compact else compact.take(MAX_WARNING_CHARS - 1) + "…"
    }

    /** Dispatch the requested tier unchanged; a resolved/observed tier never replaces the choice. */
    fun selectRequestedQuality(requestedQuality: String, onQualityChange: (String) -> Unit) {
        onQualityChange(requestedQuality)
    }
}
