package xyz.cncnlp.soundwave.download

import java.io.IOException

/** Strict, satisfiable Content-Range for a single byte range (not multipart). */
internal object ContentRangePolicy {
    data class Range(val start: Long, val end: Long, val total: Long) {
        // end < total <= Long.MAX_VALUE keeps the inclusive length in bounds.
        val byteCount: Long get() = end - start + 1L
    }

    private val pattern = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)

    fun parse(value: String?): Range? {
        val match = pattern.matchEntire(value?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start > end || end >= total) return null
        return Range(start, end, total)
    }

    /** An open-ended GET must deliver the whole remainder, including offset-zero 206. */
    fun requireSingle(value: String?, offset: Long, expectedTotal: Long, contentLength: Long): Range {
        val range = parse(value) ?: throw IOException("缺少或无效的 Content-Range")
        if (range.start != offset || range.end != range.total - 1L ||
            (expectedTotal > 0L && range.total != expectedTotal) ||
            (contentLength >= 0L && contentLength != range.byteCount)) {
            throw IOException("单流 Content-Range 与请求或实体长度不匹配")
        }
        return range
    }

    fun requireBodyLength(range: Range, actualBytes: Long) {
        if (actualBytes != range.byteCount) throw IOException("Range 响应长度不匹配：$actualBytes/${range.byteCount}")
    }
}
