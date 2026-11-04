package xyz.cncnlp.soundwave

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Main lyric text and optional companion tracks supplied by the same source response. */
internal data class LyricTextBundle(
    val main: String,
    val translation: String = "",
    val romanization: String = ""
)

private const val LYRIC_BUNDLE_HEADER_V1 = "#SOUNDWAVE_LYRIC_BUNDLE_V1\n"
private const val LYRIC_BUNDLE_HEADER_V2 = "#SOUNDWAVE_LYRIC_BUNDLE_V2\n"
private val romanizationTrackTag = Regex(
    """^(?:\[?(?:roma|romanization|romaji|pinyin)]?|罗马音|拼音)\s*[:：]\s*""",
    RegexOption.IGNORE_CASE
)
private val lrcTimestampPrefix = Regex(
    """^(\[(?:\d{1,2}:\d{1,2}(?:[.:]\d{1,3})?])*)(.*)$"""
)

/**
 * Cache envelope for provider companion tracks. V1 main+translation and plain legacy cache values
 * remain readable; V2 carries main, optional translation and optional romanization independently.
 */
internal fun encodeLyricTextBundle(bundle: LyricTextBundle): String {
    if (bundle.main.isBlank()) return bundle.main
    if (bundle.translation.isBlank() && bundle.romanization.isBlank()) return bundle.main
    val encoder = Base64.getEncoder()
    fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    return buildString {
        append(LYRIC_BUNDLE_HEADER_V2)
        append(encode(bundle.main)).append('\n')
        append(encode(bundle.translation)).append('\n')
        append(encode(bundle.romanization))
    }
}

/** Main-only text for sidecar export; ordinary old cache values pass through unchanged. */
internal fun lyricBundleMainText(raw: String): String = decodeLyricTextBundle(raw)?.main ?: raw

/** Parse either a legacy lyric cache value or a versioned companion-track cache envelope. */
internal fun parseLyricTextBundle(raw: String): List<LyricLine> {
    val bundle = decodeLyricTextBundle(raw) ?: return LyricParser.parse(raw)
    var lines = LyricParser.parse(bundle.main)
    if (bundle.translation.isNotBlank()) {
        lines = pairLyricTranslations(lines, LyricParser.parse(bundle.translation))
    }
    if (bundle.romanization.isNotBlank()) {
        lines = pairLyricRomanizations(lines, parseRomanizationTrack(bundle.romanization))
    }
    return lines
}

/** Pair rows only at an identical, valid timestamp; equal-time rows consume tracks in order. */
internal fun pairLyricTranslations(
    mainLines: List<LyricLine>,
    translationLines: List<LyricLine>
): List<LyricLine> = pairLyricTrack(mainLines, translationLines,
    hasValue = { it.translation.isNotBlank() },
    applyValue = { line, value -> line.copy(translation = value) }
)

/** Romanization is treated as a real, separately supplied track, never inferred from Latin text. */
internal fun pairLyricRomanizations(
    mainLines: List<LyricLine>,
    romanizationLines: List<LyricLine>
): List<LyricLine> = pairLyricTrack(mainLines, romanizationLines,
    hasValue = { it.romanization.isNotBlank() },
    applyValue = { line, value -> line.copy(romanization = value) }
)

private fun pairLyricTrack(
    mainLines: List<LyricLine>,
    trackLines: List<LyricLine>,
    hasValue: (LyricLine) -> Boolean,
    applyValue: (LyricLine, String) -> LyricLine
): List<LyricLine> {
    val tracksByTime = linkedMapOf<Long, MutableList<String>>()
    trackLines.forEach { line ->
        if (line.timeMs >= 0L && line.text.isNotBlank()) {
            tracksByTime.getOrPut(line.timeMs) { mutableListOf() }.add(line.text)
        }
    }
    val consumedByTime = mutableMapOf<Long, Int>()
    return mainLines.map { line ->
        if (line.timeMs < 0L || hasValue(line)) return@map line
        val candidates = tracksByTime[line.timeMs] ?: return@map line
        val index = consumedByTime[line.timeMs] ?: 0
        val value = candidates.getOrNull(index)?.takeIf(String::isNotBlank)
            ?: return@map line
        consumedByTime[line.timeMs] = index + 1
        applyValue(line, value)
    }
}

