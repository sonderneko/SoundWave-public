package xyz.cncnlp.soundwave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.download.AudioQualityEvidence

/**
 * ACCEPT6（qqslow）：试听片段时长完整性判定（isPreviewFragment）。
 * 样本按真机形态构造：未登录试听 30~60s；完整文件取各码率真实量级。
 * 核心纪律：只拦「可证伪的短」，低码完整文件与近完整文件不得误伤。
 */
class DeliveredDurationIntegrityTest {

    private fun kb(n: Long) = n * 1024L

    @Test
    fun preview45sAt128kForFourMinuteSongIsFragment() {
        // 45s × 128kbps ≈ 720KB；歌曲 240s——旧 160KB 绝对下限对此完全无感。
        assertTrue(AudioQualityEvidence.isPreviewFragment(kb(720), 240_000L, "MP3"))
    }

    @Test
    fun preview60sAt128kIsFragment() {
        assertTrue(AudioQualityEvidence.isPreviewFragment(kb(960), 240_000L, "MP3"))
    }

    @Test
    fun preview30sAt320kIsFragment() {
        // 30s × 320kbps ≈ 1200KB；高码试听同样按时长折算拦下。
        assertTrue(AudioQualityEvidence.isPreviewFragment(kb(1200), 240_000L, "MP3"))
    }

    @Test
    fun fullSongAt128kPasses() {
        assertFalse(AudioQualityEvidence.isPreviewFragment(kb(3840), 240_000L, "MP3"))
    }

    @Test
    fun fullSongAt96kPasses() {
        // 240s × 96kbps ≈ 2880KB——低码完整文件不得误伤（112k 下限的用意）。
        assertFalse(AudioQualityEvidence.isPreviewFragment(kb(2880), 240_000L, "MP3"))
    }

    @Test
    fun nearFullLengthPasses() {
        // 230s/240s 的近完整文件在双条件容差（85% 且 15s）内放行。
        assertFalse(AudioQualityEvidence.isPreviewFragment(kb(3600), 240_000L, "MP3"))
    }

    @Test
    fun flacPreviewIsFragment() {
        // 45s FLAC ≈ 5MB；歌曲 240s（无损按 320kbps 下限折算）。
        assertTrue(AudioQualityEvidence.isPreviewFragment(kb(5120), 240_000L, "FLAC"))
    }

    @Test
    fun flacFullSongPasses() {
        assertFalse(AudioQualityEvidence.isPreviewFragment(kb(25_600), 240_000L, "FLAC"))
    }

    @Test
    fun shortSongIsNeverJudged() {
        // 50s 短歌与片段同量级：信息不足不拦。
        assertFalse(AudioQualityEvidence.isPreviewFragment(kb(480), 50_000L, "MP3"))
    }

    @Test
    fun unknownSizeOrDurationIsNeverJudged() {
        assertFalse(AudioQualityEvidence.isPreviewFragment(0L, 240_000L, "MP3"))
        assertFalse(AudioQualityEvidence.isPreviewFragment(kb(720), 0L, "MP3"))
    }
}
