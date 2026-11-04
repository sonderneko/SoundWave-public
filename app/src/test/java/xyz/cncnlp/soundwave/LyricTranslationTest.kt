package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.source.direct.parseTxLyricPayload
import xyz.cncnlp.soundwave.source.direct.parseWyLyricPayload
import java.util.Base64

class LyricTranslationTest {
    private fun lrc(vararg rows: Pair<String, String>) = rows.joinToString("\n") { (time, text) -> "[$time]$text" }
    private fun base64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test
    fun wyTlyricPairsOnlyWithExactMainTimestamps() {
        val main = lrc("00:01.00" to "Hello", "00:02.00" to "World")
        val translation = lrc("00:01.00" to "你好", "00:02.00" to "世界")
        val response = org.json.JSONObject()
            .put("lrc", org.json.JSONObject().put("lyric", main))
            .put("tlyric", org.json.JSONObject().put("lyric", translation))
            .toString()

        val payload = requireNotNull(parseWyLyricPayload(response, allowYrc = false))
        assertEquals(listOf("你好", "世界"), parseLyricTextBundle(encodeLyricTextBundle(payload)).map { it.translation })
    }

    @Test
    fun wyTlyricPairsWithTheSelectedYrcStartTimestamp() {
        val yrc = "[1000,1200](1000,600,0)hello"
        val translation = "[00:01.00]你好"
        val response = org.json.JSONObject()
            .put("yrc", org.json.JSONObject().put("lyric", yrc))
            .put("lrc", org.json.JSONObject().put("lyric", "[00:01.00]fallback"))
            .put("tlyric", org.json.JSONObject().put("lyric", translation))
            .toString()

        val payload = requireNotNull(parseWyLyricPayload(response, allowYrc = true))
        assertEquals(yrc, payload.main)
        assertEquals("你好", parseLyricTextBundle(encodeLyricTextBundle(payload)).single().translation)
    }

    @Test
    fun wyRomalrcPairsOnlyAtExactMainTimestampsAndSurvivesV2CacheRoundTrip() {
        val main = lrc("00:01.00" to "こんにちは", "00:02.00" to "さようなら")
        val translation = lrc("00:01.00" to "你好", "00:02.00" to "再见")
        val romanization = lrc("00:01.00" to "konnichiwa", "00:02.01" to "sayounara")
        val response = org.json.JSONObject()
            .put("lrc", org.json.JSONObject().put("lyric", main))
            .put("tlyric", org.json.JSONObject().put("lyric", translation))
            .put("romalrc", org.json.JSONObject().put("lyric", romanization))
            .toString()

        val payload = requireNotNull(parseWyLyricPayload(response, allowYrc = false))
        val encoded = encodeLyricTextBundle(payload)
        val lines = parseLyricTextBundle(encoded)

        assertTrue(encoded.startsWith("#SOUNDWAVE_LYRIC_BUNDLE_V2\n"))
        assertEquals(listOf("你好", "再见"), lines.map { it.translation })
        assertEquals(listOf("konnichiwa", ""), lines.map { it.romanization })
    }

    @Test
    fun wyRomalrcDuplicateTimestampsPairInStableOrderWithDuplicateYrcRows() {
        val yrc = "[1000,500](1000,250,0)日\n[1000,500](1000,250,0)本"
        val romanization = lrc("00:01.00" to "ichi", "00:01.00" to "ni")
        val response = org.json.JSONObject()
            .put("yrc", org.json.JSONObject().put("lyric", yrc))
            .put("lrc", org.json.JSONObject().put("lyric", "[00:01.00]fallback"))
            .put("romalrc", org.json.JSONObject().put("lyric", romanization))
            .toString()

        val payload = requireNotNull(parseWyLyricPayload(response, allowYrc = true))
        val lines = parseLyricTextBundle(encodeLyricTextBundle(payload))

        assertEquals(yrc, payload.main)
        assertEquals(listOf("日", "本"), lines.map { it.text })
        assertEquals(listOf("ichi", "ni"), lines.map { it.romanization })
    }

    @Test
    fun malformedOptionalRomalrcNeverDropsMainOrTranslation() {
        val main = "[00:01.00]こんにちは"
        val translation = "[00:01.00]你好"
        val response = org.json.JSONObject()
            .put("lrc", org.json.JSONObject().put("lyric", main))
            .put("tlyric", org.json.JSONObject().put("lyric", translation))
            .put("romalrc", org.json.JSONObject().put("lyric", org.json.JSONObject().put("bad", true)))
            .toString()

        val payload = requireNotNull(parseWyLyricPayload(response, allowYrc = false))
        val line = parseLyricTextBundle(encodeLyricTextBundle(payload)).single()

        assertEquals("こんにちは", line.text)
        assertEquals("你好", line.translation)
        assertEquals("", line.romanization)
    }

