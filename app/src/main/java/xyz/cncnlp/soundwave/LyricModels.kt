package xyz.cncnlp.soundwave

/** 词级时间轴（YRC/QRC/增强 LRC）。startMs/endMs 相对整首歌。 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    // 多语种 LRC 常把原文、翻译、罗马音并列在同一个时间戳。
    val translation: String = "",
    val romanization: String = "",
    val words: List<LyricWord> = emptyList()
) {
    fun hasWordTimeline(): Boolean = words.size >= 2 && words.any { it.endMs > it.startMs }
}

/**
 * LRC / 增强 LRC / 网易 YRC / QQ QRC 统一解析。
 * 无词轴时 words 为空，渲染层回退整句进度刷亮。
 */
object LyricParser {
    private val lineRe = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?](.*)""")
    private val translationTag = Regex("""^(?:\[?trans(?:lation)?]?|翻译|译)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    private val romanTag = Regex("""^(?:\[?(?:roma|romanization|romaji|pinyin)]?|罗马音|拼音)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    // 网易 YRC: [start,duration](start,dur,0)字(start,dur,0)字
    private val yrcLineRe = Regex("""\[(\d+),(\d+)](.*)""")
    private val yrcWordRe = Regex("""\((\d+),(\d+),?\d*\)([^()\n]*)""")
    // QQ QRC 简化: [offset,duration]词(start,dur)词
    private val qrcLineRe = Regex("""\[(\d+),(\d+)](.*)""")
    private val qrcWordRe = Regex("""([^()\n]+?)\((\d+),(\d+)\)""")
    // 增强 LRC: [mm:ss.xx]<mm:ss.xx>字<mm:ss.xx>字
    private val enhancedWordRe = Regex("""<(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?>([^<]*)""")

    private fun parseVariant(text: String): Triple<String, String, String> {
        val parts = text.split(Regex("""\s*(?:\||\|\||\s{2,})\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
        val main = parts.firstOrNull().orEmpty()
        var trans = ""
        var roma = ""
        parts.drop(1).forEach { part ->
            when {
                romanTag.containsMatchIn(part) -> roma = romanTag.replace(part, "").trim()
                translationTag.containsMatchIn(part) -> trans = translationTag.replace(part, "").trim()
                trans.isEmpty() -> trans = part
                roma.isEmpty() -> roma = part
            }
        }
        return Triple(main, trans, roma)
    }

    private fun isRomanText(value: String): Boolean =
        value.any { it.isLetter() } && value.none { it in '\u4E00'..'\u9FFF' }

    private fun fracToMs(frac: String): Long = when {
        frac.isEmpty() -> 0L
        frac.length <= 2 -> (frac.padEnd(2, '0').toLongOrNull() ?: 0L) * 10
        else -> (frac.take(3).padEnd(3, '0').toLongOrNull() ?: 0L)
    }

    private fun clockToMs(min: String, sec: String, frac: String): Long {
        val m = min.toLongOrNull() ?: return -1L
        val s = sec.toLongOrNull() ?: return -1L
        return m * 60_000 + s * 1000 + fracToMs(frac)
    }

    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val text = raw.trim()
        // QRC timestamps follow word text; YRC timestamp tuples prefix each word.
        if (text.contains(Regex("""\[\d+,\d+]""")) && text.contains(Regex("""\(\d+,\d+"""))) {
            val hasYrcPrefixMarkers = text.lineSequence().any { rawLine ->
                val match = yrcLineRe.find(rawLine.trim()) ?: return@any false
                match.groupValues[3].trimStart().startsWith("(")
            }
            val wordTimestamped = if (hasYrcPrefixMarkers) {
                parseYrc(text).ifEmpty { parseQrc(text) }
            } else {
                parseQrc(text).ifEmpty { parseYrc(text) }
            }
            if (wordTimestamped.isNotEmpty()) return wordTimestamped
        }
        val timestamped = parseLrc(text)
        return timestamped.ifEmpty { parsePlainText(text) }
    }

    /** Untimed provider text remains readable, but -1 explicitly disables auto-highlight and seeking. */
    private fun parsePlainText(raw: String): List<LyricLine> = raw.lines()
        .map(String::trim)
        .filter { line ->
            line.isNotEmpty() &&
                !line.startsWith("http", ignoreCase = true) &&
                !line.matches(Regex("""(?i)^\[(?:ti|ar|al|by|offset|re|ve|length|kana|id):.*]$""")) &&
                !(line.startsWith("[") && line.contains("]"))
        }
        .map { LyricLine(timeMs = -1L, text = it) }

    private fun parseYrc(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (line in raw.lines()) {
            val m = yrcLineRe.find(line.trim()) ?: continue
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val dur = m.groupValues[2].toLongOrNull() ?: 0L
            val body = m.groupValues[3]
            val words = yrcWordRe.findAll(body).mapNotNull { wm ->
                val ws = wm.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val wd = wm.groupValues[2].toLongOrNull() ?: 0L
                val wt = wm.groupValues[3]
                if (wt.isEmpty()) null else LyricWord(ws, ws + wd.coerceAtLeast(1L), wt)
            }.toList()
            val text = words.joinToString("") { it.text }.ifBlank {
                body.replace(yrcWordRe, "").trim()
            }
            if (text.isBlank()) continue
            val end = if (words.isNotEmpty()) words.last().endMs else start + dur.coerceAtLeast(1L)
            val normalized = if (words.isEmpty()) emptyList() else words
            out += LyricLine(start, text, words = normalized.ifEmpty {
                // 无词块时仍保留行时长，供整句刷亮窗口
                listOf(LyricWord(start, end, text))
            }.let { if (it.size == 1 && !body.contains('(')) emptyList() else it })
        }
        return out.sortedBy { it.timeMs }
    }

    private fun parseQrc(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (line in raw.lines()) {
            val m = qrcLineRe.find(line.trim()) ?: continue
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val dur = m.groupValues[2].toLongOrNull() ?: 0L
            val body = m.groupValues[3]
            // QRC 常见: 词(相对起点,时长)
            val words = ArrayList<LyricWord>()
            qrcWordRe.findAll(body).forEach { wm ->
                val wt = wm.groupValues[1]
                val rel = wm.groupValues[2].toLongOrNull() ?: 0L
                val wd = wm.groupValues[3].toLongOrNull() ?: 0L
                val ws = start + rel
                if (wt.isNotEmpty()) {
                    words += LyricWord(ws, ws + wd.coerceAtLeast(1L), wt)
                }
            }
            val text = words.joinToString("") { it.text }.ifBlank { body.replace(Regex("""\(\d+,\d+\)"""), "").trim() }
            if (text.isBlank()) continue
            out += LyricLine(
                start,
                text,
                words = if (words.size >= 2) words else emptyList()
            )
        }
        return out.sortedBy { it.timeMs }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val main = linkedMapOf<Long, String>()
        val translations = linkedMapOf<Long, String>()
        val romanizations = linkedMapOf<Long, String>()
        val wordsMap = linkedMapOf<Long, List<LyricWord>>()
        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val matches = lineRe.findAll(line).toList()
            if (matches.isEmpty()) continue
            val rawText = matches.last().groupValues.getOrNull(4)?.trim().orEmpty()
            if (rawText.isEmpty() || rawText.startsWith("http")) continue
            val taggedRoman = romanTag.containsMatchIn(rawText)
            val taggedTranslation = translationTag.containsMatchIn(rawText)
            val enhancedWords = parseEnhancedWords(rawText)
            val plain = if (enhancedWords.isNotEmpty()) {
                enhancedWords.joinToString("") { it.text }
            } else rawText
            val (text, inlineTranslation, inlineRomanization) = parseVariant(plain)
            for (m in matches) {
                val at = clockToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                if (at < 0) continue
                when {
                    taggedRoman -> romanizations[at] = romanTag.replace(rawText, "").trim()
                    taggedTranslation -> translations[at] = translationTag.replace(rawText, "").trim()
                    main.containsKey(at) && translations[at].isNullOrEmpty() && !isRomanText(text) -> translations[at] = text
                    main.containsKey(at) && romanizations[at].isNullOrEmpty() && isRomanText(text) -> romanizations[at] = text
                    else -> {
                        main[at] = text
                        if (inlineTranslation.isNotEmpty()) translations[at] = inlineTranslation
                        if (inlineRomanization.isNotEmpty()) romanizations[at] = inlineRomanization
                        if (enhancedWords.size >= 2) wordsMap[at] = enhancedWords
                    }
                }
            }
        }
        return main.map { (at, text) ->
            LyricLine(at, text, translations[at].orEmpty(), romanizations[at].orEmpty(), wordsMap[at].orEmpty())
        }.sortedBy { it.timeMs }
    }

    private fun parseEnhancedWords(rawText: String): List<LyricWord> {
        val found = enhancedWordRe.findAll(rawText).toList()
        if (found.size < 2) return emptyList()
        val words = ArrayList<LyricWord>()
        found.forEachIndexed { i, m ->
            val start = clockToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            if (start < 0) return@forEachIndexed
            val text = m.groupValues[4]
            if (text.isEmpty()) return@forEachIndexed
            val end = if (i + 1 < found.size) {
                clockToMs(found[i + 1].groupValues[1], found[i + 1].groupValues[2], found[i + 1].groupValues[3]).takeIf { it > start }
                    ?: (start + 400L)
            } else start + 500L
            words += LyricWord(start, end, text)
        }
        return words
    }

    fun indexAt(lines: List<LyricLine>, posMs: Long, offsetMs: Long = 0): Int {
        if (lines.isEmpty()) return -1
        val t = posMs + offsetMs
        var idx = -1
        for (i in lines.indices) {
            val at = lines[i].timeMs
            if (at < 0L) continue
            if (at <= t) idx = i else break
        }
        return idx
    }

    /** 0..1 word progress only when the provider supplied a real word timeline. */
    fun lineProgress(line: LyricLine, next: LyricLine?, posMs: Long): Float {
        val words = effectiveWords(line, next)
        if (words.size >= 2) return wordClipFraction(words, posMs)
        if (line.timeMs < 0L) return 0f
        return if (posMs >= line.timeMs) 1f else 0f
    }

    /** Only provider-supplied word timestamps are exposed as a word timeline. */
    @Suppress("UNUSED_PARAMETER")
    fun effectiveWords(line: LyricLine, next: LyricLine?): List<LyricWord> {
        return line.words.takeIf { line.hasWordTimeline() }.orEmpty()
    }

    /** 按字宽权重计算 0..1 裁剪进度（中文单字不再被英文词均分时间）。 */
    fun wordClipFraction(words: List<LyricWord>, posMs: Long): Float {
        if (words.isEmpty()) return 0f
        if (posMs <= words.first().startMs) return 0f
        if (posMs >= words.last().endMs) return 1f
        fun weight(t: String): Float = when {
            t.isBlank() -> 0.4f
            t.any { it in '\u4E00'..'\u9FFF' || it in '\u3040'..'\u30FF' || it in '\uAC00'..'\uD7AF' } -> 2f * t.length
            else -> 1f * t.length.coerceAtLeast(1)
        }
        val weights = words.map { weight(it.text) }
        val total = weights.sum().coerceAtLeast(0.01f)
        var acc = 0f
        for (i in words.indices) {
            val w = words[i]
            val wt = weights[i]
            when {
                posMs >= w.endMs -> acc += wt
                posMs >= w.startMs -> {
                    val span = (w.endMs - w.startMs).coerceAtLeast(1L).toFloat()
                    acc += wt * ((posMs - w.startMs) / span).coerceIn(0f, 1f)
                    break
                }
                else -> break
            }
        }
        return (acc / total).coerceIn(0f, 1f)
    }
}