/** Allow explicitly tagged rows in a dedicated romanization track to be parsed as track text. */
private fun parseRomanizationTrack(raw: String): List<LyricLine> {
    return raw.lineSequence().flatMap { rawLine ->
        val line = rawLine.trim()
        val match = lrcTimestampPrefix.matchEntire(line)
        val untagged = if (match == null) line else {
            val body = match.groupValues[2].trimStart()
            match.groupValues[1] + romanizationTrackTag.replaceFirst(body, "")
        }
        // Parse each source row independently so repeated timestamps retain their stable order.
        LyricParser.parse(untagged).asSequence()
    }
        .toList()
}

/**
 * null means automatic: show an existing translation for non-Chinese source lyrics only.
 * true/false are explicit user choices and continue to show/hide translations globally.
 */
internal fun shouldShowLyricTranslation(line: LyricLine?, explicitPreference: Boolean?): Boolean {
    if (line == null || line.translation.isBlank()) return false
    return when (explicitPreference) {
        true -> true
        false -> false
        null -> hasNonChineseLetters(line.text)
    }
}

internal fun hasNonChineseLetters(text: String): Boolean {
    var hasHan = false
    var hasJapaneseKana = false
    var hasHangul = false
    var hasLetter = false
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        if (Character.isLetter(codePoint)) hasLetter = true
        if (codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F
        ) hasHan = true
        if (codePoint in 0x3040..0x30FF || codePoint in 0x31F0..0x31FF) hasJapaneseKana = true
        if (codePoint in 0x1100..0x11FF || codePoint in 0x3130..0x318F ||
            codePoint in 0xA960..0xA97F || codePoint in 0xAC00..0xD7AF || codePoint in 0xD7B0..0xD7FF
        ) hasHangul = true
        offset += Character.charCount(codePoint)
    }
    // Kana/Hangul identify Japanese/Korean even when those lines also contain Hanja/Kanji.
    return hasLetter && (hasJapaneseKana || hasHangul || !hasHan)
}

private fun decodeLyricTextBundle(raw: String): LyricTextBundle? = when {
    raw.startsWith(LYRIC_BUNDLE_HEADER_V1) -> decodeV1Bundle(raw.removePrefix(LYRIC_BUNDLE_HEADER_V1))
    raw.startsWith(LYRIC_BUNDLE_HEADER_V2) -> decodeV2Bundle(raw.removePrefix(LYRIC_BUNDLE_HEADER_V2))
    else -> null
}

private fun decodeV1Bundle(payload: String): LyricTextBundle? {
    val fields = splitBundleFields(payload, expectedCount = 2) ?: return null
    if (fields.any(String::isBlank)) return null
    val main = decodeUtf8Base64(fields[0]) ?: return null
    val translation = decodeUtf8Base64(fields[1]) ?: return null
    if (main.isBlank() || translation.isBlank()) return null
    return LyricTextBundle(main, translation)
}

private fun decodeV2Bundle(payload: String): LyricTextBundle? {
    val fields = splitBundleFields(payload, expectedCount = 3) ?: return null
    if (fields[0].isBlank()) return null
    val main = decodeUtf8Base64(fields[0]) ?: return null
    val translation = decodeUtf8Base64(fields[1]) ?: return null
    val romanization = decodeUtf8Base64(fields[2]) ?: return null
    if (main.isBlank()) return null
    return LyricTextBundle(main, translation, romanization)
}

/** Split a fixed-count, newline-delimited envelope while preserving empty optional V2 tracks. */
private fun splitBundleFields(payload: String, expectedCount: Int): List<String>? {
    val fields = ArrayList<String>(expectedCount)
    var start = 0
    repeat(expectedCount - 1) {
        val separator = payload.indexOf('\n', start)
        if (separator < 0) return null
        fields += payload.substring(start, separator)
        start = separator + 1
    }
    if (payload.indexOf('\n', start) >= 0) return null
    fields += payload.substring(start)
    return fields
}

private fun decodeUtf8Base64(value: String): String? = try {
    val bytes = Base64.getDecoder().decode(value)
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    null
}
