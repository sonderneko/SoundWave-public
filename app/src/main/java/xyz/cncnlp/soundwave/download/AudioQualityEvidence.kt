package xyz.cncnlp.soundwave.download

import java.util.Locale

/** Metadata observed from a HEAD or a bounded Range response; it never contains the media URL. */
data class MediaProbeMetadata(
    val sizeBytes: Long = -1L,
    val sizeSource: String = "",
    val contentType: String = "",
    val httpStatus: Int = 0,
    val method: String = "",
    val rangeHeader: String = "",
    val contentRange: String = ""
)

data class AudioFormatHint(val label: String, val evidence: String)

/** Facts for one requested tier. Unknown size/format stays unknown instead of being guessed. */
data class AudioQualityProbe(
    val requestedQuality: String,
    val resolvedQuality: String = "",
    val bytes: Long = -1L,
    val durationMs: Long = 0L,
    val contentType: String = "",
    val format: String = "",
    val formatEvidence: String = "",
    val httpStatus: Int = 0,
    val requestMethod: String = "",
    val rangeHeader: String = "",
    val contentRange: String = "",
    val sizeSource: String = "",
    val resolveMs: Long = -1L,
    val platform: String = "",
    val scriptId: String = "",
    val failure: String = "",
    /** Ephemeral SHA-256 used only to compare URLs in memory; never display or persist it. */
    val urlFingerprint: String = ""
)

/** Conservative evidence helpers shared by the download selector and script diagnostics. */
object AudioQualityEvidence {
    /** A 206 Content-Length is only the returned range length, never the whole-file size. */
    fun measuredSize(status: Int, contentRange: String?, contentLength: String?): Pair<Long, String> {
        val total = contentRange.orEmpty().substringAfterLast('/', "").trim().toLongOrNull()
        if (total != null && total > 0L) return total to "Content-Range 总长度"
        if (status == 206) return -1L to ""
        val length = contentLength.orEmpty().trim().toLongOrNull()
        if (status in 200..299 && length != null && length > 0L) return length to "Content-Length"
        return -1L to ""
    }

    /** Use only an explicit URL extension or audio MIME; opaque CDN URLs stay unlabeled. */
    fun formatHint(url: String, contentType: String): AudioFormatHint? {
        val mime = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val byExtension = when (ext) {
            "mp3" -> "MP3"
            "flac" -> "FLAC"
            "mflac", "mflac0" -> "MFLAC 加密封装"
            "ncm" -> "NCM 加密封装"
            "mgg" -> "MGG 加密封装"
            "qmc", "qmc0", "qmcflac", "qmcogg" -> "QMC 加密封装"
            "aac" -> "AAC"
            "m4a" -> "M4A 容器"
            "ogg" -> "OGG 容器"
            "opus" -> "Opus"
            "wav" -> "WAV/PCM"
            "ape" -> "APE"
            else -> ""
        }
        if (byExtension.isNotBlank()) return AudioFormatHint(byExtension, "URL 扩展名 .$ext")
        val byMime = when (mime) {
            "audio/mpeg", "audio/mp3", "audio/x-mp3" -> "MP3"
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/aac", "audio/aacp" -> "AAC"
            "audio/opus" -> "Opus"
            "audio/ogg", "application/ogg" -> "OGG/音频编码未细分"
            "audio/wav", "audio/x-wav", "audio/wave" -> "WAV/PCM"
            "audio/alac" -> "ALAC"
            else -> ""
        }
        return byMime.takeIf { it.isNotBlank() }?.let { AudioFormatHint(it, "Content-Type $mime") }
    }

    /**
     * Return a warning only when evidence is material: an explicitly lower resolver tier,
     * an MP3 response to a lossless/high-tier request, a repeated media URL, a large same-song
     * cross-tier size inversion, or a very low observed average bitrate for a high-tier file.
     * A small file on its own is never enough.
     */
    fun suspectedDowngrade(target: AudioQualityProbe, peers: List<AudioQualityProbe>): String? {
        val wanted = qualityRank(target.requestedQuality)
        val resolved = qualityRank(target.resolvedQuality)
        if (wanted > 0 && resolved > 0 && resolved < wanted) {
            return "取链返回档位低于所选：${qualityLabel(target.resolvedQuality)}（所选 ${qualityLabel(target.requestedQuality)}）"
        }

        val highTier = wanted >= qualityRank("flac")
        if (highTier && target.format.equals("MP3", ignoreCase = true)) {
            return "高规格请求返回 MP3 格式线索（${target.formatEvidence.ifBlank { "格式证据未注明" }}）"
        }

        val sameUrlPeer = peers.firstOrNull {
            it !== target && it.urlFingerprint.isNotBlank() && it.urlFingerprint == target.urlFingerprint &&
                qualityRank(it.requestedQuality) != wanted
        }
        if (sameUrlPeer != null) {
            return "不同请求档位返回相同媒体链接，疑似未切换音质"
        }

        val lowerTierLarger = peers.firstOrNull {
            val peerRank = qualityRank(it.requestedQuality)
            peerRank in 1 until wanted && it.bytes >= 1_000_000L && target.bytes > 0L &&
                target.bytes.toDouble() <= it.bytes.toDouble() * 0.4
        }
        if (lowerTierLarger != null) {
            return "同曲文件大小明显低于较低档（${formatBytes(target.bytes)} vs ${formatBytes(lowerTierLarger.bytes)}）"
        }

        val durationSeconds = when {
            target.durationMs >= 10_000L -> target.durationMs / 1000.0
            target.durationMs in 60L..10_000L -> target.durationMs.toDouble()
            else -> 0.0
        }
        if (highTier && target.bytes > 0L && durationSeconds > 0.0) {
            val observedKbps = target.bytes * 8.0 / durationSeconds / 1000.0
            val minimumKbps = when (target.requestedQuality.lowercase(Locale.ROOT)) {
                "atmos", "dolby" -> 220.0
                "flac" -> 240.0
                else -> 320.0
            }
            if (observedKbps < minimumKbps) {
                return "按歌曲时长估算约 ${String.format(Locale.ROOT, "%.0f", observedKbps)} kb/s，明显偏低；仅作疑似线索"
            }
        }
        return null
    }

