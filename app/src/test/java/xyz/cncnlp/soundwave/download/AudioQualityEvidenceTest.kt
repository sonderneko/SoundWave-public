package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ScriptProbeCache

class AudioQualityEvidenceTest {
    @Test
    fun partialContentLengthIsNotReportedAsWholeFileSize() {
        assertEquals(-1L to "", AudioQualityEvidence.measuredSize(206, "bytes 0-2047/", "2048"))
        assertEquals(-1L to "", AudioQualityEvidence.measuredSize(206, null, "2048"))
        assertEquals(123_456L to "Content-Range 总长度", AudioQualityEvidence.measuredSize(206, "bytes 0-2047/123456", "2048"))
        assertEquals(123_456L to "Content-Length", AudioQualityEvidence.measuredSize(200, null, "123456"))
    }

    @Test
    fun formatHintRequiresAnObservedExtensionOrAudioMime() {
        assertEquals("MP3", AudioQualityEvidence.formatHint("https://cdn.example/audio.mp3?token=private", "")?.label)
        assertEquals("MP3", AudioQualityEvidence.formatHint("https://cdn.example/stream?id=1", "audio/mpeg")?.label)
        assertEquals("MFLAC 加密封装", AudioQualityEvidence.formatHint("https://cdn.example/audio.mflac", "application/octet-stream")?.label)
        assertNull(AudioQualityEvidence.formatHint("https://cdn.example/stream?id=1", "application/octet-stream"))
    }

    @Test
    fun smallFileByItselfDoesNotTriggerDowngrade() {
        val tiny = AudioQualityProbe(requestedQuality = "atmos", bytes = 5L * 1024L * 1024L)
        assertNull(AudioQualityEvidence.suspectedDowngrade(tiny, emptyList()))
    }

    @Test
    fun explicitReturnedLowerTierIsFlagged() {
        val item = AudioQualityProbe(requestedQuality = "flac", resolvedQuality = "128k")
        assertNotNull(AudioQualityEvidence.suspectedDowngrade(item, emptyList()))
    }

    @Test
    fun mp3FormatIsFlaggedOnlyForHighTierRequest() {
        val lossless = AudioQualityProbe(
            requestedQuality = "flac",
            format = "MP3",
            formatEvidence = "Content-Type audio/mpeg"
        )
        assertNotNull(AudioQualityEvidence.suspectedDowngrade(lossless, emptyList()))
        val standard = lossless.copy(requestedQuality = "320k")
        assertNull(AudioQualityEvidence.suspectedDowngrade(standard, emptyList()))
    }

    @Test
    fun crossTierSizeWarningRequiresSameSongEvidenceAndLargeDifference() {
        val high = AudioQualityProbe(requestedQuality = "atmos", bytes = 2_000_000L)
        val lower = AudioQualityProbe(requestedQuality = "320k", bytes = 8_000_000L)
        assertNotNull(AudioQualityEvidence.suspectedDowngrade(high, listOf(lower)))
        assertNull(AudioQualityEvidence.suspectedDowngrade(high.copy(bytes = 5_000_000L), listOf(lower)))
        assertNull(AudioQualityEvidence.suspectedDowngrade(high.copy(bytes = -1L), listOf(lower)))
    }

    @Test
    fun identicalMediaFingerprintAndLowAverageBitrateAreSeparateEvidence() {
        val sameUrlHigh = AudioQualityProbe(requestedQuality = "hires", urlFingerprint = "same")
        val sameUrlLow = AudioQualityProbe(requestedQuality = "320k", urlFingerprint = "same")
        assertNotNull(AudioQualityEvidence.suspectedDowngrade(sameUrlHigh, listOf(sameUrlLow)))

        val lowRate = AudioQualityProbe(requestedQuality = "atmos", bytes = 5L * 1024L * 1024L, durationMs = 240_000L)
        assertNotNull(AudioQualityEvidence.suspectedDowngrade(lowRate, emptyList()))
        assertNull(AudioQualityEvidence.suspectedDowngrade(lowRate.copy(durationMs = 0L), emptyList()))
    }

