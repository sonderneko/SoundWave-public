package xyz.cncnlp.soundwave

import okhttp3.Request

/** Shared media-request defaults used by playback, download and probe paths. */
object MediaRequestPolicy {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36 SoundWave/1.0"
    const val REFERER = "https://music.163.com/"

    fun apply(builder: Request.Builder): Request.Builder {
        builder.header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Referer", REFERER)
        return builder
    }
}