    /**
     * ACCEPT5（B）：无音质声明的匿名链（oiapi 类）以实测证据定**结果档**，不许
     * 拿请求档冒充。证据优先级：① 格式线索是无损家族（FLAC/WAV/APE/ALAC）→
     * "flac" 档（无损已由容器自证）；② 总字节与时长齐全 → 按平均码率分档
     * （≥280kbps 归 320k、≥160 归 192k、其余 128k——阈值留足 VBR/容器开销
     * 余量，只求不虚报）；③ 证据不足 → null，由调用方按该通道的保守地板
     * 标注。加密封装（MFLAC/MGG/QMC/NCM）的内层码率不可见，不按格式拔高，
     * 落②/③。
     */
    fun estimateDeliveredQuality(formatLabel: String?, totalBytes: Long, durationMs: Long): String? {
        val format = formatLabel.orEmpty().uppercase(Locale.ROOT)
        val lossless = format == "FLAC" || format.startsWith("WAV") || format == "APE" || format == "ALAC"
        if (lossless) return "flac"
        if (totalBytes > 0L && durationMs > 0L) {
            val kbps = totalBytes * 8.0 / (durationMs / 1000.0) / 1000.0
            return when {
                kbps >= 280.0 -> "320k"
                kbps >= 160.0 -> "192k"
                else -> "128k"
            }
        }
        return null
    }

    /**
     * ACCEPT5（本地直读）：本地文件的实测档——路径扩展名给格式线索，字节与
     * 时长给码率证据，口径同 [estimateDeliveredQuality]。路径无扩展名线索
     * （如 SAF content:// 文档 URI）时格式未知：纯码率分档会把无损文件误标
     * 成有损档，此时返回 null 交回存储值，不许以残缺证据推翻落盘盖章。
     */
    fun measuredFileQuality(path: String, totalBytes: Long, durationMs: Long): String? {
        val hint = formatHint(path, "") ?: return null
        return estimateDeliveredQuality(hint.label, totalBytes, durationMs)
    }

    /**
     * ACCEPT5（下载盖章）：下载完成落盘的音质盖章与 oiapi 同纪律——结果档
     * 只认实测（格式 + 实收字节 + 时长），解析自报档（脚本按请求档回链时
     * 的阶梯档）仅在无实测证据时作回退。脚本是黑盒：请求 master 回一条
     * 128k MP3 链时，自报 master 就是说谎，必须以实收文件为准。
     */
    fun stampedDownloadQuality(claimed: String, formatLabel: String?, totalBytes: Long, durationMs: Long): String =
        estimateDeliveredQuality(formatLabel, totalBytes, durationMs) ?: claimed

    fun qualityLabel(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "master" -> "Master"
        "atmos", "dolby" -> "Atmos"
        "hires" -> "Hi-Res"
        "flac24bit" -> "FLAC 24Bit"
        "flac" -> "FLAC"
        "320k", "320" -> "320K"
        "192k", "192" -> "192K"
        "128k", "128" -> "128K"
        else -> value.ifBlank { "未确认" }
    }

    fun qualityRank(value: String): Int = when (value.lowercase(Locale.ROOT)) {
        "master" -> 8
        "atmos", "dolby" -> 7
        "hires" -> 6
        "flac24bit" -> 5
        "flac" -> 4
        "320k", "320" -> 3
        "192k", "192" -> 2
        "128k", "128" -> 1
        else -> 0
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 0L -> "大小未知"
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * ACCEPT6（qqslow）：交付时长完整性——仅凭总字节数与元数据时长，判断
     * 交付文件在物理上不可能装下完整歌曲（试听片段）。比特率下限取保守
     * 值（有损 112kbps / 无损 320kbps），上限时长 = 总字节 × 8 / 下限；
     * 上限时长同时 < 期望 × 0.85 且 < 期望 − 15s 才判试听。96kbps 及以上
     * 的完整编码不会中招；64~80kbps 的完整低码文件仍可能被误判——解析
     * 链会继续换腿，误判成本是多走一条腿而非不可播。短歌（<60s）与未知
     * 大小不判（信息不足时不拦，只拦可证伪的短）。
     */
    fun isPreviewFragment(totalBytes: Long, expectedDurationMs: Long, formatLabel: String = ""): Boolean {
        if (totalBytes <= 0L || expectedDurationMs < 60_000L) return false
        val label = formatLabel.lowercase(Locale.ROOT)
        val lossless = label.contains("flac") || label.contains("wav") ||
            label.contains("ape") || label.contains("alac")
        val floorBps = if (lossless) 320_000L else 112_000L
        val upperBoundSec = totalBytes * 8L / floorBps
        val expectedSec = expectedDurationMs / 1000L
        return upperBoundSec * 100L < expectedSec * 85L && upperBoundSec < expectedSec - 15L
    }
}