    @Test
    fun qqTransPairsOnlyWithExactMainTimestampsAndDoesNotSetRomanization() {
        val main = lrc("00:01.00" to "Hello", "00:02.00" to "World")
        val translation = lrc("00:01.00" to "你好", "00:02.00" to "世界")
        val response = org.json.JSONObject()
            .put("lyric", base64(main))
            .put("trans", base64(translation))
            .toString()

        val payload = requireNotNull(parseTxLyricPayload(response))
        val lines = parseLyricTextBundle(encodeLyricTextBundle(payload))
        assertEquals(listOf("你好", "世界"), lines.map { it.translation })
        assertTrue(lines.all { it.romanization.isBlank() })
    }

    @Test
    fun translationWithOffsetTimestampIsNotFuzzilyMatched() {
        val main = LyricParser.parse("[00:01.00]hello")
        val translation = LyricParser.parse("[00:01.01]你好")
        assertEquals(1_000L, main.single().timeMs)
        assertEquals(1_010L, translation.single().timeMs)
        assertEquals("", pairLyricTranslations(main, translation).single().translation)
    }

    @Test
    fun duplicateTimestampsConsumeTranslationsInStableOrderAndPreserveRomanization() {
        val main = listOf(
            LyricLine(1_000L, "first", romanization = "romaji-1"),
            LyricLine(1_000L, "second", romanization = "romaji-2"),
            LyricLine(2_000L, "unmatched")
        )
        val translations = listOf(
            LyricLine(1_000L, "第一"),
            LyricLine(1_000L, "第二"),
            LyricLine(3_000L, "不匹配")
        )
        val paired = pairLyricTranslations(main, translations)
        assertEquals(listOf("第一", "第二", ""), paired.map { it.translation })
        assertEquals(listOf("romaji-1", "romaji-2", ""), paired.map { it.romanization })
    }

    @Test
    fun malformedEmptyAndInvalidBase64ResponsesFailSafelyWithoutLosingMainLyrics() {
        val main = "[00:01.00]hello"
        assertNull(parseWyLyricPayload("", allowYrc = true))
        assertNull(parseWyLyricPayload("not-json", allowYrc = true))
        assertNull(parseTxLyricPayload(""))
        assertNull(parseTxLyricPayload("not-json"))

        val response = org.json.JSONObject()
            .put("lyric", base64(main))
            .put("trans", "!!!not-base64!!!")
            .toString()
        val payload = requireNotNull(parseTxLyricPayload(response))
        assertEquals(main, payload.main)
        assertEquals("", payload.translation)
        assertTrue(parseLyricTextBundle(encodeLyricTextBundle(payload)).single().translation.isEmpty())
    }

    @Test
    fun missingAndUntimedTranslationsRemainBlank() {
        assertFalse(shouldShowLyricTranslation(LyricLine(1_000L, "Hello"), null))
        val untimedMain = listOf(LyricLine(-1L, "Hello"))
        val untimedTranslation = listOf(LyricLine(-1L, "你好"))
        assertEquals("", pairLyricTranslations(untimedMain, untimedTranslation).single().translation)
    }

    @Test
    fun automaticVisibilityHidesChineseIncludingTraditionalAndMixedChineseEnglish() {
        assertFalse(shouldShowLyricTranslation(LyricLine(0, "你好", translation = "Hello"), null))
        assertFalse(shouldShowLyricTranslation(LyricLine(0, "妳好，世界", translation = "Hello"), null))
        assertFalse(shouldShowLyricTranslation(LyricLine(0, "Hello你好", translation = "Hello"), null))
    }

    @Test
    fun automaticVisibilityShowsExistingEnglishJapaneseAndKoreanTranslations() {
        assertTrue(shouldShowLyricTranslation(LyricLine(0, "Hello world", translation = "你好"), null))
        assertTrue(shouldShowLyricTranslation(LyricLine(0, "こんにちは", translation = "你好"), null))
        assertTrue(shouldShowLyricTranslation(LyricLine(0, "明日へ", translation = "To tomorrow"), null))
        assertTrue(shouldShowLyricTranslation(LyricLine(0, "안녕하세요", translation = "你好"), null))
        assertFalse(shouldShowLyricTranslation(LyricLine(0, "Hello world"), null))
    }

