package xyz.cncnlp.soundwave.playback

import java.util.Locale

/** Audio format delivered to the audio renderer; labels exclude requested quality, channels, and unmeasured bitrate. */
data class PlaybackAudioFormat(
    val codec: String = "",
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null
) {
    fun label(): String {
        val parts = buildList {
            codec.takeIf { it.isNotBlank() }?.let(::add)
            sampleRateHz?.takeIf { it > 0 }?.let { hz ->
                val khz = if (hz % 1000 == 0) {
                    (hz / 1000).toString()
                } else {
                    String.format(Locale.ROOT, "%.1f", hz / 1000f)
                }
                add("$khz kHz")
            }
        }
        return parts.joinToString(" · ")
    }

    companion object {
        /** Build only facts present in Media3's renderer input Format; absent/unknown values remain absent. */
        fun fromMedia3(
            sampleMimeType: String?,
            codecs: String?,
            sampleRateHz: Int,
            channelCount: Int
        ): PlaybackAudioFormat? {
            val codec = audioCodecLabel(sampleMimeType, codecs)
            val sampleRate = sampleRateHz.takeIf { it > 0 }
            val channels = channelCount.takeIf { it > 0 }
            if (codec.isBlank() && sampleRate == null && channels == null) return null
            return PlaybackAudioFormat(codec, sampleRate, channels)
        }
    }
}

/** UI-facing label; it never falls back to the resolver's requested/preferred quality. */
fun playbackAudioFormatLabel(format: PlaybackAudioFormat?): String =
    format?.label()?.takeIf { it.isNotBlank() } ?: "播放格式信息暂不可用"

private fun audioCodecLabel(sampleMimeType: String?, codecs: String?): String {
    val mime = sampleMimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    val codec = codecs.orEmpty().substringBefore(',').trim().lowercase(Locale.ROOT)
    return when {
        mime == "audio/flac" || codec.startsWith("flac") -> "FLAC"
        mime == "audio/mpeg" || codec.startsWith("mp3") -> "MP3"
        mime == "audio/aac" || mime == "audio/mp4a-latm" || codec.startsWith("mp4a") -> "AAC"
        mime == "audio/opus" || codec.startsWith("opus") -> "Opus"
        mime == "audio/vorbis" || codec.startsWith("vorbis") -> "Vorbis"
        mime == "audio/alac" || codec.startsWith("alac") -> "ALAC"
        mime == "audio/ac3" || codec.startsWith("ac-3") -> "AC-3"
        mime == "audio/eac3" || codec.startsWith("ec-3") -> "E-AC-3"
        mime == "audio/ac4" || codec.startsWith("ac-4") -> "AC-4"
        mime == "audio/raw" -> "PCM"
        else -> ""
    }
}
