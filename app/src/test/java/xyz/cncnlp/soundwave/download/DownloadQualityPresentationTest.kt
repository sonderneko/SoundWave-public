package xyz.cncnlp.soundwave.download

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.USER_AUDIO_QUALITY_OPTIONS
import xyz.cncnlp.soundwave.ui.common.downloadQualityAccent

class DownloadQualityPresentationTest {
    @Test
    fun successfulProbeShowsConfirmedWholeFileSize() {
        val probe = AudioQualityProbe(requestedQuality = "atmos", bytes = 8L * 1024L * 1024L)

        assertEquals("8.00 MB", DownloadQualityPresentation.trailingStatus(probe))
    }

    @Test
    fun successfulProbeWithoutWholeFileLengthShowsUnknownSize() {
        val probe = AudioQualityProbe(requestedQuality = "flac", bytes = -1L)

        assertEquals("大小未知", DownloadQualityPresentation.trailingStatus(probe))
    }

    @Test
    fun failureReplacesSizeWithShortOutcome() {
        assertEquals(
            "取链失败",
            DownloadQualityPresentation.trailingStatus(
                AudioQualityProbe(requestedQuality = "flac", bytes = 99_000_000L, failure = "NO_PLAYABLE_URL")
            )
        )
        assertEquals(
            "取链超时",
            DownloadQualityPresentation.trailingStatus(
                AudioQualityProbe(requestedQuality = "atmos", failure = "取链超时或失败")
            )
        )
        assertEquals(
            "大小未知",
            DownloadQualityPresentation.trailingStatus(
                AudioQualityProbe(requestedQuality = "hires", failure = "媒体大小/格式响应未取得")
            )
        )
    }

    @Test
    fun sizeStatusRemainsSingleLineBeforeAndAfterProbeCompletes() {
        val statuses = listOf(
            DownloadQualityPresentation.trailingStatus(null),
            DownloadQualityPresentation.trailingStatus(AudioQualityProbe(requestedQuality = "flac", failure = "检测中")),
            DownloadQualityPresentation.trailingStatus(AudioQualityProbe(requestedQuality = "flac", bytes = 8L * 1024L * 1024L)),
            DownloadQualityPresentation.trailingStatus(AudioQualityProbe(requestedQuality = "flac", failure = "NO_PLAYABLE_URL"))
        )

        statuses.forEach { assertFalse(it.contains("\n")) }
    }

    @Test
    fun downgradeReasonIsConciseAndBoundedToOneLine() {
        assertEquals(
            "高音质请求仅返回 MP3 线索",
            DownloadQualityPresentation.compactDowngradeReason("高规格请求返回 MP3 格式线索（Content-Type audio/mpeg）")
        )
        assertEquals(
            "多个档位返回同一媒体链接",
            DownloadQualityPresentation.compactDowngradeReason("不同请求档位返回相同媒体链接，疑似未切换音质")
        )
        assertEquals(
            "返回音质低于所选",
            DownloadQualityPresentation.compactDowngradeReason("取链返回档位低于所选：128K（所选 FLAC）")
        )
        val fallback = DownloadQualityPresentation.compactDowngradeReason("很长的未知诊断原因".repeat(8))
        assertTrue(fallback.orEmpty().length <= 28)
        assertNull(DownloadQualityPresentation.compactDowngradeReason(null))
    }

    @Test
    fun selectingOptionDispatchesTheOriginalRequestedTier() {
        var submitted = ""

        DownloadQualityPresentation.selectRequestedQuality("hires") { submitted = it }

        assertEquals("hires", submitted)
    }

    @Test
    fun qualityOptionsUseDistinctSemanticAccents() {
        val qualities = USER_AUDIO_QUALITY_OPTIONS.map { it.first }
        val accents = qualities.map(::downloadQualityAccent)

        assertEquals(qualities.size, accents.toSet().size)
        assertEquals(Color(0xFF8A3324.toInt()), downloadQualityAccent("master"))
        assertEquals(Color(0xFFFF5C45.toInt()), downloadQualityAccent("hires"))
        assertEquals(Color(0xFFB45309.toInt()), downloadQualityAccent("flac24bit"))
        assertEquals(Color(0xFF15803D.toInt()), downloadQualityAccent("flac"))
        assertEquals(Color(0xFFBE123C.toInt()), downloadQualityAccent("320k"))
        assertEquals(Color(0xFF57504A.toInt()), downloadQualityAccent("128k"))
    }
}
