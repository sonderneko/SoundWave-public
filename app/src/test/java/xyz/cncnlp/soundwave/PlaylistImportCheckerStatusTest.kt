package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPT5（死链标记生命周期）：状态值编解码 + TTL 判定纯逻辑。
 * - 值格式 "状态@时间戳"；旧版裸值按时间戳 0 读（已过期，进复检/回写纠正通道）；
 * - cross 有效期 7 天（到期 shouldForceCross 失效，原平台在下次实播获得机会）；
 * - ok 有效期 30 天；时钟回拨保守视为新鲜。
 */
class PlaylistImportCheckerStatusTest {

    private val dayMs = 24L * 3600 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun encodeParseRoundTrip() {
        assertEquals("cross@123456789", PlaylistImportChecker.encodeStatusValue("cross", 123456789L))
        assertEquals("cross" to 123456789L, PlaylistImportChecker.parseStatusValue("cross@123456789"))
        assertEquals("ok" to 42L, PlaylistImportChecker.parseStatusValue("ok@42"))
    }

    @Test
    fun legacyBareValuesParseAsEpochZero() {
        assertEquals("cross" to 0L, PlaylistImportChecker.parseStatusValue("cross"))
        assertEquals("ok" to 0L, PlaylistImportChecker.parseStatusValue("ok"))
        // 时间戳损坏同样按 0 读，不抛异常、不丢状态语义。
        assertEquals("cross" to 0L, PlaylistImportChecker.parseStatusValue("cross@abc"))
    }

    @Test
    fun emptyAndMissingValuesParseToNull() {
        assertNull(PlaylistImportChecker.parseStatusValue(null))
        assertNull(PlaylistImportChecker.parseStatusValue(""))
        assertNull(PlaylistImportChecker.parseStatusValue("@123"))
    }

    @Test
    fun crossTtlIsSevenDays() {
        assertTrue(PlaylistImportChecker.statusIsFresh("cross", now - 6 * dayMs, now))
        assertFalse(PlaylistImportChecker.statusIsFresh("cross", now - 8 * dayMs, now))
        // 旧版裸值（时间戳 0）在任何现实时刻都已过期。
        assertFalse(PlaylistImportChecker.statusIsFresh("cross", 0L, now))
    }

    @Test
    fun okTtlIsThirtyDays() {
        assertTrue(PlaylistImportChecker.statusIsFresh("ok", now - 29 * dayMs, now))
        assertFalse(PlaylistImportChecker.statusIsFresh("ok", now - 31 * dayMs, now))
        assertFalse(PlaylistImportChecker.statusIsFresh("ok", 0L, now))
    }

    @Test
    fun forceCrossOnlyForFreshCross() {
        assertTrue(PlaylistImportChecker.forceCrossActive("cross", now - dayMs, now))
        assertFalse(PlaylistImportChecker.forceCrossActive("cross", now - 8 * dayMs, now))
        assertFalse(PlaylistImportChecker.forceCrossActive("ok", now, now))
        assertFalse(PlaylistImportChecker.forceCrossActive("unknown", now, now))
    }

    @Test
    fun clockSkewTreatedAsFresh() {
        val future = now + 3600_000L
        assertTrue(PlaylistImportChecker.statusIsFresh("cross", future, now))
        assertTrue(PlaylistImportChecker.forceCrossActive("cross", future, now))
    }
}
