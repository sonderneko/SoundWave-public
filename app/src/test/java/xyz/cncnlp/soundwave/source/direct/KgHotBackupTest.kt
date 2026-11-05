package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** PB-F1：kg 双端点 1002 热备——1002 识别/短冷却/备路选择/备路字段单位归一。 */
class KgHotBackupTest {

    @Test
    fun `mobile errcode 1002 is rate limited not a dead end`() {
        val raw = """{"errcode":1002,"error":"too frequent","url":""}"""
        assertEquals(KgResolveParse.RateLimited, parseKgMobileResolve(raw))
        // 旧 String 口径下 1002 应答不产出 URL（与既有 parseKgResolve 行为一致）。
        assertEquals(null, parseKgResolve(raw))
    }

    @Test
    fun `mobile success keeps kbps and seconds as-is`() {
        val raw = """{"errcode":0,"url":"http://cdn.kugou.com/a.mp3","bitRate":320,"timeLength":245,"extName":"mp3"}"""
        val found = parseKgMobileResolve(raw) as KgResolveParse.Found
        assertEquals("https://cdn.kugou.com/a.mp3", found.track.url)
        assertEquals(320, found.track.bitrateKbps)
        assertEquals(245, found.track.durationSec)
        assertEquals("mp3", found.track.extName)
    }

    @Test
    fun `mobile other errcode without url is NoUrl not rate limited`() {
        assertEquals(KgResolveParse.NoUrl, parseKgMobileResolve("""{"errcode":20028,"url":""}"""))
        assertEquals(KgResolveParse.NoUrl, parseKgMobileResolve(""))
        assertEquals(KgResolveParse.NoUrl, parseKgMobileResolve("{broken"))
    }

    @Test
    fun `tracker backup normalizes bps and milliseconds to mobile units`() {
        // 实测形态（2026-11-05）：cmd4 应答 bitRate=128000（bps）、timeLength 秒；
        // 毫秒形态同样按 music-lib normalize 口径（>1000 才除 1000）归一。
        val raw = """{"status":1,"url":"http://fs.kugou.com/b.mp3","bitRate":128000,"timeLength":197,"extName":"mp3","fileSize":1014807}"""
        val found = parseKgTrackerResolve(raw) as KgResolveParse.Found
        assertEquals("https://fs.kugou.com/b.mp3", found.track.url)
        assertEquals(128, found.track.bitrateKbps)
        assertEquals(197, found.track.durationSec)
        val msRaw = """{"status":1,"backup_url":["http://fs.kugou.com/c.mp3"],"bitRate":320000,"timeLength":197000}"""
        val msFound = parseKgTrackerResolve(msRaw) as KgResolveParse.Found
        assertEquals("https://fs.kugou.com/c.mp3", msFound.track.url)
        assertEquals(320, msFound.track.bitrateKbps)
        assertEquals(197, msFound.track.durationSec)
    }

    @Test
    fun `tracker nonzero errcode and empty url are NoUrl`() {
        assertEquals(KgResolveParse.NoUrl, parseKgTrackerResolve("""{"errcode":20028,"status":2,"url":""}"""))
        assertEquals(KgResolveParse.NoUrl, parseKgTrackerResolve("""{"status":0,"error":"The Resource Needs to be Paid"}"""))
        assertEquals(KgResolveParse.NoUrl, parseKgTrackerResolve(""))
    }

    @Test
    fun `unit normalization mirrors music-lib thresholds`() {
        assertEquals(128, normalizeKgBitrate(128000))
        assertEquals(320, normalizeKgBitrate(320))
        assertEquals(1000, normalizeKgBitrate(1000))
        assertEquals(197, normalizeKgDurationSec(197000))
        assertEquals(197, normalizeKgDurationSec(197))
    }

    @Test
    fun `tracker backup url is cmd4 https with md5 key of lowercase hash`() {
        val hash = "E933F0EE8FF92EBB9BABB5D3EF5A6EC2"
        val expectedKey = MessageDigest.getInstance("MD5")
            .digest((hash.lowercase() + "kgcloud").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "https://trackercdn.kugou.com/i/?cmd=4&pid=1&forceDown=0&vip=1&hash=${hash.lowercase()}&key=$expectedKey",
            kgTrackerBackupUrl(hash)
        )
        // 大小写不敏感：同一 hash 的大小写形态产出同一备路 URL。
        assertEquals(kgTrackerBackupUrl(hash), kgTrackerBackupUrl(hash.lowercase()))
    }

    @Test
    fun `backup is tried only on rate limit or during cooldown`() {
        assertTrue(kgShouldTryBackup(KgResolveParse.RateLimited, cooling = false))
        assertTrue(kgShouldTryBackup(null, cooling = true))
        assertTrue(kgShouldTryBackup(KgResolveParse.NoUrl, cooling = true))
        assertFalse(kgShouldTryBackup(KgResolveParse.NoUrl, cooling = false))
        assertFalse(kgShouldTryBackup(null, cooling = false))
    }

    @Test
    fun `rate limit cooldown lasts sixty seconds and expires`() {
        val t0 = 1_000_000L
        KgMobileRateLimitCooldown.record(t0)
        try {
            assertTrue(KgMobileRateLimitCooldown.isCooling(t0))
            assertTrue(KgMobileRateLimitCooldown.isCooling(t0 + KG_MOBILE_COOLDOWN_MS - 1))
            assertFalse(KgMobileRateLimitCooldown.isCooling(t0 + KG_MOBILE_COOLDOWN_MS))
            assertEquals(60_000L, KG_MOBILE_COOLDOWN_MS)
            assertEquals(1002, KG_ERRCODE_RATE_LIMITED)
        } finally {
            KgMobileRateLimitCooldown.clearForTests()
        }
    }
}