    @Test
    fun probeReportShowsTheRealSampleRequestLatencyAndWholeSize() {
        val report = ScriptProbeCache.format(
            ScriptProbeCache.Report(
                scriptId = "script-a",
                scriptName = "脚本 A",
                attempts = listOf(
                    ScriptProbeCache.Attempt(
                        platform = "wy",
                        quality = "flac",
                        outcome = ScriptProbeCache.Outcome.OK,
                        resolveMs = 35L,
                        firstByteMs = 65L,
                        firstByteReceived = true,
                        bytes = 4L * 1024L * 1024L,
                        mime = "audio/flac",
                        playable = true,
                        urlHint = "https://cdn.example/audio.flac",
                        httpStatus = 206,
                        sampleName = "素颜",
                        sampleSinger = "许嵩",
                        sampleDurationMs = 240_000L,
                        searchMs = 100L,
                        requestDescription = "脚本调用 getMusicUrl(wy, flac)；媒体校验 GET Range: bytes=0-2047",
                        requestMethod = "GET",
                        rangeHeader = "bytes=0-2047",
                        contentRange = "bytes 0-2047/4194304",
                        sizeSource = "Content-Range 总长度",
                        formatHint = "FLAC",
                        formatEvidence = "URL 扩展名 .flac"
                    )
                )
            )
        )
        assertTrue(report.contains("匹配到: 素颜 - 许嵩"))
        assertTrue(report.contains("请求档位 flac"))
        assertTrue(report.contains("请求档位标注为请求值，不冒充响应实际码率"))
        assertTrue(report.contains("搜索100ms"))
        assertTrue(report.contains("脚本取链35ms"))
        assertTrue(report.contains("媒体首字节65ms"))
        assertTrue(report.contains("实测总大小 4.00 MB"))
        assertTrue(report.contains("疑似格式 FLAC"))
        assertTrue(report.contains("HTTP 206"))
        assertTrue(report.contains("大小来源 Content-Range 总长度"))
        assertTrue(report.contains("格式依据: URL 扩展名 .flac"))
        assertTrue(report.contains("GET Range: bytes=0-2047"))
        assertTrue(report.contains("bytes 0-2047/4194304"))
        assertTrue(report.contains("206 的分段 Content-Length"))
        assertFalse(report.contains("64 B"))
    }

    @Test
    fun cachedProbeFormatMarksQuickCoverageAndLegacyUnknownSize() {
        val text = ScriptProbeCache.format(
            ScriptProbeCache.Report(
                scriptId = "script-a",
                scriptName = "脚本 A",
                type = ScriptProbeCache.ReportType.QUICK,
                complete = false,
                attempts = listOf(
                    ScriptProbeCache.Attempt(
                        platform = "wy",
                        quality = "320k",
                        outcome = ScriptProbeCache.Outcome.OK,
                        firstByteMs = 20L,
                        bytes = -1L,
                        playable = true,
                        reason = "长度未知但可拉流",
                        sampleName = "鲜花"
                    )
                )
            )
        )
        assertTrue(text.contains("快速抽检"))
        assertTrue(text.contains("1 项实际尝试"))
        assertTrue(text.contains("实际样本×请求音质覆盖：网易云/鲜花：320k"))
        assertTrue(text.contains("总大小未知"))
        assertFalse(text.contains("0 B"))
    }

    @Test
    fun probeDowngradeComparisonNeverMixesDifferentFixedSongs() {
        val high = ScriptProbeCache.Attempt(
            platform = "wy",
            quality = "flac",
            outcome = ScriptProbeCache.Outcome.OK,
            firstByteMs = 20L,
            firstByteReceived = true,
            bytes = 2_000_000L,
            playable = true,
            sampleName = "歌曲甲",
            sampleQueryName = "歌曲甲"
        )
        val largerOtherSong = high.copy(
            quality = "320k",
            bytes = 8_000_000L,
            sampleName = "歌曲乙",
            sampleQueryName = "歌曲乙"
        )

        val text = ScriptProbeCache.format(
            ScriptProbeCache.Report(scriptId = "script-a", scriptName = "脚本 A", attempts = listOf(high, largerOtherSong))
        )

        assertFalse(text.contains("同曲文件大小明显低于"))
    }

    // ACCEPT5（B）：无声明链的实测估算——无损格式自证 flac；mp3 按平均码率
    // 分档且只许向下取实（阈值留余量）；证据不足返回 null（调用方按保守地板）。
    @Test
    fun `estimateDeliveredQuality uses format then bitrate evidence`() {
        assertEquals("flac", AudioQualityEvidence.estimateDeliveredQuality("FLAC", 0L, 0L))
        assertEquals("flac", AudioQualityEvidence.estimateDeliveredQuality("WAV (PCM)", 10_000_000L, 200_000L))
        // 320k mp3：约 12.5MB / 320s ≈ 312kbps → 320k 档。
        assertEquals("320k", AudioQualityEvidence.estimateDeliveredQuality("MP3", 12_500_000L, 320_000L))
        // 128k mp3：约 4MB / 250s ≈ 128kbps → 128k 档（不得虚报 192k/320k）。
        assertEquals("128k", AudioQualityEvidence.estimateDeliveredQuality("MP3", 4_000_000L, 250_000L))
        // 192k 区间：约 6MB / 250s ≈ 192kbps。
        assertEquals("192k", AudioQualityEvidence.estimateDeliveredQuality("MP3", 6_000_000L, 250_000L))
        // 证据不足：无字节或无时长 → null。
        assertEquals(null, AudioQualityEvidence.estimateDeliveredQuality("MP3", 0L, 250_000L))
        assertEquals(null, AudioQualityEvidence.estimateDeliveredQuality("MP3", 4_000_000L, 0L))
        assertEquals(null, AudioQualityEvidence.estimateDeliveredQuality(null, 0L, 0L))
        // 加密封装不按格式拔高：MFLAC 无码率证据 → null。
        assertEquals(null, AudioQualityEvidence.estimateDeliveredQuality("MFLAC", 0L, 0L))
    }

