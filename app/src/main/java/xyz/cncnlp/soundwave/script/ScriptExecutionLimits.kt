package xyz.cncnlp.soundwave.script

/** Explicit per-request budgets for untrusted script inputs and native HTTP payloads. */
internal object ScriptExecutionLimits {
    const val MAX_SCRIPT_CODE_BYTES = 1 * 1024 * 1024
    const val MAX_SCRIPT_METADATA_BYTES = 64 * 1024
    const val MAX_MUSIC_INFO_BYTES = 64 * 1024
    const val MAX_REQUEST_BODY_BYTES = 1 * 1024 * 1024
    const val MAX_REQUEST_HEADERS_BYTES = 64 * 1024
    const val MAX_REQUEST_URL_CHARS = 2048
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    /** getMusicUrl 的总预算（含泳道排队）：封顶单次脚本取链对解析链的最长占用。 */
    const val SCRIPT_URL_CALL_TOTAL_MS = 24_000L
    const val NEGATIVE_RESULT_TTL_MS = 60_000L
    const val MAX_NEGATIVE_RESULTS = 128

    fun scriptCodeAllowed(code: String): Boolean {
        if (code.length > MAX_SCRIPT_CODE_BYTES) return false
        return code.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_CODE_BYTES
    }

    fun scriptMetadataAllowed(metadata: String): Boolean = boundedUtf8(metadata, MAX_SCRIPT_METADATA_BYTES)

    fun musicInfoAllowed(musicInfo: String): Boolean = boundedUtf8(musicInfo, MAX_MUSIC_INFO_BYTES)

    fun requestAllowed(url: String, headersJson: String?, body: String?): Boolean {
        if (url.length > MAX_REQUEST_URL_CHARS) return false
        if (headersJson != null && headersJson.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_HEADERS_BYTES) return false
        if (body != null && (body.length > MAX_REQUEST_BODY_BYTES || body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BODY_BYTES)) return false
        return true
    }

    private fun boundedUtf8(value: String, maxBytes: Int): Boolean {
        if (value.length > maxBytes) return false
        return value.toByteArray(Charsets.UTF_8).size <= maxBytes
    }
}