    @Test
    fun explicitPreferenceOverridesAutomaticLanguagePolicy() {
        val chinese = LyricLine(0, "你好", translation = "Hello")
        val english = LyricLine(0, "Hello", translation = "你好")
        assertTrue(shouldShowLyricTranslation(chinese, true))
        assertFalse(shouldShowLyricTranslation(english, false))
    }

    @Test
    fun legacyPlainLyricCacheRemainsReadableAndCorrectionOverrideStaysFirst() {
        val oldCache = "[00:01.00]Hello"
        assertEquals("Hello", parseLyricTextBundle(oldCache).single().text)
        val correction = "[00:01.00]User corrected lyric"
        val bundledNetwork = encodeLyricTextBundle(
            LyricTextBundle(
                "[00:01.00]Hello",
                "[00:01.00]你好",
                "[00:01.00]romaji: heh-loh"
            )
        )
        val originalNetworkCache = bundledNetwork
        var cachedNetworkText = originalNetworkCache
        var normalCacheWrites = 0
        val displayed = preferUserLyricCorrection(correction) {
            normalCacheWrites++
            cachedNetworkText = "ordinary network cache refresh"
            cachedNetworkText
        }
        assertEquals(correction, displayed)
        assertEquals(0, normalCacheWrites)
        assertEquals(originalNetworkCache, cachedNetworkText)
    }

    @Test
    fun versionOneBundleCacheRemainsReadable() {
        val main = "[00:01.00]Hello"
        val translation = "[00:01.00]你好"
        val oldCache = "#SOUNDWAVE_LYRIC_BUNDLE_V1\n${base64(main)}\n${base64(translation)}"

        val line = parseLyricTextBundle(oldCache).single()
        assertEquals("Hello", line.text)
        assertEquals("你好", line.translation)
        assertEquals("", line.romanization)
        assertEquals(main, lyricBundleMainText(oldCache))
    }

    @Test
    fun v2BundleRoundTripsMainTranslationAndExplicitRomanizationTracks() {
        val main = lrc("00:01.00" to "こんにちは", "00:02.00" to "さようなら")
        val translation = lrc("00:01.00" to "你好", "00:02.00" to "再见")
        val romanization = lrc("00:01.00" to "romaji: konnichiwa", "00:02.00" to "romaji: sayonara")

        val encoded = encodeLyricTextBundle(LyricTextBundle(main, translation, romanization))
        val lines = parseLyricTextBundle(encoded)
        assertTrue(encoded.startsWith("#SOUNDWAVE_LYRIC_BUNDLE_V2\n"))
        assertEquals(listOf("こんにちは", "さようなら"), lines.map { it.text })
        assertEquals(listOf("你好", "再见"), lines.map { it.translation })
        assertEquals(listOf("konnichiwa", "sayonara"), lines.map { it.romanization })
    }

    @Test
    fun romanizationWithoutTranslationRoundTripsAndKeepsTranslationBlank() {
        val main = "[00:01.00]こんにちは"
        val romanization = "[00:01.00]konnichiwa"
        val encoded = encodeLyricTextBundle(LyricTextBundle(main, romanization = romanization))

        val line = parseLyricTextBundle(encoded).single()
        assertTrue(encoded.startsWith("#SOUNDWAVE_LYRIC_BUNDLE_V2\n"))
        assertEquals("", line.translation)
        assertEquals("konnichiwa", line.romanization)
    }

    @Test
    fun emptyCompanionTracksKeepLegacyPlainCacheAndV2EmptyTrackCompatibility() {
        val main = "[00:01.00]Hello"
        assertEquals(main, encodeLyricTextBundle(LyricTextBundle(main)))
        assertEquals("", parseLyricTextBundle(main).single().romanization)

        val translationOnly = encodeLyricTextBundle(LyricTextBundle(main, "[00:01.00]你好"))
        val line = parseLyricTextBundle(translationOnly).single()
        assertEquals("你好", line.translation)
        assertEquals("", line.romanization)
    }

    @Test
    fun romanizationSidecarDoesNotFuzzilyMatchDifferentTimestamp() {
        val main = lrc("00:01.00" to "こんにちは", "00:02.00" to "さようなら")
        val romanization = lrc("00:01.01" to "konnichiwa")
        val lines = parseLyricTextBundle(encodeLyricTextBundle(LyricTextBundle(main, romanization = romanization)))

        assertEquals(listOf("", ""), lines.map { it.romanization })
    }
}