    // ACCEPT5（下载盖章）：落盘盖章只认实测——脚本请求 master 回 128k MP3
    // 时必须盖 128k（用户实证《雨爱》4:20 · 4.0MB ≈ 128kbps 却标 master）；
    // 实测无证据才回退自报档；实测为无损格式时盖 flac，不冒充 master。
    @Test
    fun `stampedDownloadQuality measures instead of trusting the claimed tier`() {
        assertEquals("128k", AudioQualityEvidence.stampedDownloadQuality("master", "MP3", 4_000_000L, 260_000L))
        assertEquals("320k", AudioQualityEvidence.stampedDownloadQuality("master", "MP3", 10_500_000L, 260_000L))
        assertEquals("flac", AudioQualityEvidence.stampedDownloadQuality("master", "FLAC", 25_000_000L, 260_000L))
        // 无实测证据（缺字节/时长且格式非无损）→ 回退自报档，不编造。
        assertEquals("master", AudioQualityEvidence.stampedDownloadQuality("master", "MP3", 0L, 0L))
        assertEquals("320k", AudioQualityEvidence.stampedDownloadQuality("320k", null, 0L, 0L))
    }

    // ACCEPT6（mgquality）：播放盖章——声称与实测冲突以实测为准。咪咕实证：
    // 官方目录 SQ 为真 FLAC（androidFileType=flac），但匿名/脚本通道只出 MP3，
    // 脚本按阶梯档声称 FLAC 交付 MP3 时必须按实测 MP3 码率档盖章，不得标 FLAC。
    @Test
    fun `stampedPlaybackQuality trusts measured evidence over the claimed tier`() {
        // 渡千关（128s）：魔数判明 MP3 + PQ 尺寸 2,062,003B ≈ 129kbps → 128k。
        assertEquals("128k", AudioQualityEvidence.stampedPlaybackQuality("flac", "MP3", "MP3", "URL 扩展名 .mp3", 2_062_003L, 128_000L))
        // 同歌 HQ 尺寸 5,154,692B ≈ 322kbps → 320k。
        assertEquals("320k", AudioQualityEvidence.stampedPlaybackQuality("flac", "MP3", "MP3", "URL 扩展名 .mp3", 5_154_692L, 128_000L))
        // CT 判明 MP3（无魔数）+ 码率不可测 → oiapi 先例的保守地板 128k。
        assertEquals("128k", AudioQualityEvidence.stampedPlaybackQuality("master", "", "MP3", "Content-Type audio/mpeg", -1L, 0L))
        // 魔数判明 FLAC：无损族声称（master）保持声称——容器自证不与声称矛盾。
        assertEquals("master", AudioQualityEvidence.stampedPlaybackQuality("master", "FLAC", "FLAC", "Content-Type audio/flac", 15_208_797L, 128_000L))
        // 魔数判明 FLAC + 有损声称 → 字节实证上修至 flac 为止。
        assertEquals("flac", AudioQualityEvidence.stampedPlaybackQuality("320k", "FLAC", "", "", 15_208_797L, 128_000L))
        // 无格式实证（仅 .flac 扩展名线索=声称方自带数据）→ 不以纯码率推翻无损声称。
        assertEquals("flac", AudioQualityEvidence.stampedPlaybackQuality("flac", "", "FLAC", "URL 扩展名 .flac", 2_062_003L, 128_000L))
        // 无格式实证 + 有损声称 → 同族内可按码率下修（kg FileHash 128k 文件冒充 320k 偏好档）。
        assertEquals("128k", AudioQualityEvidence.stampedPlaybackQuality("320k", "", "", "", 4_000_000L, 250_000L))
        // 证据全无 → 声称原样，不造数据。
        assertEquals("flac", AudioQualityEvidence.stampedPlaybackQuality("flac", "", "", "", -1L, 0L))
        assertEquals("320k", AudioQualityEvidence.stampedPlaybackQuality("320k", "", "", "", -1L, 0L))
    }

    // ACCEPT5（本地直读）：本地文件实测档——路径扩展名给格式线索；无扩展名
    // 线索（SAF content:// 等）返回 null，禁止以纯码率推翻落盘盖章。
    @Test
    fun `measuredFileQuality needs a format hint from the path`() {
        assertEquals("128k", AudioQualityEvidence.measuredFileQuality("/music/雨爱.mp3", 4_000_000L, 260_000L))
        assertEquals("flac", AudioQualityEvidence.measuredFileQuality("/music/song.flac", 900_000L, 260_000L))
        assertEquals(null, AudioQualityEvidence.measuredFileQuality("content://com.android.externalstorage.documents/document/abc", 4_000_000L, 260_000L))
        assertEquals(null, AudioQualityEvidence.measuredFileQuality("/music/雨爱.mp3", 0L, 260_000L))
    }
}
